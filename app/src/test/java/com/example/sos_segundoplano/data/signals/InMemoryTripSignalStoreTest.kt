package com.example.sos_segundoplano.data.signals

import com.example.sos_segundoplano.domain.signals.BatterySample
import com.example.sos_segundoplano.domain.signals.CaptureState
import com.example.sos_segundoplano.domain.signals.GpsCalibrationState
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


    @Test fun startupCalibrationNeverDropsPrimaryLocationEvenWhenAccuracyIsCoarse() = runTest {
        val store = InMemoryTripSignalStore()
        val coarse = location(1_000L, -99.1332).copy(latitude = 19.4326, accuracyMeters = 150f)
        store.updateGpsCalibration(GpsCalibrationState.Calibrating(targetAccuracyMeters = 20f))
        store.updateLocation(SignalReading(SignalAvailability.Available, coarse))

        assertEquals(coarse, store.snapshots.value.location.sample)
        assertNull(store.snapshots.value.speed.sample)
    }

    @Test fun startupGpsCalibrationSuppressesDerivedSpeedUntilReady() = runTest {
        val store = InMemoryTripSignalStore()
        store.updateGpsCalibration(GpsCalibrationState.Calibrating(targetAccuracyMeters = 20f))
        store.updateLocation(SignalReading(SignalAvailability.Available, location(1_000L, 0.0)))
        store.updateLocation(SignalReading(SignalAvailability.Available, location(2_000L, 0.001)))

        assertNull(store.snapshots.value.speed.sample)

        store.updateGpsCalibration(GpsCalibrationState.Ready(achievedAccuracyMeters = 8f, consecutiveAccurateSamples = 3))
        store.updateLocation(SignalReading(SignalAvailability.Available, location(3_000L, 0.002)))
        store.updateLocation(SignalReading(SignalAvailability.Available, location(4_000L, 0.003)))

        assertTrue(store.snapshots.value.speed.sample!!.metersPerSecond > 0f)
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
