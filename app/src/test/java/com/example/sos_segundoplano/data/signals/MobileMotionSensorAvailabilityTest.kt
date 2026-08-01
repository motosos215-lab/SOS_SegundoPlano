package com.example.sos_segundoplano.data.signals

import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MobileMotionSensorAvailabilityTest {
    @Test fun absentAccelerometerIsNotConvertedToZeroSample() {
        val store = InMemoryTripSignalStore()

        store.updateMobileAccelerometer(SignalReading(SignalAvailability.Unsupported))

        val reading = store.snapshots.value.mobileAccelerometer
        assertSame(SignalAvailability.Unsupported, reading.availability)
        assertNull(reading.sample)
    }

    @Test fun absentGyroscopeIsNotConvertedToZeroSample() {
        val store = InMemoryTripSignalStore()

        store.updateMobileGyroscope(SignalReading(SignalAvailability.Unsupported))

        val reading = store.snapshots.value.mobileGyroscope
        assertSame(SignalAvailability.Unsupported, reading.availability)
        assertNull(reading.sample)
    }
}
