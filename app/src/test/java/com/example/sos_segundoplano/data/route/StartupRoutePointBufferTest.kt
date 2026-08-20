package com.example.sos_segundoplano.data.route

import com.example.sos_segundoplano.domain.signals.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRoutePointBufferTest {
    @Test fun keepsStartupFixesInChronologicalOrderAndDeduplicatesSameFix() {
        val buffer = StartupRoutePointBuffer(maxPoints = 8)
        buffer.add(location(3_000L, -99.13))
        buffer.add(location(1_000L, -99.11))
        buffer.add(location(2_000L, -99.12))
        buffer.add(location(2_000L, -99.12))

        val points = buffer.drain()

        assertEquals(listOf(1_000L, 2_000L, 3_000L), points.map { it.timestampMillis })
        assertTrue(buffer.drain().isEmpty())
    }

    @Test fun bufferIsBoundedButKeepsMostRecentStartupFixes() {
        val buffer = StartupRoutePointBuffer(maxPoints = 2)
        buffer.add(location(1_000L, -99.11))
        buffer.add(location(2_000L, -99.12))
        buffer.add(location(3_000L, -99.13))

        assertEquals(listOf(2_000L, 3_000L), buffer.drain().map { it.timestampMillis })
    }

    private fun location(timestamp: Long, longitude: Double) = LocationSample(
        latitude = 19.4326,
        longitude = longitude,
        accuracyMeters = 80f,
        timestampMillis = timestamp,
        provider = "gps",
        isMock = false
    )
}
