package com.example.sos_segundoplano.features.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sos_segundoplano.domain.monitor.MonitorAlertDetail
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsRepository
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsResult
import com.example.sos_segundoplano.domain.monitor.MonitorAlertStatus
import com.example.sos_segundoplano.domain.monitor.NotificationDeliveryAttemptId
import com.example.sos_segundoplano.domain.push.PendingMonitorAlertCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MonitorAlertsUiState {
    data object Ready : MonitorAlertsUiState
    data class Loading(val attemptId: NotificationDeliveryAttemptId) : MonitorAlertsUiState
    data class Alert(val attemptId: NotificationDeliveryAttemptId, val detail: MonitorAlertDetail, val action: MonitorAlertAction = MonitorAlertAction.Idle, val notice: MonitorAlertNotice? = null, val status: MonitorAlertStatus? = null) : MonitorAlertsUiState
    data class Error(val attemptId: NotificationDeliveryAttemptId, val message: String) : MonitorAlertsUiState
}

sealed interface MonitorAlertAction { data object Idle : MonitorAlertAction; data object Submitting : MonitorAlertAction }
sealed interface MonitorAlertNotice { data object Confirmed : MonitorAlertNotice; data object Declined : MonitorAlertNotice; data class NonBlockingError(val message: String) : MonitorAlertNotice }

