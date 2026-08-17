package com.example.sos_segundoplano.features.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sos_segundoplano.domain.history.RiderIncidentHistoryItem
import com.example.sos_segundoplano.domain.history.RiderTripHistoryItem
import com.example.sos_segundoplano.ui.components.MotoBottomBar
import com.example.sos_segundoplano.ui.components.MotoBottomBarItem
import com.example.sos_segundoplano.ui.format.DisplayFormatters
import com.example.sos_segundoplano.ui.format.incidentCauseLabel
import com.example.sos_segundoplano.ui.format.tripStatusLabel
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoSuccessSoft
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary

@Composable
fun RiderHistoryScreen(
    viewModel: RiderHistoryViewModel,
    onBack: () -> Unit,
    onHomeSelected: () -> Unit = onBack,
    onSosSelected: () -> Unit = {},
    onProfileSelected: () -> Unit = {}
) {
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    val incidents by viewModel.incidents.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(RiderHistorySection.Trips) }
    var selectedTrip by remember { mutableStateOf<RiderTripHistoryItem?>(null) }
    selectedTrip?.let { trip ->
        RiderTripDetailScreen(item = trip, onBack = { selectedTrip = null })
        return
    }
    val refresh = if (section == RiderHistorySection.Trips) viewModel::refreshTrips else viewModel::refreshIncidents

    Scaffold(
        modifier = Modifier.onGloballyPositioned { coordinates ->
            HistoryDiagnostics.debug(
                "event=rider_history_layout node=root width_px=${coordinates.size.width} height_px=${coordinates.size.height}"
            )
        },
        containerColor = MotoBackground,
        bottomBar = {
            MotoBottomBar(
                selectedItem = MotoBottomBarItem.Trips,
                enabledItems = setOf(
                    MotoBottomBarItem.Home,
                    MotoBottomBarItem.Trips,
                    MotoBottomBarItem.Sos,
                    MotoBottomBarItem.Profile
                ),
                onHomeSelected = onHomeSelected,
                onTripsSelected = {},
                onSosSelected = onSosSelected,
                onProfileSelected = onProfileSelected
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .onGloballyPositioned { coordinates ->
                    HistoryDiagnostics.debug(
                        "event=rider_history_layout node=main_column width_px=${coordinates.size.width} height_px=${coordinates.size.height}"
                    )
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Historial de viajes", color = MotoPrimaryDark, fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = {
                        if (section == RiderHistorySection.Incidents) {
                            HistoryDiagnostics.debug("event=rider_history_refresh_trigger source=manual")
                        }
                        refresh()
                    },
                    modifier = Modifier.semantics { contentDescription = "Actualizar historial" }
                ) { Text("↻", color = MotoPrimaryBlue, fontWeight = FontWeight.Bold) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
                HistorySectionButton(
                    label = "Viajes",
                    selected = section == RiderHistorySection.Trips,
                    onClick = { section = RiderHistorySection.Trips }
                )
                Spacer(Modifier.width(8.dp))
                HistorySectionButton(
                    label = "Incidentes",
                    selected = section == RiderHistorySection.Incidents,
                    onClick = {
                        HistoryDiagnostics.debug("event=rider_history_tab_selected tab=incidents")
                        section = RiderHistorySection.Incidents
                    }
                )
            }
            if (section == RiderHistorySection.Trips) {
                TripContent(
                    state = trips,
                    retry = viewModel::refreshTrips,
                    onTripSelected = { selectedTrip = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            HistoryDiagnostics.debug(
                                "event=rider_history_layout node=content_container width_px=${coordinates.size.width} height_px=${coordinates.size.height}"
                            )
                        }
                )
            } else {
                IncidentContent(
                    state = incidents,
                    retry = viewModel::refreshIncidents,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            HistoryDiagnostics.debug(
                                "event=rider_history_layout node=content_container width_px=${coordinates.size.width} height_px=${coordinates.size.height}"
                            )
                        }
                )
            }
        }
    }
}

private enum class RiderHistorySection { Trips, Incidents }

@Composable
private fun HistorySectionButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = MotoPrimaryBlue)
        ) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label, color = MotoTextSecondary) }
    }
}

@Composable
private fun TripContent(
    state: RiderHistoryUiState<RiderTripHistoryItem>,
    retry: () -> Unit,
    onTripSelected: (RiderTripHistoryItem) -> Unit,
    modifier: Modifier = Modifier
) = when (state) {
    RiderHistoryUiState.Loading -> HistoryLoading()
    RiderHistoryUiState.Empty -> HistoryEmpty()
    is RiderHistoryUiState.Error -> HistoryError(state.message, retry)
    is RiderHistoryUiState.Content -> LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(state.items) { TripHistoryCard(it, onClick = { onTripSelected(it) }) }
        state.error?.let { item { Text(it, color = MotoTextSecondary) } }
    }
}

