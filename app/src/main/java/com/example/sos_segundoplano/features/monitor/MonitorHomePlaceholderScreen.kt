package com.example.sos_segundoplano.features.monitor

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.core.push.MonitorAlertProvider
import com.example.sos_segundoplano.data.remote.incident.AndroidCurrentManualSosLocationProvider
import com.example.sos_segundoplano.data.remote.monitor.MonitorAlertsProvider
import com.example.sos_segundoplano.data.remote.routing.MonitorIncidentRoutingProvider
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.auth.authenticatedIdentityOrNull
import com.example.sos_segundoplano.domain.monitor.MonitorAlertAcknowledgement
import com.example.sos_segundoplano.domain.monitor.MonitorAlertStatusLocation
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.features.history.HistoryDiagnostics
import com.example.sos_segundoplano.ui.components.MotoAssetIcon
import com.example.sos_segundoplano.ui.components.MotoTopBar
import com.example.sos_segundoplano.ui.format.DisplayFormatters
import com.example.sos_segundoplano.ui.format.monitorResponseLabel
import com.example.sos_segundoplano.ui.format.monitorStatusLabel
import com.example.sos_segundoplano.ui.maps.MotoIncidentResponseMap
import com.example.sos_segundoplano.ui.maps.MotoMapPoint
import com.example.sos_segundoplano.ui.maps.MotoSinglePointMap
import com.example.sos_segundoplano.ui.theme.MotoAlert
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoSuccessSoft
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary
import com.example.sos_segundoplano.ui.theme.MotoWarning
import kotlinx.coroutines.launch
import kotlin.math.ceil

data class MonitorProfileUiData(
    val fullName: String,
    val email: String,
    val phoneNumber: String,
    val isActive: Boolean
)

