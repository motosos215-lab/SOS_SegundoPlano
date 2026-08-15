package com.example.sos_segundoplano.features.history

import com.example.sos_segundoplano.domain.history.RiderTripHistoryLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRoutePreviewTest {
    @Test fun normalizesDistinctEndpointsWithinLogicalPadding() {
        val points = normalizeTripRoutePoints(location(19.0, -100.0), location(20.0, -99.0))

        assertEquals(0.16f, points.startX, 0.001f)
        assertEquals(0.84f, points.endX, 0.001f)
        assertTrue(points.endY < points.startY)
        assertFalse(points.locationsPracticallyCoincide)
    }

    @Test fun preservesGeographicOrientationForNegativeCoordinates() {
        val points = normalizeTripRoutePoints(location(-20.0, -101.0), location(-19.0, -100.0))

        assertTrue(points.endX > points.startX)
        assertTrue(points.endY < points.startY)
    }

    @Test fun nearlyEqualEndpointsReceiveOnlyVisualSeparation() {
        val points = normalizeTripRoutePoints(location(19.4326, -99.1332), location(19.4326, -99.1332))

        assertTrue(points.locationsPracticallyCoincide)
        assertTrue(points.startX < points.endX)
        assertEquals(points.startY, points.endY, 0.001f)
    }

    private fun location(latitude: Double, longitude: Double) = RiderTripHistoryLocation(latitude, longitude)
}
