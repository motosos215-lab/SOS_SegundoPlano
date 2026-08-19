package com.example.sos_segundoplano.wearprotocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instrumented tests for the action codec's public ByteArray boundary.
 * DataMap byte serialization requires the Android runtime.
 */
class WearActionProtocolCodecRoundTripTest {

    @Test
    fun tripStateRequestByteRoundTrip() {
        val value = TripStateRequest(
            requestId = "request-state-001",
            requestedAtEpochMs = 1_700_000_000_100L,
        )

        val parsed = WearActionProtocolCodec.decodeTripStateRequest(
            WearActionProtocolCodec.encodeTripStateRequest(value),
        ).success()

        assertEquals(WearProtocol.PROTOCOL_VERSION, parsed.protocolVersion)
        assertEquals(WearProtocol.SCHEMA_VERSION, parsed.schemaVersion)
        assertEquals(value.requestId, parsed.requestId)
        assertEquals(value.requestedAtEpochMs, parsed.requestedAtEpochMs)
    }

    @Test
    fun activeTripStateResponseByteRoundTrip() {
        val value = TripStateResponse(
            requestId = "request-state-response-001",
            active = true,
            remoteTripId = "trip-real-123",
            startedAtEpochMs = 1_700_000_000_200L,
            updatedAtEpochMs = 1_700_000_000_300L,
        )

        val parsed = WearActionProtocolCodec.decodeTripStateResponse(
            WearActionProtocolCodec.encodeTripStateResponse(value),
        ).success()

        assertEquals(value.requestId, parsed.requestId)
        assertTrue(parsed.active)
        assertEquals(value.remoteTripId, parsed.remoteTripId)
        assertEquals(value.startedAtEpochMs, parsed.startedAtEpochMs)
        assertEquals(value.updatedAtEpochMs, parsed.updatedAtEpochMs)
    }

    @Test
    fun startTripActionByteRoundTrip() {
        val value = StartTripActionRequest(
            requestId = "request-start-001",
            commandId = "command-start-777",
            requestedAtEpochMs = 1_700_000_000_400L,
        )

        val parsed = WearActionProtocolCodec.decodeStartTripAction(
            WearActionProtocolCodec.encodeStartTripAction(value),
        ).success()

        assertEquals(value.requestId, parsed.requestId)
        assertEquals(value.commandId, parsed.commandId)
        assertFalse(parsed.requestId == parsed.commandId)
        assertEquals(value.requestedAtEpochMs, parsed.requestedAtEpochMs)
    }

    @Test
    fun finishTripActionByteRoundTrip() {
        val value = FinishTripActionRequest(
            requestId = "request-finish-001",
            commandId = "command-finish-777",
            remoteTripId = "trip-real-123",
            requestedAtEpochMs = 1_700_000_000_500L,
        )

        val parsed = WearActionProtocolCodec.decodeFinishTripAction(
            WearActionProtocolCodec.encodeFinishTripAction(value),
        ).success()

        assertEquals(value.requestId, parsed.requestId)
        assertEquals(value.commandId, parsed.commandId)
        assertEquals(value.remoteTripId, parsed.remoteTripId)
        assertEquals(value.requestedAtEpochMs, parsed.requestedAtEpochMs)
    }

    @Test
    fun manualSosActionByteRoundTrip() {
        val value = ManualSosActionRequest(
            requestId = "request-sos-001",
            commandId = "command-sos-777",
            remoteTripId = "trip-real-123",
            requestedAtEpochMs = 1_700_000_000_600L,
        )

        val parsed = WearActionProtocolCodec.decodeManualSosAction(
            WearActionProtocolCodec.encodeManualSosAction(value),
        ).success()

        assertEquals(value.requestId, parsed.requestId)
        assertEquals(value.commandId, parsed.commandId)
        assertEquals(value.remoteTripId, parsed.remoteTripId)
        assertEquals(value.requestedAtEpochMs, parsed.requestedAtEpochMs)
    }

    @Test
    fun phoneActionResponseByteRoundTrip() {
        val value = PhoneActionResponse(
            requestId = "request-response-001",
            result = PhoneActionResult.OK,
            sanitizedCode = "accepted",
            remoteTripId = "trip-real-123",
            respondedAtEpochMs = 1_700_000_000_700L,
        )

        val parsed = WearActionProtocolCodec.decodePhoneActionResponse(
            WearActionProtocolCodec.encodePhoneActionResponse(
                WearProtocol.PATH_ACTION_START_TRIP,
                value,
            ),
            WearProtocol.PATH_ACTION_START_TRIP,
        ).success()

        assertEquals(value.requestId, parsed.requestId)
        assertEquals(value.result, parsed.result)
        assertEquals(value.sanitizedCode, parsed.sanitizedCode)
        assertEquals(value.remoteTripId, parsed.remoteTripId)
        assertEquals(value.respondedAtEpochMs, parsed.respondedAtEpochMs)
    }

    @Test
    fun malformedBytesRejected() {
        val result = WearActionProtocolCodec.decodeTripStateRequest(byteArrayOf(0x01, 0x02, 0x03))

        assertTrue(result is WearDecodeResult.Failure)
        assertTrue((result as WearDecodeResult.Failure).error is WearProtocolError.CorruptPayload)
    }

    @Test
    fun oversizedPayloadRejected() {
        val result = WearActionProtocolCodec.decodeTripStateRequest(
            ByteArray(WearProtocol.MAX_PAYLOAD_BYTES + 1),
        )

        assertTrue(result is WearDecodeResult.Failure)
        assertTrue((result as WearDecodeResult.Failure).error is WearProtocolError.PayloadTooLarge)
    }

    private fun <T> WearDecodeResult<T>.success(): T =
        (this as? WearDecodeResult.Success<T>)?.value
            ?: error("Expected Success but was $this")
}
