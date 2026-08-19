package com.example.sos_segundoplano.features.history

import com.example.sos_segundoplano.domain.history.RiderTripRoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRoutePreviewTest {
    @Test fun normalizesOnlyRealPointsAndPreservesOrder() {
        val route = listOf(
            point(1, 19.4300, -99.1400),
            point(2, 19.4350, -99.1350),
            point(3, 19.4400, -99.1300)
        )

        val normalized = normalizeRealTripRoute(route)

        assertEquals(route.size, normalized.size)
        assertTrue(normalized[0].x < normalized[1].x)
        assertTrue(normalized[1].x < normalized[2].x)
        assertTrue(normalized[0].y > normalized[1].y)
        assertTrue(normalized[1].y > normalized[2].y)
    }

    @Test fun singleRealPointIsCenteredWithoutInventingEndpoint() {
        val normalized = normalizeRealTripRoute(listOf(point(1, 19.4326, -99.1332)))

        assertEquals(1, normalized.size)
        assertEquals(0.5f, normalized.single().x, 0.001f)
        assertEquals(0.5f, normalized.single().y, 0.001f)
    }

    @Test fun invalidCoordinatesAreDiscardedInsteadOfFabricatingGeometry() {
        val normalized = normalizeRealTripRoute(
            listOf(
                point(1, 95.0, -99.0),
                point(2, 19.4326, -99.1332)
            )
        )

        assertEquals(1, normalized.size)
    }

    @Test fun computesDistanceAlongEveryRecordedSegment() {
        val direct = routeDistanceMeters(
            listOf(point(1, 19.4326, -99.1332), point(2, 19.4426, -99.1332))
        )
        val withDetour = routeDistanceMeters(
            listOf(
                point(1, 19.4326, -99.1332),
                point(2, 19.4376, -99.1232),
                point(3, 19.4426, -99.1332)
            )
        )

        assertTrue(direct > 1_000.0)
        assertTrue(withDetour > direct)
    }

    private fun point(sequence: Long, latitude: Double, longitude: Double) = RiderTripRoutePoint(
        clientRoutePointId = "point-$sequence",
        sequence = sequence,
        recordedAtUtc = "2026-08-17T18:00:00Z",
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = 8.0,
        speedMetersPerSecond = null,
        bearingDegrees = null
    )
}
