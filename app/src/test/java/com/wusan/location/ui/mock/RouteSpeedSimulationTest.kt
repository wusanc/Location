package com.wusan.location.ui.mock

import moe.wusan.xposed.utils.FakeLoc
import moe.wusan.xposed.utils.RouteSampleClock
import net.sf.geographiclib.Geodesic
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.random.Random

/** Virtual time; production pace, ramp, geometry, progress and reporting policies. No sleeping. */
class RouteSpeedSimulationTest {
    private val start = 30.0 to 106.0
    private fun point(from: Pair<Double, Double>, bearing: Double, meters: Double): Pair<Double, Double> =
        Geodesic.WGS84.Direct(from.first, from.second, bearing, meters).let { it.lat2 to it.lon2 }
    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        Geodesic.WGS84.Inverse(a.first, a.second, b.first, b.second).s12

    @After fun clearMotion() {
        FakeLoc.enable = false
        FakeLoc.motion = FakeLoc.Motion()
        FakeLoc.speed = 3.05
    }

    private inner class Simulation(
        val pace: Int,
        val route: List<Pair<Double, Double>> = listOf(start, point(start, 90.0, 20_000.0)),
        warm: Boolean = false,
        jitter: Boolean = false,
    ) {
        val target = RoutePace.toSpeed(pace)
        private val variation = if (jitter) RouteSpeedJitter(Random(42)) else null
        var position = route.first()
        val progress = RouteProgress()
        var nanos = 10_000_000_000L
        var millis = 1_000_000L
        var elapsed = 0.0
        var measuredMeters = 0.0
        var plannedMeters = 0.0
        var lastSpeed = 0.0
        var lastClock = nanos
        var maxSteadyError = 0.0

        init { if (warm) progress.commit(progress.generation, 1, target) }

        fun step(dt: Double, newTarget: Double = target, transportMs: Long = 0, oldClock: Boolean = false) {
            nanos += (dt * 1_000_000_000).roundToLong()
            millis += (dt * 1000).roundToLong()
            elapsed += dt
            val variedTarget = variation?.target(newTarget, dt) ?: newTarget
            val ramp = SpeedRamp.advance(progress.speed, variedTarget, dt)
            val step = RouteStepper.advance(route, position, max(1, progress.nextIndex), ramp.meters)
            val moved = distance(position, step.position)
            measuredMeters += moved
            plannedMeters += ramp.meters
            val receivedNanos = nanos + transportMs * 1_000_000
            val receivedMillis = millis + transportMs
            val clock = RouteSampleClock.resolve(if (oldClock) 0 else nanos,
                if (oldClock) 0 else millis, receivedNanos, receivedMillis)
            FakeLoc.enable = true
            FakeLoc.speed = newTarget
            FakeLoc.motion = FakeLoc.Motion(ramp.speed, clock.nanos, true, clock.millis)
            val reportTime = FakeLoc.motion.timestamp(receivedNanos, receivedMillis)
            val reportSpeed = FakeLoc.reportedSpeed(receivedNanos)
            assertEquals(ramp.speed, reportSpeed, 1e-12)
            assertEquals(reportTime, FakeLoc.motion.timestamp(receivedNanos + 1_000_000, receivedMillis + 1))
            val reportedDt = (reportTime.first - lastClock) / 1_000_000_000.0
            if (abs(progress.speed - newTarget) < 1e-12 && abs(ramp.speed - newTarget) < 1e-12) {
                maxSteadyError = max(maxSteadyError, abs(moved / reportedDt - newTarget))
            }
            lastClock = reportTime.first
            lastSpeed = reportSpeed
            position = step.position
            assertTrue(progress.commit(progress.generation, step.nextIndex, ramp.speed))
        }

        fun pause(seconds: Double) {
            progress.pause()
            variation?.reset()
            nanos += (seconds * 1_000_000_000).roundToLong()
            millis += (seconds * 1000).roundToLong()
            FakeLoc.motion = FakeLoc.Motion(0.0, nanos, true, millis)
            lastClock = nanos
            assertEquals(0.0, FakeLoc.reportedSpeed(nanos), 0.0)
        }
    }

