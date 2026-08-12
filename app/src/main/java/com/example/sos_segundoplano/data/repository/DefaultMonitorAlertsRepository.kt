package com.example.sos_segundoplano.data.repository

import com.example.sos_segundoplano.data.remote.monitor.AcknowledgeMonitorAlertRequestDto
import com.example.sos_segundoplano.data.remote.monitor.DeclineMonitorAlertRequestDto
import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertAcknowledgementDto
import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertDetailDataDto
import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertsRemoteDataSource
import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertsRemoteResult
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.monitor.MonitorAlertAcknowledgement
import com.example.sos_segundoplano.domain.monitor.MonitorAlertDetail
import com.example.sos_segundoplano.domain.monitor.MonitorAlertOpaquePayload
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsRepository
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsResult
import com.example.sos_segundoplano.domain.monitor.NotificationDeliveryAttemptId
import com.example.sos_segundoplano.domain.repository.AuthRepository

class DefaultMonitorAlertsRepository(
    private val authRepository: AuthRepository,
    private val remote: MonitorAlertsRemoteDataSource
) : MonitorAlertsRepository {
    override suspend fun getAlerts() = monitorCall { remote.list(it) }.opaque()
    override suspend fun getAlert(id: NotificationDeliveryAttemptId) = monitorCall { remote.detail(it, id.value) }.detail()
    override suspend fun getStatus(id: NotificationDeliveryAttemptId) = monitorCall { remote.status(it, id.value) }.opaque()
    override suspend fun getLocation(id: NotificationDeliveryAttemptId) = monitorCall { remote.location(it, id.value) }.opaque()
    override suspend fun markViewed(id: NotificationDeliveryAttemptId) = monitorCall { remote.view(it, id.value) }.opaque()
    override suspend fun acknowledge(id: NotificationDeliveryAttemptId, responseType: String, message: String) =
        monitorCall { remote.acknowledge(it, id.value, AcknowledgeMonitorAlertRequestDto(responseType, message)) }.detail()
    override suspend fun decline(id: NotificationDeliveryAttemptId, reason: String) =
        monitorCall { remote.decline(it, id.value, DeclineMonitorAlertRequestDto(reason)) }.detail()

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

    private fun MonitorAlertsRemoteResult<MonitorAlertDetailDataDto>.detail(): MonitorAlertsResult<MonitorAlertDetail> = when (this) {
        is MonitorAlertsRemoteResult.Success -> MonitorAlertsResult.Success(MonitorAlertDetail(data.acknowledgement?.toDomain()))
        is MonitorAlertsRemoteResult.Failure -> MonitorAlertsResult.Failure(statusCode, errorCode, message)
    }

    private fun MonitorAlertAcknowledgementDto.toDomain(): MonitorAlertAcknowledgement? =
        if (listOf(id, alertDispatchId, notificationDeliveryAttemptId, incidentId, tripId, emergencyContactId, status).any { it.isNullOrBlank() }) null
        else MonitorAlertAcknowledgement(id!!, alertDispatchId!!, notificationDeliveryAttemptId!!, incidentId!!, tripId!!, emergencyContactId!!, status!!, responseType, message, viewedAtUtc, acknowledgedAtUtc, declinedAtUtc, createdAtUtc, updatedAtUtc)
}
