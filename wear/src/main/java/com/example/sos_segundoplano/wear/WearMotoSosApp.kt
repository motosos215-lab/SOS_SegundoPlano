package com.example.sos_segundoplano.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sos_segundoplano.wear.presentation.components.ConnectionBadge
import com.example.sos_segundoplano.wear.presentation.components.CompactMetricStrip
import com.example.sos_segundoplano.wear.presentation.components.EyebrowLabel
import com.example.sos_segundoplano.wear.presentation.components.HeroSurface
import com.example.sos_segundoplano.wear.presentation.components.InfoBanner
import com.example.sos_segundoplano.wear.presentation.components.MetricPill
import com.example.sos_segundoplano.wear.presentation.components.MotorcycleIllustration
import com.example.sos_segundoplano.wear.presentation.components.RoundPrimaryButton
import com.example.sos_segundoplano.wear.presentation.components.ScreenSubtitle
import com.example.sos_segundoplano.wear.presentation.components.ScreenTitle
import com.example.sos_segundoplano.wear.presentation.components.WearScreenScaffold
import com.example.sos_segundoplano.wear.presentation.theme.MotoProgressGreen
import com.example.sos_segundoplano.wear.presentation.theme.MotoBlueGlow
import com.example.sos_segundoplano.wear.presentation.theme.MotoGreen
import com.example.sos_segundoplano.wear.presentation.theme.MotoTripBlue
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

private val MotoSosBlack = Color(0xFF05070A)
private val MotoSosNavy = Color(0xFF0B2447)
private val MotoSosBlue = Color(0xFF12395F)
private val MotoSosGreen = Color(0xFF34C759)
private val MotoSosRed = Color(0xFFFF3B30)
private val WearActionWidth = 180.dp
private const val MANUAL_SOS_HOLD_MILLIS = 2_000L
private const val HOLD_PROGRESS_INTERVAL_MILLIS = 40L

@Composable
internal fun WearMotoSosApp(
    tripStateStore: WearTripStateStore,
    controller: WearTripUiController,
    validationController: WearValidationUiController,
    manualSosController: WearManualSosUiController,
    onOpenHeartRatePermission: () -> Unit
) {
    val tripState by tripStateStore.state.collectAsState()
    val tripActionState by controller.actionState.collectAsState()
    val validationStatus by WearValidationStateStore.state.collectAsState()
    val validationActionState by validationController.actionState.collectAsState()
    val manualSosState by manualSosController.state.collectAsState()
    val signalSnapshot by WearSignalStateStore.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val healthPermissionChecker = remember(context) { AndroidWearHealthPermissionChecker(context) }

    LaunchedEffect(validationStatus?.sessionId, validationStatus?.assessmentId, validationStatus?.state) {
        validationController.onValidationStatusChanged(validationStatus)
    }

    WearMotoSosScreen(
        tripState = tripState,
        actionState = tripActionState,
        validationStatus = validationStatus,
        validationActionState = validationActionState,
        signalSnapshot = signalSnapshot,
        onStartTrip = {
            if (healthPermissionChecker.status() == WearPermissionStatus.Granted) {
                scope.launch { controller.startTrip() }
            } else {
                onOpenHeartRatePermission()
            }
        },
        onFinishTrip = { scope.launch { controller.finishTrip() } },
        onRetry = { scope.launch { controller.retry() } },
        onConfirmSafe = { scope.launch { validationController.confirmSafe() } },
        onRequestHelp = { scope.launch { validationController.requestHelp() } },
        onRetryValidation = { scope.launch { validationController.retry() } },
        manualSosState = manualSosState,
        onStartManualSosHold = manualSosController::startHold,
        onManualSosHoldProgress = manualSosController::updateHoldProgress,
        onCancelManualSosHold = manualSosController::cancelHold,
        onConfirmManualSosHold = { scope.launch { manualSosController.confirmHeld() } },
        onRetryManualSos = { scope.launch { manualSosController.retry() } },
        onDismissManualSos = manualSosController::beginNewManualSos,
        onRefresh = { scope.launch { controller.refresh() } },
        onOpenHeartRatePermission = onOpenHeartRatePermission
    )
}

