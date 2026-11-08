package com.wusan.location.ui.mock

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class RouteSpeedJitterTest {
    @Test fun variationRemainsBoundedAtAllSupportedSpeeds() {
        for (base in listOf(0.5, 2.777777778, 50.0)) {
            val jitter = RouteSpeedJitter(Random(7))
            repeat(3000) {
                val target = jitter.target(base, 0.02)
                assertTrue(target in base * 0.97..base * 1.03)
            }
        }
    }

    @Test fun zeroTargetNeverProducesMovement() {
        val jitter = RouteSpeedJitter(Random(7))
        repeat(100) { assertEquals(0.0, jitter.target(0.0, 0.1), 0.0) }
    }

    @Test fun variationIsContinuousAcrossRandomPeriodBoundaries() {
        val jitter = RouteSpeedJitter(Random(7))
        var previous = 3.0
        repeat(10000) {
            val speed = jitter.target(3.0, 0.02)
            // Maximum sine derivative: base * fraction * 2π / minimum period.
            assertTrue(abs(speed - previous) <= 3 * 0.03 * 2 * Math.PI / 4 * 0.02 + 1e-10)
            previous = speed
        }
    }

    @Test fun resetRestartsNearTheBaseRatherThanRestoringAnOldOffset() {
        val jitter = RouteSpeedJitter(Random(7))
        repeat(100) { jitter.target(3.0, 0.1) }
        jitter.reset()
        assertEquals(3.0, jitter.target(3.0, 0.0), 0.0)
    }
}
