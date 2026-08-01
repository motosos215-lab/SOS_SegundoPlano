package com.example.sos_segundoplano.data.signals

import com.example.sos_segundoplano.domain.signals.WearableStatus
import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearDataLayerProtocolTest {
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
}
