package com.example.sos_segundoplano.data.signals

import com.example.sos_segundoplano.domain.signals.WearableStatus
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import com.example.sos_segundoplano.domain.validation.UserValidationAction
import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearDataLayerProtocolTest {
    @Test fun validationRoutesAreStable() {
        assertEquals("/motosos/validation/status", WearDataLayerProtocol.PATH_VALIDATION_STATUS)
        assertEquals("/motosos/validation/confirm-safe", WearDataLayerProtocol.PATH_VALIDATION_CONFIRM_SAFE)
        assertEquals("/motosos/validation/request-help", WearDataLayerProtocol.PATH_VALIDATION_REQUEST_HELP)
    }

    @Test fun decodesPermissionRequiredFromWear() {
        val map = DataMap().apply {
            putString("status", "permission_required")
            putBoolean("captureActive", true)
            putLong("lastUpdatedMillis", 1L)
        }

        val sample = WearDataLayerProtocol.decodeWearable(map, nodeId = "watch", isNearby = true)

        assertEquals(WearableStatus.PermissionRequired, sample.status)
        assertNull(sample.heartRateBpm)
    }

    @Test fun decodesUnavailableWearStatesWithoutMagicHeartRate() {
        val map = DataMap().apply {
            putString("status", "health_services_unavailable")
            putString("heartRateStatus", "health_services_unavailable")
            putDouble("heartRateBpm", 72.0)
            putBoolean("captureActive", true)
            putLong("lastUpdatedMillis", 1L)
        }

        val sample = WearDataLayerProtocol.decodeWearable(map, nodeId = "watch", isNearby = true)

        assertEquals(WearableStatus.HealthServicesUnavailable, sample.status)
        assertNull(sample.heartRateBpm)
    }

    @Test fun encodesStoppedValidationStateForWear() {
        val map = WearDataLayerProtocol.encodeValidationStateMap(
            FalsePositiveValidationState.Stopped(1L, 10L, "false-positive-validation-v1"),
            messageId = "message-1"
        )

        assertEquals("stopped", map.getString("state"))
        assertEquals(1, map.getInt("protocolVersion"))
        assertEquals("message-1", map.getString("messageId"))
    }

    @Test fun decodesValidationResponseOnlyWithRequiredIdentifiers() {
        val valid = DataMap().apply {
            putInt("protocolVersion", 1)
            putString("action", "confirm_safe")
            putLong("sessionId", 7L)
            putLong("assessmentId", 8L)
            putString("responseId", "response-1")
        }

        val response = WearDataLayerProtocol.decodeValidationResponseMap(valid, UserValidationAction.ConfirmSafe)

        assertEquals(7L, response!!.sessionId)
        assertEquals(8L, response.assessmentId)
        assertNull(WearDataLayerProtocol.decodeValidationResponseMap(DataMap(), UserValidationAction.ConfirmSafe))
        valid.putString("responseId", "")
        assertNull(WearDataLayerProtocol.decodeValidationResponseMap(valid, UserValidationAction.ConfirmSafe))
    }
}
