package com.example.sos_segundoplano.data.signals

import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TripSignalCaptureCoordinatorTest {
    @Test fun startAndStopAreIdempotentWithFakes() {
        val source = FakeSignalSource()
        val coordinator = TripSignalCaptureCoordinator(InMemoryTripSignalStore(), listOf(source))

        coordinator.start()
        coordinator.start()
        coordinator.stop()
        coordinator.stop()

        assertEquals(1, source.starts)
        assertEquals(1, source.stops)
    }

    @Test fun unavailableMobileSensorDoesNotPreventOtherSourcesFromStarting() {
        val store = InMemoryTripSignalStore()
        val unavailableSensor = UnsupportedAccelerometerSource(store)
        val availableSource = FakeSignalSource()
        val coordinator = TripSignalCaptureCoordinator(store, listOf(unavailableSensor, availableSource))

        coordinator.start()

        assertSame(SignalAvailability.Unsupported, store.snapshots.value.mobileAccelerometer.availability)
        assertEquals(1, availableSource.starts)
    }
}

private class FakeSignalSource : SignalSource {
    var starts = 0
    var stops = 0
    override fun start() { starts++ }
    override fun stop() { stops++ }
}

private class UnsupportedAccelerometerSource(private val store: TripSignalStore) : SignalSource {
    override fun start() {
        store.updateMobileAccelerometer(SignalReading(SignalAvailability.Unsupported))
    }

    override fun stop() = Unit
}
