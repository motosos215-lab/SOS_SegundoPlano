package com.example.sos_segundoplano.features.monitor

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.core.push.MonitorAlertProvider
import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertsProvider
import com.example.sos_segundoplano.domain.auth.authenticatedIdentityOrNull
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.ui.components.MotoTopBar
import com.example.sos_segundoplano.ui.theme.MotoAlert
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoSuccessSoft
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary
import com.example.sos_segundoplano.ui.theme.MotoWarning
import com.example.sos_segundoplano.ui.format.DisplayFormatters
import com.example.sos_segundoplano.ui.format.monitorResponseLabel
import com.example.sos_segundoplano.ui.format.monitorStatusLabel
import com.example.sos_segundoplano.features.history.buildGeoUri
import kotlinx.coroutines.launch

@Composable
fun MonitorRoot(authRepository: AuthRepository) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val sessionState by authRepository.observeSession().collectAsStateWithLifecycle()
    val sessionIdentity = sessionState.authenticatedIdentityOrNull()
    val viewModel = remember(context) {
        MonitorAlertsViewModel(
            repository = MonitorAlertsProvider.get(context),
            pendingAlerts = MonitorAlertProvider.get(context),
            isMonitorSession = {
                when (val state = authRepository.observeSession().value) {
                    is SessionState.Authenticated -> state.user.role == UserRole.Monitor
                    is SessionState.Refreshing -> state.user.role == UserRole.Monitor
                    else -> false
                }
            }
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val consumedFcmAttemptId by viewModel.consumedFcmAttemptId.collectAsStateWithLifecycle()
    val successfulActionRevision by viewModel.successfulActionRevision.collectAsStateWithLifecycle()
    val historyViewModel = remember(context) {
        MonitorAlertHistoryViewModel(
            repository = MonitorAlertsProvider.get(context),
            isMonitorSession = { authRepository.observeSession().value.let { it is SessionState.Authenticated && it.user.role == UserRole.Monitor || it is SessionState.Refreshing && it.user.role == UserRole.Monitor } }
        )
    }
    val historyState by historyViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(consumedFcmAttemptId) {
        if (consumedFcmAttemptId != null) historyViewModel.refresh()
    }
    LaunchedEffect(successfulActionRevision) {
        if (successfulActionRevision > 0L) historyViewModel.refresh()
    }
    MonitorHomeScreen(
        state = state,
        onRetry = viewModel::retry,
        onAcknowledge = viewModel::acknowledge,
        onDecline = viewModel::decline,
        historyState = historyState,
        onRefresh = viewModel::refresh,
        onRefreshHistory = historyViewModel::refresh,
        onOpenHistoryAlert = { viewModel.open(it) },
        onLogout = { sessionIdentity?.let { expected -> scope.launch { authRepository.logoutIfCurrent(expected) } } }
    )
}

@Composable
fun MonitorHomePlaceholderScreen(onLogout: () -> Unit, modifier: Modifier = Modifier) = Column(
    modifier.fillMaxSize().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
) {
    Text("Modo Monitor")
    Text("Sesión Monitor activa", modifier = Modifier.padding(vertical = 12.dp))
    Button(onClick = onLogout) { Text(stringResource(R.string.profile_logout)) }
}

@Composable
fun MonitorHomeScreen(
    state: MonitorAlertsUiState,
    onRetry: () -> Unit,
    onAcknowledge: (String) -> Unit,
    onDecline: (String) -> Unit,
    historyState: MonitorAlertHistoryUiState = MonitorAlertHistoryUiState.Loading,
    onRefresh: () -> Unit = onRetry,
    onRefreshHistory: () -> Unit = {},
    onOpenHistoryAlert: (String) -> Unit = {},
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dialog by remember { mutableStateOf<MonitorDialog?>(null) }
    var destination by remember { mutableStateOf<MonitorDestination>(MonitorDestination.CurrentAlert) }
    Column(modifier = modifier.fillMaxSize().background(MotoBackground).testTag("monitor_home_screen")) {
        MotoTopBar(
            title = if (destination is MonitorDestination.AlertHistory) "Historial de alertas" else stringResource(R.string.monitor_alert_received),
            subtitle = if (destination is MonitorDestination.CurrentAlert && state is MonitorAlertsUiState.Ready) stringResource(R.string.monitor_active) else null,
            showNavigationIcon = false,
            showNotificationsIcon = false
        )
        if (destination !is MonitorDestination.HistoryDetail) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = { if (destination is MonitorDestination.AlertHistory) onRefreshHistory() else onRefresh() },
                    modifier = Modifier.semantics { contentDescription = "Actualizar" }.testTag("monitor_refresh_button")
                ) { Text("Actualizar") }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = {
                        destination = if (destination is MonitorDestination.AlertHistory) {
                            MonitorDestination.CurrentAlert
                        } else {
                            MonitorDestination.AlertHistory
                        }
                    },
                    modifier = Modifier
                        .semantics {
                            contentDescription = if (destination is MonitorDestination.AlertHistory) {
                                "Abrir alerta actual"
                            } else {
                                "Abrir historial de alertas"
                            }
                        }
                        .testTag("monitor_history_section")
                ) { Text(if (destination is MonitorDestination.AlertHistory) "Alerta actual" else "Historial") }
            }
        }
        when (destination) {
            MonitorDestination.AlertHistory -> MonitorHistoryContent(
                state = historyState,
                refresh = onRefreshHistory,
                open = { id ->
                    destination = MonitorDestination.HistoryDetail(id)
                    onOpenHistoryAlert(id)
                }
            )

            MonitorDestination.CurrentAlert,
            is MonitorDestination.HistoryDetail -> when (state) {
            MonitorAlertsUiState.Ready -> ReadyContent(onLogout)
            is MonitorAlertsUiState.Loading -> LoadingContent()
            is MonitorAlertsUiState.Error -> ErrorContent(state.message, onRetry)
            is MonitorAlertsUiState.Alert -> AlertContent(
                state = state,
                onOpenAcknowledge = { dialog = MonitorDialog.Acknowledge },
                onOpenDecline = { dialog = MonitorDialog.Decline },
                onRetry = onRetry,
                onBackToHistory = (destination as? MonitorDestination.HistoryDetail)?.let { { destination = MonitorDestination.AlertHistory } }
            )
            }
        }
    }
    dialog?.let { mode -> ActionDialog(mode, onDismiss = { dialog = null }, onSubmit = { value -> dialog = null; if (mode == MonitorDialog.Acknowledge) onAcknowledge(value) else onDecline(value) }) }
}

