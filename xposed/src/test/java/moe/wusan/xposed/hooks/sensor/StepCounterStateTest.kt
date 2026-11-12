package moe.wusan.xposed.hooks.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

class StepCounterStateTest {
    @Test fun motionlessPhoneGetsAnInitialBaselineAndIncrementingEvents() {
        val state = StepCounterState()
        assertEquals(0f, state.baseline(), 0f)
        assertEquals(1f, state.step(), 0f)
        assertEquals(2f, state.step(), 0f)
    }

    @Test fun physicalEventsRemainUntouchedBeforeAnySimulation() {
        val state = StepCounterState()
        assertEquals(125f, state.observePhysical(125f, 1, false), 0f)
        assertEquals(130f, state.observePhysical(130f, 2, false), 0f)
        assertEquals(130f, state.baseline(), 0f)
        assertEquals(131f, state.step(), 0f)
    }

    @Test fun latePhysicalBaselineCannotJumpTheSyntheticCounter() {
        val state = StepCounterState()
        state.baseline()
        state.step()
        assertEquals(1f, state.observePhysical(9000f, 1, true), 0f)
        assertEquals(2f, state.step(), 0f)
        assertEquals(2f, state.observePhysical(9010f, 2, true), 0f)
        assertEquals(3f, state.step(), 0f)
    }

    @Test fun togglingUsesOnlyNewPhysicalDeltasAndNeverDropsTheOffset() {
        val state = StepCounterState()
        state.observePhysical(100f, 1, false)
        state.baseline()
        repeat(20) { state.step() }
        assertEquals(120f, state.observePhysical(101f, 2, true), 0f)
        assertEquals(122f, state.observePhysical(103f, 3, false), 0f)
        assertEquals(123f, state.observePhysical(104f, 4, false), 0f)
        assertEquals(123f, state.baseline(), 0f)
        assertEquals(124f, state.step(), 0f)
        assertEquals(124f, state.observePhysical(105f, 5, true), 0f)
    }

    @Test fun firstPhysicalEventAfterDisablingOnlyEstablishesItsAnchor() {
        val state = StepCounterState()
        state.baseline()
        repeat(10) { state.step() }
        assertEquals(10f, state.observePhysical(500f, 1, false), 0f)
        assertEquals(11f, state.observePhysical(501f, 2, false), 0f)
    }

    @Test fun duplicateAndOutOfOrderEventsFromMultipleListenersCountOnlyOnce() {
        val state = StepCounterState()
        state.observePhysical(100f, 10, false)
        state.baseline()
        state.step()
        assertEquals(106f, state.observePhysical(105f, 20, false), 0f)
        assertEquals(106f, state.observePhysical(105f, 20, false), 0f)
        assertEquals(106f, state.observePhysical(102f, 15, false), 0f)
        assertEquals(107f, state.observePhysical(106f, 21, false), 0f)
    }

    @Test fun newAndReregisteredConsumersShareTheCurrentBaseline() {
        val state = StepCounterState()
        assertEquals(0f, state.baseline(), 0f)
        assertEquals(0f, state.baseline(), 0f)
        repeat(8) { state.step() }
        assertEquals(8f, state.baseline(), 0f)
        assertEquals(8f, state.baseline(), 0f)
        assertEquals(9f, state.step(), 0f)
    }

    @Test fun hardwareResetReanchorsWithoutRollingBackAndContinuesCounting() {
        val state = StepCounterState()
        state.observePhysical(100f, 1, false)
        state.baseline()
        state.step()
        assertEquals(101f, state.observePhysical(0f, 2, false), 0f)
        assertEquals(102f, state.observePhysical(1f, 3, false), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPhysicalCountIsRejected() {
        StepCounterState().observePhysical(Float.NaN, 1, false)
    }
}
