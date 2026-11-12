package moe.wusan.xposed.utils

import java.util.ArrayDeque

/** Accepted route samples shared by delivery paths, without consuming another listener's history. */
object RouteLocationHistory {
    private const val MAX_AGE_NANOS = 120_000_000_000L
    private const val MAX_SAMPLES = 6_000
    private val samples = ArrayDeque<Sample>()
    private var epoch = 0L

    data class Sample(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val accuracy: Float,
        val speed: Double,
        val bearing: Double,
        val atNanos: Long,
        val atMillis: Long,
        val positionClockNanos: Long,
    )

    @Synchronized
    fun record(sample: Sample) {
        if (sample.latitude !in -90.0..90.0 || sample.longitude !in -180.0..180.0 ||
            !sample.altitude.isFinite() || !sample.accuracy.isFinite() || sample.accuracy < 0f ||
            !sample.speed.isFinite() || sample.speed < 0.0 || !sample.bearing.isFinite() ||
            sample.atNanos <= 0L || sample.atMillis <= 0L || sample.positionClockNanos < 0L
        ) return
        val previous = samples.peekLast()
        if (previous != null && sample.atNanos <= previous.atNanos) return
        samples.addLast(sample)
        val cutoff = sample.atNanos - MAX_AGE_NANOS
        while (samples.size > MAX_SAMPLES || (samples.peekFirst()?.atNanos ?: Long.MAX_VALUE) < cutoff) {
            samples.removeFirst()
        }
    }

    @Synchronized
    fun clear() {
        epoch++
        samples.clear()
    }

    @Synchronized
    fun generation(): Long = epoch

    @Synchronized
    fun latest(): Sample? = samples.peekLast()

    @Synchronized
    fun atOrBefore(atNanos: Long): Sample? {
        val iterator = samples.descendingIterator()
        while (iterator.hasNext()) {
            val sample = iterator.next()
            if (sample.atNanos <= atNanos) return sample
        }
        return null
    }

    @Synchronized
    fun exact(atNanos: Long): Sample? {
        val iterator = samples.descendingIterator()
        while (iterator.hasNext()) {
            val sample = iterator.next()
            if (sample.atNanos == atNanos) return sample
            if (sample.atNanos < atNanos) return null
        }
        return null
    }

    @Synchronized
    fun countAfter(atNanos: Long): Int = samples.count { it.atNanos > atNanos }

    @Synchronized
    fun after(atNanos: Long): List<Sample> = samples.filter { it.atNanos > atNanos }
}


