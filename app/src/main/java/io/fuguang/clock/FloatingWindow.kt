package io.fuguang.clock

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class FloatingWindow(
    private val serviceContext: Context,
    private val store: ClockStore,
    private val close: () -> Unit,
) {
    private val context = overlayContext(serviceContext)
    private val manager = context.getSystemService(WindowManager::class.java)
    private val dialog = Dialog(context, R.style.OverlayTheme)
    private val window = checkNotNull(dialog.window)
    private var scaleFactor = store.state.value.overlayPercent / 100f
    private var wantedPercent = store.state.value.overlayPercent
    private var cachedBounds = Rect()
    private val handler = Handler(Looper.getMainLooper())
    private val surface = ClockSurface(dialog.context)
    private val header = object : FrameLayout(dialog.context) {
        override fun performClick(): Boolean = super.performClick()
    }
    private val readout = RollingReadout(dialog.context)
    private val panel = LinearLayout(dialog.context)
    private val modeButton = actionButton { swapMode() }
    private val timerButton = actionButton { timerAction() }
    private val closeButton = actionButton { close() }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var position = store.position()
    private var expanded = false
    private val power = context.getSystemService(PowerManager::class.java)

    // Every moving part is a spring on the same frame pump, so a gesture that interrupts an
    // animation inherits its velocity instead of restarting from a fixed curve.
    private val openness = SpringFloat(0f, stiffness = 560f, dampingRatio = .88f)
    private val press = SpringFloat(0f, stiffness = 1500f, dampingRatio = .62f)
    private val stretch = SpringFloat(0f, stiffness = 900f, dampingRatio = .80f)
    private var snap: SpringFloat? = null
    private var snapFrom = 0f
    private var snapTo = 0f
    private var snapY = 0
    private var morphing = false
    private var morphPending = false
    private val shine = Envelope(durationMs = 540, peakAt = .16f)
    private var lastPhase: Phase? = null
    private var shown = false
    private var permissionCheckAt = 0L
    private var readoutIsClock = false
    private var wallMillis: Long? = null
    private var lastCalibrationRevision = -1L
    private var lastTextBucket = Long.MIN_VALUE
    private var lastFrameNanos = 0L
    private var dragging = false
    private val pulse = FramePulse(::onFrame)
    private val wake = Runnable { if (shown) pulse.start() }

    fun show() {
        refreshBounds()
        buildContents()
        layOutControls()
        dialog.setCancelable(false)
        dialog.setContentView(surface)
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setGravity(Gravity.TOP or Gravity.LEFT)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val attributes = window.attributes
        attributes.setTitle("浮光时钟悬浮窗")
        if (Build.VERSION.SDK_INT >= 34) attributes.setCanPlayMoveAnimation(false)
        if (Build.VERSION.SDK_INT >= 30) {
            attributes.setFitInsetsTypes(0)
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.attributes = attributes
        window.setElevation(0f)
        window.preferSmoothMotion()

        applyGeometry(compactWidth(), compactHeight())
        dialog.show()
        shown = true
        applyGeometry(compactWidth(), compactHeight())
        // The decor view only exists once the window is attached; ask for the panel rate again now
        // so the surface carries the request for its whole lifetime.
        window.preferSmoothMotion()
        pulse.start()
    }

    fun dismiss() {
        shown = false
        morphing = false
        morphPending = false
        snap = null
        pulse.stop()
        handler.removeCallbacksAndMessages(null)
        shine.cancel()
        if (dialog.isShowing) dialog.dismiss()
    }

    fun onState(state: ClockState) {
        render(state)
        if (shown) pulse.start()
    }

    fun reposition() {
        if (!shown) return
        refreshBounds()
        layOutControls()
        surface.invalidateOutline()
        cancelMotion()
        settlePanel()
        header.post { if (shown) applyGeometry(targetWidth(), targetHeight()) }
    }

    /**
     * The single frame step for the overlay. Values are recomputed from the frame's own timestamp
     * immediately before the surface draws, so text, bar and morph always describe the same instant.
     */
    private fun onFrame(frameNanos: Long, deltaNanos: Long) {
        if (!shown) {
            pulse.stop()
            return
        }
        lastFrameNanos = frameNanos
        if (!power.isInteractive) {
            pulse.stop()
            handler.removeCallbacks(wake)
            handler.postDelayed(wake, 250)
            return
        }
        val dt = if (deltaNanos <= 0L) 1f / 60f else (deltaNanos / 1_000_000_000f).coerceIn(1f / 240f, 1f / 30f)

        val state = store.state.value
        val uptime = SystemClock.uptimeMillis()
        if (uptime >= permissionCheckAt) {
            permissionCheckAt = uptime + 1_000
            if (!Settings.canDrawOverlays(serviceContext)) {
                close()
                return
            }
            if (state.timer.phase == Phase.RUNNING) store.checkDeadline()
        }

        val clock = state.overlayMode == ClockMode.CLOCK
        val remaining = state.timer.remaining()
        val running = state.timer.phase == Phase.RUNNING
        val clockNow = if (clock) CalibratedTime.now() else null
        val calibrationRevision = CalibratedTime.revision.value
        val bucket = if (clock) clockNow?.div(if (state.wallProgress.enabled) 100L else 1_000L) ?: Long.MIN_VALUE
            else (remaining + 999) / 1000
        val progress = if (clock) clockNow?.let(state.wallProgress::progress) ?: -1f
            else remaining.toFloat() / state.timer.duration
        wallMillis = clockNow
        if (bucket != lastTextBucket || (clock && calibrationRevision != lastCalibrationRevision) ||
            (surface.progress >= 0f) != (progress >= 0f)) {
            render(state, clockNow)
            lastTextBucket = bucket
            lastCalibrationRevision = calibrationRevision
        }

        surface.progress = progress
        surface.sweep = clockNow?.let { Math.floorMod(it, 60_000L) / 60_000f } ?: -1f

        var animating = false
        if (morphing) {
            if (openness.step(dt)) {
                applyOpenness()
                animating = true
            } else {
                morphing = false
                settlePanel()
            }
        }
        if (press.step(dt) or stretch.step(dt)) {
            applyScale()
            animating = true
        }
        snap?.let { spring ->
            val moving = spring.step(dt)
            val attrs = window.attributes
            val x = (snapFrom + (snapTo - snapFrom) * spring.value).roundToInt()
            applyGeometry(attrs.width, attrs.height, x, snapY)
            if (!moving) {
                snap = null
                applyGeometry(attrs.width, attrs.height, snapTo.roundToInt(), snapY)
            }
            animating = true
        }
        if (shine.isActive) {
            surface.shine = shine.value(frameNanos)
            animating = true
        }

        val rolling = readout.isRolling(frameNanos)
        if (rolling || clockNow != null) readout.invalidate()
        if (animating || clock || running || rolling) surface.invalidate()

        if (!animating && !clock && !running && !rolling) {
            pulse.stop()
            handler.removeCallbacks(wake)
            handler.postDelayed(wake, 1_000L)
        }
    }

    private fun applyScale() {
        val pinch = 1f - .024f * press.value
        val x = pinch * (1f + stretch.value)
        val y = pinch * (1f - stretch.value)
        if (abs(surface.scaleX - x) > .0002f || abs(surface.scaleY - y) > .0002f) {
            surface.scaleX = x
            surface.scaleY = y
        }
    }

    private fun render(state: ClockState, clockNow: Long? = CalibratedTime.now()) {
        if (wantedPercent != state.overlayPercent) {
            wantedPercent = state.overlayPercent
            reposition()
        }
        val timer = state.timer
        val clock = state.overlayMode == ClockMode.CLOCK
        val remaining = timer.remaining()
        val finished = !clock && timer.phase == Phase.FINISHED
        if (finished && lastPhase != null && lastPhase != Phase.FINISHED &&
            ValueAnimator.areAnimatorsEnabled()
        ) {
            shine.start(if (lastFrameNanos != 0L) lastFrameNanos else System.nanoTime())
            pulse.start()
        }
        lastPhase = timer.phase
        val wall = state.wallProgress
        wallMillis = clockNow.takeIf { clock }
        val wallPosition = if (clock) clockNow?.let(wall::progress) ?: -1f else -1f
        val highlight = clock && clockNow != null && wall.highlights(clockNow)
        val tint = if (finished || wallPosition >= 0f || highlight) 0xFFF2BBD5.toInt() else 0xFFE6D6E0.toInt()
        val text = when {
            clock -> wallClockText(clockNow, CalibratedTime.zone())
            finished -> "时间到"
            else -> durationText(remaining)
        }
        if (readout.text.toString() != text) readout.change(text)
        if (readoutIsClock != clock) {
            readoutIsClock = clock
            readout.textSize = (if (clock) 18f else 22f) * scaleFactor
        }
        val face = if (finished) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
        if (readout.typeface != face) readout.typeface = face
        val textColor = if (finished || highlight) tint else Color.WHITE
        if (readout.currentTextColor != textColor) readout.setTextColor(textColor)
        val description = buildString {
            append(if (clock) "${CalibratedTime.source.name} 时钟" else "倒计时")
            append(" ")
            append(text)
            if (!clock && timer.phase == Phase.PAUSED) append("，已暂停")
            append(if (expanded) "，收起快捷操作" else "，展开快捷操作")
        }
        if (header.contentDescription != description) header.contentDescription = description
        modeButton.setLabel(if (clock) "切到倒计时" else "切到时钟")
        val timerVisibility = if (clock) View.GONE else View.VISIBLE
        if (timerButton.visibility != timerVisibility) timerButton.visibility = timerVisibility
        timerButton.setLabel(when (timer.phase) {
            Phase.RUNNING -> "暂停"
            Phase.PAUSED -> "继续"
            Phase.FINISHED -> "再开始"
            Phase.READY -> "开始"
        })
        surface.progress = if (clock) wallPosition else remaining.toFloat() / timer.duration
        surface.markers = if (clock) {
            if (wallPosition >= 0f) listOf(wall.marker) else emptyList()
        } else {
            timer.checkpoints.map { 1f - it.toFloat() / timer.duration }
        }
        surface.markerLabel = if (clock && wallPosition >= 0f) (wall.lead / 1_000f).toString() else null
        surface.tint = tint
        surface.invalidate()
    }

    private fun TextView.setLabel(value: String) {
        if (text.toString() != value) text = value
    }

    private fun buildContents() {
        header.isClickable = true
        header.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        header.setOnClickListener { togglePanel() }
        header.setOnTouchListener(DragGesture())
        surface.addView(header)
        readout.gravity = Gravity.CENTER
        readout.textSize = 22f
        readout.isSingleLine = true
        readout.setHorizontallyScrolling(false)
        readout.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        header.addView(readout)
        panel.orientation = LinearLayout.HORIZONTAL
        panel.visibility = View.GONE
        surface.addView(panel)
        closeButton.text = "关闭"
        closeButton.contentDescription = "关闭悬浮窗"
        for (button in listOf(modeButton, timerButton, closeButton)) {
            panel.addView(button)
        }
    }

    private fun refreshBounds() {
        cachedBounds = safeBounds()
        val density = context.resources.displayMetrics.density
        scaleFactor = minOf(wantedPercent / 100f, cachedBounds.width() / (242f * density),
            cachedBounds.height() / (104f * density)).coerceAtLeast(.1f)
    }

    private fun layOutControls() {
        header.layoutParams = FrameLayout.LayoutParams(-1, compactHeight())
        readout.layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        }
        readout.textSize = (if (readoutIsClock) 18f else 22f) * scaleFactor
        panel.layoutParams = FrameLayout.LayoutParams(-1, expandedHeight() - compactHeight() - dp(9)).apply {
            topMargin = compactHeight()
        }
        panel.setPadding(dp(10), 0, dp(10), 0)
        for (button in listOf(modeButton, timerButton, closeButton)) {
            button.textSize = 13f * scaleFactor
            button.includeFontPadding = false
            button.layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }
    }

    /**
     * Draws the readout by hand so a digit change can roll on sub-pixel positions and be eased on
     * the frame clock, instead of letting the platform re-lay-out a new string once per second.
     */
    private inner class RollingReadout(context: Context) : TextView(context) {
        private var previous = ""
        private var startedAt = 0L
        private var transition = INSTANT
        private val face = Paint(Paint.ANTI_ALIAS_FLAG)
        private val inkBounds = Rect()

        fun change(next: String) {
            val old = text.toString()
            val numeric = old.isNotEmpty() && next.isNotEmpty() &&
                next.all { it in DIGITS } && old.all { it in DIGITS }
            // The text contains whole seconds. The fractional part is drawn separately.
            // Changes such as the finish label or a new digit count use a short scale/fade.
            previous = old
            startedAt = System.nanoTime()
            transition = when {
                !ValueAnimator.areAnimatorsEnabled() -> INSTANT
                old.isEmpty() -> INSTANT
                numeric && old.length == next.length -> ROLL
                else -> POP
            }
            text = next
        }

        fun isRolling(now: Long): Boolean = when (transition) {
            ROLL -> now - startedAt < ROLL_NANOS
            POP -> now - startedAt < POP_NANOS
            else -> false
        }

        override fun onDraw(canvas: Canvas) {
            val fraction = if (readoutIsClock) millisText(wallMillis) else ""
            val current = text.toString() + fraction
            val previousText = previous + fraction
            face.set(paint)
            face.color = currentTextColor
            face.isSubpixelText = true
            face.alpha = 255
            val x = (width - face.measureText(current)) * .5f
            val sample = if (current.firstOrNull()?.isDigit() == true) "08" else current
            face.getTextBounds(sample, 0, sample.length, inkBounds)
            val y = dp(22) - inkBounds.exactCenterY()
            val now = if (lastFrameNanos != 0L) lastFrameNanos else System.nanoTime()

            if (transition == POP) {
                val phase = ((now - startedAt) / POP_NANOS.toFloat()).coerceIn(0f, 1f)
                if (phase >= 1f) {
                    transition = INSTANT
                } else {
                    val eased = easeOutQuint(phase)
                    val scale = 1f + .07f * (1f - eased)
                    canvas.save()
                    canvas.scale(scale, scale, width / 2f, dp(22).toFloat())
                    face.alpha = (255 * eased).toInt().coerceIn(0, 255)
                    canvas.drawText(current, x, y, face)
                    canvas.restore()
                    face.alpha = 255
                    return
                }
            }

            val phase = ((now - startedAt) / ROLL_NANOS.toFloat()).coerceIn(0f, 1f)
            if (transition != ROLL || phase >= 1f || previousText.length != current.length) {
                if (phase >= 1f) transition = INSTANT
                canvas.drawText(current, x, y, face)
                return
            }

            val eased = easeOutBack(phase)
            val travel = textSize * .30f
            val fade = smoothStep(phase / .82f)
            var pen = x
            for (i in current.indices) {
                if (current[i] != previousText[i]) {
                    face.alpha = (255 * (1f - fade)).toInt().coerceIn(0, 255)
                    canvas.drawText(previousText, i, i + 1, pen, y - travel * eased, face)
                    face.alpha = (255 * fade).toInt().coerceIn(0, 255)
                    canvas.drawText(current, i, i + 1, pen, y + travel * (1f - eased), face)
                } else {
                    face.alpha = 255
                    canvas.drawText(current, i, i + 1, pen, y, face)
                }
                pen += face.measureText(current, i, i + 1)
            }
            face.alpha = 255
        }
    }

    private fun actionButton(click: () -> Unit): TextView = TextView(dialog.context).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(Color.WHITE)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x30FFFFFF),
            GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF614F5B.toInt()) }, null)
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (ValueAnimator.areAnimatorsEnabled())
                    animate().scaleX(.974f).scaleY(.974f).setDuration(90)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (ValueAnimator.areAnimatorsEnabled())
                    animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(android.view.animation.OvershootInterpolator(.55f)).start()
                else -> Unit
            }
            false
        }
        setOnClickListener { click() }
    }

    private fun togglePanel() {
        if (!shown) return
        expanded = !expanded
        snap = null
        render(store.state.value)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            morphing = false
            morphPending = false
            settlePanel()
            return
        }
        // The window is resized once, up front, to the largest frame; only the drawn shape morphs.
        // If the resize has not landed yet the spring waits for it, so the chip never pops.
        morphing = false
        morphPending = true
        applyGeometry(morphWidth(), morphHeight())
        if (expanded) panel.visibility = View.VISIBLE
        openness.target(if (expanded) 1f else 0f)
        onLayoutReady()
    }

    private fun onLayoutReady() {
        if (!morphPending) return
        if (surface.width < morphWidth() || surface.height < morphHeight()) return
        morphPending = false
        morphing = true
        pulse.start()
    }

    private fun settlePanel() {
        val target = if (expanded) 1f else 0f
        openness.snapTo(target)
        press.snapTo(0f)
        stretch.snapTo(0f)
        applyScale()
        val rim = surface.visibleBounds()
        header.translationY = rim.top
        readout.translationX = rim.centerX() - surface.width / 2f
        panel.alpha = if (expanded) 1f else 0f
        panel.translationX = readout.translationX
        panel.translationY = 0f
        panel.scaleX = 1f
        panel.scaleY = 1f
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        applyGeometry(targetWidth(), targetHeight())
        surface.invalidate()
    }

    private fun applyOpenness() {
        val open = smoothStep(openness.value)
        val rim = surface.visibleBounds()
        val centerShift = rim.centerX() - surface.width / 2f
        header.translationY = rim.top
        readout.translationX = centerShift
        val reveal = smoothStep((open - .32f) / .68f)
        panel.alpha = reveal
        panel.translationX = centerShift
        panel.translationY = dp(9) * (1f - reveal)
        panel.scaleX = (rim.width() / surface.width.coerceAtLeast(1)) * (.965f + reveal * .035f)
        panel.scaleY = .90f + reveal * .10f
        val accessible = reveal > .85f
        if (modeButton.isEnabled != accessible) {
            panel.importantForAccessibility =
                if (accessible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            modeButton.isEnabled = accessible
            timerButton.isEnabled = accessible
            closeButton.isEnabled = accessible
        }
        surface.invalidate()
    }

    private fun swapMode() {
        val mode = store.state.value.overlayMode
        store.setOverlayMode(if (mode == ClockMode.CLOCK) ClockMode.TIMER else ClockMode.CLOCK)
    }

    private fun timerAction() {
        if (store.state.value.overlayMode != ClockMode.TIMER) return
        if (store.state.value.timer.phase == Phase.RUNNING) store.pause() else store.startOrResume()
        TimerAudioService.prepare(serviceContext, store.state.value)
    }

    private fun cancelMotion() {
        morphing = false
        morphPending = false
        snap = null
        press.snapTo(0f)
        stretch.snapTo(0f)
        applyScale()
    }

    private fun compactWidth() = dp(162)
    private fun morphWidth() = dp(242)
    private fun targetWidth() = if (expanded) morphWidth() else compactWidth()
    private fun compactHeight() = dp(54)
    private fun expandedHeight() = dp(104)
    private fun morphHeight() = expandedHeight()
    private fun targetHeight() = if (expanded) expandedHeight() else compactHeight()

    private fun applyGeometry(width: Int, height: Int, dragX: Int? = null, dragY: Int? = null) {
        val bounds = cachedBounds
        val w = width.coerceAtMost(bounds.width().coerceAtLeast(1))
        val h = height.coerceAtMost(bounds.height().coerceAtLeast(1))
        val right = bounds.right - w
        val bottom = bounds.bottom - h
        val attrs = window.attributes
        val x = (dragX ?: if (position.right) right else bounds.left).coerceIn(bounds.left, right)
        val y = (dragY ?: (bounds.top + (bottom - bounds.top) * position.vertical).roundToInt())
            .coerceIn(bounds.top, bottom)
        if (attrs.width == w && attrs.height == h && attrs.x == x && attrs.y == y) return
        attrs.width = w
        attrs.height = h
        attrs.x = x
        attrs.y = y
        window.attributes = attrs
    }

    @Suppress("DEPRECATION")
    private fun safeBounds(): Rect {
        val margin = (10 * context.resources.displayMetrics.density).roundToInt()
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return Rect(insets.left + margin, insets.top + margin,
                bounds.width() - insets.right - margin, bounds.height() - insets.bottom - margin)
        }
        val size = android.graphics.Point().also { manager.defaultDisplay.getRealSize(it) }
        val insets = window.decorView.rootWindowInsets
        val top = insets?.stableInsetTop ?: systemDimension("status_bar_height")
        val left = insets?.stableInsetLeft ?: 0
        val right = insets?.stableInsetRight ?: 0
        val bottom = insets?.stableInsetBottom ?: systemDimension("navigation_bar_height")
        return Rect(left + margin, top + margin, size.x - right - margin, size.y - bottom - margin)
    }

    private fun systemDimension(name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) context.resources.getDimensionPixelSize(id) else 0
    }

    /**
     * Flings the chip to the nearest side. A critically damped spring replaces the fixed
     * decelerate curve so grabbing it again mid-flight continues from the live position.
     */
    private fun snapToEdge() {
        val bounds = cachedBounds
        val attrs = window.attributes
        val width = attrs.width
        val height = attrs.height
        val right = attrs.x + width / 2 >= bounds.centerX()
        val travel = (bounds.height() - height).coerceAtLeast(1)
        position = OverlayPosition(right, ((attrs.y - bounds.top).toFloat() / travel).coerceIn(0f, 1f))
        store.savePosition(position)
        val destination = if (right) bounds.right - width else bounds.left
        if (destination == attrs.x) return
        snapFrom = attrs.x.toFloat()
        snapTo = destination.toFloat()
        snapY = attrs.y
        snap = SpringFloat(0f, stiffness = 420f, dampingRatio = 1f).apply { target(1f) }
        pulse.start()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density * scaleFactor).roundToInt()

    private inner class DragGesture : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var originX = 0
        private var originY = 0
        private var lastX = 0f
        private var lastY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val attrs = window.attributes
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = attrs.x
                    originY = attrs.y
                    lastX = downX
                    lastY = downY
                    dragging = false
                    snap = null
                    press.target(1f)
                    pulse.start()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = event.rawX - downX
                    val y = event.rawY - downY
                    if (!dragging && hypot(x, y) > slop) {
                        dragging = true
                        snap = null
                        press.target(0f)
                        val drawnPosition = surface.visibleBounds()
                        if (!expanded) {
                            originX += drawnPosition.left.roundToInt()
                            originY += drawnPosition.top.roundToInt()
                        }
                        settlePanel()
                        pulse.start()
                    }
                    if (dragging) {
                        // A gentle squash along the axis of travel: the chip leans into the drag.
                        val lean = ((abs(event.rawX - lastX) - abs(event.rawY - lastY)) / dp(150))
                            .coerceIn(-.02f, .02f)
                        stretch.target(lean)
                        applyGeometry(targetWidth(), targetHeight(), originX + x.roundToInt(), originY + y.roundToInt())
                        pulse.start()
                    }
                    lastX = event.rawX
                    lastY = event.rawY
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        dragging = false
                        snapToEdge()
                    } else {
                        view.performClick()
                    }
                    press.target(0f)
                    stretch.target(0f)
                    pulse.start()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        snapToEdge()
                    }
                    press.target(0f)
                    stretch.target(0f)
                    pulse.start()
                    return true
                }
            }
            return true
        }
    }

    private inner class ClockSurface(context: Context) : FrameLayout(context) {
        var sweep = -1f
        var progress = -1f
        var markers = emptyList<Float>()
        var markerLabel: String? = null
        var tint = Color.WHITE
        var shine = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outlinePath = Path()
        private val clipBounds = RectF()
        private var clipRadius = -1f
        private val rim = RectF()
        private var trailShader: Shader? = null
        private var trailLength = 0f
        private var glowShader: Shader? = null
        private var glowRadius = 0f

        fun visibleBounds(): RectF {
            val w = width.toFloat()
            val h = height.toFloat()
            val open = openness.value.coerceIn(0f, 1f)
            val revealWidth = ((w - compactWidth()) * (1f - open)).coerceAtLeast(0f)
            val revealHeight = ((h - compactHeight()) * (1f - open)).coerceAtLeast(0f)
            val side = if (position.right) revealWidth else 0f
            val top = revealHeight * position.vertical
            rim.set(side, top, side + w - revealWidth, top + h - revealHeight)
            return rim
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            buildShaders()
            applyOpenness()
            onLayoutReady()
        }

        private fun buildShaders() {
            trailLength = dp(38).toFloat()
            trailShader = LinearGradient(
                0f, 0f, trailLength, 0f,
                intArrayOf(Color.TRANSPARENT, 0x3DFFFFFF, 0x9EFFFFFF.toInt()),
                floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP,
            )
            glowRadius = dp(8).toFloat()
            glowShader = RadialGradient(
                0f, 0f, glowRadius,
                intArrayOf(0x59FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP,
            )
        }

        override fun dispatchDraw(canvas: Canvas) {
            val radius = dp(22).toFloat()
            val bounds = visibleBounds()
            if (clipBounds != bounds || clipRadius != radius) {
                outlinePath.rewind()
                outlinePath.addRoundRect(bounds, radius, radius, Path.Direction.CW)
                clipBounds.set(bounds)
                clipRadius = radius
            }
            val save = canvas.save()
            canvas.clipPath(outlinePath)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
        }

        init {
            setWillNotDraw(false)
            clipToOutline = false
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(22).toFloat())
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val radius = dp(22).toFloat()
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.alpha = 255
            paint.color = Color.argb(
                232,
                (51 + shine * 26).toInt(),
                (40 + shine * 13).toInt(),
                (49 + shine * 21).toInt(),
            )
            val bounds = visibleBounds()
            canvas.drawRoundRect(bounds, radius, radius, paint)
            val save = canvas.save()
            canvas.translate(bounds.left, bounds.top)
            val left = dp(24).toFloat()
            val right = bounds.width() - left
            val trackCenter = dp(39).toFloat()
            if (sweep >= 0f && progress < 0f) {
                val width = resources.displayMetrics.density * scaleFactor
                paint.color = 0x55E4CEDA
                paint.strokeWidth = width
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(left, trackCenter, right, trackCenter, paint)
                paint.color = 0xFFE6B4CC.toInt()
                val x = left + (right - left) * sweep
                canvas.drawLine(left, trackCenter, x, trackCenter, paint)
                drawComet(canvas, x, trackCenter, left, width)
            }
            if (progress >= 0f) {
                val halfThickness = dp(1).toFloat()
                val top = trackCenter - halfThickness
                val bottom = trackCenter + halfThickness
                paint.color = 0x55E4CEDA
                paint.strokeWidth = 0f
                canvas.drawRoundRect(left, top, right, bottom, halfThickness, halfThickness, paint)
                paint.color = tint
                canvas.drawRoundRect(left, top, left + (right - left) * progress, bottom,
                    halfThickness, halfThickness, paint)
                paint.color = 0xFFF2BBD5.toInt()
                paint.strokeWidth = dp(1).toFloat()
                for (marker in markers) {
                    val x = left + (right - left) * marker
                    canvas.drawLine(x, trackCenter - dp(4), x, trackCenter + dp(4), paint)
                }
                markerLabel?.let { label ->
                    paint.textAlign = Paint.Align.CENTER
                    paint.textSize = dp(7).toFloat()
                    canvas.drawText(label, left + (right - left) * markers.first(), trackCenter + dp(10), paint)
                }
            }
            canvas.restoreToCount(save)
        }

        /**
         * A soft light tail behind the sweep head. Reads as the frame the head has already passed,
         * which is what sells continuous motion at any panel rate.
         */
        private fun drawComet(canvas: Canvas, x: Float, centerY: Float, trackLeft: Float, width: Float) {
            val shader = trailShader
            if (shader != null) {
                val restore = canvas.save()
                canvas.clipRect(trackLeft, centerY - width * 1.6f, x, centerY + width * 1.6f)
                canvas.translate(x - trailLength, centerY)
                paint.shader = shader
                paint.strokeWidth = width
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(0f, 0f, trailLength, 0f, paint)
                paint.shader = null
                canvas.restoreToCount(restore)
            }
            val restore = canvas.save()
            canvas.translate(x, centerY)
            paint.shader = glowShader
            paint.strokeWidth = 0f
            canvas.drawCircle(0f, 0f, glowRadius, paint)
            paint.shader = null
            paint.color = 0xFFE6B4CC.toInt()
            canvas.drawCircle(0f, 0f, width * 1.7f, paint)
            canvas.restoreToCount(restore)
        }
    }

    private companion object {
        private const val DIGITS = "0123456789:"
        private const val ROLL_NANOS = 220_000_000L
        private const val POP_NANOS = 260_000_000L
        private const val INSTANT = 0
        private const val ROLL = 1
        private const val POP = 2

        fun overlayContext(context: Context): Context {
            if (Build.VERSION.SDK_INT < 30) return context
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            return context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        }

        fun easeOutBack(fraction: Float): Float {
            val c = 1.32f
            val p = fraction - 1f
            return 1f + (c + 1f) * p * p * p + c * p * p
        }
    }
}
