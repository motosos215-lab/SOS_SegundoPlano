package com.example.sos_segundoplano

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.sos_segundoplano.core.background.AndroidMonitoringServiceStarter
import com.example.sos_segundoplano.core.background.AndroidMonitoringServiceStopper
import com.example.sos_segundoplano.core.background.MonitoringServiceStartResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStarter
import com.example.sos_segundoplano.core.background.MonitoringServiceStopResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStopper
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.core.permissions.AppNotificationStatus
import com.example.sos_segundoplano.core.permissions.AppNotificationStatusChecker
import com.example.sos_segundoplano.core.permissions.AppNotificationStatusProvider
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionChecker
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatus
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatusProvider
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementChecker
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatus
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatusProvider
import com.example.sos_segundoplano.data.offline.OfflineQueueProvider
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationCoordinatorProvider
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationStoreProvider
import com.example.sos_segundoplano.data.signals.TripSignalStoreProvider
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.offline.OfflineQueueSummary
import com.example.sos_segundoplano.domain.signals.TripSignalSnapshot
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import com.example.sos_segundoplano.domain.validation.UserResponseSource
import com.example.sos_segundoplano.domain.usecase.FinishTripUseCase
import com.example.sos_segundoplano.domain.usecase.StartTripUseCase
import com.example.sos_segundoplano.features.background.MonitoringScreen
import com.example.sos_segundoplano.features.auth.InitialSessionRestoration
import com.example.sos_segundoplano.features.auth.MotoSosRoot
import com.example.sos_segundoplano.features.permissions.BackgroundLocationPermissionDialog
import com.example.sos_segundoplano.features.permissions.BluetoothRequirementDialog
import com.example.sos_segundoplano.features.permissions.MonitoringStartFailureDialog
import com.example.sos_segundoplano.features.permissions.MonitoringStopFailureDialog
import com.example.sos_segundoplano.features.permissions.NotificationPermissionDialog
import com.example.sos_segundoplano.features.trip.HomeScreen
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val authRepository = AuthProvider.get(applicationContext)
        val initialSessionRestoration = InitialSessionRestoration {
            AuthProvider.restorationResult()?.await()
        }
        setContent {
            SOS_SegundoPlanoTheme {
                MotoSosRoot(
                    authRepository = authRepository,
                    initialSessionRestoration = initialSessionRestoration
                ) {
                    MotoSosApp(
                        locationPermissionStatusProvider = BackgroundLocationPermissionChecker(applicationContext),
                        notificationStatusProvider = AppNotificationStatusChecker(applicationContext),
                        bluetoothRequirementStatusProvider = BluetoothRequirementChecker(applicationContext),
                        monitoringServiceStarter = AndroidMonitoringServiceStarter(applicationContext),
                        monitoringServiceStopper = AndroidMonitoringServiceStopper(applicationContext),
                        offlineQueueSummaries = OfflineQueueProvider.get(applicationContext).repository.observeSummary(),
                        onOpenAppSettings = ::openAppSettings,
                        onOpenNotificationSettings = ::openNotificationSettings,
                        onOpenBluetoothSettings = ::openBluetoothSettings
                    )
                }
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    private fun openNotificationSettings() {
        val notificationSettingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

        try {
            startActivity(notificationSettingsIntent)
        } catch (_: ActivityNotFoundException) {
            val fallbackIntent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(fallbackIntent)
        }
    }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                openAppSettings()
            }
        }
    }
}

