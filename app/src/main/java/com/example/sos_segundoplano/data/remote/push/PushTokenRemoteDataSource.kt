package com.example.sos_segundoplano.data.remote.push

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

sealed interface PushTokenRemoteResult {
    data class Registered(val token: PushNotificationTokenDto) : PushTokenRemoteResult
    data class Listed(val data: PushTokenListDataDto) : PushTokenRemoteResult
    data class StatusLoaded(val data: PushTokenStatusDataDto) : PushTokenRemoteResult
    data class Revoked(val token: PushNotificationTokenDto) : PushTokenRemoteResult
    data class NotFound(val errorCode: String?) : PushTokenRemoteResult
    data class HttpFailure(val status: Int, val errorCode: String? = null) : PushTokenRemoteResult
    data class InvalidResponse(val errorCode: String? = null) : PushTokenRemoteResult
    data object NetworkFailure : PushTokenRemoteResult
    data object Timeout : PushTokenRemoteResult
}

interface PushTokenRemoteDataSource {
    suspend fun register(authorization: String, request: RegisterPushTokenRequestDto): PushTokenRemoteResult
    suspend fun listActiveAndroidFcm(authorization: String): PushTokenRemoteResult
    suspend fun status(authorization: String): PushTokenRemoteResult
    suspend fun revoke(authorization: String, registrationId: String): PushTokenRemoteResult
}

class RetrofitPushTokenRemoteDataSource(
    private val api: PushNotificationTokensApi,
    moshi: Moshi
) : PushTokenRemoteDataSource {
    private val errorEnvelopeAdapter: JsonAdapter<ApiEnvelopeDto<Any>> = moshi.adapter(
        Types.newParameterizedType(ApiEnvelopeDto::class.java, Any::class.java)
    )

    override suspend fun register(
        authorization: String,
        request: RegisterPushTokenRequestDto
    ): PushTokenRemoteResult = executeEnvelope(
        request = { api.register(authorization, request) },
        success = { PushTokenRemoteResult.Registered(it.pushNotificationToken) }
    )

    override suspend fun listActiveAndroidFcm(authorization: String): PushTokenRemoteResult = executeEnvelope(
        request = { api.list(authorization) },
        success = { PushTokenRemoteResult.Listed(it) }
    )

    override suspend fun status(authorization: String): PushTokenRemoteResult = executeEnvelope(
        request = { api.status(authorization) },
        success = { PushTokenRemoteResult.StatusLoaded(it) }
    )

    override suspend fun revoke(
        authorization: String,
        registrationId: String
    ): PushTokenRemoteResult = executeEnvelope(
        request = { api.revoke(authorization, registrationId) },
        success = { PushTokenRemoteResult.Revoked(it) }
    )

    private suspend fun <T> executeEnvelope(
        request: suspend () -> Response<ApiEnvelopeDto<T>>,
        success: (T) -> PushTokenRemoteResult
    ): PushTokenRemoteResult = executeSafely {
        val response = request()
        if (!response.isSuccessful) return@executeSafely mapHttpFailure(response)
        val envelope = response.body() ?: return@executeSafely PushTokenRemoteResult.InvalidResponse()
        if (!envelope.success) {
            return@executeSafely PushTokenRemoteResult.HttpFailure(response.code(), envelope.error?.code)
        }
        envelope.data?.let(success) ?: PushTokenRemoteResult.InvalidResponse(envelope.error?.code)
    }

    private suspend fun executeSafely(block: suspend () -> PushTokenRemoteResult): PushTokenRemoteResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: JsonDataException) {
        PushTokenRemoteResult.InvalidResponse()
    } catch (_: JsonEncodingException) {
        PushTokenRemoteResult.InvalidResponse()
    } catch (_: EOFException) {
        PushTokenRemoteResult.InvalidResponse()
    } catch (_: SocketTimeoutException) {
        PushTokenRemoteResult.Timeout
    } catch (_: UnknownHostException) {
        PushTokenRemoteResult.NetworkFailure
    } catch (_: ConnectException) {
        PushTokenRemoteResult.NetworkFailure
    } catch (_: NoRouteToHostException) {
        PushTokenRemoteResult.NetworkFailure
    } catch (_: IOException) {
        PushTokenRemoteResult.NetworkFailure
    } catch (_: IllegalArgumentException) {
        PushTokenRemoteResult.InvalidResponse()
    }

    private fun mapHttpFailure(response: Response<*>): PushTokenRemoteResult {
        val errorCode = parseError(response)?.code
        return if (response.code() == 404) {
            PushTokenRemoteResult.NotFound(errorCode)
        } else {
            PushTokenRemoteResult.HttpFailure(response.code(), errorCode)
        }
    }

    private fun parseError(response: Response<*>): ApiErrorDto? = response.errorBody()?.use { body ->
        try {
            errorEnvelopeAdapter.fromJson(body.source())?.error
        } catch (_: IOException) {
            null
        } catch (_: JsonDataException) {
            null
        }
    }
}
