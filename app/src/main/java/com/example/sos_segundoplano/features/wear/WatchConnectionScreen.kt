package com.example.sos_segundoplano.features.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sos_segundoplano.R
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.wear.HeartRatePermissionState
import com.example.sos_segundoplano.domain.wear.VectorReading
import com.example.sos_segundoplano.domain.wear.WatchConnectionRepository
import com.example.sos_segundoplano.domain.wear.WatchConnectionState
import com.example.sos_segundoplano.domain.wear.WatchDeclineReason
import com.example.sos_segundoplano.domain.wear.WatchHandshakeInfo
import com.example.sos_segundoplano.domain.wear.WatchSensorSnapshot
import com.example.sos_segundoplano.domain.wear.WatchStatus
import com.example.sos_segundoplano.ui.components.MotoBottomBar
import com.example.sos_segundoplano.ui.components.MotoBottomBarItem
import com.example.sos_segundoplano.ui.components.MotoTopBar
import com.example.sos_segundoplano.ui.components.MotoTopBarIcon
import com.example.sos_segundoplano.ui.theme.MotoAlert
import com.example.sos_segundoplano.ui.theme.MotoBackground
import com.example.sos_segundoplano.ui.theme.MotoDivider
import com.example.sos_segundoplano.ui.theme.MotoPrimaryBlue
import com.example.sos_segundoplano.ui.theme.MotoSurface
import com.example.sos_segundoplano.ui.theme.MotoTextPrimary
import com.example.sos_segundoplano.ui.theme.MotoTextSecondary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun WatchConnectionRoute(
    repository: WatchConnectionRepository,
    authRepository: AuthRepository,
    onBack: () -> Unit,
    onHomeSelected: () -> Unit,
    onProfileSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val factory = remember(repository, authRepository) {
        WatchConnectionViewModel.Factory(repository, authRepository)
    }
    val viewModel: WatchConnectionViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.onScreenStarted()
    }
    WatchConnectionScreen(
        state = state,
        onBack = onBack,
        onHomeSelected = onHomeSelected,
        onProfileSelected = onProfileSelected,
        onRefresh = viewModel::refreshConnection,
        onRequestStatus = viewModel::requestStatus,
        onRequestSnapshot = viewModel::requestSnapshot,
        onDismissNotice = viewModel::dismissNotice,
        modifier = modifier
    )
}

@Composable
fun WatchConnectionScreen(
    state: WatchConnectionUiState,
    onBack: () -> Unit,
    onHomeSelected: () -> Unit,
    onProfileSelected: () -> Unit,
    onRefresh: () -> Unit,
    onRequestStatus: () -> Unit,
    onRequestSnapshot: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    val connecting = state.connection is WatchConnectionState.Loading ||
        state.connection is WatchConnectionState.Handshaking ||
        state.connection is WatchConnectionState.RequestingStatus ||
        state.connection is WatchConnectionState.RequestingSnapshot
    val busy = state.isRequestingStatus || state.isRequestingSnapshot || connecting
    val canRequestData = state.connection is WatchConnectionState.Connected && !busy

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("watch_connection_screen"),
        containerColor = MotoBackground,
        topBar = {
            MotoTopBar(
                title = stringResource(R.string.watch_connection_title),
                subtitle = stringResource(R.string.watch_connection_subtitle),
                navigationIcon = MotoTopBarIcon.Back
            )
        },
        bottomBar = {
            MotoBottomBar(
                selectedItem = MotoBottomBarItem.Profile,
                onHomeSelected = onHomeSelected,
                onProfileSelected = onProfileSelected,
                enabledItems = setOf(MotoBottomBarItem.Home, MotoBottomBarItem.Profile)
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
            state.notice?.let { notice ->
                NoticeCard(notice = notice, onDismiss = onDismissNotice)
            }
            ConnectionCard(
                state = state.connection,
                connecting = connecting,
                busy = busy,
                canRequestData = canRequestData,
                onRefresh = onRefresh,
                onRequestStatus = onRequestStatus,
                onRequestSnapshot = onRequestSnapshot
            )
            state.handshake?.let { DeviceInfoCard(it) }
            state.status?.let { StatusCard(it) }
            state.status?.let { SensorsCard(it) }
            state.snapshot?.let { LastReadingCard(it) }
        }
    }
}

// -------------------------------------------------------------- connection card