@Composable private fun MonitorHistoryContent(state: MonitorAlertHistoryUiState, refresh: () -> Unit, open: (String) -> Unit) = when (state) {
    MonitorAlertHistoryUiState.Loading -> LoadingContent()
    MonitorAlertHistoryUiState.Empty -> Column(Modifier.fillMaxSize().padding(24.dp)) { Text("No hay alertas en el historial.") }
    is MonitorAlertHistoryUiState.Error -> HistoryErrorContent(refresh)
    is MonitorAlertHistoryUiState.Content -> LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(state.alerts) { alert ->
            OutlinedButton(
                onClick = { open(alert.notificationDeliveryAttemptId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Abrir detalle de alerta" }
                    .testTag("monitor_history_alert_${alert.notificationDeliveryAttemptId}")
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text("Alerta de emergencia", color = MotoPrimaryDark)
                    Text(safeMonitorStatusLabel(alert.status), color = MotoTextSecondary)
                    localizedResponseOrNull(alert.responseType)?.let { Text(it, color = MotoTextSecondary) }
                    Text(DisplayFormatters.dateTime(alert.createdAtUtc).takeUnless { it.isNullOrBlank() } ?: "Fecha no disponible", color = MotoTextSecondary)
                }
            }
        }
        state.error?.let {
            item {
                Text("No pudimos actualizar el historial.", color = MotoWarning)
                Text("Intenta nuevamente.", color = MotoTextSecondary)
            }
        }
    }
}

