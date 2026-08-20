package com.example.sos_segundoplano.data.repository

import com.example.sos_segundoplano.data.remote.monitor.AcknowledgeMonitorAlertRequestDto
import com.example.sos_segundoplano.data.remote.monitor.DeclineMonitorAlertRequestDto
import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertAcknowledgementDto
import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertDetailDataDto
import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertHistoryDataDto
import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertLocationDataDto
import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertStatusDataDto
import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertsRemoteDataSource
import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertsRemoteResult
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.monitor.MonitorAlertAcknowledgement
import com.example.sos_segundoplano.domain.monitor.MonitorAlertDetail
import com.example.sos_segundoplano.domain.monitor.MonitorAlertOpaquePayload
import com.example.sos_segundoplano.domain.monitor.MonitorAlertStatus
import com.example.sos_segundoplano.domain.monitor.MonitorAlertIncidentStatus
import com.example.sos_segundoplano.domain.monitor.MonitorAlertTripStatus
import com.example.sos_segundoplano.domain.monitor.MonitorAlertDispatchStatus
import com.example.sos_segundoplano.domain.monitor.MonitorAlertNotificationsStatus
import com.example.sos_segundoplano.domain.monitor.MonitorAlertAcknowledgementsStatus
import com.example.sos_segundoplano.domain.monitor.MonitorAlertStatusLocation
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsRepository
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsResult
import com.example.sos_segundoplano.domain.monitor.NotificationDeliveryAttemptId
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.features.history.HistoryDiagnostics

class DefaultMonitorAlertsRepository(
    private val authRepository: AuthRepository,
    private val remote: MonitorAlertsRemoteDataSource
) : MonitorAlertsRepository {
    override suspend fun listAlerts(): MonitorAlertsResult<List<MonitorAlertAcknowledgement>> {
        val remoteResult = monitorCall { remote.list(it) }
        when (remoteResult) {
            is MonitorAlertsRemoteResult.Success -> {
                val alerts = remoteResult.data.alerts
                HistoryDiagnostics.debug(
                    HistoryDiagnostics.monitorApiSuccess(
                        pageNumber = remoteResult.data.pageNumber,
                        pageSize = remoteResult.data.pageSize,
                        totalCount = remoteResult.data.totalCount,
                        receivedCount = alerts.size,
                        hasPending = alerts.any { it.status == "Pending" },
                        hasViewed = alerts.any { it.status == "Viewed" },
                        hasAcknowledged = alerts.any { it.status == "Acknowledged" },
                        hasDeclined = alerts.any { it.status == "Declined" },
                        hasDeliveryAttempt = alerts.any { !it.notificationDeliveryAttemptId.isNullOrBlank() }
                    )
                )
                val mapped = alerts.mapNotNull { it.toHistoryDomain() }
                HistoryDiagnostics.debug(
                    HistoryDiagnostics.monitorMapping(
                        receivedCount = alerts.size,
                        mappedCount = mapped.size,
                        hasAcknowledged = mapped.any { it.status == "Acknowledged" }
                    )
                )
                return MonitorAlertsResult.Success(mapped)
            }
            is MonitorAlertsRemoteResult.Failure -> {
                HistoryDiagnostics.debug(HistoryDiagnostics.apiFailure("monitor_history", remoteResult.statusCode, remoteResult.message))
                return MonitorAlertsResult.Failure(remoteResult.statusCode, remoteResult.errorCode, remoteResult.message)
            }
        }
    }
    override suspend fun getAlerts() = monitorCall { remote.list(it) }.historyOpaque()
    override suspend fun getAlert(id: NotificationDeliveryAttemptId) = monitorCall { remote.detail(it, id.value) }.detail()
    override suspend fun getStatus(id: NotificationDeliveryAttemptId) = monitorCall { remote.status(it, id.value) }.status()
    override suspend fun getLocation(id: NotificationDeliveryAttemptId) = monitorCall { remote.location(it, id.value) }.locationOpaque()
    override suspend fun getLocationStatus(id: NotificationDeliveryAttemptId) = monitorCall { remote.location(it, id.value) }.locationStatus()
    override suspend fun markViewed(id: NotificationDeliveryAttemptId) = monitorCall { remote.view(it, id.value) }.opaque()
    override suspend fun acknowledge(id: NotificationDeliveryAttemptId, responseType: String, message: String) =
        monitorCall { remote.acknowledge(it, id.value, AcknowledgeMonitorAlertRequestDto(responseType, message)) }.detail()
    override suspend fun decline(id: NotificationDeliveryAttemptId, reason: String) =
        monitorCall { remote.decline(it, id.value, DeclineMonitorAlertRequestDto(reason = reason)) }.detail()

    private suspend fun <T> monitorCall(call: suspend (String) -> MonitorAlertsRemoteResult<T>): MonitorAlertsRemoteResult<T> {
        val role = when (val state = authRepository.observeSession().value) {
            is SessionState.Authenticated -> state.user.role
            is SessionState.Refreshing -> state.user.role
            else -> null
        }
        if (role != UserRole.Monitor) return MonitorAlertsRemoteResult.Failure(403, "forbidden", "role_not_allowed")
        val firstToken = (authRepository.ensureValidAccessToken() as? AuthResult.Success)?.value?.reveal()
            ?: return MonitorAlertsRemoteResult.Failure(401, null, "access_token_unavailable")
        val first = call("Bearer $firstToken")
        if (first !is MonitorAlertsRemoteResult.Failure || first.statusCode != 401) return first
        if (authRepository.refreshSession() !is AuthResult.Success) return first
        val refreshed = (authRepository.ensureValidAccessToken() as? AuthResult.Success)?.value?.reveal() ?: return first
        return call("Bearer $refreshed")
    }

    private fun MonitorAlertsRemoteResult<Any>.opaque(): MonitorAlertsResult<MonitorAlertOpaquePayload> = when (this) {
        is MonitorAlertsRemoteResult.Success -> MonitorAlertsResult.Success(MonitorAlertOpaquePayload(data))
        is MonitorAlertsRemoteResult.Failure -> MonitorAlertsResult.Failure(statusCode, errorCode, message)
    }

    private fun MonitorAlertsRemoteResult<MonitorAlertLocationDataDto>.locationOpaque(): MonitorAlertsResult<MonitorAlertOpaquePayload> = when (this) {
        is MonitorAlertsRemoteResult.Success -> MonitorAlertsResult.Success(MonitorAlertOpaquePayload(data))
        is MonitorAlertsRemoteResult.Failure -> MonitorAlertsResult.Failure(statusCode, errorCode, message)
    }

    private fun MonitorAlertsRemoteResult<MonitorAlertLocationDataDto>.locationStatus(): MonitorAlertsResult<MonitorAlertStatusLocation?> = when (this) {
        is MonitorAlertsRemoteResult.Success -> MonitorAlertsResult.Success(data.resolvedLocation()?.toDomain())
        is MonitorAlertsRemoteResult.Failure -> MonitorAlertsResult.Failure(statusCode, errorCode, message)
    }

    private fun MonitorAlertsRemoteResult<MonitorAlertHistoryDataDto>.historyOpaque(): MonitorAlertsResult<MonitorAlertOpaquePayload> = when (this) {
        is MonitorAlertsRemoteResult.Success -> MonitorAlertsResult.Success(MonitorAlertOpaquePayload(data))
        is MonitorAlertsRemoteResult.Failure -> MonitorAlertsResult.Failure(statusCode, errorCode, message)
    }

    private fun MonitorAlertsRemoteResult<MonitorAlertStatusDataDto>.status(): MonitorAlertsResult<MonitorAlertStatus> = when (this) {
        is MonitorAlertsRemoteResult.Success -> MonitorAlertsResult.Success(data.toDomain())
        is MonitorAlertsRemoteResult.Failure -> MonitorAlertsResult.Failure(statusCode, errorCode, message)
    }

    private fun MonitorAlertsRemoteResult<MonitorAlertDetailDataDto>.detail(): MonitorAlertsResult<MonitorAlertDetail> = when (this) {
        is MonitorAlertsRemoteResult.Success -> MonitorAlertsResult.Success(MonitorAlertDetail(data.acknowledgement?.toHistoryDomain()))
        is MonitorAlertsRemoteResult.Failure -> MonitorAlertsResult.Failure(statusCode, errorCode, message)
    }

    private fun MonitorAlertsRemoteResult<MonitorAlertHistoryDataDto>.alerts(): MonitorAlertsResult<List<MonitorAlertAcknowledgement>> = when (this) {
        // The endpoint has no Android paging parameters yet; the current UI renders this received page in server order.
        is MonitorAlertsRemoteResult.Success -> MonitorAlertsResult.Success(data.alerts.mapNotNull { it.toHistoryDomain() })
        is MonitorAlertsRemoteResult.Failure -> MonitorAlertsResult.Failure(statusCode, errorCode, message)
    }
}

