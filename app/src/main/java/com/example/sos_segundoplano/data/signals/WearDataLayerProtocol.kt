package com.example.sos_segundoplano.data.signals

import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading
import com.example.sos_segundoplano.domain.signals.Vector3Sample
import com.example.sos_segundoplano.domain.signals.WearableSample
import com.example.sos_segundoplano.domain.signals.WearableStatus
import com.google.android.gms.wearable.DataMap

object WearDataLayerProtocol {
    const val PATH_TRIP_START = "/motosos/trip/start"
    const val PATH_TRIP_STOP = "/motosos/trip/stop"
    const val PATH_SIGNALS_LATEST = "/motosos/signals/latest"
    const val PATH_WATCH_STATUS = "/motosos/watch/status"

    fun decodeWearable(map: DataMap, nodeId: String?, isNearby: Boolean): WearableSample {
        val now = map.getLong("lastUpdatedMillis", System.currentTimeMillis())
        return WearableSample(
            accelerometer = decodeVector(map, "accelerometer"),
            gyroscope = decodeVector(map, "gyroscope"),
            heartRateBpm = if (map.containsKey("heartRateBpm")) map.getDouble("heartRateBpm") else null,
            watchBatteryPercentage = if (map.containsKey("watchBatteryPercentage")) map.getInt("watchBatteryPercentage") else null,
            nodeId = nodeId,
            isNearby = isNearby,
            captureActive = map.getBoolean("captureActive", false),
            lastUpdatedMillis = now,
            status = decodeStatus(map.getString("status"), map.getBoolean("captureActive", false), isNearby)
        )
    }

    private fun decodeVector(map: DataMap, prefix: String): SignalReading<Vector3Sample>? {
        val status = map.getString("${prefix}Status") ?: return null
        val availability = decodeAvailability(status)
        if (availability != SignalAvailability.Available) return SignalReading(availability)
        return SignalReading(
            availability,
            Vector3Sample(
                x = map.getFloat("${prefix}X"),
                y = map.getFloat("${prefix}Y"),
                z = map.getFloat("${prefix}Z"),
                timestampNanos = map.getLong("${prefix}TimestampNanos"),
                accuracy = if (map.containsKey("${prefix}Accuracy")) map.getInt("${prefix}Accuracy") else null
            )
        )
    }

    private fun decodeAvailability(value: String): SignalAvailability = when (value) {
        "available" -> SignalAvailability.Available
        "permission_missing" -> SignalAvailability.PermissionMissing
        "disabled" -> SignalAvailability.Disabled
        "unsupported" -> SignalAvailability.Unsupported
        "stale" -> SignalAvailability.Stale
        "error" -> SignalAvailability.Error()
        else -> SignalAvailability.Waiting
    }

    private fun decodeStatus(value: String?, captureActive: Boolean, isNearby: Boolean): WearableStatus = when (value) {
        "not_installed_or_unavailable" -> WearableStatus.NotInstalledOrUnavailable
        "permission_missing" -> WearableStatus.PermissionMissing
        "sensor_unavailable" -> WearableStatus.SensorUnavailable
        "capturing" -> WearableStatus.Capturing
        "stale" -> WearableStatus.Stale
        "error" -> WearableStatus.Error()
        "user_action_required" -> WearableStatus.UserActionRequired
        "connected_remote" -> WearableStatus.ConnectedRemote
        "connected_nearby" -> WearableStatus.ConnectedNearby
        else -> when {
            captureActive -> WearableStatus.Capturing
            isNearby -> WearableStatus.ConnectedNearby
            else -> WearableStatus.ConnectedRemote
        }
    }
}
