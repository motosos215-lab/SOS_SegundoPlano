package com.example.sos_segundoplano.wear

import com.google.android.gms.wearable.DataMap

object WearDataLayerProtocol {
    const val PATH_TRIP_START = "/motosos/trip/start"
    const val PATH_TRIP_STOP = "/motosos/trip/stop"
    const val PATH_SIGNALS_LATEST = "/motosos/signals/latest"
    const val PATH_WATCH_STATUS = "/motosos/watch/status"
    const val PATH_VALIDATION_STATUS = "/motosos/validation/status"
    const val PATH_VALIDATION_CONFIRM_SAFE = "/motosos/validation/confirm-safe"
    const val PATH_VALIDATION_REQUEST_HELP = "/motosos/validation/request-help"
    const val PROTOCOL_VERSION = 1

    data class ValidationStatus(
        val state: String,
        val sessionId: Long? = null,
        val assessmentId: Long? = null,
        val windowId: Long? = null,
        val remainingMillis: Long? = null,
        val reason: String? = null,
        val messageId: String? = null,
        val updatedAtElapsedRealtimeNanos: Long? = null
    ) {
        val isCountdownActive: Boolean get() = state == "countdown_active"
    }

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

    fun decodeValidationStatusMap(map: DataMap): ValidationStatus {
        if (map.getInt("protocolVersion", -1) != PROTOCOL_VERSION) return ValidationStatus("idle")
        return ValidationStatus(
            state = map.getString("state") ?: "idle",
            sessionId = if (map.containsKey("sessionId")) map.getLong("sessionId") else null,
            assessmentId = if (map.containsKey("assessmentId")) map.getLong("assessmentId") else null,
            windowId = if (map.containsKey("windowId")) map.getLong("windowId") else null,
            remainingMillis = if (map.containsKey("remainingMillis")) map.getLong("remainingMillis") else null,
            reason = map.getString("reason"),
            messageId = map.getString("messageId"),
            updatedAtElapsedRealtimeNanos = if (map.containsKey("updatedAtElapsedRealtimeNanos")) map.getLong("updatedAtElapsedRealtimeNanos") else null
        )
    }

    fun decodeValidationStatus(bytes: ByteArray): ValidationStatus {
        val map = runCatching { DataMap.fromByteArray(bytes) }.getOrNull() ?: return ValidationStatus("idle")
        return decodeValidationStatusMap(map)
    }

    fun encodeValidationResponseMap(action: String, sessionId: Long, assessmentId: Long, responseId: String): DataMap = DataMap().apply {
        putInt("protocolVersion", PROTOCOL_VERSION)
        putString("action", action)
        putLong("sessionId", sessionId)
        putLong("assessmentId", assessmentId)
        putString("responseId", responseId)
    }

    fun encodeValidationResponse(action: String, sessionId: Long, assessmentId: Long, responseId: String): ByteArray =
        encodeValidationResponseMap(action, sessionId, assessmentId, responseId).toByteArray()

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
        WearSignalAvailability.PermissionRequired -> "permission_required"
        WearSignalAvailability.PermanentlyDenied -> "permanently_denied"
        WearSignalAvailability.Unsupported -> "unsupported"
        WearSignalAvailability.HealthServicesUnavailable -> "health_services_unavailable"
        WearSignalAvailability.StartFailed -> "start_failed"
        WearSignalAvailability.Error -> "error"
        WearSignalAvailability.Stopped -> "stopped"
    }

    private fun WearCaptureStatus.toProtocol(): String = when (this) {
        WearCaptureStatus.Disconnected -> "disconnected"
        WearCaptureStatus.Capturing -> "capturing"
        WearCaptureStatus.PermissionRequired -> "permission_required"
        WearCaptureStatus.PermanentlyDenied -> "permanently_denied"
        WearCaptureStatus.SensorUnavailable -> "sensor_unavailable"
        WearCaptureStatus.HealthServicesUnavailable -> "health_services_unavailable"
        WearCaptureStatus.StartFailed -> "start_failed"
        WearCaptureStatus.Error -> "error"
        WearCaptureStatus.UserActionRequired -> "user_action_required"
        WearCaptureStatus.Stopped -> "stopped"
    }
}
