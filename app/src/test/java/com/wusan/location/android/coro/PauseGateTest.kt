package com.wusan.location.android.coro

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PauseGateTest {
    @Test fun initiallyPausedWaitsUntilResume() = runBlocking {
        val gate = PauseGate()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitRunning() }
        assertFalse(waiter.isCompleted)
        gate.resume()
        assertTrue(gate.isCurrent(withTimeout(1000) { waiter.await() }))
    }

    @Test fun pauseResumeInvalidatesAPreviouslyScheduledTick() = runBlocking {
        val gate = PauseGate()
        gate.resume()
        val old = gate.awaitRunning()
        gate.pause()
        gate.resume()
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(gate.awaitRunning()))
    }

    @Test fun repeatedCallsDoNotQueueOldCommands() = runBlocking {
        var pauses = 0
        val gate = PauseGate(onPause = { pauses++ })
        repeat(1000) { gate.pause() }
        gate.resume()
        val revision = gate.awaitRunning()
        repeat(1000) { gate.resume() }
        assertTrue(gate.isCurrent(revision))
        repeat(1000) { gate.pause() }
        assertEquals(1, pauses)
    }

    @Test fun pausedWaitCanBeCancelled() = runBlocking {
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { PauseGate().awaitRunning() }
        waiter.cancel()
        waiter.join()
        assertTrue(waiter.isCancelled)
    }

    @Test fun movementModesAreMutuallyExclusive() = runBlocking {
        lateinit var rocker: CoroutineController
        val route = CoroutineRouteMock(onResume = { rocker.pause() })
        rocker = CoroutineController(onResume = { route.pause() })
        route.resume()
        assertFalse(route.isPaused)
        assertTrue(rocker.isPaused)
        rocker.resume()
        assertTrue(route.isPaused)
        assertFalse(rocker.isPaused)
    }
}