    private fun row(name: String, pace: Int, elapsed: Double, meters: Double, speed: Double, error: Double) {
        println(String.format(Locale.ROOT, "SIM|%s|%s|%.3f|%.6f|%.9f|%.9f",
            name, RoutePace.format(pace), elapsed, meters, speed, error))
    }

    @Test fun steadyPacesMatchCoordinateBasedMeasurement() {
        for (pace in listOf(180, 210, 240, 300, 330, 360, 420, 480, 600, 1200, 2000)) {
            val sim = Simulation(pace, warm = true)
            repeat(600) { sim.step(0.1) }
            assertEquals(sim.target * 60, sim.measuredMeters, 0.0001)
            assertTrue(sim.maxSteadyError < 0.00001)
            assertEquals(pace, RoutePace.fromSpeed(sim.measuredMeters / sim.elapsed))
            row("steady_60s", pace, sim.elapsed, sim.measuredMeters, sim.lastSpeed, sim.maxSteadyError)
        }
    }

    @Test fun startingFromRestAddsOnlyTheExpectedAccelerationTime() {
        for (pace in listOf(300, 360, 480)) {
            val sim = Simulation(pace)
            while (sim.plannedMeters < 1000) sim.step(0.1)
            val expected = pace + sim.target / (2 * SpeedRamp.ACCELERATION)
            assertTrue(sim.elapsed + 1e-7 >= expected)
            assertTrue(sim.elapsed - expected < 0.100001)
            assertEquals(sim.plannedMeters, sim.measuredMeters, 0.0001)
            row("first_km_from_rest", pace, sim.elapsed, sim.measuredMeters, sim.lastSpeed, sim.maxSteadyError)
        }
    }

    @Test fun reportIntervalsDoNotChangeSteadyPace() {
        for (dt in listOf(0.02, 0.05, 0.1, 0.2, 1.0)) {
            val sim = Simulation(360, warm = true)
            repeat((60 / dt).roundToLong().toInt()) { sim.step(dt) }
            assertEquals(1000.0 / 6, sim.measuredMeters, 0.0001)
            row("interval_${(dt * 1000).toInt()}ms", 360, sim.elapsed, sim.measuredMeters,
                sim.lastSpeed, sim.maxSteadyError)
        }
    }

    @Test fun denseWaypointsDoNotIncreaseSpeed() {
        val route = (0..10_000).map { point(start, 90.0, it / 10.0) }
        val sim = Simulation(360, route, warm = true)
        repeat(1200) { sim.step(0.1) }
        assertEquals(sim.target * 120, sim.measuredMeters, 0.0001)
        assertTrue(sim.maxSteadyError < 0.00001)
        row("dense_10cm_points", 360, sim.elapsed, sim.measuredMeters, sim.lastSpeed, sim.maxSteadyError)
    }

    @Test fun cornerMeasurementsHaveBoundedChordLossAndNeverOverspeed() {
        val route = mutableListOf(start)
        // Non-integral segment length forces turns between reports, rather than on tick boundaries.
        repeat(20) { i -> route.add(point(route.last(), (i % 4) * 90.0, 100.13)) }
        val sim = Simulation(360, route, warm = true)
        repeat(1800) { sim.step(0.1) }
        val loss = sim.plannedMeters - sim.measuredMeters
        assertTrue(loss >= -0.0001 && loss < 0.5)
        assertEquals(360, RoutePace.fromSpeed(sim.measuredMeters / sim.elapsed))
        row("right_angle_corners", 360, sim.elapsed, sim.measuredMeters, sim.lastSpeed, sim.maxSteadyError)
    }

    @Test fun pauseAndResumeDoNotAccumulatePausedDistance() {
        val sim = Simulation(360)
        repeat(600) { sim.step(0.1) }
        val before = sim.position
        val metersBefore = sim.measuredMeters
        val indexBefore = sim.progress.nextIndex
        sim.pause(30.0)
        assertEquals(before, sim.position)
        assertEquals(metersBefore, sim.measuredMeters, 0.0)
        assertEquals(indexBefore, sim.progress.nextIndex)
        sim.step(0.1)
        assertEquals(0.1, sim.lastSpeed, 1e-12)
        assertEquals(0.005, distance(before, sim.position), 1e-7)
        row("resume_first_tick", 360, 0.1, distance(before, sim.position), sim.lastSpeed, 0.0)
    }

