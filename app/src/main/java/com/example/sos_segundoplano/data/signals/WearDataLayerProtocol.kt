package com.example.sos_segundoplano.data.signals

import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading
import com.example.sos_segundoplano.domain.signals.Vector3Sample
import com.example.sos_segundoplano.domain.signals.WearableSample
import com.example.sos_segundoplano.domain.signals.WearableStatus
import com.example.sos_segundoplano.domain.validation.UserResponseSource
import com.example.sos_segundoplano.domain.validation.UserValidationAction
import com.example.sos_segundoplano.domain.validation.UserValidationResponse
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
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

    fun encodeValidationStateMap(state: FalsePositiveValidationState, messageId: String): DataMap = DataMap().apply {
        putInt("protocolVersion", PROTOCOL_VERSION)
        putString("messageId", messageId)
        when (state) {
            is FalsePositiveValidationState.CountdownActive -> {
                putString("state", "countdown_active")
                putLong("sessionId", state.metadata.sessionId)
                putLong("assessmentId", state.metadata.assessmentId)
                putLong("windowId", state.metadata.windowId)
                putLong("remainingMillis", state.remainingNanos / NANOS_PER_MILLI)
                putLong("updatedAtElapsedRealtimeNanos", state.metadata.timestampElapsedRealtimeNanos)
            }
            is FalsePositiveValidationState.ImmediateAlertRequested -> {
                putString("state", "immediate_alert_requested")
                putLong("sessionId", state.incident.sessionId)
                putLong("assessmentId", state.incident.assessmentId)
                putLong("windowId", state.incident.windowId)
                putLong("incidentId", state.incident.incidentId)
                putString("reason", state.incident.cause.name)
                putLong("updatedAtElapsedRealtimeNanos", state.metadata.timestampElapsedRealtimeNanos)
            }
            is FalsePositiveValidationState.IncidentGenerated -> {
                putString("state", "incident_generated")
                putLong("sessionId", state.incident.sessionId)
                putLong("assessmentId", state.incident.assessmentId)
                putLong("windowId", state.incident.windowId)
                putLong("incidentId", state.incident.incidentId)
                putString("reason", state.incident.cause.name)
                putLong("updatedAtElapsedRealtimeNanos", state.metadata.timestampElapsedRealtimeNanos)
            }
            is FalsePositiveValidationState.SafeConfirmed -> {
                putString("state", "safe_confirmed")
                putLong("sessionId", state.metadata.sessionId)
                putLong("assessmentId", state.metadata.assessmentId)
                putLong("windowId", state.metadata.windowId)
                putLong("updatedAtElapsedRealtimeNanos", state.metadata.timestampElapsedRealtimeNanos)
            }
            is FalsePositiveValidationState.HelpRequested -> {
                putString("state", "help_requested")
                putLong("sessionId", state.metadata.sessionId)
                putLong("assessmentId", state.metadata.assessmentId)
                putLong("windowId", state.metadata.windowId)
                putLong("updatedAtElapsedRealtimeNanos", state.metadata.timestampElapsedRealtimeNanos)
            }
            is FalsePositiveValidationState.MinorEventRecorded -> {
                putString("state", "minor_event_recorded")
                putLong("sessionId", state.metadata.sessionId)
                putLong("assessmentId", state.metadata.assessmentId)
                putLong("windowId", state.metadata.windowId)
                putLong("updatedAtElapsedRealtimeNanos", state.metadata.timestampElapsedRealtimeNanos)
            }
            is FalsePositiveValidationState.SuppressedFalsePositive -> {
                putString("state", "suppressed_false_positive")
                putLong("sessionId", state.metadata.sessionId)
                putLong("assessmentId", state.metadata.assessmentId)
                putLong("windowId", state.metadata.windowId)
                putLong("updatedAtElapsedRealtimeNanos", state.metadata.timestampElapsedRealtimeNanos)
            }
            is FalsePositiveValidationState.CandidateDetected -> {
                putString("state", "candidate_detected")
                putLong("sessionId", state.metadata.sessionId)
                putLong("assessmentId", state.metadata.assessmentId)
                putLong("windowId", state.metadata.windowId)
                putLong("updatedAtElapsedRealtimeNanos", state.metadata.timestampElapsedRealtimeNanos)
            }
            is FalsePositiveValidationState.Monitoring -> {
                putString("state", "monitoring")
                state.sessionId?.let { putLong("sessionId", it) }
                putLong("updatedAtElapsedRealtimeNanos", state.timestampElapsedRealtimeNanos)
            }
            FalsePositiveValidationState.Idle -> putString("state", "idle")
            is FalsePositiveValidationState.Stopped -> {
                putString("state", "stopped")
                state.sessionId?.let { putLong("sessionId", it) }
                putLong("updatedAtElapsedRealtimeNanos", state.timestampElapsedRealtimeNanos)
            }
            is FalsePositiveValidationState.Error -> putString("state", "error")
        }
    }

    fun encodeValidationState(state: FalsePositiveValidationState, messageId: String): DataMap = encodeValidationStateMap(state, messageId)

    fun decodeValidationResponseMap(map: DataMap, expectedAction: UserValidationAction): UserValidationResponse? {
        if (map.getInt("protocolVersion", -1) != PROTOCOL_VERSION) return null
        val action = when (map.getString("action")) {
            "confirm_safe" -> UserValidationAction.ConfirmSafe
            "request_help" -> UserValidationAction.RequestHelp
            else -> return null
        }
        if (action != expectedAction) return null
        if (!map.containsKey("sessionId") || !map.containsKey("assessmentId")) return null
        val responseId = map.getString("responseId")?.takeIf { it.isNotBlank() } ?: return null
        return UserValidationResponse(
            protocolVersion = PROTOCOL_VERSION,
            action = action,
            sessionId = map.getLong("sessionId"),
            assessmentId = map.getLong("assessmentId"),
            responseId = responseId,
            source = UserResponseSource.Wear
        )
    }

    fun decodeValidationResponse(bytes: ByteArray, expectedAction: UserValidationAction): UserValidationResponse? {
        val map = runCatching { DataMap.fromByteArray(bytes) }.getOrNull() ?: return null
        return decodeValidationResponseMap(map, expectedAction)
    }

    fun decodeWearable(map: DataMap, nodeId: String?, isNearby: Boolean): WearableSample {
        val now = map.getLong("lastUpdatedMillis", System.currentTimeMillis())
        val heartRateStatus = map.getString("heartRateStatus")
        return WearableSample(
            accelerometer = decodeVector(map, "accelerometer"),
            gyroscope = decodeVector(map, "gyroscope"),
            heartRateBpm = decodeHeartRateBpm(map, heartRateStatus),
            watchBatteryPercentage = if (map.containsKey("watchBatteryPercentage")) map.getInt("watchBatteryPercentage") else null,
            nodeId = nodeId,
            isNearby = isNearby,
            captureActive = map.getBoolean("captureActive", false),
            lastUpdatedMillis = now,
            status = decodeStatus(map.getString("status"), map.getBoolean("captureActive", false), isNearby)
        )
    }

    private fun decodeHeartRateBpm(map: DataMap, heartRateStatus: String?): Double? {
        if (!map.containsKey("heartRateBpm")) return null
        if (heartRateStatus != null && heartRateStatus != "available") return null
        return map.getDouble("heartRateBpm")
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
        "permission_required" -> SignalAvailability.PermissionMissing
        "permanently_denied" -> SignalAvailability.PermissionMissing
        "disabled" -> SignalAvailability.Disabled
        "unsupported" -> SignalAvailability.Unsupported
        "stale" -> SignalAvailability.Stale
        "health_services_unavailable" -> SignalAvailability.Unsupported
        "start_failed" -> SignalAvailability.Error("start_failed")
        "error" -> SignalAvailability.Error()
        "stopped" -> SignalAvailability.Stale
        else -> SignalAvailability.Waiting
    }

    private fun decodeStatus(value: String?, captureActive: Boolean, isNearby: Boolean): WearableStatus = when (value) {
        "not_installed_or_unavailable" -> WearableStatus.NotInstalledOrUnavailable
        "permission_missing" -> WearableStatus.PermissionMissing
        "permission_required" -> WearableStatus.PermissionRequired
        "permanently_denied" -> WearableStatus.PermanentlyDenied
        "sensor_unavailable" -> WearableStatus.SensorUnavailable
        "health_services_unavailable" -> WearableStatus.HealthServicesUnavailable
        "start_failed" -> WearableStatus.StartFailed
        "capturing" -> WearableStatus.Capturing
        "stale" -> WearableStatus.Stale
        "error" -> WearableStatus.Error()
        "user_action_required" -> WearableStatus.UserActionRequired
        "connected_remote" -> WearableStatus.ConnectedRemote
        "connected_nearby" -> WearableStatus.ConnectedNearby
        "disconnected" -> WearableStatus.Disconnected
        "stopped" -> WearableStatus.Stopped
        else -> when {
            captureActive -> WearableStatus.Capturing
            isNearby -> WearableStatus.ConnectedNearby
            else -> WearableStatus.ConnectedRemote
        }
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