@Composable private fun ReadyContent(onLogout: () -> Unit) = Column(
    Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
) {
    Text("✓", color = MotoSuccess, modifier = Modifier.testTag("monitor_ready_icon"))
    Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.monitor_active), color = MotoPrimaryDark)
    Text(stringResource(R.string.monitor_ready_description), color = MotoTextSecondary, modifier = Modifier.padding(top = 12.dp))
    OutlinedButton(onClick = onLogout, modifier = Modifier.padding(top = 32.dp)) { Text(stringResource(R.string.profile_logout)) }
}

@Composable private fun LoadingContent() = Column(Modifier.fillMaxSize().testTag("monitor_alert_loading"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator(); Text(stringResource(R.string.monitor_alert_loading_text), Modifier.padding(top = 16.dp)) }
@Composable private fun ErrorContent(message: String, retry: () -> Unit) = Column(Modifier.fillMaxSize().padding(24.dp).testTag("monitor_alert_error"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(message, color = MotoTextSecondary); Button(onClick = retry, modifier = Modifier.padding(top = 16.dp).testTag("monitor_alert_retry")) { Text(stringResource(R.string.retry)) } }
@Composable private fun HistoryErrorContent(retry: () -> Unit) = Column(Modifier.fillMaxSize().padding(24.dp).testTag("monitor_alert_error"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Text("No pudimos cargar el historial.", color = MotoTextSecondary)
    Text("Intenta nuevamente.", color = MotoTextSecondary, modifier = Modifier.padding(top = 8.dp))
    Button(onClick = retry, modifier = Modifier.padding(top = 16.dp).testTag("monitor_alert_retry")) { Text(stringResource(R.string.retry)) }
}

@Composable private fun AlertContent(
    state: MonitorAlertsUiState.Alert,
    onOpenAcknowledge: () -> Unit,
    onOpenDecline: () -> Unit,
    onRetry: () -> Unit,
    onBackToHistory: (() -> Unit)?
) {
    val acknowledgement = state.detail.acknowledgement
    val context = LocalContext.current
    val isSubmitting = state.action == MonitorAlertAction.Submitting
    val response = localizedResponseOrNull(acknowledgement?.responseType)
    val message = acknowledgement?.message?.takeIf { it.isNotBlank() }
    val enrichedStatus = state.status
    val location = enrichedStatus?.location
    val usableLocation = location?.takeIf { it.hasUsableCoordinates() }
    val final = acknowledgement?.status == "Acknowledged" ||
        acknowledgement?.status == "Declined" ||
        acknowledgement?.acknowledgedAtUtc != null ||
        acknowledgement?.declinedAtUtc != null ||
        state.notice == MonitorAlertNotice.Confirmed ||
        state.notice == MonitorAlertNotice.Declined
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()).testTag("monitor_alert_detail")) {
        onBackToHistory?.let { onBack ->
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .semantics { contentDescription = "Volver al historial" }
                    .testTag("monitor_alert_back_to_history")
            ) { Text("Volver") }
            Spacer(Modifier.height(12.dp))
        }
        AlertCard(
            icon = "⚠",
            title = localizedIncidentTitle(enrichedStatus?.incident?.cause),
            subtitle = safeMonitorStatusLabel(acknowledgement?.status),
            color = emergencyColorFor(acknowledgement?.status),
            risk = localizedRiskLabel(enrichedStatus?.incident?.riskLevel),
            priority = localizedPriorityLabel(enrichedStatus?.alertDispatch?.priority)
        )
        if (enrichedStatus?.requiresAttention == true) Text("Requiere atención", color = MotoWarning, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        if (response != null || message != null) {
            DetailCard("Respuesta") {
                response?.let { DetailRow(stringResource(R.string.monitor_alert_response), it) }
                message?.let { DetailRow("Mensaje", it) }
            }
            Spacer(Modifier.height(16.dp))
        }
        DetailCard("Información de la alerta") {
            val occurredAtUtc = enrichedStatus?.incident?.occurredAtUtc
            DetailRow(if (occurredAtUtc != null) "Ocurrió" else "Registro", DisplayFormatters.dateTime(occurredAtUtc ?: acknowledgement?.createdAtUtc) ?: "Fecha no disponible")
            localizedPriorityLabel(enrichedStatus?.alertDispatch?.priority)?.let { DetailRow("Prioridad", it) }
            localizedTripStatus(enrichedStatus?.trip?.status)?.let { DetailRow("Viaje", it) }
            DetailRow("Vista", DisplayFormatters.dateTime(acknowledgement?.viewedAtUtc))
            DetailRow("Atendida", DisplayFormatters.dateTime(acknowledgement?.acknowledgedAtUtc))
            DetailRow("Rechazada", DisplayFormatters.dateTime(acknowledgement?.declinedAtUtc))
        }
        Spacer(Modifier.height(16.dp))
        DetailCard(stringResource(R.string.monitor_location)) {
            if (usableLocation != null) {
                Text("Ubicación disponible", color = MotoTextSecondary)
                usableLocation.accuracyMeters?.takeIf { it.isFinite() && it >= 0.0 }?.let { Text("Precisión: %.0f m".format(it), color = MotoTextSecondary) }
                OutlinedButton(
                    onClick = { openMonitorLocationInMap(context, usableLocation.latitude!!, usableLocation.longitude!!) },
                    modifier = Modifier.semantics { contentDescription = "Ver ubicación en Maps" }.testTag("monitor_open_location_button")
                ) { Text("Ver ubicación") }
            } else {
                Text("Ubicación no disponible para esta alerta.", color = MotoTextSecondary)
            }
        }
        state.notice?.let { notice -> Spacer(Modifier.height(16.dp)); Notice(notice) }
        if (!final) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onOpenAcknowledge,
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Confirmar recibido" }.testTag("monitor_acknowledge_button"),
                colors = ButtonDefaults.buttonColors(containerColor = MotoSuccess)
            ) { if (isSubmitting) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White) else Text(stringResource(R.string.monitor_confirm_received)) }
            OutlinedButton(
                onClick = onOpenDecline,
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = "No puedo ayudar" }.testTag("monitor_decline_button")
            ) { Text(stringResource(R.string.monitor_decline)) }
        }
        if (state.notice is MonitorAlertNotice.NonBlockingError) OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(stringResource(R.string.retry)) }
    }
}

