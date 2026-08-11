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

sealed interface TripMutationResult {
    data class Success(val remoteTripId: String, val status: String) : TripMutationResult
    data class HttpError(val statusCode: Int, val sanitizedMessage: String?) : TripMutationResult
    data class NetworkUnavailable(val sanitizedMessage: String?) : TripMutationResult
    data class Timeout(val sanitizedMessage: String?) : TripMutationResult
    data class InvalidResponse(val sanitizedMessage: String?) : TripMutationResult
    data class MissingRequiredData(val sanitizedMessage: String?) : TripMutationResult
}

sealed interface TripStartResourceLookupResult<out T> {
    data class Success<T>(val value: T) : TripStartResourceLookupResult<T>
    data class HttpError(val statusCode: Int, val sanitizedMessage: String?) : TripStartResourceLookupResult<Nothing>
    data class NetworkUnavailable(val sanitizedMessage: String?) : TripStartResourceLookupResult<Nothing>
    data class Timeout(val sanitizedMessage: String?) : TripStartResourceLookupResult<Nothing>
    data class InvalidResponse(val sanitizedMessage: String?) : TripStartResourceLookupResult<Nothing>
}

interface TripRemoteDataSource {
    suspend fun activeTrip(authorization: String): ActiveTripLookupResult
    suspend fun vehicles(authorization: String): TripStartResourceLookupResult<List<VehicleResourceDto>> =
        TripStartResourceLookupResult.InvalidResponse("vehicles_not_supported")
    suspend fun devices(authorization: String): TripStartResourceLookupResult<List<DeviceResourceDto>> =
        TripStartResourceLookupResult.InvalidResponse("devices_not_supported")
    suspend fun startTrip(authorization: String, request: StartTripRequestDto): TripMutationResult =
        TripMutationResult.MissingRequiredData("start_trip_not_supported")
    suspend fun finishTrip(authorization: String, remoteTripId: String, request: FinishTripRequestDto): TripMutationResult =
        TripMutationResult.MissingRequiredData("finish_trip_not_supported")
}

