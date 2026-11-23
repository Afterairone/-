package io.fuguang.clock

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fuguang.clock.tap.TimeSource

class MainActivity : ComponentActivity() {
    private val store by lazy { ClockStore.get(this) }
    private var awaitingOverlay = false
    private var enteringForeground = false
    private var timeSource by mutableStateOf(TimeSource.UTC)
    private var overlayAllowed by mutableStateOf(false)
    private var exactAllowed by mutableStateOf(false)
    private var notificationAllowed by mutableStateOf(false)
    private var hiddenFromRecents by mutableStateOf(false)

    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (awaitingOverlay) {
            awaitingOverlay = false
            updatePermissions()
            if (overlayAllowed) startOverlay() else showMessage("尚未授予悬浮窗权限")
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updatePermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingOverlay = savedInstanceState?.getBoolean("awaitingOverlay") ?: false
        val tapPreferences = getSharedPreferences("timed_tap", MODE_PRIVATE)
        CalibratedTime.install(applicationContext)
        timeSource = CalibratedTime.source
        // Match the window to the first frame before it is drawn, so the launch does not flash
        // the other theme's background.
        window.setBackgroundDrawable(ColorDrawable(if (BuildConfig.FLAT_UI) 0xFFF3F3F4.toInt() else 0xFFFFF7FA.toInt()))
        enableEdgeToEdge(SystemBarStyle.light(0, 0), SystemBarStyle.light(0, 0))
        // The screen is the app's main surface: hold the panel at its highest rate while it is
        // in front, and let the platform know the frame cadence the UI is authored for. Release
        // builds force the fast display mode because that is the only mode that delivers 120 Hz
        // end to end; it happens during launch, behind the splash, so it cannot stutter the UI.
        window.preferSmoothMotion(forceDisplayMode = !BuildConfig.DEBUG && !BuildConfig.FLAT_UI)
        if (getPreferences(MODE_PRIVATE).getBoolean("hideFromRecents", false)) {
            window.decorView.post {
                if (!isFinishing && !isDestroyed) setRecentsHidden(true)
            }
        }
        // Permissions and alarms are refreshed in onResume, which lands before the first frame.
        // Doing the same binder work in onCreate only delays that first frame.
        setContent {
            val state by store.state.collectAsStateWithLifecycle()
            ClockScreen(
                state = state,
                timeSource = timeSource,
                onTimeSource = { source ->
                    tapPreferences.edit().putString("timeSource", source.name).apply()
                    timeSource = source
                    CalibratedTime.select(source)
                    store.refreshWallSchedule()
                },
                overlayAllowed = overlayAllowed,
                exactAllowed = exactAllowed,
                notificationAllowed = notificationAllowed,
                hiddenFromRecents = hiddenFromRecents,
                onRecentsHidden = ::setRecentsHidden,
                onOverlaySize = store::setOverlayPercent,
                onOverlayAutoOpen = store::setOverlayAutoOpen,
                onOverlay = ::setOverlay,
                onExactSettings = ::openExactSettings,
                onNotificationSettings = ::requestNotifications,
                onDuration = store::selectDuration,
                onCheckpoint = store::toggleCheckpoint,
                onWallProgress = { progress ->
                    store.setWallProgress(progress)
                    if (progress.enabled && progress.remind && store.state.value.alerts.notification &&
                        !notificationAllowed && Build.VERSION.SDK_INT >= 33 &&
                        !permissionPrefs().getBoolean("notificationAsked", false)
                    ) requestNotifications()
                },
                onTimerAction = ::timerAction,
                onReset = store::reset,
                onAlertSettings = { alerts ->
                    val enableNotification = alerts.notification && !store.state.value.alerts.notification
                    store.setAlerts(alerts)
                    TimerAudioService.prepare(this, store.state.value)
                    if (enableNotification && !notificationAllowed) requestNotifications()
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        enteringForeground = true
    }

    override fun onResume() {
        super.onResume()
        updatePermissions()
        store.refreshSchedule()
        TimerAudioService.prepare(this, store.state.value)
        val state = store.state.value
        val autoOpen = enteringForeground && state.overlayAutoOpen
        enteringForeground = false
        if (!awaitingOverlay) {
            if (overlayAllowed && (state.overlayEnabled || autoOpen)) startOverlay()
            else if (!overlayAllowed && state.overlayEnabled) setOverlay(false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaitingOverlay", awaitingOverlay)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION") // RecentTaskInfo.taskId is available from Android 10.
    private fun setRecentsHidden(hidden: Boolean) {
        val applied = runCatching {
            val manager = getSystemService(ActivityManager::class.java)
            val task = manager.appTasks.firstOrNull { appTask ->
                val info = appTask.taskInfo ?: return@firstOrNull false
                val id = if (Build.VERSION.SDK_INT >= 29) info.taskId else info.persistentId
                id == taskId
            } ?: return@runCatching false
            task.setExcludeFromRecents(hidden)
            true
        }.getOrDefault(false)
        if (applied) {
            hiddenFromRecents = hidden
            getPreferences(MODE_PRIVATE).edit().putBoolean("hideFromRecents", hidden).apply()
        } else {
            showMessage("当前无法更改最近任务显示")
        }
    }

    private fun setOverlay(enabled: Boolean) {
        if (!enabled) {
            store.setOverlayEnabled(false)
            stopService(Intent(this, OverlayService::class.java))
            return
        }
        updatePermissions()
        if (overlayAllowed) {
            startOverlay()
            return
        }
        awaitingOverlay = true
        val launch = runCatching {
            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))
        }.recoverCatching {
            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
        if (launch.isFailure) {
            awaitingOverlay = false
            showMessage("无法打开悬浮窗权限设置")
        }
    }

    private fun startOverlay() {
        store.setOverlayEnabled(true)
        try {
            startForegroundService(Intent(this, OverlayService::class.java))
        } catch (_: IllegalStateException) {
            store.setOverlayEnabled(false)
            showMessage("暂时无法启动悬浮窗，请重试")
        } catch (_: SecurityException) {
            store.setOverlayEnabled(false)
            updatePermissions()
            showMessage("系统未允许显示悬浮窗")
        }
    }

    private fun timerAction() {
        if (store.state.value.timer.phase == Phase.RUNNING) {
            store.pause()
        } else {
            store.startOrResume()
            TimerAudioService.prepare(this, store.state.value)
            if (store.state.value.alerts.notification && !notificationAllowed &&
                Build.VERSION.SDK_INT >= 33 && !permissionPrefs().getBoolean("notificationAsked", false)
            ) requestNotifications()
        }
    }

    private fun openExactSettings() {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri())) }
            .onFailure { showMessage("无法打开精确提醒设置") }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            (!permissionPrefs().getBoolean("notificationAsked", false) ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            permissionPrefs().edit().putBoolean("notificationAsked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }.onFailure { showMessage("无法打开通知设置") }
        }
    }

    private fun updatePermissions() {
        overlayAllowed = Settings.canDrawOverlays(this)
        exactAllowed = store.exactAlarmsAllowed()
        notificationAllowed = ClockNotifications.notificationsAllowed(this)
    }

    private fun permissionPrefs() = getSharedPreferences("permission_ui", MODE_PRIVATE)
    private fun packageUri() = Uri.fromParts("package", packageName, null)
    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
