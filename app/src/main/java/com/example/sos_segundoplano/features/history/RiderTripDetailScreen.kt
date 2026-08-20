package com.example.sos_segundoplano.features.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.domain.history.RiderTripHistoryItem
import com.example.sos_segundoplano.domain.history.RiderTripHistoryLocation
import com.example.sos_segundoplano.domain.history.RiderTripRoutePoint
import com.example.sos_segundoplano.ui.components.MotoBottomBar
import com.example.sos_segundoplano.ui.components.MotoBottomBarItem
import com.example.sos_segundoplano.ui.format.DisplayFormatters
import com.example.sos_segundoplano.ui.format.tripStatusLabel
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary
import com.example.sos_segundoplano.ui.maps.MotoMapPoint
import com.example.sos_segundoplano.ui.maps.MotoRouteMap

@Composable
fun RiderTripDetailScreen(
    item: RiderTripHistoryItem,
    onBack: () -> Unit,
    routeState: RiderTripRouteUiState? = null,
    onRetryRoute: () -> Unit = {},
    onHomeSelected: () -> Unit = onBack,
    onTripsSelected: () -> Unit = onBack,
    onSosSelected: () -> Unit = {},
    onMapSelected: () -> Unit = {},
    onProfileSelected: () -> Unit = {}
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val routePoints = (routeState as? RiderTripRouteUiState.Content)?.points.orEmpty()
    val startedAt = DisplayFormatters.dateTime(item.startedAtUtc)
    val finishedAt = DisplayFormatters.dateTime(item.finishedAtUtc)
    Scaffold(
        containerColor = MotoBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.semantics { contentDescription = "Volver al historial" }
                ) {
                    Text("←")
                }
                Text("Detalle del viaje", color = MotoPrimaryDark, fontWeight = FontWeight.Bold)
            }
        },
        bottomBar = {
            MotoBottomBar(
                selectedItem = MotoBottomBarItem.Trips,
                enabledItems = setOf(
                    MotoBottomBarItem.Home,
                    MotoBottomBarItem.Trips,
                    MotoBottomBarItem.Sos,
                    MotoBottomBarItem.Map,
                    MotoBottomBarItem.Profile
                ),
                onHomeSelected = onHomeSelected,
                onTripsSelected = onTripsSelected,
                onSosSelected = onSosSelected,
                onMapSelected = onMapSelected,
                onProfileSelected = onProfileSelected
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .testTag("rider_trip_detail_list"),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MotoSurface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    startedAt?.substringBefore(' ') ?: "Fecha no disponible",
                                    color = MotoPrimaryDark,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    DisplayFormatters.duration(item.startedAtUtc, item.finishedAtUtc)
                                        ?: if (item.status == "Active") "En curso" else "Duración no disponible",
                                    color = MotoTextSecondary
                                )
                            }
                            DetailStatus(item.status)
                        }
                        DetailRow("Inicio", startedAt?.substringAfter(' ') ?: "No disponible")
                        DetailRow(
                            "Fin",
                            finishedAt?.substringAfter(' ')
                                ?: if (item.status == "Active") "En curso" else "No disponible"
                        )
                        if (routePoints.isNotEmpty()) {
                            DetailRow("Puntos GPS", routePoints.size.toString())
                        }
                        if (routePoints.size >= 2) {
                            DetailRow("Distancia GPS", formatRouteDistance(routeDistanceMeters(routePoints)))
                        }
                    }
                }
            }
            item {
                when (routeState) {
                    is RiderTripRouteUiState.Content -> RealTripRouteMap(routeState.points)
                    RiderTripRouteUiState.Loading -> TripRoutePreview(
                        item.startLocation,
                        item.endLocation,
                        Modifier.fillMaxWidth(),
                        large = true,
                        isLoading = true
                    )
                    is RiderTripRouteUiState.Error -> RouteUnavailableCard(
                        "No pudimos cargar el recorrido real.",
                        onRetryRoute
                    )
                    RiderTripRouteUiState.Empty -> RouteUnavailableCard(
                        "El viaje todavía no tiene puntos GPS sincronizados.",
                        onRetryRoute
                    )
                    null -> TripRoutePreview(item.startLocation, item.endLocation, Modifier.fillMaxWidth(), large = true)
                }
            }
            item { DetailLocation("Ubicación inicial", item.startLocation) { location -> openLocationInMap(context, location) } }
            item { DetailLocation("Ubicación final", item.endLocation) { location -> openLocationInMap(context, location) } }
        }
    }
}

