package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.InvalidResponse
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.Timeout
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import com.example.sos_segundoplano.domain.validation.LocalIncident
import java.time.Instant

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
    private val nowUtc: () -> Instant = { Instant.now() }
) : IncidentRemoteCreator {
    override suspend fun createIncident(incident: LocalIncident): LocalIncident {
        if (incident.cause != IncidentCause.Timeout) return incident
        val request = incident.toCreateIncidentRequest(nowUtc()) ?: return incident.copy(
            remoteCreationStatus = IncidentRemoteCreationStatus.MissingRequiredData("incident_required_data_missing")
        )
        val token = when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            is AuthFailure -> return incident.copy(remoteCreationStatus = result.toIncidentRemoteStatus())
        }
        val status = remoteDataSource.createIncident("Bearer $token", request)
        return when (status) {
            is IncidentRemoteCreationStatus.Success -> incident.copy(
                remoteIncidentId = status.incidentId,
                remoteCreationStatus = status
            )
            else -> incident.copy(remoteCreationStatus = status)
        }
    }

    private fun AuthFailure.toIncidentRemoteStatus(): IncidentRemoteCreationStatus = when (this) {
        is NetworkUnavailable -> IncidentRemoteCreationStatus.NetworkUnavailable(sanitizedMessage ?: "network_unavailable")
        is Timeout -> IncidentRemoteCreationStatus.Timeout(sanitizedMessage ?: "network_timeout")
        is InvalidResponse -> IncidentRemoteCreationStatus.InvalidResponse(sanitizedMessage ?: "access_token_invalid")
        else -> IncidentRemoteCreationStatus.HttpError(401, "access_token_unavailable")
    }

    private fun LocalIncident.toCreateIncidentRequest(occurredAtUtc: Instant): CreateIncidentRequestDto? {
        val scoreValue = score ?: return null
        return CreateIncidentRequestDto(
            tripId = sessionId.toString(),
            clientIncidentId = "mobile-$sessionId-$assessmentId-$windowId",
            source = "MobileDetection",
            cause = "CountdownTimeout",
            riskLevel = riskLevel.name,
            score = scoreValue,
            confidence = confidence,
            gpsQuality = gpsQuality.name,
            ruleSetVersion = ruleSetVersion,
            validationPolicyVersion = validationPolicyVersion,
            occurredAtUtc = occurredAtUtc.toString()
        )
    }
}
