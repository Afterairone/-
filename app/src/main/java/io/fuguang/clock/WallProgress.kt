package io.fuguang.clock

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

const val PROGRESS_WINDOW_MS = 3_000L
const val WALL_POINT_ACTION = "io.fuguang.clock.WALL_POINT"
private const val MINUTE_MS = 60_000L

data class WallProgressSettings(
    val enabled: Boolean = false,
    val target: Long = 0,
    val lead: Long = 600,
    val remind: Boolean = false,
) {
    val marker: Float get() = 1f - lead.toFloat() / PROGRESS_WINDOW_MS

    fun targetAfter(now: Long): Long = if (target != 0L) target else now - Math.floorMod(now, MINUTE_MS) + MINUTE_MS

    fun progress(now: Long): Float {
        if (!enabled) return -1f
        val remaining = targetAfter(now) - now
        return if (remaining in 0..PROGRESS_WINDOW_MS) 1f - remaining.toFloat() / PROGRESS_WINDOW_MS else -1f
    }

    fun highlights(now: Long): Boolean {
        if (!enabled) return false
        val elapsed = now - (targetAfter(now) - lead)
        return elapsed in 0..350 || (target == 0L && elapsed + MINUTE_MS in 0..350)
    }
}

internal fun nextWallTarget(time: LocalTime, now: Long, zone: ZoneId): Long {
    val local = Instant.ofEpochMilli(now).atZone(zone)
    val today = local.toLocalDate().atTime(time).atZone(local.zone)
    val next = if (today.toInstant().toEpochMilli() > now) today
        else local.toLocalDate().plusDays(1).atTime(time).atZone(local.zone)
    return next.toInstant().toEpochMilli()
}

internal class WallProgressAlarm(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val settings: () -> WallProgressSettings,
    private val alert: () -> Unit,
) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { receive() }

    fun reconfigure() {
        cancel()
        prefs.edit().remove("wallProgress.delivered").apply()
        refresh()
    }

    fun refresh() {
        val config = settings()
        if (!config.enabled || !config.remind || CalibratedTime.now() == null) {
            cancel()
            return
        }
        val armed = prefs.getLong("wallProgress.armed", 0)
        if (armed != 0L) {
            receive(armed)
            return
        }
        val now = CalibratedTime.now() ?: return
        val target = config.targetAfter(now)
        val delivered = prefs.getLong("wallProgress.delivered", 0)
        if (config.target != 0L && delivered == target) return
        val skipCurrent = config.target == 0L && (target == delivered || now > target - config.lead)
        arm(if (skipCurrent) target + MINUTE_MS else target, config)
    }

    @SuppressLint("ApplySharedPref")
    fun receive(expected: Long? = null) {
        val armed = prefs.getLong("wallProgress.armed", 0)
        if (armed == 0L || (expected != null && expected != armed)) return
        val config = settings()
        val now = CalibratedTime.now() ?: return
        val trigger = armed - config.lead
        if (now < trigger) {
            arm(armed, config)
            return
        }

        val currentTarget = if (config.target == 0L) {
            Math.floorMod(armed, MINUTE_MS) == 0L && now - trigger < MINUTE_MS
        } else {
            config.target == armed
        }
        val deliver = config.enabled && config.remind && currentTarget &&
            prefs.getLong("wallProgress.delivered", 0) != armed
        prefs.edit().remove("wallProgress.armed").putLong("wallProgress.delivered", armed).commit()
        cancelAlarms(armed)
        if (deliver) alert()
        if (config.enabled && config.remind && config.target == 0L) {
            arm(maxOf(armed + MINUTE_MS, config.targetAfter(now)), config)
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun arm(target: Long, config: WallProgressSettings) {
        if (prefs.getLong("wallProgress.armed", 0) != target) {
            prefs.edit().putLong("wallProgress.armed", target).commit()
        }
        val now = CalibratedTime.now() ?: return
        val delay = (target - config.lead - now).coerceAtLeast(0)
        val exact = (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) && try {
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(System.currentTimeMillis() + delay, ClockNotifications.openApp(context)),
                checkNotNull(intent(target, exact = true)),
            )
            true
        } catch (_: SecurityException) {
            false
        }
        manager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay + if (exact) 60_000L else 0L,
            checkNotNull(intent(target, exact = false)),
        )
        handler.removeCallbacks(check)
        handler.postDelayed(check, delay)
    }

    private fun cancel() {
        handler.removeCallbacks(check)
        val previous = prefs.getLong("wallProgress.armed", 0)
        if (previous != 0L) {
            prefs.edit().remove("wallProgress.armed").apply()
            cancelAlarms(previous)
        }
    }

    private fun cancelAlarms(target: Long) {
        for (exact in listOf(true, false)) {
            val pending = intent(target, exact, PendingIntent.FLAG_NO_CREATE) ?: continue
            manager.cancel(pending)
            pending.cancel()
        }
    }

    private fun intent(target: Long, exact: Boolean, flags: Int = 0): PendingIntent? {
        val intent = Intent(context, TimerReceiver::class.java)
            .setAction(WALL_POINT_ACTION)
            .setData(Uri.parse("fuguang://wall/$exact/$target"))
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or flags)
    }
}
