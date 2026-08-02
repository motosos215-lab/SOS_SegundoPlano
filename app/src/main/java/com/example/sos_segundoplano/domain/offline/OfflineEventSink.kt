package com.example.sos_segundoplano.domain.offline

import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.validation.MinorEvent

interface OfflineEventSink {
    suspend fun enqueueMinorEvent(event: MinorEvent): OfflineQueueEnqueueResult
    suspend fun enqueueIncident(incident: LocalIncident): OfflineQueueEnqueueResult
    suspend fun enqueueAlertRequest(request: AlertDispatchRequest): OfflineQueueEnqueueResult
    suspend fun enqueueIncidentBundle(incident: LocalIncident, request: AlertDispatchRequest): OfflineQueueEnqueueResult
}

object NoOpOfflineEventSink : OfflineEventSink {
    override suspend fun enqueueMinorEvent(event: MinorEvent): OfflineQueueEnqueueResult =
        OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.NotConfigured, "offline_sink_not_configured")

    override suspend fun enqueueIncident(incident: LocalIncident): OfflineQueueEnqueueResult =
        OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.NotConfigured, "offline_sink_not_configured")

    override suspend fun enqueueAlertRequest(request: AlertDispatchRequest): OfflineQueueEnqueueResult =
        OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.NotConfigured, "offline_sink_not_configured")

    override suspend fun enqueueIncidentBundle(incident: LocalIncident, request: AlertDispatchRequest): OfflineQueueEnqueueResult =
        OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.NotConfigured, "offline_sink_not_configured")
}
