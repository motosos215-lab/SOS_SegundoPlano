package com.example.sos_segundoplano.data.signals

import org.junit.Assert.assertEquals
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
}

private class FakeSignalSource : SignalSource {
    var starts = 0
    var stops = 0
    override fun start() { starts++ }
    override fun stop() { stops++ }
}
