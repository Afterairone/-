package io.fuguang.clock

import android.os.Build
import android.view.Window
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private val FLAT = BuildConfig.FLAT_UI

/**
 * Asks the panel, the compositor and the app's own frame loop to agree on one cadence.
 *
 * On API 30+ the window advertises the surface frame rate through its decor view; from API 35 the
 * platform propagates that as a per-layer frame-rate vote, so a 120 Hz panel keeps a 120 Hz
 * queue instead of resampling 60 Hz output. API 37 additionally allows keeping the panel boosted
 * while a gesture is in flight, which is exactly the moment a slow frame is visible.
 *
 */
@Suppress("DEPRECATION")
internal fun Window.preferSmoothMotion(forceDisplayMode: Boolean = false) {
    val display = windowManager.defaultDisplay
    val current = display.mode
    val fastest = display.supportedModes
        .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        .maxByOrNull { it.refreshRate } ?: current
    val rate = fastest.refreshRate
    attributes = attributes.apply {
        preferredRefreshRate = rate
        if (forceDisplayMode && fastest.modeId != 0) preferredDisplayModeId = fastest.modeId
    }
    if (Build.VERSION.SDK_INT >= 35) {
        decorView.requestedFrameRate = rate
    }
    if (Build.VERSION.SDK_INT >= 37) {
        setFrameRateBoostOnTouchEnabled(true)
    }
}

@Composable
private fun Modifier.pressMotion(source: MutableInteractionSource, enabled: Boolean): Modifier {
    val pressed by source.collectIsPressedAsState()
    // A single spring for both directions: it arrives fast, never wobbles, and if the finger goes
    // down again mid-release the value departs from where it is instead of snapping.
    val scale = if (FLAT) 1f
    else animateFloatAsState(if (pressed) .972f else 1f, Motion.tap(1150f), label = "按压").value
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = if (enabled) 1f else .45f
    }
}

internal val clockOptionBackground = if (FLAT) Color(0xFFECECEE) else Color(0xFFF6F2F4)

@Composable
internal fun ClockAction(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                         accent: Boolean = false, containerColor: Color = Color.Transparent, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (FLAT) 4.dp else 12.dp)
    Box(modifier.defaultMinSize(minHeight = 42.dp).pressMotion(source, enabled).clip(shape)
        .background(if (accent) (if (FLAT) Color(0xFF8F3F5A) else Color(0xFF603E50)) else containerColor)
        .clickable(source, null, enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides
            (if (accent) Color.White else MaterialTheme.colorScheme.primary)) { ProvideTextStyle(MaterialTheme.typography.labelLarge, content) }
    }
}

@Composable
internal fun Modifier.flatChoice(selected: Boolean, role: Role, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (FLAT) 4.dp else 10.dp)
    val fill by animateColorAsState(
        if (selected) (if (FLAT) Color(0xFFE6E6E8) else Color(0xFFF4DDE7))
        else (if (FLAT) Color.White else clockOptionBackground),
        if (FLAT) snap() else Motion.color(stiffness = 700f), label = "选项颜色",
    )
    // Lands into place with a 1.6% settle: the selection reads as a physical object arriving.
    // Only on a real change, so the first frame of the screen never pops by itself.
    val landing = remember { Animatable(1f) }
    var wasSelected by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (!FLAT && selected && !wasSelected) {
            landing.snapTo(0f)
            landing.animateTo(1f, Motion.lively(560f))
        }
        wasSelected = selected
    }
    return pressMotion(source, enabled)
        .graphicsLayer {
            val settle = if (FLAT) 1f else 1f + .016f * (1f - landing.value)
            scaleX = settle
            scaleY = settle
        }
        .then(if (FLAT) Modifier.border(1.dp, Color(0xFFDCDCDC), shape) else Modifier)
        .clip(shape)
        .background(fill)
        .selectable(selected, source, null, enabled, role, onClick)
}

@Composable
internal fun ClockSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val travel = if (FLAT) (if (checked) 1f else 0f)
    else animateFloatAsState(if (checked) 1f else 0f, Motion.lively(560f), label = "开关滑动").value
    val trackShape = RoundedCornerShape(if (FLAT) 2.dp else 50.dp)
    val thumbShape = RoundedCornerShape(if (FLAT) 2.dp else 50.dp)
    Box(modifier.size(54.dp, 48.dp).toggleable(checked, source, null, role = Role.Switch, onValueChange = onChange),
        contentAlignment = Alignment.Center) {
        val fill by animateColorAsState(
            if (checked) (if (FLAT) Color(0xFF8F3F5A) else Color(0xFF795269))
            else (if (FLAT) Color(0xFFC9C9C9) else Color(0xFFD7CDD2)),
            if (FLAT) snap() else Motion.color(stiffness = 760f), label = "开关颜色",
        )
        Box(Modifier.size(46.dp, 26.dp).background(fill, trackShape)) {
            // Transformed in the draw phase only: the thumb travels on fractional pixels without
            // ever re-measuring or re-laying-out the row it sits in.
            Box(Modifier.align(Alignment.CenterStart)
                .graphicsLayer {
                    translationX = 3.dp.toPx() + 20.dp.toPx() * travel
                    // Squash through the middle of the journey, exactly where speed peaks.
                    if (!FLAT) {
                        val speed = (1f - abs(travel * 2f - 1f)).coerceIn(0f, 1f)
                        scaleX = 1f + .16f * speed
                        scaleY = 1f - .06f * speed
                    }
                    transformOrigin = TransformOrigin.Center
                }
                .size(20.dp).background(Color.White, thumbShape))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClockSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier,
                         valueRange: ClosedFloatingPointRange<Float>, steps: Int = 0, enabled: Boolean = true,
                         track: (@Composable (androidx.compose.material3.SliderState) -> Unit)? = null) {
    val source = remember { MutableInteractionSource() }
    val dragged by source.collectIsDraggedAsState()
    val thumbScale = if (FLAT) 1f
    else animateFloatAsState(if (dragged) 1.16f else 1f, Motion.lively(600f), label = "滑杆握柄").value
    val thumbShape = RoundedCornerShape(if (FLAT) 3.dp else 50.dp)
    val thumbColor = if (FLAT) MaterialTheme.colorScheme.primary else Color(0xFF795269)
    val thumbSize = if (FLAT) 18.dp else 22.dp
    val trackBase = if (FLAT) Color(0xFFE2E2E2) else Color(0xFFECE2E7)
    val trackFill = if (FLAT) MaterialTheme.colorScheme.primary else Color(0xFF966F84)
    Slider(value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
        valueRange = valueRange, steps = steps, interactionSource = source,
        thumb = {
            Box(Modifier.size(thumbSize).graphicsLayer {
                scaleX = thumbScale
                scaleY = thumbScale
            }.background(thumbColor, thumbShape))
        },
        track = { state ->
            if (track != null) track(state) else {
                val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth()
                    .height(if (FLAT) 3.dp else 4.dp)
                    .background(trackBase, RoundedCornerShape(50))) {
                    val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                    drawRoundRect(trackFill,
                        size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height), cornerRadius = radius)
                }
            }
        })
}
