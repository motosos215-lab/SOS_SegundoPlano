package com.example.sos_segundoplano.data.signals

import com.example.sos_segundoplano.domain.signals.BatterySample
import com.example.sos_segundoplano.domain.signals.CaptureState
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryTripSignalStoreTest {
    @Test fun combinesIndependentSnapshots() = runTest {
        val store = InMemoryTripSignalStore()
        store.setCaptureState(CaptureState.Active)
        store.updatePhoneBattery(SignalReading(SignalAvailability.Available, BatterySample(80, false, 1L)))
        store.updateLocation(SignalReading(SignalAvailability.Available, location(1_000L, 0.0)))
        store.updateLocation(SignalReading(SignalAvailability.Available, location(2_000L, 0.001)))

        val snapshot = store.snapshots.value
        assertSame(CaptureState.Active, snapshot.captureState)
        assertEquals(80, snapshot.phoneBattery.sample!!.percentage)
        assertTrue(snapshot.speed.sample!!.metersPerSecond > 0f)
    }

    @Test fun resetClearsLatestSignalsAfterFinish() = runTest {
        val store = InMemoryTripSignalStore()
        store.updatePhoneBattery(SignalReading(SignalAvailability.Available, BatterySample(80, false, 1L)))
        store.reset()

        val snapshot = store.snapshots.value
        assertSame(CaptureState.Stopped, snapshot.captureState)
        assertNull(snapshot.phoneBattery.sample)
    }

    private fun location(time: Long, longitude: Double) = LocationSample(
        latitude = 0.0,
        longitude = longitude,
        accuracyMeters = 5f,
        timestampMillis = time,
        provider = "gps",
        isMock = false
    )
}
