package moe.wusan.xposed.utils

import android.os.SystemClock
import java.lang.reflect.InvocationTargetException
import java.util.WeakHashMap

/** Successful callback IPC is observable here; target App track storage is not. */
internal object LocationDeliveryDiagnostics {
    data class Snapshot(
        val deliveredPoints: Long,
        val listenerCount: Int,
        val maxSampleGapMs: Long,
        val maxDeliveryDelayMs: Long,
        val pendingPoints: Int,
        val lastError: String,
        val lastErrorAtMillis: Long,
        val sinceElapsedMs: Long,
        val capturedElapsedMs: Long,
    )

    private var generation = 0L
    private var sinceElapsedMs = SystemClock.elapsedRealtime()
    private var deliveredPoints = 0L
    private var maxSampleGapNanos = 0L
    private var maxDeliveryDelayNanos = 0L
    private var lastError = ""
    private var lastErrorAtMillis = 0L
    private val listenerLastSample = WeakHashMap<Any, Long>()

    @Synchronized
    fun reset() {
        generation++
        sinceElapsedMs = SystemClock.elapsedRealtime()
        deliveredPoints = 0L
        maxSampleGapNanos = 0L
        maxDeliveryDelayNanos = 0L
        lastError = ""
        lastErrorAtMillis = 0L
        listenerLastSample.clear()
    }

    @Synchronized
    fun generation(): Long = generation

    @Synchronized
    fun forgetListener(listener: Any) {
        listenerLastSample.remove(listener)
    }

    @Synchronized
    fun recordDelivery(session: Long, listener: Any, timestamps: List<Long>, nowNanos: Long) {
        // An IPC started before a new session must not contaminate that session's totals.
        if (session != generation) return
        var previous = listenerLastSample[listener] ?: 0L
        for (timestamp in timestamps) {
            if (timestamp <= 0L) continue
            if (previous > 0L && timestamp > previous) {
                maxSampleGapNanos = maxOf(maxSampleGapNanos, timestamp - previous)
            }
            previous = maxOf(previous, timestamp)
            maxDeliveryDelayNanos = maxOf(maxDeliveryDelayNanos, (nowNanos - timestamp).coerceAtLeast(0L))
            if (deliveredPoints < Long.MAX_VALUE) deliveredPoints++
        }
        listenerLastSample[listener] = previous
    }

    @Synchronized
    fun recordFailure(label: String, failure: Throwable? = null) {
        var cause = failure
        repeat(8) {
            if (cause is InvocationTargetException && cause?.cause != null) cause = cause?.cause
        }
        val kind = cause?.javaClass?.simpleName.orEmpty()
        lastError = (if (kind.isBlank()) label else "$label：$kind")
            .replace('\n', ' ').replace('\r', ' ').take(160)
        lastErrorAtMillis = System.currentTimeMillis()
    }

    @Synchronized
    fun snapshot(listenerCount: Int, pendingPoints: Int): Snapshot = Snapshot(
        deliveredPoints = deliveredPoints,
        listenerCount = listenerCount.coerceAtLeast(0),
        maxSampleGapMs = maxSampleGapNanos / 1_000_000L,
        maxDeliveryDelayMs = maxDeliveryDelayNanos / 1_000_000L,
        pendingPoints = pendingPoints.coerceAtLeast(0),
        lastError = lastError,
        lastErrorAtMillis = lastErrorAtMillis,
        sinceElapsedMs = sinceElapsedMs,
        capturedElapsedMs = SystemClock.elapsedRealtime(),
    )
}
