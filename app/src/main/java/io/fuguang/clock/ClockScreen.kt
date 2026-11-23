@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.fuguang.clock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.withFrameNanos
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.fuguang.clock.tap.NtpClock
import io.fuguang.clock.tap.TimeSource
import io.fuguang.clock.tap.TimedTapScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The flat build draws solid fills, hairline borders and square-ish corners, and cuts every
 *  continuously running animation it can. The pretty build keeps the soft rose cards and motion. */
private val FLAT = BuildConfig.FLAT_UI
private val Ink = if (FLAT) Color(0xFFF3F3F4) else Color(0xFFFFF7FA)
private val Paper = if (FLAT) Color(0xFFFFFFFF) else Color(0xFFFFFEFE)
private val TextInk = if (FLAT) Color(0xFF1F1F1F) else Color(0xFF392A33)
private val Muted = if (FLAT) Color(0xFF6C6C6C) else Color(0xFF786571)
private val Rose = if (FLAT) Color(0xFF9C4B66) else Color(0xFF8A4E68)
private val Berry = if (FLAT) Color(0xFF8A3B52) else Color(0xFF954761)
private val Line = if (FLAT) Color(0xFFE0E0E0) else Color(0xFFEBDCE3)
private val SelectedTint = if (FLAT) Color(0xFFFFFFFF) else Color(0xFFF4DDE7)
private val CardShape = RoundedCornerShape(if (FLAT) 6.dp else 20.dp)
private val dates = DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.SIMPLIFIED_CHINESE)
private enum class SyncPhase { IDLE, RUNNING, DONE, FAILED }

private enum class HomeTab(val label: String) {
    CLOCK("时钟"),
    TIMED_TAP("定时单击"),
    TIMER("倒计时"),
}

private val presets = listOf(5, 15, 25)

