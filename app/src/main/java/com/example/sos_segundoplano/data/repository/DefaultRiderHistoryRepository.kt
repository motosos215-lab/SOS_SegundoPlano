package com.example.sos_segundoplano.data.repository

import com.example.sos_segundoplano.data.remote.incident.IncidentsApi
import com.example.sos_segundoplano.data.remote.trip.TripsApi
import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.history.RiderHistoryRepository
import com.example.sos_segundoplano.domain.history.RiderHistoryResult
import com.example.sos_segundoplano.domain.history.RiderIncidentHistoryItem
import com.example.sos_segundoplano.domain.history.RiderTripHistoryItem
import com.example.sos_segundoplano.domain.history.RiderTripHistoryLocation
import com.example.sos_segundoplano.domain.history.RiderTripRoutePoint
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.features.history.HistoryDiagnostics
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

class DefaultRiderHistoryRepository(
    private val authRepository: AuthRepository,
    private val tripsApi: TripsApi,
    private val incidentsApi: IncidentsApi
) : RiderHistoryRepository {
    override suspend fun trips(): RiderHistoryResult<List<RiderTripHistoryItem>> = call { token ->
        tripsApi.listTrips(token).let { response ->
            val envelope = response.body()
            if (!response.isSuccessful || envelope?.success != true) throw HistoryRequestException("request_failed")
            envelope.data?.trips.orEmpty().map {
                RiderTripHistoryItem(it.status, it.startedAtUtc ?: it.clientStartedAtUtc, it.finishedAtUtc ?: it.clientFinishedAtUtc,
                    it.startLocation?.let { location -> RiderTripHistoryLocation(location.latitude, location.longitude, location.accuracyMeters, location.provider, location.recordedAtUtc) },
                    it.endLocation?.let { location -> RiderTripHistoryLocation(location.latitude, location.longitude, location.accuracyMeters, location.provider, location.recordedAtUtc) },
                    id = it.id)
            }
        }
    }

    override suspend fun route(tripId: String, preview: Boolean): RiderHistoryResult<List<RiderTripRoutePoint>> = call { token ->
        val normalizedTripId = tripId.trim().takeIf { it.isNotEmpty() } ?: throw HistoryRequestException("trip_id_missing")
        tripsApi.route(token, normalizedTripId, if (preview) "preview" else null).let { response ->
            val envelope = response.body()
            if (!response.isSuccessful || envelope?.success != true) throw HistoryRequestException("route_request_failed")
            envelope.data?.resolvedPoints().orEmpty()
                .asSequence()
                .filter { it.latitude.isFinite() && it.longitude.isFinite() }
                .filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
                .mapIndexed { index, point ->
                    RiderTripRoutePoint(
                        clientRoutePointId = point.clientRoutePointId,
                        sequence = point.sequence ?: (index + 1L),
                        recordedAtUtc = point.recordedAtUtc,
                        latitude = point.latitude,
                        longitude = point.longitude,
                        accuracyMeters = point.accuracyMeters,
                        speedMetersPerSecond = point.speedMetersPerSecond,
                        bearingDegrees = point.bearingDegrees
                    )
                }
                .sortedBy { it.sequence }
                .toList()
        }
    }

    override suspend fun incidents(): RiderHistoryResult<List<RiderIncidentHistoryItem>> = call(isIncidentHistory = true) { token ->
        incidentsApi.listIncidents(token).let { response ->
            val envelope = response.body()
            if (!response.isSuccessful || envelope?.success != true) {
                HistoryDiagnostics.debug(HistoryDiagnostics.apiFailure("rider_history", response.code(), null))
                throw HistoryRequestException("request_failed")
            }
            val page = envelope.data
            val received = page?.incidents.orEmpty()
            HistoryDiagnostics.debug(
                HistoryDiagnostics.riderApiSuccess(
                    pageNumber = page?.pageNumber,
                    pageSize = page?.pageSize,
                    totalCount = page?.totalCount,
                    receivedCount = received.size,
                    hasCountdownTimeout = received.any { it.cause == "CountdownTimeout" },
                    hasManualSos = received.any { it.cause == "ManualSos" },
                    hasOpen = received.any { it.status == "Open" }
                )
            )
            val mapped = received.map {
                RiderIncidentHistoryItem(it.cause, it.riskLevel, it.status, it.occurredAtUtc ?: it.createdAtUtc)
            }
            HistoryDiagnostics.debug(
                HistoryDiagnostics.riderMapping(
                    receivedCount = received.size,
                    mappedCount = mapped.size,
                    hasCountdownTimeout = mapped.any { it.cause == "CountdownTimeout" }
                )
            )
            mapped
        }
    }

    private suspend fun <T> call(
        isIncidentHistory: Boolean = false,
        request: suspend (String) -> T
    ): RiderHistoryResult<T> {
        val session = authRepository.observeSession().value
        val role = when (session) { is SessionState.Authenticated -> session.user.role; is SessionState.Refreshing -> session.user.role; else -> null }
        if (role != UserRole.Rider) {
            if (isIncidentHistory) HistoryDiagnostics.debug(HistoryDiagnostics.apiFailure("rider_history", null, "role_not_allowed"))
            return RiderHistoryResult.Failure("role_not_allowed")
        }
        val token = when (val auth = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> auth.value.reveal()
            is AuthFailure -> {
                if (isIncidentHistory) HistoryDiagnostics.debug(HistoryDiagnostics.apiFailure("rider_history", null, "access_token_unavailable"))
                return RiderHistoryResult.Failure("access_token_unavailable")
            }
        }
        return try {
            RiderHistoryResult.Success(request("Bearer $token"))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: HistoryRequestException) { RiderHistoryResult.Failure(failure.message)
        } catch (_: IOException) {
            if (isIncidentHistory) HistoryDiagnostics.debug(HistoryDiagnostics.apiFailure("rider_history", null, "network_unavailable"))
            RiderHistoryResult.Failure("network_unavailable")
        } catch (_: HttpException) {
            if (isIncidentHistory) HistoryDiagnostics.debug(HistoryDiagnostics.apiFailure("rider_history", null, "request_failed"))
            RiderHistoryResult.Failure("request_failed")
        } catch (_: JsonDataException) {
            if (isIncidentHistory) HistoryDiagnostics.debug(HistoryDiagnostics.apiFailure("rider_history", null, "response_contract_incomplete"))
            RiderHistoryResult.Failure("response_contract_incomplete")
        } catch (_: IllegalArgumentException) {
            if (isIncidentHistory) HistoryDiagnostics.debug(HistoryDiagnostics.apiFailure("rider_history", null, "response_contract_incomplete"))
            RiderHistoryResult.Failure("response_contract_incomplete")
        } catch (_: Exception) {
            if (isIncidentHistory) HistoryDiagnostics.debug(HistoryDiagnostics.apiFailure("rider_history", null, "history_unavailable"))
            RiderHistoryResult.Failure("history_unavailable")
        }
    }

    private class HistoryRequestException(message: String) : RuntimeException(message)
}
