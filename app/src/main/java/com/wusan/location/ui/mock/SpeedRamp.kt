package com.wusan.location.ui.mock

import kotlin.math.abs
import kotlin.math.min

/** Integrates a bounded acceleration, including the constant-speed part of a tick. */
object SpeedRamp {
    const val ACCELERATION = 1.0 // m/s²
    data class Step(val speed: Double, val meters: Double)

    fun advance(current: Double, target: Double, seconds: Double): Step {
        require(current.isFinite() && current >= 0 && target.isFinite() && target >= 0)
        require(seconds.isFinite() && seconds >= 0)
        val rampTime = min(seconds, abs(target - current) / ACCELERATION)
        val direction = if (target >= current) 1.0 else -1.0
        val speed = (current + direction * ACCELERATION * rampTime).coerceAtLeast(0.0)
        val meters = (current + speed) * rampTime / 2 + speed * (seconds - rampTime)
        return Step(speed, meters)
    }
}
