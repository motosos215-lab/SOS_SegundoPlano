package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import com.example.sos_segundoplano.data.remote.auth.ApiErrorDto
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

interface IncidentRemoteDataSource {
    suspend fun createIncident(
        authorization: String,
        request: CreateIncidentRequestDto
    ): IncidentRemoteCreationStatus
}

class RetrofitIncidentRemoteDataSource(
    private val api: IncidentsApi,
    moshi: Moshi
) : IncidentRemoteDataSource {
    private val errorEnvelopeAdapter: JsonAdapter<ApiEnvelopeDto<Any>> = moshi.adapter(
        Types.newParameterizedType(ApiEnvelopeDto::class.java, Any::class.java)
    )

    override suspend fun createIncident(
        authorization: String,
        request: CreateIncidentRequestDto
    ): IncidentRemoteCreationStatus = executeSafely {
        val response = api.createIncident(authorization, request)
        if (!response.isSuccessful) return@executeSafely mapHttpFailure(response)
        val envelope = response.body()
            ?: return@executeSafely IncidentRemoteCreationStatus.InvalidResponse("response_body_missing")
        if (!envelope.success) {
            return@executeSafely mapHandledFailure(response.code(), envelope.error)
        }
        val incidentId = envelope.data?.incident?.id?.trim()
            ?: envelope.data?.incidentId?.trim()
            ?: return@executeSafely IncidentRemoteCreationStatus.InvalidResponse("incident_id_missing")
        if (incidentId.isBlank()) {
            IncidentRemoteCreationStatus.InvalidResponse("incident_id_missing")
        } else {
            IncidentRemoteCreationStatus.Success(incidentId)
        }
    }

    private suspend fun executeSafely(
        block: suspend () -> IncidentRemoteCreationStatus
    ): IncidentRemoteCreationStatus = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: JsonDataException) {
        IncidentRemoteCreationStatus.InvalidResponse("response_json_invalid")
    } catch (_: JsonEncodingException) {
        IncidentRemoteCreationStatus.InvalidResponse("response_json_invalid")
    } catch (_: EOFException) {
        IncidentRemoteCreationStatus.InvalidResponse("response_body_invalid")
    } catch (_: SocketTimeoutException) {
        IncidentRemoteCreationStatus.Timeout("network_timeout")
    } catch (_: UnknownHostException) {
        IncidentRemoteCreationStatus.NetworkUnavailable("network_unavailable")
    } catch (_: ConnectException) {
        IncidentRemoteCreationStatus.NetworkUnavailable("network_unavailable")
    } catch (_: NoRouteToHostException) {
        IncidentRemoteCreationStatus.NetworkUnavailable("network_unavailable")
    } catch (_: IOException) {
        IncidentRemoteCreationStatus.NetworkUnavailable("network_unavailable")
    } catch (_: IllegalArgumentException) {
        IncidentRemoteCreationStatus.InvalidResponse("response_invalid")
    }

    private fun mapHttpFailure(response: Response<*>): IncidentRemoteCreationStatus.HttpError =
        mapHandledFailure(response.code(), parseError(response))

    private fun mapHandledFailure(status: Int, error: ApiErrorDto?): IncidentRemoteCreationStatus.HttpError =
        IncidentRemoteCreationStatus.HttpError(status, sanitize(error?.code ?: error?.message ?: "request_rejected"))

    private fun parseError(response: Response<*>): ApiErrorDto? = response.errorBody()?.use { body ->
        try {
            errorEnvelopeAdapter.fromJson(body.source())?.error
        } catch (_: IOException) {
            null
        } catch (_: JsonDataException) {
            null
        }
    }

    private fun sanitize(value: String?): String? = value
        ?.replace(Regex("[\\r\\n\\t]"), " ")
        ?.take(MAX_SANITIZED_LENGTH)

    private companion object {
        const val MAX_SANITIZED_LENGTH = 160
    }
}