@Composable
fun MonitorRoot(authRepository: AuthRepository) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val sessionState by authRepository.observeSession().collectAsStateWithLifecycle()
    val sessionIdentity = sessionState.authenticatedIdentityOrNull()
    val monitorProfile = when (val session = sessionState) {
        is SessionState.Authenticated -> session.user
        is SessionState.Refreshing -> session.user
        else -> null
    }?.takeIf { it.role == UserRole.Monitor }?.let { user ->
        MonitorProfileUiData(
            fullName = user.fullName,
            email = user.email,
            phoneNumber = user.phoneNumber,
            isActive = user.isActive
        )
    }
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
    val mapViewModel = remember(context) {
        MonitorIncidentMapViewModel(
            currentLocationProvider = AndroidCurrentManualSosLocationProvider(context),
            routingRepository = MonitorIncidentRoutingProvider.repository
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mapState by mapViewModel.state.collectAsStateWithLifecycle()
    val consumedFcmAttemptId by viewModel.consumedFcmAttemptId.collectAsStateWithLifecycle()
    val successfulActionRevision by viewModel.successfulActionRevision.collectAsStateWithLifecycle()
    val historyViewModel = remember(context) {
        MonitorAlertHistoryViewModel(
            repository = MonitorAlertsProvider.get(context),
            isMonitorSession = {
                authRepository.observeSession().value.let {
                    it is SessionState.Authenticated && it.user.role == UserRole.Monitor ||
                        it is SessionState.Refreshing && it.user.role == UserRole.Monitor
                }
            }
        )
    }
    val historyState by historyViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(consumedFcmAttemptId) {
        if (consumedFcmAttemptId != null) {
            HistoryDiagnostics.debug("event=monitor_history_refresh_trigger source=fcm")
            historyViewModel.refresh()
        }
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
        onOpenHistoryAlert = viewModel::open,
        mapState = mapState,
        onLoadMapRoute = mapViewModel::load,
        onClearMap = mapViewModel::clear,
        profileData = monitorProfile,
        onLogout = {
            sessionIdentity?.let { expected ->
                scope.launch { authRepository.logoutIfCurrent(expected) }
            }
        }
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
    Text("Las herramientas del Monitor están disponibles desde el panel principal.", color = MotoTextSecondary)
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
    mapState: MonitorIncidentMapUiState = MonitorIncidentMapUiState.Idle,
    onLoadMapRoute: (MotoMapPoint) -> Unit = {},
    onClearMap: () -> Unit = {},
    profileData: MonitorProfileUiData? = null,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dialog by remember { mutableStateOf<MonitorDialog?>(null) }
    var destination by remember {
        mutableStateOf(state.initialMonitorDestination())
    }
    var lastAutoOpenedAttemptId by remember { mutableStateOf<String?>(null) }
    val currentAttemptId = (state as? MonitorAlertsUiState.Alert)
        ?.takeIf { it.isPendingAlert() }
        ?.attemptId
        ?.value

    LaunchedEffect(currentAttemptId) {
        if (currentAttemptId != null && currentAttemptId != lastAutoOpenedAttemptId) {
            lastAutoOpenedAttemptId = currentAttemptId
            if (destination !is MonitorDestination.HistoryDetail) {
                destination = MonitorDestination.CurrentAlert
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MotoBackground)
            .testTag("monitor_home_screen")
            .onGloballyPositioned { coordinates ->
                HistoryDiagnostics.debug(
                    "event=monitor_history_layout node=root width_px=${coordinates.size.width} height_px=${coordinates.size.height}"
                )
            }
    ) {
        MotoTopBar(
            title = destination.title(),
            subtitle = when {
                destination == MonitorDestination.Home -> "Contacto de emergencia"
                state is MonitorAlertsUiState.Alert && destination in setOf(MonitorDestination.CurrentAlert, MonitorDestination.IncidentMap) -> stringResource(R.string.monitor_active)
                else -> null
            },
            showNavigationIcon = false,
            showNotificationsIcon = false
        )

        MonitorTopActions(
            destination = destination,
            onRefresh = {
                when (destination) {
                    MonitorDestination.Incidents,
                    MonitorDestination.Home -> onRefreshHistory()
                    else -> onRefresh()
                }
            }
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (destination) {
                MonitorDestination.Home -> MonitorDashboardContent(
                    state = state,
                    historyState = historyState,
                    onOpenPendingAlert = { id ->
                        destination = MonitorDestination.HistoryDetail(id)
                        onOpenHistoryAlert(id)
                    },
                    onOpenIncidents = { destination = MonitorDestination.Incidents },
                    onRefresh = {
                        onRefresh()
                        onRefreshHistory()
                    }
                )

                MonitorDestination.Incidents -> MonitorHistoryContent(
                    state = historyState,
                    refresh = onRefreshHistory,
                    modifier = Modifier.fillMaxSize(),
                    open = { id ->
                        destination = MonitorDestination.HistoryDetail(id)
                        onOpenHistoryAlert(id)
                    },
                    emptyMessage = "No hay alertas registradas."
                )

                MonitorDestination.CurrentAlert,
                is MonitorDestination.HistoryDetail -> when (state) {
                    MonitorAlertsUiState.Ready -> ReadyContent()
                    is MonitorAlertsUiState.Loading -> LoadingContent()
                    is MonitorAlertsUiState.Error -> ErrorContent(state.message, onRetry)
                    is MonitorAlertsUiState.Alert -> AlertContent(
                        state = state,
                        onOpenAcknowledge = { dialog = MonitorDialog.Acknowledge },
                        onOpenDecline = { dialog = MonitorDialog.Decline },
                        onRetry = onRetry,
                        onViewIncidentLocation = { incidentPoint ->
                            onClearMap()
                            destination = MonitorDestination.IncidentMap
                            onLoadMapRoute(incidentPoint)
                        },
                        onBackToIncidents = (destination as? MonitorDestination.HistoryDetail)?.let {
                            { destination = MonitorDestination.Incidents }
                        }
                    )
                }

                MonitorDestination.IncidentMap -> MonitorIncidentMapTab(
                    alertState = state as? MonitorAlertsUiState.Alert,
                    mapState = mapState,
                    onLoadMapRoute = onLoadMapRoute,
                    onRefreshAlert = onRefresh,
                    onBackToAlert = { destination = MonitorDestination.CurrentAlert }
                )

                MonitorDestination.Profile -> MonitorProfileContent(profileData, onLogout)
            }
        }

        MonitorBottomBar(
            selected = destination.bottomTab(),
            onSelect = { tab ->
                destination = when (tab) {
                    MonitorTab.Home -> MonitorDestination.Home
                    MonitorTab.Incidents -> MonitorDestination.Incidents
                    MonitorTab.Map -> MonitorDestination.IncidentMap
                    MonitorTab.Profile -> MonitorDestination.Profile
                }
            }
        )
    }

    dialog?.let { mode ->
        ActionDialog(
            mode = mode,
            onDismiss = { dialog = null },
            onSubmit = { value ->
                dialog = null
                if (mode == MonitorDialog.Acknowledge) onAcknowledge(value) else onDecline(value)
            }
        )
    }
}

@Composable
private fun MonitorTopActions(
    destination: MonitorDestination,
    onRefresh: () -> Unit
) {
    // Inicio se mantiene limpio: resumen + alerta pendiente más reciente.
    // Mapa tiene sus propios controles y Perfil/Detalle no necesitan una barra de acciones.
    if (destination != MonitorDestination.Incidents) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = monitorPagePadding(), vertical = 6.dp),
        horizontalArrangement = Arrangement.End
    ) {
        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier
                .semantics { contentDescription = "Actualizar incidentes" }
                .testTag("monitor_refresh_button")
        ) { Text("Actualizar") }
    }
}

