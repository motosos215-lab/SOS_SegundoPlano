package com.example.sos_segundoplano.features.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sos_segundoplano.domain.monitor.MonitorAlertAcknowledgement
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsRepository
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsResult
import com.example.sos_segundoplano.features.history.HistoryDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MonitorAlertHistoryUiState {
    data object Loading : MonitorAlertHistoryUiState
    data object Empty : MonitorAlertHistoryUiState
    data class Content(val alerts: List<MonitorAlertAcknowledgement>, val refreshing: Boolean = false, val error: String? = null) : MonitorAlertHistoryUiState
    data class Error(val message: String) : MonitorAlertHistoryUiState
}

class MonitorAlertHistoryViewModel(
    private val repository: MonitorAlertsRepository,
    private val isMonitorSession: () -> Boolean = { true }
) : ViewModel() {
    private val mutableState = MutableStateFlow<MonitorAlertHistoryUiState>(MonitorAlertHistoryUiState.Loading)
    val state: StateFlow<MonitorAlertHistoryUiState> = mutableState.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (!isMonitorSession()) return
        val previous = mutableState.value as? MonitorAlertHistoryUiState.Content
        mutableState.value = previous?.copy(refreshing = true, error = null) ?: MonitorAlertHistoryUiState.Loading
        HistoryDiagnostics.debug("event=monitor_history_refresh_started")
        viewModelScope.launch {
            when (val result = repository.listAlerts()) {
                is MonitorAlertsResult.Success -> {
                    mutableState.value = if (result.value.isEmpty()) {
                        HistoryDiagnostics.debug("event=monitor_history_viewmodel_result result=empty")
                        MonitorAlertHistoryUiState.Empty
                    } else {
                        HistoryDiagnostics.debug("event=monitor_history_viewmodel_result result=success count=${result.value.size}")
                        MonitorAlertHistoryUiState.Content(result.value)
                    }
                }
                is MonitorAlertsResult.Failure -> {
                    HistoryDiagnostics.debug("event=monitor_history_viewmodel_result result=error type=${HistoryDiagnostics.safeType(result.message)}")
                    mutableState.value = previous?.copy(refreshing = false, error = result.message ?: "No pudimos actualizar el historial.") ?: MonitorAlertHistoryUiState.Error(result.message ?: "No pudimos cargar el historial.")
                }
            }
            HistoryDiagnostics.debug(
                when (val state = mutableState.value) {
                    is MonitorAlertHistoryUiState.Content -> "event=monitor_history_ui_state state=content count=${state.alerts.size}"
                    MonitorAlertHistoryUiState.Empty -> "event=monitor_history_ui_state state=empty"
                    is MonitorAlertHistoryUiState.Error -> "event=monitor_history_ui_state state=error"
                    MonitorAlertHistoryUiState.Loading -> "event=monitor_history_ui_state state=loading"
                }
            )
        }
    }
}
