package com.example.sos_segundoplano.wearprotocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearActionProtocolCodecTest {
    @Test fun tripStateRequestRoundTrip() {
        val value = TripStateRequest("request-state", 100L)
        val parsed = WearActionProtocolCodec.decodeTripStateRequestMap(
            WearActionProtocolCodec.encodeTripStateRequestMap(value),
        ).success()

        assertEquals(value.requestId, parsed.requestId)
        assertEquals(value.requestedAtEpochMs, parsed.requestedAtEpochMs)
        assertEquals(WearProtocol.PROTOCOL_VERSION, parsed.protocolVersion)
        assertEquals(WearProtocol.SCHEMA_VERSION, parsed.schemaVersion)
    }

    @Test fun inactiveTripStateRoundTrip() {
        val value = TripStateResponse("request-id", false, null, null, 101L)
        val parsed = WearActionProtocolCodec.decodeTripStateResponseMap(
            WearActionProtocolCodec.encodeTripStateResponseMap(value),
        ).success()

        assertFalse(parsed.active)
        assertNull(parsed.remoteTripId)
        assertNull(parsed.startedAtEpochMs)
        assertEquals(value.requestId, parsed.requestId)
        assertEquals(101L, parsed.updatedAtEpochMs)
    }

    @Test fun activeTripStatePreservesRealId() {
        val parsed = WearActionProtocolCodec.decodeTripStateResponseMap(
            WearActionProtocolCodec.encodeTripStateResponseMap(
                TripStateResponse("request-id", true, "trip-real-123", null, 101L),
            ),
        ).success()

        assertTrue(parsed.active)
        assertEquals("trip-real-123", parsed.remoteTripId)
    }

    @Test fun startRoundTripKeepsDistinctIds() {
        val parsed = WearActionProtocolCodec.decodeStartTripActionRequestMap(
            WearActionProtocolCodec.encodeStartTripActionRequestMap(
                StartTripActionRequest("request-start-001", "command-start-777", 102L),
            ),
        ).success()

        assertEquals("request-start-001", parsed.requestId)
        assertEquals("command-start-777", parsed.commandId)
        assertFalse(parsed.requestId == parsed.commandId)
        assertEquals(102L, parsed.requestedAtEpochMs)
    }

    @Test fun finishRoundTrip() {
        val parsed = WearActionProtocolCodec.decodeFinishTripActionRequestMap(
            WearActionProtocolCodec.encodeFinishTripActionRequestMap(
                FinishTripActionRequest("request-finish-001", "command-finish-777", "trip-real-123", 103L),
            ),
        ).success()

        assertEquals("request-finish-001", parsed.requestId)
        assertEquals("command-finish-777", parsed.commandId)
        assertEquals("trip-real-123", parsed.remoteTripId)
        assertEquals(103L, parsed.requestedAtEpochMs)
    }

    @Test fun manualSosRoundTrip() {
        val parsed = WearActionProtocolCodec.decodeManualSosActionRequestMap(
            WearActionProtocolCodec.encodeManualSosActionRequestMap(
                ManualSosActionRequest("request-sos-001", "command-sos-777", "trip-real-123", 104L),
            ),
        ).success()

        assertEquals("request-sos-001", parsed.requestId)
        assertEquals("command-sos-777", parsed.commandId)
        assertEquals("trip-real-123", parsed.remoteTripId)
        assertEquals(104L, parsed.requestedAtEpochMs)
    }

    @Test fun actionResponsesRoundTripForMultipleEnums() {
        listOf(PhoneActionResult.OK, PhoneActionResult.UNAVAILABLE, PhoneActionResult.PHONE_ACTION_REQUIRED, PhoneActionResult.RETRYABLE_ERROR).forEach { result ->
            val parsed = WearActionProtocolCodec.decodePhoneActionResponseMap(
                map = WearActionProtocolCodec.encodePhoneActionResponseMap(
                    path = WearProtocol.PATH_ACTION_START_TRIP,
                    value = PhoneActionResponse("request-response", result, "example_code", "trip-real-123", 105L),
                ),
                expectedPath = WearProtocol.PATH_ACTION_START_TRIP,
            ).success()

            assertEquals("request-response", parsed.requestId)
            assertEquals(result, parsed.result)
            assertEquals("example_code", parsed.sanitizedCode)
            assertEquals("trip-real-123", parsed.remoteTripId)
            assertEquals(105L, parsed.respondedAtEpochMs)
        }
    }

    @Test fun wrongRouteRejected() = assertFailure(
        WearActionProtocolCodec.decodeTripStateRequestMap(
            WearActionProtocolCodec.encodeStartTripActionRequestMap(StartTripActionRequest("r", "c", 1L)),
        ),
    )

    @Test fun blankRequestIdRejected() = assertFailure(
        WearActionProtocolCodec.decodeTripStateRequestMap(
            WearActionProtocolCodec.encodeTripStateRequestMap(TripStateRequest(" ", 1L)),
        ),
    )

    @Test fun blankCommandIdRejectedForAllActions() {
        assertFailure(
            WearActionProtocolCodec.decodeStartTripActionRequestMap(
                WearActionProtocolCodec.encodeStartTripActionRequestMap(StartTripActionRequest("r", " ", 1L)),
            ),
        )
        assertFailure(
            WearActionProtocolCodec.decodeFinishTripActionRequestMap(
                WearActionProtocolCodec.encodeFinishTripActionRequestMap(FinishTripActionRequest("r", " ", "trip", 1L)),
            ),
        )
        assertFailure(
            WearActionProtocolCodec.decodeManualSosActionRequestMap(
                WearActionProtocolCodec.encodeManualSosActionRequestMap(ManualSosActionRequest("r", " ", "trip", 1L)),
            ),
        )
    }

    @Test fun invalidTimestampRejected() = assertFailure(
        WearActionProtocolCodec.decodeTripStateRequestMap(
            WearActionProtocolCodec.encodeTripStateRequestMap(TripStateRequest("r", 0L)),
        ),
    )

    @Test fun wrongProtocolVersionRejected() {
        val map = WearActionProtocolCodec.encodeTripStateRequestMap(TripStateRequest("request-version", 1L))
        map.putInt("protocolVersion", WearProtocol.PROTOCOL_VERSION + 1)
        assertFailure(WearActionProtocolCodec.decodeTripStateRequestMap(map))
    }

    @Test fun wrongSchemaVersionRejected() {
        val map = WearActionProtocolCodec.encodeTripStateRequestMap(TripStateRequest("request-schema", 1L))
        map.putInt("schemaVersion", WearProtocol.SCHEMA_VERSION + 1)
        assertFailure(WearActionProtocolCodec.decodeTripStateRequestMap(map))
    }

    @Test fun missingRequestIdRejected() {
        val map = WearActionProtocolCodec.encodeTripStateRequestMap(TripStateRequest("request-present", 1L))
        map.remove("requestId")
        assertFailure(WearActionProtocolCodec.decodeTripStateRequestMap(map))
    }

    private fun <T> WearDecodeResult<T>.success(): T = (this as? WearDecodeResult.Success<T>)?.value ?: error("expected success: $this")
    private fun assertFailure(result: WearDecodeResult<*>) = assertTrue(result is WearDecodeResult.Failure)
}