private sealed interface MonitorDestination {
    data object CurrentAlert : MonitorDestination
    data object AlertHistory : MonitorDestination
    data class HistoryDetail(val notificationDeliveryAttemptId: String) : MonitorDestination
}

private fun safeMonitorStatusLabel(status: String?): String = monitorStatusLabel(status)
    .takeIf { it in setOf("Pendiente", "Vista", "Atendida", "Rechazada") }
    ?: "Estado no disponible"

private fun localizedResponseOrNull(responseType: String?): String? = responseType
    ?.takeUnless { it == "None" }
    ?.let(::monitorResponseLabel)
    ?.takeIf { it.isNotBlank() }

private fun localizedIncidentTitle(cause: String?): String = when (cause) {
    "ManualSos" -> "SOS manual"
    "CountdownTimeout" -> "Tiempo de confirmación agotado"
    "CriticalEvent" -> "Evento crítico"
    "UserRequestedHelp" -> "Solicitud de ayuda"
    else -> "Alerta de emergencia"
}

private fun localizedRiskLabel(riskLevel: String?): String? = when (riskLevel) {
    "Low" -> "Bajo"
    "Medium" -> "Medio"
    "High" -> "Alto"
    else -> null
}

private fun localizedPriorityLabel(priority: String?): String? = when (priority) {
    "Low" -> "Baja"
    "Medium" -> "Media"
    "High" -> "Alta"
    "Critical" -> "Crítica"
    else -> null
}

