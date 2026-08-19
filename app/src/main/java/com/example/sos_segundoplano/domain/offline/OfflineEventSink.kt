package com.example.sos_segundoplano.domain.offline

import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.validation.MinorEvent

interface OfflineEventSink {
    suspend fun enqueueMinorEvent(event: MinorEvent): OfflineQueueEnqueueResult
    suspend fun enqueueIncident(incident: LocalIncident): OfflineQueueEnqueueResult
    suspend fun enqueueAlertRequest(request: AlertDispatchRequest): OfflineQueueEnqueueResult
    suspend fun enqueueIncidentBundle(incident: LocalIncident, request: AlertDispatchRequest): OfflineQueueEnqueueResult

    /** Replaces the durable bundle payload after a real location is captured. */
    suspend fun updateIncidentBundle(incident: LocalIncident, request: AlertDispatchRequest): OfflineQueueEnqueueResult =
        OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.NotConfigured, "offline_bundle_update_not_configured")

    /** Durable bundle-level exclusion used by the online coordinator and the worker. */
    suspend fun claimAutomaticSosBundle(bundleKey: String, workerId: String, nowMillis: Long): AutomaticSosBundleClaimResult =
        AutomaticSosBundleClaimResult.NotRecoverable

    suspend fun acknowledgeAutomaticSosBundle(
        bundle: ClaimedAutomaticSosBundle,
        receipt: AutomaticSosRemoteReceipt,
        nowMillis: Long
    ): OfflineQueueTransitionResult = OfflineQueueTransitionResult.StaleClaim

    suspend fun releaseAutomaticSosBundle(
        bundle: ClaimedAutomaticSosBundle,
        permanent: Boolean,
        code: String,
        nowMillis: Long
    ): OfflineQueueTransitionResult = OfflineQueueTransitionResult.StaleClaim
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
