package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.InvalidResponse
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.Timeout
import com.example.sos_segundoplano.data.remote.trip.ActiveTripLookupResult
import com.example.sos_segundoplano.data.remote.trip.ActiveTripRemoteResolver
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
    private val logger: IncidentRemoteLogger = NoOpIncidentRemoteLogger,
    private val nowUtc: () -> Instant = { Instant.now() }
) : IncidentRemoteCreator {
    override suspend fun createIncident(incident: LocalIncident): LocalIncident {
        if (incident.cause != IncidentCause.Timeout) return incident
        val remoteTripId = when (val trip = activeTripRemoteResolver.resolveActiveTrip()) {
            is ActiveTripLookupResult.Found -> trip.remoteTripId
            ActiveTripLookupResult.NoActiveTrip -> return incident.copy(
                remoteCreationStatus = IncidentRemoteCreationStatus.MissingRequiredData("active_remote_trip_missing")
            ).also { logger.incidentCreationFailed() }
            is ActiveTripLookupResult.HttpError -> return incident.copy(
                remoteCreationStatus = IncidentRemoteCreationStatus.HttpError(trip.statusCode, "trip_lookup_failed")
            ).also { logger.incidentCreationFailed() }
            is ActiveTripLookupResult.NetworkUnavailable -> return incident.copy(
                remoteCreationStatus = IncidentRemoteCreationStatus.NetworkUnavailable(trip.sanitizedMessage)
            ).also { logger.incidentCreationFailed() }
            is ActiveTripLookupResult.Timeout -> return incident.copy(
                remoteCreationStatus = IncidentRemoteCreationStatus.Timeout(trip.sanitizedMessage)
            ).also { logger.incidentCreationFailed() }
            is ActiveTripLookupResult.InvalidResponse -> return incident.copy(
                remoteCreationStatus = IncidentRemoteCreationStatus.InvalidResponse(trip.sanitizedMessage)
            ).also { logger.incidentCreationFailed() }
        }
        val clientIncidentId = incident.stableClientIncidentUuid()
        val request = incident.toCreateIncidentRequest(remoteTripId, clientIncidentId, nowUtc()) ?: return incident.copy(
            remoteCreationStatus = IncidentRemoteCreationStatus.MissingRequiredData("incident_required_data_missing")
        ).also { logger.incidentCreationFailed() }
        val token = when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            is AuthFailure -> return incident.copy(remoteCreationStatus = result.toIncidentRemoteStatus()).also { logger.incidentCreationFailed() }
        }
        logger.remoteIncidentRequestStarted()
        val status = remoteDataSource.createIncident("Bearer $token", request)
        return when (status) {
            is IncidentRemoteCreationStatus.Success -> incident.copy(
                remoteTripId = remoteTripId,
                clientIncidentId = clientIncidentId,
                remoteIncidentId = status.incidentId,
                remoteCreationStatus = status
            ).also {
                logger.remoteIncidentCreated()
                logger.remoteIncidentIdReceived()
            }
            else -> incident.copy(
                remoteTripId = remoteTripId,
                clientIncidentId = clientIncidentId,
                remoteCreationStatus = status
            ).also { logger.incidentCreationFailed() }
        }
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
            source = "MobileDetection",
            cause = "CountdownTimeout",
            riskLevel = riskLevel.name,
            score = score,
            confidence = confidence,
            gpsQuality = gpsQuality.name,
            ruleSetVersion = ruleSetVersion,
            validationPolicyVersion = validationPolicyVersion,
            occurredAtUtc = occurredAtUtc.toString()
        )
    }

    private fun LocalIncident.stableClientIncidentUuid(): String = UUID.nameUUIDFromBytes(
        "motosos:$sessionId:$assessmentId:$windowId".toByteArray(StandardCharsets.UTF_8)
    ).toString()
}

interface IncidentRemoteLogger {
    fun remoteIncidentRequestStarted()
    fun remoteIncidentCreated()
    fun remoteIncidentIdReceived()
    fun incidentCreationFailed()
}

object NoOpIncidentRemoteLogger : IncidentRemoteLogger {
    override fun remoteIncidentRequestStarted() = Unit
    override fun remoteIncidentCreated() = Unit
    override fun remoteIncidentIdReceived() = Unit
    override fun incidentCreationFailed() = Unit
}
