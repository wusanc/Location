package moe.wusan.xposed.hooks.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepClockTest {
    @Test fun fixedCadenceProduces160StepsInAMinute() {
        val clock = StepClock()
        clock.configure(0L, true, 160)
        var steps = 0
        for (millis in 1L..60_000L) if (clock.poll(millis * 1_000_000L)) steps++
        assertEquals(160, steps)
    }

    @Test fun disabledAndInvalidRatesProduceNoEvents() {
        val clock = StepClock()
        for (rate in listOf(-1, 0, 301)) {
            clock.configure(0, true, rate)
            assertFalse(clock.poll(60_000_000_000L))
        }
        clock.configure(0, false, 160)
        assertFalse(clock.poll(60_000_000_000L))
    }

    @Test fun stalledSchedulerDoesNotBurstOnResume() {
        val clock = StepClock()
        clock.configure(0, true, 160)
        assertTrue(clock.poll(30_000_000_000L))
        assertFalse(clock.poll(30_000_000_000L))
        assertFalse(clock.poll(30_001_000_000L))
        assertTrue(clock.poll(30_375_000_000L))
    }

    @Test fun changingCadenceStartsANewIntervalWithoutImmediateExtraStep() {
        val clock = StepClock()
        clock.configure(0, true, 60)
        assertFalse(clock.poll(500_000_000L))
        clock.configure(500_000_000L, true, 120)
        assertFalse(clock.poll(999_000_000L))
        assertTrue(clock.poll(1_000_000_000L))
        clock.configure(1_000_000_000L, false, 120)
        assertFalse(clock.poll(20_000_000_000L))
        clock.configure(20_000_000_000L, true, 120)
        assertFalse(clock.poll(20_000_000_000L))
        assertTrue(clock.poll(20_500_000_000L))
    }

    @Test fun repeatedConfigReadsDoNotPostponeSteps() {
        val clock = StepClock()
        clock.configure(0L, true, 160)
        clock.configure(200_000_000L, true, 160)
        assertTrue(clock.poll(375_000_000L))
        clock.reset()
        assertFalse(clock.poll(50_000_000_000L))
    }

    @Test fun timerSleepsUntilNextStepAndStillPollsSettingsAtLeastOncePerSecond() {
        val clock = StepClock()
        clock.configure(0L, true, 160)
        assertEquals(375L, clock.delayMillis(0L))
        assertEquals(1L, clock.delayMillis(374_500_000L))
        clock.configure(0L, true, 1)
        assertEquals(1000L, clock.delayMillis(0L))
        clock.reset()
        assertEquals(1000L, clock.delayMillis(0L))
    }
}
