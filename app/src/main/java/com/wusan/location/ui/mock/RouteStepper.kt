package com.wusan.location.ui.mock

import net.sf.geographiclib.Geodesic

/** Walks a polyline using one shared distance budget, including every crossed segment. */
object RouteStepper {
    data class Step(val position: Pair<Double, Double>, val nextIndex: Int, val bearing: Double?)

    fun isValid(route: List<Pair<Double, Double>>): Boolean =
        route.isNotEmpty() && route.all { validPoint(it) }

    private fun validPoint(point: Pair<Double, Double>): Boolean =
        point.first in -90.0..90.0 && point.second in -180.0..180.0

    fun advance(
        route: List<Pair<Double, Double>>,
        current: Pair<Double, Double>,
        nextIndex: Int,
        meters: Double,
    ): Step {
        require(validPoint(current) && meters.isFinite() && meters >= 0)
        require(nextIndex in 0..route.size)
        var position = current
        var index = nextIndex
        var remaining = meters
        var bearing: Double? = null
        while (index < route.size) {
            val target = route[index]
            require(validPoint(target))
            val segment = Geodesic.WGS84.Inverse(position.first, position.second, target.first, target.second)
            if (segment.s12 == 0.0) {
                index++
                continue
            }
            if (remaining <= 0.0) break
            if (segment.s12 <= remaining) {
                remaining = (remaining - segment.s12).coerceAtLeast(0.0)
                position = target
                bearing = (segment.azi2 + 360.0) % 360.0
                index++
            } else {
                val end = Geodesic.WGS84.Direct(position.first, position.second, segment.azi1, remaining)
                position = end.lat2 to end.lon2
                bearing = (end.azi2 + 360.0) % 360.0
                break
            }
        }
        return Step(position, index, bearing)
    }
}
