package io.fuguang.clock

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import io.fuguang.clock.tap.NtpClock
import io.fuguang.clock.tap.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Samples are valid only for their own source and the boot that produced their monotonic anchor. */
internal object CalibratedTime {
    private const val UNKNOWN_BOOT = -1
    private const val NANOS_PER_MILLI = 1_000_000L
    private var prefs: SharedPreferences? = null
    private var bootCount = UNKNOWN_BOOT
    private var samples = emptyMap<TimeSource, NtpClock>()
    var source = TimeSource.UTC
        private set
    private val revisionState = MutableStateFlow(0L)
    val revision = revisionState.asStateFlow()

    fun install(context: Context) {
        if (prefs != null) return
        val store = context.getSharedPreferences("calibrated_time", Context.MODE_PRIVATE)
        prefs = store
        val persistedSource = context.getSharedPreferences("timed_tap", Context.MODE_PRIVATE)
            .getString("timeSource", null)
        source = TimeSource.entries.firstOrNull { it.name == persistedSource } ?: TimeSource.UTC
        bootCount = runCatching { Settings.Global.getInt(context.contentResolver, "boot_count", UNKNOWN_BOOT) }
            .getOrDefault(UNKNOWN_BOOT)
        if (bootCount == UNKNOWN_BOOT || bootCount == Int.MIN_VALUE) return

        val restored = mutableMapOf<TimeSource, NtpClock>()
        for (option in TimeSource.entries) {
            readSample(store, "${option.name}.", option)?.let { restored[option] = it }
        }
        // Import the previous version's single, source-labelled sample without overwriting a newer one.
        val legacy = TimeSource.entries.firstOrNull { it.name == store.getString("source", null) }
        if (legacy != null && legacy !in restored) readSample(store, "", legacy)?.let { restored[legacy] = it }
        samples = restored
    }

    private fun readSample(store: SharedPreferences, prefix: String, option: TimeSource): NtpClock? {
        if (store.getInt(prefix + "boot", UNKNOWN_BOOT) != bootCount) return null
        val host = store.getString(prefix + "host", "").orEmpty()
        if (host !in option.servers) return null
        val unix = store.getLong(prefix + "unix", 0L)
        val elapsed = if (prefix.isEmpty()) store.getLong("elapsed", 0L) * NANOS_PER_MILLI
            else store.getLong(prefix + "elapsedNanos", 0L)
        if (unix <= 0 || elapsed !in 1..SystemClock.elapsedRealtimeNanos()) return null
        return NtpClock(unix, elapsed, store.getLong(prefix + "rtt", 0L), host)
    }

    fun select(option: TimeSource) {
        if (source == option) return
        source = option
        revisionState.value++
    }

    fun record(option: TimeSource, clock: NtpClock) {
        val previous = samples[option]
        if (previous != null && previous.sampleElapsedNanos > clock.sampleElapsedNanos) return
        samples = samples + (option to clock)
        if (bootCount >= 0) {
            val prefix = "${option.name}."
            prefs?.edit()
                ?.putLong(prefix + "unix", clock.sampleUnixMillis)
                ?.putLong(prefix + "elapsedNanos", clock.sampleElapsedNanos)
                ?.putInt(prefix + "boot", bootCount)
                ?.putLong(prefix + "rtt", clock.roundTripMillis)
                ?.putString(prefix + "host", clock.host)
                ?.apply()
        }
        if (source == option) revisionState.value++
    }

    fun sample(option: TimeSource = source): NtpClock? = samples[option]
    fun now(): Long? = sample()?.now()
    fun zone(): ZoneId = source.zone()
    fun date(): LocalDate? = now()?.let { Instant.ofEpochMilli(it).atZone(zone()).toLocalDate() }

    fun driftLabel(option: TimeSource): String {
        val clock = sample(option) ?: return ""
        val drift = clock.now() - System.currentTimeMillis()
        return "${if (drift < 0) "−" else "+"}${abs(drift)} ms"
    }
}

private val fractionalSeconds = Array(1_000) { "." + it.toString().padStart(3, '0') }

internal fun millisText(unixMillis: Long?): String =
    unixMillis?.let { fractionalSeconds[Math.floorMod(it, 1_000L).toInt()] } ?: ".---"
