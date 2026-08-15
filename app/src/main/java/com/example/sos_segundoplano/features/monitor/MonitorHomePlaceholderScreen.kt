package com.example.sos_segundoplano.features.monitor

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
    val historyViewModel = remember(context) {
        MonitorAlertHistoryViewModel(
            repository = MonitorAlertsProvider.get(context),
            isMonitorSession = { authRepository.observeSession().value.let { it is SessionState.Authenticated && it.user.role == UserRole.Monitor || it is SessionState.Refreshing && it.user.role == UserRole.Monitor } }
        )
    }
    val historyState by historyViewModel.state.collectAsStateWithLifecycle()
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
    var showingHistory by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize().background(MotoBackground).testTag("monitor_home_screen")) {
        MotoTopBar(
            title = if (state is MonitorAlertsUiState.Ready) {
                stringResource(R.string.monitor_mode)
            } else {
                stringResource(R.string.monitor_alert_received)
            },
            subtitle = if (state is MonitorAlertsUiState.Ready) stringResource(R.string.monitor_active) else null
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { if (showingHistory) onRefreshHistory() else onRefresh() }) { Text("Actualizar") }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { showingHistory = !showingHistory }) { Text(if (showingHistory) "Alerta actual" else "Historial") }
        }
        if (showingHistory) {
            MonitorHistoryContent(historyState, onRefreshHistory, { id -> showingHistory = false; onOpenHistoryAlert(id) })
        } else when (state) {
            MonitorAlertsUiState.Ready -> ReadyContent(onLogout)
            is MonitorAlertsUiState.Loading -> LoadingContent()
            is MonitorAlertsUiState.Error -> ErrorContent(state.message, onRetry)
            is MonitorAlertsUiState.Alert -> AlertContent(state, { dialog = MonitorDialog.Acknowledge }, { dialog = MonitorDialog.Decline }, onRetry)
        }
    }
    dialog?.let { mode -> ActionDialog(mode, onDismiss = { dialog = null }, onSubmit = { value -> dialog = null; if (mode == MonitorDialog.Acknowledge) onAcknowledge(value) else onDecline(value) }) }
}

@Composable private fun MonitorHistoryContent(state: MonitorAlertHistoryUiState, refresh: () -> Unit, open: (String) -> Unit) = when (state) {
    MonitorAlertHistoryUiState.Loading -> LoadingContent()
    MonitorAlertHistoryUiState.Empty -> Column(Modifier.fillMaxSize().padding(24.dp)) { Text("No hay alertas en el historial.") }
    is MonitorAlertHistoryUiState.Error -> ErrorContent(state.message, refresh)
    is MonitorAlertHistoryUiState.Content -> LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(state.alerts) { alert ->
            OutlinedButton(onClick = { open(alert.notificationDeliveryAttemptId) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text("Alerta de emergencia", color = MotoPrimaryDark)
                    Text(monitorStatusLabel(alert.status), color = MotoTextSecondary)
                    monitorResponseLabel(alert.responseType).takeIf { it.isNotBlank() }?.let { Text(it, color = MotoTextSecondary) }
                    Text(DisplayFormatters.dateTime(alert.createdAtUtc) ?: "Fecha no disponible", color = MotoTextSecondary)
                }
            }
        }
        state.error?.let { item { Text(it, color = MotoWarning) } }
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

@Composable private fun AlertContent(state: MonitorAlertsUiState.Alert, onOpenAcknowledge: () -> Unit, onOpenDecline: () -> Unit, onRetry: () -> Unit) {
    val acknowledgement = state.detail.acknowledgement
    val isSubmitting = state.action == MonitorAlertAction.Submitting
    val final = acknowledgement?.status == "Acknowledged" ||
        acknowledgement?.status == "Declined" ||
        acknowledgement?.acknowledgedAtUtc != null ||
        acknowledgement?.declinedAtUtc != null ||
        state.notice == MonitorAlertNotice.Confirmed ||
        state.notice == MonitorAlertNotice.Declined
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()).testTag("monitor_alert_detail")) {
        AlertCard("⚠", stringResource(R.string.monitor_alert_emergency), monitorStatusLabel(acknowledgement?.status).ifBlank { stringResource(R.string.monitor_alert_received) }, MotoAlert)
        Spacer(Modifier.height(16.dp))
        DetailCard(stringResource(R.string.monitor_alert_summary)) {
            DetailRow(stringResource(R.string.monitor_alert_status), monitorStatusLabel(acknowledgement?.status))
            DetailRow(stringResource(R.string.monitor_alert_response), monitorResponseLabel(acknowledgement?.responseType))
            DetailRow(stringResource(R.string.monitor_alert_received_at), DisplayFormatters.dateTime(acknowledgement?.createdAtUtc))
        }
        Spacer(Modifier.height(16.dp))
        DetailCard(stringResource(R.string.monitor_location)) { Text(stringResource(R.string.monitor_location_unavailable), color = MotoTextSecondary) }
        state.notice?.let { notice -> Spacer(Modifier.height(16.dp)); Notice(notice) }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenAcknowledge, enabled = !isSubmitting && !final, modifier = Modifier.fillMaxWidth().testTag("monitor_acknowledge_button"), colors = ButtonDefaults.buttonColors(containerColor = MotoSuccess)) { if (isSubmitting) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White) else Text(stringResource(R.string.monitor_confirm_received)) }
        OutlinedButton(onClick = onOpenDecline, enabled = !isSubmitting && !final, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("monitor_decline_button")) { Text(stringResource(R.string.monitor_decline)) }
        if (state.notice is MonitorAlertNotice.NonBlockingError) OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(stringResource(R.string.retry)) }
    }
}

