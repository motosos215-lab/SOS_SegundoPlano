package com.example.sos_segundoplano.domain.routing

import com.example.sos_segundoplano.ui.maps.MotoMapPoint

data class MonitorIncidentRoute(
    val points: List<MotoMapPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double
)

sealed interface MonitorIncidentRouteResult {
    data class Success(val route: MonitorIncidentRoute) : MonitorIncidentRouteResult
    data class Failure(val message: String) : MonitorIncidentRouteResult
}

fun interface MonitorIncidentRoutingRepository {
    suspend fun route(origin: MotoMapPoint, destination: MotoMapPoint): MonitorIncidentRouteResult
}
