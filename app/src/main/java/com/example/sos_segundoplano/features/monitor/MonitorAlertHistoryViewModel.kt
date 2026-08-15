package com.example.sos_segundoplano.features.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sos_segundoplano.domain.monitor.MonitorAlertAcknowledgement
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsRepository
import com.example.sos_segundoplano.domain.monitor.MonitorAlertsResult
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
        viewModelScope.launch {
            when (val result = repository.listAlerts()) {
                is MonitorAlertsResult.Success -> mutableState.value = if (result.value.isEmpty()) MonitorAlertHistoryUiState.Empty else MonitorAlertHistoryUiState.Content(result.value)
                is MonitorAlertsResult.Failure -> mutableState.value = previous?.copy(refreshing = false, error = result.message ?: "No pudimos actualizar el historial.") ?: MonitorAlertHistoryUiState.Error(result.message ?: "No pudimos cargar el historial.")
            }
        }
    }
}
