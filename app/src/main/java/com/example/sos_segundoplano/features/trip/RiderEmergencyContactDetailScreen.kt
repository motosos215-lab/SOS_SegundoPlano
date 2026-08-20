package com.example.sos_segundoplano.features.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.domain.emergency.EmergencyContact
import com.example.sos_segundoplano.ui.components.MotoTopBar
import com.example.sos_segundoplano.ui.components.MotoTopBarIcon
import com.example.sos_segundoplano.ui.format.DisplayFormatters
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoPrimaryDark
import com.example.sos_segundoplano.ui.theme.MotoSuccess
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary
import com.example.sos_segundoplano.ui.theme.MotoWarning

@Composable
fun RiderEmergencyContactDetailScreen(
    state: RiderEmergencyContactUiState,
    onBack: () -> Unit,
    onEditMonitorWeb: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("rider_emergency_contact_detail"),
        containerColor = MotoBackground,
        topBar = {
            MotoTopBar(
                title = "Contacto de emergencia",
                navigationIcon = MotoTopBarIcon.Back,
                showNavigationIcon = true,
                showNotificationsIcon = false,
                onNavigationClick = onBack
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (state) {
                RiderEmergencyContactUiState.Loading -> ContactStatusCard(
                    title = "Cargando contacto…",
                    description = "Consultando la información de tu contacto de emergencia."
                )
                RiderEmergencyContactUiState.Empty -> ContactStatusCard(
                    title = "Sin contacto configurado",
                    description = "Todavía no tienes un contacto de emergencia configurado en MotoSOS."
                )
                RiderEmergencyContactUiState.Unavailable -> ContactStatusCard(
                    title = "Contacto no disponible",
                    description = "No pudimos actualizar la información del contacto. Intenta nuevamente más tarde."
                )
                is RiderEmergencyContactUiState.Content -> ContactContent(state.contact)
            }

            OutlinedButton(
                onClick = onEditMonitorWeb,
                modifier = Modifier.fillMaxWidth().testTag("rider_edit_monitor_web_button")
            ) {
                Text("Editar monitor en la web")
            }

            Text(
                "La edición del contacto se realiza en MotoSOS Web. Al volver a Inicio, la aplicación actualizará los datos desde el servidor.",
                color = MotoTextSecondary
            )
        }
    }
}

@Composable
private fun ContactContent(contact: EmergencyContact) {
    val linked = contact.invitationStatus.equals("Linked", ignoreCase = true) && contact.linkedUserId != null
    val status = when {
        linked -> "Vinculado"
        contact.invitationStatus.equals("Invited", ignoreCase = true) -> "Invitación enviada"
        contact.invitationStatus.equals("Pending", ignoreCase = true) -> "Pendiente de vinculación"
        else -> contact.invitationStatus.ifBlank { "Configurado" }
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MotoSurface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(contact.fullName, color = MotoPrimaryDark, fontWeight = FontWeight.Bold)
            Text(
                status,
                color = if (linked) MotoSuccess else MotoWarning,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("rider_emergency_contact_status")
            )
            ContactDetailRow("Relación", contact.relationship.ifBlank { "No disponible" })
            ContactDetailRow("Teléfono", contact.phoneNumber.ifBlank { "No disponible" })
            ContactDetailRow("Correo", contact.email.ifBlank { "No disponible" })
            ContactDetailRow("Prioridad", contact.priority.toString())
            ContactDetailRow("Contacto principal", if (contact.isPrimary) "Sí" else "No")
            ContactDetailRow("Estado", if (contact.isActive) "Activo" else "Inactivo")
            if (!linked) {
                contact.linkingCode?.takeIf { it.isNotBlank() }?.let { ContactDetailRow("Código de vinculación", it) }
                DisplayFormatters.dateTime(contact.linkingCodeExpiresAtUtc)?.let { ContactDetailRow("Código vence", it) }
            }
            DisplayFormatters.dateTime(contact.invitedAtUtc)?.let { ContactDetailRow("Invitación enviada", it) }
            DisplayFormatters.dateTime(contact.linkedAtUtc)?.let { ContactDetailRow("Vinculado", it) }
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MotoSurface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Permisos del Monitor", color = MotoPrimaryDark, fontWeight = FontWeight.Bold)
            PermissionRow("Recibir alertas críticas", contact.permissions.canReceiveCriticalAlerts)
            PermissionRow("Ver ubicación en tiempo real", contact.permissions.canViewRealTimeLocation)
            PermissionRow("Ver historial de incidentes", contact.permissions.canViewIncidentHistory)
            PermissionRow("Ver signos vitales", contact.permissions.canViewVitalSigns)
        }
    }
}

@Composable
private fun ContactStatusCard(title: String, description: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MotoSurface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(title, color = MotoPrimaryDark, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(description, color = MotoTextSecondary)
        }
    }
}

@Composable
private fun ContactDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MotoTextSecondary, modifier = Modifier.weight(0.42f))
        Text(value, color = MotoPrimaryDark, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.58f))
    }
}

@Composable
private fun PermissionRow(label: String, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MotoTextSecondary, modifier = Modifier.weight(1f))
        Text(if (enabled) "Permitido" else "No permitido", color = if (enabled) MotoSuccess else MotoTextSecondary)
    }
}
