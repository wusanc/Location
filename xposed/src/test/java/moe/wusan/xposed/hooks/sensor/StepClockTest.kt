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

    @Test fun changingCadencePreservesProgressWithoutImmediateExtraStep() {
        val clock = StepClock()
        clock.configure(0, true, 60)
        assertFalse(clock.poll(500_000_000L))
        clock.configure(500_000_000L, true, 120)
        assertFalse(clock.poll(749_999_999L))
        assertTrue(clock.poll(750_000_000L))
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

    @Test fun slowingDownAlsoPreservesTheCurrentStepsProgress() {
        val clock = StepClock()
        clock.configure(0L, true, 120)
        clock.configure(250_000_000L, true, 60)
        assertFalse(clock.poll(749_999_999L))
        assertTrue(clock.poll(750_000_000L))
    }

    @Test fun frequentCadenceChangesDoNotStarveEvents() {
        val clock = StepClock()
        clock.configure(0L, true, 60)
        var steps = 0
        // Equal time at 60 and 120 steps/min integrates to exactly 90 steps in a minute.
        // The old implementation reset its deadline every 20ms and never emitted a step.
        for (millis in 20L..60_000L step 20) {
            val cadence = if ((millis / 20) % 2 == 0L) 60 else 120
            clock.configure(millis * 1_000_000L, true, cadence)
            if (clock.poll(millis * 1_000_000L)) steps++
        }
        assertEquals(90, steps)
    }

    @Test fun cadenceChangeAtTheDeadlineDoesNotEraseTheEarnedStep() {
        val clock = StepClock()
        clock.configure(0L, true, 60)
        clock.configure(1_000_000_000L, true, 120)
        assertEquals(1L, clock.delayMillis(1_000_000_000L))
        assertTrue(clock.poll(1_000_000_000L))
        assertFalse(clock.poll(1_000_000_000L))
        assertFalse(clock.poll(1_499_999_999L))
        assertTrue(clock.poll(1_500_000_000L))
    }

    @Test fun overdueRateChangeKeepsFractionalProgressAfterThePendingStep() {
        val clock = StepClock()
        clock.configure(0L, true, 60)
        clock.configure(1_250_000_000L, true, 120)
        assertTrue(clock.poll(1_250_000_000L))
        assertFalse(clock.poll(1_250_000_000L))
        // At 1.25s the next old step was 25% complete; the remaining 75% now takes 375ms.
        assertFalse(clock.poll(1_624_999_999L))
        assertTrue(clock.poll(1_625_000_000L))
    }

    @Test fun repeatedOverdueRateChangesCoalesceMissedSteps() {
        val clock = StepClock()
        clock.configure(0L, true, 60)
        clock.configure(10_000_000_000L, true, 120)
        clock.configure(20_000_000_000L, true, 60)
        assertTrue(clock.poll(20_000_000_000L))
        assertFalse(clock.poll(20_000_000_000L))
        assertFalse(clock.poll(20_999_999_999L))
        assertTrue(clock.poll(21_000_000_000L))
    }

    @Test fun disablingClearsPendingStepsAndReenableWaitsAFullInterval() {
        val clock = StepClock()
        clock.configure(0L, true, 60)
        clock.configure(1_250_000_000L, true, 120)
        clock.configure(1_250_000_000L, false, 120)
        assertFalse(clock.poll(1_250_000_000L))
        clock.configure(1_250_000_000L, true, 120)
        assertFalse(clock.poll(1_749_999_999L))
        assertTrue(clock.poll(1_750_000_000L))
    }

    @Test fun invalidCadenceClearsPendingStepsLikeDisabling() {
        val clock = StepClock()
        clock.configure(0L, true, 60)
        clock.configure(1_000_000_000L, true, 120)
        clock.configure(1_000_000_000L, true, 301)
        assertFalse(clock.poll(10_000_000_000L))
        clock.configure(10_000_000_000L, true, 300)
        assertFalse(clock.poll(10_199_999_999L))
        assertTrue(clock.poll(10_200_000_000L))
    }
}
