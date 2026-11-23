package io.fuguang.clock.tap

import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fuguang.clock.CalibratedTime
import io.fuguang.clock.ClockAction
import io.fuguang.clock.ClockStore
import io.fuguang.clock.ClockSwitch
import io.fuguang.clock.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun TimedTapScreen(source: TimeSource, hiddenFromRecents: Boolean, onRecentsHidden: (Boolean) -> Unit) {
    val activity = LocalActivity.current ?: return
    val service = TimedTapService.connection.collectAsStateWithLifecycle().value
    val status = TimedTapService.status.collectAsStateWithLifecycle().value
    val lastPoint by TimedTapService.selection.collectAsStateWithLifecycle()
    val clockZone = source.zone()
    val storedPoint = remember(activity) { TapPoint.load(activity) }
    val point = lastPoint ?: storedPoint
    val start = remember(clockZone) {
        val now = CalibratedTime.sample(source)?.now()
        (now?.let { Instant.ofEpochMilli(it).atZone(clockZone).toLocalTime() } ?: LocalTime.now(clockZone))
            .plusSeconds(30).withNano(0)
    }
    var hour by rememberSaveable { mutableStateOf(start.hour.toString().padStart(2, '0')) }
    var minute by rememberSaveable { mutableStateOf(start.minute.toString().padStart(2, '0')) }
    var second by rememberSaveable { mutableStateOf(start.second.toString().padStart(2, '0')) }
    var millis by rememberSaveable { mutableStateOf("000") }
    var offset by rememberSaveable { mutableStateOf("0") }
    var guide by rememberSaveable { mutableStateOf(false) }
    var syncInProgress by remember { mutableStateOf(false) }
    val calibrationRevision by CalibratedTime.revision.collectAsStateWithLifecycle()
    val sample = remember(source, calibrationRevision) { CalibratedTime.sample(source) }
    val syncSummary = remember(sample, clockZone) {
        sample?.let { clock ->
            val syncedTime = Instant.ofEpochMilli(clock.sampleUnixMillis).atZone(clockZone)
            "已校准 · ${scheduledFormat.format(syncedTime)}\n" +
                "${clock.host} · 往返 ${clock.roundTripMillis} ms · 偏差 ${CalibratedTime.driftLabel(source)}"
        }
    }
    var feedback by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val time = runCatching {
        LocalTime.of(hour.toInt(), minute.toInt(), second.toInt(), millis.toInt() * 1_000_000)
    }.getOrNull()
    val offsetMs = offset.toIntOrNull()?.takeIf { it in 0..100 }
    val active = status.scheduled != null || status.dispatching

    fun requireService(action: (TimedTapService) -> Unit) {
        when {
            service != null -> action(service)
            !isTimedTapEnabled(activity) -> guide = true
            else -> feedback = "系统正在连接无障碍服务，请稍后重试"
        }
    }

    fun sync(then: (NtpClock) -> Unit) {
        feedback = ""
        syncInProgress = true
        scope.launch {
            try {
                val clock = NtpClock.sync(source)
                CalibratedTime.record(source, clock)
                if (CalibratedTime.source == source) ClockStore.get(activity).refreshWallSchedule()
                then(CalibratedTime.sample(source) ?: clock)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback = "${source.syncLabel}失败，请检查网络后重试"
            } finally {
                syncInProgress = false
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("到达目标时刻后，在前台屏幕的指定坐标点击一次。请保持屏幕亮起并解锁。")
        ClockAction(onClick = {
            requireService {
                if (it.showPickerDock()) activity.moveTaskToBack(true)
                else feedback = "无法打开坐标拾取层"
            }
        }, enabled = !active && !syncInProgress, modifier = Modifier.fillMaxWidth().height(46.dp),
            containerColor = MaterialTheme.colorScheme.secondaryContainer) { Text("坐标拾取") }
        Text(point?.let { "屏幕坐标  (${it.x}, ${it.y}) px" } ?: "尚未保存屏幕坐标",
            fontFamily = FontFamily.Monospace)
        Text(if (source == TimeSource.UTC) "目标时刻 · 北京时间（UTC+8）" else "目标时刻 · 设备时区，取下一次")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField("时", hour, 2, !active, Modifier.weight(1f)) { hour = it }
            NumberField("分", minute, 2, !active, Modifier.weight(1f)) { minute = it }
            NumberField("秒", second, 2, !active, Modifier.weight(1f)) { second = it }
            NumberField("毫秒", millis, 3, !active, Modifier.weight(1.2f)) { millis = it }
        }
        NumberField("偏移量（ms）", offset, 3, !active, Modifier.fillMaxWidth(),
            error = offsetMs == null) { offset = it }
        Text("偏移量范围 0–100 ms，正值表示推迟点击。", style = MaterialTheme.typography.bodySmall)
        Text(if (service != null) "无障碍服务已连接" else "无障碍服务未连接")
        syncSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (status.scheduled != null) {
            val scheduled = status.scheduled
            val local = Instant.ofEpochMilli(scheduled.due).atZone(scheduled.zone)
            Text("等待单击 · ${scheduled.source.zoneLabel} ${scheduledFormat.format(local)}")
            ClockAction(onClick = { service?.cancel() }) { Text("取消本次点击") }
        } else {
            ClockAction(onClick = {
                requireService { available ->
                    if (point == null || time == null || offsetMs == null) {
                        feedback = "请先拾取坐标，并填写有效的目标时刻和偏移量"
                    } else {
                        val arrange: (NtpClock) -> Unit = { clock ->
                            if (TimedTapService.connection.value !== available) {
                                feedback = "无障碍服务已断开，请重新启用"
                            } else {
                                val due = nextDue(time, offsetMs, clock.now(), clockZone)
                                feedback = available.arm(point, clock, ScheduledTap(due, source, clockZone)).orEmpty()
                            }
                        }
                        val clock = CalibratedTime.sample(source)
                        if (clock == null) sync(arrange)
                        else arrange(clock)
                    }
                }
            }, enabled = !syncInProgress && !active, accent = true, modifier = Modifier.fillMaxWidth()) {
                Text("安排一次点击")
            }
        }
        if (feedback.isNotEmpty()) Text(feedback, color = MaterialTheme.colorScheme.error)
        if (status.message.isNotEmpty()) Text(status.message)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("隐藏后台")
                Text("从最近任务隐藏，仍可从桌面打开", style = MaterialTheme.typography.bodySmall)
            }
            ClockSwitch(
                checked = hiddenFromRecents,
                onChange = onRecentsHidden,
                modifier = Modifier.semantics { contentDescription = "隐藏后台" },
            )
        }
    }
    if (guide) AlertDialog(onDismissRequest = { guide = false },
        title = { Text("开启无障碍服务") },
        text = { Text("在系统无障碍设置中开启「${stringResource(R.string.timed_tap_service_name)}」。服务用于拾取屏幕坐标及执行你安排的一次点击。") },
        confirmButton = { ClockAction(onClick = {
            guide = false
            runCatching { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { feedback = "无法打开系统无障碍设置" }
        }) { Text("前往设置") } },
        dismissButton = { ClockAction(onClick = { guide = false }) { Text("取消") } })
}

private fun isTimedTapEnabled(context: Context): Boolean {
    val component = ComponentName(context, TimedTapService::class.java)
    return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
}

@Composable
private fun NumberField(label: String, value: String, digits: Int, enabled: Boolean, modifier: Modifier,
                        error: Boolean = false, update: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= digits && it.all { digit -> digit in '0'..'9' }) update(it) },
        modifier, enabled = enabled, label = { Text(label) }, singleLine = true, isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

private val scheduledFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.ROOT)

internal fun nextDue(time: LocalTime, offsetMs: Int, now: Long, zone: ZoneId): Long {
    val localDate = Instant.ofEpochMilli(now - offsetMs).atZone(zone).toLocalDate()
    for (days in 0L..2L) {
        val local = localDate.plusDays(days).atTime(time)
        val next = zone.rules.getValidOffsets(local)
            .map { local.toInstant(it).toEpochMilli() + offsetMs }
            .filter { it > now }.minOrNull()
        if (next != null) return next
    }
    error("无法解析目标时刻")
}
