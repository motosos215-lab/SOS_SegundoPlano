package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.data.remote.incident.AutomaticSosAlertCreator
import com.example.sos_segundoplano.data.remote.incident.AutomaticSosRequestInput
import com.example.sos_segundoplano.data.remote.incident.AutomaticSosSubmissionResult
import com.example.sos_segundoplano.data.validation.AutoIncidentDiagnostics
import com.example.sos_segundoplano.domain.offline.AutomaticSosBundleClaimResult
import com.example.sos_segundoplano.domain.offline.AutomaticSosRemoteReceipt
import com.example.sos_segundoplano.domain.offline.OfflineQueueSyncResult
import com.example.sos_segundoplano.domain.offline.OfflineQueueTransitionResult
import com.example.sos_segundoplano.domain.offline.OfflineSyncPayload
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.validation.AlertPriority
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus

/**
 * Processes one automatic SOS as its durable two-row bundle, never as two transports.
 *
 * The bundle may be persisted before GPS or the remote trip ID is available. Recovery therefore
 * completes that runtime context from the current authenticated Rider session before submitting.
 */
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

    private suspend fun processClaim(
        bundle: com.example.sos_segundoplano.domain.offline.ClaimedAutomaticSosBundle,
        now: Long
    ): OfflineQueueSyncResult {
        val incidentPayload = (repository.decryptPayload(bundle.incident) as? OfflineCryptoResult.Success)?.value
            as? OfflineSyncPayload.LocalIncidentPayload
        val requestPayload = (repository.decryptPayload(bundle.request) as? OfflineCryptoResult.Success)?.value
            as? OfflineSyncPayload.AlertDispatchRequestPayload

        if (incidentPayload == null || requestPayload == null) {
            repository.releaseAutomaticSosBundle(bundle, permanent = true, code = "recovery_payload_incomplete", nowMillis = now)
            AutoIncidentDiagnostics.recoveryResult("not_safe")
            return OfflineQueueSyncResult.Completed
        }

        val base = incidentPayload.baseInput(requestPayload)
        if (base == null) {
            repository.releaseAutomaticSosBundle(bundle, permanent = true, code = "recovery_identity_incomplete", nowMillis = now)
            AutoIncidentDiagnostics.recoveryResult("not_safe")
            return OfflineQueueSyncResult.Completed
        }

        val currentIncident = incidentPayload.payload
        val resolvedTripId = creator.resolveRemoteTripId(currentIncident.remoteTripId)
        val resolvedLocation = creator.captureLocationCoordinates(currentIncident.latitude, currentIncident.longitude)

        if (resolvedTripId.isNullOrBlank() || resolvedLocation == null) {
            repository.releaseAutomaticSosBundle(
                bundle,
                permanent = false,
                code = if (resolvedTripId.isNullOrBlank()) "remote_trip_context_unavailable" else "location_context_unavailable",
                nowMillis = now
            )
            AutoIncidentDiagnostics.remoteContext(
                remoteTripPresent = !resolvedTripId.isNullOrBlank(),
                locationPresent = resolvedLocation != null
            )
            AutoIncidentDiagnostics.recoveryResult("retry_context")
            return OfflineQueueSyncResult.RetryRequired
        }

        AutoIncidentDiagnostics.remoteContext(remoteTripPresent = true, locationPresent = true)

        val contextChanged = currentIncident.remoteTripId != resolvedTripId ||
            currentIncident.latitude != resolvedLocation.latitude ||
            currentIncident.longitude != resolvedLocation.longitude ||
            bundle.incident.item.remoteTripId != resolvedTripId ||
            bundle.request.item.remoteTripId != resolvedTripId

        if (contextChanged) {
            val updated = repository.updateClaimedAutomaticSosContext(
                bundle = bundle,
                incidentPayload = incidentPayload,
                requestPayload = requestPayload,
                remoteTripId = resolvedTripId,
                latitude = resolvedLocation.latitude,
                longitude = resolvedLocation.longitude
            )
            if (!updated) {
                repository.releaseAutomaticSosBundle(bundle, permanent = false, code = "durable_context_update_failed", nowMillis = now)
                AutoIncidentDiagnostics.recoveryResult("retry_context_persistence")
                return OfflineQueueSyncResult.RetryRequired
            }
        }

        val input = AutomaticSosRequestInput(
            clientIncidentId = base.clientIncidentId,
            clientAlertRequestId = base.clientAlertRequestId,
            detectedAtEpochMillis = base.detectedAtEpochMillis,
            latitude = resolvedLocation.latitude,
            longitude = resolvedLocation.longitude,
            remoteTripId = resolvedTripId,
            cause = base.cause,
            riskLevel = base.riskLevel,
            priority = base.priority
        )

        return when (val result = creator.submit(input)) {
            is AutomaticSosSubmissionResult.Success -> when (
                repository.acknowledgeAutomaticSosBundle(
                    bundle,
                    AutomaticSosRemoteReceipt(result.remoteIncidentId, result.remoteAlertDispatchId),
                    now
                )
            ) {
                OfflineQueueTransitionResult.Applied -> {
                    AutoIncidentDiagnostics.receiptPersistResult("success")
                    AutoIncidentDiagnostics.recoveryResult("success")
                    // Ask the emergency worker for one follow-up pass; another durable SOS may be ready.
                    OfflineQueueSyncResult.RetryRequired
                }

                else -> {
                    AutoIncidentDiagnostics.receiptPersistResult("failure")
                    AutoIncidentDiagnostics.recoveryResult("retry")
                    OfflineQueueSyncResult.RetryRequired
                }
            }

            is AutomaticSosSubmissionResult.Failure -> {
                val permanent = result.status.isPermanentAutomaticSosFailureForRecovery()
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

    private data class BaseInput(
        val clientIncidentId: String,
        val clientAlertRequestId: String,
        val detectedAtEpochMillis: Long,
        val cause: IncidentCause,
        val riskLevel: RiskLevel,
        val priority: AlertPriority
    )

    private fun OfflineSyncPayload.LocalIncidentPayload.baseInput(
        request: OfflineSyncPayload.AlertDispatchRequestPayload
    ): BaseInput? {
        val incident = payload
        val dispatch = request.payload
        val cause = runCatching { IncidentCause.valueOf(incident.cause) }.getOrNull() ?: return null
        val priority = runCatching { AlertPriority.valueOf(dispatch.priority) }.getOrNull() ?: return null
        val risk = runCatching { RiskLevel.valueOf(incident.riskLevel) }.getOrNull() ?: return null
        val clientIncident = incident.clientIncidentId?.takeIf { it.isNotBlank() } ?: return null
        val clientRequest = dispatch.clientAlertRequestId?.takeIf { it.isNotBlank() } ?: return null
        val timestamp = incident.detectedAtEpochMillis ?: return null
        if (cause == IncidentCause.ManualSos || dispatch.reason != incident.cause) return null
        return BaseInput(clientIncident, clientRequest, timestamp, cause, risk, priority)
    }

    private fun IncidentRemoteCreationStatus.isPermanentAutomaticSosFailureForRecovery(): Boolean = when (this) {
        // Runtime context may become available moments later; never make that terminal in recovery.
        is IncidentRemoteCreationStatus.MissingRequiredData -> false
        is IncidentRemoteCreationStatus.HttpError -> statusCode in 400..499 && statusCode !in setOf(401, 408, 429)
        else -> false
    }
}
