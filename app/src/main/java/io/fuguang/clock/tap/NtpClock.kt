package io.fuguang.clock.tap

import android.os.SystemClock
import java.time.ZoneId
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlin.random.Random

internal enum class TimeSource(val zoneLabel: String, val syncLabel: String, val servers: List<String>) {
    UTC("北京时间", "北京时间校时", listOf("ntp.aliyun.com", "ntp1.aliyun.com")),
    NTP("设备时区", "NTP 校时", listOf("ntp.tencent.com", "ntp2.tencent.com", "ntp3.tencent.com"));

    fun zone(): ZoneId = if (this == UTC) BEIJING else ZoneId.systemDefault()

    companion object {
        private val BEIJING = ZoneId.of("Asia/Shanghai")
    }
}

internal data class NtpClock(
    private val unixAtSample: Long,
    private val elapsedAtSample: Long,
    val roundTripMillis: Long,
    val host: String,
) {
    fun now(): Long = unixAtSample + (SystemClock.elapsedRealtimeNanos() - elapsedAtSample) / 1_000_000

    /** Calibrated Unix time at the moment the reply landed. */
    val sampleUnixMillis: Long get() = unixAtSample

    /** The monotonic anchor the sample is projected from. */
    val sampleElapsedNanos: Long get() = elapsedAtSample

    fun deadlineNanos(unixMillis: Long): Long = elapsedAtSample + (unixMillis - unixAtSample) * 1_000_000L

    companion object {
        private const val SYNC_TIMEOUT_MS = 4_000L
        private const val NETWORK_BUDGET_MS = 3_900L
        private const val FIRST_PROBE_MS = 1_100
        private const val FOLLOW_UP_PROBE_MS = 350
        private val syncWorkers = ThreadPoolExecutor(
            0, 3, 30L, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, "fuguang-ntp").apply { isDaemon = true } },
        )

        suspend fun sync(source: TimeSource): NtpClock = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val deadline = SystemClock.elapsedRealtime() + NETWORK_BUDGET_MS
                val task = FutureTask<Unit> {
                    val result = runCatching { findSample(source, deadline) }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
                // Some platform DNS lookups ignore interruption. The caller can still finish
                // on time, and a bounded pool prevents repeated retries from piling up.
                continuation.invokeOnCancellation { task.cancel(true) }
                try {
                    syncWorkers.execute(task)
                } catch (_: RejectedExecutionException) {
                    continuation.resumeWith(Result.failure(IOException("校时繁忙，请重试")))
                }
            }
        } ?: throw IOException("校时超时")

        private fun findSample(source: TimeSource, deadline: Long): NtpClock {
            for (host in source.servers) {
                if (Thread.currentThread().isInterrupted || SystemClock.elapsedRealtime() >= deadline) break
                val addresses = try {
                    InetAddress.getAllByName(host).take(2)
                } catch (_: IOException) {
                    continue
                }
                for (address in addresses) {
                    val first = probe(host, address, deadline, FIRST_PROBE_MS) ?: continue
                    val second = probe(host, address, deadline, FOLLOW_UP_PROBE_MS)
                    return if (second != null && second.roundTripMillis < first.roundTripMillis) second else first
                }
            }
            throw IOException("未收到有效的 NTP 回复")
        }

        private fun probe(host: String, address: InetAddress, deadline: Long, limitMillis: Int): NtpClock? {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L || Thread.currentThread().isInterrupted) return null
            return try {
                exchange(host, address, minOf(limitMillis.toLong(), remaining).toInt())
            } catch (_: IOException) {
                null
            }
        }

        private fun exchange(host: String, address: InetAddress, timeoutMillis: Int): NtpClock {
            val bytes = ByteArray(48)
            bytes[0] = 0x23 // NTP v4, client mode.
            // The server echoes the client timestamp. Use a nonce for association and use
            // elapsed realtime for the client leg, so local clock changes cannot skew it.
            val nonce = Random.Default.nextLong()
            ByteBuffer.wrap(bytes).putLong(40, nonce)
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress(address, 123))
                socket.soTimeout = timeoutMillis
                val started = SystemClock.elapsedRealtimeNanos()
                socket.send(DatagramPacket(bytes, bytes.size))
                val response = DatagramPacket(ByteArray(512), 512)
                socket.receive(response)
                val received = SystemClock.elapsedRealtimeNanos()
                val header = response.data[0].toInt() and 0xff
                val stratum = response.data[1].toInt() and 0xff
                val data = ByteBuffer.wrap(response.data)
                if (response.length < 48 || header shr 6 == 3 || (header and 7) != 4 ||
                    stratum !in 1..15 || data.getLong(24) != nonce ||
                    data.getLong(32) == 0L || data.getLong(40) == 0L
                ) throw IOException("无效的 NTP 数据包")
                val serverReceived = readTime(data, 32)
                val serverSent = readTime(data, 40)
                val serverDelay = serverSent - serverReceived
                val transit = (received - started) / 1_000_000.0 - serverDelay
                if (serverDelay < 0 || transit < -1.0 || transit > 2_000.0) throw IOException("无效的 NTP 往返时延")
                return NtpClock((serverSent + transit.coerceAtLeast(0.0) * .5).roundToLong(),
                    received, transit.coerceAtLeast(0.0).roundToLong(), host)
            }
        }

        private fun readTime(data: ByteBuffer, index: Int): Double {
            val seconds = data.getInt(index).toLong() and 0xffffffffL
            val fraction = data.getInt(index + 4).toLong() and 0xffffffffL
            // NTP era 1 begins in 2036. Era 0 times preceding 1970 are outside this app's range.
            val eraSeconds = if (seconds < 2_208_988_800L) seconds + 0x100000000L else seconds
            return (eraSeconds - 2_208_988_800L) * 1_000.0 + fraction * (1_000.0 / 0x100000000L)
        }
    }
}
