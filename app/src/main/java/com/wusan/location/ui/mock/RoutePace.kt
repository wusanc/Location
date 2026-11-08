package com.wusan.location.ui.mock

import java.util.Locale
import kotlin.math.roundToInt

/** User input is seconds per kilometre; movement continues to use metres per second. */
object RoutePace {
    const val MIN_SECONDS = 20
    const val MAX_SECONDS = 2000
    const val DEFAULT_SECONDS = 300

    fun fromSpeed(metersPerSecond: Double): Int? {
        if (!metersPerSecond.isFinite() || metersPerSecond <= 0.0) return null
        val seconds = 1000.0 / metersPerSecond
        if (!seconds.isFinite() || seconds > Int.MAX_VALUE) return null
        return seconds.roundToInt().coerceAtLeast(1)
    }

    fun toSpeed(secondsPerKilometer: Int): Double {
        require(secondsPerKilometer in MIN_SECONDS..MAX_SECONDS)
        return 1000.0 / secondsPerKilometer
    }

    fun format(secondsPerKilometer: Int): String =
        String.format(Locale.ROOT, "%d:%02d", secondsPerKilometer / 60, secondsPerKilometer % 60)
}