@Composable
private fun TripHistoryCard(item: RiderTripHistoryItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("trip_history_card")
            .semantics { contentDescription = "Ver detalle del viaje" }
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MotoSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    DisplayFormatters.dateTime(item.startedAtUtc) ?: "Fecha no disponible",
                    color = MotoPrimaryDark,
                    fontWeight = FontWeight.SemiBold
                )
                TripStatusChip(item.status)
            }
            val duration = DisplayFormatters.duration(item.startedAtUtc, item.finishedAtUtc)
            Text(
                duration ?: if (item.status == "Active" && item.finishedAtUtc == null) "En curso" else "No disponible",
                color = MotoPrimaryDark,
                fontWeight = FontWeight.SemiBold
            )
            TripRoutePreview(item.startLocation, item.endLocation, Modifier.fillMaxWidth())
            Text("Ver detalle  ›", modifier = Modifier.align(androidx.compose.ui.Alignment.End), color = MotoPrimaryBlue)
        }
    }
}

@Composable
private fun TripStatusChip(status: String?) {
    val label = tripStatusLabel(status).ifBlank { "Estado no disponible" }
    val color = if (status == "Finished") MotoSuccess else MotoPrimaryBlue
    AssistChip(
        onClick = {},
        label = { Text(label, color = color) },
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
            containerColor = if (status == "Finished") MotoSuccessSoft else MotoBackground
        ),
        border = BorderStroke(1.dp, color)
    )
}

internal fun buildGeoUri(latitude: Double, longitude: Double): android.net.Uri =
    android.net.Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")

@Composable
private fun IncidentContent(
    state: RiderHistoryUiState<RiderIncidentHistoryItem>,
    retry: () -> Unit,
    modifier: Modifier = Modifier
) = when (state) {
    RiderHistoryUiState.Loading -> HistoryLoading()
    RiderHistoryUiState.Empty -> HistoryEmpty()
    is RiderHistoryUiState.Error -> HistoryError(state.message, retry)
    is RiderHistoryUiState.Content -> LazyColumn(
        modifier = modifier.onGloballyPositioned { coordinates ->
            HistoryDiagnostics.debug(
                "event=rider_history_layout node=incident_list width_px=${coordinates.size.width} height_px=${coordinates.size.height}"
            )
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(state.items) { index, item ->
            if (index == 0) {
                LaunchedEffect(Unit) {
                    HistoryDiagnostics.debug("event=rider_history_first_item_composed")
                }
                IncidentHistoryCard(
                    item = item,
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        val rootPosition = coordinates.positionInRoot()
                        val windowPosition = coordinates.positionInWindow()
                        HistoryDiagnostics.debug(
                            "event=rider_history_layout node=first_incident_card width_px=${coordinates.size.width} height_px=${coordinates.size.height}"
                        )
                        HistoryDiagnostics.debug(
                            "event=rider_history_first_item_position x_root=${rootPosition.x} y_root=${rootPosition.y} x_window=${windowPosition.x} y_window=${windowPosition.y} width_px=${coordinates.size.width} height_px=${coordinates.size.height} is_attached=${coordinates.isAttached}"
                        )
                    }
                )
            } else {
                IncidentHistoryCard(item)
            }
        }
        state.error?.let { item { Text(it, color = MotoTextSecondary) } }
    }
}

@Composable
private fun IncidentHistoryCard(
    item: RiderIncidentHistoryItem,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().testTag("incident_history_card"),
        colors = CardDefaults.cardColors(containerColor = MotoSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(incidentCauseLabel(item.cause), color = MotoPrimaryDark, fontWeight = FontWeight.SemiBold)
            Text(DisplayFormatters.dateTime(item.occurredAtUtc) ?: "Fecha no disponible", color = MotoTextSecondary)
            item.riskLevel?.let { Text("Riesgo: $it", color = MotoTextSecondary) }
            item.status?.let { Text("Estado: $it", color = MotoTextSecondary) }
        }
    }
}

@Composable
private fun HistoryLoading() = Column(
    Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.Center
) { CircularProgressIndicator() }

@Composable
private fun HistoryEmpty() = Text(
    "No hay registros todavía.",
    modifier = Modifier.padding(top = 28.dp),
    color = MotoTextSecondary
)

@Composable
private fun HistoryError(message: String, retry: () -> Unit) = Column(Modifier.padding(top = 28.dp)) {
    Text(message, color = MotoTextSecondary)
    Button(onClick = retry) { Text("Reintentar") }
}