class RetrofitTripRemoteDataSource(
    private val api: TripsApi,
    moshi: Moshi
) : TripRemoteDataSource {
    private val errorEnvelopeAdapter: JsonAdapter<ApiEnvelopeDto<Any>> = moshi.adapter(
        Types.newParameterizedType(ApiEnvelopeDto::class.java, Any::class.java)
    )

    override suspend fun vehicles(
        authorization: String
    ): TripStartResourceLookupResult<List<VehicleResourceDto>> = executeResourceLookupSafely {
        val response = api.vehicles(authorization)
        if (!response.isSuccessful) return@executeResourceLookupSafely mapResourceHttpFailure(response)
        val envelope = response.body()
            ?: return@executeResourceLookupSafely TripStartResourceLookupResult.InvalidResponse("response_body_missing")
        if (!envelope.success) {
            return@executeResourceLookupSafely mapResourceHandledFailure(response.code(), envelope.error)
        }
        val vehicles = envelope.data?.vehicles
            ?: return@executeResourceLookupSafely TripStartResourceLookupResult.InvalidResponse("vehicles_missing")
        TripStartResourceLookupResult.Success(vehicles)
    }

    override suspend fun devices(
        authorization: String
    ): TripStartResourceLookupResult<List<DeviceResourceDto>> = executeResourceLookupSafely {
        val response = api.devices(authorization)
        if (!response.isSuccessful) return@executeResourceLookupSafely mapResourceHttpFailure(response)
        val envelope = response.body()
            ?: return@executeResourceLookupSafely TripStartResourceLookupResult.InvalidResponse("response_body_missing")
        if (!envelope.success) {
            return@executeResourceLookupSafely mapResourceHandledFailure(response.code(), envelope.error)
        }
        val devices = envelope.data?.devices
            ?: return@executeResourceLookupSafely TripStartResourceLookupResult.InvalidResponse("devices_missing")
        TripStartResourceLookupResult.Success(devices)
    }

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

    override suspend fun startTrip(
        authorization: String,
        request: StartTripRequestDto
    ): TripMutationResult = executeMutationSafely {
        mapMutationResponse(api.startTrip(authorization, request), EXPECTED_ACTIVE_STATUS)
    }

    override suspend fun finishTrip(
        authorization: String,
        remoteTripId: String,
        request: FinishTripRequestDto
    ): TripMutationResult {
        val normalizedId = remoteTripId.trim().takeIf { it.isNotEmpty() }
            ?: return TripMutationResult.MissingRequiredData("remote_trip_id_missing")
        return executeMutationSafely {
            mapMutationResponse(api.finishTrip(authorization, normalizedId, request), EXPECTED_FINISHED_STATUS)
        }
    }

    private fun mapMutationResponse(
        response: Response<ApiEnvelopeDto<TripMutationDataDto>>,
        expectedStatus: String
    ): TripMutationResult {
        if (!response.isSuccessful) return mapMutationHttpFailure(response)
        val envelope = response.body()
            ?: return TripMutationResult.InvalidResponse("response_body_missing")
        if (!envelope.success) return mapMutationHandledFailure(response.code(), envelope.error)
        val trip = envelope.data?.trip
            ?: return TripMutationResult.InvalidResponse("trip_missing")
        val id = trip.id?.trim().orEmpty()
        val status = trip.status?.trim().orEmpty()
        if (id.isBlank()) return TripMutationResult.InvalidResponse("trip_id_missing")
        if (!status.equals(expectedStatus, ignoreCase = true)) {
            return TripMutationResult.InvalidResponse("trip_status_invalid")
        }
        return TripMutationResult.Success(id, expectedStatus)
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

    private suspend fun executeMutationSafely(block: suspend () -> TripMutationResult): TripMutationResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: JsonDataException) {
        TripMutationResult.InvalidResponse("response_json_invalid")
    } catch (_: JsonEncodingException) {
        TripMutationResult.InvalidResponse("response_json_invalid")
    } catch (_: EOFException) {
        TripMutationResult.InvalidResponse("response_body_invalid")
    } catch (_: SocketTimeoutException) {
        TripMutationResult.Timeout("network_timeout")
    } catch (_: UnknownHostException) {
        TripMutationResult.NetworkUnavailable("network_unavailable")
    } catch (_: ConnectException) {
        TripMutationResult.NetworkUnavailable("network_unavailable")
    } catch (_: NoRouteToHostException) {
        TripMutationResult.NetworkUnavailable("network_unavailable")
    } catch (_: IOException) {
        TripMutationResult.NetworkUnavailable("network_unavailable")
    } catch (_: IllegalArgumentException) {
        TripMutationResult.InvalidResponse("response_invalid")
    }

    private suspend fun <T> executeResourceLookupSafely(
        block: suspend () -> TripStartResourceLookupResult<T>
    ): TripStartResourceLookupResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: JsonDataException) {
        TripStartResourceLookupResult.InvalidResponse("response_json_invalid")
    } catch (_: JsonEncodingException) {
        TripStartResourceLookupResult.InvalidResponse("response_json_invalid")
    } catch (_: EOFException) {
        TripStartResourceLookupResult.InvalidResponse("response_body_invalid")
    } catch (_: SocketTimeoutException) {
        TripStartResourceLookupResult.Timeout("network_timeout")
    } catch (_: UnknownHostException) {
        TripStartResourceLookupResult.NetworkUnavailable("network_unavailable")
    } catch (_: ConnectException) {
        TripStartResourceLookupResult.NetworkUnavailable("network_unavailable")
    } catch (_: NoRouteToHostException) {
        TripStartResourceLookupResult.NetworkUnavailable("network_unavailable")
    } catch (_: IOException) {
        TripStartResourceLookupResult.NetworkUnavailable("network_unavailable")
    } catch (_: IllegalArgumentException) {
        TripStartResourceLookupResult.InvalidResponse("response_invalid")
    }

    private fun mapHttpFailure(response: Response<*>): ActiveTripLookupResult.HttpError =
        mapHandledFailure(response.code(), parseError(response))

    private fun mapMutationHttpFailure(response: Response<*>): TripMutationResult.HttpError =
        mapMutationHandledFailure(response.code(), parseError(response))

    private fun mapMutationHandledFailure(status: Int, error: ApiErrorDto?): TripMutationResult.HttpError =
        TripMutationResult.HttpError(status, sanitize(error?.code ?: error?.message ?: "request_rejected"))

    private fun mapResourceHttpFailure(response: Response<*>): TripStartResourceLookupResult.HttpError =
        mapResourceHandledFailure(response.code(), parseError(response))

    private fun mapResourceHandledFailure(
        status: Int,
        error: ApiErrorDto?
    ): TripStartResourceLookupResult.HttpError =
        TripStartResourceLookupResult.HttpError(
            status,
            sanitize(error?.code ?: error?.message ?: "request_rejected")
        )

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
        const val EXPECTED_ACTIVE_STATUS = "Active"
        const val EXPECTED_FINISHED_STATUS = "Finished"
    }
}
