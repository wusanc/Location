package com.wusan.location.ui.mock

import net.sf.geographiclib.Geodesic
import org.junit.Assert.*
import org.junit.Test

class RouteStepperTest {
    private val start = 30.0 to 106.0
    private fun point(from: Pair<Double, Double>, bearing: Double, meters: Double): Pair<Double, Double> =
        Geodesic.WGS84.Direct(from.first, from.second, bearing, meters).let { it.lat2 to it.lon2 }
    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        Geodesic.WGS84.Inverse(a.first, a.second, b.first, b.second).s12

    @Test fun denseWaypointsDoNotAddFreeDistance() {
        val route = (0..100).map { point(start, 90.0, it / 10.0) }
        val step = RouteStepper.advance(route, start, 1, 0.25)
        assertEquals(0.25, distance(start, step.position), 1e-7)
        assertEquals(3, step.nextIndex)
    }

    @Test fun budgetIsSharedAcrossACorner() {
        val corner = point(start, 0.0, 1.0)
        val end = point(corner, 90.0, 10.0)
        val step = RouteStepper.advance(listOf(start, corner, end), start, 1, 1.5)
        assertEquals(0.5, distance(corner, step.position), 1e-7)
        assertEquals(2, step.nextIndex)
    }

    @Test fun duplicatePointsAreSkippedWithoutSpendingDistance() {
        val end = point(start, 0.0, 10.0)
        val step = RouteStepper.advance(listOf(start, start, start, end), start, 1, 0.05)
        assertEquals(0.05, distance(start, step.position), 1e-7)
        assertEquals(3, step.nextIndex)
    }

    @Test fun lowSpeedTicksAccumulateWithoutSnapping() {
        val route = listOf(start, point(start, 0.0, 100.0))
        var position = start
        repeat(200) { position = RouteStepper.advance(route, position, 1, 0.05).position }
        assertEquals(10.0, distance(start, position), 1e-5)
    }

    @Test fun zeroBudgetDoesNotMoveToNextPoint() {
        val step = RouteStepper.advance(listOf(start, point(start, 0.0, 1.0)), start, 1, 0.0)
        assertEquals(start, step.position)
        assertEquals(1, step.nextIndex)
    }

    @Test fun finishingClampsToEndpoint() {
        val end = point(start, 0.0, 1.0)
        val step = RouteStepper.advance(listOf(start, end), start, 1, 100.0)
        assertEquals(end, step.position)
        assertEquals(2, step.nextIndex)
    }

    @Test fun antimeridianUsesShortestSegment() {
        val origin = 0.0 to 179.9999
        val route = listOf(origin, 0.0 to -179.9999)
        val step = RouteStepper.advance(route, origin, 1, 15.0)
        assertEquals(15.0, distance(origin, step.position), 1e-7)
        assertTrue(step.position.second < 0)
    }

    @Test fun routeValidationRejectsEmptyAndInvalidCoordinates() {
        assertFalse(RouteStepper.isValid(emptyList()))
        assertFalse(RouteStepper.isValid(listOf(Double.NaN to 0.0)))
        assertFalse(RouteStepper.isValid(listOf(91.0 to 0.0)))
        assertTrue(RouteStepper.isValid(listOf(start)))
    }
}
