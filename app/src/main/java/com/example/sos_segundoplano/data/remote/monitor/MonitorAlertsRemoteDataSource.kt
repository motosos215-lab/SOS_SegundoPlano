package com.example.sos_segundoplano.data.remote.monitor

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import com.example.sos_segundoplano.data.remote.auth.ApiErrorDto
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

sealed interface MonitorAlertsRemoteResult<out T> {
    data class Success<T>(val data: T) : MonitorAlertsRemoteResult<T>
    data class Failure(val statusCode: Int?, val errorCode: String?, val message: String?) : MonitorAlertsRemoteResult<Nothing>
}

interface MonitorAlertsRemoteDataSource {
    suspend fun list(authorization: String): MonitorAlertsRemoteResult<Any>
    suspend fun detail(authorization: String, id: String): MonitorAlertsRemoteResult<MonitorAlertDetailDataDto>
    suspend fun status(authorization: String, id: String): MonitorAlertsRemoteResult<Any>
    suspend fun location(authorization: String, id: String): MonitorAlertsRemoteResult<Any>
    suspend fun view(authorization: String, id: String): MonitorAlertsRemoteResult<Any>
    suspend fun acknowledge(authorization: String, id: String, request: AcknowledgeMonitorAlertRequestDto): MonitorAlertsRemoteResult<MonitorAlertDetailDataDto>
    suspend fun decline(authorization: String, id: String, request: DeclineMonitorAlertRequestDto): MonitorAlertsRemoteResult<MonitorAlertDetailDataDto>
}

class RetrofitMonitorAlertsRemoteDataSource(private val api: MonitorAlertsApi, moshi: Moshi) : MonitorAlertsRemoteDataSource {
    private val errorAdapter: JsonAdapter<ApiEnvelopeDto<Any>> = moshi.adapter(Types.newParameterizedType(ApiEnvelopeDto::class.java, Any::class.java))
    override suspend fun list(authorization: String) = execute({ api.list(authorization) }) { it }
    override suspend fun detail(authorization: String, id: String) = execute({ api.detail(authorization, id) }) { it }
    override suspend fun status(authorization: String, id: String) = execute({ api.status(authorization, id) }) { it }
    override suspend fun location(authorization: String, id: String) = execute({ api.location(authorization, id) }) { it }
    override suspend fun view(authorization: String, id: String) = execute({ api.view(authorization, id) }) { it }
    override suspend fun acknowledge(authorization: String, id: String, request: AcknowledgeMonitorAlertRequestDto) = execute({ api.acknowledge(authorization, id, request) }) { it }
    override suspend fun decline(authorization: String, id: String, request: DeclineMonitorAlertRequestDto) = execute({ api.decline(authorization, id, request) }) { it }

    private suspend fun <T, R> execute(
        request: suspend () -> Response<ApiEnvelopeDto<T>>,
        extract: (T) -> R?
    ): MonitorAlertsRemoteResult<R> {
        return try {
            val response = request()
            if (!response.isSuccessful) return failure(response.code(), parseError(response))
            val envelope = response.body()
                ?: return MonitorAlertsRemoteResult.Failure(response.code(), null, "response_body_missing")
            if (!envelope.success) return failure(response.code(), envelope.error)
            val data = envelope.data
                ?: return MonitorAlertsRemoteResult.Failure(response.code(), envelope.error?.code, "response_data_missing")
            val extracted = extract(data)
                ?: return MonitorAlertsRemoteResult.Failure(response.code(), null, "response_contract_incomplete")
            MonitorAlertsRemoteResult.Success(extracted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: JsonDataException) {
            MonitorAlertsRemoteResult.Failure(null, null, "response_json_invalid")
        } catch (_: IOException) {
            MonitorAlertsRemoteResult.Failure(null, null, "network_unavailable")
        } catch (_: IllegalArgumentException) {
            MonitorAlertsRemoteResult.Failure(null, null, "response_invalid")
        }
    }

    private fun failure(status: Int, error: ApiErrorDto?) = MonitorAlertsRemoteResult.Failure(status, error?.code, error?.message?.replace(Regex("[\\r\\n\\t]"), " ")?.take(160))
    private fun parseError(response: Response<*>): ApiErrorDto? = response.errorBody()?.use { runCatching { errorAdapter.fromJson(it.source())?.error }.getOrNull() }
}
