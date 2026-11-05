package moe.wusan.xposed.hooks.sensor

/** Monotonic step scheduler. One poll emits at most one event, even after a long sleep. */
internal class StepClock {
    private var periodNanos = 0L
    private var nextStepNanos = 0L

    fun configure(nowNanos: Long, enabled: Boolean, cadence: Int) {
        val newPeriod = if (enabled && cadence in 1..300) 60_000_000_000L / cadence else 0L
        if (periodNanos != newPeriod) {
            periodNanos = newPeriod
            nextStepNanos = if (newPeriod == 0L) 0L else nowNanos + newPeriod
        }
    }

    fun poll(nowNanos: Long): Boolean {
        if (periodNanos == 0L || nowNanos < nextStepNanos) return false
        // Keep normal timer jitter from accumulating. Discard missed events after a stall.
        nextStepNanos += ((nowNanos - nextStepNanos) / periodNanos + 1) * periodNanos
        return true
    }

    fun reset() {
        periodNanos = 0L
        nextStepNanos = 0L
    }

    fun delayMillis(nowNanos: Long): Long {
        if (periodNanos == 0L) return 1000L
        return ((nextStepNanos - nowNanos).coerceAtLeast(1L) + 999_999L)
            .div(1_000_000L).coerceIn(1L, 1000L)
    }
}
