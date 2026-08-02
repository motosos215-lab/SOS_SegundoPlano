package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.domain.offline.AlertDispatchRequestSyncPayload
import com.example.sos_segundoplano.domain.offline.LocalIncidentSyncPayload
import com.example.sos_segundoplano.domain.offline.MinorEventSyncPayload
import com.example.sos_segundoplano.domain.offline.OfflineEventType
import com.example.sos_segundoplano.domain.offline.OfflineSyncPayload
import java.net.URLDecoder
import java.net.URLEncoder

class OfflineQueueSerializer {
    fun serialize(payload: OfflineSyncPayload): ByteArray = when (payload) {
        is OfflineSyncPayload.MinorEventPayload -> fields(
            "eventType" to payload.eventType.wireName,
            "schemaVersion" to payload.schemaVersion.toString(),
            "eventId" to payload.payload.eventId.toString(),
            "sessionId" to payload.payload.sessionId.toString(),
            "assessmentId" to payload.payload.assessmentId.toString(),
            "windowId" to payload.payload.windowId.toString(),
            "type" to payload.payload.type,
            "score" to payload.payload.score?.toString().orEmpty(),
            "confidence" to payload.payload.confidence.toString(),
            "policyVersion" to payload.payload.policyVersion,
            "occurredAtEpochMillis" to payload.payload.occurredAtEpochMillis.toString(),
            "createdAtElapsedRealtimeNanos" to payload.payload.createdAtElapsedRealtimeNanos.toString()
        )

        is OfflineSyncPayload.LocalIncidentPayload -> fields(
            "eventType" to payload.eventType.wireName,
            "schemaVersion" to payload.schemaVersion.toString(),
            "incidentId" to payload.payload.incidentId.toString(),
            "sessionId" to payload.payload.sessionId.toString(),
            "assessmentId" to payload.payload.assessmentId.toString(),
            "windowId" to payload.payload.windowId.toString(),
            "cause" to payload.payload.cause,
            "score" to payload.payload.score?.toString().orEmpty(),
            "riskLevel" to payload.payload.riskLevel,
            "confidence" to payload.payload.confidence.toString(),
            "ruleSetVersion" to payload.payload.ruleSetVersion,
            "validationPolicyVersion" to payload.payload.validationPolicyVersion,
            "gpsQuality" to payload.payload.gpsQuality,
            "occurredAtEpochMillis" to payload.payload.occurredAtEpochMillis.toString(),
            "createdAtElapsedRealtimeNanos" to payload.payload.createdAtElapsedRealtimeNanos.toString()
        )

        is OfflineSyncPayload.AlertDispatchRequestPayload -> fields(
            "eventType" to payload.eventType.wireName,
            "schemaVersion" to payload.schemaVersion.toString(),
            "requestId" to payload.payload.requestId.toString(),
            "incidentId" to payload.payload.incidentId.toString(),
            "sessionId" to payload.payload.sessionId.toString(),
            "assessmentId" to payload.payload.assessmentId.toString(),
            "priority" to payload.payload.priority,
            "reason" to payload.payload.reason,
            "score" to payload.payload.score?.toString().orEmpty(),
            "confidence" to payload.payload.confidence.toString(),
            "deliveryStatus" to payload.payload.deliveryStatus,
            "retryState" to payload.payload.retryState,
            "occurredAtEpochMillis" to payload.payload.occurredAtEpochMillis.toString(),
            "createdAtElapsedRealtimeNanos" to payload.payload.createdAtElapsedRealtimeNanos.toString()
        )
    }

