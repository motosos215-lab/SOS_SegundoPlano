package com.example.sos_segundoplano.wear

import com.google.android.gms.wearable.DataMap

object WearDataLayerProtocol {
    const val PATH_TRIP_START = "/motosos/trip/start"
    const val PATH_TRIP_STOP = "/motosos/trip/stop"
    const val PATH_SIGNALS_LATEST = "/motosos/signals/latest"
    const val PATH_WATCH_STATUS = "/motosos/watch/status"

    fun encode(snapshot: WearSignalSnapshot): DataMap = DataMap().apply {
        putBoolean("captureActive", snapshot.captureActive)
        putLong("lastUpdatedMillis", snapshot.lastUpdatedMillis)
        putString("status", snapshot.status.toProtocol())
        putString("accelerometerStatus", snapshot.accelerometerStatus.toProtocol())
        snapshot.accelerometer?.let { putVector("accelerometer", it) }
        putString("gyroscopeStatus", snapshot.gyroscopeStatus.toProtocol())
        snapshot.gyroscope?.let { putVector("gyroscope", it) }
        putString("heartRateStatus", snapshot.heartRateStatus.toProtocol())
        snapshot.heartRateBpm?.let { putDouble("heartRateBpm", it) }
        snapshot.watchBatteryPercentage?.let { putInt("watchBatteryPercentage", it) }
    }

    private fun DataMap.putVector(prefix: String, sample: WearVectorSample) {
        putFloat("${prefix}X", sample.x)
        putFloat("${prefix}Y", sample.y)
        putFloat("${prefix}Z", sample.z)
        putLong("${prefix}TimestampNanos", sample.timestampNanos)
        sample.accuracy?.let { putInt("${prefix}Accuracy", it) }
    }

    private fun WearSignalAvailability.toProtocol(): String = when (this) {
        WearSignalAvailability.Available -> "available"
        WearSignalAvailability.Waiting -> "waiting"
        WearSignalAvailability.PermissionMissing -> "permission_missing"
        WearSignalAvailability.Unsupported -> "unsupported"
        WearSignalAvailability.Error -> "error"
    }

    private fun WearCaptureStatus.toProtocol(): String = when (this) {
        WearCaptureStatus.Disconnected -> "disconnected"
        WearCaptureStatus.Capturing -> "capturing"
        WearCaptureStatus.PermissionMissing -> "permission_missing"
        WearCaptureStatus.SensorUnavailable -> "sensor_unavailable"
        WearCaptureStatus.Error -> "error"
        WearCaptureStatus.UserActionRequired -> "user_action_required"
    }
}
