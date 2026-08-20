package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.signals.InMemoryTripSignalStore
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripSignalManualSosLocationProviderTest {
    @Test fun returnsRecentRealMonitoringLocation() = runBlocking {
        val store = InMemoryTripSignalStore()
        val sample = location(timestampMillis = 10_000L)
        store.updateLocation(SignalReading(SignalAvailability.Available, sample))
        var fallbackRequests = 0

        val result = TripSignalManualSosLocationProvider(
            store = store,
            nowEpochMillis = { 14_999L },
            maxAgeMillis = 5_000L,
            currentLocationProvider = CurrentManualSosLocationProvider {
                fallbackRequests++
                location(timestampMillis = 14_000L)
            }
        ).currentRealLocation()

        assertEquals(sample, result)
        assertEquals(0, fallbackRequests)
    }

    @Test fun defaultEmergencyWindowAcceptsRecentPrimaryFixForFastSos() = runBlocking {
        val store = InMemoryTripSignalStore()
        val sample = location(timestampMillis = 10_000L).copy(accuracyMeters = 85f)
        store.updateLocation(SignalReading(SignalAvailability.Available, sample))
        var fallbackRequests = 0

        val result = TripSignalManualSosLocationProvider(
            store = store,
            nowEpochMillis = { 39_000L },
            currentLocationProvider = CurrentManualSosLocationProvider {
                fallbackRequests++
                location(timestampMillis = 39_000L)
            }
        ).currentRealLocation()

        assertEquals(sample, result)
        assertEquals(0, fallbackRequests)
    }

    @Test fun primaryEmergencyLocationDoesNotRequireHighAccuracy() = runBlocking {
        val store = InMemoryTripSignalStore()
        val coarseButValid = location(timestampMillis = 10_000L).copy(accuracyMeters = 150f)
        store.updateLocation(SignalReading(SignalAvailability.Available, coarseButValid))

        val result = TripSignalManualSosLocationProvider(
            store = store,
            nowEpochMillis = { 12_000L },
            maxAgeMillis = 5_000L
        ).currentRealLocation()

        assertEquals(coarseButValid, result)
    }

    @Test fun usesCurrentAndroidLocationWhenSnapshotIsUnavailableOrStale() = runBlocking {
        val fallback = location(timestampMillis = 14_000L)
        val store = InMemoryTripSignalStore().apply {
            updateLocation(SignalReading(SignalAvailability.Waiting, null))
        }
        val result = TripSignalManualSosLocationProvider(
            store = store,
            nowEpochMillis = { 15_000L },
            maxAgeMillis = 5_000L,
            currentLocationProvider = CurrentManualSosLocationProvider { fallback }
        ).currentRealLocation()

        assertEquals(fallback, result)
    }

    @Test fun usesCurrentAndroidLocationWhenSnapshotIsStale() = runBlocking {
        val fallback = location(timestampMillis = 14_000L)
        val store = InMemoryTripSignalStore().apply {
            updateLocation(SignalReading(SignalAvailability.Available, location(timestampMillis = 1_000L)))
        }
        val result = TripSignalManualSosLocationProvider(
            store = store,
            nowEpochMillis = { 15_000L },
            maxAgeMillis = 5_000L,
            currentLocationProvider = CurrentManualSosLocationProvider { fallback }
        ).currentRealLocation()

        assertEquals(fallback, result)
    }

    @Test fun rejectsMockAndZeroCurrentAndroidLocations() = runBlocking {
        listOf(
            location(timestampMillis = 14_000L, isMock = true),
            location(timestampMillis = 14_000L, latitude = 0.0, longitude = 0.0)
        ).forEach { fallback ->
            val result = TripSignalManualSosLocationProvider(
                store = InMemoryTripSignalStore(),
                nowEpochMillis = { 15_000L },
                maxAgeMillis = 5_000L,
                currentLocationProvider = CurrentManualSosLocationProvider { fallback }
            ).currentRealLocation()

            assertNull(result)
        }
    }

    @Test fun currentAndroidLocationTimeoutReturnsNoLocation() = runBlocking {
        val result = TripSignalManualSosLocationProvider(
            store = InMemoryTripSignalStore(),
            nowEpochMillis = { 15_000L },
            maxAgeMillis = 5_000L,
            currentLocationTimeoutMillis = 1L,
            currentLocationProvider = CurrentManualSosLocationProvider {
                delay(100L)
                location(timestampMillis = 14_000L)
            }
        ).currentRealLocation()

        assertNull(result)
    }

    @Test fun rejectsStaleMockMissingAndZeroFallbackLocations() = runBlocking {
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

    @Test fun realLocationValidationRejectsInvalidSamplesAndAcceptsFreshFix() {
        val maxAgeMillis = 5_000L

        assertTrue(isValidRealLocation(location(timestampMillis = 14_000L), 1_000L, maxAgeMillis))

        listOf(
            location(timestampMillis = 14_000L, isMock = true),
            location(timestampMillis = 14_000L, latitude = 0.0, longitude = 0.0),
            location(timestampMillis = 14_000L, latitude = 91.0),
            location(timestampMillis = 14_000L, longitude = -181.0),
            location(timestampMillis = 14_000L).copy(accuracyMeters = -1f),
            location(timestampMillis = 14_000L).copy(accuracyMeters = Float.NaN)
        ).forEach { sample ->
            assertFalse(isValidRealLocation(sample, 1_000L, maxAgeMillis))
        }

        assertFalse(isValidRealLocation(location(timestampMillis = 14_000L), 5_001L, maxAgeMillis))
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