@Composable
internal fun WearMotoSosScreen(
    tripState: WearTripState,
    actionState: WearTripUiActionState,
    onStartTrip: () -> Unit,
    onFinishTrip: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onOpenHeartRatePermission: () -> Unit,
    validationStatus: WearDataLayerProtocol.ValidationStatus? = null,
    validationActionState: WearValidationUiActionState = WearValidationUiActionState.Idle,
    signalSnapshot: WearSignalSnapshot = WearSignalSnapshot(),
    onConfirmSafe: () -> Unit = {},
    onRequestHelp: () -> Unit = {},
    onRetryValidation: () -> Unit = {},
    manualSosState: WearManualSosUiState = WearManualSosUiState.Idle,
    onStartManualSosHold: () -> Unit = {},
    onManualSosHoldProgress: (Float) -> Unit = {},
    onCancelManualSosHold: () -> Unit = {},
    onConfirmManualSosHold: () -> Unit = {},
    onRetryManualSos: () -> Unit = {},
    onDismissManualSos: () -> Unit = {}
) {
    var monitoringVisible by rememberSaveable { mutableStateOf(false) }
    var manualSosVisible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tripState.active) {
        if (tripState.active != true) {
            monitoringVisible = false
            if (manualSosState !is WearManualSosUiState.Success) manualSosVisible = false
        }
    }
    BackHandler(enabled = monitoringVisible || manualSosVisible) {
        when {
            monitoringVisible -> monitoringVisible = false
            manualSosState !is WearManualSosUiState.InFlight -> {
                manualSosVisible = false
                onDismissManualSos()
            }
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MotoSosBlack) {
        if (validationStatus?.isCountdownActive == true) {
            CountdownScreen(
                status = validationStatus,
                actionState = validationActionState,
                connected = tripState.connected,
                onConfirmSafe = onConfirmSafe,
                onRequestHelp = onRequestHelp,
                onRetry = onRetryValidation
            )
        } else {
            when {
                manualSosVisible && manualSosState is WearManualSosUiState.Success -> ManualSosAlertSentScreen(
                    tripState = tripState,
                    onDismiss = {
                        manualSosVisible = false
                        onDismissManualSos()
                    }
                )
                manualSosVisible -> ManualSosScreen(
                    state = manualSosState,
                    onHoldStart = onStartManualSosHold,
                    onHoldProgress = onManualSosHoldProgress,
                    onHoldCancel = onCancelManualSosHold,
                    onHoldComplete = onConfirmManualSosHold,
                    onRetry = onRetryManualSos
                )
                else -> when (tripState.active) {
                true -> if (monitoringVisible) {
                    MonitoringScreen(
                        tripState = tripState,
                        signalSnapshot = signalSnapshot,
                        onBack = { monitoringVisible = false }
                    )
                } else {
                    ActiveTripScreen(
                        tripState = tripState,
                        actionState = actionState,
                        signalSnapshot = signalSnapshot,
                        validationStatus = validationStatus,
                        onOpenMonitoring = { monitoringVisible = true },
                        onOpenHeartRatePermission = onOpenHeartRatePermission,
                        onFinish = onFinishTrip,
                        onRetry = onRetry,
                        onOpenManualSos = { manualSosVisible = true }
                    )
                }
                false -> ReadyScreen(
                    tripState = tripState,
                    actionState = actionState,
                    validationStatus = validationStatus,
                    onStart = onStartTrip,
                    onRetry = onRetry
                )
                null -> ConnectionScreen(
                    tripState = tripState,
                    actionState = actionState,
                    onRefresh = onRefresh,
                    onOpenHeartRatePermission = onOpenHeartRatePermission
                )
                }
            }
        }
    }
}

@Composable
private fun ReadyScreen(
    tripState: WearTripState,
    actionState: WearTripUiActionState,
    validationStatus: WearDataLayerProtocol.ValidationStatus?,
    onStart: () -> Unit,
    onRetry: () -> Unit
) = WearScreenScaffold(accentGlowColor = MotoProgressGreen) {
    EyebrowLabel(text = "MotoSOS")
    Spacer(Modifier.height(6.dp))
    ScreenTitle(
        text = "¡Listo para tu viaje!",
        modifier = Modifier.testTag("wear_trip_status")
    )
    Spacer(Modifier.height(8.dp))
    HeroSurface { MotorcycleIllustration() }
    Spacer(Modifier.height(8.dp))
    ScreenSubtitle("Activa el monitoreo para viajar más seguro.")
    Spacer(Modifier.height(8.dp))
    ConnectionBadge(
        label = connectionLabel(tripState.connected),
        modifier = Modifier.testTag("wear_connection_status")
    )
    ValidationStatusBanner(validationStatus)
    TripActionFeedback(actionState, onRetry)
    Spacer(Modifier.height(8.dp))
    RoundPrimaryButton(
        label = "Iniciar viaje",
        enabled = actionState !is WearTripUiActionState.InFlight,
        tag = "wear_start_trip",
        onClick = onStart
    )
}

