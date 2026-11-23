@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.fuguang.clock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalTime
import io.fuguang.clock.tap.TimeSource
import java.util.Locale
import kotlin.math.roundToInt

@Composable
private fun AxisTrack(progress: Float, points: List<Float>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.fillMaxWidth().height(20.dp)) {
        val trackY = center.y
        val xInset = 2.dp.toPx()
        val span = (size.width - 2 * xInset).coerceAtLeast(0f)
        drawLine(colors.outline, Offset(xInset, trackY), Offset(xInset + span, trackY), 3.dp.toPx(), StrokeCap.Round)
        for (tick in 0..12) {
            val x = xInset + span * tick / 12
            val height = if (tick % 3 == 0) 5.dp.toPx() else 2.dp.toPx()
            drawLine(colors.onSurfaceVariant.copy(alpha = 0.6f), Offset(x, trackY + 4.dp.toPx()),
                Offset(x, trackY + 4.dp.toPx() + height), 1.dp.toPx(), StrokeCap.Round)
        }
        if (progress > 0f) {
            drawLine(colors.primary, Offset(xInset, trackY), Offset(xInset + span * progress, trackY),
                3.dp.toPx(), StrokeCap.Round)
        }
        for (point in points) {
            val x = xInset + span * point
            drawLine(colors.secondary, Offset(x, trackY - 7.dp.toPx()), Offset(x, trackY + 6.dp.toPx()),
                2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountdownTimeline(timer: TimerState, remaining: Long, onTogglePoint: (Long) -> Unit) {
    val limit = (timer.duration / 1000).toInt()
    if (limit < 2) return
    var selection by rememberSaveable(timer.duration) { mutableIntStateOf(limit / 2) }
    val chosen = selection * 1_000L
    val elapsed = timer.duration - remaining
    val existing = chosen in timer.checkpoints
    val pointFractions = timer.checkpoints.map { it.toFloat() / timer.duration }
    val displayedProgress = if (timer.editable) selection.toFloat() / limit else elapsed.toFloat() / timer.duration
    Column(Modifier.padding(top = 9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("倒计时时间轴", Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(if (timer.editable) durationText(chosen) else "已计时 ${durationText(elapsed)}",
                color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        ClockSlider(
            value = if (timer.editable) selection.toFloat() else elapsed / 1000f,
            onValueChange = { selection = it.roundToInt() },
            enabled = timer.editable,
            valueRange = 0f..limit.toFloat(),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "倒计时提醒点时间轴"
                stateDescription = durationText(if (timer.editable) chosen else elapsed)
            },
            track = { AxisTrack(displayedProgress, pointFractions) },
        )
        AxisLabels("开始 00:00", "结束 ${durationText(timer.duration)}")
        if (timer.editable) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StepButton("−1 秒", "提醒点前移一秒", selection > 0) { selection-- }
                StepButton("+1 秒", "提醒点后移一秒", selection < limit) { selection++ }
                Box(Modifier.weight(1f))
                ClockAction(
                    onClick = { onTogglePoint(chosen) },
                    enabled = selection in 1 until limit && (existing || timer.checkpoints.size < MAX_CHECKPOINTS),
                ) { Text(if (existing) "移除提醒点" else "添加提醒点") }
            }
        }
        if (timer.checkpoints.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                timer.checkpoints.forEachIndexed { index, point ->
                    val label = durationText(point)
                    if (timer.editable) {
                        ClockAction(
                            onClick = { onTogglePoint(point) },
                            modifier = Modifier.semantics { contentDescription = "移除 ${label} 提醒点" },
                        ) { Text("$label ×", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp) }
                    } else {
                        Text(
                            if (index < timer.deliveredCheckpoints) "$label 已到" else label,
                            Modifier.padding(vertical = 8.dp, horizontal = 3.dp),
                            color = if (index < timer.deliveredCheckpoints) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.secondary, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

private val wallTargetFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm:ss", Locale.ROOT)

@Composable
internal fun WallProgressControls(
    saved: WallProgressSettings,
    exactAllowed: Boolean,
    onExactSettings: () -> Unit,
    onSave: (WallProgressSettings) -> Unit,
    source: TimeSource,
) {
    val zone = source.zone()
    val revision by CalibratedTime.revision.collectAsStateWithLifecycle()
    val sample = remember(source, revision) { CalibratedTime.sample(source) }
    val initialTime = remember(saved.target, zone, sample != null) {
        if (saved.target != 0L) Instant.ofEpochMilli(saved.target).atZone(zone).toLocalTime()
        else sample?.let { Instant.ofEpochMilli(it.now()).atZone(zone).toLocalTime().plusMinutes(1).withSecond(0).withNano(0) }
            ?: LocalTime.MIDNIGHT
    }
    var enabled by rememberSaveable(saved.enabled) { mutableStateOf(saved.enabled) }
    var remind by rememberSaveable(saved.remind) { mutableStateOf(saved.remind) }
    var specific by rememberSaveable(saved.target) { mutableStateOf(saved.target != 0L) }
    var hour by rememberSaveable(saved.target, zone, sample != null) { mutableStateOf(initialTime.hour.toString().padStart(2, '0')) }
    var minute by rememberSaveable(saved.target, zone, sample != null) { mutableStateOf(initialTime.minute.toString().padStart(2, '0')) }
    var second by rememberSaveable(saved.target, zone, sample != null) { mutableStateOf(initialTime.second.toString().padStart(2, '0')) }
    var index by rememberSaveable(saved.lead) { mutableIntStateOf((saved.lead / 100).toInt()) }
    val lead = index * 100L
    val time = runCatching { LocalTime.of(hour.toInt(), minute.toInt(), second.toInt()) }.getOrNull()
    val leadText = String.format(Locale.ROOT, "%.1f 秒", index / 10f)
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("时间刻度", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((isSpecific, label) in listOf(false to "每分钟整分", true to "指定时刻")) {
                Box(Modifier.weight(1f).height(42.dp).flatChoice(isSpecific == specific, Role.Tab) { specific = isSpecific },
                    contentAlignment = Alignment.Center) {
                    Text(label, color = if (isSpecific == specific) colors.primary else colors.onSurfaceVariant,
                        fontSize = 13.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ClockField("时", hour, { hour = it; specific = true }, Modifier.weight(1f))
            ClockField("分", minute, { minute = it; specific = true }, Modifier.weight(1f))
            ClockField("秒", second, { second = it; specific = true }, Modifier.weight(1f))
        }
        Text(
            if (specific && time == null) "请输入有效的时、分、秒"
            else if (saved.target != 0L) {
                val target = Instant.ofEpochMilli(saved.target).atZone(zone)
                "已保存 ${target.format(wallTargetFormat)}"
            } else if (sample == null) "完成校准后可保存指定时刻"
            else "按${source.zoneLabel}保存最近一次指定时刻",
            color = if (specific && time == null) colors.error else colors.onSurfaceVariant,
            fontSize = 12.sp,
        )
        SettingSwitch("显示最后 3 秒进度条", enabled) { enabled = it }
        Text("时间卡点 · 提前 $leadText", fontWeight = FontWeight.Medium, color = colors.onSurface)
        ClockSlider(
            value = (30 - index).toFloat(),
            onValueChange = { index = 30 - it.roundToInt() },
            valueRange = 0f..30f, steps = 29,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "时间卡点刻度"
                stateDescription = "提前 $leadText"
            },
        )
        AxisLabels("提前 3.0 秒", "目标时刻")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("−0.1 秒", "减少提前时间零点一秒", index > 0) { index-- }
            StepButton("+0.1 秒", "增加提前时间零点一秒", index < 30) { index++ }
        }
        SettingSwitch("到卡点时提醒", remind) { remind = it }
        if (enabled && remind && !exactAllowed) {
            Text("后台提醒可能延迟", color = colors.secondary, fontSize = 12.sp)
            ClockAction(onClick = onExactSettings) { Text("开启精确提醒") }
        }
        ClockAction(
            enabled = !specific || (time != null && sample != null),
            accent = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val target = if (!specific) 0L else {
                    val chosen = checkNotNull(time)
                    val now = CalibratedTime.now() ?: return@ClockAction
                    if (chosen == initialTime && saved.target - lead >= now) saved.target
                    else nextWallTarget(chosen, now + lead, zone)
                }
                onSave(WallProgressSettings(enabled, target, lead, remind))
            },
        ) { Text("保存时间刻度") }
    }
}

@Composable
private fun AxisLabels(start: String, end: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        Text(start, color = tint, fontSize = 11.sp)
        Text(end, color = tint, fontSize = 11.sp)
    }
}

@Composable
private fun StepButton(label: String, description: String, enabled: Boolean, action: () -> Unit) {
    ClockAction(onClick = action, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ClockField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (candidate.length <= 2 && candidate.all { it in '0'..'9' }) onChange(candidate)
        },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun SettingSwitch(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        ClockSwitch(checked = enabled, onChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}
