package io.fuguang.clock

import android.view.Choreographer
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One motion vocabulary shared by the Compose screen and the raw overlay window.
 *
 * Every animated value in the app is driven either by the Compose frame clock or by [FramePulse],
 * which is the same Choreographer queue, so nothing is ever sampled on a timer: a value is
 * recomputed exactly once per display frame, at a fractional precision, right before it is drawn.
 */
internal object Motion {
    /** Long, confident deceleration. Entrances, page swaps, expansions. */
    val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Barely-there overshoot, used for content that appears or grows. */
    fun silky(stiffness: Float): SpringSpec<Float> = spring(dampingRatio = 0.92f, stiffness = stiffness)

    /** Visible settle, used for toggles and selection. */
    fun lively(stiffness: Float): SpringSpec<Float> = spring(dampingRatio = 0.68f, stiffness = stiffness)

    /** Fast press-in, calm press-out. */
    fun tap(stiffness: Float): SpringSpec<Float> = spring(dampingRatio = 0.82f, stiffness = stiffness)

    /** Colour targets lag the shape slightly so fills feel painted on rather than switched. */
    fun color(stiffness: Float): SpringSpec<Color> = spring(dampingRatio = 0.95f, stiffness = stiffness)
}

internal fun smoothStep(fraction: Float): Float {
    val t = fraction.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Fast start, very long tail — reads as higher frame rate than a tween of the same length. */
internal fun easeOutQuint(fraction: Float): Float {
    val t = 1f - fraction.coerceIn(0f, 1f)
    return 1f - t * t * t * t * t
}

/**
 * A single Choreographer pump. [start] is idempotent; [stop] can be called from inside the frame
 * callback and no further frame is scheduled after it.
 */
internal class FramePulse(private val onFrame: (frameNanos: Long, deltaNanos: Long) -> Unit) :
    Choreographer.FrameCallback {

    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var lastFrameNanos = 0L

    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(this)
    }

    fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val delta = if (lastFrameNanos == 0L) 0L else frameTimeNanos - lastFrameNanos
        lastFrameNanos = frameTimeNanos
        onFrame(frameTimeNanos, delta)
        if (running) choreographer.postFrameCallback(this)
    }
}

/**
 * Damped harmonic oscillator integrated in fixed sub-steps, so the motion is identical at 60, 120
 * or 165 Hz, and can be retargeted mid-flight without the value jumping (the velocity is kept).
 */
internal class SpringFloat(
    value: Float,
    private val stiffness: Float,
    private val dampingRatio: Float,
) {
    var value: Float = value
        private set
    private var target: Float = value
    private var velocity: Float = 0f

    private val isSettled: Boolean
        get() = abs(value - target) < 0.0004f && abs(velocity) < 0.0018f

    fun target(to: Float) {
        target = to
    }

    fun snapTo(to: Float) {
        value = to
        target = to
        velocity = 0f
    }

    /** Advance by [deltaSeconds]; returns true while the value still needs another frame. */
    fun step(deltaSeconds: Float): Boolean {
        if (deltaSeconds <= 0f) return !isSettled
        if (isSettled) {
            value = target
            velocity = 0f
            return false
        }
        val damping = 2f * dampingRatio * sqrt(stiffness)
        var remaining = deltaSeconds.coerceAtMost(0.064f)
        while (remaining > 0f) {
            val h = min(remaining, 1f / 480f)
            velocity += ((target - value) * stiffness - velocity * damping) * h
            value += velocity * h
            remaining -= h
        }
        if (isSettled) {
            value = target
            velocity = 0f
            return false
        }
        return true
    }
}

/** A one-shot 0 → peak → 0 envelope on the frame clock. Used for flashes and bumps. */
internal class Envelope(private val durationMs: Long, private val peakAt: Float) {
    private var startNanos = 0L

    var isActive: Boolean = false
        private set

    fun start(frameNanos: Long) {
        startNanos = frameNanos
        isActive = true
    }

    fun cancel() {
        isActive = false
    }

    fun value(frameNanos: Long): Float {
        if (!isActive) return 0f
        val t = (frameNanos - startNanos) / (durationMs * 1_000_000f)
        if (t >= 1f) {
            isActive = false
            return 0f
        }
        val shape = if (t < peakAt) t / peakAt else 1f - (t - peakAt) / (1f - peakAt)
        return smoothStep(shape)
    }
}