@Composable
fun MotoSosApp(
    modifier: Modifier = Modifier,
    startTripUseCase: (TripSessionState) -> TripSessionState = StartTripUseCase()::invoke,
    finishTripUseCase: (TripSessionState) -> TripSessionState = FinishTripUseCase()::invoke,
    locationPermissionStatusProvider: BackgroundLocationPermissionStatusProvider =
        BackgroundLocationPermissionStatusProvider { BackgroundLocationPermissionStatus.Granted },
    notificationStatusProvider: AppNotificationStatusProvider =
        AppNotificationStatusProvider { AppNotificationStatus.Enabled },
    bluetoothRequirementStatusProvider: BluetoothRequirementStatusProvider =
        BluetoothRequirementStatusProvider { BluetoothRequirementStatus.Enabled },
    monitoringServiceStarter: MonitoringServiceStarter =
        MonitoringServiceStarter { MonitoringServiceStartResult.Started },
    monitoringServiceStopper: MonitoringServiceStopper =
        MonitoringServiceStopper { MonitoringServiceStopResult.Stopped },
    signalSnapshots: StateFlow<TripSignalSnapshot> = TripSignalStoreProvider.store.snapshots,
    validationStates: StateFlow<FalsePositiveValidationState> = FalsePositiveValidationStoreProvider.store.states,
    offlineQueueSummaries: kotlinx.coroutines.flow.Flow<OfflineQueueSummary>? = null,
    onConfirmSafe: (Long, Long, String) -> Unit = { sessionId, assessmentId, responseId ->
        FalsePositiveValidationCoordinatorProvider.coordinator.confirmSafe(sessionId, assessmentId, UserResponseSource.Mobile, responseId)
    },
    onRequestHelp: (Long, Long, String) -> Unit = { sessionId, assessmentId, responseId ->
        FalsePositiveValidationCoordinatorProvider.coordinator.requestHelp(sessionId, assessmentId, UserResponseSource.Mobile, responseId)
    },
    onOpenAppSettings: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenBluetoothSettings: () -> Unit = {}
) {
    var isTripActive by rememberSaveable { mutableStateOf(false) }
    var isTripStartPending by remember { mutableStateOf(false) }
    var permissionDialogStatus by remember {
        mutableStateOf<BackgroundLocationPermissionStatus?>(null)
    }
    var isNotificationDialogVisible by remember { mutableStateOf(false) }
    var bluetoothDialogStatus by remember {
        mutableStateOf<BluetoothRequirementStatus?>(null)
    }
    var monitoringStartFailureVisible by remember { mutableStateOf(false) }
    var isTripFinishInProgress by remember { mutableStateOf(false) }
    var monitoringStopFailureVisible by remember { mutableStateOf(false) }
    val currentState = if (isTripActive) {
        TripSessionState.Active
    } else {
        TripSessionState.Idle
    }
    val offlineQueueSummary = (offlineQueueSummaries ?: kotlinx.coroutines.flow.flowOf(OfflineQueueSummary()))
        .collectAsState(OfflineQueueSummary())
        .value

    fun validateTripStartRequirements() {
        when (val locationStatus = locationPermissionStatusProvider.getStatus()) {
            BackgroundLocationPermissionStatus.Granted -> {
                when (notificationStatusProvider.getStatus()) {
                    AppNotificationStatus.Enabled -> {
                        when (val bluetoothStatus = bluetoothRequirementStatusProvider.getStatus()) {
                            BluetoothRequirementStatus.Enabled -> {
                                if (isTripStartPending && currentState == TripSessionState.Idle) {
                                    when (monitoringServiceStarter.start()) {
                                        MonitoringServiceStartResult.Started -> {
                                            val nextState = startTripUseCase(currentState)
                                            isTripActive = nextState == TripSessionState.Active
                                            isTripStartPending = false
                                            permissionDialogStatus = null
                                            isNotificationDialogVisible = false
                                            bluetoothDialogStatus = null
                                            monitoringStartFailureVisible = false
                                            monitoringStopFailureVisible = false
                                        }

                                        MonitoringServiceStartResult.Failed -> {
                                            permissionDialogStatus = null
                                            isNotificationDialogVisible = false
                                            bluetoothDialogStatus = null
                                            monitoringStartFailureVisible = true
                                            monitoringStopFailureVisible = false
                                        }
                                    }
                                } else {
                                    permissionDialogStatus = null
                                    isNotificationDialogVisible = false
                                    bluetoothDialogStatus = null
                                    monitoringStartFailureVisible = false
                                    monitoringStopFailureVisible = false
                                }
                            }

                            BluetoothRequirementStatus.PermissionMissing,
                            BluetoothRequirementStatus.Disabled,
                            BluetoothRequirementStatus.Unsupported -> {
                                permissionDialogStatus = null
                                isNotificationDialogVisible = false
                                bluetoothDialogStatus = bluetoothStatus
                                monitoringStartFailureVisible = false
                                monitoringStopFailureVisible = false
                            }
                        }
                    }

                    AppNotificationStatus.Disabled -> {
                        permissionDialogStatus = null
                        isNotificationDialogVisible = true
                        bluetoothDialogStatus = null
                        monitoringStartFailureVisible = false
                        monitoringStopFailureVisible = false
                    }
                }
            }

            BackgroundLocationPermissionStatus.ForegroundMissing,
            BackgroundLocationPermissionStatus.BackgroundMissing -> {
                permissionDialogStatus = locationStatus
                isNotificationDialogVisible = false
                bluetoothDialogStatus = null
                monitoringStartFailureVisible = false
                monitoringStopFailureVisible = false
            }
        }
    }

    fun finishActiveTrip() {
        if (currentState != TripSessionState.Active || isTripFinishInProgress) {
            return
        }

        isTripFinishInProgress = true
        monitoringStopFailureVisible = false

        when (monitoringServiceStopper.stop()) {
            MonitoringServiceStopResult.Stopped,
            MonitoringServiceStopResult.AlreadyStopped -> {
                val nextState = finishTripUseCase(currentState)
                isTripActive = nextState == TripSessionState.Active
                isTripFinishInProgress = false
                monitoringStopFailureVisible = false
                isTripStartPending = false
                permissionDialogStatus = null
                isNotificationDialogVisible = false
                bluetoothDialogStatus = null
                monitoringStartFailureVisible = false
            }

            MonitoringServiceStopResult.Failed -> {
                isTripFinishInProgress = false
                monitoringStopFailureVisible = true
            }
        }
    }

    when (currentState) {
        TripSessionState.Idle -> HomeScreen(
            onStartTrip = {
                isTripStartPending = true
                validateTripStartRequirements()
            },
            modifier = modifier
        )

        TripSessionState.Active -> MonitoringScreen(
            modifier = modifier,
            snapshot = signalSnapshots.collectAsState().value,
            validationState = validationStates.collectAsState().value,
            offlineQueueSummary = offlineQueueSummary,
            onConfirmSafe = onConfirmSafe,
            onRequestHelp = onRequestHelp,
            onFinishTrip = {
                finishActiveTrip()
            },
            isFinishTripEnabled = !isTripFinishInProgress
        )
    }

    permissionDialogStatus?.let { status ->
        BackgroundLocationPermissionDialog(
            status = status,
            onOpenSettings = onOpenAppSettings,
            onRecheckPermissions = {
                validateTripStartRequirements()
            },
            onDismiss = {
                isTripStartPending = false
                permissionDialogStatus = null
                isNotificationDialogVisible = false
                bluetoothDialogStatus = null
                monitoringStartFailureVisible = false
                monitoringStopFailureVisible = false
            }
        )
    }
    if (isNotificationDialogVisible) {
        NotificationPermissionDialog(
            onOpenSettings = onOpenNotificationSettings,
            onRecheckPermissions = {
                validateTripStartRequirements()
            },
            onDismiss = {
                isTripStartPending = false
                isNotificationDialogVisible = false
                permissionDialogStatus = null
                bluetoothDialogStatus = null
                monitoringStartFailureVisible = false
                monitoringStopFailureVisible = false
            }
        )
    }
    bluetoothDialogStatus?.let { status ->
        BluetoothRequirementDialog(
            status = status,
            onOpenAppSettings = onOpenAppSettings,
            onOpenBluetoothSettings = onOpenBluetoothSettings,
            onRecheckRequirements = {
                validateTripStartRequirements()
            },
            onDismiss = {
                isTripStartPending = false
                bluetoothDialogStatus = null
                isNotificationDialogVisible = false
                permissionDialogStatus = null
                monitoringStartFailureVisible = false
                monitoringStopFailureVisible = false
            }
        )
    }
    if (monitoringStartFailureVisible) {
        MonitoringStartFailureDialog(
            onOpenNotificationSettings = onOpenNotificationSettings,
            onRetry = {
                validateTripStartRequirements()
            },
            onDismiss = {
                isTripStartPending = false
                monitoringStartFailureVisible = false
                bluetoothDialogStatus = null
                isNotificationDialogVisible = false
                permissionDialogStatus = null
            }
        )
    }
    if (monitoringStopFailureVisible) {
        MonitoringStopFailureDialog(
            onRetry = {
                finishActiveTrip()
            },
            onDismiss = {
                monitoringStopFailureVisible = false
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MotoSosAppPreview() {
    SOS_SegundoPlanoTheme {
        MotoSosApp()
    }
}
