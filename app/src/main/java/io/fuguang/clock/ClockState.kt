package io.fuguang.clock

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

const val DEFAULT_OVERLAY_PERCENT = 110
const val MIN_OVERLAY_PERCENT = 85
const val MAX_OVERLAY_PERCENT = 140
const val DEFAULT_DURATION = 25 * 60_000L
const val MAX_DURATION_SECONDS = 359_999
const val MAX_CHECKPOINTS = 200
private const val ALARM_ACTION = "io.fuguang.clock.TIMER_EXPIRED"

enum class Phase { READY, RUNNING, PAUSED, FINISHED }
enum class ClockMode { CLOCK, TIMER }

data class TimerState(
    val phase: Phase = Phase.READY,
    val duration: Long = DEFAULT_DURATION,
    val savedRemaining: Long = DEFAULT_DURATION,
    val elapsedDeadline: Long = 0,
    val wallDeadline: Long = 0,
    val bootCount: Int = -1,
    val alarmToken: String = "",
    val checkpoints: List<Long> = emptyList(),
    val deliveredCheckpoints: Int = 0,
) {
    val editable: Boolean get() = phase == Phase.READY || phase == Phase.FINISHED

    fun remaining(): Long = when (phase) {
        Phase.RUNNING -> (elapsedDeadline - SystemClock.elapsedRealtime()).coerceIn(0, duration)
        Phase.FINISHED -> 0
        else -> savedRemaining
    }
}

data class AlertSettings(
    val notification: Boolean = true,
    val sound: Boolean = true,
    val vibration: Boolean = false,
)

data class ClockState(
    val timer: TimerState,
    val overlayEnabled: Boolean,
    val overlayAutoOpen: Boolean,
    val overlayMode: ClockMode,
    val overlayPercent: Int,
    val alerts: AlertSettings,
    val wallProgress: WallProgressSettings,
)

data class OverlayPosition(val right: Boolean = true, val vertical: Float = 0f)

