package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.remote.trip.ActiveTripLookupResult
import com.example.sos_segundoplano.data.remote.trip.ActiveTripRemoteResolver
import com.example.sos_segundoplano.data.remote.trip.RemoteTripSessionStore
import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.InvalidResponse
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.Timeout
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.sos.MobileSosIncidentType
import com.example.sos_segundoplano.domain.sos.MobileSosPriority
import com.example.sos_segundoplano.domain.sos.MobileSosReason
import com.example.sos_segundoplano.domain.sos.MobileSosSeverity
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import com.example.sos_segundoplano.domain.validation.LocalIncident
import java.time.Instant
import java.util.UUID

fun interface ManualSosAlertCreator {
    suspend fun createManualSosAlert(incident: LocalIncident): LocalIncident
}

class AuthenticatedManualSosAlertCreator(
    private val authRepository: AuthRepository,
    private val remoteDataSource: ManualSosAlertRemoteDataSource,
    private val activeTripRemoteResolver: ActiveTripRemoteResolver,
    private val remoteTripSessionStore: RemoteTripSessionStore,
    private val remoteIncidentLinkStore: RemoteIncidentLinkStore,
    private val locationProvider: ManualSosLocationProvider,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : ManualSosAlertCreator {
    override suspend fun createManualSosAlert(incident: LocalIncident): LocalIncident {
        val clientIncidentId = incident.clientIncidentId.validUuid()
            ?: return incident.failed(IncidentRemoteCreationStatus.InvalidResponse("client_incident_id_invalid"))
        val link = remoteIncidentLinkStore.read(clientIncidentId)
            ?: return incident.failed(IncidentRemoteCreationStatus.InvalidResponse("manual_sos_link_missing"))
        if (link.syncState == RemoteIncidentSyncState.Created) {
            val tripId = link.remoteTripId.normalized()
            val incidentId = link.remoteIncidentId.normalized()
            val dispatchId = link.remoteAlertDispatchId.normalized()
            if (tripId != null && incidentId != null && dispatchId != null) {
                return incident.copy(
                    remoteTripId = tripId,
                    remoteIncidentId = incidentId,
                    remoteCreationStatus = IncidentRemoteCreationStatus.Success(incidentId)
                )
            }
        }
        val clientAlertRequestId = link.clientAlertRequestId.validUuid()
            ?: return incident.failed(IncidentRemoteCreationStatus.InvalidResponse("client_alert_request_id_invalid"))
        val detectedAtUtc = link.detectedAtUtc.validInstant()
            ?: return incident.failed(IncidentRemoteCreationStatus.InvalidResponse("detected_at_utc_invalid"))
        val remoteTripId = remoteTripSessionStore.remoteTripId.value.normalized()
            ?: when (val lookup = activeTripRemoteResolver.resolveActiveTrip()) {
                is ActiveTripLookupResult.Found -> lookup.remoteTripId
                ActiveTripLookupResult.NoActiveTrip -> return incident.failed(
                    IncidentRemoteCreationStatus.MissingRequiredData("active_remote_trip_missing")
                )
                is ActiveTripLookupResult.HttpError -> return incident.failed(
                    IncidentRemoteCreationStatus.HttpError(lookup.statusCode, "trip_lookup_failed")
                )
                is ActiveTripLookupResult.NetworkUnavailable -> return incident.failed(
                    IncidentRemoteCreationStatus.NetworkUnavailable(lookup.sanitizedMessage)
                )
                is ActiveTripLookupResult.Timeout -> return incident.failed(
                    IncidentRemoteCreationStatus.Timeout(lookup.sanitizedMessage)
                )
                is ActiveTripLookupResult.InvalidResponse -> return incident.failed(
                    IncidentRemoteCreationStatus.InvalidResponse(lookup.sanitizedMessage)
                )
            }
        val location = locationProvider.currentRealLocation()
            ?: return incident.copy(
                remoteTripId = remoteTripId,
                remoteCreationStatus = IncidentRemoteCreationStatus.MissingRequiredData("manual_sos_location_missing")
            )
        val pendingLink = link.copy(
            remoteTripId = remoteTripId,
            updatedAtEpochMillis = nowEpochMillis()
        )
        if (!remoteIncidentLinkStore.save(pendingLink)) {
            return incident.copy(remoteTripId = remoteTripId).failed(
                IncidentRemoteCreationStatus.InvalidResponse("manual_sos_link_persistence_failed")
            )
        }
        val request = ManualSosAlertRequestDto(
            tripId = remoteTripId,
            clientIncidentId = clientIncidentId,
            clientAlertRequestId = clientAlertRequestId,
            incidentType = MobileSosIncidentType.ManualSos.apiValue,
            severity = MobileSosSeverity.High.apiValue,
            detectedAtUtc = detectedAtUtc,
            latitude = location.latitude,
            longitude = location.longitude,
            priority = MobileSosPriority.High.apiValue,
            reason = MobileSosReason.ManualSos.apiValue,
            notes = NOTES
        )
        val token = when (val auth = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> auth.value.reveal()
            is AuthFailure -> return incident.copy(remoteTripId = remoteTripId).failed(auth.toRemoteStatus())
        }
        val first = remoteDataSource.createManualSosAlert("Bearer $token", request)
        val status = retryOnceAfterUnauthorized(first, request)
        return when (status) {
            is ManualSosAlertSubmissionStatus.Success -> {
                val durable = remoteIncidentLinkStore.save(
                    pendingLink.copy(
                        remoteIncidentId = status.remoteIncidentId,
                        remoteAlertDispatchId = status.remoteAlertDispatchId,
                        syncState = RemoteIncidentSyncState.Created,
                        updatedAtEpochMillis = nowEpochMillis()
                    )
                )
                if (!durable) {
                    incident.copy(remoteTripId = remoteTripId).failed(
                        IncidentRemoteCreationStatus.InvalidResponse("manual_sos_result_persistence_failed")
                    )
                } else {
                    incident.copy(
                        remoteTripId = remoteTripId,
                        remoteIncidentId = status.remoteIncidentId,
                        remoteCreationStatus = IncidentRemoteCreationStatus.Success(status.remoteIncidentId)
                    )
                }
            }
            else -> incident.copy(remoteTripId = remoteTripId).failed(status.toRemoteStatus())
        }
    }

    private suspend fun retryOnceAfterUnauthorized(
        first: ManualSosAlertSubmissionStatus,
        request: ManualSosAlertRequestDto
    ): ManualSosAlertSubmissionStatus {
        if (first !is ManualSosAlertSubmissionStatus.HttpError || first.statusCode != 401) return first
        if (authRepository.refreshSession() !is AuthResult.Success) return first
        val refreshedToken = when (val auth = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> auth.value.reveal()
            is AuthFailure -> return auth.toSubmissionStatus()
        }
        return remoteDataSource.createManualSosAlert("Bearer $refreshedToken", request)
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

    private fun LocalIncident.failed(status: IncidentRemoteCreationStatus): LocalIncident = copy(
        remoteCreationStatus = status
    )

    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun String?.validUuid(): String? = normalized()?.takeIf {
        runCatching { UUID.fromString(it).toString() == it.lowercase() }.getOrDefault(false)
    }

    private fun String?.validInstant(): String? = normalized()?.takeIf {
        runCatching { Instant.parse(it) }.isSuccess
    }

    private companion object {
        const val NOTES = "Alerta SOS desde Android"
    }
}
