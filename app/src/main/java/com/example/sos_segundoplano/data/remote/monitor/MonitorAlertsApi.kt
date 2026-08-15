package com.example.sos_segundoplano.data.remote.monitor

import com.example.sos_segundoplano.data.remote.auth.ApiEnvelopeDto
import com.example.sos_segundoplano.data.remote.emergency.EmptyBodyDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface MonitorAlertsApi {
    @GET("api/v1/monitor/alerts")
    suspend fun list(@Header("Authorization") authorization: String): Response<ApiEnvelopeDto<List<MonitorAlertAcknowledgementDto>>>

    @GET("api/v1/monitor/alerts/{notificationDeliveryAttemptId}")
    suspend fun detail(@Header("Authorization") authorization: String, @Path("notificationDeliveryAttemptId") id: String): Response<ApiEnvelopeDto<MonitorAlertDetailDataDto>>

    @GET("api/v1/monitor/alerts/{notificationDeliveryAttemptId}/status")
    suspend fun status(@Header("Authorization") authorization: String, @Path("notificationDeliveryAttemptId") id: String): Response<ApiEnvelopeDto<Any>>

    @GET("api/v1/monitor/alerts/{notificationDeliveryAttemptId}/location")
    suspend fun location(@Header("Authorization") authorization: String, @Path("notificationDeliveryAttemptId") id: String): Response<ApiEnvelopeDto<Any>>

    @POST("api/v1/monitor/alerts/{notificationDeliveryAttemptId}/view")
    suspend fun view(@Header("Authorization") authorization: String, @Path("notificationDeliveryAttemptId") id: String, @Body request: EmptyBodyDto = EmptyBodyDto()): Response<ApiEnvelopeDto<Any>>

    @POST("api/v1/monitor/alerts/{notificationDeliveryAttemptId}/acknowledge")
    suspend fun acknowledge(@Header("Authorization") authorization: String, @Path("notificationDeliveryAttemptId") id: String, @Body request: AcknowledgeMonitorAlertRequestDto): Response<ApiEnvelopeDto<MonitorAlertDetailDataDto>>

    @POST("api/v1/monitor/alerts/{notificationDeliveryAttemptId}/decline")
    suspend fun decline(@Header("Authorization") authorization: String, @Path("notificationDeliveryAttemptId") id: String, @Body request: DeclineMonitorAlertRequestDto): Response<ApiEnvelopeDto<MonitorAlertDetailDataDto>>
}

data class AcknowledgeMonitorAlertRequestDto(val responseType: String, val message: String)
data class DeclineMonitorAlertRequestDto(val reason: String)
data class MonitorAlertDetailDataDto(val acknowledgement: MonitorAlertAcknowledgementDto? = null)
data class MonitorAlertAcknowledgementDto(
    val id: String? = null,
    val alertDispatchId: String? = null,
    val notificationDeliveryAttemptId: String? = null,
    val incidentId: String? = null,
    val tripId: String? = null,
    val emergencyContactId: String? = null,
    val status: String? = null,
    val responseType: String? = null,
    val message: String? = null,
    val viewedAtUtc: String? = null,
    val acknowledgedAtUtc: String? = null,
    val declinedAtUtc: String? = null,
    val createdAtUtc: String? = null,
    val updatedAtUtc: String? = null
)
