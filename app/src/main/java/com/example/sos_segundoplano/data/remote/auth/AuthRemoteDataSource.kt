package com.example.sos_segundoplano.data.remote.auth

import com.example.sos_segundoplano.domain.auth.ActiveMobileSessionInfo
import com.example.sos_segundoplano.domain.auth.ActiveSessionExists
import com.example.sos_segundoplano.domain.auth.ActiveTripTakeoverInfo
import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.InvalidCredentials
import com.example.sos_segundoplano.domain.auth.InvalidResponse
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.RateLimited
import com.example.sos_segundoplano.domain.auth.ServerFailure
import com.example.sos_segundoplano.domain.auth.SessionRevoked
import com.example.sos_segundoplano.domain.auth.SessionTakeoverChallenge
import com.example.sos_segundoplano.domain.auth.SessionTakeoverFailure
import com.example.sos_segundoplano.domain.auth.Timeout
import com.example.sos_segundoplano.domain.auth.Unauthorized
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

interface AuthRemoteDataSource {
    suspend fun login(request: LoginRequestDto): AuthResult<LoginDataDto>
    suspend fun takeover(request: SessionTakeoverRequestDto): AuthResult<LoginDataDto> =
        InvalidResponse(sanitizedMessage = "session_takeover_not_supported")
    suspend fun currentUser(accessToken: String): AuthResult<AuthUserDto> =
        InvalidResponse(sanitizedMessage = "current_user_not_supported")
    suspend fun refresh(request: RefreshTokenRequestDto): AuthResult<RefreshDataDto>
    suspend fun logout(request: LogoutRequestDto): AuthResult<Unit>
    suspend fun logout(accessToken: String, request: LogoutRequestDto): AuthResult<Unit> = logout(request)
}

