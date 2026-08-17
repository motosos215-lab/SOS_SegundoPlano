package com.example.sos_segundoplano.features.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sos_segundoplano.domain.history.RiderHistoryRepository
import com.example.sos_segundoplano.domain.history.RiderHistoryResult
import com.example.sos_segundoplano.domain.history.RiderIncidentHistoryItem
import com.example.sos_segundoplano.domain.history.RiderTripHistoryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RiderHistoryUiState<out T> {
    data object Loading : RiderHistoryUiState<Nothing>
    data object Empty : RiderHistoryUiState<Nothing>
    data class Content<T>(val items: List<T>, val refreshing: Boolean = false, val error: String? = null) : RiderHistoryUiState<T>
    data class Error(val message: String) : RiderHistoryUiState<Nothing>
}

class RiderHistoryViewModel(private val repository: RiderHistoryRepository) : ViewModel() {
    private val mutableTrips = MutableStateFlow<RiderHistoryUiState<RiderTripHistoryItem>>(RiderHistoryUiState.Loading)
    private val mutableIncidents = MutableStateFlow<RiderHistoryUiState<RiderIncidentHistoryItem>>(RiderHistoryUiState.Loading)
    val trips: StateFlow<RiderHistoryUiState<RiderTripHistoryItem>> = mutableTrips.asStateFlow()
    val incidents: StateFlow<RiderHistoryUiState<RiderIncidentHistoryItem>> = mutableIncidents.asStateFlow()

    init { refreshTrips(); refreshIncidents() }

    fun refreshTrips() = refresh(mutableTrips, repository::trips)
    fun refreshIncidents() = refresh(mutableIncidents, repository::incidents, isIncidentHistory = true)

    private fun <T> refresh(
        target: MutableStateFlow<RiderHistoryUiState<T>>,
        request: suspend () -> RiderHistoryResult<List<T>>,
        isIncidentHistory: Boolean = false
    ) {
        val previous = target.value as? RiderHistoryUiState.Content<T>
        target.value = previous?.copy(refreshing = true, error = null) ?: RiderHistoryUiState.Loading
        if (isIncidentHistory) HistoryDiagnostics.debug("event=rider_history_refresh_started")
        viewModelScope.launch {
            val result = try {
                request()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RiderHistoryResult.Failure("history_unavailable")
            }
            target.value = when (result) {
                is RiderHistoryResult.Success -> if (result.value.isEmpty()) {
                    if (isIncidentHistory) HistoryDiagnostics.debug("event=rider_history_viewmodel_result result=empty")
                    RiderHistoryUiState.Empty
                } else {
                    if (isIncidentHistory) HistoryDiagnostics.debug("event=rider_history_viewmodel_result result=success count=${result.value.size}")
                    RiderHistoryUiState.Content(result.value)
                }
                is RiderHistoryResult.Failure -> {
                    if (isIncidentHistory) HistoryDiagnostics.debug("event=rider_history_viewmodel_result result=error type=${HistoryDiagnostics.safeType(result.message)}")
                    previous?.copy(refreshing = false, error = result.message ?: "No pudimos actualizar.") ?: RiderHistoryUiState.Error(result.message ?: "No pudimos cargar el historial.")
                }
            }
            if (isIncidentHistory) HistoryDiagnostics.debug(
                when (val state = target.value) {
                    is RiderHistoryUiState.Content -> "event=rider_history_ui_state state=content count=${state.items.size}"
                    RiderHistoryUiState.Empty -> "event=rider_history_ui_state state=empty"
                    is RiderHistoryUiState.Error -> "event=rider_history_ui_state state=error"
                    RiderHistoryUiState.Loading -> "event=rider_history_ui_state state=loading"
                }
            )
        }
    }
}
