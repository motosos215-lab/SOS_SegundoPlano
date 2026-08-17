package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.data.remote.incident.AutomaticSosAlertCreator
import com.example.sos_segundoplano.data.remote.incident.AutomaticSosRequestInput
import com.example.sos_segundoplano.data.remote.incident.AutomaticSosSubmissionResult
import com.example.sos_segundoplano.domain.offline.AutomaticSosBundleClaimResult
import com.example.sos_segundoplano.domain.offline.AutomaticSosRemoteReceipt
import com.example.sos_segundoplano.domain.offline.OfflineSyncPayload
import com.example.sos_segundoplano.domain.offline.OfflineQueueSyncResult
import com.example.sos_segundoplano.domain.offline.OfflineQueueTransitionResult
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.validation.AlertPriority
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.data.validation.AutoIncidentDiagnostics

/** Processes one automatic SOS as its durable two-row bundle, never as two transports. */
class AutomaticSosBundleRecoveryProcessor(
    private val repository: RoomOfflineQueueRepository,
    private val creator: AutomaticSosAlertCreator,
    private val clock: com.example.sos_segundoplano.domain.offline.WallClock
) {
    suspend fun process(workerId: String): OfflineQueueSyncResult {
        val now = clock.currentTimeMillis()
        return when (val claim = repository.claimNextAutomaticSosBundle(workerId, now)) {
            AutomaticSosBundleClaimResult.BusyOrUnavailable -> {
                AutoIncidentDiagnostics.recoveryResult("busy")
                OfflineQueueSyncResult.NothingToDo
            }
            AutomaticSosBundleClaimResult.NotRecoverable -> {
                AutoIncidentDiagnostics.recoveryResult("not_safe")
                OfflineQueueSyncResult.NothingToDo
            }
            is AutomaticSosBundleClaimResult.Acquired -> {
                AutoIncidentDiagnostics.recoveryStarted()
                processClaim(claim.bundle, now)
            }
        }
    }

    private suspend fun processClaim(bundle: com.example.sos_segundoplano.domain.offline.ClaimedAutomaticSosBundle, now: Long): OfflineQueueSyncResult {
        val incident = (repository.decryptPayload(bundle.incident) as? OfflineCryptoResult.Success)?.value as? OfflineSyncPayload.LocalIncidentPayload
        val request = (repository.decryptPayload(bundle.request) as? OfflineCryptoResult.Success)?.value as? OfflineSyncPayload.AlertDispatchRequestPayload
        val input = incident?.let { i -> request?.let { r -> i.toInput(r) } }
        if (input == null) {
            repository.releaseAutomaticSosBundle(bundle, permanent = true, code = "recovery_payload_incomplete", nowMillis = now)
            AutoIncidentDiagnostics.recoveryResult("not_safe")
            return OfflineQueueSyncResult.Completed
        }
        return when (val result = creator.submit(input)) {
            is AutomaticSosSubmissionResult.Success -> when (repository.acknowledgeAutomaticSosBundle(bundle, AutomaticSosRemoteReceipt(result.remoteIncidentId, result.remoteAlertDispatchId), now)) {
                // Ask WorkManager for one follow-up pass; a different durable bundle may be ready.
                OfflineQueueTransitionResult.Applied -> {
                    AutoIncidentDiagnostics.receiptPersistResult("success")
                    AutoIncidentDiagnostics.recoveryResult("success")
                    OfflineQueueSyncResult.RetryRequired
                }
                else -> {
                    AutoIncidentDiagnostics.receiptPersistResult("failure")
                    AutoIncidentDiagnostics.recoveryResult("retry")
                    OfflineQueueSyncResult.RetryRequired
                }
            }
            is AutomaticSosSubmissionResult.Failure -> {
                val permanent = result.status is com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus.MissingRequiredData
                repository.releaseAutomaticSosBundle(bundle, permanent, "remote_submission_failed", now)
                AutoIncidentDiagnostics.recoveryResult(if (permanent) "not_safe" else "retry")
                if (permanent) OfflineQueueSyncResult.Completed else OfflineQueueSyncResult.RetryRequired
            }
            AutomaticSosSubmissionResult.NotConfigured -> {
                repository.releaseAutomaticSosBundle(bundle, permanent = false, code = "auth_or_transport_unavailable", nowMillis = now)
                AutoIncidentDiagnostics.recoveryResult("auth_unavailable")
                OfflineQueueSyncResult.RetryRequired
            }
        }
    }

    private fun OfflineSyncPayload.LocalIncidentPayload.toInput(request: OfflineSyncPayload.AlertDispatchRequestPayload): AutomaticSosRequestInput? {
        val incident = payload
        val dispatch = request.payload
        val cause = runCatching { IncidentCause.valueOf(incident.cause) }.getOrNull() ?: return null
        val priority = runCatching { AlertPriority.valueOf(dispatch.priority) }.getOrNull() ?: return null
        val risk = runCatching { RiskLevel.valueOf(incident.riskLevel) }.getOrNull() ?: return null
        val clientIncident = incident.clientIncidentId?.takeIf { it.isNotBlank() } ?: return null
        val clientRequest = dispatch.clientAlertRequestId?.takeIf { it.isNotBlank() } ?: return null
        val timestamp = incident.detectedAtEpochMillis ?: return null
        val latitude = incident.latitude ?: return null
        val longitude = incident.longitude ?: return null
        val trip = incident.remoteTripId?.takeIf { it.isNotBlank() } ?: return null
        if (cause == IncidentCause.ManualSos || dispatch.reason != incident.cause) return null
        return AutomaticSosRequestInput(clientIncident, clientRequest, timestamp, latitude, longitude, trip, cause, risk, priority)
    }
}
