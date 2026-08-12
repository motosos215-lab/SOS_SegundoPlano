package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.signals.InMemoryTripSignalStore
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripSignalManualSosLocationProviderTest {
    @Test fun returnsRecentRealMonitoringLocation() {
        val store = InMemoryTripSignalStore()
        val sample = location(timestampMillis = 10_000L)
        store.updateLocation(SignalReading(SignalAvailability.Available, sample))

        val result = TripSignalManualSosLocationProvider(
            store = store,
            nowEpochMillis = { 14_999L },
            maxAgeMillis = 5_000L
        ).currentRealLocation()

        assertEquals(sample, result)
    }

    @Test fun rejectsStaleMockMissingAndZeroFallbackLocations() {
        val cases = listOf(
            SignalReading(SignalAvailability.Waiting, location(timestampMillis = 10_000L)),
            SignalReading(SignalAvailability.Available, location(timestampMillis = 9_999L)),
            SignalReading(SignalAvailability.Available, location(timestampMillis = 10_000L, isMock = true)),
            SignalReading(SignalAvailability.Available, location(timestampMillis = 10_000L, latitude = 0.0, longitude = 0.0)),
            SignalReading<LocationSample>(SignalAvailability.Available, null)
        )
        cases.forEach { reading ->
            val store = InMemoryTripSignalStore()
            store.updateLocation(reading)
            assertNull(
                TripSignalManualSosLocationProvider(
                    store = store,
                    nowEpochMillis = { 15_000L },
                    maxAgeMillis = 5_000L
                ).currentRealLocation()
            )
        }
    }

    private fun location(
        timestampMillis: Long,
        latitude: Double = 19.4326,
        longitude: Double = -99.1332,
        isMock: Boolean = false
    ) = LocationSample(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = 8f,
        timestampMillis = timestampMillis,
        provider = "gps",
        isMock = isMock
    )
}
