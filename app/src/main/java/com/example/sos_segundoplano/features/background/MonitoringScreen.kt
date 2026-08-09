package com.example.sos_segundoplano.features.background

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.domain.offline.OfflineQueueSummary
import com.example.sos_segundoplano.domain.rules.RiskAssessmentState
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.signals.CaptureState
import com.example.sos_segundoplano.domain.signals.TripSignalSnapshot
import com.example.sos_segundoplano.domain.signals.WearableSample
import com.example.sos_segundoplano.domain.signals.WearableStatus
import com.example.sos_segundoplano.ui.components.MotoBottomBar
import com.example.sos_segundoplano.ui.components.MotoBottomBarItem
import com.example.sos_segundoplano.ui.components.MotoMetricCard
import com.example.sos_segundoplano.ui.components.MotoMonitoringIndicator
import com.example.sos_segundoplano.ui.components.MotoTopBar
import com.example.sos_segundoplano.ui.components.MotoTopBarIcon
import com.example.sos_segundoplano.ui.theme.MotoAlert
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoTextPrimary
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary

@Composable
fun MonitoringScreen(
    modifier: Modifier = Modifier,
    snapshot: TripSignalSnapshot = TripSignalSnapshot(),
    riskAssessmentState: RiskAssessmentState = RiskAssessmentState.Idle,
    offlineQueueSummary: OfflineQueueSummary = OfflineQueueSummary(),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MotoMonitoringIndicator()
            Text(
                text = stringResource(R.string.background_monitoring_active),
                style = MaterialTheme.typography.bodyMedium,
                color = MotoSuccess,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                softWrap = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            MonitoringStatusPanel(snapshot.captureState)
            RiskScorePanel(riskAssessmentState)
            OfflineQueuePanel(offlineQueueSummary)
            MetricsGrid(snapshot)
            WearablePanel(snapshot.wearable)
            Spacer(modifier = Modifier.height(8.dp))
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
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MonitoringStatusPanel(captureState: CaptureState) {
    TitledStatusCard(
        title = stringResource(R.string.monitoring_status_title),
        testTag = "monitoring_status_panel"
    ) {
        FieldLine(
            label = stringResource(R.string.monitoring_status_signal_capture),
            value = captureStateText(captureState),
            testTag = "monitoring_status_value"
        )
    }
}

@Composable
private fun RiskScorePanel(state: RiskAssessmentState) {
    val assessment = (state as? RiskAssessmentState.AssessmentReady)?.assessment
    TitledStatusCard(
        title = stringResource(R.string.risk_score_title),
        testTag = "risk_score_panel"
    ) {
        if (assessment?.score == null) {
            Text(
                text = stringResource(R.string.risk_score_waiting),
                modifier = Modifier.testTag("risk_score_waiting"),
                style = MaterialTheme.typography.titleMedium,
                color = MotoTextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.risk_score_waiting_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MotoTextSecondary
            )
        } else {
            FieldLine(
                label = stringResource(R.string.risk_score_current),
                value = assessment.score.toString(),
                testTag = "risk_score_value"
            )
            FieldLine(
                label = stringResource(R.string.risk_score_level),
                value = riskLevelText(assessment.riskLevel),
                testTag = "risk_score_level"
            )
        }
    }
}

@Composable
private fun OfflineQueuePanel(summary: OfflineQueueSummary) {
    if (summary.unsentCount == 0 && summary.sentCount == 0 && summary.permanentFailureCount == 0 && summary.syncErrorCount == 0) return
    val status = when {
        summary.permanentFailureCount > 0 -> stringResource(R.string.offline_queue_sync_error, summary.permanentFailureCount)
        summary.notConfiguredCount > 0 -> stringResource(R.string.offline_queue_not_configured, summary.notConfiguredCount)
        summary.retryPendingCount > 0 -> stringResource(R.string.offline_queue_waiting_connection, summary.retryPendingCount)
        summary.pendingCount > 0 || summary.inFlightCount > 0 -> stringResource(R.string.offline_queue_pending, summary.unsentCount)
        else -> stringResource(R.string.offline_queue_confirmed)
    }
    Text(
        text = status,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("offline_queue_status"),
        style = MaterialTheme.typography.bodySmall,
        color = if (summary.permanentFailureCount > 0) MotoAlert else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun MetricsGrid(snapshot: TripSignalSnapshot) {
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
    }
}

@Composable
private fun WearablePanel(wearable: WearableSample) {
    TitledStatusCard(
        title = stringResource(R.string.smartwatch),
        testTag = "wearable_status_panel"
    ) {
        FieldLine(
            label = stringResource(R.string.watch_connection_status_label),
            value = wearableStatusText(wearable),
            testTag = "wearable_status_value"
        )
        wearable.heartRateBpm?.takeIf { it.isFinite() && it > 0.0 }?.let { heartRate ->
            FieldLine(
                label = stringResource(R.string.heart_rate),
                value = "${heartRate.toInt()} bpm",
                testTag = "wearable_heart_rate_value"
            )
        }
        wearable.watchBatteryPercentage?.takeIf { it in 0..100 }?.let { battery ->
            FieldLine(
                label = stringResource(R.string.watch_battery_label),
                value = "$battery%",
                testTag = "wearable_battery_value"
            )
        }
        if (wearable.lastUpdatedMillis > 0L && (wearable.captureActive || wearable.status == WearableStatus.Stale)) {
            FieldLine(
                label = stringResource(R.string.watch_last_signal_label),
                value = stringResource(R.string.watch_last_signal_received),
                testTag = "wearable_last_signal"
            )
        }
    }
}

private fun speedValue(snapshot: TripSignalSnapshot): String {
    val speed = com.example.sos_segundoplano.domain.signals.SpeedPolicy
        .metersPerSecondToKilometersPerHour(snapshot.speed.sample?.metersPerSecond)
        ?: return availabilityText(snapshot.speed.availability)
    return speed.toInt().toString()
}

private fun speedUnit(snapshot: TripSignalSnapshot): String? =
    if (snapshot.speed.sample == null) null else "km/h"

private fun batteryValue(snapshot: TripSignalSnapshot): String =
    snapshot.phoneBattery.sample?.let { "${it.percentage}%" } ?: availabilityText(snapshot.phoneBattery.availability)

private fun connectivityValue(snapshot: TripSignalSnapshot): String {
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

private fun gpsAccuracyValue(snapshot: TripSignalSnapshot): String {
    val sample = snapshot.location.sample ?: return when (snapshot.location.availability) {
        com.example.sos_segundoplano.domain.signals.SignalAvailability.Disabled -> "GPS desactivado"
        com.example.sos_segundoplano.domain.signals.SignalAvailability.PermissionMissing -> "Permiso de ubicación faltante"
        else -> "Esperando GPS"
    }
    return "± ${sample.accuracyMeters.toInt()} m"
}

private fun gpsAccuracyClassification(snapshot: TripSignalSnapshot): String? {
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

private fun availabilityText(availability: com.example.sos_segundoplano.domain.signals.SignalAvailability): String = when (availability) {
    com.example.sos_segundoplano.domain.signals.SignalAvailability.Available -> "Activo"
    com.example.sos_segundoplano.domain.signals.SignalAvailability.Waiting -> "Esperando señal"
    com.example.sos_segundoplano.domain.signals.SignalAvailability.PermissionMissing -> "Permiso faltante"
    com.example.sos_segundoplano.domain.signals.SignalAvailability.Disabled -> "GPS desactivado"
    com.example.sos_segundoplano.domain.signals.SignalAvailability.Unsupported -> "Sensor no disponible"
    com.example.sos_segundoplano.domain.signals.SignalAvailability.Stale -> "Datos desactualizados"
    is com.example.sos_segundoplano.domain.signals.SignalAvailability.Error -> "Error"
}

@Composable
private fun TitledStatusCard(
    title: String,
    testTag: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MotoSurface, RoundedCornerShape(16.dp))
            .border(1.dp, MotoDivider, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MotoTextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        content()
    }
}

@Composable
private fun FieldLine(
    label: String,
    value: String,
    testTag: String? = null
) {
    Column(modifier = Modifier.then(if (testTag == null) Modifier else Modifier.testTag(testTag))) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MotoTextSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MotoTextPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun captureStateText(state: CaptureState): String = when (state) {
    CaptureState.Idle -> stringResource(R.string.monitoring_status_preparing)
    CaptureState.Starting -> stringResource(R.string.monitoring_status_starting)
    CaptureState.Active -> stringResource(R.string.monitoring_status_active)
    CaptureState.Stopped -> stringResource(R.string.monitoring_status_stopped)
    is CaptureState.Error -> stringResource(R.string.monitoring_status_error)
}

@Composable
private fun riskLevelText(level: RiskLevel): String = when (level) {
    RiskLevel.Low -> stringResource(R.string.risk_level_low)
    RiskLevel.Medium -> stringResource(R.string.risk_level_medium)
    RiskLevel.High -> stringResource(R.string.risk_level_high)
    RiskLevel.Unknown -> stringResource(R.string.risk_level_unknown)
}

@Composable
private fun wearableStatusText(wearable: WearableSample): String = when (wearable.status) {
    WearableStatus.NotInstalledOrUnavailable -> stringResource(R.string.wearable_status_unavailable)
    WearableStatus.Disconnected -> stringResource(R.string.wearable_status_disconnected)
    WearableStatus.ConnectedNearby -> stringResource(R.string.wearable_status_connected_nearby)
    WearableStatus.ConnectedRemote -> stringResource(R.string.wearable_status_connected_remote)
    WearableStatus.PermissionMissing,
    WearableStatus.PermissionRequired -> stringResource(R.string.wearable_status_permission_required)
    WearableStatus.PermanentlyDenied -> stringResource(R.string.wearable_status_permission_denied)
    WearableStatus.SensorUnavailable -> stringResource(R.string.wearable_status_sensor_unavailable)
    WearableStatus.HealthServicesUnavailable -> stringResource(R.string.wearable_status_health_unavailable)
    WearableStatus.StartFailed -> stringResource(R.string.wearable_status_start_failed)
    WearableStatus.Capturing -> stringResource(R.string.wearable_status_capturing)
    WearableStatus.Stale -> stringResource(R.string.wearable_status_stale)
    is WearableStatus.Error -> stringResource(R.string.wearable_status_error)
    WearableStatus.UserActionRequired -> stringResource(R.string.wearable_status_user_action_required)
    WearableStatus.Stopped -> stringResource(R.string.wearable_status_stopped)
}