    @Test fun accelerationAndDecelerationMatchIntegratedDistance() {
        val sim = Simulation(360, warm = true)
        var previous = sim.progress.speed
        for (target in listOf(RoutePace.toSpeed(240), RoutePace.toSpeed(600), 0.0)) {
            repeat(100) {
                val before = sim.position
                sim.step(0.1, target)
                assertTrue(abs(sim.lastSpeed - previous) <= 0.10000000001)
                assertEquals(sim.plannedMeters, sim.measuredMeters, 0.0001)
                if (target == 0.0 && sim.lastSpeed == 0.0 && previous == 0.0) assertEquals(before, sim.position)
                previous = sim.lastSpeed
            }
        }
        assertEquals(0.0, sim.lastSpeed, 0.0)
        row("changing_paces_then_stop", 360, sim.elapsed, sim.measuredMeters, sim.lastSpeed, 0.0)
    }

    @Test fun capturingClientClockEliminatesTransportLatencySpeedSpikes() {
        val old = Simulation(360, warm = true)
        val fixed = Simulation(360, warm = true)
        var priorLatency = 0L
        repeat(600) { i ->
            val latency = if (i % 2 == 0) 80L else 0L
            // The production loop waits its interval after the previous command returns.
            val dt = 0.1 + priorLatency / 1000.0
            old.step(dt, transportMs = latency, oldClock = true)
            fixed.step(dt, transportMs = latency)
            priorLatency = latency
        }
        assertTrue(old.maxSteadyError > 1.0)
        assertTrue(fixed.maxSteadyError < 0.00001)
        row("latency_old_0_80ms", 360, old.elapsed, old.measuredMeters, old.lastSpeed, old.maxSteadyError)
        row("latency_fixed_0_80ms", 360, fixed.elapsed, fixed.measuredMeters, fixed.lastSpeed, fixed.maxSteadyError)
    }

    @Test fun jitterChangesRealMovementWithoutBiasingAveragePace() {
        val sim = Simulation(360, warm = true, jitter = true)
        var minSpeed = Double.POSITIVE_INFINITY
        var maxSpeed = 0.0
        var previous = sim.target
        repeat(6000) {
            sim.step(0.1)
            minSpeed = kotlin.math.min(minSpeed, sim.lastSpeed)
            maxSpeed = kotlin.math.max(maxSpeed, sim.lastSpeed)
            assertTrue(sim.lastSpeed in (sim.target * 0.97 - 1e-12)..(sim.target * 1.03 + 1e-12))
            assertTrue(abs(sim.lastSpeed - previous) <= 0.10000000001)
            assertEquals(sim.plannedMeters, sim.measuredMeters, 0.001)
            previous = sim.lastSpeed
        }
        assertTrue(maxSpeed - minSpeed > sim.target * 0.05)
        val average = sim.measuredMeters / sim.elapsed
        assertEquals(sim.target, average, sim.target * 0.001)
        println(String.format(Locale.ROOT,
            "JITTER|target=%.9f|min=%.9f|max=%.9f|average=%.9f|distance=%.6f|seconds=%.1f",
            sim.target, minSpeed, maxSpeed, average, sim.measuredMeters, sim.elapsed))
        val beforePause = sim.position
        sim.pause(30.0)
        assertEquals(beforePause, sim.position)
        assertEquals(0.0, FakeLoc.reportedSpeed(sim.nanos), 0.0)
        sim.step(0.1)
        assertEquals(0.1, sim.lastSpeed, 1e-12)
        assertEquals(0.005, distance(beforePause, sim.position), 1e-7)
    }

    @Test fun paceRoundTripsAndZeroInputsAreSafe() {
        for (pace in RoutePace.MIN_SECONDS..RoutePace.MAX_SECONDS) {
            val stored = RoutePace.toSpeed(pace).toFloat().toDouble()
            assertEquals(pace, RoutePace.fromSpeed(stored))
        }
        assertNull(RoutePace.fromSpeed(0.0))
        assertNull(RoutePace.fromSpeed(Double.NaN))
        assertEquals("6:00", RoutePace.format(360))
        assertEquals("5:30", RoutePace.format(330))
    }
}