@Composable
internal fun ClockScreen(
    state: ClockState,
    timeSource: TimeSource,
    onTimeSource: (TimeSource) -> Unit,
    overlayAllowed: Boolean,
    exactAllowed: Boolean,
    notificationAllowed: Boolean,
    hiddenFromRecents: Boolean,
    onRecentsHidden: (Boolean) -> Unit,
    onOverlay: (Boolean) -> Unit,
    onOverlaySize: (Int) -> Unit,
    onOverlayAutoOpen: (Boolean) -> Unit,
    onExactSettings: () -> Unit,
    onNotificationSettings: () -> Unit,
    onDuration: (Long) -> Unit,
    onCheckpoint: (Long) -> Unit,
    onWallProgress: (WallProgressSettings) -> Unit,
    onTimerAction: () -> Unit,
    onReset: () -> Unit,
    onAlertSettings: (AlertSettings) -> Unit,
) {
    MaterialTheme(
        colorScheme = if (FLAT) lightColorScheme(
            primary = Rose, onPrimary = Color.White, background = Ink, surface = Paper, onSurface = TextInk,
            onSurfaceVariant = Muted, outline = Line, secondary = Berry, primaryContainer = Color(0xFFEDEDEF),
            secondaryContainer = Color(0xFFF1F1F2),
            surfaceVariant = Color(0xFFF2F2F2), onPrimaryContainer = TextInk, surfaceContainerHigh = Paper, onBackground = TextInk,
        ) else lightColorScheme(
            primary = Rose, onPrimary = Ink, background = Ink, surface = Paper, onSurface = TextInk,
            onSurfaceVariant = Muted, outline = Line, secondary = Berry, primaryContainer = Color(0xFFF8DCE7),
            secondaryContainer = Color(0xFFF4DDE7),
            surfaceVariant = Color(0xFFF5EFF2), onPrimaryContainer = TextInk, surfaceContainerHigh = Paper, onBackground = TextInk,
        ),
    ) {
        var tab by rememberSaveable {
            mutableStateOf(if (state.timer.phase == Phase.READY) HomeTab.CLOCK else HomeTab.TIMER)
        }
        var guide by rememberSaveable { mutableStateOf(false) }
        var custom by rememberSaveable { mutableStateOf(false) }
        val tabState = rememberSaveableStateHolder()
        val tapDrafts = rememberSaveableStateHolder()
        val scope = rememberCoroutineScope()
        var syncPhase by remember { mutableStateOf(SyncPhase.IDLE) }
        var syncRun by remember { mutableIntStateOf(0) }
        var syncSource by remember { mutableStateOf<TimeSource?>(null) }
        val context = LocalContext.current
        val liveSource by rememberUpdatedState(timeSource)

        fun calibrate() {
            val source = liveSource
            if (syncPhase == SyncPhase.RUNNING && syncSource == source) return
            syncSource = source
            val run = ++syncRun
            syncPhase = SyncPhase.RUNNING
            scope.launch {
                try {
                    val clock = NtpClock.sync(source)
                    CalibratedTime.record(source, clock)
                    if (source == liveSource) ClockStore.get(context).refreshWallSchedule()
                    if (syncRun == run && source == liveSource) syncPhase = SyncPhase.DONE
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (syncRun == run && source == liveSource) syncPhase = SyncPhase.FAILED
                }
                delay(2_700)
                // A second tap starts a new run; only the latest run may clear the badge.
                if (syncRun == run && source == liveSource) syncPhase = SyncPhase.IDLE
            }
        }

        LaunchedEffect(timeSource) {
            if (syncSource != timeSource) syncPhase = SyncPhase.IDLE
            if (CalibratedTime.sample(timeSource) == null) calibrate()
        }

        Box(Modifier.fillMaxSize().background(Ink)) {
            Column(
                Modifier.align(Alignment.TopCenter).widthIn(max = 540.dp).fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp),
            ) {
                AppHeader(timeSource, onTimeSource, syncPhase, ::calibrate)
                Spacer(Modifier.height(14.dp))
                ModeSelector(tab, onTab = { tab = it })
                Spacer(Modifier.height(14.dp))
                Box(Modifier.weight(1f).clipToBounds()) {
                    AnimatedContent(
                        targetState = tab,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            if (FLAT) {
                                fadeIn(tween(110)) togetherWith fadeOut(tween(90))
                            } else {
                                val forward = targetState.ordinal > initialState.ordinal
                                val direction = if (forward) 1 else -1
                                (
                                    slideInHorizontally(
                                        spring(dampingRatio = .94f, stiffness = 360f),
                                    ) { width -> direction * width / 13 } +
                                        fadeIn(tween(220)) +
                                        scaleIn(initialScale = .988f, animationSpec = spring(dampingRatio = 1f, stiffness = 420f))
                                    ).togetherWith(
                                    slideOutHorizontally(
                                        spring(dampingRatio = 1f, stiffness = 460f),
                                    ) { width -> -direction * width / 18 } +
                                        fadeOut(tween(160)) +
                                        scaleOut(targetScale = .994f, animationSpec = spring(dampingRatio = 1f, stiffness = 460f))
                                    )
                            }
                        },
                        label = "页面切换",
                    ) { page ->
                        tabState.SaveableStateProvider(page.name) {
                            // Lazy so the two cards below the fold are not composed during the
                            // first frame; on open that is most of the screen's UI work.
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 26.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                when (page) {
                                HomeTab.CLOCK -> {
                                    item {
                                        SettingsCard {
                                            Readout(
                                                ClockMode.CLOCK, state.timer, 0L,
                                                calibration = rememberCalibrationNote(timeSource),
                                                clockFrame = rememberCalibratedFrame(timeSource), timeSource = timeSource,
                                            )
                                            WallProgressControls(state.wallProgress, exactAllowed, onExactSettings, onWallProgress, timeSource)
                                        }
                                    }
                                    item {
                                        SettingsCard {
                                            ToggleRow(
                                                Glyph.LAYERS, "显示悬浮时钟",
                                                if (state.overlayEnabled) "正在其他应用上方显示"
                                                else if (overlayAllowed) "已授权，可随时开启" else "开启后在系统设置中授权",
                                                state.overlayEnabled, { enable -> if (enable && !overlayAllowed) guide = true else onOverlay(enable) },
                                            )
                                            DividerLine()
                                            ToggleRow(
                                                Glyph.LAYERS, "打开应用时自动显示浮窗",
                                                if (state.overlayAutoOpen && !overlayAllowed) "需要先授予悬浮窗权限" else null,
                                                state.overlayAutoOpen,
                                            ) { enable ->
                                                onOverlayAutoOpen(enable)
                                                if (enable && !overlayAllowed) guide = true
                                            }
                                            DividerLine()
                                            Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text("悬浮窗大小", Modifier.weight(1f), color = TextInk, fontSize = 14.sp)
                                                Text("${state.overlayPercent}%", color = Rose, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                            }
                                            ClockSlider(value = state.overlayPercent.toFloat(),
                                                onValueChange = { onOverlaySize(it.roundToInt()) },
                                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "悬浮窗大小" },
                                                valueRange = MIN_OVERLAY_PERCENT.toFloat()..MAX_OVERLAY_PERCENT.toFloat())
                                        }
                                    }
                                }
                                HomeTab.TIMED_TAP -> item {
                                    SettingsCard {
                                        tapDrafts.SaveableStateProvider(timeSource.name) {
                                            TimedTapScreen(timeSource, hiddenFromRecents, onRecentsHidden)
                                        }
                                    }
                                }
                                HomeTab.TIMER -> item {
                                    SettingsCard {
                                        val remaining = rememberTimerRemaining(state.timer)
                                        Readout(ClockMode.TIMER, state.timer, remaining)
                                        TimerControls(state.timer, onDuration, { custom = true }, onTimerAction, onReset)
                                        if (!exactAllowed) {
                                            PermissionRow("后台提醒可能延迟", "开启精确提醒", onExactSettings)
                                        }
                                        CountdownTimeline(state.timer, remaining, onCheckpoint)
                                    }
                                }
                                }
                                if (page != HomeTab.TIMED_TAP) {
                                    item {
                                        AlertPanel(
                                            state.alerts, page == HomeTab.CLOCK && !exactAllowed, notificationAllowed,
                                            onAlertSettings, onExactSettings, onNotificationSettings,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (guide) {
            AlertDialog(onDismissRequest = { guide = false }, shape = RoundedCornerShape(if (FLAT) 8.dp else 22.dp),
                title = { Text("允许悬浮显示") },
                text = { Text("在系统设置中选择「${stringResource(R.string.app_name)}」，开启「显示在其他应用的上层」。返回后，时钟会自动出现。") },
                confirmButton = { ClockAction(onClick = { guide = false; onOverlay(true) }) { Text("前往授权") } },
                dismissButton = { ClockAction(onClick = { guide = false }) { Text("稍后") } })
        }
        if (custom) {
            CustomDurationDialog(
                duration = state.timer.duration,
                onDismiss = { custom = false },
                onConfirm = {
                    custom = false
                    onDuration(it)
                },
            )
        }
    }
}

@Composable
private fun AppHeader(
    source: TimeSource,
    onTimeSource: (TimeSource) -> Unit,
    syncPhase: SyncPhase,
    onCalibrate: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(stringResource(R.string.app_title), color = TextInk, fontSize = 27.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val revision by CalibratedTime.revision.collectAsStateWithLifecycle()
            val date = remember(source, revision) { CalibratedTime.date()?.format(dates) ?: "--月--日" }
            Text(date, color = Muted, fontSize = 12.sp, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            CalibrateButton(syncPhase, onCalibrate)
            TimeSourceTabs(source, onTimeSource)
        }
    }
}

@Composable
private fun CalibrateButton(phase: SyncPhase, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale = if (FLAT) 1f
    else animateFloatAsState(if (pressed) .95f else 1f, Motion.tap(950f), label = "校准按压").value
    val running = phase == SyncPhase.RUNNING
    val fill = if (FLAT) when (phase) {
        SyncPhase.RUNNING, SyncPhase.DONE -> Color(0xFFEDEDEF)
        SyncPhase.FAILED -> Color(0xFFF4E4E8)
        SyncPhase.IDLE -> Paper
    } else animateColorAsState(
        when (phase) {
            SyncPhase.RUNNING, SyncPhase.DONE -> SelectedTint
            SyncPhase.FAILED -> Color(0xFFF7E1E8)
            SyncPhase.IDLE -> Paper
        }, Motion.color(650f), label = "校准底色",
    ).value
    val edge = if (FLAT) when (phase) {
        SyncPhase.RUNNING, SyncPhase.DONE -> Line
        SyncPhase.FAILED -> Berry.copy(alpha = .45f)
        SyncPhase.IDLE -> Line
    } else animateColorAsState(
        when (phase) {
            SyncPhase.RUNNING -> Rose.copy(alpha = .38f)
            SyncPhase.DONE -> Rose.copy(alpha = .46f)
            SyncPhase.FAILED -> Berry.copy(alpha = .45f)
            SyncPhase.IDLE -> Line
        }, Motion.color(650f), label = "校准描边",
    ).value
    val tint = if (FLAT) (if (phase == SyncPhase.FAILED) Berry else Rose)
    else animateColorAsState(if (phase == SyncPhase.FAILED) Berry else Rose,
        Motion.color(650f), label = "校准墨色").value
    val label = when (phase) {
        SyncPhase.IDLE -> "校准"
        SyncPhase.RUNNING -> "校时中"
        SyncPhase.DONE -> "已校准"
        SyncPhase.FAILED -> "校时失败"
    }
    // Advertised as State and read in the draw phase only, so the spinner never recomposes the row.
    // The flat build drops the continuous spin entirely.
    val spin = if (running && !FLAT) {
        val transition = rememberInfiniteTransition(label = "校时旋转")
        transition.animateFloat(
            0f, 360f,
            infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "旋转",
        )
    } else null
    val check = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        if (phase == SyncPhase.DONE) {
            if (FLAT) {
                check.snapTo(1f)
            } else {
                check.snapTo(0f)
                check.animateTo(1f, tween(300, easing = Motion.Emphasized))
            }
        } else {
            check.snapTo(0f)
        }
    }
    val shape = RoundedCornerShape(if (FLAT) 4.dp else 12.dp)
    Row(
        Modifier.height(38.dp).width(72.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(shape).background(fill).border(1.dp, edge, shape)
            .clickable(interaction, null, !running, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "校准时间"
                stateDescription = when (phase) {
                    SyncPhase.RUNNING -> "正在校时"
                    SyncPhase.DONE -> "校准完成"
                    SyncPhase.FAILED -> "校时失败"
                    SyncPhase.IDLE -> "未校准"
                }
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).graphicsLayer { spin?.let { rotationZ = it.value } }) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 1.6.dp.toPx()
                if (phase == SyncPhase.DONE) {
                    val path = Path().apply {
                        moveTo(size.width * .16f, size.height * .55f)
                        lineTo(size.width * .41f, size.height * .79f)
                        lineTo(size.width * .86f, size.height * .24f)
                    }
                    val measure = PathMeasure().apply { setPath(path, false) }
                    val partial = Path()
                    measure.getSegment(0f, measure.length * (if (FLAT) 1f else check.value), partial, true)
                    drawPath(partial, tint, style = Stroke(1.9.dp.toPx(), cap = StrokeCap.Round))
                } else {
                    val radius = size.minDimension / 2f - stroke / 2f
                    drawArc(tint, 40f, 280f, false,
                        Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round))
                    // A small chevron closes the ring at the top-right, like a refresh arrow.
                    val radians = Math.toRadians(320.0)
                    val cos = kotlin.math.cos(radians).toFloat()
                    val sin = kotlin.math.sin(radians).toFloat()
                    val tip = Offset(center.x + radius * cos, center.y + radius * sin)
                    val tangentX = -sin
                    val tangentY = cos
                    val arm = 2.9.dp.toPx()
                    val backX = tip.x - tangentX * arm
                    val backY = tip.y - tangentY * arm
                    drawLine(tint, tip, Offset(backX + cos * arm * .8f, backY + sin * arm * .8f), stroke, StrokeCap.Round)
                    drawLine(tint, tip, Offset(backX - cos * arm * .8f, backY - sin * arm * .8f), stroke, StrokeCap.Round)
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun TimeSourceTabs(source: TimeSource, onSource: (TimeSource) -> Unit) {
    val options = TimeSource.entries
    val selected = options.indexOf(source).coerceAtLeast(0)
    // The selection is a physical pill that slides between the two options; a tap mid-flight
    // retargets the same spring instead of snapping. The flat build snaps.
    val slide = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) {
        if (FLAT) slide.snapTo(selected.toFloat())
        else slide.animateTo(selected.toFloat(), Motion.silky(stiffness = 380f))
    }
    val shape = RoundedCornerShape(if (FLAT) 6.dp else 12.dp)
    val segmentShape = RoundedCornerShape(if (FLAT) 3.dp else 10.dp)
    val inset = 2.dp
    val gap = 2.dp
    val segment = 56.dp
    Box(Modifier.height(38.dp).width(segment * options.size.toFloat() + gap * (options.size - 1).toFloat() + inset * 2f)
        .clip(shape).background(clockOptionBackground)
        .then(if (FLAT) Modifier.border(1.dp, Line, shape) else Modifier)) {
        Box(
            Modifier.offset(x = inset + (segment + gap) * slide.value, y = inset)
                .width(segment).height(34.dp)
                .clip(segmentShape)
                .background(SelectedTint)
                .border(1.dp, if (FLAT) Line else Rose.copy(alpha = .10f), segmentShape),
        )
        Row(
            Modifier.fillMaxSize().padding(horizontal = inset, vertical = inset),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            for (option in options) {
                val chosen = option == source
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val labelScale = if (FLAT) 1f
                else animateFloatAsState(if (pressed) .93f else 1f, Motion.tap(950f), label = "校时选项按压").value
                val tone = if (FLAT) (if (chosen) Rose else Muted)
                else animateColorAsState(if (chosen) Rose else Muted, Motion.color(700f), label = "校时选项文字").value
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .selectable(chosen, interaction, null, role = Role.Tab) {
                            if (!chosen) onSource(option)
                        }
                        .semantics { contentDescription = "校时方式 ${option.name}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option.name, color = tone, fontSize = 12.sp, maxLines = 1, softWrap = false,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.graphicsLayer { scaleX = labelScale; scaleY = labelScale },
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberCalibratedFrame(source: TimeSource): LongState {
    val revision by CalibratedTime.revision.collectAsStateWithLifecycle()
    val clock = remember(source, revision) { CalibratedTime.sample(source) }
    val frame = remember(clock) { mutableLongStateOf(clock?.now() ?: -1L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clock, lifecycle) {
        if (clock != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) withFrameNanos { frame.longValue = clock.now() }
        }
    }
    return frame
}

@Composable
private fun rememberCalibrationNote(source: TimeSource): String? {
    val revision by CalibratedTime.revision.collectAsStateWithLifecycle()
    return remember(source, revision) {
        CalibratedTime.sample(source)?.let { "已校准 ${CalibratedTime.driftLabel(source)}" }
    }
}

@Composable
private fun MillisecondReadout(frame: LongState) {
    Text(
        ".000", color = Color.Transparent, fontFamily = FontFamily.Monospace, fontSize = 16.sp,
        modifier = Modifier.clearAndSetSemantics {}.drawWithCache {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 16.sp.toPx()
                color = Rose.toArgb()
            }
            val baseline = (size.height - paint.ascent() - paint.descent()) / 2f
            onDrawBehind {
                drawContext.canvas.nativeCanvas.drawText(
                    millisText(frame.longValue.takeIf { it >= 0L }), 0f, baseline, paint,
                )
            }
        },
    )
}

@Composable
private fun rememberTimerRemaining(timer: TimerState): Long {
    var remaining by remember(timer) { mutableLongStateOf(timer.remaining()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(timer, lifecycle) {
        if (timer.phase == Phase.RUNNING) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                remaining = timer.remaining()
                delay(75)
            }
        }
    }
    return remaining
}

@Composable
private fun SettingsCard(contents: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(Paper)
            .then(if (FLAT) Modifier.border(1.dp, Line, CardShape) else Modifier)
            .padding(18.dp),
        content = contents,
    )
}

@Composable
private fun ModeSelector(tab: HomeTab, onTab: (HomeTab) -> Unit) {
    val options = HomeTab.entries
    val selected = tab.ordinal
    // One pill glides between the three pages, so switching reads as a single object moving
    // rather than three independent fills blinking on and off. The flat build snaps instead.
    val slide = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) {
        if (FLAT) slide.snapTo(selected.toFloat())
        else slide.animateTo(selected.toFloat(), Motion.silky(stiffness = 380f))
    }
    val shape = RoundedCornerShape(if (FLAT) 6.dp else 14.dp)
    val segmentShape = RoundedCornerShape(if (FLAT) 3.dp else 11.dp)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(42.dp).clip(shape).background(clockOptionBackground)
            .then(if (FLAT) Modifier.border(1.dp, Line, shape) else Modifier),
    ) {
        val inset = 3.dp
        val gap = 3.dp
        val segment = (maxWidth - inset * 2f - gap * (options.size - 1).toFloat()) / options.size.toFloat()
        Box(
            Modifier.offset(x = inset + (segment + gap) * slide.value, y = inset)
                .width(segment).height(36.dp)
                .clip(segmentShape)
                .background(SelectedTint)
                .border(1.dp, if (FLAT) Line else Rose.copy(alpha = .08f), segmentShape),
        )
        Row(
            Modifier.fillMaxSize().padding(horizontal = inset, vertical = inset),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            for (option in options) {
                val chosen = option == tab
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val labelScale = if (FLAT) 1f
                else animateFloatAsState(if (pressed) .94f else 1f, Motion.tap(950f), label = "标签按压").value
                val tone = if (FLAT) (if (chosen) TextInk else Muted)
                else animateColorAsState(if (chosen) TextInk else Muted, Motion.color(700f), label = "标签颜色").value
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .selectable(chosen, interaction, null, role = Role.Tab) { if (!chosen) onTab(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(option.label, color = tone, fontSize = 14.sp,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.graphicsLayer { scaleX = labelScale; scaleY = labelScale })
                }
            }
        }
    }
}

@Composable
private fun Readout(
    mode: ClockMode,
    timer: TimerState,
    remaining: Long,
    calibration: String? = null,
    clockFrame: LongState? = null,
    timeSource: TimeSource = TimeSource.UTC,
) {
    val isClock = mode == ClockMode.CLOCK
    val finished = !isClock && timer.phase == Phase.FINISHED
    val tint = if (finished) Berry else Rose
    val label = when {
        isClock -> if (timeSource == TimeSource.NTP) "NTP · 腾讯文档" else "UTC · 北京时间"
        timer.phase == Phase.RUNNING -> "剩余时间"
        timer.phase == Phase.PAUSED -> "已暂停"
        finished -> "本轮倒计时已结束"
        else -> "设定时长"
    }
    Column(Modifier.fillMaxWidth().padding(top = 27.dp, bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = Muted, fontSize = 13.sp)
            if (isClock) {
                if (FLAT) {
                    Text(
                        calibration ?: "未校准",
                        color = if (calibration != null) Rose else Muted,
                        fontSize = 11.sp,
                    )
                } else {
                    AnimatedContent(
                        targetState = calibration,
                        transitionSpec = {
                            (fadeIn(tween(240)) + slideInVertically(tween(240, easing = Motion.Emphasized)) { it / 2 })
                                .togetherWith(fadeOut(tween(140)))
                        },
                        label = "校准标签",
                    ) { note ->
                        Text(
                            note ?: "未校准",
                            color = if (note != null) Rose.copy(alpha = .78f) else Muted,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        if (clockFrame != null) {
            val zone = timeSource.zone()
            val seconds by remember(clockFrame) { derivedStateOf { Math.floorDiv(clockFrame.longValue, 1_000L) } }
            val wall = remember(seconds, zone, clockFrame) {
                wallClockText(seconds.takeIf { it >= 0L }?.times(1_000L), zone)
            }
            Row(Modifier.semantics(mergeDescendants = true) { contentDescription = wall },
                verticalAlignment = Alignment.CenterVertically) {
                Text(wall.take(5), color = TextInk, fontFamily = FontFamily.Monospace, fontSize = 55.sp,
                    lineHeight = 66.sp, letterSpacing = (-3).sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.alignByBaseline())
                Row(Modifier.padding(start = 7.dp).alignByBaseline()) {
                    // The digit is drawn, not re-laid-out: a single Text keeps the monospace cell
                    // and its baseline fixed, so a tick can never nudge the row (or the controls
                    // under it) by a fraction. The roll itself happens in the draw phase.
                    val seconds = wall.substring(6, 8)
                    if (FLAT) {
                        Text(seconds, color = Rose, fontFamily = FontFamily.Monospace, fontSize = 24.sp,
                            modifier = Modifier.alignByBaseline())
                    } else {
                        val roll = remember { Animatable(1f) }
                        var firstSecond by remember { mutableStateOf(true) }
                        LaunchedEffect(seconds) {
                            if (firstSecond) {
                                firstSecond = false
                            } else {
                                roll.snapTo(0f)
                                roll.animateTo(1f, spring(dampingRatio = 1f, stiffness = 950f))
                            }
                        }
                        Text(
                            seconds, color = Rose, fontFamily = FontFamily.Monospace, fontSize = 24.sp,
                            modifier = Modifier.alignByBaseline().graphicsLayer {
                                val progress = roll.value
                                alpha = .22f + .78f * progress
                                translationY = 7.dp.toPx() * (1f - progress)
                                val settle = .90f + .10f * progress
                                scaleX = settle
                                scaleY = settle
                            },
                        )
                    }
                    Box(Modifier.align(Alignment.CenterVertically)) { MillisecondReadout(clockFrame) }
                }
            }
        } else {
            val timerText = durationText(remaining)
            Text(if (finished) "时间到" else timerText, color = if (finished) Berry else TextInk,
                fontFamily = if (finished) FontFamily.Default else FontFamily.Monospace,
                fontSize = if (timerText.length > 5 || finished) 42.sp else 55.sp,
                fontWeight = FontWeight.Medium, lineHeight = 66.sp, letterSpacing = (-1).sp)
        }
        Spacer(Modifier.height(10.dp))
        TimeRuler(isClock, timer, tint, Modifier.fillMaxWidth().height(24.dp), clockFrame)
    }
}

@Composable
private fun TimeRuler(isClock: Boolean, timer: TimerState, tint: Color, modifier: Modifier, clockFrame: LongState?) {
    val frame = remember(timer) { mutableLongStateOf(timer.remaining()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(isClock, timer, lifecycle) {
        if (!isClock && timer.phase == Phase.RUNNING) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (FLAT) {
                // Four updates a second: the flat build never keeps a 60 Hz redraw alive while idle.
                while (true) {
                    frame.longValue = timer.remaining()
                    delay(250)
                }
            } else {
                // Let the first frames land before the live sweep starts competing for the vsync,
                // then follow the panel exactly: 120 Hz stays 120 Hz.
                delay(260)
                while (true) withFrameNanos { frame.longValue = timer.remaining() }
            }
        }
    }
    Box(modifier.drawWithCache {
        val gutter = 3.dp.toPx()
        val span = (size.width - 2 * gutter).coerceAtLeast(0f)
        val baseline = 16.dp.toPx()
        val tickStroke = Stroke(1.dp.toPx())
        val ticks = Path().apply {
            for (i in 0..60) {
                val x = gutter + span * i / 60
                moveTo(x, baseline)
                lineTo(x, baseline - if (i % 5 == 0) 8.dp.toPx() else 4.dp.toPx())
            }
        }
        onDrawBehind {
            val drawTime = clockFrame?.longValue
            val progress = when {
                !isClock -> frame.longValue.toFloat() / timer.duration
                drawTime != null && drawTime >= 0L -> Math.floorMod(drawTime, 60_000L) / 60_000f
                else -> -1f
            }
            if (progress < 0f) {
                drawPath(ticks, Line, style = tickStroke)
                return@onDrawBehind
            }
            val marker = gutter + span * progress.coerceIn(0f, 1f)
            val split = (marker + tickStroke.width / 2f).coerceAtMost(size.width)
            clipRect(left = split) { drawPath(ticks, Line, style = tickStroke) }
            clipRect(right = split) { drawPath(ticks, tint.copy(alpha = .64f), style = tickStroke) }
            drawLine(tint, Offset(marker, baseline - 13.dp.toPx()), Offset(marker, baseline + 1.dp.toPx()),
                2.dp.toPx(), cap = StrokeCap.Round)
        }
    })
}

@Composable
private fun TimerControls(
    timer: TimerState,
    onDuration: (Long) -> Unit,
    onCustom: () -> Unit,
    onTimerAction: () -> Unit,
    onReset: () -> Unit,
) {
    val editable = timer.editable
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (minutes in presets) {
            DurationChip(
                "${minutes} 分钟", timer.duration == minutes * 60_000L, editable,
                Modifier.weight(1f), onClick = { onDuration(minutes * 60_000L) },
            )
        }
        DurationChip("自定义", presets.none { timer.duration == it * 60_000L }, editable,
            Modifier.weight(1f), onClick = onCustom)
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        val action = when (timer.phase) {
            Phase.READY -> "开始"
            Phase.RUNNING -> "暂停"
            Phase.PAUSED -> "继续"
            Phase.FINISHED -> "再开始"
        }
        ClockAction(accent = true, onClick = onTimerAction, modifier = Modifier.weight(1f).height(46.dp)) {
            Text(action, fontWeight = FontWeight.SemiBold)
        }
        ClockAction(onClick = onReset, modifier = Modifier.height(46.dp), enabled = timer.phase != Phase.READY) { Text("重置") }
    }
}

@Composable
private fun DurationChip(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(37.dp).flatChoice(selected, Role.RadioButton, enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Rose else if (enabled) TextInk else Muted, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AlertPanel(
    alerts: AlertSettings,
    showExactSettings: Boolean,
    notificationAllowed: Boolean,
    update: (AlertSettings) -> Unit,
    onExactSettings: () -> Unit,
    onNotificationSettings: () -> Unit,
) {
    Column {
        Text("到点提醒", Modifier.padding(start = 3.dp, bottom = 9.dp), color = TextInk,
            fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        SettingsCard {
            ToggleRow(Glyph.BELL, "系统通知", null, alerts.notification) { update(alerts.copy(notification = it)) }
            DividerLine()
            ToggleRow(Glyph.SOUND, "响铃", null, alerts.sound) { update(alerts.copy(sound = it)) }
            DividerLine()
            ToggleRow(Glyph.VIBRATE, "振动", null, alerts.vibration) { update(alerts.copy(vibration = it)) }
            if (!notificationAllowed && alerts.notification) {
                DividerLine()
                PermissionRow("系统通知权限未开启", "去授权", onNotificationSettings)
            }
            if (showExactSettings) {
                DividerLine()
                PermissionRow("后台提醒可能延迟", "开启精确提醒", onExactSettings)
            }
        }
    }
}

@Composable
private fun ToggleRow(glyph: Glyph, title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(if (detail == null) 48.dp else 58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(25.dp),
            contentAlignment = Alignment.Center,
        ) {
            ClockGlyph(glyph, Rose, Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(title, color = TextInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (detail != null) Text(detail, color = Muted, fontSize = 11.sp)
        }
        ClockSwitch(checked, onChange, Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun DividerLine() {
    Spacer(Modifier.fillMaxWidth().padding(start = 45.dp).height(1.dp).background(if (FLAT) Line else Color(0xFFF1E7EC)))
}

@Composable
private fun PermissionRow(note: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(note, Modifier.weight(1f), color = Berry, fontSize = 12.sp)
        ClockAction(onClick = onClick) { Text(action, fontSize = 12.sp, color = Rose) }
    }
}

@Composable
private fun CustomDurationDialog(duration: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val wasPreset = presets.any { duration == it * 60_000L }
    var minutes by rememberSaveable { mutableStateOf(if (wasPreset) "" else (duration / 60_000).toString()) }
    var seconds by rememberSaveable { mutableStateOf(if (wasPreset) "" else (duration / 1000 % 60).toString()) }
    val mins = minutes.toIntOrNull() ?: 0
    val secs = seconds.toIntOrNull() ?: 0
    val total = mins * 60L + secs
    val valid = secs < 60 && total in 1..MAX_DURATION_SECONDS
    AlertDialog(
        shape = RoundedCornerShape(if (FLAT) 8.dp else 22.dp),
        onDismissRequest = onDismiss,
        title = { Text("自定义时长") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(minutes, { value -> if (value.length <= 4 && value.all { it in '0'..'9' }) minutes = value },
                        Modifier.weight(1f), label = { Text("分钟") }, placeholder = { Text("0") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(seconds, { value -> if (value.length <= 2 && value.all { it in '0'..'9' }) seconds = value },
                        Modifier.weight(1f), label = { Text("秒") }, placeholder = { Text("0") }, singleLine = true, isError = secs > 59,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Text("支持 1 秒至 99 小时 59 分 59 秒", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            ClockAction(enabled = valid, onClick = { onConfirm(total * 1000) }) { Text("确定") }
        },
        dismissButton = { ClockAction(onClick = onDismiss) { Text("取消") } },
    )
}

private enum class Glyph { LAYERS, BELL, SOUND, VIBRATE }

@Composable
private fun ClockGlyph(glyph: Glyph, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.width / 24
        val stroke = 1.5f * unit
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, point(x1, y1), point(x2, y2), stroke, StrokeCap.Round)
        fun polygon(vararg xy: Pair<Float, Float>) = drawPath(
            Path().apply {
                xy.forEachIndexed { index, (x, y) ->
                    if (index == 0) moveTo(x * unit, y * unit) else lineTo(x * unit, y * unit)
                }
            }, tint, style = Stroke(stroke, cap = StrokeCap.Round),
        )
        fun arc(left: Float, top: Float, width: Float, height: Float, start: Float, sweep: Float) =
            drawArc(tint, start, sweep, false, point(left, top), Size(width * unit, height * unit),
                style = Stroke(stroke, cap = StrokeCap.Round))
        when (glyph) {
            Glyph.LAYERS -> {
                polygon(3f to 8f, 12f to 3.5f, 21f to 8f, 12f to 12.5f, 3f to 8f)
                polygon(3f to 12.5f, 12f to 17f, 21f to 12.5f)
                polygon(3f to 17f, 12f to 21.5f, 21f to 17f)
            }
            Glyph.BELL -> {
                arc(6.5f, 4f, 11f, 11f, 180f, 180f)
                polygon(6.5f to 9.5f, 6.5f to 14f, 4.5f to 17f, 19.5f to 17f, 17.5f to 14f, 17.5f to 9.5f)
                arc(9.5f, 17f, 5f, 3.5f, 0f, 180f)
            }
            Glyph.SOUND -> {
                polygon(4f to 10f, 8f to 10f, 12f to 6f, 12f to 18f, 8f to 14f, 4f to 14f, 4f to 10f)
                arc(11f, 8f, 8f, 8f, -60f, 120f)
                arc(8f, 4.5f, 15f, 15f, -49f, 98f)
            }
            Glyph.VIBRATE -> {
                drawRoundRect(tint, point(8f, 4f), Size(8 * unit, 16 * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * unit), style = Stroke(stroke))
                line(5f, 7f, 5f, 17f)
                line(19f, 7f, 19f, 17f)
                line(2f, 9f, 2f, 15f)
                line(22f, 9f, 22f, 15f)
            }
        }
    }
}
