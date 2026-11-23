package io.fuguang.clock

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TimerAudioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store by lazy { ClockStore.get(this) }
    private var stateJob: Job? = null
    private var wallSoundUntil = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ClockNotifications.TIMER_AUDIO_ID, ClockNotifications.timerAudio(this))
        running = true
        val state = store.state.value
        val playWall = intent?.action == PLAY_WALL && state.wallProgress.enabled &&
            state.wallProgress.remind && state.alerts.sound
        if (playWall) {
            wallSoundUntil = SystemClock.elapsedRealtime() + 5_500
            ClockNotifications.playSound(this)
            stateJob?.cancel()
            stateJob = null
        }
        if (stateJob == null) {
            stateJob = scope.launch {
                store.state.collectLatest { state ->
                    if (state.alerts.sound && state.timer.phase == Phase.RUNNING) return@collectLatest
                    if (state.alerts.sound && state.timer.phase == Phase.FINISHED) delay(5_500)
                    val wallLeft = wallSoundUntil - SystemClock.elapsedRealtime()
                    if (wallLeft > 0L) delay(wallLeft)
                    running = false
                    stopSelf()
                }
            }
        }
        if (intent?.action == PLAY && state.alerts.sound &&
            intent.getStringExtra("phase") == state.timer.phase.name &&
            intent.getStringExtra("token") == state.timer.alarmToken &&
            intent.getIntExtra("points", -1) == state.timer.deliveredCheckpoints
        ) {
            ClockNotifications.playSound(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val PLAY = "io.fuguang.clock.PLAY_TIMER_ALERT"
        private const val PLAY_WALL = "io.fuguang.clock.PLAY_WALL_POINT"
        var running = false
            private set

        fun prepare(context: Context, state: ClockState) {
            if (Build.VERSION.SDK_INT >= 37 && !running && state.alerts.sound && state.timer.phase == Phase.RUNNING) {
                start(context, Intent(context, TimerAudioService::class.java))
            }
        }

        fun startForAlert(context: Context, timer: TimerState): Boolean =
            start(context, Intent(context, TimerAudioService::class.java).setAction(PLAY)
                .putExtra("phase", timer.phase.name)
                .putExtra("token", timer.alarmToken)
                .putExtra("points", timer.deliveredCheckpoints))

        fun startForWallPoint(context: Context): Boolean =
            start(context, Intent(context, TimerAudioService::class.java).setAction(PLAY_WALL))

        private fun start(context: Context, intent: Intent): Boolean = runCatching {
            context.startForegroundService(intent)
            true
        }.getOrDefault(false)
    }
}

