package com.example.sos_segundoplano.data.wear

import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearFinishRequestFactoryTest {
    private val location = LocationSample(latitude = 19.4, longitude = -99.1, accuracyMeters = 8f, timestampMillis = 1L, provider = "gps", isMock = false)
    @Test fun availableLocationIsMapped() { val request=buildWearFinishRequest("2026-08-16T00:00:00Z",SignalAvailability.Available,location); assertEquals(19.4,request.endLocation?.latitude); assertEquals(-99.1,request.endLocation?.longitude); assertEquals(8.0,request.endLocation?.accuracyMeters); assertEquals("2026-08-16T00:00:00Z",request.clientFinishedAtUtc) }
    @Test fun unavailableLocationIsOmitted() { val request=buildWearFinishRequest("2026-08-16T00:00:00Z",SignalAvailability.Waiting,location); assertNull(request.endLocation); assertEquals("2026-08-16T00:00:00Z",request.clientFinishedAtUtc) }
}
