package moe.wusan.xposed.utils

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class RoutePositionJitterTest {
    private val start = 30.0 to 106.0
    private val second = 1_000_000_000L

    @Test fun sameSampleAlwaysHasTheSameOutputAndDoesNotChangeCanonicalPoint() {
        val canonical = start
        val expected = RoutePositionJitter.point(start.first, start.second, 31 * second, 5.0)
        repeat(100) {
            assertEquals(expected, RoutePositionJitter.point(start.first, start.second, 31 * second, 5.0))
        }
        assertEquals(start, canonical)
        assertTrue(distance(start, expected) > 0.1)
    }

    @Test fun zeroAccuracyAndInitialPhaseReturnExactCanonicalCoordinates() {
        for (accuracy in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(start, RoutePositionJitter.point(start.first, start.second, 31 * second, accuracy))
        }
        assertEquals(start, RoutePositionJitter.point(start.first, start.second, 0L, 5.0))
    }

    @Test fun displacementAndDriftRateRemainBoundedAcrossSamplingIntervals() {
        for (interval in listOf(20_000_000L, 100_000_000L, second)) {
            for (accuracy in listOf(0.3, 1.5, 100.0)) {
                var previous = start
                val samples = (600 * second / interval).toInt()
                repeat(samples) { index ->
                    val point = RoutePositionJitter.point(start.first, start.second,
                        (index + 1) * interval, accuracy)
                    assertTrue(distance(start, point) <= minOf(accuracy, 1.5) + 0.00001)
                    assertTrue(distance(previous, point) / (interval / 1_000_000_000.0) < 0.12)
                    previous = point
                }
            }
        }
    }

    @Test fun activityClockFreezesAcrossThirtySecondPauseAndFirstResumeSample() {
        val running = FakeLoc.Motion(3.0, 10 * second, true, 1000L, 8 * second)
        val paused = running.copy(speed = 0.0, atNanos = 11 * second)
        val resumedAt = 41 * second
        val resumeClock = RoutePositionJitter.advanceClock(paused.positionClockNanos,
            paused.atNanos, resumedAt, paused.route, paused.speed)
        assertEquals(running.positionClockNanos, paused.positionClockNanos)
        assertEquals(running.positionClockNanos, resumeClock)
        assertEquals(RoutePositionJitter.point(start.first, start.second, running.positionClockNanos, 5.0),
            RoutePositionJitter.point(start.first, start.second, resumeClock, 5.0))
        assertEquals(resumeClock + second / 10, RoutePositionJitter.advanceClock(
            resumeClock, resumedAt, resumedAt + second / 10, true, 0.1))
    }

    @Test fun activityClockResetsForNewRouteAndCapsStalledOrBackwardsSamples() {
        assertEquals(0L, RoutePositionJitter.advanceClock(100 * second, second, 40 * second, false, 3.0))
        assertEquals(10 * second, RoutePositionJitter.advanceClock(8 * second, second, 40 * second, true, 3.0))
        assertEquals(8 * second, RoutePositionJitter.advanceClock(8 * second, 5 * second, second, true, 3.0))
        assertEquals(Long.MAX_VALUE, RoutePositionJitter.advanceClock(Long.MAX_VALUE - 1,
            second, 2 * second, true, 3.0))
    }

    @Test fun tenMinuteRunningDistanceHasLessThanPointTwoPercentErrorAndSlowWalkingLessThanPointSix() {
        val radius = 6_371_000.0
        val dt = 0.1
        for (speed in listOf(0.5, 1.5, 1000.0 / 360.0, 5.0)) {
            for (bearing in listOf(0.0, 90.0, 225.0)) {
                var measured = 0.0
                var canonicalDistance = 0.0
                var previous = start
                var previousCanonical = start
                repeat(6000) { index ->
                    val elapsed = (index + 1) * dt
                    val meters = speed * elapsed
                    val angle = Math.toRadians(bearing)
                    val canonical = (start.first + Math.toDegrees(meters * cos(angle) / radius)) to
                        (start.second + Math.toDegrees(meters * sin(angle) / (radius * cos(Math.toRadians(start.first)))))
                    val output = RoutePositionJitter.point(canonical.first, canonical.second,
                        (index + 1) * second / 10, 5.0)
                    canonicalDistance += distance(previousCanonical, canonical)
                    measured += distance(previous, output)
                    previous = output
                    previousCanonical = canonical
                }
                val error = abs(measured - canonicalDistance) / canonicalDistance
                val limit = if (speed < 1.5) 0.006 else 0.002
                assertTrue("speed=$speed bearing=$bearing error=$error limit=$limit", error < limit)
                println("GPS_DRIFT|speed=$speed|bearing=$bearing|canonical=$canonicalDistance|measured=$measured|error=$error")
            }
        }
    }

    @Test fun sphericalOffsetStaysFiniteAtPolesAndAcrossDateLine() {
        for (point in listOf(90.0 to 0.0, -90.0 to 0.0, 30.0 to 179.9999999)) {
            val output = RoutePositionJitter.point(point.first, point.second, 10 * second, 5.0)
            assertTrue(output.first in -90.0..90.0)
            assertTrue(output.second in -180.0..180.0)
            assertTrue(distance(point, output) <= 1.5 + 0.001)
        }
    }

    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dLat = Math.toRadians(b.first - a.first)
        val dLon = Math.toRadians(b.second - a.second)
        val halfLatitude = sin(dLat / 2.0)
        val halfLongitude = sin(dLon / 2.0)
        val h = (halfLatitude * halfLatitude + cos(Math.toRadians(a.first)) *
            cos(Math.toRadians(b.first)) * halfLongitude * halfLongitude).coerceIn(0.0, 1.0)
        return 2.0 * 6_371_000.0 * atan2(sqrt(h), sqrt(1.0 - h))
    }
}
