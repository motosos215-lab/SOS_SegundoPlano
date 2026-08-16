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
    suspend fun list(@Header("Authorization") authorization: String): Response<ApiEnvelopeDto<MonitorAlertHistoryDataDto>>

    @GET("api/v1/monitor/alerts/{notificationDeliveryAttemptId}")
    suspend fun detail(@Header("Authorization") authorization: String, @Path("notificationDeliveryAttemptId") id: String): Response<ApiEnvelopeDto<MonitorAlertDetailDataDto>>

    @GET("api/v1/monitor/alerts/{notificationDeliveryAttemptId}/status")
    suspend fun status(@Header("Authorization") authorization: String, @Path("notificationDeliveryAttemptId") id: String): Response<ApiEnvelopeDto<MonitorAlertStatusDataDto>>

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
data class DeclineMonitorAlertRequestDto(
    val responseType: String = "CannotAssist",
    val message: String? = null
)
data class MonitorAlertHistoryDataDto(
    val alerts: List<MonitorAlertAcknowledgementDto>,
    val pageNumber: Int,
    val pageSize: Int,
    val totalCount: Int
)
data class MonitorAlertDetailDataDto(val acknowledgement: MonitorAlertAcknowledgementDto? = null)
data class MonitorAlertStatusDataDto(
    val incident: MonitorAlertIncidentStatusDto? = null,
    val trip: MonitorAlertTripStatusDto? = null,
    val alertDispatch: MonitorAlertDispatchStatusDto? = null,
    val notifications: MonitorAlertNotificationsStatusDto? = null,
    val acknowledgements: MonitorAlertAcknowledgementsStatusDto? = null,
    val location: MonitorAlertStatusLocationDto? = null,
    val overallStatus: String? = null,
    val requiresAttention: Boolean? = null,
    val lastUpdatedAtUtc: String? = null
)
data class MonitorAlertIncidentStatusDto(val id: String? = null, val status: String? = null, val source: String? = null, val cause: String? = null, val riskLevel: String? = null, val occurredAtUtc: String? = null, val createdAtUtc: String? = null)
data class MonitorAlertTripStatusDto(val id: String? = null, val status: String? = null, val startedAtUtc: String? = null, val finishedAtUtc: String? = null)
data class MonitorAlertDispatchStatusDto(val id: String? = null, val status: String? = null, val priority: String? = null, val reason: String? = null, val createdAtUtc: String? = null)
data class MonitorAlertNotificationsStatusDto(val total: Int? = null, val prepared: Int? = null, val simulatedSent: Int? = null, val failed: Int? = null, val cancelled: Int? = null)
data class MonitorAlertAcknowledgementsStatusDto(val total: Int? = null, val pending: Int? = null, val viewed: Int? = null, val acknowledged: Int? = null, val declined: Int? = null)
data class MonitorAlertStatusLocationDto(val available: Boolean? = null, val incidentId: String? = null, val tripId: String? = null, val latitude: Double? = null, val longitude: Double? = null, val accuracyMeters: Double? = null, val source: String? = null, val recordedAtUtc: String? = null, val receivedAtUtc: String? = null, val isActive: Boolean? = null, val isStale: Boolean? = null)
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
