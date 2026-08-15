package com.example.sos_segundoplano.domain.monitor

/** notificationDeliveryAttemptId is the canonical identifier for Monitor alert operations. */
data class NotificationDeliveryAttemptId(val value: String) {
    init { require(value.isNotBlank()) }
}

data class MonitorAlertAcknowledgement(
    val id: String,
    val alertDispatchId: String,
    val notificationDeliveryAttemptId: String,
    val incidentId: String,
    val tripId: String,
    val emergencyContactId: String,
    val status: String,
    val responseType: String?,
    val message: String?,
    val viewedAtUtc: String?,
    val acknowledgedAtUtc: String?,
    val declinedAtUtc: String?,
    val createdAtUtc: String?,
    val updatedAtUtc: String?
)

data class MonitorAlertDetail(val acknowledgement: MonitorAlertAcknowledgement?)

/** Isolated partial-contract boundary for Monitor response schemas not yet documented. */
data class MonitorAlertOpaquePayload(val value: Any)

sealed interface MonitorAlertsResult<out T> {
    data class Success<T>(val value: T) : MonitorAlertsResult<T>
    data class Failure(val statusCode: Int?, val errorCode: String?, val message: String?) : MonitorAlertsResult<Nothing>
}

interface MonitorAlertsRepository {
    suspend fun listAlerts(): MonitorAlertsResult<List<MonitorAlertAcknowledgement>>
    suspend fun getAlerts(): MonitorAlertsResult<MonitorAlertOpaquePayload>
    suspend fun getAlert(id: NotificationDeliveryAttemptId): MonitorAlertsResult<MonitorAlertDetail>
    suspend fun getStatus(id: NotificationDeliveryAttemptId): MonitorAlertsResult<MonitorAlertOpaquePayload>
    suspend fun getLocation(id: NotificationDeliveryAttemptId): MonitorAlertsResult<MonitorAlertOpaquePayload>
    suspend fun markViewed(id: NotificationDeliveryAttemptId): MonitorAlertsResult<MonitorAlertOpaquePayload>
    suspend fun acknowledge(id: NotificationDeliveryAttemptId, responseType: String, message: String): MonitorAlertsResult<MonitorAlertDetail>
    suspend fun decline(id: NotificationDeliveryAttemptId, reason: String): MonitorAlertsResult<MonitorAlertDetail>
}
