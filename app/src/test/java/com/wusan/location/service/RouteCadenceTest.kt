package com.wusan.location.service

import com.wusan.location.ui.mock.RoutePace
import com.wusan.location.ui.mock.RouteSpeedJitter
import com.wusan.location.ui.mock.SpeedRamp
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class RouteCadenceTest {
    @Test fun paceAndSingleStepLengthProduceExpectedCadence() {
        assertEquals(166.666666667, RouteCadence.target(RoutePace.toSpeed(360), 1.0), 1e-8)
        assertEquals(200.0, RouteCadence.target(RoutePace.toSpeed(300), 1.0), 1e-8)
        assertEquals(160.0, RouteCadence.target(RoutePace.toSpeed(360), 1000.0 / 960), 1e-8)
    }

    @Test fun changingStepLengthIsSmoothAndEventuallyReachesTheNewRate() {
        val cadence = RouteCadence()
        var current = 0.0
        repeat(40) { current = cadence.advance(3.0, 1.0, 0.1) }
        assertEquals(180.0, current, 1e-8)
        repeat(30) {
            val next = cadence.advance(3.0, 0.6, 0.1)
            assertTrue(abs(next - current) <= 6.000000001)
            current = next
        }
        assertEquals(300.0, current, 1e-8)
    }

    @Test fun pauseResetsImmediatelyAndResumeRampsFromZero() {
        val cadence = RouteCadence()
        repeat(40) { cadence.advance(3.0, 1.0, 0.1) }
        cadence.reset()
        assertEquals(6.0, cadence.advance(3.0, 1.0, 0.1), 1e-8)
        assertEquals(0.0, cadence.advance(0.0, 1.0, 0.1), 0.0)
    }

    @Test fun invalidInputsAndLimitsAreSafe() {
        for (speed in listOf(0.0, -1.0, Double.NaN)) assertEquals(0.0, RouteCadence.target(speed, 1.0), 0.0)
        for (stride in listOf(0.0, -1.0, Double.NaN, 2.1)) assertEquals(0.0, RouteCadence.target(3.0, stride), 0.0)
        assertEquals(300.0, RouteCadence.target(50.0, 0.2), 0.0)
    }

    @Test fun autoCadenceTracksSpeedJitterAndPreservesDistanceStepRelationship() {
        val cadence = RouteCadence()
        val jitter = RouteSpeedJitter(Random(42))
        val base = RoutePace.toSpeed(360)
        var speed = 0.0
        var distance = 0.0
        var integratedSteps = 0.0
        var previousCadence = 0.0
        var minCadence = Double.POSITIVE_INFINITY
        var maxCadence = 0.0
        repeat(6000) { tick ->
            val movement = SpeedRamp.advance(speed, jitter.target(base, 0.1), 0.1)
            val current = cadence.advance(movement.speed, 1.0, 0.1)
            assertTrue(abs(current - previousCadence) <= 6.000000001)
            distance += movement.meters
            integratedSteps += (current + previousCadence) / 2 / 60 * 0.1
            if (tick > 100) {
                minCadence = minOf(minCadence, current)
                maxCadence = maxOf(maxCadence, current)
                assertEquals(movement.speed * 60, current, 1e-8)
            }
            previousCadence = current
            speed = movement.speed
        }
        assertTrue(maxCadence - minCadence > 9.0)
        assertEquals(distance, integratedSteps, 0.1)
        println("CADENCE|pace=6:00|stride=1.00|min=$minCadence|max=$maxCadence|meters=$distance|steps=$integratedSteps")
    }
}
