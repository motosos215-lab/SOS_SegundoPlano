package com.example.sos_segundoplano.data.remote.incident

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

sealed interface ManualSosAlertSubmissionStatus {
    data class Success(
        val remoteIncidentId: String,
        val remoteAlertDispatchId: String,
        val notificationAttempts: List<ManualSosNotificationAttemptDto>,
        val summary: ManualSosAlertSummaryDto?
    ) : ManualSosAlertSubmissionStatus

    data class HttpError(val statusCode: Int, val sanitizedMessage: String?) : ManualSosAlertSubmissionStatus
    data class NetworkUnavailable(val sanitizedMessage: String?) : ManualSosAlertSubmissionStatus
    data class Timeout(val sanitizedMessage: String?) : ManualSosAlertSubmissionStatus
    data class InvalidResponse(val sanitizedMessage: String?) : ManualSosAlertSubmissionStatus
}

fun interface ManualSosAlertRemoteDataSource {
    suspend fun createManualSosAlert(
        authorization: String,
        request: ManualSosAlertRequestDto
    ): ManualSosAlertSubmissionStatus
}

class RetrofitManualSosAlertRemoteDataSource(
    private val api: MobileSosAlertsApi,
    moshi: Moshi
) : ManualSosAlertRemoteDataSource {
    private val errorEnvelopeAdapter: JsonAdapter<ApiEnvelopeDto<Any>> = moshi.adapter(
        Types.newParameterizedType(ApiEnvelopeDto::class.java, Any::class.java)
    )

    override suspend fun createManualSosAlert(
        authorization: String,
        request: ManualSosAlertRequestDto
    ): ManualSosAlertSubmissionStatus = executeSafely {
        val response = api.createManualSosAlert(authorization, request)
        if (!response.isSuccessful) return@executeSafely mapHttpFailure(response)
        val envelope = response.body()
            ?: return@executeSafely ManualSosAlertSubmissionStatus.InvalidResponse("response_body_missing")
        if (!envelope.success) return@executeSafely mapHandledFailure(response.code(), envelope.error)
        val data = envelope.data
            ?: return@executeSafely ManualSosAlertSubmissionStatus.InvalidResponse("response_data_missing")
        val incident = data.incident
            ?: return@executeSafely ManualSosAlertSubmissionStatus.InvalidResponse("incident_missing")
        val dispatch = data.alertDispatch
            ?: return@executeSafely ManualSosAlertSubmissionStatus.InvalidResponse("alert_dispatch_missing")
        val incidentId = incident.id.normalized()
            ?: return@executeSafely ManualSosAlertSubmissionStatus.InvalidResponse("incident_id_missing")
        val incidentTripId = incident.tripId.normalized()
            ?: return@executeSafely ManualSosAlertSubmissionStatus.InvalidResponse("incident_trip_id_missing")
        val alertDispatchId = dispatch.id.normalized()
            ?: return@executeSafely ManualSosAlertSubmissionStatus.InvalidResponse("alert_dispatch_id_missing")
        val dispatchIncidentId = dispatch.incidentId.normalized()
            ?: return@executeSafely ManualSosAlertSubmissionStatus.InvalidResponse("dispatch_incident_id_missing")
        if (incidentTripId != request.tripId) {
            return@executeSafely ManualSosAlertSubmissionStatus.InvalidResponse("incident_trip_id_mismatch")
        }
        if (dispatchIncidentId != incidentId) {
            return@executeSafely ManualSosAlertSubmissionStatus.InvalidResponse("dispatch_incident_id_mismatch")
        }
        ManualSosAlertSubmissionStatus.Success(
            remoteIncidentId = incidentId,
            remoteAlertDispatchId = alertDispatchId,
            notificationAttempts = data.notificationAttempts.orEmpty(),
            summary = data.summary
        )
    }

    private suspend fun executeSafely(
        block: suspend () -> ManualSosAlertSubmissionStatus
    ): ManualSosAlertSubmissionStatus = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: JsonDataException) {
        ManualSosAlertSubmissionStatus.InvalidResponse("response_json_invalid")
    } catch (_: JsonEncodingException) {
        ManualSosAlertSubmissionStatus.InvalidResponse("response_json_invalid")
    } catch (_: EOFException) {
        ManualSosAlertSubmissionStatus.InvalidResponse("response_body_invalid")
    } catch (_: SocketTimeoutException) {
        ManualSosAlertSubmissionStatus.Timeout("network_timeout")
    } catch (_: UnknownHostException) {
        ManualSosAlertSubmissionStatus.NetworkUnavailable("network_unavailable")
    } catch (_: ConnectException) {
        ManualSosAlertSubmissionStatus.NetworkUnavailable("network_unavailable")
    } catch (_: NoRouteToHostException) {
        ManualSosAlertSubmissionStatus.NetworkUnavailable("network_unavailable")
    } catch (_: IOException) {
        ManualSosAlertSubmissionStatus.NetworkUnavailable("network_unavailable")
    } catch (_: IllegalArgumentException) {
        ManualSosAlertSubmissionStatus.InvalidResponse("response_invalid")
    }

    private fun mapHttpFailure(response: Response<*>): ManualSosAlertSubmissionStatus.HttpError =
        mapHandledFailure(response.code(), parseError(response))

    private fun mapHandledFailure(
        status: Int,
        error: ApiErrorDto?
    ): ManualSosAlertSubmissionStatus.HttpError = ManualSosAlertSubmissionStatus.HttpError(
        status,
        sanitize(error?.code ?: error?.message ?: "request_rejected")
    )

    private fun parseError(response: Response<*>): ApiErrorDto? = response.errorBody()?.use { body ->
        try {
            errorEnvelopeAdapter.fromJson(body.source())?.error
        } catch (_: IOException) {
            null
        } catch (_: JsonDataException) {
            null
        }
    }

    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun sanitize(value: String?): String? = value
        ?.replace(Regex("[\\r\\n\\t]"), " ")
        ?.take(MAX_SANITIZED_LENGTH)

    private companion object {
        const val MAX_SANITIZED_LENGTH = 160
    }
}
