package io.fuguang.clock.tap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId
import kotlin.math.roundToInt

internal data class DisplayGeometry(val width: Int, val height: Int, val rotation: Int)
internal data class TapPoint(val x: Int, val y: Int, val display: DisplayGeometry) {
    companion object {
        fun load(context: Context): TapPoint? {
            val prefs = context.getSharedPreferences("timed_tap", Context.MODE_PRIVATE)
            if (!prefs.contains("x")) return null
            val display = DisplayGeometry(prefs.getInt("width", 0), prefs.getInt("height", 0), prefs.getInt("rotation", -1))
            val x = prefs.getInt("x", -1)
            val y = prefs.getInt("y", -1)
            return if (x in 0 until display.width && y in 0 until display.height) TapPoint(x, y, display) else null
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences("timed_tap", Context.MODE_PRIVATE).edit()
            .putInt("x", x).putInt("y", y).putInt("width", display.width).putInt("height", display.height)
            .putInt("rotation", display.rotation).apply()
    }
}

internal data class ScheduledTap(val due: Long, val source: TimeSource, val zone: ZoneId)
internal data class TapStatus(val scheduled: ScheduledTap? = null, val message: String = "", val dispatching: Boolean = false)

class TimedTapService : AccessibilityService() {
    private val timingThread = HandlerThread("fuguang-tap", Process.THREAD_PRIORITY_DISPLAY)
    private lateinit var timingHandler: Handler
    private val taskLock = Any()
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var picker: View? = null
    @Volatile private var pending: Runnable? = null
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (pending != null) cancel("屏幕已关闭，本次点击已取消")
            removePicker()
        }
    }

    override fun onCreate() {
        super.onCreate()
        timingThread.start()
        timingHandler = Handler(timingThread.looper)
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, filter)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        cancel("无障碍服务已断开，本次点击已取消")
        if (connected.value === this) connected.value = null
        if (status.value.dispatching) status.value = TapStatus(message = "无障碍服务已断开，点击结果未知")
        removePicker()
        unregisterReceiver(screenOff)
        timingThread.quitSafely()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removePicker()
    }

    @Suppress("DEPRECATION")
    private fun geometry(): DisplayGeometry {
        val display = windows.defaultDisplay
        val size = Point().also(display::getRealSize)
        return DisplayGeometry(size.x, size.y, display.rotation)
    }

    internal fun showPickerDock(): Boolean {
        if (pending != null || status.value.dispatching) return false
        removePicker()
        val dock = LinearLayout(this).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xff392a33.toInt())
            }
            addView(pickerButton("拾取坐标") { showCapture() })
            addView(pickerButton("取消") { removePicker() })
        }
        return showPicker(dock, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, false)
    }

    private fun showCapture() {
        removePicker()
        val capture = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) removePicker()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(0x18000000)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val x = event.rawX.toInt()
                    val y = event.rawY.toInt()
                    val display = geometry()
                    if (x in 0 until display.width && y in 0 until display.height) {
                        TapPoint(x, y, display).also { point ->
                            point.save(this@TimedTapService)
                            selection.value = point
                        }
                        removePicker()
                        Toast.makeText(this@TimedTapService, "已保存坐标 ($x, $y)", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            addView(pickerButton("取消拾取") { removePicker() }, FrameLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, dp(44), Gravity.TOP or Gravity.END
            ).apply { topMargin = dp(36); marginEnd = dp(12) })
        }
        if (!showPicker(capture, -1, -1, Gravity.TOP or Gravity.LEFT, true)) {
            Toast.makeText(this, "无法打开坐标拾取层", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickerButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xff604252.toInt())
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    private fun showPicker(view: View, width: Int, height: Int, gravity: Int, focusable: Boolean): Boolean {
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            if (focusable) WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN else
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity
            if (!focusable) y = dp(20)
            title = "浮光时钟坐标拾取"
        }
        return runCatching { windows.addView(view, params); picker = view }.isSuccess
    }

    private fun removePicker() {
        val view = picker ?: return
        picker = null
        runCatching { windows.removeViewImmediate(view) }
    }

    internal fun arm(point: TapPoint, clock: NtpClock, scheduled: ScheduledTap): String? {
        if (pending != null || status.value.dispatching) return "已有任务"
        if (point.display != geometry()) return "屏幕方向或分辨率已变化，请重新拾取坐标"
        if (!canTouch()) return "请保持屏幕亮起并解锁"
        val deadline = clock.deadlineNanos(scheduled.due)
        if (SystemClock.elapsedRealtimeNanos() >= deadline) return "目标时间已过，请重新设置"
        removePicker()
        val path = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 5L)).build()
        val job = object : Runnable {
            override fun run() {
                if (pending !== this) return
                val remainingNanos = deadline - SystemClock.elapsedRealtimeNanos()
                if (remainingNanos > 0L) {
                    // The handler can only wait in whole milliseconds. Always check the
                    // monotonic deadline again rather than rounding down and tapping early.
                    timingHandler.postDelayed(this, (remainingNanos + 999_999L) / NANOS_PER_MILLI)
                    return
                }
                val error = when {
                    !canTouch() -> "屏幕未解锁，本次点击已取消"
                    point.display != geometry() -> "屏幕方向或分辨率已变化，本次点击已取消"
                    else -> null
                }
                synchronized(taskLock) {
                    if (pending !== this) return
                    pending = null
                    if (error != null) {
                        status.value = TapStatus(message = error)
                        return
                    }
                    status.value = TapStatus(message = "系统正在执行单击", dispatching = true)
                }
                submit(gesture, deadline)
            }
        }
        synchronized(taskLock) {
            pending = job
            status.value = TapStatus(scheduled = scheduled)
            timingHandler.post(job)
        }
        return null
    }

    private fun canTouch(): Boolean = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    internal fun cancel(message: String = "已取消本次点击") {
        synchronized(taskLock) {
            val job = pending ?: return
            pending = null
            timingHandler.removeCallbacks(job)
            status.value = TapStatus(message = message)
        }
    }

    private fun submit(gesture: GestureDescription, deadline: Long) {
        if (connected.value !== this) return
        var submittedAt = 0L
        var latenessNanos = 0L
        val result = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                if (connected.value !== this@TimedTapService) return
                val elapsed = (SystemClock.elapsedRealtimeNanos() - submittedAt) / NANOS_PER_MILLI
                status.value = TapStatus(
                    message = "单击完成 · 提交晚 ${latenessNanos / NANOS_PER_MILLI} ms · 系统回报 ${elapsed} ms",
                )
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                if (connected.value === this@TimedTapService) status.value = TapStatus(message = "系统取消了本次点击")
            }
        }
        submittedAt = SystemClock.elapsedRealtimeNanos()
        latenessNanos = (submittedAt - deadline).coerceAtLeast(0)
        if (latenessNanos >= NANOS_PER_MILLI * 1_000L) {
            status.value = TapStatus(message = "系统调度晚 ${latenessNanos / NANOS_PER_MILLI} ms，本次点击已取消")
            return
        }
        if (!runCatching { dispatchGesture(gesture, result, timingHandler) }.getOrDefault(false)) {
            if (connected.value === this) status.value = TapStatus(message = "系统拒绝了本次点击")
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        private val connected = MutableStateFlow<TimedTapService?>(null)
        internal val connection = connected.asStateFlow()
        internal val selection = MutableStateFlow<TapPoint?>(null)
        internal val status = MutableStateFlow(TapStatus())
    }
}
