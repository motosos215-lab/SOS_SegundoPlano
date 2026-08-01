package com.example.sos_segundoplano.features.background

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.ui.components.MotoBottomBar
import com.example.sos_segundoplano.ui.components.MotoBottomBarItem
import com.example.sos_segundoplano.ui.components.MotoMetricCard
import com.example.sos_segundoplano.ui.components.MotoMonitoringIndicator
import com.example.sos_segundoplano.ui.components.MotoTopBar
import com.example.sos_segundoplano.ui.components.MotoTopBarIcon
import com.example.sos_segundoplano.ui.theme.MotoAlert
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoSuccess

@Composable
fun MonitoringScreen(
    modifier: Modifier = Modifier,
    snapshot: com.example.sos_segundoplano.domain.signals.TripSignalSnapshot = com.example.sos_segundoplano.domain.signals.TripSignalSnapshot(),
    onFinishTrip: () -> Unit = {},
    isFinishTripEnabled: Boolean = true
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("monitoring_screen"),
        containerColor = MotoBackground,
        topBar = {
            MotoTopBar(
                title = stringResource(R.string.monitoring_title),
                subtitle = stringResource(R.string.active_trip),
                navigationIcon = MotoTopBarIcon.Back
            )
        },
        bottomBar = {
            MotoBottomBar(selectedItem = MotoBottomBarItem.Home)
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val useFlexibleBottomSpace = maxHeight >= 560.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (useFlexibleBottomSpace) {
                            Modifier
                        } else {
                            Modifier.verticalScroll(rememberScrollState())
                        }
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                MotoMonitoringIndicator()
                Text(
                    text = stringResource(R.string.background_monitoring_active),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MotoSuccess,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    softWrap = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                MetricsGrid(snapshot)
                if (useFlexibleBottomSpace) {
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = onFinishTrip,
                    enabled = isFinishTripEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .testTag("finish_trip_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MotoSurface,
                        disabledContainerColor = MotoSurface
                    ),
                    border = BorderStroke(1.5.dp, MotoAlert)
                ) {
                    Text(
                        text = stringResource(R.string.finish_trip),
                        color = MotoAlert,
                        softWrap = true,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun MetricsGrid(snapshot: com.example.sos_segundoplano.domain.signals.TripSignalSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MotoMetricCard(
                title = stringResource(R.string.speed),
                value = speedValue(snapshot),
                unit = speedUnit(snapshot),
                modifier = Modifier.weight(1f)
            )
            MotoMetricCard(
                title = stringResource(R.string.gps_accuracy),
                value = gpsAccuracyValue(snapshot),
                unit = gpsAccuracyClassification(snapshot),
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MotoMetricCard(
                title = stringResource(R.string.mobile_battery),
                value = batteryValue(snapshot),
                iconRes = R.drawable.ic_metric_battery,
                modifier = Modifier.weight(1f)
            )
            MotoMetricCard(
                title = stringResource(R.string.connectivity),
                value = connectivityValue(snapshot),
                iconRes = R.drawable.ic_metric_signal,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MotoMetricCard(
                title = stringResource(R.string.smartwatch),
                value = wearableValue(snapshot),
                modifier = Modifier.weight(1f)
            )
            MotoMetricCard(
                title = stringResource(R.string.heart_rate),
                value = heartRateValue(snapshot),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun speedValue(snapshot: com.example.sos_segundoplano.domain.signals.TripSignalSnapshot): String {
    val speed = com.example.sos_segundoplano.domain.signals.SpeedPolicy
        .metersPerSecondToKilometersPerHour(snapshot.speed.sample?.metersPerSecond)
        ?: return availabilityText(snapshot.speed.availability)
    return speed.toInt().toString()
}

private fun speedUnit(snapshot: com.example.sos_segundoplano.domain.signals.TripSignalSnapshot): String? =
    if (snapshot.speed.sample == null) null else "km/h"

private fun batteryValue(snapshot: com.example.sos_segundoplano.domain.signals.TripSignalSnapshot): String =
    snapshot.phoneBattery.sample?.let { "${it.percentage}%" } ?: availabilityText(snapshot.phoneBattery.availability)

private fun connectivityValue(snapshot: com.example.sos_segundoplano.domain.signals.TripSignalSnapshot): String {
    val sample = snapshot.connectivity.sample ?: return availabilityText(snapshot.connectivity.availability)
    if (!sample.connected) return "Sin conexión"
    val transport = when (sample.transport) {
        com.example.sos_segundoplano.domain.signals.NetworkTransport.Wifi -> "Wi-Fi"
        com.example.sos_segundoplano.domain.signals.NetworkTransport.Cellular -> "Celular"
        com.example.sos_segundoplano.domain.signals.NetworkTransport.Ethernet -> "Ethernet"
        com.example.sos_segundoplano.domain.signals.NetworkTransport.Vpn -> "VPN"
        com.example.sos_segundoplano.domain.signals.NetworkTransport.Bluetooth -> "Bluetooth"
        com.example.sos_segundoplano.domain.signals.NetworkTransport.Other -> "Otra"
        com.example.sos_segundoplano.domain.signals.NetworkTransport.None -> "Sin red"
    }
    return if (sample.validated) "Internet disponible" else "$transport sin internet"
}

private fun gpsAccuracyValue(snapshot: com.example.sos_segundoplano.domain.signals.TripSignalSnapshot): String {
    val sample = snapshot.location.sample ?: return when (snapshot.location.availability) {
        com.example.sos_segundoplano.domain.signals.SignalAvailability.Disabled -> "GPS desactivado"
        com.example.sos_segundoplano.domain.signals.SignalAvailability.PermissionMissing -> "Permiso de ubicación faltante"
        else -> "Esperando GPS"
    }
    return "± ${sample.accuracyMeters.toInt()} m"
}

private fun gpsAccuracyClassification(snapshot: com.example.sos_segundoplano.domain.signals.TripSignalSnapshot): String? {
    val classification = com.example.sos_segundoplano.domain.signals.GpsAccuracyPolicy
        .classify(snapshot.location.sample?.accuracyMeters) ?: return null
    return when (classification) {
        com.example.sos_segundoplano.domain.signals.GpsAccuracyClassification.Excellent -> "Excelente"
        com.example.sos_segundoplano.domain.signals.GpsAccuracyClassification.High -> "Alta"
        com.example.sos_segundoplano.domain.signals.GpsAccuracyClassification.Medium -> "Media"
        com.example.sos_segundoplano.domain.signals.GpsAccuracyClassification.Low -> "Baja"
        com.example.sos_segundoplano.domain.signals.GpsAccuracyClassification.VeryLow -> "Muy baja"
    }
}

private fun wearableValue(snapshot: com.example.sos_segundoplano.domain.signals.TripSignalSnapshot): String = when (snapshot.wearable.status) {
    com.example.sos_segundoplano.domain.signals.WearableStatus.NotInstalledOrUnavailable -> "Wear no disponible"
    com.example.sos_segundoplano.domain.signals.WearableStatus.Disconnected -> "Smartwatch desconectado"
    com.example.sos_segundoplano.domain.signals.WearableStatus.ConnectedNearby -> "Reloj cercano"
    com.example.sos_segundoplano.domain.signals.WearableStatus.ConnectedRemote -> "Reloj remoto"
    com.example.sos_segundoplano.domain.signals.WearableStatus.PermissionMissing -> "Permiso faltante"
    com.example.sos_segundoplano.domain.signals.WearableStatus.PermissionRequired -> "Permiso requerido en el reloj"
    com.example.sos_segundoplano.domain.signals.WearableStatus.PermanentlyDenied -> "Permiso denegado en el reloj"
    com.example.sos_segundoplano.domain.signals.WearableStatus.SensorUnavailable -> "Sensor no disponible"
    com.example.sos_segundoplano.domain.signals.WearableStatus.HealthServicesUnavailable -> "Health Services no disponible"
    com.example.sos_segundoplano.domain.signals.WearableStatus.StartFailed -> "No se pudo iniciar el reloj"
    com.example.sos_segundoplano.domain.signals.WearableStatus.Capturing -> "Activo"
    com.example.sos_segundoplano.domain.signals.WearableStatus.Stale -> "Datos del reloj desactualizados"
    is com.example.sos_segundoplano.domain.signals.WearableStatus.Error -> "Error de reloj"
    com.example.sos_segundoplano.domain.signals.WearableStatus.UserActionRequired -> "Requiere acción"
    com.example.sos_segundoplano.domain.signals.WearableStatus.Stopped -> "Reloj detenido"
}

private fun heartRateValue(snapshot: com.example.sos_segundoplano.domain.signals.TripSignalSnapshot): String =
    snapshot.wearable.heartRateBpm?.toInt()?.let { "$it bpm" } ?: "Esperando señal"

private fun availabilityText(availability: com.example.sos_segundoplano.domain.signals.SignalAvailability): String = when (availability) {
    com.example.sos_segundoplano.domain.signals.SignalAvailability.Available -> "Activo"
    com.example.sos_segundoplano.domain.signals.SignalAvailability.Waiting -> "Esperando señal"
    com.example.sos_segundoplano.domain.signals.SignalAvailability.PermissionMissing -> "Permiso faltante"
    com.example.sos_segundoplano.domain.signals.SignalAvailability.Disabled -> "GPS desactivado"
    com.example.sos_segundoplano.domain.signals.SignalAvailability.Unsupported -> "Sensor no disponible"
    com.example.sos_segundoplano.domain.signals.SignalAvailability.Stale -> "Datos desactualizados"
    is com.example.sos_segundoplano.domain.signals.SignalAvailability.Error -> "Error"
}