/** A history row is actionable only when its canonical Monitor attempt ID is present. */
internal fun MonitorAlertAcknowledgementDto.toHistoryDomain(): MonitorAlertAcknowledgement? {
    val attemptId = notificationDeliveryAttemptId.normalized() ?: return null
    return MonitorAlertAcknowledgement(
        id = id.normalized(),
        alertDispatchId = alertDispatchId.normalized(),
        notificationDeliveryAttemptId = attemptId,
        incidentId = incidentId.normalized(),
        tripId = tripId.normalized(),
        emergencyContactId = emergencyContactId.normalized(),
        status = status.normalized(),
        responseType = responseType.normalized(),
        message = message.normalized(),
        viewedAtUtc = viewedAtUtc.normalized(),
        acknowledgedAtUtc = acknowledgedAtUtc.normalized(),
        declinedAtUtc = declinedAtUtc.normalized(),
        createdAtUtc = createdAtUtc.normalized(),
        updatedAtUtc = updatedAtUtc.normalized()
    )
}

private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun MonitorAlertStatusDataDto.toDomain() = MonitorAlertStatus(
    incident = incident?.let { MonitorAlertIncidentStatus(it.status, it.source, it.cause, it.riskLevel, it.occurredAtUtc, it.createdAtUtc) },
    trip = trip?.let { MonitorAlertTripStatus(it.status, it.startedAtUtc, it.finishedAtUtc) },
    alertDispatch = alertDispatch?.let { MonitorAlertDispatchStatus(it.status, it.priority, it.reason, it.createdAtUtc) },
    notifications = notifications?.let { MonitorAlertNotificationsStatus(it.total, it.prepared, it.simulatedSent, it.failed, it.cancelled) },
    acknowledgements = acknowledgements?.let { MonitorAlertAcknowledgementsStatus(it.total, it.pending, it.viewed, it.acknowledged, it.declined) },
    location = location?.let { MonitorAlertStatusLocation(it.available, it.latitude, it.longitude, it.accuracyMeters, it.source, it.recordedAtUtc, it.receivedAtUtc, it.isActive, it.isStale) },
    overallStatus = overallStatus,
    requiresAttention = requiresAttention,
    lastUpdatedAtUtc = lastUpdatedAtUtc
)

private fun com.example.sos_segundoplano.data.remote.monitor.MonitorAlertStatusLocationDto.toDomain() = MonitorAlertStatusLocation(
    available = available,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    source = source,
    recordedAtUtc = recordedAtUtc,
    receivedAtUtc = receivedAtUtc,
    isActive = isActive,
    isStale = isStale
)
