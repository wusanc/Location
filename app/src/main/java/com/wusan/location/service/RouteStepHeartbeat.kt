package com.wusan.location.service

/** A fresh immutable snapshot keeps cadence and movement state from different ticks apart. */
class RouteStepHeartbeat {
    data class Snapshot(val speed: Double = 0.0, val cadence: Double = 0.0) {
        val active: Boolean get() = speed > 0.0
    }
    private data class State(val nanos: Long = 0, val sample: Snapshot = Snapshot())
    @Volatile private var state = State()

    fun record(nowNanos: Long, speed: Double, cadence: Double = 0.0) {
        val validSpeed = speed.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val validCadence = if (validSpeed > 0.0 && cadence.isFinite()) cadence.coerceIn(0.0, 300.0) else 0.0
        state = State(nowNanos, Snapshot(validSpeed, validCadence))
    }

    fun stop() { state = State() }

    fun snapshot(nowNanos: Long): Snapshot {
        val current = state
        return if (nowNanos - current.nanos in 0L until 2_500_000_000L) current.sample else Snapshot()
    }

    fun isActive(nowNanos: Long): Boolean = snapshot(nowNanos).active
}
