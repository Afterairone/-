package io.fuguang.clock

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi

object ClockNotifications {
    const val OVERLAY_ID = 101
    private const val COMPLETION_ID = 102
    const val TIMER_AUDIO_ID = 103
    private const val CHECKPOINT_ID = 104
    private const val WALL_POINT_ID = 105
    private const val OVERLAY_CHANNEL = "overlay"
    private const val COMPLETION_CHANNEL = "timer_done"
    private const val AUDIBLE_COMPLETION_CHANNEL = "timer_done_audible"
    private const val TIMER_AUDIO_CHANNEL = "timer_audio"
    private val alarmAudio = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val audioHandler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private val stopSound = Runnable {
        ringtone?.stop()
        ringtone = null
    }

    fun notificationsAllowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val permission = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permission && manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(COMPLETION_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun overlay(context: Context): Notification {
        channels(context)
        val close = PendingIntent.getService(
            context, 0, Intent(context, OverlayService::class.java).setAction(OverlayService.CLOSE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, OVERLAY_CHANNEL)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("悬浮时钟正在显示")
            .setSmallIcon(R.drawable.ic_status_clock)
            .setContentIntent(openApp(context))
            .addAction(Notification.Action.Builder(null, "关闭悬浮窗", close).build())
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    fun alert(context: Context, settings: AlertSettings, timer: TimerState, reached: List<Long>) {
        val finished = timer.phase == Phase.FINISHED
        val checkpointText = reached.joinToString("、", transform = ::durationText)
        val message = when {
            !finished -> "已计时 $checkpointText · 倒计时继续"
            reached.isEmpty() -> "${context.getString(R.string.app_name)} · 本轮倒计时已结束"
            else -> "本轮倒计时已结束 · 已经过提醒点 $checkpointText"
        }
        if (finished) context.getSystemService(NotificationManager::class.java).cancel(CHECKPOINT_ID)
        announce(context, settings, if (finished) COMPLETION_ID else CHECKPOINT_ID,
            if (finished) "时间到" else "提醒点到了", message) {
            TimerAudioService.startForAlert(context, timer)
        }
    }

    fun timerAudio(context: Context): Notification {
        channels(context)
        return Notification.Builder(context, TIMER_AUDIO_CHANNEL)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("倒计时响铃已启用")
            .setSmallIcon(R.drawable.ic_status_clock)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    fun wallPoint(context: Context, settings: AlertSettings) {
        announce(context, settings, WALL_POINT_ID, "时间卡点到了", "${context.getString(R.string.app_name)} · 已到设定的卡点") {
            TimerAudioService.startForWallPoint(context)
        }
    }

    private fun announce(
        context: Context,
        settings: AlertSettings,
        id: Int,
        title: String,
        message: String,
        startAudio: () -> Boolean,
    ) {
        channels(context)
        val playNow = settings.sound && (Build.VERSION.SDK_INT < 37 || TimerAudioService.running)
        val queued = settings.sound && !playNow && startAudio()
        if (settings.notification && notificationsAllowed(context)) {
            // Use the system notification sound if background playback cannot start.
            val channel = if (settings.sound && !playNow && !queued) AUDIBLE_COMPLETION_CHANNEL else COMPLETION_CHANNEL
            val notification = Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_status_clock)
                .setContentTitle(title)
                .setContentText(message)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        }
        if (playNow) playSound(context)
        if (settings.vibration) runCatching { vibrate(context) }
    }

    fun clearCompletion(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(COMPLETION_ID)
        manager.cancel(CHECKPOINT_ID)
        audioHandler.removeCallbacks(stopSound)
        stopSound.run()
    }

    fun playSound(context: Context) {
        runCatching {
            audioHandler.removeCallbacks(stopSound)
            stopSound.run()
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val sound = RingtoneManager.getRingtone(context, uri) ?: return
            sound.audioAttributes = alarmAudio
            if (Build.VERSION.SDK_INT >= 28) sound.isLooping = true
            sound.play()
            ringtone = sound
            audioHandler.postDelayed(stopSound, 5_000L)
        }
    }

    private fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(OVERLAY_CHANNEL, "悬浮窗状态", NotificationManager.IMPORTANCE_LOW),
        )
        val completion = NotificationChannel(COMPLETION_CHANNEL, "倒计时到点", NotificationManager.IMPORTANCE_HIGH)
        completion.setSound(null, null)
        completion.enableVibration(false)
        manager.createNotificationChannel(completion)
        if (Build.VERSION.SDK_INT >= 37) {
            manager.createNotificationChannel(
                NotificationChannel(TIMER_AUDIO_CHANNEL, "倒计时运行状态", NotificationManager.IMPORTANCE_LOW),
            )
            val audible = NotificationChannel(AUDIBLE_COMPLETION_CHANNEL, "倒计时响铃提醒", NotificationManager.IMPORTANCE_HIGH)
            audible.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                alarmAudio,
            )
            audible.enableVibration(false)
            manager.createNotificationChannel(audible)
        }
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context) {
        val vibrator = vibrator(context)
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 250, 140, 350), -1)
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            vibrator.vibrate(effect, alarmAudio)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) systemVibrator(context)
        else context.getSystemService(Vibrator::class.java)

    @RequiresApi(31)
    private fun systemVibrator(context: Context): Vibrator =
        context.getSystemService(VibratorManager::class.java).defaultVibrator
}