private fun localizedTripStatus(status: String?): String? = when (status) {
    "Active" -> "Activo"
    "Finished" -> "Finalizado"
    else -> null
}

private fun emergencyColorFor(status: String?): Color = when (status) {
    "Acknowledged" -> MotoSuccess
    "Declined" -> MotoWarning
    else -> MotoAlert
}

private fun com.example.sos_segundoplano.domain.monitor.MonitorAlertStatusLocation?.hasUsableCoordinates(): Boolean = this?.let {
    it.available == true && it.latitude != null && it.longitude != null &&
        it.latitude.isFinite() && it.longitude.isFinite() &&
        it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 &&
        !(it.latitude == 0.0 && it.longitude == 0.0)
} == true

private fun openMonitorLocationInMap(context: android.content.Context, latitude: Double, longitude: Double) {
    runCatching {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, buildGeoUri(latitude, longitude)), "Ver en mapa"))
    }.onFailure {
        Toast.makeText(context, "No hay una aplicación de mapas disponible.", Toast.LENGTH_SHORT).show()
    }
}

@Composable private fun AlertCard(
    icon: String,
    title: String,
    subtitle: String,
    color: Color,
    risk: String?,
    priority: String?
) = Row(
    Modifier.fillMaxWidth()
        .background(color.copy(alpha = .10f), RoundedCornerShape(20.dp))
        .border(1.dp, color.copy(alpha = .35f), RoundedCornerShape(20.dp))
        .padding(20.dp)
        .testTag("monitor_alert_urgency"),
    verticalAlignment = Alignment.CenterVertically
) {
    Text(icon, modifier = Modifier.padding(end = 14.dp))
    Column {
        Text(title, color = color)
        Text(subtitle, color = MotoTextSecondary)
        risk?.let { Text("Riesgo: $it", color = MotoTextSecondary, modifier = Modifier.padding(top = 6.dp)) }
        priority?.let { Text("Prioridad: $it", color = MotoTextSecondary) }
    }
}
@Composable private fun DetailCard(title: String, content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth().background(MotoSurface, RoundedCornerShape(16.dp)).border(1.dp, MotoDivider, RoundedCornerShape(16.dp)).padding(18.dp)) { Text(title, color = MotoPrimaryDark); Spacer(Modifier.height(10.dp)); content() }
@Composable private fun DetailRow(label: String, value: String?) { if (!value.isNullOrBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = MotoTextSecondary); Text(value, color = MotoPrimaryDark) } }
@Composable private fun Notice(notice: MonitorAlertNotice) { val (text, color) = when (notice) { MonitorAlertNotice.Confirmed -> stringResource(R.string.monitor_confirmed) to MotoSuccess; MonitorAlertNotice.Declined -> stringResource(R.string.monitor_declined) to MotoWarning; is MonitorAlertNotice.NonBlockingError -> notice.message to MotoWarning }; Text(text, Modifier.fillMaxWidth().background(if (color == MotoSuccess) MotoSuccessSoft else color.copy(alpha = .10f), RoundedCornerShape(12.dp)).padding(14.dp).testTag("monitor_action_notice"), color = color) }

private enum class MonitorDialog { Acknowledge, Decline }
@Composable private fun ActionDialog(mode: MonitorDialog, onDismiss: () -> Unit, onSubmit: (String) -> Unit) { var text by remember { mutableStateOf("") }; val label = if (mode == MonitorDialog.Acknowledge) stringResource(R.string.monitor_message) else stringResource(R.string.monitor_reason); AlertDialog(onDismissRequest = onDismiss, title = { Text(if (mode == MonitorDialog.Acknowledge) stringResource(R.string.monitor_can_help) else stringResource(R.string.monitor_decline)) }, text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.testTag("monitor_action_input")) }, confirmButton = { Button(onClick = { onSubmit(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("monitor_action_submit")) { Text(stringResource(R.string.monitor_send)) } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }) }