@Composable
private fun RealTripRouteMap(points: List<RiderTripRoutePoint>) {
    val context = LocalContext.current
    val ordered = remember(points) { points.sortedBy { it.sequence }.filter(::isValidRoutePoint) }
    var recenterToken by remember { mutableStateOf(0) }
    if (ordered.isEmpty()) {
        RouteUnavailableCard("El viaje todavía no tiene puntos GPS sincronizados.", {})
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MotoSurface)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Recorrido real", color = MotoPrimaryDark, fontWeight = FontWeight.Bold)
                Text(
                    "${ordered.size} puntos GPS • ${formatRouteDistance(routeDistanceMeters(ordered))}",
                    color = MotoTextSecondary
                )
                Text(
                    "MapLibre + OpenFreeMap • ruta basada sólo en los puntos guardados por MotoSOS",
                    color = MotoTextSecondary
                )
            }
            MotoRouteMap(
                points = ordered.map { MotoMapPoint(it.latitude, it.longitude) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                recenterToken = recenterToken
            )
            Text(
                text = "Arrastra para mover el mapa • Pellizca para acercar o alejar",
                color = MotoTextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            OutlinedButton(
                onClick = { recenterToken += 1 },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text("Centrar recorrido")
            }
            OutlinedButton(
                onClick = { openRealRouteInGoogleMaps(context, ordered) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text("Abrir recorrido en Google Maps")
            }
            Text(
                text = "El mapa integrado no necesita una API key. Google Maps se usa sólo como navegación externa.",
                color = MotoTextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun RouteUnavailableCard(message: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MotoSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recorrido real", color = MotoPrimaryDark, fontWeight = FontWeight.SemiBold)
            Text(message, color = MotoTextSecondary)
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Reintentar ruta")
            }
        }
    }
}

@Composable
private fun DetailStatus(status: String?) {
    AssistChip(onClick = {}, label = { Text(tripStatusLabel(status).ifBlank { "Estado no disponible" }) })
}

@Composable
private fun DetailRow(label: String, value: String) = Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(label, color = MotoTextSecondary)
    Text(value, color = MotoPrimaryDark, fontWeight = FontWeight.Medium)
}

@Composable
private fun DetailLocation(
    label: String,
    location: RiderTripHistoryLocation?,
    onOpenMap: (RiderTripHistoryLocation) -> Unit
) = Card(
    colors = CardDefaults.cardColors(containerColor = MotoSurface),
    modifier = Modifier.fillMaxWidth()
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MotoPrimaryDark, fontWeight = FontWeight.SemiBold)
        if (location == null) {
            Text("No disponible", color = MotoTextSecondary)
        } else {
            Text("%.5f, %.5f".format(location.latitude, location.longitude), color = MotoTextSecondary)
            OutlinedButton(onClick = { onOpenMap(location) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (label == "Ubicación inicial") "Abrir inicio en Google Maps" else "Abrir final en Google Maps")
            }
        }
    }
}

private fun openLocationInMap(context: Context, location: RiderTripHistoryLocation) {
    val mapsIntent = Intent(Intent.ACTION_VIEW, buildGeoUri(location.latitude, location.longitude))
        .setPackage("com.google.android.apps.maps")
    if (mapsIntent.resolveActivity(context.packageManager) != null) {
        context.startActivity(mapsIntent)
    } else {
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW, buildGeoUri(location.latitude, location.longitude)),
                    "Ver en mapa"
                )
            )
        }.onFailure {
            Toast.makeText(context, "No hay una aplicación de mapas disponible.", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun openRealRouteInGoogleMaps(context: Context, points: List<RiderTripRoutePoint>) {
    val ordered = points.filter(::isValidRoutePoint).sortedBy { it.sequence }
    if (ordered.size < 2) {
        Toast.makeText(context, "No hay suficientes puntos para abrir el recorrido.", Toast.LENGTH_SHORT).show()
        return
    }
    val origin = "${ordered.first().latitude},${ordered.first().longitude}"
    val destination = "${ordered.last().latitude},${ordered.last().longitude}"
    val waypoints = sampledWaypoints(ordered)
        .joinToString("|") { "${it.latitude},${it.longitude}" }

    val uri = Uri.parse(
        buildString {
            append("https://www.google.com/maps/dir/?api=1")
            append("&origin=${Uri.encode(origin)}")
            append("&destination=${Uri.encode(destination)}")
            append("&travelmode=driving")
            if (waypoints.isNotBlank()) append("&waypoints=${Uri.encode(waypoints)}")
        }
    )
    val mapsIntent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
    when {
        mapsIntent.resolveActivity(context.packageManager) != null -> context.startActivity(mapsIntent)
        fallbackIntent.resolveActivity(context.packageManager) != null -> context.startActivity(fallbackIntent)
        else -> Toast.makeText(context, "No hay una aplicación de mapas disponible.", Toast.LENGTH_SHORT).show()
    }
}

private fun sampledWaypoints(points: List<RiderTripRoutePoint>, maxWaypoints: Int = 6): List<RiderTripRoutePoint> {
    val innerPoints = points.drop(1).dropLast(1)
    if (innerPoints.size <= maxWaypoints) return innerPoints
    val step = innerPoints.size.toDouble() / maxWaypoints.toDouble()
    return List(maxWaypoints) { index ->
        innerPoints[(index * step).toInt().coerceIn(innerPoints.indices)]
    }
}

private fun isValidRoutePoint(point: RiderTripRoutePoint): Boolean =
    point.latitude.isFinite() && point.longitude.isFinite() &&
        point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0
