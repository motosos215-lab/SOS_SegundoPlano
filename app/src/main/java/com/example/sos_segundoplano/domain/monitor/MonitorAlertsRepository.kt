package com.example.sos_segundoplano.domain.monitor

/** notificationDeliveryAttemptId is the canonical identifier for Monitor alert operations. */
data class NotificationDeliveryAttemptId(val value: String) {
    init { require(value.isNotBlank()) }
}

data class MonitorAlertAcknowledgement(
    val id: String?,
    val alertDispatchId: String?,
    val notificationDeliveryAttemptId: String,
    val incidentId: String?,
    val tripId: String?,
    val emergencyContactId: String?,
    val status: String?,
    val responseType: String?,
    val message: String?,
    val viewedAtUtc: String?,
    val acknowledgedAtUtc: String?,
    val declinedAtUtc: String?,
    val createdAtUtc: String?,
    val updatedAtUtc: String?
)

data class MonitorAlertDetail(val acknowledgement: MonitorAlertAcknowledgement?)

data class MonitorAlertStatus(
    val incident: MonitorAlertIncidentStatus?,
    val trip: MonitorAlertTripStatus?,
    val alertDispatch: MonitorAlertDispatchStatus?,
    val notifications: MonitorAlertNotificationsStatus?,
    val acknowledgements: MonitorAlertAcknowledgementsStatus?,
    val location: MonitorAlertStatusLocation?,
    val overallStatus: String?,
    val requiresAttention: Boolean?,
    val lastUpdatedAtUtc: String?
)
data class MonitorAlertIncidentStatus(val status: String?, val source: String?, val cause: String?, val riskLevel: String?, val occurredAtUtc: String?, val createdAtUtc: String?)
data class MonitorAlertTripStatus(val status: String?, val startedAtUtc: String?, val finishedAtUtc: String?)
data class MonitorAlertDispatchStatus(val status: String?, val priority: String?, val reason: String?, val createdAtUtc: String?)
data class MonitorAlertNotificationsStatus(val total: Int?, val prepared: Int?, val simulatedSent: Int?, val failed: Int?, val cancelled: Int?)
data class MonitorAlertAcknowledgementsStatus(val total: Int?, val pending: Int?, val viewed: Int?, val acknowledged: Int?, val declined: Int?)
data class MonitorAlertStatusLocation(val available: Boolean?, val latitude: Double?, val longitude: Double?, val accuracyMeters: Double?, val source: String?, val recordedAtUtc: String?, val receivedAtUtc: String?, val isActive: Boolean?, val isStale: Boolean?)

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
    suspend fun getStatus(id: NotificationDeliveryAttemptId): MonitorAlertsResult<MonitorAlertStatus>
    suspend fun getLocation(id: NotificationDeliveryAttemptId): MonitorAlertsResult<MonitorAlertOpaquePayload>
    /** Typed location used by the Monitor UI when aggregated status has not caught up yet. */
    suspend fun getLocationStatus(id: NotificationDeliveryAttemptId): MonitorAlertsResult<MonitorAlertStatusLocation?> =
        MonitorAlertsResult.Success(null)
    suspend fun markViewed(id: NotificationDeliveryAttemptId): MonitorAlertsResult<MonitorAlertOpaquePayload>
    suspend fun acknowledge(id: NotificationDeliveryAttemptId, responseType: String, message: String): MonitorAlertsResult<MonitorAlertDetail>
    suspend fun decline(id: NotificationDeliveryAttemptId, reason: String): MonitorAlertsResult<MonitorAlertDetail>
}