    fun deserialize(bytes: ByteArray): OfflineSyncPayload? = runCatching {
        val values = parse(bytes.toString(Charsets.UTF_8))
        val schemaVersion = values["schemaVersion"]?.toIntOrNull() ?: return null
        if (schemaVersion != 1) return null
        when (values["eventType"]) {
            OfflineEventType.MinorEvent.wireName -> OfflineSyncPayload.MinorEventPayload(
                MinorEventSyncPayload(
                    eventId = values.long("eventId") ?: return null,
                    sessionId = values.long("sessionId") ?: return null,
                    assessmentId = values.long("assessmentId") ?: return null,
                    windowId = values.long("windowId") ?: return null,
                    type = values["type"]?.takeIf { it in MINOR_TYPES } ?: return null,
                    score = values.intOrNull("score"),
                    confidence = values.double("confidence") ?: return null,
                    policyVersion = values["policyVersion"] ?: return null,
                    occurredAtEpochMillis = values.long("occurredAtEpochMillis") ?: return null,
                    createdAtElapsedRealtimeNanos = values.long("createdAtElapsedRealtimeNanos") ?: return null
                ),
                schemaVersion
            )

            OfflineEventType.LocalIncident.wireName -> OfflineSyncPayload.LocalIncidentPayload(
                LocalIncidentSyncPayload(
                    incidentId = values.long("incidentId") ?: return null,
                    sessionId = values.long("sessionId") ?: return null,
                    assessmentId = values.long("assessmentId") ?: return null,
                    windowId = values.long("windowId") ?: return null,
                    cause = values["cause"]?.takeIf { it in INCIDENT_CAUSES } ?: return null,
                    score = values.intOrNull("score"),
                    riskLevel = values["riskLevel"]?.takeIf { it in RISK_LEVELS } ?: return null,
                    confidence = values.double("confidence") ?: return null,
                    ruleSetVersion = values["ruleSetVersion"] ?: return null,
                    validationPolicyVersion = values["validationPolicyVersion"] ?: return null,
                    gpsQuality = values["gpsQuality"]?.takeIf { it in GPS_QUALITIES } ?: return null,
                    occurredAtEpochMillis = values.long("occurredAtEpochMillis") ?: return null,
                    createdAtElapsedRealtimeNanos = values.long("createdAtElapsedRealtimeNanos") ?: return null
                ),
                schemaVersion
            )

            OfflineEventType.AlertDispatchRequest.wireName -> OfflineSyncPayload.AlertDispatchRequestPayload(
                AlertDispatchRequestSyncPayload(
                    requestId = values.long("requestId") ?: return null,
                    incidentId = values.long("incidentId") ?: return null,
                    sessionId = values.long("sessionId") ?: return null,
                    assessmentId = values.long("assessmentId") ?: return null,
                    priority = values["priority"]?.takeIf { it in ALERT_PRIORITIES } ?: return null,
                    reason = values["reason"]?.takeIf { it in INCIDENT_CAUSES } ?: return null,
                    score = values.intOrNull("score"),
                    confidence = values.double("confidence") ?: return null,
                    deliveryStatus = values["deliveryStatus"]?.takeIf { it == "Pending" } ?: return null,
                    retryState = values["retryState"]?.takeIf { it == "NotStarted" } ?: return null,
                    occurredAtEpochMillis = values.long("occurredAtEpochMillis") ?: return null,
                    createdAtElapsedRealtimeNanos = values.long("createdAtElapsedRealtimeNanos") ?: return null
                ),
                schemaVersion
            )

            else -> null
        }
    }.getOrNull()

    private fun fields(vararg pairs: Pair<String, String>): ByteArray = pairs
        .joinToString(separator = "\n") { (key, value) -> "${encode(key)}=${encode(value)}" }
        .toByteArray(Charsets.UTF_8)

    private fun parse(text: String): Map<String, String> = text
        .lineSequence()
        .filter { it.contains('=') }
        .associate { line ->
            val key = line.substringBefore('=')
            val value = line.substringAfter('=')
            decode(key) to decode(value)
        }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
    private fun Map<String, String>.long(key: String): Long? = this[key]?.toLongOrNull()
    private fun Map<String, String>.double(key: String): Double? = this[key]?.toDoubleOrNull()
    private fun Map<String, String>.intOrNull(key: String): Int? = this[key]?.takeIf { it.isNotBlank() }?.toIntOrNull()

    companion object {
        private val MINOR_TYPES = setOf("Bump", "RoadIrregularity")
        private val INCIDENT_CAUSES = setOf("UserRequestedHelp", "Timeout", "CriticalPhysicalEvent")
        private val ALERT_PRIORITIES = setOf("Normal", "High", "Critical")
        private val RISK_LEVELS = setOf("Low", "Medium", "High", "Unknown")
        private val GPS_QUALITIES = setOf("Good", "Degraded", "Poor", "Unavailable", "Stale")
    }
}