class MonitorAlertsViewModel(
    private val repository: MonitorAlertsRepository,
    private val pendingAlerts: PendingMonitorAlertCoordinator,
    private val isMonitorSession: () -> Boolean = { true }
) : ViewModel() {
    private val mutableState = MutableStateFlow<MonitorAlertsUiState>(MonitorAlertsUiState.Ready)
    val state: StateFlow<MonitorAlertsUiState> = mutableState.asStateFlow()
    private val mutableConsumedFcmAttemptId = MutableStateFlow<String?>(null)
    val consumedFcmAttemptId: StateFlow<String?> = mutableConsumedFcmAttemptId.asStateFlow()
    private val mutableSuccessfulActionRevision = MutableStateFlow(0L)
    val successfulActionRevision: StateFlow<Long> = mutableSuccessfulActionRevision.asStateFlow()
    private var viewMarkedFor: String? = null
    private var lastConsumedFcmAttemptId: String? = null

    init {
        viewModelScope.launch {
            pendingAlerts.pendingAlerts.collect { pending ->
                consumePendingAlert(pending?.notificationDeliveryAttemptId)
            }
        }
    }

    fun loadPendingAlert() {
        consumePendingAlert(pendingAlerts.pending()?.notificationDeliveryAttemptId)
    }

    fun retry() {
        val attempt = when (val current = mutableState.value) {
            is MonitorAlertsUiState.Error -> current.attemptId
            is MonitorAlertsUiState.Alert -> current.attemptId
            is MonitorAlertsUiState.Loading -> current.attemptId
            MonitorAlertsUiState.Ready -> return
        }
        load(attempt)
    }

    fun open(notificationDeliveryAttemptId: String) {
        if (notificationDeliveryAttemptId.isNotBlank()) load(NotificationDeliveryAttemptId(notificationDeliveryAttemptId))
    }

    fun refresh() {
        val current = mutableState.value as? MonitorAlertsUiState.Alert ?: return
        viewModelScope.launch {
            when (val result = repository.getAlert(current.attemptId)) {
                is MonitorAlertsResult.Success -> mutableState.value = current.copy(detail = result.value)
                is MonitorAlertsResult.Failure -> mutableState.value = current.copy(notice = MonitorAlertNotice.NonBlockingError(result.message.userMessage()))
            }
        }
    }

    fun acknowledge(message: String) = submit { id -> repository.acknowledge(id, "CanAssist", message) }
    fun decline(reason: String) = submit { id -> repository.decline(id, reason) }

    private fun load(attemptId: NotificationDeliveryAttemptId) {
        if (!isMonitorSession()) { mutableState.value = MonitorAlertsUiState.Ready; return }
        mutableState.value = MonitorAlertsUiState.Loading(attemptId)
        viewModelScope.launch {
            when (val result = repository.getAlert(attemptId)) {
                is MonitorAlertsResult.Success -> {
                    mutableState.value = MonitorAlertsUiState.Alert(attemptId, result.value)
                    markViewedOnce(attemptId)
                    loadStatus(attemptId)
                }
                is MonitorAlertsResult.Failure -> mutableState.value = MonitorAlertsUiState.Error(attemptId, result.message.userMessage())
            }
        }
    }

    private fun markViewedOnce(attemptId: NotificationDeliveryAttemptId) {
        if (viewMarkedFor == attemptId.value) return
        viewMarkedFor = attemptId.value
        viewModelScope.launch {
            if (repository.markViewed(attemptId) is MonitorAlertsResult.Failure) {
                val current = mutableState.value as? MonitorAlertsUiState.Alert ?: return@launch
                if (current.attemptId == attemptId) mutableState.value = current.copy(notice = MonitorAlertNotice.NonBlockingError("No pudimos registrar la visualización."))
            }
        }
    }

    private fun submit(operation: suspend (NotificationDeliveryAttemptId) -> MonitorAlertsResult<MonitorAlertDetail>) {
        val current = mutableState.value as? MonitorAlertsUiState.Alert ?: return
        if (
            current.action != MonitorAlertAction.Idle ||
            current.detail.acknowledgement?.hasFinalResponse() == true ||
            current.notice == MonitorAlertNotice.Confirmed ||
            current.notice == MonitorAlertNotice.Declined
        ) return
        mutableState.value = current.copy(action = MonitorAlertAction.Submitting, notice = null)
        viewModelScope.launch {
            when (val result = operation(current.attemptId)) {
                is MonitorAlertsResult.Success -> {
                    mutableState.value = MonitorAlertsUiState.Alert(current.attemptId, result.value, notice = if (result.value.acknowledgement?.declinedAtUtc != null) MonitorAlertNotice.Declined else MonitorAlertNotice.Confirmed)
                    mutableSuccessfulActionRevision.value += 1
                    loadStatus(current.attemptId)
                }
                is MonitorAlertsResult.Failure -> mutableState.value = current.copy(
                    action = MonitorAlertAction.Idle,
                    notice = MonitorAlertNotice.NonBlockingError("No pudimos registrar la respuesta. Intenta nuevamente.")
                )
            }
        }
    }

    private fun loadStatus(attemptId: NotificationDeliveryAttemptId) {
        viewModelScope.launch {
            when (val result = repository.getStatus(attemptId)) {
                is MonitorAlertsResult.Success -> {
                    val current = mutableState.value as? MonitorAlertsUiState.Alert ?: return@launch
                    if (current.attemptId == attemptId) mutableState.value = current.copy(status = result.value)
                }
                is MonitorAlertsResult.Failure -> Unit
            }
        }
    }

    private fun consumePendingAlert(notificationDeliveryAttemptId: String?) {
        if (!isMonitorSession()) {
            mutableState.value = MonitorAlertsUiState.Ready
            return
        }
        val attemptId = notificationDeliveryAttemptId?.takeIf { it.isNotBlank() }
            ?: run {
                if (lastConsumedFcmAttemptId == null) mutableState.value = MonitorAlertsUiState.Ready
                return
            }
        if (attemptId == lastConsumedFcmAttemptId) return
        lastConsumedFcmAttemptId = attemptId
        mutableConsumedFcmAttemptId.value = attemptId
        load(NotificationDeliveryAttemptId(attemptId))
    }

    private fun String?.userMessage(): String = takeUnless { it.isNullOrBlank() } ?: "No pudimos cargar la alerta."
    private fun com.example.sos_segundoplano.domain.monitor.MonitorAlertAcknowledgement.hasFinalResponse(): Boolean = acknowledgedAtUtc != null || declinedAtUtc != null
}
