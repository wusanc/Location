package moe.wusan.xposed.utils

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MotionReportingTest {
    private val nanos = 10_000_000_000L
    private val millis = 1_000_000L

    @After fun clear() {
        FakeLoc.enable = false
        FakeLoc.speed = 3.05
        FakeLoc.motion = FakeLoc.Motion()
    }

    @Test fun actualSpeedIsIndependentOfTargetAndHasNoRandomNoise() {
        FakeLoc.enable = true
        FakeLoc.speed = 20.0
        FakeLoc.motion = FakeLoc.Motion(0.5, nanos, true, millis)
        repeat(100) { assertEquals(0.5, FakeLoc.reportedSpeed(nanos + 1), 0.0) }
    }

    @Test fun pausedSpeedRemainsZero() {
        FakeLoc.enable = true
        FakeLoc.motion = FakeLoc.Motion(0.0, nanos, true, millis)
        assertEquals(0.0, FakeLoc.reportedSpeed(nanos), 0.0)
        assertEquals(0.0, FakeLoc.reportedSpeed(nanos + 10_000_000_000L), 0.0)
    }

    @Test fun staleAndFutureMotionDoNotReportMovement() {
        FakeLoc.enable = true
        FakeLoc.motion = FakeLoc.Motion(3.0, nanos, true, millis)
        assertEquals(3.0, FakeLoc.reportedSpeed(nanos + 1_999_999_999L), 0.0)
        assertEquals(0.0, FakeLoc.reportedSpeed(nanos + 2_000_000_000L), 0.0)
        assertEquals(0.0, FakeLoc.reportedSpeed(nanos - 1), 0.0)
    }

    @Test fun disabledServiceReportsZero() {
        FakeLoc.motion = FakeLoc.Motion(3.0, nanos, true, millis)
        FakeLoc.enable = false
        assertEquals(0.0, FakeLoc.reportedSpeed(nanos + 1), 0.0)
    }

    @Test fun repeatedRouteReportsKeepTheSampleClock() {
        val sample = FakeLoc.Motion(3.0, nanos, true, millis)
        assertEquals(nanos to millis, sample.timestamp(nanos + 1, millis + 1))
        assertEquals(nanos to millis, sample.timestamp(nanos + 1_000_000_000L, millis + 1000))
    }

    @Test fun nextSampleAdvancesBothClocks() {
        val first = FakeLoc.Motion(3.0, nanos, true, millis)
        val next = FakeLoc.Motion(3.0, nanos + 100_000_000L, true, millis + 100)
        assertNotEquals(first.timestamp(nanos + 200_000_000L, millis + 200),
            next.timestamp(nanos + 200_000_000L, millis + 200))
    }

    @Test fun staleOrNonRouteSamplesUseCurrentClock() {
        val now = nanos + 3_000_000_000L
        val wall = millis + 3000
        assertEquals(now to wall, FakeLoc.Motion(3.0, nanos, true, millis).timestamp(now, wall))
        assertEquals(now to wall, FakeLoc.Motion(0.0, nanos, false, millis).timestamp(now, wall))
    }
}
