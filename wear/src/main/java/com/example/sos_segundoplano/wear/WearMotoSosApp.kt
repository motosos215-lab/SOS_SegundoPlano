package com.example.sos_segundoplano.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.wear.presentation.components.ConnectionBadge
import com.example.sos_segundoplano.wear.presentation.components.EyebrowLabel
import com.example.sos_segundoplano.wear.presentation.components.HeroSurface
import com.example.sos_segundoplano.wear.presentation.components.MotorcycleIllustration
import com.example.sos_segundoplano.wear.presentation.components.RoundPrimaryButton
import com.example.sos_segundoplano.wear.presentation.components.ScreenSubtitle
import com.example.sos_segundoplano.wear.presentation.components.ScreenTitle
import com.example.sos_segundoplano.wear.presentation.components.WearScreenScaffold
import com.example.sos_segundoplano.wear.presentation.theme.MotoProgressGreen
import kotlinx.coroutines.launch

private val MotoSosBlack = Color(0xFF05070A)
private val MotoSosNavy = Color(0xFF0B2447)
private val MotoSosBlue = Color(0xFF12395F)
private val MotoSosGreen = Color(0xFF34C759)
private val MotoSosRed = Color(0xFFFF3B30)
private val WearActionWidth = 180.dp

@Composable
internal fun WearMotoSosApp(
    tripStateStore: WearTripStateStore,
    controller: WearTripUiController,
    validationController: WearValidationUiController,
    onOpenHeartRatePermission: () -> Unit
) {
    val tripState by tripStateStore.state.collectAsState()
    val tripActionState by controller.actionState.collectAsState()
    val validationStatus by WearValidationStateStore.state.collectAsState()
    val validationActionState by validationController.actionState.collectAsState()
    val signalSnapshot by WearSignalStateStore.snapshot.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(validationStatus?.sessionId, validationStatus?.assessmentId, validationStatus?.state) {
        validationController.onValidationStatusChanged(validationStatus)
    }

    WearMotoSosScreen(
        tripState = tripState,
        actionState = tripActionState,
        validationStatus = validationStatus,
        validationActionState = validationActionState,
        signalSnapshot = signalSnapshot,
        onStartTrip = { scope.launch { controller.startTrip() } },
        onFinishTrip = { scope.launch { controller.finishTrip() } },
        onRetry = { scope.launch { controller.retry() } },
        onConfirmSafe = { scope.launch { validationController.confirmSafe() } },
        onRequestHelp = { scope.launch { validationController.requestHelp() } },
        onRetryValidation = { scope.launch { validationController.retry() } },
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
    onRetryValidation: () -> Unit = {}
) {
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
            when (tripState.active) {
                true -> ActiveTripScreen(
                    tripState = tripState,
                    actionState = actionState,
                    signalSnapshot = signalSnapshot,
                    validationStatus = validationStatus,
                    onFinish = onFinishTrip,
                    onRetry = onRetry
                )
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
    onFinish: () -> Unit,
    onRetry: () -> Unit
) = WearVisualScaffold {
    Text("MotoSOS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
    Spacer(Modifier.height(4.dp))
    Text(
        "Viaje activo",
        modifier = Modifier.fillMaxWidth().testTag("wear_trip_status"),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MotoSosGreen,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(4.dp))
    ActiveTripRing()
    Spacer(Modifier.height(2.dp))
    Text("Monitoreo activo", color = Color.White, fontWeight = FontWeight.SemiBold)
    Text("Tiempo de viaje", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(4.dp))
    ConnectionIndicator(tripState.connected)
    SignalMetrics(signalSnapshot)
    ValidationStatusBanner(validationStatus)
    TripActionFeedback(actionState, onRetry)
    Spacer(Modifier.height(8.dp))
    PrimaryWearButton(
        label = "Finalizar viaje",
        enabled = tripState.remoteTripId != null && actionState !is WearTripUiActionState.InFlight,
        tag = "wear_finish_trip",
        onClick = onFinish
    )
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
) = WearVisualScaffold(alert = true) {
    Text("MotoSOS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
    Spacer(Modifier.height(8.dp))
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
    Spacer(Modifier.height(10.dp))
    ConnectionIndicator(connected)
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
private fun ActiveTripRing() {
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(108.dp)) {
            drawCircle(MotoSosGreen.copy(alpha = 0.20f), style = Stroke(width = 13.dp.toPx()))
            drawArc(
                color = MotoSosGreen,
                startAngle = -90f,
                sweepAngle = 300f,
                useCenter = false,
                style = Stroke(width = 13.dp.toPx())
            )
            drawCircle(Color.White.copy(alpha = 0.16f), radius = size.minDimension * 0.31f)
        }
        Text("🏍", style = MaterialTheme.typography.displaySmall)
    }
}

@Composable
private fun CountdownRing(remaining: String) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(120.dp)) {
            drawCircle(MotoSosRed.copy(alpha = 0.16f), style = Stroke(width = 7.dp.toPx()))
            drawCircle(MotoSosRed.copy(alpha = 0.32f), radius = size.minDimension * 0.37f, style = Stroke(width = 9.dp.toPx()))
            drawCircle(MotoSosRed, radius = size.minDimension * 0.26f, style = Stroke(width = 6.dp.toPx()))
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
private fun ConnectionIndicator(connected: Boolean?) {
    val label = connectionLabel(connected)
    val color = when (connected) {
        true -> MotoSosGreen
        false -> MotoSosRed
        null -> Color.White.copy(alpha = 0.72f)
    }
    Text(
        label,
        modifier = Modifier.fillMaxWidth().testTag("wear_connection_status"),
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
