package moe.wusan.xposed.utils

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Smooth output-only GPS drift. Never feed these coordinates back into route progress. */
object RoutePositionJitter {
    const val MAX_METERS = 1.5
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MAX_CLOCK_STEP_NANOS = 2_000_000_000L

    /** Resume starts at the paused phase; a delayed command cannot cause a large phase jump. */
    fun advanceClock(
        clockNanos: Long,
        previousAtNanos: Long,
        sampleAtNanos: Long,
        wasRoute: Boolean,
        previousSpeed: Double,
    ): Long {
        if (!wasRoute) return 0L
        val clock = clockNanos.coerceAtLeast(0L)
        if (!previousSpeed.isFinite() || previousSpeed <= 0.0) return clock
        val elapsed = if (previousAtNanos >= 0L && sampleAtNanos > previousAtNanos)
            (sampleAtNanos - previousAtNanos).coerceAtMost(MAX_CLOCK_STEP_NANOS) else 0L
        return if (Long.MAX_VALUE - clock < elapsed) Long.MAX_VALUE else clock + elapsed
    }

    /** East/north offset is bounded by min(accuracy, 1.5m), with drift rate below 0.12m/s. */
    fun point(
        latitude: Double,
        longitude: Double,
        positionClockNanos: Long,
        accuracyMeters: Double,
    ): Pair<Double, Double> {
        if (!accuracyMeters.isFinite() || accuracyMeters <= 0.0 || positionClockNanos <= 0L)
            return latitude to longitude
        val amplitude = min(MAX_METERS, accuracyMeters) / sqrt(2.0)
        val seconds = positionClockNanos / 1_000_000_000.0
        val east = amplitude * (0.72 * sin(2.0 * PI * seconds / 71.0) +
            0.28 * sin(2.0 * PI * seconds / 113.0))
        val north = amplitude * (0.72 * sin(2.0 * PI * seconds / 89.0) +
            0.28 * sin(2.0 * PI * seconds / 137.0))
        val angularDistance = hypot(east, north) / EARTH_RADIUS_METERS
        if (angularDistance == 0.0) return latitude to longitude
        val bearing = atan2(east, north)
        val lat = Math.toRadians(latitude)
        val lon = Math.toRadians(longitude)
        val outputLat = asin((sin(lat) * cos(angularDistance) +
            cos(lat) * sin(angularDistance) * cos(bearing)).coerceIn(-1.0, 1.0))
        val outputLon = lon + atan2(sin(bearing) * sin(angularDistance) * cos(lat),
            cos(angularDistance) - sin(lat) * sin(outputLat))
        val normalizedLon = ((Math.toDegrees(outputLon) + 540.0) % 360.0) - 180.0
        return Math.toDegrees(outputLat) to normalizedLon
    }
}
