package com.example.sos_segundoplano.features.history

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.domain.history.RiderTripHistoryItem
import com.example.sos_segundoplano.domain.history.RiderTripHistoryLocation
import com.example.sos_segundoplano.ui.format.DisplayFormatters
import com.example.sos_segundoplano.ui.format.tripStatusLabel
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary

@Composable
fun RiderTripDetailScreen(item: RiderTripHistoryItem, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    Scaffold(
        containerColor = MotoBackground,
        topBar = {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Volver al historial" }) { Text("←") }
                Text("Detalle del viaje", color = MotoPrimaryDark, fontWeight = FontWeight.Bold)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).testTag("rider_trip_detail_list"),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MotoSurface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailStatus(item.status)
                        DetailRow("Fecha", DisplayFormatters.dateTime(item.startedAtUtc)?.substringBefore(' ') ?: "No disponible")
                        DetailRow("Inicio", DisplayFormatters.dateTime(item.startedAtUtc)?.substringAfter(' ') ?: "No disponible")
                        DetailRow("Fin", DisplayFormatters.dateTime(item.finishedAtUtc)?.substringAfter(' ') ?: if (item.status == "Active") "En curso" else "No disponible")
                        DetailRow("Duración", DisplayFormatters.duration(item.startedAtUtc, item.finishedAtUtc) ?: if (item.status == "Active") "En curso" else "No disponible")
                    }
                }
            }
            item { TripRoutePreview(item.startLocation, item.endLocation, Modifier.fillMaxWidth(), large = true) }
            item { DetailLocation("Ubicación inicial", item.startLocation) { location -> openLocationInMap(context, location) } }
            item { DetailLocation("Ubicación final", item.endLocation) { location -> openLocationInMap(context, location) } }
        }
    }
}

@Composable
private fun DetailStatus(status: String?) {
    AssistChip(onClick = {}, label = { Text(tripStatusLabel(status).ifBlank { "Estado no disponible" }) })
}

@Composable
private fun DetailRow(label: String, value: String) = Column {
    Text(label, color = MotoTextSecondary)
    Text(value, color = MotoPrimaryDark, fontWeight = FontWeight.Medium)
}

@Composable
private fun DetailLocation(
    label: String,
    location: RiderTripHistoryLocation?,
    onOpenMap: (RiderTripHistoryLocation) -> Unit
) = Card(colors = CardDefaults.cardColors(containerColor = MotoSurface)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MotoPrimaryDark, fontWeight = FontWeight.SemiBold)
        if (location == null) {
            Text("No disponible", color = MotoTextSecondary)
        } else {
            Text("%.5f, %.5f".format(location.latitude, location.longitude), color = MotoTextSecondary)
            OutlinedButton(onClick = { onOpenMap(location) }) {
                Text(if (label == "Ubicación inicial") "Abrir inicio en mapa" else "Abrir destino en mapa")
            }
        }
    }
}

private fun openLocationInMap(context: android.content.Context, location: RiderTripHistoryLocation) {
    runCatching {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, buildGeoUri(location.latitude, location.longitude)), "Ver en mapa"))
    }.onFailure {
        Toast.makeText(context, "No hay una aplicación de mapas disponible.", Toast.LENGTH_SHORT).show()
    }
}
