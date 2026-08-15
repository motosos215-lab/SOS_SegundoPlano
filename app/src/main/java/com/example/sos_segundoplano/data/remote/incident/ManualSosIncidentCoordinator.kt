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
    private val nowElapsedRealtimeNanos: () -> Long = System::nanoTime
) {
    private val mutex = Mutex()
    private val inFlightLock = Any()
    private var inFlight: CompletableDeferred<LocalIncident>? = null

    suspend fun requestManualSos(
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
            performRequest(progressReporter).also(request::complete)
        } catch (failure: Throwable) {
            request.completeExceptionally(failure)
            throw failure
        } finally {
            synchronized(inFlightLock) {
                if (inFlight === request) inFlight = null
            }
        }
    }

    private suspend fun performRequest(progressReporter: ManualSosProgressReporter): LocalIncident = mutex.withLock {
        val pendingLink = remoteIncidentLinkStore.readPendingManualSos()
        progressReporter.report(
            if (pendingLink == null) ManualSosRequestState.Preparing else ManualSosRequestState.Retrying
        )
        val incident = if (pendingLink != null) {
            pendingLink.toLocalIncident()
        } else {
            createAndPersistLocalAttempt()
        }
        if (incident.remoteCreationStatus is IncidentRemoteCreationStatus.InvalidResponse) {
            return@withLock incidentStore.add(incident).also {
                progressReporter.report(ManualSosRequestState.RetryableFailure)
            }
        }
        if (pendingLink == null) enqueueOfflineWithoutBlocking(incident)
        val result = remoteCreator.createManualSosAlert(incident, progressReporter)
        incidentStore.add(result)
        progressReporter.report(result.toRequestState())
        result
    }

    private fun createAndPersistLocalAttempt(): LocalIncident {
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
                remoteAlertDispatchId = null
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

    private fun LocalIncident.toRequestState(): ManualSosRequestState = when (val status = remoteCreationStatus) {
        is IncidentRemoteCreationStatus.Success -> ManualSosRequestState.Sent
        is IncidentRemoteCreationStatus.MissingRequiredData -> if (status.sanitizedMessage == "manual_sos_location_missing") {
            ManualSosRequestState.LocationUnavailable
        } else {
            ManualSosRequestState.RetryableFailure
        }
        else -> ManualSosRequestState.RetryableFailure
    }

    private companion object {
        const val MANUAL_RULE_SET_VERSION = "manual-sos"
        const val MANUAL_POLICY_VERSION = "manual-sos-v1"
        const val MANUAL_CONTEXT_ID = 0L
    }
}