@Composable
private fun MonitorDashboardContent(
    state: MonitorAlertsUiState,
    historyState: MonitorAlertHistoryUiState,
    onOpenPendingAlert: (String) -> Unit,
    onOpenIncidents: () -> Unit,
    onRefresh: () -> Unit
) {
    val historyAlerts = (historyState as? MonitorAlertHistoryUiState.Content)?.alerts.orEmpty()
    val currentAlertState = state as? MonitorAlertsUiState.Alert
    val finalCurrentAttemptId = currentAlertState
        ?.takeUnless { it.isPendingAlert() }
        ?.attemptId
        ?.value
    val livePending = currentAlertState
        ?.takeIf { it.isPendingAlert() }
        ?.detail
        ?.acknowledgement
    val pendingHistory = historyAlerts.filter {
        it.isPendingAlert() && it.notificationDeliveryAttemptId != finalCurrentAttemptId
    }
    val pendingAlerts = (pendingHistory + listOfNotNull(livePending))
        .distinctBy { it.notificationDeliveryAttemptId }
    val latestPending = pendingAlerts.maxByOrNull { it.createdAtUtc.orEmpty() }

    val pending = pendingHistory.size
        .coerceAtLeast(if (latestPending != null) 1 else 0)
    val acknowledged = historyAlerts.count { it.isAcknowledgedAlert() }
    val declined = historyAlerts.count { it.isDeclinedAlert() }

    Column(
        Modifier.fillMaxSize().padding(monitorPagePadding()).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DetailCard("Resumen de alertas") {
            MonitorSummaryRow("Pendientes", pending.toString(), MotoAlert)
            MonitorSummaryRow("Confirmadas", acknowledged.toString(), MotoSuccess)
            MonitorSummaryRow("No puedo ayudar", declined.toString(), MotoWarning)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenIncidents, modifier = Modifier.fillMaxWidth().testTag("monitor_dashboard_open_incidents")) {
                Text("Ver incidentes")
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("monitor_dashboard_refresh")) {
                Text("Actualizar")
            }
        }

        DetailCard("Alerta pendiente más reciente") {
            when {
                latestPending != null -> {
                    val liveState = (state as? MonitorAlertsUiState.Alert)
                        ?.takeIf { it.attemptId.value == latestPending.notificationDeliveryAttemptId && it.isPendingAlert() }
                    if (liveState != null) {
                        AlertCard(
                            icon = "⚠",
                            title = localizedIncidentTitle(liveState.status?.incident?.cause),
                            subtitle = safeMonitorStatusLabel(latestPending.status),
                            color = emergencyColorFor(latestPending.status),
                            risk = localizedRiskLabel(liveState.status?.incident?.riskLevel),
                            priority = localizedPriorityLabel(liveState.status?.alertDispatch?.priority)
                        )
                    } else {
                        Text("Alerta de emergencia", color = MotoPrimaryDark, fontWeight = FontWeight.Bold)
                        Text(safeMonitorStatusLabel(latestPending.status), color = emergencyColorFor(latestPending.status))
                        Text(
                            DisplayFormatters.dateTime(latestPending.createdAtUtc) ?: "Fecha no disponible",
                            color = MotoTextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Button(
                        onClick = { onOpenPendingAlert(latestPending.notificationDeliveryAttemptId) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("monitor_dashboard_open_alert"),
                        colors = ButtonDefaults.buttonColors(containerColor = MotoPrimaryDark)
                    ) { Text("Ver alerta") }
                }
                historyState == MonitorAlertHistoryUiState.Loading -> {
                    Text("Actualizando alertas…", color = MotoTextSecondary)
                }
                historyState is MonitorAlertHistoryUiState.Error -> {
                    Text("No pudimos comprobar las alertas pendientes.", color = MotoWarning)
                    Text("Pulsa Actualizar para intentarlo nuevamente.", color = MotoTextSecondary)
                }
                else -> {
                    Text("✓ No tienes alertas pendientes", color = MotoSuccess, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("monitor_no_pending_alerts"))
                    Text("Las alertas confirmadas o rechazadas permanecen disponibles en Incidentes.", color = MotoTextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun MonitorSummaryRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MotoTextSecondary)
        Text(value, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MonitorHistoryContent(
    state: MonitorAlertHistoryUiState,
    refresh: () -> Unit,
    modifier: Modifier = Modifier,
    open: (String) -> Unit,
    emptyMessage: String
) = when (state) {
    MonitorAlertHistoryUiState.Loading -> LoadingContent()
    MonitorAlertHistoryUiState.Empty -> Column(Modifier.fillMaxSize().padding(24.dp)) { Text(emptyMessage) }
    is MonitorAlertHistoryUiState.Error -> HistoryErrorContent(refresh)
    is MonitorAlertHistoryUiState.Content -> LazyColumn(
        modifier
            .padding(monitorPagePadding())
            .onGloballyPositioned { coordinates ->
                HistoryDiagnostics.debug(
                    "event=monitor_history_layout node=history_list width_px=${coordinates.size.width} height_px=${coordinates.size.height}"
                )
            },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(state.alerts) { index, alert ->
            if (index == 0) {
                LaunchedEffect(Unit) { HistoryDiagnostics.debug("event=monitor_history_first_item_composed") }
            }
            OutlinedButton(
                onClick = { open(alert.notificationDeliveryAttemptId) },
                modifier = (if (index == 0) {
                    Modifier.onGloballyPositioned { coordinates ->
                        val rootPosition = coordinates.positionInRoot()
                        val windowPosition = coordinates.positionInWindow()
                        HistoryDiagnostics.debug(
                            "event=monitor_history_first_item_position x_root=${rootPosition.x} y_root=${rootPosition.y} x_window=${windowPosition.x} y_window=${windowPosition.y} width_px=${coordinates.size.width} height_px=${coordinates.size.height} is_attached=${coordinates.isAttached}"
                        )
                    }
                } else Modifier)
                    .fillMaxWidth()
                    .semantics { contentDescription = "Abrir detalle de alerta" }
                    .testTag("monitor_history_alert_${alert.notificationDeliveryAttemptId}")
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Alerta de emergencia", color = MotoPrimaryDark, fontWeight = FontWeight.SemiBold)
                        Text(safeMonitorStatusLabel(alert.status), color = emergencyColorFor(alert.status))
                    }
                    localizedResponseOrNull(alert.responseType)?.let { Text(it, color = MotoTextSecondary) }
                    Text(
                        DisplayFormatters.dateTime(alert.createdAtUtc).takeUnless { it.isNullOrBlank() } ?: "Fecha no disponible",
                        color = MotoTextSecondary
                    )
                }
            }
        }
        state.error?.let {
            item {
                Text("No pudimos actualizar los incidentes.", color = MotoWarning)
                Text("Intenta nuevamente.", color = MotoTextSecondary)
            }
        }
    }
}

@Composable
private fun ReadyContent() = Column(
    Modifier.fillMaxSize().padding(monitorPagePadding()).verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
) {
    Text("✓", color = MotoSuccess, modifier = Modifier.testTag("monitor_ready_icon"))
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.monitor_active), color = MotoPrimaryDark)
    Text(stringResource(R.string.monitor_ready_description), color = MotoTextSecondary, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun LoadingContent(compact: Boolean = false) = Column(
    Modifier
        .then(if (compact) Modifier.fillMaxWidth().padding(24.dp) else Modifier.fillMaxSize())
        .testTag("monitor_alert_loading"),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
) {
    CircularProgressIndicator()
    Text(stringResource(R.string.monitor_alert_loading_text), Modifier.padding(top = 16.dp))
}

@Composable
private fun ErrorContent(message: String, retry: () -> Unit, compact: Boolean = false) = Column(
    Modifier
        .then(if (compact) Modifier.fillMaxWidth().padding(24.dp) else Modifier.fillMaxSize().padding(24.dp))
        .testTag("monitor_alert_error"),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
) {
    Text(message, color = MotoTextSecondary)
    Button(onClick = retry, modifier = Modifier.padding(top = 16.dp).testTag("monitor_alert_retry")) { Text(stringResource(R.string.retry)) }
}

@Composable
private fun HistoryErrorContent(retry: () -> Unit) = Column(
    Modifier.fillMaxSize().padding(24.dp).testTag("monitor_alert_error"),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
) {
    Text("No pudimos cargar los incidentes.", color = MotoTextSecondary)
    Text("Intenta nuevamente.", color = MotoTextSecondary, modifier = Modifier.padding(top = 8.dp))
    Button(onClick = retry, modifier = Modifier.padding(top = 16.dp).testTag("monitor_alert_retry")) { Text(stringResource(R.string.retry)) }
}

@Composable
private fun AlertContent(
    state: MonitorAlertsUiState.Alert,
    onOpenAcknowledge: () -> Unit,
    onOpenDecline: () -> Unit,
    onRetry: () -> Unit,
    onViewIncidentLocation: (MotoMapPoint) -> Unit,
    onBackToIncidents: (() -> Unit)?
) {
    val acknowledgement = state.detail.acknowledgement
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

    Column(
        Modifier.fillMaxSize().padding(monitorPagePadding()).verticalScroll(rememberScrollState()).testTag("monitor_alert_detail")
    ) {
        onBackToIncidents?.let { onBack ->
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .semantics { contentDescription = "Volver a incidentes" }
                    .testTag("monitor_alert_back_to_incidents")
            ) { Text("Volver a incidentes") }
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
        if (enrichedStatus?.requiresAttention == true) {
            Text("Requiere atención", color = MotoWarning, modifier = Modifier.padding(top = 8.dp))
        }

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
            DetailRow(
                if (occurredAtUtc != null) "Ocurrió" else "Registro",
                DisplayFormatters.dateTime(occurredAtUtc ?: acknowledgement?.createdAtUtc) ?: "Fecha no disponible"
            )
            localizedPriorityLabel(enrichedStatus?.alertDispatch?.priority)?.let { DetailRow("Prioridad", it) }
            localizedTripStatus(enrichedStatus?.trip?.status)?.let { DetailRow("Viaje", it) }
            DetailRow("Vista", DisplayFormatters.dateTime(acknowledgement?.viewedAtUtc))
            DetailRow("Atendida", DisplayFormatters.dateTime(acknowledgement?.acknowledgedAtUtc))
            DetailRow("Rechazada", DisplayFormatters.dateTime(acknowledgement?.declinedAtUtc))
        }

        Spacer(Modifier.height(16.dp))
        DetailCard("Ubicación del incidente") {
            if (usableLocation != null) {
                val incidentPoint = MotoMapPoint(usableLocation.latitude!!, usableLocation.longitude!!)
                Text("Última ubicación disponible", color = MotoTextSecondary)
                Text("%.5f, %.5f".format(usableLocation.latitude, usableLocation.longitude), color = MotoPrimaryDark)
                usableLocation.accuracyMeters
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?.let { Text("Precisión: %.0f m".format(it), color = MotoTextSecondary) }
                Spacer(Modifier.height(10.dp))
                MonitorLocationMap(incidentPoint.latitude, incidentPoint.longitude)
                Button(
                    onClick = { onViewIncidentLocation(incidentPoint) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .semantics { contentDescription = "Ver ubicación del incidente" }
                        .testTag("monitor_open_location_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = MotoPrimaryDark)
                ) { Text("Ver ubicación del incidente") }
            } else {
                Text("Ubicación no disponible para esta alerta.", color = MotoTextSecondary)
            }
        }

        state.notice?.let { notice ->
            Spacer(Modifier.height(16.dp))
            Notice(notice)
        }

        if (!final) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onOpenAcknowledge,
                enabled = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Confirmar recibido" }
                    .testTag("monitor_acknowledge_button"),
                colors = ButtonDefaults.buttonColors(containerColor = MotoSuccess)
            ) {
                if (isSubmitting) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White)
                else Text(stringResource(R.string.monitor_confirm_received))
            }
            OutlinedButton(
                onClick = onOpenDecline,
                enabled = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .semantics { contentDescription = "No puedo ayudar" }
                    .testTag("monitor_decline_button")
            ) { Text(stringResource(R.string.monitor_decline)) }
        }

        if (state.notice is MonitorAlertNotice.NonBlockingError) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun MonitorIncidentMapTab(
    alertState: MonitorAlertsUiState.Alert?,
    mapState: MonitorIncidentMapUiState,
    onLoadMapRoute: (MotoMapPoint) -> Unit,
    onRefreshAlert: () -> Unit,
    onBackToAlert: () -> Unit
) {
    val context = LocalContext.current
    val incidentLocation = alertState?.status?.location?.takeIf { it.hasUsableCoordinates() }
    val incidentPoint = incidentLocation?.let { MotoMapPoint(it.latitude!!, it.longitude!!) }
    var recenterToken by remember { mutableIntStateOf(0) }
    var permissionRevision by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionRevision++
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted && incidentPoint != null) onLoadMapRoute(incidentPoint)
    }
    val hasPermission = permissionRevision.let { hasMonitorLocationPermission(context) }

    val loadedIncidentPoint = when (mapState) {
        is MonitorIncidentMapUiState.LoadingRoute -> mapState.incidentPoint
        is MonitorIncidentMapUiState.Ready -> mapState.incidentPoint
        is MonitorIncidentMapUiState.RouteUnavailable -> mapState.incidentPoint
        is MonitorIncidentMapUiState.LocationUnavailable -> mapState.incidentPoint
        MonitorIncidentMapUiState.Idle, MonitorIncidentMapUiState.LocatingMonitor -> null
    }
    LaunchedEffect(incidentPoint, hasPermission, loadedIncidentPoint) {
        if (incidentPoint != null && hasPermission && loadedIncidentPoint != incidentPoint && mapState !is MonitorIncidentMapUiState.LocatingMonitor) {
            onLoadMapRoute(incidentPoint)
        }
    }

    if (incidentPoint == null) {
        Column(
            Modifier.fillMaxSize().padding(monitorPagePadding()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("No hay una ubicación de incidente disponible para mostrar.", color = MotoTextSecondary)
            OutlinedButton(onClick = onBackToAlert, modifier = Modifier.padding(top = 16.dp)) { Text("Volver a la alerta") }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = monitorPagePadding(), vertical = 10.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DetailCard("Ruta de asistencia") {
            Text("MotoSOS muestra el camino estimado desde tu ubicación actual hasta el incidente.", color = MotoTextSecondary)
            Text(
                "Incidente: %.5f, %.5f".format(incidentPoint.latitude, incidentPoint.longitude),
                color = MotoPrimaryDark,
                modifier = Modifier.padding(top = 8.dp)
            )
            incidentLocation.accuracyMeters
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.let { Text("Precisión del incidente: ±%.0f m".format(it), color = MotoTextSecondary) }
        }

        if (!hasPermission) {
            DetailCard("Tu ubicación") {
                Text("Necesitamos tu ubicación para calcular el recorrido hasta el incidente.", color = MotoTextSecondary)
                Button(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                ) { Text("Permitir ubicación") }
            }
            MotoSinglePointMap(
                point = incidentPoint,
                modifier = Modifier.fillMaxWidth().height(monitorFallbackMapHeight()),
                markerColor = MotoAlert,
                zoom = 15.0
            )
            return@Column
        }

        when (mapState) {
            MonitorIncidentMapUiState.Idle,
            MonitorIncidentMapUiState.LocatingMonitor -> {
                DetailCard("Buscando tu ubicación") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp))
                        Text("Obteniendo posición del Monitor…", color = MotoTextSecondary, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                MotoSinglePointMap(
                    point = incidentPoint,
                    modifier = Modifier.fillMaxWidth().height(monitorFallbackMapHeight()),
                    markerColor = MotoAlert,
                    zoom = 15.0
                )
            }

            is MonitorIncidentMapUiState.LoadingRoute -> {
                MonitorRouteMapCard(
                    monitorPoint = mapState.monitorPoint,
                    incidentPoint = mapState.incidentPoint,
                    routePoints = emptyList(),
                    recenterToken = recenterToken,
                    statusText = "Calculando ruta por calles…"
                )
            }

            is MonitorIncidentMapUiState.Ready -> {
                MonitorRouteMapCard(
                    monitorPoint = mapState.monitorPoint,
                    incidentPoint = mapState.incidentPoint,
                    routePoints = mapState.route.points,
                    recenterToken = recenterToken,
                    statusText = "Ruta estimada por calles"
                )
                DetailCard("Recorrido") {
                    DetailRow("Distancia", formatRouteDistance(mapState.route.distanceMeters))
                    DetailRow("Tiempo estimado", formatRouteDuration(mapState.route.durationSeconds))
                    Text(
                        "La ruta es una referencia de asistencia. Respeta tránsito, cierres y condiciones reales del camino.",
                        color = MotoTextSecondary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            is MonitorIncidentMapUiState.RouteUnavailable -> {
                MonitorRouteMapCard(
                    monitorPoint = mapState.monitorPoint,
                    incidentPoint = mapState.incidentPoint,
                    routePoints = emptyList(),
                    recenterToken = recenterToken,
                    statusText = "Ruta no disponible"
                )
                NoticeBox(mapState.message)
            }

            is MonitorIncidentMapUiState.LocationUnavailable -> {
                MotoSinglePointMap(
                    point = mapState.incidentPoint,
                    modifier = Modifier.fillMaxWidth().height(monitorFallbackMapHeight()),
                    markerColor = MotoAlert,
                    zoom = 15.0
                )
                NoticeBox(mapState.message)
            }
        }

        val compactRouteActions = LocalConfiguration.current.screenWidthDp < 360
        if (compactRouteActions) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        onRefreshAlert()
                        onLoadMapRoute(incidentPoint)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("monitor_route_refresh")
                ) { Text("Actualizar ruta") }
                OutlinedButton(
                    onClick = { recenterToken++ },
                    modifier = Modifier.fillMaxWidth().testTag("monitor_route_recenter")
                ) { Text("Centrar recorrido") }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        onRefreshAlert()
                        onLoadMapRoute(incidentPoint)
                    },
                    modifier = Modifier.weight(1f).testTag("monitor_route_refresh")
                ) { Text("Actualizar ruta") }
                OutlinedButton(
                    onClick = { recenterToken++ },
                    modifier = Modifier.weight(1f).testTag("monitor_route_recenter")
                ) { Text("Centrar recorrido") }
            }
        }

        OutlinedButton(onClick = onBackToAlert, modifier = Modifier.fillMaxWidth()) { Text("Volver a la alerta") }
    }
}

@Composable
private fun MonitorRouteMapCard(
    monitorPoint: MotoMapPoint,
    incidentPoint: MotoMapPoint,
    routePoints: List<MotoMapPoint>,
    recenterToken: Int,
    statusText: String
) {
    Column(
        Modifier.fillMaxWidth().background(MotoSurface, RoundedCornerShape(18.dp)).border(1.dp, MotoDivider, RoundedCornerShape(18.dp))
    ) {
        MotoIncidentResponseMap(
            monitorPoint = monitorPoint,
            incidentPoint = incidentPoint,
            routePoints = routePoints,
            recenterToken = recenterToken,
            modifier = Modifier.fillMaxWidth().height(monitorRouteMapHeight()).testTag("monitor_incident_route_map")
        )
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(statusText, color = MotoPrimaryDark, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = MotoPrimaryBlue)
                Text(" Monitor", color = MotoTextSecondary)
                Spacer(Modifier.size(18.dp))
                Text("●", color = MotoAlert)
                Text(" Incidente", color = MotoTextSecondary)
            }
            Text("Arrastra para mover • Pellizca para acercar", color = MotoTextSecondary)
        }
    }
}

@Composable
private fun NoticeBox(message: String) {
    Text(
        message,
        modifier = Modifier.fillMaxWidth().background(MotoWarning.copy(alpha = .10f), RoundedCornerShape(12.dp)).padding(14.dp),
        color = MotoWarning
    )
}

private fun formatRouteDistance(distanceMeters: Double): String = when {
    distanceMeters < 1000.0 -> "%.0f m".format(distanceMeters)
    else -> "%.1f km".format(distanceMeters / 1000.0)
}

private fun formatRouteDuration(durationSeconds: Double): String {
    val minutes = ceil(durationSeconds / 60.0).toInt().coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else {
        val hours = minutes / 60
        val remainder = minutes % 60
        if (remainder == 0) "$hours h" else "$hours h $remainder min"
    }
}

private fun hasMonitorLocationPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
private fun MonitorProfileContent(profileData: MonitorProfileUiData?, onLogout: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(monitorPagePadding()).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Mi perfil", style = MaterialTheme.typography.titleLarge, color = MotoPrimaryDark, fontWeight = FontWeight.Bold)
        Text("Información de la cuenta Monitor con la que iniciaste sesión.", color = MotoTextSecondary)
        DetailCard("Datos del Monitor") {
            if (profileData == null) {
                Text("No pudimos leer los datos de la sesión actual.", color = MotoTextSecondary)
            } else {
                DetailRow("Nombre", profileData.fullName)
                DetailRow("Correo", profileData.email)
                DetailRow("Teléfono", profileData.phoneNumber)
                DetailRow("Rol", "Monitor")
                DetailRow("Estado", if (profileData.isActive) "Cuenta activa" else "Cuenta inactiva")
            }
        }
        DetailCard("Notificaciones") {
            Text("MotoSOS usa esta cuenta para recibir y atender las alertas de los Riders vinculados.", color = MotoTextSecondary)
        }
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth().testTag("monitor_profile_logout")
        ) { Text(stringResource(R.string.profile_logout)) }
    }
}

