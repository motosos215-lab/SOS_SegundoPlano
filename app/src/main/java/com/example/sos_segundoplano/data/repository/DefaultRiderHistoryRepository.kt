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
import com.example.sos_segundoplano.domain.repository.AuthRepository
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
                    it.endLocation?.let { location -> RiderTripHistoryLocation(location.latitude, location.longitude, location.accuracyMeters, location.provider, location.recordedAtUtc) })
            }
        }
    }

    override suspend fun incidents(): RiderHistoryResult<List<RiderIncidentHistoryItem>> = call { token ->
        incidentsApi.listIncidents(token).let { response ->
            val envelope = response.body()
            if (!response.isSuccessful || envelope?.success != true) throw HistoryRequestException("request_failed")
            envelope.data?.incidents.orEmpty().map {
                RiderIncidentHistoryItem(it.cause, it.riskLevel, it.status, it.occurredAtUtc ?: it.createdAtUtc)
            }
        }
    }

    private suspend fun <T> call(request: suspend (String) -> T): RiderHistoryResult<T> {
        val session = authRepository.observeSession().value
        val role = when (session) { is SessionState.Authenticated -> session.user.role; is SessionState.Refreshing -> session.user.role; else -> null }
        if (role != UserRole.Rider) return RiderHistoryResult.Failure("role_not_allowed")
        val token = when (val auth = authRepository.ensureValidAccessToken()) { is AuthResult.Success -> auth.value.reveal(); is AuthFailure -> return RiderHistoryResult.Failure("access_token_unavailable") }
        return try {
            RiderHistoryResult.Success(request("Bearer $token"))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: HistoryRequestException) { RiderHistoryResult.Failure(failure.message)
        } catch (_: IOException) { RiderHistoryResult.Failure("network_unavailable")
        } catch (_: HttpException) { RiderHistoryResult.Failure("request_failed")
        } catch (_: JsonDataException) { RiderHistoryResult.Failure("response_contract_incomplete")
        } catch (_: IllegalArgumentException) { RiderHistoryResult.Failure("response_contract_incomplete") }
        catch (_: Exception) { RiderHistoryResult.Failure("history_unavailable") }
    }

    private class HistoryRequestException(message: String) : RuntimeException(message)
}