class RetrofitAuthRemoteDataSource(
    private val api: AuthApi,
    moshi: Moshi
) : AuthRemoteDataSource {
    private val errorEnvelopeAdapter: JsonAdapter<ApiEnvelopeDto<Any>> = moshi.adapter(
        Types.newParameterizedType(ApiEnvelopeDto::class.java, Any::class.java)
    )
    private val activeSessionEnvelopeAdapter: JsonAdapter<ApiEnvelopeDto<ActiveSessionConflictDataDto>> = moshi.adapter(
        Types.newParameterizedType(ApiEnvelopeDto::class.java, ActiveSessionConflictDataDto::class.java)
    )

    override suspend fun login(request: LoginRequestDto): AuthResult<LoginDataDto> = executeSafely {
        val response = api.login(request)
        if (!response.isSuccessful) {
            return@executeSafely mapLoginFailure(response, request.email, request.rememberMe)
        }
        mapEnvelope(response)
    }

    override suspend fun takeover(request: SessionTakeoverRequestDto): AuthResult<LoginDataDto> = executeSafely {
        val response = api.takeover(request)
        if (!response.isSuccessful) return@executeSafely mapTakeoverFailure(response)
        mapEnvelope(response)
    }

    override suspend fun currentUser(accessToken: String): AuthResult<AuthUserDto> = executeSafely {
        val normalized = accessToken.trim()
        if (normalized.isEmpty()) return@executeSafely InvalidResponse(sanitizedMessage = "access_token_missing")
        val response = api.currentUser("Bearer $normalized")
        if (!response.isSuccessful) return@executeSafely mapHttpFailure(response)
        val envelope = response.body()
            ?: return@executeSafely InvalidResponse(response.code(), sanitizedMessage = "response_body_missing")
        if (!envelope.success) {
            return@executeSafely mapHandledFailure(response.code(), envelope.error, response.headers()[RETRY_AFTER])
        }
        envelope.data?.user?.let { AuthResult.Success(it) }
            ?: InvalidResponse(response.code(), sanitizedMessage = "auth_user_missing")
    }

    override suspend fun refresh(request: RefreshTokenRequestDto): AuthResult<RefreshDataDto> =
        executeEnvelope { api.refresh(request) }

    override suspend fun logout(request: LogoutRequestDto): AuthResult<Unit> =
        InvalidResponse(sanitizedMessage = "authenticated_logout_required")

    override suspend fun logout(accessToken: String, request: LogoutRequestDto): AuthResult<Unit> = executeSafely {
        val response = api.logout("Bearer ${accessToken.trim()}", request)
        if (response.isSuccessful) AuthResult.Success(Unit) else mapHttpFailure(response)
    }

    private fun <T> mapEnvelope(response: Response<ApiEnvelopeDto<T>>): AuthResult<T> {
        val envelope = response.body()
            ?: return InvalidResponse(response.code(), sanitizedMessage = "response_body_missing")
        if (!envelope.success) {
            return mapHandledFailure(response.code(), envelope.error, response.headers()[RETRY_AFTER])
        }
        return envelope.data?.let { AuthResult.Success(it) }
            ?: InvalidResponse(response.code(), sanitizedMessage = "response_data_missing")
    }

    private suspend fun <T> executeEnvelope(
        request: suspend () -> Response<ApiEnvelopeDto<T>>
    ): AuthResult<T> = executeSafely {
        val response = request()
        if (!response.isSuccessful) return@executeSafely mapHttpFailure(response)
        mapEnvelope(response)
    }

    private fun mapLoginFailure(
        response: Response<*>,
        email: String,
        rememberMe: Boolean
    ): AuthFailure {
        if (response.code() != 409) return mapHttpFailure(response)
        val envelope = parseActiveSessionEnvelope(response)
        val code = sanitize(envelope?.error?.code)
        if (!code.equals("active_session_exists", ignoreCase = true)) {
            return mapHandledFailure(response.code(), envelope?.error, response.headers()[RETRY_AFTER])
        }
        val data = envelope?.data ?: return InvalidResponse(
            response.code(), code, sanitize(envelope?.error?.message) ?: "active_session_data_missing"
        )
        val token = data.takeoverToken?.trim().orEmpty()
        if (token.isEmpty()) return InvalidResponse(response.code(), code, "takeover_token_missing")
        val activeTripId = data.activeTrip?.id?.trim().orEmpty()
        val hasActiveTrip = data.hasActiveTrip == true
        if (hasActiveTrip && activeTripId.isEmpty()) {
            return InvalidResponse(response.code(), code, "active_trip_missing")
        }
        return ActiveSessionExists(
            SessionTakeoverChallenge(
                activeSession = data.activeSession?.let {
                    ActiveMobileSessionInfo(
                        deviceName = sanitize(it.deviceName),
                        platform = sanitize(it.platform),
                        lastSeenAtUtc = sanitize(it.lastSeenAtUtc)
                    )
                },
                takeoverToken = token,
                takeoverExpiresAtUtc = sanitize(data.takeoverExpiresAtUtc),
                hasActiveTrip = hasActiveTrip,
                activeTrip = data.activeTrip?.takeIf { activeTripId.isNotEmpty() }?.let {
                    ActiveTripTakeoverInfo(
                        id = activeTripId,
                        startedAtUtc = sanitize(it.startedAtUtc),
                        mobileDeviceId = sanitize(it.mobileDeviceId)
                    )
                },
                accountEmail = email.trim(),
                rememberMe = rememberMe
            )
        )
    }

    private fun mapTakeoverFailure(response: Response<*>): AuthFailure {
        val error = parseError(response)
        val code = sanitize(error?.code)
        val message = sanitize(error?.message)
        return when {
            response.code() == 401 && code.equals("session_revoked", ignoreCase = true) -> SessionRevoked
            response.code() == 429 -> RateLimited(sanitize(response.headers()[RETRY_AFTER]), response.code(), code, message)
            response.code() >= 500 -> ServerFailure(response.code(), code, message)
            code != null && code in TAKEOVER_ERROR_CODES -> SessionTakeoverFailure(code, message)
            response.code() == 401 -> Unauthorized(response.code(), code, message)
            else -> InvalidResponse(response.code(), code, message ?: "takeover_rejected")
        }
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
            status == 401 && code.equals("session_revoked", ignoreCase = true) -> SessionRevoked
            status == 401 && code.equals("invalid_credentials", ignoreCase = true) -> InvalidCredentials(status, code, message)
            status == 401 -> Unauthorized(status, code, message)
            status >= 500 -> ServerFailure(status, code, message)
            code.equals("invalid_credentials", ignoreCase = true) -> InvalidCredentials(status, code, message)
            code.equals("unauthorized", ignoreCase = true) -> Unauthorized(status, code, message)
            else -> InvalidResponse(status, code, message ?: "request_rejected")
        }
    }

    private fun parseActiveSessionEnvelope(response: Response<*>): ApiEnvelopeDto<ActiveSessionConflictDataDto>? =
        response.errorBody()?.use { body ->
            try { activeSessionEnvelopeAdapter.fromJson(body.source()) }
            catch (_: IOException) { null }
            catch (_: JsonDataException) { null }
        }

    private fun parseError(response: Response<*>): ApiErrorDto? = response.errorBody()?.use { body ->
        try { errorEnvelopeAdapter.fromJson(body.source())?.error }
        catch (_: IOException) { null }
        catch (_: JsonDataException) { null }
    }

    private fun sanitize(value: String?): String? = value
        ?.replace(Regex("[\r\n\t]"), " ")
        ?.take(MAX_SANITIZED_LENGTH)

    private companion object {
        const val RETRY_AFTER = "Retry-After"
        const val MAX_SANITIZED_LENGTH = 160
        val TAKEOVER_ERROR_CODES = setOf(
            "active_trip_transfer_required",
            "takeover_token_invalid",
            "takeover_token_expired",
            "takeover_token_already_used",
            "session_revoked",
            "device_not_available",
            "active_trip_not_available"
        )
    }
}