@Composable
private fun ConnectionCard(
    state: WatchConnectionState,
    connecting: Boolean,
    busy: Boolean,
    canRequestData: Boolean,
    onRefresh: () -> Unit,
    onRequestStatus: () -> Unit,
    onRequestSnapshot: () -> Unit
) {
    ConnectionCardFrame(title = stringResource(R.string.watch_card_connection)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (connecting) {
                CircularProgressIndicator(
                    color = MotoPrimaryBlue,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = when (val s = state) {
                    is WatchConnectionState.Connected ->
                        stringResource(R.string.watch_state_connected, s.device.displayName)
                    else -> stringResource(state.statusText())
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MotoTextPrimary,
                modifier = Modifier.testTag("watch_connection_state")
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onRequestStatus,
                enabled = canRequestData,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("watch_status_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MotoPrimaryBlue)
            ) {
                Text(stringResource(R.string.watch_status_button), color = MotoSurface)
            }
            Button(
                onClick = onRequestSnapshot,
                enabled = canRequestData,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("watch_snapshot_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MotoPrimaryBlue)
            ) {
                Text(stringResource(R.string.watch_snapshot_button), color = MotoSurface)
            }
        }
        OutlinedButton(
            onClick = onRefresh,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("watch_refresh_button"),
            border = BorderStroke(1.5.dp, MotoPrimaryBlue),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MotoSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.watch_refresh_button), color = MotoPrimaryBlue)
        }
    }
}

// -------------------------------------------------------------------- device info

@Composable
private fun DeviceInfoCard(handshake: WatchHandshakeInfo) {
    TitledCard(
        title = stringResource(R.string.watch_card_device),
        testTag = "watch_device_info"
    ) {
        Field(
            stringResource(R.string.watch_handshake_app),
            stringResource(R.string.watch_handshake_app_version, handshake.appVersionName, handshake.appVersionCode)
        )
        Field(stringResource(R.string.watch_handshake_manufacturer), handshake.manufacturer)
        Field(stringResource(R.string.watch_handshake_model), handshake.model)
        Field(stringResource(R.string.watch_handshake_wear_os), handshake.wearOsApiLevel.toString())
    }
}

// ---------------------------------------------------------------------------- status

@Composable
private fun StatusCard(status: WatchStatus) {
    TitledCard(
        title = stringResource(R.string.watch_card_status),
        testTag = "watch_status_info"
    ) {
        val battery = if (status.batteryAvailable && status.batteryPercent != null) {
            stringResource(R.string.watch_battery_percent, status.batteryPercent)
        } else {
            stringResource(R.string.watch_not_available_short)
        }
        Field(stringResource(R.string.watch_status_battery), battery)
        Field(stringResource(R.string.watch_last_communication), formatEpoch(status.respondedAtEpochMs))
        val charging = when {
            !status.chargingKnown -> stringResource(R.string.watch_charging_unknown)
            status.charging == true -> stringResource(R.string.watch_charging_yes)
            else -> stringResource(R.string.watch_charging_no)
        }
        Field(stringResource(R.string.watch_status_charging), charging)
    }
}

// --------------------------------------------------------------------------- sensors

@Composable
private fun SensorsCard(status: WatchStatus) {
    val available = stringResource(R.string.watch_available_short)
    val unavailable = stringResource(R.string.watch_not_available_short)
    TitledCard(
        title = stringResource(R.string.watch_card_sensors),
        testTag = "watch_sensors_info"
    ) {
        Field(
            stringResource(R.string.watch_sensor_accelerometer),
            if (status.accelerometerAvailable) available else unavailable
        )
        Field(
            stringResource(R.string.watch_sensor_gyroscope),
            if (status.gyroscopeAvailable) available else unavailable
        )
        Field(
            stringResource(R.string.watch_sensor_heart_rate),
            if (status.heartRateAvailable) available else unavailable
        )
        Field(
            stringResource(R.string.watch_sensor_heart_permission),
            stringResource(status.heartRatePermission.permissionText())
        )
    }
}

// ------------------------------------------------------------------------ snapshot

@Composable
private fun LastReadingCard(snapshot: WatchSensorSnapshot) {
    TitledCard(
        title = stringResource(R.string.watch_card_last_reading),
        testTag = "watch_last_reading"
    ) {
        Field(stringResource(R.string.watch_reading_time), formatEpoch(snapshot.capturedAtEpochMs))
        Field(
            stringResource(R.string.watch_sensor_accelerometer),
            vectorText(snapshot.accelerometer, snapshot.accelerometerAvailable, "m/s²")
        )
        Field(
            stringResource(R.string.watch_sensor_gyroscope),
            vectorText(snapshot.gyroscope, snapshot.gyroscopeAvailable, "rad/s")
        )
        Field(stringResource(R.string.watch_sensor_heart_rate), heartRateText(snapshot))
    }
}

// --------------------------------------------------------------------------- notice

@Composable
private fun NoticeCard(notice: WatchNotice, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MotoAlert.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .border(1.dp, MotoAlert.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(14.dp)
            .testTag("watch_notice"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(notice.messageRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MotoTextPrimary
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("watch_notice_dismiss")
        ) {
            Text(stringResource(R.string.watch_notice_dismiss), color = MotoPrimaryBlue)
        }
    }
}

// ------------------------------------------------------------------- shared pieces

@Composable
private fun ConnectionCardFrame(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MotoSurface, RoundedCornerShape(18.dp))
            .border(1.dp, MotoDivider, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MotoTextPrimary,
            modifier = Modifier.semantics { heading() }
        )
        content()
    }
}

