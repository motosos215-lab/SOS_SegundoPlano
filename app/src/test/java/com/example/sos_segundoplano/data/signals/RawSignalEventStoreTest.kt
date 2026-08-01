package com.example.sos_segundoplano.data.signals

import com.example.sos_segundoplano.data.preprocessing.RawSignalEventSink
import com.example.sos_segundoplano.domain.preprocessing.RawSignalEvent
import com.example.sos_segundoplano.domain.preprocessing.RawSignalValue
import com.example.sos_segundoplano.domain.preprocessing.SignalKind
import com.example.sos_segundoplano.domain.preprocessing.SignalSourceId
import com.example.sos_segundoplano.domain.preprocessing.TimeDomain
import com.example.sos_segundoplano.domain.signals.BatterySample
import com.example.sos_segundoplano.domain.signals.ConnectivitySample
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.NetworkTransport
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading
import com.example.sos_segundoplano.domain.signals.Vector3Sample
import com.example.sos_segundoplano.domain.signals.WearableSample
import com.example.sos_segundoplano.domain.signals.WearableStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSignalEventStoreTest {
    @Test fun mobileMotionEventsKeepPhoneMonotonicTimeline() {
        val sink = CapturingRawEvents()
        val store = InMemoryTripSignalStore(rawEvents = sink, phoneTimeNanos = { 500L }, wallClockMillis = { 1L })

        store.updateMobileAccelerometer(SignalReading(SignalAvailability.Available, Vector3Sample(1f, 2f, 3f, 123L)))

        val event = sink.eventsList.single()
        assertEquals(SignalSourceId.Phone, event.sourceId)
        assertEquals(SignalKind.Accelerometer, event.signalKind)
        assertEquals(123L, event.timestamp.phoneTimeNanos)
        assertEquals(TimeDomain.PhoneElapsedRealtime, event.timestamp.originalTimeDomain)
    }

    @Test fun wearMotionEventsUseMobileReceiveTimeForAlignment() {
        val sink = CapturingRawEvents()
        val store = InMemoryTripSignalStore(rawEvents = sink, phoneTimeNanos = { 999L }, wallClockMillis = { 1L })

        store.updateWearable(
            WearableSample(
                accelerometer = SignalReading(SignalAvailability.Available, Vector3Sample(1f, 2f, 3f, 44L)),
                status = WearableStatus.Capturing,
                lastUpdatedMillis = 10L
            )
        )

        val event = sink.eventsList.first { it.signalKind == SignalKind.Accelerometer }
        assertEquals(SignalSourceId.Wear, event.sourceId)
        assertEquals(999L, event.timestamp.phoneTimeNanos)
        assertEquals(44L, event.timestamp.originalTimestampNanos)
        assertEquals(999L, event.timestamp.receivedAtPhoneNanos)
        assertEquals(TimeDomain.WearElapsedRealtime, event.timestamp.originalTimeDomain)
    }

    @Test fun unavailableWearPermissionDoesNotEmitHeartRateZero() {
        val sink = CapturingRawEvents()
        val store = InMemoryTripSignalStore(rawEvents = sink, phoneTimeNanos = { 1L }, wallClockMillis = { 1L })

        store.updateWearable(WearableSample(status = WearableStatus.PermissionRequired, heartRateBpm = null))

        assertTrue(sink.eventsList.none { it.signalKind == SignalKind.HeartRate })
        assertTrue(sink.eventsList.any { (it.value as? RawSignalValue.WearStatus)?.status == WearableStatus.PermissionRequired })
    }

    @Test fun discreteContextEventsAreEmittedWithoutContinuousStatisticsAssumption() {
        val sink = CapturingRawEvents()
        val store = InMemoryTripSignalStore(rawEvents = sink, phoneTimeNanos = { 5L }, wallClockMillis = { 1L })

        store.updatePhoneBattery(SignalReading(SignalAvailability.Available, BatterySample(80, false, 1L)))
        store.updateConnectivity(SignalReading(SignalAvailability.Available, ConnectivitySample(true, true, false, NetworkTransport.Wifi, 2L)))

        assertEquals(SignalKind.Battery, sink.eventsList[0].signalKind)
        assertEquals(SignalKind.Connectivity, sink.eventsList[1].signalKind)
    }

    @Test fun locationEmitsLocationAndDerivedSpeedWithoutNormalizingCoordinates() {
        val sink = CapturingRawEvents()
        val store = InMemoryTripSignalStore(rawEvents = sink, phoneTimeNanos = { 5L }, wallClockMillis = { 1L })

        store.updateLocation(SignalReading(SignalAvailability.Available, location(1_000L, 0.0)))
        store.updateLocation(SignalReading(SignalAvailability.Available, location(2_000L, 0.001)))

        assertTrue(sink.eventsList.any { it.signalKind == SignalKind.Location && it.value is RawSignalValue.Location })
        assertTrue(sink.eventsList.any { it.signalKind == SignalKind.Speed && it.value is RawSignalValue.Scalar })
        assertNull(sink.eventsList.first { it.signalKind == SignalKind.Location }.timestamp.receivedAtPhoneNanos)
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

private class CapturingRawEvents : RawSignalEventSink {
    val eventsList = mutableListOf<RawSignalEvent>()
    override val events: SharedFlow<RawSignalEvent> = MutableSharedFlow()
    override val droppedEvents: Long = 0L

    override fun tryEmit(event: RawSignalEvent) {
        eventsList += event
    }
}
