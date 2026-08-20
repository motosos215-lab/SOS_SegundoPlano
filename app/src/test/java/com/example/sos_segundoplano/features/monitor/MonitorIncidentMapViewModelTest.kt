package com.example.sos_segundoplano.features.monitor

import com.example.sos_segundoplano.data.remote.incident.CurrentManualSosLocationProvider
import com.example.sos_segundoplano.domain.routing.MonitorIncidentRoute
import com.example.sos_segundoplano.domain.routing.MonitorIncidentRouteResult
import com.example.sos_segundoplano.domain.routing.MonitorIncidentRoutingRepository
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.ui.maps.MotoMapPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorIncidentMapViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun validMonitorLocationAndRouteProduceReadyState() = runTest {
        val origin = MotoMapPoint(19.4300, -99.1400)
        val destination = MotoMapPoint(19.4326, -99.1332)
        val expectedRoute = MonitorIncidentRoute(
            points = listOf(origin, MotoMapPoint(19.4310, -99.1360), destination),
            distanceMeters = 1200.0,
            durationSeconds = 300.0
        )
        val viewModel = MonitorIncidentMapViewModel(
            currentLocationProvider = CurrentManualSosLocationProvider { sample(origin) },
            routingRepository = MonitorIncidentRoutingRepository { _, _ -> MonitorIncidentRouteResult.Success(expectedRoute) }
        )

        viewModel.load(destination)

        val state = viewModel.state.value as MonitorIncidentMapUiState.Ready
        assertEquals(origin, state.monitorPoint)
        assertEquals(destination, state.incidentPoint)
        assertEquals(expectedRoute, state.route)
    }

    @Test fun missingMonitorLocationKeepsIncidentVisibleWithoutInventingRoute() = runTest {
        val destination = MotoMapPoint(19.4326, -99.1332)
        var routingCalls = 0
        val viewModel = MonitorIncidentMapViewModel(
            currentLocationProvider = CurrentManualSosLocationProvider { null },
            routingRepository = MonitorIncidentRoutingRepository { _, _ ->
                routingCalls++
                MonitorIncidentRouteResult.Failure("unused")
            }
        )

        viewModel.load(destination)

        val state = viewModel.state.value
        assertTrue(state is MonitorIncidentMapUiState.LocationUnavailable)
        assertEquals(destination, (state as MonitorIncidentMapUiState.LocationUnavailable).incidentPoint)
        assertEquals(0, routingCalls)
    }

    @Test fun routingFailureKeepsBothRealLocationsWithoutStraightLineFallback() = runTest {
        val origin = MotoMapPoint(19.4300, -99.1400)
        val destination = MotoMapPoint(19.4326, -99.1332)
        val viewModel = MonitorIncidentMapViewModel(
            currentLocationProvider = CurrentManualSosLocationProvider { sample(origin) },
            routingRepository = MonitorIncidentRoutingRepository { _, _ ->
                MonitorIncidentRouteResult.Failure("Ruta temporalmente no disponible")
            }
        )

        viewModel.load(destination)

        val state = viewModel.state.value as MonitorIncidentMapUiState.RouteUnavailable
        assertEquals(origin, state.monitorPoint)
        assertEquals(destination, state.incidentPoint)
        assertEquals("Ruta temporalmente no disponible", state.message)
    }

    private fun sample(point: MotoMapPoint) = LocationSample(
        latitude = point.latitude,
        longitude = point.longitude,
        accuracyMeters = 8f,
        timestampMillis = System.currentTimeMillis(),
        provider = "gps",
        isMock = false
    )
}
