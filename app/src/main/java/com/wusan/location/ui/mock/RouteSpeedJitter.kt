package com.wusan.location.ui.mock

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Slow, bounded movement variation; each complete wave has zero mean offset. */
class RouteSpeedJitter(private val random: Random = Random.Default) {
    private var phaseSeconds = 0.0
    private var periodSeconds = nextPeriod()

    fun reset() {
        phaseSeconds = 0.0
        periodSeconds = nextPeriod()
    }

    fun target(baseSpeed: Double, seconds: Double): Double {
        require(baseSpeed.isFinite() && baseSpeed >= 0.0)
        require(seconds.isFinite() && seconds >= 0.0)
        if (baseSpeed == 0.0) return 0.0
        phaseSeconds += seconds
        while (phaseSeconds >= periodSeconds) {
            phaseSeconds -= periodSeconds
            periodSeconds = nextPeriod()
        }
        val offset = FRACTION * sin(2 * PI * phaseSeconds / periodSeconds)
        return baseSpeed * (1 + offset)
    }

    private fun nextPeriod(): Double = random.nextDouble(4.0, 8.0)

    companion object {
        const val FRACTION = 0.03
    }
}
