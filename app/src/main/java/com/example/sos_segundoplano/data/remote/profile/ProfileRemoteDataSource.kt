package com.example.sos_segundoplano.data.remote.profile

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import com.example.sos_segundoplano.data.remote.auth.ApiErrorDto
import com.example.sos_segundoplano.domain.profile.ProfileAccountUnavailable
import com.example.sos_segundoplano.domain.profile.ProfileFailure
import com.example.sos_segundoplano.domain.profile.ProfileInvalidResponse
import com.example.sos_segundoplano.domain.profile.ProfileNetworkUnavailable
import com.example.sos_segundoplano.domain.profile.ProfileRateLimited
import com.example.sos_segundoplano.domain.profile.ProfileResult
import com.example.sos_segundoplano.domain.profile.ProfileServerFailure
import com.example.sos_segundoplano.domain.profile.ProfileTimeout
import com.example.sos_segundoplano.domain.profile.ProfileUnauthorized
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

interface ProfileRemoteDataSource {
    suspend fun me(authorization: String): ProfileResult<ProfileUserDto>
}

class RetrofitProfileRemoteDataSource(
    private val api: UsersApi,
    moshi: Moshi
) : ProfileRemoteDataSource {
    private val errorEnvelopeAdapter: JsonAdapter<ApiEnvelopeDto<Any>> = moshi.adapter(
        Types.newParameterizedType(ApiEnvelopeDto::class.java, Any::class.java)
    )

    override suspend fun me(authorization: String): ProfileResult<ProfileUserDto> = executeSafely {
        val response = api.me(authorization)
        if (!response.isSuccessful) return@executeSafely mapHttpFailure(response)
        val envelope = response.body() ?: return@executeSafely ProfileInvalidResponse
        if (!envelope.success) {
            return@executeSafely mapHandledFailure(response.code(), envelope.error)
        }
        envelope.data?.user?.let { ProfileResult.Success(it) } ?: ProfileInvalidResponse
    }

    private suspend fun executeSafely(
        block: suspend () -> ProfileResult<ProfileUserDto>
    ): ProfileResult<ProfileUserDto> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: JsonDataException) {
        ProfileInvalidResponse
    } catch (_: JsonEncodingException) {
        ProfileInvalidResponse
    } catch (_: EOFException) {
        ProfileInvalidResponse
    } catch (_: SocketTimeoutException) {
        ProfileTimeout
    } catch (_: UnknownHostException) {
        ProfileNetworkUnavailable
    } catch (_: ConnectException) {
        ProfileNetworkUnavailable
    } catch (_: NoRouteToHostException) {
        ProfileNetworkUnavailable
    } catch (_: IOException) {
        ProfileNetworkUnavailable
    } catch (_: IllegalArgumentException) {
        ProfileInvalidResponse
    }

    private fun mapHttpFailure(response: Response<*>): ProfileFailure =
        mapHandledFailure(response.code(), parseError(response))

    private fun mapHandledFailure(status: Int, error: ApiErrorDto?): ProfileFailure {
        val code = error?.code?.trim()
        return when {
            status == 401 -> ProfileUnauthorized
            status == 429 -> ProfileRateLimited
            status >= 500 -> ProfileServerFailure
            status == 404 || code.equals("not_found", ignoreCase = true) -> ProfileAccountUnavailable
            else -> ProfileInvalidResponse
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
