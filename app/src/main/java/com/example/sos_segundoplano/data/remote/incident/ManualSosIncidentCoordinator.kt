package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.validation.IncidentStoreProvider
import com.example.sos_segundoplano.data.validation.LocalIncidentStore
import com.example.sos_segundoplano.domain.offline.OfflineEventSink
import com.example.sos_segundoplano.domain.rules.GpsQualityStatus
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import com.example.sos_segundoplano.domain.validation.LocalIncident
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.UUID

class ManualSosIncidentCoordinator(
    private val remoteCreator: ManualSosAlertCreator,
    private val offlineEventSink: OfflineEventSink,
    private val remoteIncidentLinkStore: RemoteIncidentLinkStore,
    private val incidentStore: LocalIncidentStore = IncidentStoreProvider.incidents,
    private val nextIncidentId: () -> Long = { IncidentStoreProvider.incidentIds.incrementAndGet() },
    private val nextClientIncidentId: () -> String = { UUID.randomUUID().toString() },
    private val nextClientAlertRequestId: () -> String = { UUID.randomUUID().toString() },
    private val nowUtc: () -> Instant = Instant::now,
    private val nowElapsedRealtimeNanos: () -> Long = System::nanoTime,
    private val currentOwnerUserId: () -> String? = { null },
    /** Schedules the dedicated manual-SOS recovery worker. Delay is expressed in milliseconds. */
    private val scheduleRecovery: (Long) -> Unit = {}
) {
    private val mutex = Mutex()
    private val inFlightLock = Any()
    private var inFlight: CompletableDeferred<LocalIncident>? = null

    suspend fun requestManualSos(
        progressReporter: ManualSosProgressReporter = ManualSosProgressReporter {}
    ): LocalIncident = requestManualSos(ManualSosSubmissionOptions(), progressReporter)

    suspend fun requestManualSos(
        options: ManualSosSubmissionOptions,
        progressReporter: ManualSosProgressReporter = ManualSosProgressReporter {}
    ): LocalIncident {
        val (request, owner) = synchronized(inFlightLock) {
            val existing = inFlight
            if (existing != null) {
                existing to false
            } else {
                val created = CompletableDeferred<LocalIncident>()
                inFlight = created
                created to true
            }
        }
        if (!owner) return request.await()
        return try {
            performRequest(options, progressReporter).also(request::complete)
        } catch (failure: Throwable) {
            request.completeExceptionally(failure)
            throw failure
        } finally {
            synchronized(inFlightLock) {
                if (inFlight === request) inFlight = null
            }
        }
    }

    /**
     * Background-only recovery seam. It NEVER creates a new manual SOS: if the durable pending
     * identity has already been acknowledged, this returns null. Sharing [mutex] with the foreground
     * request also prevents a recovery worker from racing a one-tap send.
     */
    suspend fun retryPendingManualSos(): LocalIncident? = mutex.withLock {
        val pending = readPendingManualSosForCurrentOwner() ?: return@withLock null
        val incident = pending.toLocalIncident()
        val result = remoteCreator.createManualSosAlert(incident)
        incidentStore.add(result)
        result
    }

    private suspend fun performRequest(
        options: ManualSosSubmissionOptions,
        progressReporter: ManualSosProgressReporter
    ): LocalIncident = mutex.withLock {
        val pendingLink = readPendingManualSosForCurrentOwner()
        progressReporter.report(
            if (pendingLink == null) ManualSosRequestState.Preparing else ManualSosRequestState.Retrying
        )
        val incident = if (pendingLink != null) {
            pendingLink.toLocalIncident()
        } else {
            createAndPersistLocalAttempt(options)
        }
        if (incident.remoteCreationStatus is IncidentRemoteCreationStatus.InvalidResponse) {
            return@withLock incidentStore.add(incident).also {
                progressReporter.report(ManualSosRequestState.RetryableFailure)
            }
        }
        if (pendingLink == null) {
            // SharedPreferences is the authoritative durable identity for manual SOS recovery.
            // The delayed worker is a process-death safety net; foreground remains the first sender.
            scheduleRecovery(MANUAL_RECOVERY_SAFETY_DELAY_MILLIS)
            enqueueOfflineWithoutBlocking(incident)
        }
        val result = remoteCreator.createManualSosAlert(incident, progressReporter)
        incidentStore.add(result)
        if (result.remoteCreationStatus !is IncidentRemoteCreationStatus.Success && result.remoteCreationStatus.isManualSosRetryable()) {
            scheduleRecovery(MANUAL_RECOVERY_RETRY_MILLIS)
        }
        progressReporter.report(result.toRequestState())
        result
    }

    private fun createAndPersistLocalAttempt(options: ManualSosSubmissionOptions): LocalIncident {
        val detectedAtUtc = nowUtc()
        val incident = newLocalIncident(
            localIncidentId = nextIncidentId(),
            clientIncidentId = nextClientIncidentId()
        )
        val persisted = remoteIncidentLinkStore.save(
            RemoteIncidentLink(
                localIncidentId = incident.incidentId,
                clientIncidentId = requireNotNull(incident.clientIncidentId),
                remoteTripId = null,
                remoteIncidentId = null,
                syncState = RemoteIncidentSyncState.Pending,
                updatedAtEpochMillis = detectedAtUtc.toEpochMilli(),
                clientAlertRequestId = nextClientAlertRequestId(),
                detectedAtUtc = detectedAtUtc.toString(),
                remoteAlertDispatchId = null,
                manualSeverity = options.severity.apiValue,
                manualPriority = options.priority.apiValue,
                ownerUserId = currentOwnerUserId()?.trim()?.takeIf { it.isNotEmpty() }
            )
        )
        return if (persisted) incident else incident.copy(
            remoteCreationStatus = IncidentRemoteCreationStatus.InvalidResponse("manual_sos_link_persistence_failed")
        )
    }

    private fun RemoteIncidentLink.toLocalIncident(): LocalIncident = newLocalIncident(
        localIncidentId = localIncidentId,
        clientIncidentId = clientIncidentId
    )

    private fun newLocalIncident(
        localIncidentId: Long,
        clientIncidentId: String
    ): LocalIncident = LocalIncident(
            incidentId = localIncidentId,
            sessionId = MANUAL_CONTEXT_ID,
            assessmentId = MANUAL_CONTEXT_ID,
            windowId = MANUAL_CONTEXT_ID,
            createdAtElapsedRealtimeNanos = nowElapsedRealtimeNanos(),
            cause = IncidentCause.ManualSos,
            score = null,
            riskLevel = RiskLevel.Unknown,
            confidence = 0.0,
            relevantOutcomes = emptyList(),
            ruleSetVersion = MANUAL_RULE_SET_VERSION,
            validationPolicyVersion = MANUAL_POLICY_VERSION,
            gpsQuality = GpsQualityStatus.Unavailable,
            hasAssessmentEvidence = false,
            clientIncidentId = clientIncidentId,
            remoteCreationStatus = IncidentRemoteCreationStatus.Pending
        )

    private suspend fun enqueueOfflineWithoutBlocking(incident: LocalIncident) {
        try {
            offlineEventSink.enqueueIncident(incident)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            Unit
        }
    }

    private fun readPendingManualSosForCurrentOwner(): RemoteIncidentLink? {
        val pending = remoteIncidentLinkStore.readPendingManualSos() ?: return null
        val owner = currentOwnerUserId()?.trim()?.takeIf { it.isNotEmpty() }
        return if (owner == null) {
            // Keeps legacy/unit-test records usable while preventing a new owner-scoped SOS from
            // being retried before the Rider session is restored.
            pending.takeIf { it.ownerUserId == null }
        } else {
            pending.takeIf { it.ownerUserId == owner }
        }
    }

    private fun LocalIncident.toRequestState(): ManualSosRequestState = when (val status = remoteCreationStatus) {
        is IncidentRemoteCreationStatus.Success -> ManualSosRequestState.Sent
        // Once the durable link exists, lack of network/location/runtime context is not a failed
        // manual SOS. The worker owns delivery and the Rider does not need to press SOS again.
        else -> if (status.isManualSosRetryable() && readPendingManualSosForCurrentOwner() != null) {
            ManualSosRequestState.SavedOffline
        } else {
            ManualSosRequestState.RetryableFailure
        }
    }

    private fun IncidentRemoteCreationStatus.isManualSosRetryable(): Boolean = when (this) {
        is IncidentRemoteCreationStatus.Success -> false
        is IncidentRemoteCreationStatus.NetworkUnavailable,
        is IncidentRemoteCreationStatus.Timeout,
        is IncidentRemoteCreationStatus.MissingRequiredData,
        IncidentRemoteCreationStatus.Pending,
        IncidentRemoteCreationStatus.NotRequested,
        IncidentRemoteCreationStatus.DuplicateAttempt -> true
        is IncidentRemoteCreationStatus.HttpError -> statusCode == 401 || statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500
        is IncidentRemoteCreationStatus.InvalidResponse -> sanitizedMessage in setOf(
            "access_token_invalid",
            "manual_sos_result_persistence_failed"
        )
    }

    private companion object {
        const val MANUAL_RULE_SET_VERSION = "manual-sos"
        const val MANUAL_POLICY_VERSION = "manual-sos-v1"
        const val MANUAL_CONTEXT_ID = 0L
        const val MANUAL_RECOVERY_SAFETY_DELAY_MILLIS = 15_000L
        const val MANUAL_RECOVERY_RETRY_MILLIS = 30_000L
    }
}
