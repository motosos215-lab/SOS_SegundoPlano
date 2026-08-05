package com.example.sos_segundoplano.data.remote.auth

import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.InvalidCredentials
import com.example.sos_segundoplano.domain.auth.InvalidResponse
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.RateLimited
import com.example.sos_segundoplano.domain.auth.ServerFailure
import com.example.sos_segundoplano.domain.auth.Timeout
import com.example.sos_segundoplano.domain.auth.Unauthorized
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.JsonAdapter
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

interface AuthRemoteDataSource {
    suspend fun login(request: LoginRequestDto): AuthResult<LoginDataDto>
    suspend fun refresh(request: RefreshTokenRequestDto): AuthResult<RefreshDataDto>
    suspend fun logout(request: LogoutRequestDto): AuthResult<Unit>
}

class RetrofitAuthRemoteDataSource(
    private val api: AuthApi,
    moshi: Moshi
) : AuthRemoteDataSource {
    private val errorEnvelopeAdapter: JsonAdapter<ApiEnvelopeDto<Any>> = moshi.adapter(
        Types.newParameterizedType(ApiEnvelopeDto::class.java, Any::class.java)
    )

    override suspend fun login(request: LoginRequestDto): AuthResult<LoginDataDto> =
        executeEnvelope { api.login(request) }

    override suspend fun refresh(request: RefreshTokenRequestDto): AuthResult<RefreshDataDto> =
        executeEnvelope { api.refresh(request) }

    override suspend fun logout(request: LogoutRequestDto): AuthResult<Unit> = executeSafely {
        val response = api.logout(request)
        if (response.code() == 204) {
            AuthResult.Success(Unit)
        } else {
            mapHttpFailure(response)
        }
    }

    private suspend fun <T> executeEnvelope(
        request: suspend () -> Response<ApiEnvelopeDto<T>>
    ): AuthResult<T> = executeSafely {
        val response = request()
        if (!response.isSuccessful) return@executeSafely mapHttpFailure(response)
        val envelope = response.body()
            ?: return@executeSafely InvalidResponse(response.code(), sanitizedMessage = "response_body_missing")
        if (!envelope.success) {
            return@executeSafely mapHandledFailure(response.code(), envelope.error, response.headers()[RETRY_AFTER])
        }
        envelope.data?.let { AuthResult.Success(it) }
            ?: InvalidResponse(response.code(), sanitizedMessage = "response_data_missing")
    }

    private suspend fun <T> executeSafely(block: suspend () -> AuthResult<T>): AuthResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: JsonDataException) {
        InvalidResponse(sanitizedMessage = "response_json_invalid")
    } catch (_: JsonEncodingException) {
        InvalidResponse(sanitizedMessage = "response_json_invalid")
    } catch (_: EOFException) {
        InvalidResponse(sanitizedMessage = "response_body_invalid")
    } catch (_: SocketTimeoutException) {
        Timeout("network_timeout")
    } catch (_: UnknownHostException) {
        NetworkUnavailable("network_unavailable")
    } catch (_: ConnectException) {
        NetworkUnavailable("network_unavailable")
    } catch (_: NoRouteToHostException) {
        NetworkUnavailable("network_unavailable")
    } catch (_: IOException) {
        NetworkUnavailable("network_unavailable")
    } catch (_: IllegalArgumentException) {
        InvalidResponse(sanitizedMessage = "response_invalid")
    }

    private fun mapHttpFailure(response: Response<*>): AuthFailure {
        val error = parseError(response)
        return mapHandledFailure(response.code(), error, response.headers()[RETRY_AFTER])
    }

    private fun mapHandledFailure(status: Int, error: ApiErrorDto?, retryAfter: String?): AuthFailure {
        val code = sanitize(error?.code)
        val message = sanitize(error?.message)
        return when {
            status == 429 -> RateLimited(sanitize(retryAfter), status, code, message)
            status == 401 && code.equals("invalid_credentials", ignoreCase = true) ->
                InvalidCredentials(status, code, message)
            status == 401 -> Unauthorized(status, code, message)
            status >= 500 -> ServerFailure(status, code, message)
            code.equals("invalid_credentials", ignoreCase = true) ->
                InvalidCredentials(status, code, message)
            code.equals("unauthorized", ignoreCase = true) -> Unauthorized(status, code, message)
            else -> InvalidResponse(status, code, message ?: "request_rejected")
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

    private fun sanitize(value: String?): String? = value
        ?.replace(Regex("[\\r\\n\\t]"), " ")
        ?.take(MAX_SANITIZED_LENGTH)

    private companion object {
        const val RETRY_AFTER = "Retry-After"
        const val MAX_SANITIZED_LENGTH = 160
    }
}
