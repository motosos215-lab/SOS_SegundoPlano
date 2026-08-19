package com.example.sos_segundoplano.features.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.domain.emergency.EmergencyContact
import com.example.sos_segundoplano.domain.monitoring.MonitoringReadiness
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackMessage
import com.example.sos_segundoplano.domain.push.RiderMonitorFeedbackType
import com.example.sos_segundoplano.domain.monitoring.MonitoringRequirementStatus
import com.example.sos_segundoplano.ui.components.MotoBottomBar
import com.example.sos_segundoplano.ui.components.MotoBottomBarItem
import com.example.sos_segundoplano.ui.components.MotoHeroTripCard
import com.example.sos_segundoplano.ui.components.MotoInformationCard
import com.example.sos_segundoplano.ui.components.MotoTopBar
import com.example.sos_segundoplano.ui.components.MotoTopBarIcon
import com.example.sos_segundoplano.ui.components.deviceContentDescription
import com.example.sos_segundoplano.ui.components.emergencyContactContentDescription
import com.example.sos_segundoplano.ui.theme.MotoBackground

sealed interface RiderEmergencyContactUiState {
    data object Loading : RiderEmergencyContactUiState
    data object Empty : RiderEmergencyContactUiState
    data class Content(val contact: EmergencyContact) : RiderEmergencyContactUiState
    data object Unavailable : RiderEmergencyContactUiState
}

@Composable
fun HomeScreen(
    onStartTrip: () -> Unit,
    monitoringReadiness: MonitoringReadiness = MonitoringReadiness(
        location = MonitoringRequirementStatus.Available,
        notifications = MonitoringRequirementStatus.Available,
        bluetooth = MonitoringRequirementStatus.Available
    ),
    onLocationReadinessAction: () -> Unit = {},
    onNotificationReadinessAction: () -> Unit = {},
    onBluetoothReadinessAction: () -> Unit = {},
    onSosSelected: () -> Unit = {},
    onMapSelected: () -> Unit = {},
    onProfileSelected: () -> Unit = {},
    onTripsSelected: () -> Unit = {},
    onDeviceSelected: () -> Unit = {},
    onEmergencyContactSelected: () -> Unit = {},
    onMessagesSelected: () -> Unit = {},
    emergencyContactState: RiderEmergencyContactUiState = RiderEmergencyContactUiState.Loading,
    monitorFeedbackMessages: List<RiderMonitorFeedbackMessage> = emptyList(),
    isTripStartInProgress: Boolean = false,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen"),
        containerColor = MotoBackground,
        topBar = {
            MotoTopBar(
                title = stringResource(R.string.home_title),
                showNavigationIcon = false,
                showNotificationsIcon = true,
                notificationsIcon = MotoTopBarIcon.Message,
                notificationBadgeCount = monitorFeedbackMessages.count { !it.isRead },
                onNotificationsClick = onMessagesSelected
            )
        },
        bottomBar = {
            MotoBottomBar(
                selectedItem = MotoBottomBarItem.Home,
                enabledItems = setOf(MotoBottomBarItem.Home, MotoBottomBarItem.Trips, MotoBottomBarItem.Sos, MotoBottomBarItem.Map, MotoBottomBarItem.Profile),
                onTripsSelected = onTripsSelected,
                onSosSelected = onSosSelected,
                onMapSelected = onMapSelected,
                onProfileSelected = onProfileSelected
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MotoHeroTripCard(
                onStartTrip = onStartTrip,
                isStarting = isTripStartInProgress
            )
            MonitoringReadinessCard(
                readiness = monitoringReadiness,
                onLocationAction = onLocationReadinessAction,
                onNotificationAction = onNotificationReadinessAction,
                onBluetoothAction = onBluetoothReadinessAction
            )
            val latestFeedback = monitorFeedbackMessages.maxByOrNull { it.receivedAtEpochMillis }
            val unreadFeedbackCount = monitorFeedbackMessages.count { !it.isRead }
            MotoInformationCard(
                title = stringResource(R.string.rider_messages_home_title),
                state = when {
                    unreadFeedbackCount > 0 -> stringResource(R.string.rider_messages_home_unread, unreadFeedbackCount)
                    latestFeedback != null -> stringResource(R.string.rider_messages_home_latest)
                    else -> stringResource(R.string.rider_messages_home_empty)
                },
                description = latestFeedback?.homeMessageText()
                    ?: stringResource(R.string.rider_messages_home_empty_description),
                contentDescription = stringResource(R.string.cd_messages),
                iconRes = R.drawable.ic_message_bubble,
                iconViewportSize = 40.dp,
                iconAssetSize = 64.dp,
                modifier = Modifier.testTag("home_monitor_messages_card"),
                onClick = onMessagesSelected
            )
            val contactCard = emergencyContactState.toCardCopy()
            MotoInformationCard(
                title = stringResource(R.string.emergency_contact),
                state = contactCard.first,
                description = contactCard.second,
                contentDescription = emergencyContactContentDescription(),
                iconRes = R.drawable.ic_nav_profile,
                iconViewportSize = 40.dp,
                iconAssetSize = 64.dp,
                modifier = Modifier.testTag("home_emergency_contact_card"),
                onClick = onEmergencyContactSelected
            )
            MotoInformationCard(
                title = stringResource(R.string.device),
                state = "Reloj Wear OS",
                description = "Toca para revisar la conexión y los sensores del reloj detectado.",
                contentDescription = deviceContentDescription(),
                iconRes = R.drawable.ic_device_watch,
                iconViewportSize = 42.dp,
                iconAssetSize = 76.dp,
                modifier = Modifier.testTag("home_linked_device_card"),
                onClick = onDeviceSelected
            )
        }
    }
}


@Composable
private fun RiderMonitorFeedbackMessage.homeMessageText(): String =
    body?.takeIf { it.isNotBlank() } ?: when (type) {
        RiderMonitorFeedbackType.Viewed -> stringResource(R.string.rider_message_viewed_body)
        RiderMonitorFeedbackType.Acknowledged -> stringResource(R.string.rider_message_acknowledged_body)
        RiderMonitorFeedbackType.Declined -> stringResource(R.string.rider_message_declined_body)
    }

private fun RiderEmergencyContactUiState.toCardCopy(): Pair<String, String> = when (this) {
    RiderEmergencyContactUiState.Loading -> "Cargando contacto…" to "Consultando tu contacto principal de emergencia."
    RiderEmergencyContactUiState.Empty -> "Sin contacto configurado" to "Configura tu contacto de emergencia desde la web de MotoSOS."
    RiderEmergencyContactUiState.Unavailable -> "Contacto no disponible" to "No pudimos actualizar el contacto. Puedes volver a intentarlo al regresar a Inicio."
    is RiderEmergencyContactUiState.Content -> {
        val status = when {
            contact.invitationStatus.equals("Linked", ignoreCase = true) && contact.linkedUserId != null -> "Vinculado"
            contact.invitationStatus.equals("Invited", ignoreCase = true) -> "Invitación enviada"
            contact.invitationStatus.equals("Pending", ignoreCase = true) -> "Pendiente de vinculación"
            else -> contact.invitationStatus.ifBlank { "Configurado" }
        }
        val details = buildList {
            add("$status · ${contact.relationship}")
            contact.phoneNumber.takeIf { it.isNotBlank() }?.let(::add)
            contact.email.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString("\n")
        contact.fullName to details
    }
}