private sealed interface MonitorDestination {
    data object Home : MonitorDestination
    data object Incidents : MonitorDestination
    data object CurrentAlert : MonitorDestination
    data object IncidentMap : MonitorDestination
    data class HistoryDetail(val notificationDeliveryAttemptId: String) : MonitorDestination
    data object Profile : MonitorDestination
}

private fun MonitorAlertsUiState.Alert.isPendingAlert(): Boolean =
    detail.acknowledgement?.isPendingAlert() ?: true

private fun MonitorAlertAcknowledgement.isPendingAlert(): Boolean =
    acknowledgedAtUtc == null && declinedAtUtc == null &&
        !status.equals("Acknowledged", ignoreCase = true) &&
        !status.equals("Declined", ignoreCase = true)

private fun MonitorAlertAcknowledgement.isAcknowledgedAlert(): Boolean =
    acknowledgedAtUtc != null || status.equals("Acknowledged", ignoreCase = true)

private fun MonitorAlertAcknowledgement.isDeclinedAlert(): Boolean =
    declinedAtUtc != null || status.equals("Declined", ignoreCase = true)

private fun MonitorAlertsUiState.initialMonitorDestination(): MonitorDestination =
    if (this is MonitorAlertsUiState.Alert) MonitorDestination.CurrentAlert else MonitorDestination.Home

