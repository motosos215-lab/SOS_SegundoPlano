package com.example.sos_segundoplano.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearDataLayerProtocolTest {
    @Test fun routesAreStable() {
        assertEquals("/motosos/trip/start", WearDataLayerProtocol.PATH_TRIP_START)
        assertEquals("/motosos/trip/stop", WearDataLayerProtocol.PATH_TRIP_STOP)
        assertEquals("/motosos/signals/latest", WearDataLayerProtocol.PATH_SIGNALS_LATEST)
        assertEquals("/motosos/watch/status", WearDataLayerProtocol.PATH_WATCH_STATUS)
    }

    @Test fun encodesSensorPermissionAndCapabilityStatus() {
        val map = WearDataLayerProtocol.encode(
            WearSignalSnapshot(
                accelerometerStatus = WearSignalAvailability.Available,
                accelerometer = WearVectorSample(1f, 2f, 3f, 4L, 5),
                gyroscopeStatus = WearSignalAvailability.Unsupported,
                heartRateStatus = WearSignalAvailability.PermissionRequired,
                captureActive = true,
                status = WearCaptureStatus.PermissionRequired,
                lastUpdatedMillis = 10L
            )
        )

        assertEquals("available", map.getString("accelerometerStatus"))
        assertEquals(1f, map.getFloat("accelerometerX"), 0.001f)
        assertEquals("unsupported", map.getString("gyroscopeStatus"))
        assertEquals("permission_required", map.getString("heartRateStatus"))
        assertEquals("permission_required", map.getString("status"))
        assertTrue(map.getBoolean("captureActive"))
    }

    @Test fun throttlesTransmissionToOneSecond() {
        val limiter = WearTransmissionLimiter(1_000L)
        assertFalse(limiter.shouldSend(999L))
        assertTrue(limiter.shouldSend(1_000L))
        assertFalse(limiter.shouldSend(1_500L))
        assertTrue(limiter.shouldSend(2_000L))
        limiter.reset()
        assertTrue(limiter.shouldSend(1_000L))
    }

    @Test fun fakeStopIsIdempotent() {
        val controller = FakeWearCaptureController()
        controller.start()
        controller.start()
        controller.stop()
        controller.stop()
        assertEquals(1, controller.starts)
        assertEquals(1, controller.stops)
    }
}

private class FakeWearCaptureController {
    private var active = false
    var starts = 0
    var stops = 0
    fun start() {
        if (active) return
        active = true
        starts++
    }
    fun stop() {
        if (!active) return
        active = false
        stops++
    }
}