@Composable
private fun ActiveTripScreen(
    tripState: WearTripState,
    actionState: WearTripUiActionState,
    signalSnapshot: WearSignalSnapshot,
    validationStatus: WearDataLayerProtocol.ValidationStatus?,
    onOpenMonitoring: () -> Unit,
    onOpenHeartRatePermission: () -> Unit,
    onFinish: () -> Unit,
    onRetry: () -> Unit,
    onOpenManualSos: () -> Unit
) = WearScreenScaffold(backgroundColor = MotoTripBlue, accentGlowColor = MotoProgressGreen) {
    val elapsedTime = elapsedTripTime(tripState.startedAtEpochMs)
    EyebrowLabel(text = "Monitoreo activo")
    Spacer(Modifier.height(4.dp))
    ScreenTitle(text = "Viaje activo", modifier = Modifier.testTag("wear_trip_status"))
    Spacer(Modifier.height(5.dp))
    ConnectionBadge(
        label = connectionLabel(tripState.connected),
        modifier = Modifier.testTag("wear_connection_status")
    )
    Spacer(Modifier.height(8.dp))
    CircularTripProgress {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MotorcycleIllustration(compact = true)
            Text(elapsedTime, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("Tiempo de viaje", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
        }
    }
    Spacer(Modifier.height(8.dp))
    CompactMetricStrip(
        firstLabel = "m/s²",
        firstValue = accelerationMagnitude(signalSnapshot),
        secondLabel = "bpm",
        secondValue = signalSnapshot.heartRateBpm?.let { it.toInt().toString() } ?: "--",
        thirdLabel = "batería",
        thirdValue = signalSnapshot.watchBatteryPercentage?.let { "$it%" } ?: "--"
    )
    if (signalSnapshot.status == WearCaptureStatus.PermissionRequired ||
        signalSnapshot.status == WearCaptureStatus.PermanentlyDenied ||
        signalSnapshot.heartRateStatus == WearSignalAvailability.PermissionRequired ||
        signalSnapshot.heartRateStatus == WearSignalAvailability.PermanentlyDenied
    ) {
        Spacer(Modifier.height(6.dp))
        SecondaryWearButton(
            label = "Permitir frecuencia cardiaca",
            tag = "wear_heart_rate_permission",
            onClick = onOpenHeartRatePermission
        )
    }
    ValidationStatusBanner(validationStatus)
    TripActionFeedback(actionState, onRetry)
    Spacer(Modifier.height(8.dp))
    SecondaryWearButton(
        label = "Ver sensores",
        tag = "wear_open_monitoring",
        onClick = onOpenMonitoring
    )
    Spacer(Modifier.height(6.dp))
    SecondaryWearButton(
        label = "SOS manual",
        tag = "wear_manual_sos_open",
        onClick = onOpenManualSos,
        alert = true
    )
    Spacer(Modifier.height(6.dp))
    RoundPrimaryButton(
        label = "Finalizar viaje",
        enabled = tripState.remoteTripId != null && actionState !is WearTripUiActionState.InFlight,
        tag = "wear_finish_trip",
        onClick = onFinish
    )
}

@Composable
private fun ManualSosScreen(
    state: WearManualSosUiState,
    onHoldStart: () -> Unit,
    onHoldProgress: (Float) -> Unit,
    onHoldCancel: () -> Unit,
    onHoldComplete: () -> Unit,
    onRetry: () -> Unit
) = WearScreenScaffold(backgroundColor = Color(0xFF35131B), accentGlowColor = MotoSosRed) {
    EyebrowLabel(text = "Emergencia manual")
    Spacer(Modifier.height(6.dp))
    ScreenTitle("¿Necesitas ayuda?", Modifier.testTag("wear_manual_sos_screen"))
    Spacer(Modifier.height(6.dp))
    ScreenSubtitle("Mantén presionado para enviar una solicitud de ayuda al teléfono.")
    Spacer(Modifier.height(14.dp))
    EmergencyPulseRings {
        HoldToConfirmSosButton(
            state = state,
            onHoldStart = onHoldStart,
            onHoldProgress = onHoldProgress,
            onHoldCancel = onHoldCancel,
            onHoldComplete = onHoldComplete
        )
    }
    Spacer(Modifier.height(10.dp))
    when (state) {
        is WearManualSosUiState.Holding -> Text(
            "Mantén presionado… ${(state.progress * 100).toInt()}%",
            color = Color.White,
            textAlign = TextAlign.Center
        )
        WearManualSosUiState.InFlight -> {
            CircularProgressIndicator(color = MotoSosRed, modifier = Modifier.testTag("wear_manual_sos_sending"))
            Spacer(Modifier.height(6.dp))
            Text("Enviando solicitud…", color = Color.White, textAlign = TextAlign.Center)
        }
        is WearManualSosUiState.Error -> {
            InfoBanner("SOS no enviado", state.message, MotoSosRed)
            if (state.retryable) {
                Spacer(Modifier.height(8.dp))
                SecondaryWearButton("Reintentar", onRetry, tag = "wear_manual_sos_retry", alert = true)
            }
        }
        WearManualSosUiState.Idle,
        WearManualSosUiState.Success -> Text(
            "Mantén presionado para confirmar",
            color = Color.White.copy(alpha = 0.78f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ManualSosAlertSentScreen(
    tripState: WearTripState,
    onDismiss: () -> Unit
) = WearScreenScaffold(accentGlowColor = MotoSosGreen) {
    EyebrowLabel(text = "Solicitud confirmada")
    Spacer(Modifier.height(8.dp))
    AlertSentIndicator()
    Spacer(Modifier.height(10.dp))
    ScreenTitle("Alerta enviada", Modifier.testTag("wear_manual_sos_alert_sent"))
    Spacer(Modifier.height(6.dp))
    ScreenSubtitle("Tu solicitud de ayuda fue procesada por el teléfono.")
    Spacer(Modifier.height(12.dp))
    if (tripState.active == true) {
        SecondaryWearButton("Volver al viaje", onDismiss, tag = "wear_manual_sos_back_to_trip")
    } else {
        RoundPrimaryButton("Continuar", onDismiss, tag = "wear_manual_sos_back_to_trip")
    }
}

@Composable
private fun EmergencyPulseRings(content: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(148.dp)) {
            drawCircle(MotoSosRed.copy(alpha = 0.10f), radius = size.minDimension * 0.46f)
            drawCircle(MotoSosRed.copy(alpha = 0.30f), radius = size.minDimension * 0.40f, style = Stroke(5.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.22f), radius = size.minDimension * 0.30f, style = Stroke(1.dp.toPx()))
        }
        content()
    }
}

@Composable
private fun AlertSentIndicator() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MotoSosGreen.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Text("✓", color = MotoSosGreen, fontSize = 38.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HoldToConfirmSosButton(
    state: WearManualSosUiState,
    onHoldStart: () -> Unit,
    onHoldProgress: (Float) -> Unit,
    onHoldCancel: () -> Unit,
    onHoldComplete: () -> Unit
) {
    var holdStartedAt by remember { mutableStateOf<Long?>(null) }
    val enabled = state !is WearManualSosUiState.InFlight && state !is WearManualSosUiState.Success
    LaunchedEffect(holdStartedAt, enabled) {
        val start = holdStartedAt ?: return@LaunchedEffect
        while (holdStartedAt == start && enabled) {
            val progress = ((System.currentTimeMillis() - start).toFloat() / MANUAL_SOS_HOLD_MILLIS).coerceIn(0f, 1f)
            onHoldProgress(progress)
            if (progress >= 1f) {
                holdStartedAt = null
                onHoldComplete()
                break
            }
            delay(HOLD_PROGRESS_INTERVAL_MILLIS)
        }
    }
    Box(
        modifier = Modifier
            .size(126.dp)
            .clip(CircleShape)
            .background(if (enabled) MotoSosRed else MotoSosRed.copy(alpha = 0.42f))
            .testTag("wear_manual_sos_hold")
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    holdStartedAt = System.currentTimeMillis()
                    onHoldStart()
                    val releasedBeforeConfirm = withTimeoutOrNull(MANUAL_SOS_HOLD_MILLIS) {
                        waitForUpOrCancellation()
                        true
                    } ?: false
                    if (releasedBeforeConfirm && holdStartedAt != null) {
                        holdStartedAt = null
                        onHoldCancel()
                    } else {
                        waitForUpOrCancellation()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val label = when (state) {
            is WearManualSosUiState.Holding -> "${(state.progress * 100).toInt()}%"
            WearManualSosUiState.InFlight -> "…"
            else -> "SOS"
        }
        Text(label, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MonitoringScreen(
    tripState: WearTripState,
    signalSnapshot: WearSignalSnapshot,
    onBack: () -> Unit
) = WearScreenScaffold(accentGlowColor = MotoBlueGlow) {
    EyebrowLabel(text = "Sensores reales")
    Spacer(Modifier.height(4.dp))
    ScreenTitle(text = "Monitoreo activo", modifier = Modifier.testTag("wear_monitoring_screen"))
    Spacer(Modifier.height(4.dp))
    ScreenSubtitle("Lecturas disponibles del reloj.")
    Spacer(Modifier.height(8.dp))
    MetricPill("Acelerómetro", accelerationMagnitude(signalSnapshot), MotoProgressGreen, Modifier.testTag("wear_monitoring_accelerometer"))
    Spacer(Modifier.height(5.dp))
    MetricPill("Giroscopio", vectorValue(signalSnapshot.gyroscope), MotoBlueGlow, Modifier.testTag("wear_monitoring_gyroscope"))
    Spacer(Modifier.height(5.dp))
    MetricPill("Frecuencia cardiaca", signalSnapshot.heartRateBpm?.let { "${it.toInt()} bpm" } ?: "--", MotoGreen, Modifier.testTag("wear_monitoring_heart_rate"))
    Spacer(Modifier.height(5.dp))
    MetricPill("Batería", signalSnapshot.watchBatteryPercentage?.let { "$it%" } ?: "--", MotoGreen, Modifier.testTag("wear_monitoring_battery"))
    Spacer(Modifier.height(5.dp))
    MetricPill("Conexión", connectionLabel(tripState.connected), MotoProgressGreen)
    Spacer(Modifier.height(8.dp))
    InfoBanner(
        title = "Señales del reloj",
        message = "Acelerómetro, giroscopio, frecuencia cardiaca y batería muestran sólo datos disponibles.",
        accentColor = MotoProgressGreen
    )
    Spacer(Modifier.height(8.dp))
    SecondaryWearButton("Volver al viaje", onBack)
}

@Composable
private fun ConnectionScreen(
    tripState: WearTripState,
    actionState: WearTripUiActionState,
    onRefresh: () -> Unit,
    onOpenHeartRatePermission: () -> Unit
) = WearVisualScaffold {
    Text("MotoSOS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
    Spacer(Modifier.height(16.dp))
    Box(
        modifier = Modifier
            .size(112.dp)
            .clip(CircleShape)
            .background(MotoSosBlue.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        Text("⌁", color = MotoSosGreen, style = MaterialTheme.typography.displayMedium)
    }
    Spacer(Modifier.height(14.dp))
    val unavailable = tripState.connected == false
    Text(
        if (unavailable) "Teléfono no disponible" else "Conectando con teléfono",
        modifier = Modifier.fillMaxWidth().testTag("wear_trip_status"),
        style = MaterialTheme.typography.headlineSmall,
        color = Color.White,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    ConnectionIndicator(tripState.connected)
    TripActionFeedback(actionState, onRefresh)
    Spacer(Modifier.height(16.dp))
    PrimaryWearButton(
        label = if (unavailable) "Reintentar conexión" else "Actualizando…",
        enabled = unavailable && actionState !is WearTripUiActionState.InFlight,
        tag = "wear_connection_refresh",
        onClick = onRefresh
    )
    Spacer(Modifier.height(8.dp))
    SecondaryWearButton("Permiso cardiaco", onOpenHeartRatePermission)
}

@Composable
private fun CountdownScreen(
    status: WearDataLayerProtocol.ValidationStatus,
    actionState: WearValidationUiActionState,
    connected: Boolean?,
    onConfirmSafe: () -> Unit,
    onRequestHelp: () -> Unit,
    onRetry: () -> Unit
) = WearScreenScaffold(backgroundColor = MotoSosNavy, accentGlowColor = MotoSosRed) {
    Text(
        "POSIBLE INCIDENTE",
        color = MotoSosRed,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "¿Estás bien?",
        modifier = Modifier.fillMaxWidth().testTag("wear_validation_countdown"),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Detectamos un posible accidente.",
        modifier = Modifier.fillMaxWidth(),
        color = Color.White.copy(alpha = 0.86f),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(10.dp))
    CountdownRing(formatRemainingMillis(status.remainingMillis))
    Spacer(Modifier.height(6.dp))
    Text(
        "Tiempo confirmado por el teléfono",
        color = Color.White.copy(alpha = 0.72f),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    ConnectionIndicator(connected, tag = "wear_validation_connection_state")
    ValidationActionFeedback(actionState, onRetry)
    Spacer(Modifier.height(14.dp))
    PrimaryWearButton(
        label = "Estoy bien",
        enabled = actionState !is WearValidationUiActionState.InFlight,
        tag = "wear_validation_confirm_safe",
        onClick = onConfirmSafe
    )
    Spacer(Modifier.height(8.dp))
    SecondaryWearButton(
        label = "Necesito ayuda",
        enabled = actionState !is WearValidationUiActionState.InFlight,
        tag = "wear_validation_request_help",
        onClick = onRequestHelp,
        alert = true
    )
}

@Composable
private fun WearVisualScaffold(
    alert: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val background = if (alert) {
        Brush.verticalGradient(listOf(MotoSosBlack, Color(0xFF35131B), MotoSosNavy))
    } else {
        Brush.verticalGradient(listOf(MotoSosBlack, MotoSosNavy, MotoSosBlue))
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        content = content
    )
}

@Composable
private fun CircularTripProgress(content: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(132.dp)) {
            drawCircle(MotoProgressGreen.copy(alpha = 0.16f), style = Stroke(width = 10.dp.toPx()))
            drawArc(
                color = MotoProgressGreen,
                startAngle = -90f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = 10.dp.toPx())
            )
            drawCircle(Color.White.copy(alpha = 0.16f), radius = size.minDimension * 0.31f)
        }
        content()
    }
}

@Composable
private fun elapsedTripTime(startedAtEpochMs: Long?): String {
    if (startedAtEpochMs == null) return "--:--"
    val now by produceState(initialValue = System.currentTimeMillis(), startedAtEpochMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val elapsedSeconds = ((now - startedAtEpochMs).coerceAtLeast(0L)) / 1_000L
    return "%02d:%02d".format(elapsedSeconds / 60L, elapsedSeconds % 60L)
}

private fun accelerationMagnitude(snapshot: WearSignalSnapshot): String {
    val sample = snapshot.accelerometer ?: return "--"
    val magnitude = sqrt(sample.x * sample.x + sample.y * sample.y + sample.z * sample.z)
    return "%.1f".format(magnitude)
}

private fun vectorValue(sample: WearVectorSample?): String = sample?.let {
    "%.1f, %.1f, %.1f".format(it.x, it.y, it.z)
} ?: "--"

@Composable
private fun CountdownRing(remaining: String) {
    Box(
        modifier = Modifier.testTag("wear_validation_remaining_time"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(120.dp)) {
            drawCircle(Color.White.copy(alpha = 0.18f), style = Stroke(width = 1.dp.toPx()))
            drawCircle(MotoSosRed.copy(alpha = 0.16f), radius = size.minDimension * 0.42f)
            drawCircle(MotoSosRed.copy(alpha = 0.44f), radius = size.minDimension * 0.42f, style = Stroke(width = 8.dp.toPx()))
            drawCircle(MotoSosRed, radius = size.minDimension * 0.29f, style = Stroke(width = 5.dp.toPx()))
        }
        Text("!", color = MotoSosRed, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            remaining,
            modifier = Modifier.padding(top = 58.dp).testTag("wear_validation_remaining"),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ConnectionIndicator(connected: Boolean?, tag: String = "wear_connection_status") {
    val label = connectionLabel(connected)
    val color = when (connected) {
        true -> MotoSosGreen
        false -> MotoSosRed
        null -> Color.White.copy(alpha = 0.72f)
    }
    Text(
        label,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        color = color,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall
    )
}

private fun connectionLabel(connected: Boolean?): String = when (connected) {
    true -> "Teléfono conectado"
    false -> "Sin conexión con el teléfono"
    null -> "Verificando conexión"
}

@Composable
private fun SignalMetrics(snapshot: WearSignalSnapshot) {
    val heartRate = snapshot.heartRateBpm?.let { "${it.toInt()} bpm" }
    val battery = snapshot.watchBatteryPercentage?.let { "$it%" }
    if (heartRate == null && battery == null) return
    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier
            .widthIn(max = WearActionWidth)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        heartRate?.let { MetricText("FC", it) }
        battery?.let { MetricText("Batería", it) }
    }
}

@Composable
private fun MetricText(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Text(label, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ValidationStatusBanner(status: WearDataLayerProtocol.ValidationStatus?) {
    val label = when (status?.state) {
        "safe_confirmed" -> "Estás bien"
        "help_requested" -> "Ayuda solicitada"
        "incident_generated" -> "Alerta enviada"
        else -> null
    } ?: return
    Spacer(Modifier.height(8.dp))
    Text(
        label,
        modifier = Modifier.fillMaxWidth().testTag("wear_validation_status"),
        color = Color.White,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun TripActionFeedback(actionState: WearTripUiActionState, onRetry: () -> Unit) {
    when (actionState) {
        is WearTripUiActionState.InFlight -> {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(color = MotoSosGreen)
            Spacer(Modifier.height(6.dp))
            Text(
                if (actionState.operation == WearTripUiOperation.Start) "Iniciando…" else "Finalizando…",
                color = Color.White
            )
        }
        is WearTripUiActionState.Error -> {
            Spacer(Modifier.height(12.dp))
            Text(actionState.message, color = MotoSosRed, textAlign = TextAlign.Center)
            if (actionState.retryable) {
                Spacer(Modifier.height(8.dp))
                SecondaryWearButton("Reintentar", onRetry, tag = "wear_retry_action")
            }
        }
        WearTripUiActionState.Idle -> Unit
    }
}

@Composable
private fun ValidationActionFeedback(actionState: WearValidationUiActionState, onRetry: () -> Unit) {
    when (actionState) {
        is WearValidationUiActionState.InFlight -> {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(color = MotoSosRed)
            Spacer(Modifier.height(6.dp))
            Text("Enviando respuesta…", color = Color.White)
        }
        is WearValidationUiActionState.Error -> {
            Spacer(Modifier.height(12.dp))
            Text(
                actionState.message,
                modifier = Modifier.fillMaxWidth().testTag("wear_validation_status"),
                color = MotoSosRed,
                textAlign = TextAlign.Center
            )
            if (actionState.retryable) {
                Spacer(Modifier.height(8.dp))
                SecondaryWearButton("Reintentar", onRetry, tag = "wear_validation_retry")
            }
        }
        is WearValidationUiActionState.Sent -> {
            Spacer(Modifier.height(12.dp))
            Text(
                "Respuesta enviada",
                modifier = Modifier.testTag("wear_validation_status"),
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
        WearValidationUiActionState.Idle -> Unit
    }
}

@Composable
private fun PrimaryWearButton(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = MotoSosGreen, contentColor = MotoSosBlack),
        shape = CircleShape,
        modifier = Modifier
            .widthIn(max = WearActionWidth)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag)
    ) {
        Text(label, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SecondaryWearButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tag: String? = null,
    alert: Boolean = false
) {
    val borderColor = if (alert) MotoSosRed else Color.White.copy(alpha = 0.78f)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = borderColor),
        shape = CircleShape,
        modifier = Modifier
            .widthIn(max = WearActionWidth)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (tag == null) Modifier else Modifier.testTag(tag))
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

private fun formatRemainingMillis(remainingMillis: Long?): String {
    val totalSeconds = (((remainingMillis ?: 0L) + 999L) / 1_000L).coerceAtLeast(0L)
    return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