@Composable private fun AlertCard(icon: String, title: String, subtitle: String, color: Color) = Row(Modifier.fillMaxWidth().background(color.copy(alpha = .10f), RoundedCornerShape(20.dp)).border(1.dp, color.copy(alpha = .35f), RoundedCornerShape(20.dp)).padding(20.dp).testTag("monitor_alert_urgency"), verticalAlignment = Alignment.CenterVertically) { Text(icon, modifier = Modifier.padding(end = 14.dp)); Column { Text(title, color = color); Text(subtitle, color = MotoTextSecondary) } }
@Composable private fun DetailCard(title: String, content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth().background(MotoSurface, RoundedCornerShape(16.dp)).border(1.dp, MotoDivider, RoundedCornerShape(16.dp)).padding(18.dp)) { Text(title, color = MotoPrimaryDark); Spacer(Modifier.height(10.dp)); content() }
@Composable private fun DetailRow(label: String, value: String?) { if (!value.isNullOrBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = MotoTextSecondary); Text(value, color = MotoPrimaryDark) } }
@Composable private fun Notice(notice: MonitorAlertNotice) { val (text, color) = when (notice) { MonitorAlertNotice.Confirmed -> stringResource(R.string.monitor_confirmed) to MotoSuccess; MonitorAlertNotice.Declined -> stringResource(R.string.monitor_declined) to MotoWarning; is MonitorAlertNotice.NonBlockingError -> notice.message to MotoWarning }; Text(text, Modifier.fillMaxWidth().background(if (color == MotoSuccess) MotoSuccessSoft else color.copy(alpha = .10f), RoundedCornerShape(12.dp)).padding(14.dp).testTag("monitor_action_notice"), color = color) }

private enum class MonitorDialog { Acknowledge, Decline }
@Composable private fun ActionDialog(mode: MonitorDialog, onDismiss: () -> Unit, onSubmit: (String) -> Unit) { var text by remember { mutableStateOf("") }; val label = if (mode == MonitorDialog.Acknowledge) stringResource(R.string.monitor_message) else stringResource(R.string.monitor_reason); AlertDialog(onDismissRequest = onDismiss, title = { Text(if (mode == MonitorDialog.Acknowledge) stringResource(R.string.monitor_can_help) else stringResource(R.string.monitor_decline)) }, text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.testTag("monitor_action_input")) }, confirmButton = { Button(onClick = { onSubmit(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("monitor_action_submit")) { Text(stringResource(R.string.monitor_send)) } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }) }
