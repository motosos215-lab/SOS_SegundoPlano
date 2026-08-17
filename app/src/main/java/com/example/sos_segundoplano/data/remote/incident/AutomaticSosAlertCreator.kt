package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.remote.trip.ActiveTripLookupResult
import com.example.sos_segundoplano.data.remote.trip.ActiveTripRemoteResolver
import com.example.sos_segundoplano.data.remote.trip.RemoteTripSessionStore
import com.example.sos_segundoplano.data.validation.AutoIncidentDiagnostics
import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.InvalidResponse
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.Timeout
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.sos.MobileSosIncidentType
import com.example.sos_segundoplano.domain.sos.MobileSosPriority
import com.example.sos_segundoplano.domain.sos.MobileSosReason
import com.example.sos_segundoplano.domain.sos.MobileSosSeverity
import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.AlertPriority
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import com.example.sos_segundoplano.domain.validation.LocalIncident
import java.time.Instant
import java.util.UUID

/** Sends automatic Rider incidents through the canonical mobile SOS endpoint. */
interface AutomaticSosAlertCreator {
    suspend fun captureLocation(incident: LocalIncident): LocalIncident
    /** Resolves the trip before submission so the coordinator can durably persist it first. */
    suspend fun resolveRemoteTrip(incident: LocalIncident): LocalIncident = incident
    suspend fun createAutomaticSosAlert(incident: LocalIncident, request: AlertDispatchRequest): LocalIncident
    /** Shared online/recovery submission seam. Inputs are already durable and must never be regenerated. */
    suspend fun submit(input: AutomaticSosRequestInput): AutomaticSosSubmissionResult =
        AutomaticSosSubmissionResult.NotConfigured
}

data class AutomaticSosRequestInput(
    val clientIncidentId: String,
    val clientAlertRequestId: String,
    val detectedAtEpochMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val remoteTripId: String,
    val cause: IncidentCause,
    val riskLevel: RiskLevel,
    val priority: AlertPriority
)

sealed interface AutomaticSosSubmissionResult {
    data class Success(val remoteIncidentId: String, val remoteAlertDispatchId: String) : AutomaticSosSubmissionResult
    data class Failure(val status: IncidentRemoteCreationStatus) : AutomaticSosSubmissionResult
    data object NotConfigured : AutomaticSosSubmissionResult
}

object NoOpAutomaticSosAlertCreator : AutomaticSosAlertCreator {
    override suspend fun captureLocation(incident: LocalIncident): LocalIncident = incident.copy(
        remoteCreationStatus = IncidentRemoteCreationStatus.NotRequested
    )

    override suspend fun resolveRemoteTrip(incident: LocalIncident): LocalIncident = incident

    override suspend fun createAutomaticSosAlert(
        incident: LocalIncident,
        request: AlertDispatchRequest
    ): LocalIncident = incident.copy(remoteCreationStatus = IncidentRemoteCreationStatus.NotRequested)
}

