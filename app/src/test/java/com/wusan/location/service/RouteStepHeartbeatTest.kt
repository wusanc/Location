package com.wusan.location.service

import org.junit.Assert.*
import org.junit.Test

class RouteStepHeartbeatTest {
    @Test fun idleAndPausedRoutesDoNotGenerateSteps() {
        val state = RouteStepHeartbeat()
        assertFalse(state.isActive(10_000_000_000L))
        state.record(10_000_000_000L, 3.0)
        assertTrue(state.isActive(10_100_000_000L))
        state.stop()
        assertFalse(state.isActive(10_100_000_000L))
    }

    @Test fun staleOrInvalidMovementFailsClosed() {
        val state = RouteStepHeartbeat()
        state.record(10_000_000_000L, 3.0)
        assertFalse(state.isActive(12_500_000_000L))
        assertFalse(state.isActive(9_000_000_000L))
        for (speed in listOf(0.0, -1.0, Double.NaN)) {
            state.record(10_000_000_000L, speed)
            assertFalse(state.isActive(10_100_000_000L))
        }
    }

    @Test fun independentAndRouteSwitchesRemainSeparate() {
        val moving = RouteStepHeartbeat.Snapshot(3.0, 180.0)
        val idle = RouteStepHeartbeat.Snapshot()
        val routeOnly = StepSimulationSettings(enabled = false, routeEnabled = true)
        assertFalse(routeOnly.delivery(idle).enabled)
        assertEquals(StepSimulationSettings.Delivery(true, 180), routeOnly.delivery(moving))
        val independent = StepSimulationSettings(enabled = true, cadence = 160, routeEnabled = false)
        assertEquals(StepSimulationSettings.Delivery(true, 160), independent.delivery(idle))
        assertEquals(StepSimulationSettings.Delivery(true, 160), independent.delivery(moving))
        val both = StepSimulationSettings(enabled = true, cadence = 160, routeEnabled = true)
        assertEquals(180, both.delivery(moving).cadence)
        assertEquals(160, both.delivery(idle).cadence)
        assertFalse(StepSimulationSettings(enabled = false, routeEnabled = false).delivery(moving).enabled)
    }

    @Test fun snapshotKeepsCadenceAndSpeedTogetherAndExpiresThemTogether() {
        val state = RouteStepHeartbeat()
        state.record(10_000_000_000L, 3.0, 180.0)
        assertEquals(RouteStepHeartbeat.Snapshot(3.0, 180.0), state.snapshot(10_100_000_000L))
        assertEquals(RouteStepHeartbeat.Snapshot(), state.snapshot(12_500_000_000L))
    }

    @Test fun nearZeroCadenceDoesNotCreateAPhantomOneStepPerMinute() {
        val config = StepSimulationSettings(enabled = true, routeEnabled = true)
        assertFalse(config.delivery(RouteStepHeartbeat.Snapshot(0.001, 0.06)).enabled)
    }
}
