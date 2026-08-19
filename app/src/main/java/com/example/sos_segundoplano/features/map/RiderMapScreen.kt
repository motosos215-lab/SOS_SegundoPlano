package com.example.sos_segundoplano.features.map

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.TripSignalSnapshot
import com.example.sos_segundoplano.ui.components.MotoBottomBar
import com.example.sos_segundoplano.ui.components.MotoBottomBarItem
import com.example.sos_segundoplano.ui.components.MotoTopBar
import com.example.sos_segundoplano.ui.components.MotoTopBarIcon
import com.example.sos_segundoplano.ui.maps.MotoBaseMap
import com.example.sos_segundoplano.ui.maps.MotoMapPoint
import com.example.sos_segundoplano.ui.maps.MotoSinglePointMap
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary

@Composable
fun RiderMapScreen(
    snapshot: TripSignalSnapshot,
    tripActive: Boolean,
    onHomeSelected: () -> Unit,
    onTripsSelected: () -> Unit,
    onSosSelected: () -> Unit,
    onProfileSelected: () -> Unit
) {
    val context = LocalContext.current
    var destination by remember { mutableStateOf("") }
    val currentLocation = snapshot.location.sample?.takeIf { it.isUsableMapLocation() }
    val mapCenter = currentLocation?.let { MotoMapPoint(it.latitude, it.longitude) }
        ?: MotoMapPoint(19.4326, -99.1332)

    Scaffold(
        containerColor = MotoBackground,
        topBar = {
            MotoTopBar(
                title = "Mapa",
                subtitle = if (tripActive) "Viaje activo" else "Ubicación y navegación",
                navigationIcon = MotoTopBarIcon.Back,
                showNotificationsIcon = false,
                onNavigationClick = onHomeSelected
            )
        },
        bottomBar = {
            MotoBottomBar(
                selectedItem = MotoBottomBarItem.Map,
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
                onMapSelected = {},
                onProfileSelected = onProfileSelected
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MotoSurface)
            ) {
                if (currentLocation != null) {
                    MotoSinglePointMap(
                        point = mapCenter,
                        modifier = Modifier.fillMaxSize(),
                        zoom = 16.0
                    )
                } else {
                    MotoBaseMap(
                        center = mapCenter,
                        modifier = Modifier.fillMaxSize(),
                        zoom = 11.0
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MotoSurface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Abrir navegación en Google Maps",
                        color = MotoPrimaryDark,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "MotoSOS muestra tu ubicación con MapLibre + OpenFreeMap y registra tus puntos GPS reales. Las indicaciones, tráfico y ruta estimada las genera Google Maps al abrir la navegación.",
                        color = MotoTextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    currentLocation?.let {
                        Text(
                            "Ubicación actual: %.5f, %.5f".format(it.latitude, it.longitude),
                            color = MotoTextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedTextField(
                        value = destination,
                        onValueChange = { destination = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Destino o dirección") },
                        placeholder = { Text("Ej. Universidad Tecnológica de Tula-Tepeji") }
                    )
                    Button(
                        onClick = { openGoogleMapsNavigation(context, destination) },
                        enabled = destination.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MotoSuccess)
                    ) {
                        Text("Navegar con Google Maps", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { openGoogleMapsApp(context, currentLocation) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Abrir mapa completo")
                    }
                    if (tripActive) {
                        Text(
                            "Puedes salir a Google Maps sin perder el viaje: el monitoreo de MotoSOS continúa en segundo plano.",
                            color = MotoTextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

internal fun googleNavigationUri(destination: String): Uri =
    Uri.parse("google.navigation:q=${Uri.encode(destination.trim())}&mode=d")

private fun openGoogleMapsNavigation(context: Context, destination: String) {
    val query = destination.trim()
    if (query.isEmpty()) return
    val intent = Intent(Intent.ACTION_VIEW, googleNavigationUri(query)).setPackage("com.google.android.apps.maps")
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    } else {
        val fallback = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(query)}&travelmode=driving")
        )
        if (fallback.resolveActivity(context.packageManager) != null) context.startActivity(fallback)
        else Toast.makeText(context, "No hay una aplicación de mapas disponible.", Toast.LENGTH_SHORT).show()
    }
}

private fun openGoogleMapsApp(context: Context, location: LocationSample?) {
    val uri = if (location != null) {
        Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}(MotoSOS)")
    } else {
        Uri.parse("geo:0,0?q=Motociclistas")
    }
    val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps"))
    when {
        intent.resolveActivity(context.packageManager) != null -> context.startActivity(intent)
        fallback.resolveActivity(context.packageManager) != null -> context.startActivity(fallback)
        else -> Toast.makeText(context, "No hay una aplicación de mapas disponible.", Toast.LENGTH_SHORT).show()
    }
}

private fun LocationSample.isUsableMapLocation(): Boolean =
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        !(latitude == 0.0 && longitude == 0.0)
