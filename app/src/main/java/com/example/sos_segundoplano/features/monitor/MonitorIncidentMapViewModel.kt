package com.example.sos_segundoplano.features.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sos_segundoplano.data.remote.incident.CurrentManualSosLocationProvider
import com.example.sos_segundoplano.domain.routing.MonitorIncidentRoute
import com.example.sos_segundoplano.domain.routing.MonitorIncidentRouteResult
import com.example.sos_segundoplano.domain.routing.MonitorIncidentRoutingRepository
import com.example.sos_segundoplano.ui.maps.MotoMapPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MonitorIncidentMapUiState {
    data object Idle : MonitorIncidentMapUiState
    data object LocatingMonitor : MonitorIncidentMapUiState
    data class LoadingRoute(
        val monitorPoint: MotoMapPoint,
        val incidentPoint: MotoMapPoint
    ) : MonitorIncidentMapUiState
    data class Ready(
        val monitorPoint: MotoMapPoint,
        val incidentPoint: MotoMapPoint,
        val route: MonitorIncidentRoute
    ) : MonitorIncidentMapUiState
    data class RouteUnavailable(
        val monitorPoint: MotoMapPoint,
        val incidentPoint: MotoMapPoint,
        val message: String
    ) : MonitorIncidentMapUiState
    data class LocationUnavailable(
        val incidentPoint: MotoMapPoint,
        val message: String
    ) : MonitorIncidentMapUiState
}

class MonitorIncidentMapViewModel(
    private val currentLocationProvider: CurrentManualSosLocationProvider,
    private val routingRepository: MonitorIncidentRoutingRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow<MonitorIncidentMapUiState>(MonitorIncidentMapUiState.Idle)
    val state: StateFlow<MonitorIncidentMapUiState> = mutableState.asStateFlow()

    private var requestRevision = 0L

    fun load(incidentPoint: MotoMapPoint) {
        if (!incidentPoint.isUsable()) return
        val revision = ++requestRevision
        mutableState.value = MonitorIncidentMapUiState.LocatingMonitor
        viewModelScope.launch {
            val sample = currentLocationProvider.currentLocation()
            if (revision != requestRevision) return@launch
            val monitorPoint = sample?.let { MotoMapPoint(it.latitude, it.longitude) }?.takeIf { it.isUsable() }
            if (monitorPoint == null) {
                mutableState.value = MonitorIncidentMapUiState.LocationUnavailable(
                    incidentPoint = incidentPoint,
                    message = "No pudimos obtener tu ubicación actual. Revisa el permiso de ubicación y que el GPS esté encendido."
                )
                return@launch
            }
            mutableState.value = MonitorIncidentMapUiState.LoadingRoute(monitorPoint, incidentPoint)
            when (val routeResult = routingRepository.route(monitorPoint, incidentPoint)) {
                is MonitorIncidentRouteResult.Success -> {
                    if (revision == requestRevision) {
                        mutableState.value = MonitorIncidentMapUiState.Ready(monitorPoint, incidentPoint, routeResult.route)
                    }
                }
                is MonitorIncidentRouteResult.Failure -> {
                    if (revision == requestRevision) {
                        mutableState.value = MonitorIncidentMapUiState.RouteUnavailable(
                            monitorPoint = monitorPoint,
                            incidentPoint = incidentPoint,
                            message = routeResult.message
                        )
                    }
                }
            }
        }
    }

    fun clear() {
        requestRevision++
        mutableState.value = MonitorIncidentMapUiState.Idle
    }

    private fun MotoMapPoint.isUsable(): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)
}
