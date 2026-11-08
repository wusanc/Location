package com.wusan.location.ui.mock

import org.junit.Assert.*
import org.junit.Test

class SpeedRampTest {
    @Test fun accelerationUsesIntegratedDistance() {
        val step = SpeedRamp.advance(0.0, 3.0, 0.1)
        assertEquals(0.1, step.speed, 1e-12)
        assertEquals(0.005, step.meters, 1e-12)
    }

    @Test fun reachingTargetMidTickIncludesConstantSpeedDistance() {
        val step = SpeedRamp.advance(0.0, 0.5, 1.0)
        assertEquals(0.5, step.speed, 1e-12)
        assertEquals(0.375, step.meters, 1e-12)
    }

    @Test fun brakingToZeroNeverReverses() {
        val step = SpeedRamp.advance(0.5, 0.0, 1.0)
        assertEquals(0.0, step.speed, 0.0)
        assertEquals(0.125, step.meters, 1e-12)
    }

    @Test fun splittingTimeDoesNotChangeSpeedOrDistance() {
        for (target in listOf(0.0, 0.5, 3.0, 10.0)) {
            var speed = 2.0
            var meters = 0.0
            repeat(100) {
                val step = SpeedRamp.advance(speed, target, 0.02)
                speed = step.speed
                meters += step.meters
            }
            val whole = SpeedRamp.advance(2.0, target, 2.0)
            assertEquals(whole.speed, speed, 1e-10)
            assertEquals(whole.meters, meters, 1e-10)
        }
    }

    @Test fun rapidTargetChangesRespectAccelerationLimit() {
        var speed = 0.0
        for (target in listOf(10.0, 0.0, 20.0, 0.5, 5.0)) {
            val step = SpeedRamp.advance(speed, target, 0.1)
            assertTrue(kotlin.math.abs(step.speed - speed) <= 0.10000000001)
            assertTrue(step.meters >= 0)
            speed = step.speed
        }
    }

    @Test fun zeroTimeDoesNotMoveOrChangeSpeed() {
        val step = SpeedRamp.advance(2.0, 10.0, 0.0)
        assertEquals(2.0, step.speed, 0.0)
        assertEquals(0.0, step.meters, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSpeedIsRejected() { SpeedRamp.advance(0.0, Double.NaN, 0.1) }
}
