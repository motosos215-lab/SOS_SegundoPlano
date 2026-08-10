package com.example.sos_segundoplano.data.remote.trip

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import com.example.sos_segundoplano.data.remote.auth.ApiErrorDto
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

sealed interface ActiveTripLookupResult {
    data class Found(val remoteTripId: String) : ActiveTripLookupResult
    data object NoActiveTrip : ActiveTripLookupResult
    data class HttpError(val statusCode: Int, val sanitizedMessage: String?) : ActiveTripLookupResult
    data class NetworkUnavailable(val sanitizedMessage: String?) : ActiveTripLookupResult
    data class Timeout(val sanitizedMessage: String?) : ActiveTripLookupResult
    data class InvalidResponse(val sanitizedMessage: String?) : ActiveTripLookupResult
}

interface TripRemoteDataSource {
    suspend fun activeTrip(authorization: String): ActiveTripLookupResult
}

class RetrofitTripRemoteDataSource(
    private val api: TripsApi,
    moshi: Moshi
) : TripRemoteDataSource {
    private val errorEnvelopeAdapter: JsonAdapter<ApiEnvelopeDto<Any>> = moshi.adapter(
        Types.newParameterizedType(ApiEnvelopeDto::class.java, Any::class.java)
    )

    override suspend fun activeTrip(authorization: String): ActiveTripLookupResult = executeSafely {
        val response = api.activeTrip(authorization)
        if (response.code() == 404) return@executeSafely ActiveTripLookupResult.NoActiveTrip
        if (!response.isSuccessful) return@executeSafely mapHttpFailure(response)
        val envelope = response.body()
            ?: return@executeSafely ActiveTripLookupResult.InvalidResponse("response_body_missing")
        if (!envelope.success) {
            return@executeSafely mapHandledFailure(response.code(), envelope.error)
        }
        val remoteTripId = envelope.data?.trip?.id?.trim().orEmpty()
            .ifBlank { envelope.data?.id?.trim().orEmpty() }
        if (remoteTripId.isBlank()) ActiveTripLookupResult.NoActiveTrip else ActiveTripLookupResult.Found(remoteTripId)
    }

    private suspend fun executeSafely(block: suspend () -> ActiveTripLookupResult): ActiveTripLookupResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: JsonDataException) {
        ActiveTripLookupResult.InvalidResponse("response_json_invalid")
    } catch (_: JsonEncodingException) {
        ActiveTripLookupResult.InvalidResponse("response_json_invalid")
    } catch (_: EOFException) {
        ActiveTripLookupResult.InvalidResponse("response_body_invalid")
    } catch (_: SocketTimeoutException) {
        ActiveTripLookupResult.Timeout("network_timeout")
    } catch (_: UnknownHostException) {
        ActiveTripLookupResult.NetworkUnavailable("network_unavailable")
    } catch (_: ConnectException) {
        ActiveTripLookupResult.NetworkUnavailable("network_unavailable")
    } catch (_: NoRouteToHostException) {
        ActiveTripLookupResult.NetworkUnavailable("network_unavailable")
    } catch (_: IOException) {
        ActiveTripLookupResult.NetworkUnavailable("network_unavailable")
    } catch (_: IllegalArgumentException) {
        ActiveTripLookupResult.InvalidResponse("response_invalid")
    }

    private fun mapHttpFailure(response: Response<*>): ActiveTripLookupResult.HttpError =
        mapHandledFailure(response.code(), parseError(response))

    private fun mapHandledFailure(status: Int, error: ApiErrorDto?): ActiveTripLookupResult.HttpError =
        ActiveTripLookupResult.HttpError(status, sanitize(error?.code ?: error?.message ?: "request_rejected"))

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
