package com.example.sos_segundoplano.data.signals

import com.example.sos_segundoplano.domain.validation.UserValidationAction
import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WearDataLayerProtocolInstrumentedTest {
    @Test fun validationResponseByteArrayRoundTripPreservesIdentifiers() {
        val map = DataMap().apply {
            putInt("protocolVersion", WearDataLayerProtocol.PROTOCOL_VERSION)
            putString("action", "confirm_safe")
            putLong("sessionId", 7L)
            putLong("assessmentId", 8L)
            putString("responseId", "response-1")
        }

        val bytes = map.toByteArray()
        val decodedMap = DataMap.fromByteArray(bytes)
        val response = WearDataLayerProtocol.decodeValidationResponse(decodedMap.toByteArray(), UserValidationAction.ConfirmSafe)

        assertNotNull(response)
        assertEquals(7L, response!!.sessionId)
        assertEquals(8L, response.assessmentId)
        assertEquals("response-1", response.responseId)
    }
}
