package com.example.sos_segundoplano.wear

import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Test

class WearDataLayerProtocolInstrumentedTest {
    @Test fun validationResponseByteArrayRoundTripPreservesIdentifiers() {
        val bytes = WearDataLayerProtocol.encodeValidationResponse("confirm_safe", 7L, 8L, "response-1")
        val map = DataMap.fromByteArray(bytes)

        assertEquals(WearDataLayerProtocol.PROTOCOL_VERSION, map.getInt("protocolVersion"))
        assertEquals("confirm_safe", map.getString("action"))
        assertEquals(7L, map.getLong("sessionId"))
        assertEquals(8L, map.getLong("assessmentId"))
        assertEquals("response-1", map.getString("responseId"))
    }
}
