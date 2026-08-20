package com.example.sos_segundoplano.features.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.domain.emergency.EmergencyContactPermissions
import com.example.sos_segundoplano.ui.format.DisplayFormatters
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoSuccessSoft
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary
import com.example.sos_segundoplano.ui.theme.MotoWarning
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@Composable
fun MonitorLinkingScreen(
    state: MonitorLinkingUiState,
    onCodeChanged: (String) -> Unit,
    onLookup: () -> Unit,
    onQrPayload: (String) -> Unit,
    onScannerFailure: () -> Unit,
    onAccept: () -> Unit,
    onRefreshPushStatus: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scanner = remember(context) { GmsBarcodeScanning.getClient(context) }
    val busy = state.phase == MonitorLinkingPhase.LoadingInvitation || state.phase == MonitorLinkingPhase.Accepting

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
            .testTag("monitor_linking_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Vincular con Rider", style = MaterialTheme.typography.titleLarge, color = MotoPrimaryDark, fontWeight = FontWeight.Bold)
        Text(
            "Usa el código generado por MotoSOS Web para enlazar esta cuenta Monitor con el Rider. Android no crea ni modifica el contacto.",
            color = MotoTextSecondary
        )

        LinkingCard("Código de invitación") {
            OutlinedTextField(
                value = state.code,
                onValueChange = onCodeChanged,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("monitor_linking_code"),
                label = { Text("Código") },
                placeholder = { Text("8X7Q-3M2K-9L6R") },
                singleLine = true
            )
            Button(
                onClick = onLookup,
                enabled = !busy && state.code.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("monitor_linking_lookup")
            ) {
                if (state.phase == MonitorLinkingPhase.LoadingInvitation) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp), strokeWidth = 2.dp)
                }
                Text("Consultar invitación")
            }
            OutlinedButton(
                onClick = {
                    scanner.startScan()
                        .addOnSuccessListener { barcode -> barcode.rawValue?.let(onQrPayload) ?: onScannerFailure() }
                        .addOnFailureListener { onScannerFailure() }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("monitor_linking_scan_qr")
            ) { Text("Escanear QR") }
            Text(
                "El QR sólo debe contener el código o un enlace con el parámetro code. Nunca debe contener contraseñas ni tokens.",
                color = MotoTextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        state.message?.let { message ->
            Text(
                message,
                modifier = Modifier.fillMaxWidth().background(MotoWarning.copy(alpha = .10f), RoundedCornerShape(12.dp)).padding(12.dp),
                color = if (state.phase == MonitorLinkingPhase.Linked) MotoSuccess else MotoWarning,
                fontWeight = FontWeight.SemiBold
            )
        }

        state.invitation?.let { invitation ->
            LinkingCard("Invitación") {
                LinkingRow("Rider", invitation.driverFullName)
                LinkingRow("Contacto", invitation.contactFullName)
                LinkingRow("Estado", invitation.status)
                LinkingRow("Vence", DisplayFormatters.dateTime(invitation.expiresAtUtc) ?: invitation.expiresAtUtc)
                Text("Permisos solicitados", color = MotoPrimaryDark, fontWeight = FontWeight.SemiBold)
                PermissionsContent(invitation.permissions)
                if (state.phase != MonitorLinkingPhase.Linked) {
                    Button(
                        onClick = onAccept,
                        enabled = state.phase == MonitorLinkingPhase.InvitationReady,
                        modifier = Modifier.fillMaxWidth().testTag("monitor_linking_accept")
                    ) {
                        if (state.phase == MonitorLinkingPhase.Accepting) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp), strokeWidth = 2.dp)
                        }
                        Text("Aceptar y vincularme")
                    }
                }
            }
        }

        if (state.phase == MonitorLinkingPhase.Linked) {
            LinkingCard("Estado de notificaciones") {
                when (val push = state.pushReadiness) {
                    MonitorPushReadiness.Unknown -> Text("Aún no se ha verificado el registro FCM.", color = MotoTextSecondary)
                    MonitorPushReadiness.Checking -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Text("Verificando notificaciones…", color = MotoTextSecondary)
                    }
                    is MonitorPushReadiness.Ready -> {
                        Text("✓ Este dispositivo está listo para recibir alertas Push.", color = MotoSuccess, fontWeight = FontWeight.SemiBold)
                        LinkingRow("Tokens Android activos", push.status.activeTokenCount.toString())
                    }
                    is MonitorPushReadiness.Missing -> {
                        Text("El backend todavía no reporta un token Android FCM activo para esta cuenta.", color = MotoWarning)
                        LinkingRow("Tokens activos", push.status.activeTokenCount.toString())
                    }
                    is MonitorPushReadiness.Error -> Text(push.message, color = MotoWarning)
                }
                OutlinedButton(onClick = onRefreshPushStatus, modifier = Modifier.fillMaxWidth().testTag("monitor_linking_push_status")) {
                    Text("Revisar estado de notificaciones")
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("monitor_linking_back")) {
            Text("Volver al perfil")
        }
    }
}

@Composable
private fun LinkingCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MotoSurface, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = MotoPrimaryDark, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun LinkingRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MotoTextSecondary, modifier = Modifier.weight(1f))
        Text(value, color = MotoPrimaryDark, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.2f))
    }
}

@Composable
private fun PermissionsContent(permissions: EmergencyContactPermissions) {
    PermissionRow("Ver ubicación en tiempo real", permissions.canViewRealTimeLocation)
    PermissionRow("Recibir alertas críticas", permissions.canReceiveCriticalAlerts)
    PermissionRow("Ver historial de incidentes", permissions.canViewIncidentHistory)
    PermissionRow("Ver signos vitales", permissions.canViewVitalSigns)
}

@Composable
private fun PermissionRow(label: String, enabled: Boolean) {
    Text(
        "${if (enabled) "✓" else "—"} $label",
        color = if (enabled) MotoSuccess else MotoTextSecondary,
        modifier = Modifier.fillMaxWidth().background(
            if (enabled) MotoSuccessSoft else MotoDivider.copy(alpha = .25f),
            RoundedCornerShape(10.dp)
        ).padding(horizontal = 10.dp, vertical = 8.dp)
    )
}
