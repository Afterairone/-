package io.fuguang.clock

import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: ClockStore
    private var overlay: FloatingWindow? = null

    override fun onCreate() {
        super.onCreate()
        store = ClockStore.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CLOSE || !store.state.value.overlayEnabled || !Settings.canDrawOverlays(this)) {
            close()
            return START_NOT_STICKY
        }
        if (overlay != null) return START_STICKY
        startForeground(ClockNotifications.OVERLAY_ID, ClockNotifications.overlay(this))
        store.refreshSchedule()

        val shown = FloatingWindow(this, store, ::close)
        try {
            shown.show()
        } catch (_: SecurityException) {
            shown.dismiss()
            close()
            return START_NOT_STICKY
        } catch (_: android.view.WindowManager.BadTokenException) {
            shown.dismiss()
            close()
            return START_NOT_STICKY
        }
        overlay = shown
        scope.launch {
            store.state.collect { state ->
                if (state.overlayEnabled) shown.onState(state) else close()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.reposition()
    }

    private fun close() {
        store.setOverlayEnabled(false)
        overlay?.dismiss()
        overlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        overlay?.dismiss()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CLOSE = "io.fuguang.clock.CLOSE_OVERLAY"
    }
}