class AuthenticatedAutomaticSosAlertCreator(
    private val authRepository: AuthRepository,
    private val remoteDataSource: ManualSosAlertRemoteDataSource,
    private val activeTripRemoteResolver: ActiveTripRemoteResolver,
    private val remoteTripSessionStore: RemoteTripSessionStore,
    private val locationProvider: ManualSosLocationProvider
) : AutomaticSosAlertCreator {
    override suspend fun captureLocation(incident: LocalIncident): LocalIncident {
        if (incident.hasValidLocation()) {
            AutoIncidentDiagnostics.locationResult(true)
            return incident.copy(remoteCreationStatus = IncidentRemoteCreationStatus.Pending)
        }
        val location = locationProvider.currentRealLocation()
        if (location == null) {
            AutoIncidentDiagnostics.locationResult(false)
            return incident.copy(
                remoteCreationStatus = IncidentRemoteCreationStatus.MissingRequiredData("location_unavailable")
            )
        }
        AutoIncidentDiagnostics.locationResult(true)
        return incident.copy(
            latitude = location.latitude,
            longitude = location.longitude,
            remoteCreationStatus = IncidentRemoteCreationStatus.Pending
        )
    }

    override suspend fun createAutomaticSosAlert(
        incident: LocalIncident,
        request: AlertDispatchRequest
    ): LocalIncident {
        val clientIncidentId = incident.clientIncidentId.validUuid()
            ?: return incident.failed(IncidentRemoteCreationStatus.MissingRequiredData("client_incident_id_missing"))
        val clientAlertRequestId = request.clientAlertRequestId.validUuid()
            ?: return incident.failed(IncidentRemoteCreationStatus.MissingRequiredData("client_alert_request_id_missing"))
        val detectedAtEpochMillis = incident.detectedAtEpochMillis
            ?: return incident.failed(IncidentRemoteCreationStatus.MissingRequiredData("detected_at_missing"))
        val latitude = incident.latitude
            ?: return incident.failed(IncidentRemoteCreationStatus.MissingRequiredData("location_unavailable"))
        val longitude = incident.longitude
            ?: return incident.failed(IncidentRemoteCreationStatus.MissingRequiredData("location_unavailable"))
        if (!validCoordinates(latitude, longitude)) {
            return incident.failed(IncidentRemoteCreationStatus.MissingRequiredData("location_unavailable"))
        }
        val remoteTripId = incident.remoteTripId.normalized()
            ?: resolveRemoteTrip(incident).remoteTripId.normalized()
            ?: return incident.failed(IncidentRemoteCreationStatus.MissingRequiredData("active_remote_trip_missing"))
        AutoIncidentDiagnostics.remoteContext(remoteTripPresent = true, locationPresent = true)
        val input = AutomaticSosRequestInput(clientIncidentId, clientAlertRequestId, detectedAtEpochMillis, latitude, longitude, remoteTripId, incident.cause, incident.riskLevel, request.priority)
        return when (val submission = submit(input)) {
            is AutomaticSosSubmissionResult.Success -> incident.copy(
                remoteTripId = remoteTripId,
                remoteIncidentId = submission.remoteIncidentId,
                remoteAlertDispatchId = submission.remoteAlertDispatchId,
                remoteCreationStatus = IncidentRemoteCreationStatus.Success(submission.remoteIncidentId)
            )
            is AutomaticSosSubmissionResult.Failure -> incident.copy(remoteTripId = remoteTripId).failed(submission.status)
            AutomaticSosSubmissionResult.NotConfigured -> incident.copy(remoteTripId = remoteTripId).failed(IncidentRemoteCreationStatus.InvalidResponse("automatic_sos_not_configured"))
        }
    }

    override suspend fun submit(input: AutomaticSosRequestInput): AutomaticSosSubmissionResult {
        if (input.clientIncidentId.validUuid() == null || input.clientAlertRequestId.validUuid() == null || input.remoteTripId.normalized() == null) {
            return AutomaticSosSubmissionResult.Failure(IncidentRemoteCreationStatus.MissingRequiredData("automatic_sos_identity_missing"))
        }
        if (!input.latitude.isFinite() || !input.longitude.isFinite() || input.latitude !in -90.0..90.0 || input.longitude !in -180.0..180.0 || (input.latitude == 0.0 && input.longitude == 0.0)) {
            return AutomaticSosSubmissionResult.Failure(IncidentRemoteCreationStatus.MissingRequiredData("location_unavailable"))
        }
        val requestDto = input.toRequest() ?: return AutomaticSosSubmissionResult.Failure(IncidentRemoteCreationStatus.MissingRequiredData("automatic_sos_mapping_missing"))
        val token = when (val auth = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> auth.value.reveal()
            is AuthFailure -> return AutomaticSosSubmissionResult.Failure(auth.toRemoteStatus())
        }
        AutoIncidentDiagnostics.mobileSosStarted()
        val status = retryOnceAfterUnauthorized(remoteDataSource.createManualSosAlert("Bearer $token", requestDto), requestDto)
        return when (status) {
            is ManualSosAlertSubmissionStatus.Success -> {
                if (!status.hasPreparedAttempt()) {
                    AutomaticSosSubmissionResult.Failure(IncidentRemoteCreationStatus.InvalidResponse("alert_not_prepared"))
                } else {
                    AutoIncidentDiagnostics.mobileSosSuccess(status.summary)
                    AutomaticSosSubmissionResult.Success(status.remoteIncidentId, status.remoteAlertDispatchId)
                }
            }
            else -> AutomaticSosSubmissionResult.Failure(status.toRemoteStatus())
        }
    }

    override suspend fun resolveRemoteTrip(incident: LocalIncident): LocalIncident {
        incident.remoteTripId.normalized()?.let { return incident.copy(remoteTripId = it, remoteCreationStatus = IncidentRemoteCreationStatus.Pending) }
        remoteTripSessionStore.remoteTripId.value.normalized()?.let {
            return incident.copy(remoteTripId = it, remoteCreationStatus = IncidentRemoteCreationStatus.Pending)
        }
        return when (val lookup = activeTripRemoteResolver.resolveActiveTrip()) {
            is ActiveTripLookupResult.Found -> incident.copy(
                remoteTripId = lookup.remoteTripId,
                remoteCreationStatus = IncidentRemoteCreationStatus.Pending
            )
            ActiveTripLookupResult.NoActiveTrip -> incident.failed(IncidentRemoteCreationStatus.MissingRequiredData("active_remote_trip_missing"))
            is ActiveTripLookupResult.HttpError -> incident.failed(IncidentRemoteCreationStatus.HttpError(lookup.statusCode, "trip_lookup_failed"))
            is ActiveTripLookupResult.NetworkUnavailable -> incident.failed(IncidentRemoteCreationStatus.NetworkUnavailable(lookup.sanitizedMessage))
            is ActiveTripLookupResult.Timeout -> incident.failed(IncidentRemoteCreationStatus.Timeout(lookup.sanitizedMessage))
            is ActiveTripLookupResult.InvalidResponse -> incident.failed(IncidentRemoteCreationStatus.InvalidResponse(lookup.sanitizedMessage))
        }
    }

    private suspend fun retryOnceAfterUnauthorized(
        first: ManualSosAlertSubmissionStatus,
        request: ManualSosAlertRequestDto
    ): ManualSosAlertSubmissionStatus {
        if (first !is ManualSosAlertSubmissionStatus.HttpError || first.statusCode != 401) return first
        if (authRepository.refreshSession() !is AuthResult.Success) return first
        val token = when (val auth = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> auth.value.reveal()
            is AuthFailure -> return auth.toSubmissionStatus()
        }
        return remoteDataSource.createManualSosAlert("Bearer $token", request)
    }

    private fun AutomaticSosRequestInput.toRequest(): ManualSosAlertRequestDto? {
        val type = when (cause) {
            IncidentCause.Timeout -> MobileSosIncidentType.CountdownTimeout
            IncidentCause.UserRequestedHelp -> MobileSosIncidentType.UserRequestedHelp
            IncidentCause.CriticalPhysicalEvent -> MobileSosIncidentType.CriticalEvent
            IncidentCause.ManualSos -> return null
        }
        val reason = when (cause) {
            IncidentCause.Timeout -> MobileSosReason.CountdownTimeout
            IncidentCause.UserRequestedHelp -> MobileSosReason.UserRequestedHelp
            IncidentCause.CriticalPhysicalEvent -> MobileSosReason.CriticalEvent
            IncidentCause.ManualSos -> return null
        }
        return ManualSosAlertRequestDto(
            tripId = remoteTripId,
            clientIncidentId = clientIncidentId,
            clientAlertRequestId = clientAlertRequestId,
            incidentType = type.apiValue,
            severity = riskLevel.toSeverity().apiValue,
            detectedAtUtc = Instant.ofEpochMilli(detectedAtEpochMillis).toString(),
            latitude = latitude,
            longitude = longitude,
            priority = priority.toMobilePriority().apiValue,
            reason = reason.apiValue,
            notes = null
        )
    }

    private fun ManualSosAlertSubmissionStatus.Success.hasPreparedAttempt(): Boolean =
        (summary?.totalPrepared ?: 0) > 0 || notificationAttempts.any { it.status?.equals("Prepared", ignoreCase = true) == true }

    private fun RiskLevel.toSeverity(): MobileSosSeverity = when (this) {
        RiskLevel.Low -> MobileSosSeverity.Low
        RiskLevel.Medium -> MobileSosSeverity.Medium
        RiskLevel.High -> MobileSosSeverity.High
        RiskLevel.Unknown -> MobileSosSeverity.Unknown
    }

    private fun AlertPriority.toMobilePriority(): MobileSosPriority = when (this) {
        AlertPriority.Normal -> MobileSosPriority.Low
        AlertPriority.High -> MobileSosPriority.High
        AlertPriority.Critical -> MobileSosPriority.Critical
    }

    private fun AuthFailure.toRemoteStatus(): IncidentRemoteCreationStatus = when (this) {
        is NetworkUnavailable -> IncidentRemoteCreationStatus.NetworkUnavailable(sanitizedMessage ?: "network_unavailable")
        is Timeout -> IncidentRemoteCreationStatus.Timeout(sanitizedMessage ?: "network_timeout")
        is InvalidResponse -> IncidentRemoteCreationStatus.InvalidResponse(sanitizedMessage ?: "access_token_invalid")
        else -> IncidentRemoteCreationStatus.HttpError(401, "access_token_unavailable")
    }

    private fun AuthFailure.toSubmissionStatus(): ManualSosAlertSubmissionStatus = when (this) {
        is NetworkUnavailable -> ManualSosAlertSubmissionStatus.NetworkUnavailable(sanitizedMessage ?: "network_unavailable")
        is Timeout -> ManualSosAlertSubmissionStatus.Timeout(sanitizedMessage ?: "network_timeout")
        is InvalidResponse -> ManualSosAlertSubmissionStatus.InvalidResponse(sanitizedMessage ?: "access_token_invalid")
        else -> ManualSosAlertSubmissionStatus.HttpError(401, "access_token_unavailable")
    }

    private fun ManualSosAlertSubmissionStatus.toRemoteStatus(): IncidentRemoteCreationStatus = when (this) {
        is ManualSosAlertSubmissionStatus.Success -> IncidentRemoteCreationStatus.Success(remoteIncidentId)
        is ManualSosAlertSubmissionStatus.HttpError -> IncidentRemoteCreationStatus.HttpError(statusCode, sanitizedMessage)
        is ManualSosAlertSubmissionStatus.NetworkUnavailable -> IncidentRemoteCreationStatus.NetworkUnavailable(sanitizedMessage)
        is ManualSosAlertSubmissionStatus.Timeout -> IncidentRemoteCreationStatus.Timeout(sanitizedMessage)
        is ManualSosAlertSubmissionStatus.InvalidResponse -> IncidentRemoteCreationStatus.InvalidResponse(sanitizedMessage)
    }

    private fun LocalIncident.failed(status: IncidentRemoteCreationStatus): LocalIncident = copy(remoteCreationStatus = status)
    private fun LocalIncident.hasValidLocation(): Boolean = validCoordinates(latitude, longitude)
    private fun validCoordinates(latitude: Double?, longitude: Double?): Boolean =
        latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 && !(latitude == 0.0 && longitude == 0.0)
    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    private fun String?.validUuid(): String? = normalized()?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
}
