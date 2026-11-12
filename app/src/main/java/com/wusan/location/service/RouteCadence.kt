package com.wusan.location.service

/** Pace drives actual speed; single-step length maps that speed to steps per minute. */
class RouteCadence {
    private var current = 0.0

    fun advance(speed: Double, stepLength: Double, seconds: Double): Double {
        require(seconds.isFinite() && seconds >= 0.0)
        val target = target(speed, stepLength)
        if (target == 0.0) {
            current = 0.0
            return 0.0
        }
        val maxChange = MAX_CHANGE_PER_SECOND * seconds.coerceAtMost(2.0)
        current += (target - current).coerceIn(-maxChange, maxChange)
        return current
    }

    fun reset() { current = 0.0 }

    companion object {
        const val DEFAULT_STEP_LENGTH = 1.0
        const val MIN_STEP_LENGTH = 0.2
        const val MAX_STEP_LENGTH = 2.0
        const val MAX_CADENCE = 300.0
        const val MAX_CHANGE_PER_SECOND = 60.0

        fun validStepLength(meters: Double): Boolean =
            meters.isFinite() && meters in MIN_STEP_LENGTH..MAX_STEP_LENGTH

        fun target(speed: Double, stepLength: Double): Double {
            if (!speed.isFinite() || speed <= 0.0 || !validStepLength(stepLength)) return 0.0
            return (speed * 60.0 / stepLength).coerceAtMost(MAX_CADENCE)
        }
    }
}