@Composable
private fun TitledCard(
    title: String,
    testTag: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MotoSurface, RoundedCornerShape(18.dp))
            .border(1.dp, MotoDivider, RoundedCornerShape(18.dp))
            .padding(16.dp)
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MotoTextPrimary,
            modifier = Modifier.semantics { heading() }
        )
        content()
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MotoTextSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MotoTextPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// --------------------------------------------------------------------------- mappings

@Composable
private fun vectorText(vector: VectorReading?, available: Boolean, unit: String): String {
    if (!available || vector == null) return stringResource(R.string.watch_not_available_short)
    return String.format(
        Locale.getDefault(),
        "%.2f, %.2f, %.2f %s",
        vector.x,
        vector.y,
        vector.z,
        unit
    )
}

@Composable
private fun heartRateText(snapshot: WatchSensorSnapshot): String {
    val bpm = snapshot.heartRateBpm
    if (!snapshot.heartRateAvailable || bpm == null) {
        return stringResource(R.string.watch_not_available_short)
    }
    return String.format(Locale.getDefault(), "%.0f bpm", bpm)
}

private fun formatEpoch(epochMs: Long): String = try {
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
} catch (_: Exception) {
    "No disponible"
}

private fun WatchConnectionState.statusText(): Int = when (this) {
    is WatchConnectionState.Loading -> R.string.watch_state_loading
    WatchConnectionState.DataLayerUnavailable -> R.string.watch_state_data_layer
    WatchConnectionState.NoWearNodes -> R.string.watch_state_no_nodes
    WatchConnectionState.WearNodeDetectedWithoutMotoSos -> R.string.watch_state_no_motosos
    is WatchConnectionState.CompatibleNodeIndirect -> R.string.watch_state_indirect
    is WatchConnectionState.CompatibleNodeNearby -> R.string.watch_state_nearby
    is WatchConnectionState.Handshaking -> R.string.watch_state_handshaking
    is WatchConnectionState.Connected -> R.string.watch_state_connected
    is WatchConnectionState.ProtocolIncompatible -> R.string.watch_state_incompatible
    is WatchConnectionState.RequestingStatus -> R.string.watch_state_requesting_status
    is WatchConnectionState.RequestingSnapshot -> R.string.watch_state_requesting_snapshot
    is WatchConnectionState.Timeout -> R.string.watch_state_timeout
    is WatchConnectionState.Disconnected -> R.string.watch_state_disconnected
    is WatchConnectionState.Error -> R.string.watch_state_error
}

private fun HeartRatePermissionState.permissionText(): Int = when (this) {
    HeartRatePermissionState.Granted -> R.string.watch_permission_granted
    HeartRatePermissionState.Denied -> R.string.watch_permission_denied
    HeartRatePermissionState.NotRequested -> R.string.watch_permission_not_requested
    HeartRatePermissionState.NotRequired -> R.string.watch_permission_not_required
    HeartRatePermissionState.Unavailable -> R.string.watch_permission_unavailable
}

private fun WatchNotice.messageRes(): Int = when (this) {
    is WatchNotice.Timeout -> R.string.watch_notice_timeout
    is WatchNotice.InvalidResponse -> R.string.watch_notice_invalid_response
    is WatchNotice.Declined -> declineText(reason)
}

private fun declineText(reason: WatchDeclineReason): Int = when (reason) {
    WatchDeclineReason.NotReady -> R.string.watch_notice_not_ready
    WatchDeclineReason.SensorUnavailable -> R.string.watch_notice_sensor_unavailable
    WatchDeclineReason.PermissionDenied -> R.string.watch_notice_permission_denied
    WatchDeclineReason.ProtocolMismatch -> R.string.watch_notice_protocol_mismatch
    WatchDeclineReason.InvalidRequest -> R.string.watch_notice_invalid_request
    WatchDeclineReason.InternalError -> R.string.watch_notice_internal_error
}
