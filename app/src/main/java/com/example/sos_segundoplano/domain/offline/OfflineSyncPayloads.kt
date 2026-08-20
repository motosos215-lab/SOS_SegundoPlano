package com.example.sos_segundoplano.domain.offline

data class ConnectivitySyncSnapshot(
    val connected: Boolean,
    val validated: Boolean,
    val metered: Boolean,
    val transport: String,
    val timestampMillis: Long
)

data class MinorEventSyncPayload(
    val eventId: Long,
    val sessionId: Long,
    val assessmentId: Long,
    val windowId: Long,
    val type: String,
    val score: Int?,
    val confidence: Double,
    val policyVersion: String,
    val occurredAtEpochMillis: Long,
    val createdAtElapsedRealtimeNanos: Long,
    val connectivity: ConnectivitySyncSnapshot? = null
)

data class LocalIncidentSyncPayload(
    val incidentId: Long,
    val sessionId: Long,
    val assessmentId: Long,
    val windowId: Long,
    val cause: String,
    val score: Int?,
    val riskLevel: String,
    val confidence: Double,
    val ruleSetVersion: String,
    val validationPolicyVersion: String,
    val gpsQuality: String,
    val occurredAtEpochMillis: Long,
    val createdAtElapsedRealtimeNanos: Long,
    val clientIncidentId: String? = null,
    val detectedAtEpochMillis: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val remoteTripId: String? = null,
    val tripSessionKey: String? = null,
    val connectivity: ConnectivitySyncSnapshot? = null
)

data class AlertDispatchRequestSyncPayload(
    val requestId: Long,
    val incidentId: Long,
    val sessionId: Long,
    val assessmentId: Long,
    val priority: String,
    val reason: String,
    val score: Int?,
    val confidence: Double,
    val deliveryStatus: String,
    val retryState: String,
    val occurredAtEpochMillis: Long,
    val createdAtElapsedRealtimeNanos: Long,
    val clientAlertRequestId: String? = null,
    val connectivity: ConnectivitySyncSnapshot? = null
)

sealed interface OfflineSyncPayload {
    val eventType: OfflineEventType
    val schemaVersion: Int
    val sourceEventId: String
    val sourceSessionId: Long?
    val sourceAssessmentId: Long?
    val occurredAtEpochMillis: Long
    val connectivity: ConnectivitySyncSnapshot?

    data class MinorEventPayload(
        val payload: MinorEventSyncPayload,
        override val schemaVersion: Int = 1
    ) : OfflineSyncPayload {
        override val eventType: OfflineEventType = OfflineEventType.MinorEvent
        override val sourceEventId: String = payload.eventId.toString()
        override val sourceSessionId: Long = payload.sessionId
        override val sourceAssessmentId: Long = payload.assessmentId
        override val occurredAtEpochMillis: Long = payload.occurredAtEpochMillis
        override val connectivity: ConnectivitySyncSnapshot? = payload.connectivity
    }

    data class LocalIncidentPayload(
        val payload: LocalIncidentSyncPayload,
        override val schemaVersion: Int = 1
    ) : OfflineSyncPayload {
        override val eventType: OfflineEventType = OfflineEventType.LocalIncident
        override val sourceEventId: String = payload.clientIncidentId?.takeIf { it.isNotBlank() } ?: payload.incidentId.toString()
        override val sourceSessionId: Long = payload.sessionId
        override val sourceAssessmentId: Long = payload.assessmentId
        override val occurredAtEpochMillis: Long = payload.occurredAtEpochMillis
        override val connectivity: ConnectivitySyncSnapshot? = payload.connectivity
    }

    data class AlertDispatchRequestPayload(
        val payload: AlertDispatchRequestSyncPayload,
        override val schemaVersion: Int = 1
    ) : OfflineSyncPayload {
        override val eventType: OfflineEventType = OfflineEventType.AlertDispatchRequest
        override val sourceEventId: String = payload.clientAlertRequestId?.takeIf { it.isNotBlank() } ?: payload.requestId.toString()
        override val sourceSessionId: Long = payload.sessionId
        override val sourceAssessmentId: Long = payload.assessmentId
        override val occurredAtEpochMillis: Long = payload.occurredAtEpochMillis
        override val connectivity: ConnectivitySyncSnapshot? = payload.connectivity
    }
}