class ClockStore private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("clock", Context.MODE_PRIVATE)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val deadlineCheck = Runnable { checkDeadline() }
    private val mutable = MutableStateFlow(
        ClockState(
            timer = readTimer(),
            overlayPercent = prefs.getInt("overlay.percent", DEFAULT_OVERLAY_PERCENT).coerceIn(MIN_OVERLAY_PERCENT, MAX_OVERLAY_PERCENT),
            overlayEnabled = prefs.getBoolean("overlay.enabled", false),
            overlayAutoOpen = prefs.getBoolean("overlay.autoOpen", false),
            overlayMode = if (prefs.getBoolean("overlay.timer", false)) ClockMode.TIMER else ClockMode.CLOCK,
            alerts = AlertSettings(
                prefs.getBoolean("alert.notification", true),
                prefs.getBoolean("alert.sound", true),
                prefs.getBoolean("alert.vibration", false),
            ),
            wallProgress = WallProgressSettings(
                enabled = prefs.getBoolean("wallProgress.enabled", false),
                target = prefs.getLong("wallProgress.target", 0),
                lead = prefs.getLong("wallProgress.lead", 600).coerceIn(0, PROGRESS_WINDOW_MS),
                remind = prefs.getBoolean("wallProgress.remind", false),
            ),
        ),
    )
    val state = mutable.asStateFlow()
    private val wallAlarm = WallProgressAlarm(context, prefs, { state.value.wallProgress }) {
        ClockNotifications.wallPoint(context, state.value.alerts)
    }

    internal fun refreshWallSchedule() = wallAlarm.refresh()

    fun refreshSchedule(afterBoot: Boolean = false) {
        wallAlarm.refresh()
        val timer = state.value.timer
        if (timer.phase != Phase.RUNNING) return
        val bootCount = bootCount()
        if (afterBoot || timer.bootCount != bootCount || timer.elapsedDeadline <= 0) {
            val remaining = (timer.wallDeadline - System.currentTimeMillis()).coerceIn(0, timer.duration)
            saveTimer(timer.copy(elapsedDeadline = SystemClock.elapsedRealtime() + remaining, bootCount = bootCount))
        }
        if (!checkDeadline()) arm(state.value.timer)
    }

    fun setOverlayPercent(percent: Int) {
        val size = percent.coerceIn(MIN_OVERLAY_PERCENT, MAX_OVERLAY_PERCENT)
        if (state.value.overlayPercent == size) return
        prefs.edit().putInt("overlay.percent", size).apply()
        mutable.value = state.value.copy(overlayPercent = size)
    }

    fun exactAlarmsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    fun selectDuration(duration: Long) {
        require(duration in 1_000L..MAX_DURATION_SECONDS * 1_000L)
        val timer = state.value.timer
        if (!timer.editable) return
        replaceTimer(TimerState(duration = duration, savedRemaining = duration,
            checkpoints = timer.checkpoints.filter { it < duration }))
    }

    fun toggleCheckpoint(offset: Long) {
        val timer = state.value.timer
        if (!timer.editable || offset !in 1_000L until timer.duration) return
        val points = timer.checkpoints
        val updated = when {
            offset in points -> points - offset
            points.size < MAX_CHECKPOINTS -> (points + offset).sorted()
            else -> return
        }
        saveTimer(TimerState(duration = timer.duration, savedRemaining = timer.duration, checkpoints = updated))
        ClockNotifications.clearCompletion(context)
    }

    fun startOrResume() {
        val timer = state.value.timer
        if (timer.phase == Phase.RUNNING) return
        val remaining = if (timer.phase == Phase.PAUSED) timer.remaining() else timer.duration
        val next = timer.copy(
            phase = Phase.RUNNING,
            savedRemaining = remaining,
            elapsedDeadline = SystemClock.elapsedRealtime() + remaining,
            wallDeadline = System.currentTimeMillis() + remaining,
            bootCount = bootCount(),
            alarmToken = UUID.randomUUID().toString(),
            deliveredCheckpoints = if (timer.phase == Phase.PAUSED) timer.deliveredCheckpoints else 0,
        )
        if (timer.phase == Phase.PAUSED) saveTimer(next, ClockMode.TIMER) else replaceTimer(next, ClockMode.TIMER)
        arm(next)
    }

    fun pause() {
        if (checkDeadline()) return
        val timer = state.value.timer
        if (timer.phase != Phase.RUNNING) return
        val remaining = timer.remaining()
        if (remaining == 0L) {
            checkDeadline()
            return
        }
        saveTimer(TimerState(Phase.PAUSED, timer.duration, remaining,
            checkpoints = timer.checkpoints, deliveredCheckpoints = timer.deliveredCheckpoints))
        cancel(timer)
    }

    fun reset() {
        val timer = state.value.timer
        replaceTimer(TimerState(duration = timer.duration, savedRemaining = timer.duration, checkpoints = timer.checkpoints))
    }

    fun checkDeadline(token: String? = null): Boolean {
        val timer = state.value.timer
        if (timer.phase != Phase.RUNNING || (token != null && timer.alarmToken != token)) return false
        val remaining = timer.remaining()
        val reached = timer.checkpoints.drop(timer.deliveredCheckpoints).takeWhile { it <= timer.duration - remaining }
        if (remaining > 0 && reached.isEmpty()) {
            scheduleLocalCheck(timer)
            return false
        }

        val delivered = timer.deliveredCheckpoints + reached.size
        if (remaining == 0L) {
            saveTimer(TimerState(Phase.FINISHED, timer.duration, 0,
                checkpoints = timer.checkpoints, deliveredCheckpoints = delivered), ClockMode.TIMER)
            cancel(timer)
        } else {
            val updated = timer.copy(deliveredCheckpoints = delivered)
            saveTimer(updated)
            reached.forEach { cancelAlarm(timer.alarmToken, it) }
            scheduleLocalCheck(updated)
        }
        val current = state.value
        ClockNotifications.alert(context, current.alerts, current.timer, reached)
        return remaining == 0L
    }

    fun setOverlayAutoOpen(enabled: Boolean) {
        prefs.edit().putBoolean("overlay.autoOpen", enabled).apply()
        mutable.value = state.value.copy(overlayAutoOpen = enabled)
    }

    fun setOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("overlay.enabled", enabled).apply()
        mutable.value = state.value.copy(overlayEnabled = enabled)
    }

    fun setOverlayMode(mode: ClockMode) {
        prefs.edit().putBoolean("overlay.timer", mode == ClockMode.TIMER).apply()
        mutable.value = state.value.copy(overlayMode = mode)
    }

    fun setAlerts(alerts: AlertSettings) {
        prefs.edit()
            .putBoolean("alert.notification", alerts.notification)
            .putBoolean("alert.sound", alerts.sound)
            .putBoolean("alert.vibration", alerts.vibration)
            .apply()
        mutable.value = state.value.copy(alerts = alerts)
    }

    @SuppressLint("ApplySharedPref")
    fun setWallProgress(settings: WallProgressSettings) {
        if (state.value.wallProgress == settings) return
        prefs.edit().putBoolean("wallProgress.enabled", settings.enabled)
            .putLong("wallProgress.target", settings.target)
            .putLong("wallProgress.lead", settings.lead)
            .putBoolean("wallProgress.remind", settings.remind)
            .commit()
        mutable.value = state.value.copy(wallProgress = settings)
        wallAlarm.reconfigure()
    }

    fun receiveWallPoint(target: Long) = wallAlarm.receive(target)

    fun position() = OverlayPosition(
        prefs.getBoolean("position.right", true),
        prefs.getFloat("position.vertical", 0f).coerceIn(0f, 1f),
    )

    fun savePosition(position: OverlayPosition) {
        prefs.edit().putBoolean("position.right", position.right)
            .putFloat("position.vertical", position.vertical).apply()
    }

    private fun replaceTimer(timer: TimerState, mode: ClockMode = state.value.overlayMode) {
        val previous = state.value.timer
        saveTimer(timer, mode)
        cancel(previous)
        ClockNotifications.clearCompletion(context)
    }

    @SuppressLint("ApplySharedPref") // Persist the transition before dispatching its system alarm or alert.
    private fun saveTimer(timer: TimerState, mode: ClockMode = state.value.overlayMode) {
        prefs.edit()
            .putString("timer.phase", timer.phase.name)
            .putLong("timer.duration", timer.duration)
            .putLong("timer.remaining", timer.savedRemaining)
            .putLong("timer.elapsed", timer.elapsedDeadline)
            .putLong("timer.wall", timer.wallDeadline)
            .putInt("timer.boot", timer.bootCount)
            .putString("timer.token", timer.alarmToken)
            .putString("timer.checkpoints", timer.checkpoints.joinToString(","))
            .putInt("timer.deliveredCheckpoints", timer.deliveredCheckpoints)
            .putBoolean("overlay.timer", mode == ClockMode.TIMER)
            .commit()
        mutable.value = state.value.copy(timer = timer, overlayMode = mode)
    }

    private fun readTimer(): TimerState {
        val duration = prefs.getLong("timer.duration", DEFAULT_DURATION)
        val checkpoints = prefs.getString("timer.checkpoints", "").orEmpty().split(',')
            .mapNotNull(String::toLongOrNull).filter { it in 1_000L until duration }.distinct().sorted()
        return TimerState(
            phase = Phase.entries.firstOrNull { it.name == prefs.getString("timer.phase", null) } ?: Phase.READY,
            duration = duration,
            savedRemaining = prefs.getLong("timer.remaining", duration),
            elapsedDeadline = prefs.getLong("timer.elapsed", 0),
            wallDeadline = prefs.getLong("timer.wall", 0),
            bootCount = prefs.getInt("timer.boot", -1),
            alarmToken = prefs.getString("timer.token", "").orEmpty(),
            checkpoints = checkpoints,
            deliveredCheckpoints = prefs.getInt("timer.deliveredCheckpoints", 0).coerceIn(0, checkpoints.size),
        )
    }

    private fun arm(timer: TimerState) {
        if (timer.phase != Phase.RUNNING || timer.alarmToken.isEmpty()) return
        val canScheduleExact = exactAlarmsAllowed()
        for (point in timer.checkpoints.drop(timer.deliveredCheckpoints) + timer.duration) {
            val deadline = timer.elapsedDeadline - (timer.duration - point)
            val checkpoint = point.takeUnless { it == timer.duration }
            val exact = canScheduleExact && scheduleExact(timer, checkpoint, deadline)
            // The fallback remains registered if exact alarm access is later revoked.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                deadline + if (exact) 60_000L else 0L,
                checkNotNull(alarmIntent(timer.alarmToken, false, checkpoint)),
            )
        }
        scheduleLocalCheck(timer)
    }

    private fun scheduleExact(timer: TimerState, checkpoint: Long?, deadline: Long): Boolean = try {
        val operation = checkNotNull(alarmIntent(timer.alarmToken, true, checkpoint))
        if (timer.checkpoints.isEmpty()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, operation)
        } else {
            // Adjacent reminders must stay independent of the while-idle alarm rate limit.
            val wallTime = System.currentTimeMillis() + (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            val info = AlarmManager.AlarmClockInfo(wallTime, ClockNotifications.openApp(context))
            alarmManager.setAlarmClock(info, operation)
        }
        true
    } catch (_: SecurityException) {
        false
    }

    private fun scheduleLocalCheck(timer: TimerState) {
        val next = timer.checkpoints.getOrNull(timer.deliveredCheckpoints) ?: timer.duration
        val delay = (timer.remaining() - (timer.duration - next)).coerceAtLeast(0)
        handler.removeCallbacks(deadlineCheck)
        handler.postDelayed(deadlineCheck, delay)
    }

    private fun cancel(timer: TimerState) {
        handler.removeCallbacks(deadlineCheck)
        if (timer.alarmToken.isEmpty()) return
        timer.checkpoints.drop(timer.deliveredCheckpoints).forEach { cancelAlarm(timer.alarmToken, it) }
        cancelAlarm(timer.alarmToken, null)
    }

    private fun cancelAlarm(token: String, checkpoint: Long?) {
        for (exact in listOf(true, false)) {
            val pending = alarmIntent(token, exact, checkpoint, PendingIntent.FLAG_NO_CREATE) ?: continue
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    private fun alarmIntent(token: String, exact: Boolean, checkpoint: Long?, flags: Int = 0): PendingIntent? {
        val route = if (exact) "exact" else "fallback"
        val intent = Intent(context, TimerReceiver::class.java).apply {
            action = ALARM_ACTION
            data = Uri.parse("fuguang://timer/$route/$token").buildUpon().apply {
                if (checkpoint != null) appendQueryParameter("point", checkpoint.toString())
            }.build()
        }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or flags)
    }

    private fun bootCount(): Int = Settings.Global.getInt(context.contentResolver, "boot_count", -1)

    companion object {
        @SuppressLint("StaticFieldLeak") // The store only holds the application context.
        private var instance: ClockStore? = null

        // Activities, the overlay service and broadcasts all access the store on the main thread.
        fun get(context: Context): ClockStore {
            CalibratedTime.install(context.applicationContext)
            return instance ?: ClockStore(context.applicationContext).also { instance = it }
        }
    }
}

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = ClockStore.get(context)
        when (intent.action) {
            ALARM_ACTION -> intent.data?.lastPathSegment?.let { store.checkDeadline(it) }
            WALL_POINT_ACTION -> intent.data?.lastPathSegment?.toLongOrNull()?.let { store.receiveWallPoint(it) }
            Intent.ACTION_BOOT_COMPLETED -> store.refreshSchedule(afterBoot = true)
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> store.refreshSchedule()
        }
    }
}

private val wallClockFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

internal fun wallClockText(now: Long?, zone: java.time.ZoneId): String =
    now?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).format(wallClockFormat) } ?: "--:--:--"

fun durationText(milliseconds: Long): String {
    val seconds = (milliseconds + 999) / 1000
    val hours = seconds / 3600
    val minutes = seconds / 60 % 60
    return if (hours > 0) String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds % 60)
}
