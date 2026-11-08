package com.wusan.location.ui.mock

import org.junit.Assert.*
import org.junit.Test

class RouteProgressTest {
    @Test fun acceptedStepSurvivesPauseWithoutRestoringSpeed() {
        val progress = RouteProgress()
        val generation = progress.generation
        progress.commit(generation, 1, 3.0)
        progress.pause()
        assertTrue(progress.commit(generation, 2, null))
        assertEquals(2, progress.nextIndex)
        assertEquals(0.0, progress.speed, 0.0)
    }

    @Test fun resetRejectsAnOldInFlightStep() {
        val progress = RouteProgress()
        val oldGeneration = progress.generation
        progress.reset()
        assertFalse(progress.commit(oldGeneration, 50, 10.0))
        assertEquals(0, progress.nextIndex)
        assertEquals(0.0, progress.speed, 0.0)
    }

    @Test fun resumedStepCanContinueFromAcceptedIndex() {
        val progress = RouteProgress()
        val generation = progress.generation
        progress.pause()
        progress.commit(generation, 3, null)
        progress.commit(generation, 4, 0.1)
        assertEquals(4, progress.nextIndex)
        assertEquals(0.1, progress.speed, 0.0)
    }

    @Test fun resettingCompletedRouteAllowsReplay() {
        val progress = RouteProgress()
        progress.commit(progress.generation, 100, 3.0)
        progress.reset()
        assertEquals(0, progress.nextIndex)
        assertTrue(progress.commit(progress.generation, 1, 0.1))
        assertEquals(1, progress.nextIndex)
    }
}
