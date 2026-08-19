package com.example.sos_segundoplano.data.remote.emergency

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import com.example.sos_segundoplano.data.remote.auth.ApiErrorDto
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

sealed interface EmergencyContactsRemoteResult<out T> {
    data class Success<T>(val data: T) : EmergencyContactsRemoteResult<T>
    data class Failure(val statusCode: Int?, val errorCode: String?, val message: String?) : EmergencyContactsRemoteResult<Nothing>
}

interface EmergencyContactsRemoteDataSource {
    suspend fun list(authorization: String): EmergencyContactsRemoteResult<List<EmergencyContactDto>>
    suspend fun create(authorization: String, request: CreateEmergencyContactRequestDto): EmergencyContactsRemoteResult<EmergencyContactDto>
    suspend fun invite(authorization: String, contactId: String): EmergencyContactsRemoteResult<EmergencyContactDto>
    suspend fun invitation(authorization: String, code: String): EmergencyContactsRemoteResult<EmergencyInvitationDto>
    suspend fun acceptInvitation(authorization: String, code: String): EmergencyContactsRemoteResult<EmergencyContactDto>
}

class RetrofitEmergencyContactsRemoteDataSource(
    private val api: EmergencyContactsApi,
    moshi: Moshi
) : EmergencyContactsRemoteDataSource {
    private val errorAdapter: JsonAdapter<ApiEnvelopeDto<Any>> = moshi.adapter(
        Types.newParameterizedType(ApiEnvelopeDto::class.java, Any::class.java)
    )

    override suspend fun list(authorization: String) =
        execute({ api.list(authorization) }) { it.contacts }

    override suspend fun create(authorization: String, request: CreateEmergencyContactRequestDto) =
        execute({ api.create(authorization, request) }) { it.contact }

    override suspend fun invite(authorization: String, contactId: String) =
        execute({ api.invite(authorization, contactId) }) { it.contact }

    override suspend fun invitation(authorization: String, code: String) =
        execute({ api.invitation(authorization, code) }) { it.invitation }

    override suspend fun acceptInvitation(authorization: String, code: String) =
        execute({ api.acceptInvitation(authorization, code) }) { it.contact }

    private suspend fun <T, R> execute(
        request: suspend () -> Response<ApiEnvelopeDto<T>>,
        extract: (T) -> R?
    ): EmergencyContactsRemoteResult<R> {
        return try {
            val response = request()
            if (!response.isSuccessful) return failure(response.code(), parseError(response))
            val envelope = response.body()
                ?: return EmergencyContactsRemoteResult.Failure(response.code(), null, "response_body_missing")
            if (!envelope.success) return failure(response.code(), envelope.error)
            val data = envelope.data
                ?: return EmergencyContactsRemoteResult.Failure(response.code(), envelope.error?.code, "response_data_missing")
            val extracted = extract(data)
                ?: return EmergencyContactsRemoteResult.Failure(response.code(), null, "response_contract_incomplete")
            EmergencyContactsRemoteResult.Success(extracted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: JsonDataException) {
            EmergencyContactsRemoteResult.Failure(null, null, "response_json_invalid")
        } catch (_: IOException) {
            EmergencyContactsRemoteResult.Failure(null, null, "network_unavailable")
        } catch (_: IllegalArgumentException) {
            EmergencyContactsRemoteResult.Failure(null, null, "response_invalid")
        }
    }

    private fun failure(status: Int, error: ApiErrorDto?): EmergencyContactsRemoteResult.Failure =
        EmergencyContactsRemoteResult.Failure(status, error?.code, error?.message?.sanitize())

    private fun parseError(response: Response<*>): ApiErrorDto? = response.errorBody()?.use { body ->
        runCatching { errorAdapter.fromJson(body.source())?.error }.getOrNull()
    }

    private fun String.sanitize(): String = replace(Regex("[\\r\\n\\t]"), " ").take(160)
}
