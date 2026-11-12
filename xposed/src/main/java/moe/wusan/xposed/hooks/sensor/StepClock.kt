package moe.wusan.xposed.hooks.sensor

import kotlin.math.roundToLong

/** Monotonic, phase-preserving scheduler; one poll emits at most one step after a stall. */
internal class StepClock {
    private var periodNanos = 0L
    private var nextStepNanos = 0L
    private var stepDue = false

    fun configure(nowNanos: Long, enabled: Boolean, cadence: Int) {
        val newPeriod = if (enabled && cadence in 1..300) 60_000_000_000L / cadence else 0L
        if (newPeriod == periodNanos) return
        if (newPeriod == 0L) {
            reset()
            return
        }
        if (periodNanos == 0L) {
            periodNanos = newPeriod
            nextStepNanos = nowNanos + newPeriod
            stepDue = false
            return
        }

        var remaining = nextStepNanos - nowNanos
        if (remaining <= 0L) {
            // A rate update cannot erase an already completed step. Keep only one pending
            // step after a stall, then preserve the fractional progress of the current step.
            stepDue = true
            remaining = periodNanos - (nowNanos - nextStepNanos) % periodNanos
        }
        val newRemaining = (remaining.toDouble() / periodNanos * newPeriod)
            .roundToLong().coerceIn(1L, newPeriod)
        nextStepNanos = nowNanos + newRemaining
        periodNanos = newPeriod
    }

    fun poll(nowNanos: Long): Boolean {
        if (periodNanos == 0L) return false
        if (nowNanos >= nextStepNanos) {
            stepDue = true
            // Preserve normal phase; discard missed events instead of emitting a burst.
            nextStepNanos += ((nowNanos - nextStepNanos) / periodNanos + 1) * periodNanos
        }
        if (!stepDue) return false
        stepDue = false
        return true
    }

    fun reset() {
        periodNanos = 0L
        nextStepNanos = 0L
        stepDue = false
    }

    fun delayMillis(nowNanos: Long): Long {
        if (periodNanos == 0L) return 1000L
        if (stepDue) return 1L
        return ((nextStepNanos - nowNanos).coerceAtLeast(1L) + 999_999L)
            .div(1_000_000L).coerceIn(1L, 1000L)
    }
}
