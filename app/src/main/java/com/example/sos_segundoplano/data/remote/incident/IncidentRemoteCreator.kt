package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.InvalidResponse
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.Timeout
import com.example.sos_segundoplano.data.remote.trip.ActiveTripLookupResult
import com.example.sos_segundoplano.data.remote.trip.ActiveTripRemoteResolver
import com.example.sos_segundoplano.data.remote.trip.RemoteTripSessionStore
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import com.example.sos_segundoplano.domain.validation.LocalIncident
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

fun interface IncidentRemoteCreator {
    suspend fun createIncident(incident: LocalIncident): LocalIncident
}

object NoOpIncidentRemoteCreator : IncidentRemoteCreator {
    override suspend fun createIncident(incident: LocalIncident): LocalIncident = incident.copy(
        remoteCreationStatus = IncidentRemoteCreationStatus.NotRequested
    )
}

class AuthenticatedIncidentRemoteCreator(
    private val authRepository: AuthRepository,
    private val remoteDataSource: IncidentRemoteDataSource,
    private val activeTripRemoteResolver: ActiveTripRemoteResolver,
    private val remoteTripSessionStore: RemoteTripSessionStore? = null,
    private val remoteIncidentLinkStore: RemoteIncidentLinkStore = InMemoryRemoteIncidentLinkStore(),
    private val eventLocationProvider: ManualSosLocationProvider = ManualSosLocationProvider { null },
    private val emergencyLocationPublisher: EmergencyLocationPublisher = NoOpEmergencyLocationPublisher,
    private val logger: IncidentRemoteLogger = NoOpIncidentRemoteLogger,
    private val nowUtc: () -> Instant = { Instant.now() }
) : IncidentRemoteCreator {
    override suspend fun createIncident(incident: LocalIncident): LocalIncident {
        val clientIncidentId = incident.stableClientIncidentUuid()
        remoteIncidentLinkStore.read(clientIncidentId)
            ?.takeIf {
                it.syncState == RemoteIncidentSyncState.Created &&
                    !it.remoteTripId.isNullOrBlank() &&
                    !it.remoteIncidentId.isNullOrBlank()
            }
            ?.let { existing ->
                val remoteIncidentId = requireNotNull(existing.remoteIncidentId)
                val remoteTripId = requireNotNull(existing.remoteTripId)
                return incident.copy(
                    remoteTripId = remoteTripId,
                    clientIncidentId = existing.clientIncidentId,
                    remoteIncidentId = remoteIncidentId,
                    remoteCreationStatus = IncidentRemoteCreationStatus.Success(remoteIncidentId)
                )
            }
        val occurredAtUtc = nowUtc()
        val localPendingLink = RemoteIncidentLink(
            localIncidentId = incident.incidentId,
            clientIncidentId = clientIncidentId,
            remoteTripId = null,
            remoteIncidentId = null,
            syncState = RemoteIncidentSyncState.Pending,
            updatedAtEpochMillis = occurredAtUtc.toEpochMilli()
        )
        if (!remoteIncidentLinkStore.save(localPendingLink)) {
            return incident.copy(
                clientIncidentId = clientIncidentId,
                remoteCreationStatus = IncidentRemoteCreationStatus.InvalidResponse("incident_link_persistence_failed")
            ).also {
                logger.remoteIncidentPersistenceFailed()
                logger.incidentCreationFailed()
            }
        }
        val persistedRemoteTripId = remoteTripSessionStore?.remoteTripId?.value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val remoteTripId = persistedRemoteTripId ?: when (val trip = activeTripRemoteResolver.resolveActiveTrip()) {
            is ActiveTripLookupResult.Found -> trip.remoteTripId
            ActiveTripLookupResult.NoActiveTrip -> return incident.copy(
                clientIncidentId = clientIncidentId,
                remoteCreationStatus = IncidentRemoteCreationStatus.MissingRequiredData("active_remote_trip_missing")
            ).also { logger.incidentCreationFailed() }
            is ActiveTripLookupResult.HttpError -> return incident.copy(
                clientIncidentId = clientIncidentId,
                remoteCreationStatus = IncidentRemoteCreationStatus.HttpError(trip.statusCode, "trip_lookup_failed")
            ).also { logger.incidentCreationFailed() }
            is ActiveTripLookupResult.NetworkUnavailable -> return incident.copy(
                clientIncidentId = clientIncidentId,
                remoteCreationStatus = IncidentRemoteCreationStatus.NetworkUnavailable(trip.sanitizedMessage)
            ).also { logger.incidentCreationFailed() }
            is ActiveTripLookupResult.Timeout -> return incident.copy(
                clientIncidentId = clientIncidentId,
                remoteCreationStatus = IncidentRemoteCreationStatus.Timeout(trip.sanitizedMessage)
            ).also { logger.incidentCreationFailed() }
            is ActiveTripLookupResult.InvalidResponse -> return incident.copy(
                clientIncidentId = clientIncidentId,
                remoteCreationStatus = IncidentRemoteCreationStatus.InvalidResponse(trip.sanitizedMessage)
            ).also { logger.incidentCreationFailed() }
        }
        val request = incident.toCreateIncidentRequest(remoteTripId, clientIncidentId, occurredAtUtc) ?: return incident.copy(
            clientIncidentId = clientIncidentId,
            remoteCreationStatus = IncidentRemoteCreationStatus.MissingRequiredData("incident_required_data_missing")
        ).also { logger.incidentCreationFailed() }
        val pendingLink = localPendingLink.copy(
            remoteTripId = remoteTripId,
            updatedAtEpochMillis = occurredAtUtc.toEpochMilli()
        )
        if (!remoteIncidentLinkStore.save(pendingLink)) {
            return incident.copy(
                remoteTripId = remoteTripId,
                clientIncidentId = clientIncidentId,
                remoteCreationStatus = IncidentRemoteCreationStatus.InvalidResponse("incident_link_persistence_failed")
            ).also {
                logger.remoteIncidentPersistenceFailed()
                logger.incidentCreationFailed()
            }
        }
        val token = when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            is AuthFailure -> return incident.copy(
                remoteTripId = remoteTripId,
                clientIncidentId = clientIncidentId,
                remoteCreationStatus = result.toIncidentRemoteStatus()
            ).also { logger.incidentCreationFailed() }
        }
        logger.remoteIncidentRequestStarted()
        val firstStatus = remoteDataSource.createIncident("Bearer $token", request)
        val status = retryOnceAfterUnauthorized(firstStatus, request)
        return when (status) {
            is IncidentRemoteCreationStatus.Success -> {
                val durable = remoteIncidentLinkStore.save(
                    pendingLink.copy(
                        remoteIncidentId = status.incidentId,
                        syncState = RemoteIncidentSyncState.Created,
                        updatedAtEpochMillis = nowUtc().toEpochMilli()
                    )
                )
                val created = incident.copy(
                    remoteTripId = remoteTripId,
                    clientIncidentId = clientIncidentId,
                    remoteIncidentId = status.incidentId,
                    remoteCreationStatus = status
                )
                eventLocationProvider.currentRealLocation()
                    ?.toEmergencyLocationSnapshot(status.incidentId)
                    ?.let { snapshot -> emergencyLocationPublisher.publishSafely(snapshot) }
                created.also {
                    if (!durable) logger.remoteIncidentPersistenceFailed()
                    logger.remoteIncidentCreated()
                    logger.remoteIncidentIdReceived()
                }
            }
            else -> incident.copy(
                remoteTripId = remoteTripId,
                clientIncidentId = clientIncidentId,
                remoteCreationStatus = status
            ).also { logger.incidentCreationFailed() }
        }
    }

    private suspend fun retryOnceAfterUnauthorized(
        first: IncidentRemoteCreationStatus,
        request: CreateIncidentRequestDto
    ): IncidentRemoteCreationStatus {
        if (first !is IncidentRemoteCreationStatus.HttpError || first.statusCode != 401) return first
        if (authRepository.refreshSession() !is AuthResult.Success) return first
        val refreshedToken = when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            is AuthFailure -> return result.toIncidentRemoteStatus()
        }
        return remoteDataSource.createIncident("Bearer $refreshedToken", request)
    }

    private fun AuthFailure.toIncidentRemoteStatus(): IncidentRemoteCreationStatus = when (this) {
        is NetworkUnavailable -> IncidentRemoteCreationStatus.NetworkUnavailable(sanitizedMessage ?: "network_unavailable")
        is Timeout -> IncidentRemoteCreationStatus.Timeout(sanitizedMessage ?: "network_timeout")
        is InvalidResponse -> IncidentRemoteCreationStatus.InvalidResponse(sanitizedMessage ?: "access_token_invalid")
        else -> IncidentRemoteCreationStatus.HttpError(401, "access_token_unavailable")
    }

    private fun LocalIncident.toCreateIncidentRequest(remoteTripId: String, clientIncidentId: String, occurredAtUtc: Instant): CreateIncidentRequestDto? {
        return CreateIncidentRequestDto(
            tripId = remoteTripId,
            clientIncidentId = clientIncidentId,
            source = if (cause == IncidentCause.ManualSos) "ManualSos" else "MobileDetection",
            cause = when (cause) {
                IncidentCause.Timeout -> "CountdownTimeout"
                IncidentCause.UserRequestedHelp -> "UserRequestedHelp"
                IncidentCause.CriticalPhysicalEvent -> "CriticalEvent"
                IncidentCause.ManualSos -> "ManualSos"
            },
            riskLevel = riskLevel.name,
            occurredAtUtc = occurredAtUtc.toString(),
            location = null,
            evidenceSummary = if (hasAssessmentEvidence) {
                IncidentEvidenceSummaryDto(
                    assessmentId = assessmentId,
                    windowId = windowId,
                    triggeredRules = relevantOutcomes.map { it.ruleId.name },
                    hasLocation = false
                )
            } else {
                null
            }
        )
    }

    private fun LocalIncident.stableClientIncidentUuid(): String {
        val existing = clientIncidentId
            ?.trim()
            ?.let { candidate -> runCatching { UUID.fromString(candidate).toString() }.getOrNull() }
        return existing ?: UUID.nameUUIDFromBytes(
            "motosos:$cause:$sessionId:$assessmentId:$windowId".toByteArray(StandardCharsets.UTF_8)
        ).toString()
    }
}

interface IncidentRemoteLogger {
    fun remoteIncidentRequestStarted()
    fun remoteIncidentCreated()
    fun remoteIncidentIdReceived()
    fun incidentCreationFailed()
    fun remoteIncidentPersistenceFailed()
}

object NoOpIncidentRemoteLogger : IncidentRemoteLogger {
    override fun remoteIncidentRequestStarted() = Unit
    override fun remoteIncidentCreated() = Unit
    override fun remoteIncidentIdReceived() = Unit
    override fun incidentCreationFailed() = Unit
    override fun remoteIncidentPersistenceFailed() = Unit
}
