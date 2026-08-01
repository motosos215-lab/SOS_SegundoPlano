package com.example.sos_segundoplano.domain.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedPolicyTest {
    @Test fun convertsMetersPerSecondToKilometersPerHour() {
        assertEquals(36f, SpeedPolicy.metersPerSecondToKilometersPerHour(10f)!!, 0.001f)
    }

    @Test fun directLocationSpeedIsPreferred() {
        val result = SpeedPolicy.resolve(location(speed = 12f), location(latitude = 0.0, longitude = 0.0))
        assertSame(SignalAvailability.Available, result.availability)
        assertEquals(SpeedSource.DirectLocation, result.sample!!.source)
        assertEquals(12f, result.sample!!.metersPerSecond, 0.001f)
    }

    @Test fun derivesSpeedFromTwoLocations() {
        val previous = location(latitude = 0.0, longitude = 0.0, time = 1_000L)
        val current = location(latitude = 0.0, longitude = 0.001, time = 11_000L)
        val result = SpeedPolicy.resolve(current, previous)
        assertSame(SignalAvailability.Available, result.availability)
        assertEquals(SpeedSource.DerivedLocation, result.sample!!.source)
        assertTrue(result.sample!!.metersPerSecond > 0f)
    }

    @Test fun invalidTimeDifferenceDoesNotDeriveSpeed() {
        val result = SpeedPolicy.resolve(location(time = 1_000L), location(time = 1_000L))
        assertSame(SignalAvailability.Waiting, result.availability)
    }

    @Test fun insufficientLocationDoesNotReturnZero() {
        val result = SpeedPolicy.resolve(location(speed = null), null)
        assertSame(SignalAvailability.Waiting, result.availability)
        assertNull(result.sample)
    }

    @Test fun nanAndInfiniteValuesAreRejected() {
        assertNull(SpeedPolicy.metersPerSecondToKilometersPerHour(Float.NaN))
        assertNull(SpeedPolicy.metersPerSecondToKilometersPerHour(Float.POSITIVE_INFINITY))
        assertNull(SpeedPolicy.metersPerSecondToKilometersPerHour(-1f))
    }

    private fun location(
        latitude: Double = 1.0,
        longitude: Double = 1.0,
        time: Long = 2_000L,
        speed: Float? = null
    ) = LocationSample(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = 5f,
        speedMetersPerSecond = speed,
        timestampMillis = time,
        provider = "gps",
        isMock = false
    )
}