private fun MonitorDestination.title(): String = when (this) {
    MonitorDestination.Home -> "Monitor"
    MonitorDestination.Incidents -> "Incidentes"
    MonitorDestination.CurrentAlert -> "Alerta recibida"
    MonitorDestination.IncidentMap -> "Ubicación del incidente"
    is MonitorDestination.HistoryDetail -> "Detalle de la alerta"
    MonitorDestination.Profile -> "Perfil"
}

private enum class MonitorTab { Home, Incidents, Map, Profile }

private fun MonitorDestination.bottomTab(): MonitorTab = when (this) {
    MonitorDestination.Home -> MonitorTab.Home
    MonitorDestination.Incidents,
    MonitorDestination.CurrentAlert,
    is MonitorDestination.HistoryDetail -> MonitorTab.Incidents
    MonitorDestination.IncidentMap -> MonitorTab.Map
    MonitorDestination.Profile -> MonitorTab.Profile
}

@Composable
private fun MonitorBottomBar(selected: MonitorTab, onSelect: (MonitorTab) -> Unit) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth().testTag("monitor_bottom_bar"),
        containerColor = MotoSurface,
        tonalElevation = 0.dp
    ) {
        MonitorBottomItem(MonitorTab.Home, selected, R.drawable.ic_nav_home, "Inicio", "monitor_bottom_home", onSelect)
        MonitorBottomItem(MonitorTab.Incidents, selected, R.drawable.ic_alert_warning, "Incidentes", "monitor_bottom_incidents", onSelect)
        MonitorBottomItem(MonitorTab.Map, selected, R.drawable.ic_location_pin, "Mapa", "monitor_bottom_map", onSelect)
        MonitorBottomItem(MonitorTab.Profile, selected, R.drawable.ic_nav_profile, "Perfil", "monitor_bottom_profile", onSelect)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MonitorBottomItem(
    item: MonitorTab,
    selected: MonitorTab,
    iconRes: Int,
    label: String,
    tag: String,
    onSelect: (MonitorTab) -> Unit
) {
    val active = item == selected
    val color = if (active) MotoPrimaryDark else MotoTextSecondary
    val compact = LocalConfiguration.current.screenWidthDp < 360
    NavigationBarItem(
        selected = active,
        onClick = { onSelect(item) },
        modifier = Modifier.testTag(tag).semantics { contentDescription = label },
        icon = {
            MotoAssetIcon(
                iconRes = iconRes,
                contentDescription = null,
                viewportSize = if (compact) 27.dp else 31.dp,
                assetSize = if (item == MonitorTab.Incidents) {
                    if (compact) 31.dp else 35.dp
                } else {
                    if (compact) 43.dp else 48.dp
                },
                tint = if (iconRes == R.drawable.ic_alert_warning || iconRes == R.drawable.ic_time_history) null else color
            )
        },
        label = {
            Text(
                label,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        },
        alwaysShowLabel = true
    )
}

@Composable
private fun monitorPagePadding(): Dp = when (LocalConfiguration.current.screenWidthDp) {
    in Int.MIN_VALUE..359 -> 12.dp
    in 360..599 -> 16.dp
    else -> 24.dp
}

@Composable
private fun monitorRouteMapHeight(): Dp {
    val configuration = LocalConfiguration.current
    return when {
        configuration.screenWidthDp >= 600 -> 460.dp
        configuration.screenHeightDp < 680 -> 300.dp
        configuration.screenHeightDp < 800 -> 350.dp
        else -> 400.dp
    }
}

@Composable
private fun monitorFallbackMapHeight(): Dp {
    val configuration = LocalConfiguration.current
    return when {
        configuration.screenWidthDp >= 600 -> 420.dp
        configuration.screenHeightDp < 680 -> 270.dp
        else -> 330.dp
    }
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
    "Unknown" -> "No determinado"
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

@Composable
private fun MonitorLocationMap(latitude: Double, longitude: Double) {
    MotoSinglePointMap(
        point = MotoMapPoint(latitude = latitude, longitude = longitude),
        modifier = Modifier.fillMaxWidth().height(190.dp),
        markerColor = MotoAlert,
        zoom = 16.0
    )
}

private fun emergencyColorFor(status: String?): Color = when (status) {
    "Acknowledged" -> MotoSuccess
    "Declined" -> MotoWarning
    else -> MotoAlert
}

private fun MonitorAlertStatusLocation?.hasUsableCoordinates(): Boolean = this?.let {
    it.available != false && it.latitude != null && it.longitude != null &&
        it.latitude.isFinite() && it.longitude.isFinite() &&
        it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 &&
        !(it.latitude == 0.0 && it.longitude == 0.0)
} == true

@Composable
private fun AlertCard(
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
        Text(title, color = color, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = MotoTextSecondary)
        risk?.let { Text("Riesgo: $it", color = MotoTextSecondary, modifier = Modifier.padding(top = 6.dp)) }
        priority?.let { Text("Prioridad: $it", color = MotoTextSecondary) }
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) = Column(
    Modifier.fillMaxWidth().background(MotoSurface, RoundedCornerShape(16.dp)).border(1.dp, MotoDivider, RoundedCornerShape(16.dp)).padding(18.dp)
) {
    Text(title, color = MotoPrimaryDark, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    content()
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (!value.isNullOrBlank()) {
        if (LocalConfiguration.current.screenWidthDp < 360) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(label, color = MotoTextSecondary)
                Text(value, color = MotoPrimaryDark, modifier = Modifier.padding(top = 2.dp))
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(label, color = MotoTextSecondary, modifier = Modifier.weight(0.38f))
                Text(
                    value,
                    color = MotoPrimaryDark,
                    modifier = Modifier.weight(0.62f),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun Notice(notice: MonitorAlertNotice) {
    val (text, color) = when (notice) {
        MonitorAlertNotice.Confirmed -> stringResource(R.string.monitor_confirmed) to MotoSuccess
        MonitorAlertNotice.Declined -> stringResource(R.string.monitor_declined) to MotoWarning
        is MonitorAlertNotice.NonBlockingError -> notice.message to MotoWarning
    }
    Text(
        text,
        Modifier.fillMaxWidth()
            .background(if (color == MotoSuccess) MotoSuccessSoft else color.copy(alpha = .10f), RoundedCornerShape(12.dp))
            .padding(14.dp)
            .testTag("monitor_action_notice"),
        color = color
    )
}

private enum class MonitorDialog { Acknowledge, Decline }

@Composable
private fun ActionDialog(
    mode: MonitorDialog,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val label = if (mode == MonitorDialog.Acknowledge) stringResource(R.string.monitor_message) else stringResource(R.string.monitor_reason)
    val quickReplies = listOf(
        "Ya recibí tu alerta",
        "Voy en camino",
        "Estoy llamando a emergencias",
        "Estoy revisando tu ubicación",
        "¿Estás bien?"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode == MonitorDialog.Acknowledge) stringResource(R.string.monitor_can_help) else stringResource(R.string.monitor_decline)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (mode == MonitorDialog.Acknowledge) {
                    Text("Respuesta rápida", color = MotoTextSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quickReplies.forEach { reply ->
                            OutlinedButton(onClick = { text = reply }) { Text(reply) }
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("monitor_action_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(text.trim()) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("monitor_action_submit")
            ) { Text(stringResource(R.string.monitor_send)) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
