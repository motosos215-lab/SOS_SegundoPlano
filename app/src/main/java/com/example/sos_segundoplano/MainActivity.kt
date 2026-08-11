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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import com.example.sos_segundoplano.core.background.AndroidMonitoringServiceStarter
import com.example.sos_segundoplano.core.background.AndroidMonitoringServiceStopper
import com.example.sos_segundoplano.core.background.MonitoringServiceStartResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStarter
import com.example.sos_segundoplano.core.background.MonitoringServiceStopResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStopper
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.core.push.MonitorAlertProvider
import com.example.sos_segundoplano.core.profile.ProfileProvider
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
import com.example.sos_segundoplano.data.remote.incident.IncidentRemoteProvider
import com.example.sos_segundoplano.data.remote.trip.FinishTripRequestDto
import com.example.sos_segundoplano.data.remote.trip.RemoteTripFinisher
import com.example.sos_segundoplano.data.remote.trip.ResolvedRemoteTripStarter
import com.example.sos_segundoplano.data.remote.trip.TripMutationResult
import com.example.sos_segundoplano.data.remote.trip.TripRemoteSessionProvider
import com.example.sos_segundoplano.data.rules.RiskAssessmentStoreProvider
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationCoordinatorProvider
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationStoreProvider
import com.example.sos_segundoplano.data.signals.TripSignalStoreProvider
import com.example.sos_segundoplano.data.trip.InMemoryTripSessionStore
import com.example.sos_segundoplano.data.trip.TripSessionStore
import com.example.sos_segundoplano.data.trip.TripSessionStoreProvider
import com.example.sos_segundoplano.data.trip.AndroidElapsedRealtimeClock
import com.example.sos_segundoplano.data.trip.TripTimingStoreProvider
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.monitoring.MonitoringReadinessFactory
import com.example.sos_segundoplano.domain.offline.OfflineQueueSummary
import com.example.sos_segundoplano.domain.rules.RiskAssessmentState
import com.example.sos_segundoplano.domain.signals.TripSignalSnapshot
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import com.example.sos_segundoplano.domain.validation.UserResponseSource
import com.example.sos_segundoplano.domain.trip.ElapsedRealtimeClock
import com.example.sos_segundoplano.domain.trip.TripLocalSummary
import com.example.sos_segundoplano.domain.trip.TripLocalSummaryFactory
import com.example.sos_segundoplano.domain.trip.TripTimingState
import com.example.sos_segundoplano.domain.trip.TripTimingStore
import com.example.sos_segundoplano.features.background.AccidentCountdownScreen
import com.example.sos_segundoplano.domain.usecase.FinishTripUseCase
import com.example.sos_segundoplano.domain.usecase.StartTripUseCase
import com.example.sos_segundoplano.features.background.MonitoringScreen
import com.example.sos_segundoplano.features.auth.InitialSessionRestoration
import com.example.sos_segundoplano.features.auth.MotoSosRoot
import com.example.sos_segundoplano.features.monitor.MonitorRoot
import com.example.sos_segundoplano.features.permissions.BackgroundLocationPermissionDialog
import com.example.sos_segundoplano.features.permissions.BluetoothRequirementDialog
import com.example.sos_segundoplano.features.permissions.MonitoringStartFailureDialog
import com.example.sos_segundoplano.features.permissions.MonitoringStopFailureDialog
import com.example.sos_segundoplano.features.permissions.NotificationPermissionDialog
import com.example.sos_segundoplano.features.permissions.NotificationRuntimePermissionGate
import com.example.sos_segundoplano.features.profile.ProfileRoute
import com.example.sos_segundoplano.features.sos.RiderSosScreen
import com.example.sos_segundoplano.features.trip.HomeScreen
import com.example.sos_segundoplano.features.trip.TripLocalSummaryScreen
import com.example.sos_segundoplano.push.MonitorAlertIntent
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        capturePendingMonitorAlert(intent)
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
                NotificationRuntimePermissionGate(
                    lifecycleOwner = this@MainActivity,
                    onOpenSettings = ::openNotificationSettings
                ) {
                    MotoSosRoot(
                        authRepository = authRepository,
                        initialSessionRestoration = initialSessionRestoration,
                        riderContent = {
                            MotoSosApp(
                                locationPermissionStatusProvider = BackgroundLocationPermissionChecker(applicationContext),
                                notificationStatusProvider = AppNotificationStatusChecker(applicationContext),
                                bluetoothRequirementStatusProvider = BluetoothRequirementChecker(applicationContext),
                                monitoringServiceStarter = AndroidMonitoringServiceStarter(applicationContext),
                                monitoringServiceStopper = AndroidMonitoringServiceStopper(applicationContext),
                                tripSessionStore = TripSessionStoreProvider.store,
                                tripTimingStore = TripTimingStoreProvider.store,
                                remoteTripStarter = TripRemoteSessionProvider.get(applicationContext).resolvedStarter,
                                remoteTripFinisher = TripRemoteSessionProvider.get(applicationContext).finisher,
                                onManualSos = { IncidentRemoteProvider.requestManualSos(applicationContext) },
                                offlineQueueSummaries = OfflineQueueProvider.get(applicationContext).repository.observeSummary(),
                                profileContent = { onHomeSelected, onSosSelected ->
                                    ProfileRoute(
                                        profileRepository = ProfileProvider.get(applicationContext),
                                        authRepository = authRepository,
                                        onHomeSelected = onHomeSelected,
                                        onSosSelected = onSosSelected
                                    )
                                },
                                onOpenAppSettings = ::openAppSettings,
                                onOpenNotificationSettings = ::openNotificationSettings,
                                onOpenBluetoothSettings = ::openBluetoothSettings
                            )
                        },
                        monitorContent = { MonitorRoot(authRepository) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        capturePendingMonitorAlert(intent)
    }

    private fun capturePendingMonitorAlert(intent: Intent?) {
        val payload = MonitorAlertIntent.parse(intent) ?: return
        MonitorAlertProvider.get(applicationContext).record(payload)
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
    tripSessionStore: TripSessionStore? = null,
    tripTimingStore: TripTimingStore? = null,
    remoteTripStarter: ResolvedRemoteTripStarter? = null,
    remoteTripFinisher: RemoteTripFinisher? = null,
    elapsedRealtimeClock: ElapsedRealtimeClock = AndroidElapsedRealtimeClock,
    signalSnapshots: StateFlow<TripSignalSnapshot> = TripSignalStoreProvider.store.snapshots,
    validationStates: StateFlow<FalsePositiveValidationState> = FalsePositiveValidationStoreProvider.store.states,
    riskAssessmentStates: StateFlow<RiskAssessmentState> = RiskAssessmentStoreProvider.store.states,
    offlineQueueSummaries: kotlinx.coroutines.flow.Flow<OfflineQueueSummary>? = null,
    onConfirmSafe: (Long, Long, String) -> Unit = { sessionId, assessmentId, responseId ->
        FalsePositiveValidationCoordinatorProvider.coordinator.confirmSafe(sessionId, assessmentId, UserResponseSource.Mobile, responseId)
    },
    onRequestHelp: (Long, Long, String) -> Unit = { sessionId, assessmentId, responseId ->
        FalsePositiveValidationCoordinatorProvider.coordinator.requestHelp(sessionId, assessmentId, UserResponseSource.Mobile, responseId)
    },
    onManualSos: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenBluetoothSettings: () -> Unit = {},
    readinessLifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    profileContent: (@Composable (() -> Unit, () -> Unit) -> Unit)? = null
) {
    val resolvedTripSessionStore = tripSessionStore ?: remember { InMemoryTripSessionStore() }
    val resolvedTripTimingStore = tripTimingStore
    val coroutineScope = rememberCoroutineScope()
    var selectedScreen by remember { mutableStateOf(MotoSosAppScreen.Home) }
    var sosReturnScreen by remember { mutableStateOf(MotoSosAppScreen.Home) }
    var isTripStartPending by remember { mutableStateOf(false) }
    var isRemoteTripStartInProgress by remember { mutableStateOf(false) }
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
    var tripSummaryVisible by rememberSaveable { mutableStateOf(false) }
    var tripSummaryDuration by rememberSaveable { mutableStateOf<String?>(null) }
    var monitoringReadiness by remember {
        mutableStateOf(
            MonitoringReadinessFactory.create(
                locationPermissionStatusProvider.getStatus(),
                notificationStatusProvider.getStatus(),
                bluetoothRequirementStatusProvider.getStatus()
            )
        )
    }
    val currentState = resolvedTripSessionStore.states.collectAsState().value
    val validationState = validationStates.collectAsState().value
    val offlineQueueSummary = (offlineQueueSummaries ?: kotlinx.coroutines.flow.flowOf(OfflineQueueSummary()))
        .collectAsState(OfflineQueueSummary())
        .value
    var dismissedEmergencyStateKey by remember { mutableStateOf<String?>(null) }

    fun refreshMonitoringReadiness() {
        monitoringReadiness = MonitoringReadinessFactory.create(
            locationPermissionStatusProvider.getStatus(),
            notificationStatusProvider.getStatus(),
            bluetoothRequirementStatusProvider.getStatus()
        )
    }

    DisposableEffect(readinessLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshMonitoringReadiness()
            }
        }
        readinessLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { readinessLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(selectedScreen, currentState, tripSummaryVisible) {
        if (
            selectedScreen == MotoSosAppScreen.Home &&
            currentState == TripSessionState.Idle &&
            !tripSummaryVisible
        ) {
            refreshMonitoringReadiness()
        }
    }

    fun openManualSos() {
        if (selectedScreen != MotoSosAppScreen.Sos) {
            sosReturnScreen = if (currentState == TripSessionState.Active) {
                MotoSosAppScreen.Home
            } else {
                selectedScreen
            }
            selectedScreen = MotoSosAppScreen.Sos
        }
    }

    fun closeManualSos() {
        selectedScreen = if (currentState == TripSessionState.Active) {
            MotoSosAppScreen.Home
        } else {
            sosReturnScreen
        }
    }

    if (currentState == TripSessionState.Active && selectedScreen == MotoSosAppScreen.Profile) {
        selectedScreen = MotoSosAppScreen.Home
    }

    BackHandler(enabled = currentState == TripSessionState.Idle && selectedScreen == MotoSosAppScreen.Profile) {
        selectedScreen = MotoSosAppScreen.Home
    }

    fun dismissTripSummary() {
        tripSummaryVisible = false
        tripSummaryDuration = null
        selectedScreen = MotoSosAppScreen.Home
    }

    fun startMonitoringAfterRemoteTrip() {
        if (!isTripStartPending || resolvedTripSessionStore.states.value != TripSessionState.Idle) return
        when (monitoringServiceStarter.start()) {
            MonitoringServiceStartResult.Started -> {
                dismissTripSummary()
                val nextState = startTripUseCase(TripSessionState.Idle)
                resolvedTripSessionStore.setState(nextState)
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
    }

    fun resolveRemoteTripThenStartMonitoring() {
        val starter = remoteTripStarter
        if (starter == null) {
            startMonitoringAfterRemoteTrip()
            return
        }
        if (isRemoteTripStartInProgress) return
        isRemoteTripStartInProgress = true
        coroutineScope.launch {
            val result = starter.startTrip()
            isRemoteTripStartInProgress = false
            if (result is TripMutationResult.Success) {
                startMonitoringAfterRemoteTrip()
            } else if (isTripStartPending) {
                permissionDialogStatus = null
                isNotificationDialogVisible = false
                bluetoothDialogStatus = null
                monitoringStartFailureVisible = true
                monitoringStopFailureVisible = false
            }
        }
    }

    fun validateTripStartRequirements() {
        when (val locationStatus = locationPermissionStatusProvider.getStatus()) {
            BackgroundLocationPermissionStatus.Granted -> {
                when (notificationStatusProvider.getStatus()) {
                    AppNotificationStatus.Enabled -> {
                        when (val bluetoothStatus = bluetoothRequirementStatusProvider.getStatus()) {
                            BluetoothRequirementStatus.Enabled -> {
                                if (isTripStartPending && currentState == TripSessionState.Idle) {
                                    resolveRemoteTripThenStartMonitoring()
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

    fun showLocationReadinessAction() {
        isTripStartPending = false
        val status = locationPermissionStatusProvider.getStatus()
        refreshMonitoringReadiness()
        permissionDialogStatus = status.takeUnless { it == BackgroundLocationPermissionStatus.Granted }
        isNotificationDialogVisible = false
        bluetoothDialogStatus = null
    }

    fun showNotificationReadinessAction() {
        isTripStartPending = false
        val status = notificationStatusProvider.getStatus()
        refreshMonitoringReadiness()
        permissionDialogStatus = null
        isNotificationDialogVisible = status == AppNotificationStatus.Disabled
        bluetoothDialogStatus = null
    }

    fun showBluetoothReadinessAction() {
        isTripStartPending = false
        val status = bluetoothRequirementStatusProvider.getStatus()
        refreshMonitoringReadiness()
        permissionDialogStatus = null
        isNotificationDialogVisible = false
        bluetoothDialogStatus = status.takeUnless { it == BluetoothRequirementStatus.Enabled }
    }

    fun finishActiveTrip() {
        if (currentState != TripSessionState.Active || isTripFinishInProgress) {
            return
        }

        isTripFinishInProgress = true
        monitoringStopFailureVisible = false
        val frozenSummary = if (validationState.canShowNormalTripSummary()) {
            val timingState = resolvedTripTimingStore?.states?.value ?: TripTimingState.Unknown
            TripLocalSummaryFactory(elapsedRealtimeClock).capture(timingState)
        } else {
            null
        }

        when (monitoringServiceStopper.stop()) {
            MonitoringServiceStopResult.Stopped,
            MonitoringServiceStopResult.AlreadyStopped -> {
                resolvedTripTimingStore?.clear()
                val nextState = finishTripUseCase(currentState)
                tripSummaryDuration = frozenSummary?.durationText
                tripSummaryVisible = frozenSummary != null
                resolvedTripSessionStore.setState(nextState)
                remoteTripFinisher?.let { finisher ->
                    coroutineScope.launch {
                        finisher.finishTrip(FinishTripRequestDto())
                    }
                }
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

    val emergencyStateKey = validationState.emergencyScreenKey()
    if (tripSummaryVisible && currentState == TripSessionState.Idle) {
        TripLocalSummaryScreen(
            summary = TripLocalSummary(tripSummaryDuration),
            onReturnHome = ::dismissTripSummary,
            modifier = modifier
        )
    } else if (currentState == TripSessionState.Active && emergencyStateKey != null && emergencyStateKey != dismissedEmergencyStateKey) {
        AccidentCountdownScreen(
            state = validationState,
            modifier = modifier,
            onContinueTrip = { dismissedEmergencyStateKey = emergencyStateKey }
        )
    } else if (selectedScreen == MotoSosAppScreen.Sos) {
        RiderSosScreen(
            canSubmitManualSos = true,
            onSubmitManualSos = {
                onManualSos()
                selectedScreen = MotoSosAppScreen.Home
            },
            onNavigateBack = ::closeManualSos,
            onHomeSelected = { selectedScreen = MotoSosAppScreen.Home },
            onProfileSelected = {
                selectedScreen = if (currentState == TripSessionState.Idle) {
                    MotoSosAppScreen.Profile
                } else {
                    MotoSosAppScreen.Home
                }
            },
            modifier = modifier
        )
    } else if (validationState is FalsePositiveValidationState.CountdownActive) {
        AccidentCountdownScreen(
            state = validationState,
            modifier = modifier,
            onConfirmSafe = onConfirmSafe,
            onRequestHelp = onRequestHelp
        )
    } else when (currentState) {
        TripSessionState.Idle -> when (selectedScreen) {
            MotoSosAppScreen.Home -> HomeScreen(
                onStartTrip = {
                    isTripStartPending = true
                    validateTripStartRequirements()
                },
                monitoringReadiness = monitoringReadiness,
                onLocationReadinessAction = ::showLocationReadinessAction,
                onNotificationReadinessAction = ::showNotificationReadinessAction,
                onBluetoothReadinessAction = ::showBluetoothReadinessAction,
                onSosSelected = ::openManualSos,
                onProfileSelected = {
                    refreshMonitoringReadiness()
                    selectedScreen = MotoSosAppScreen.Profile
                },
                modifier = modifier
            )

            MotoSosAppScreen.Profile -> profileContent?.invoke(
                { selectedScreen = MotoSosAppScreen.Home },
                ::openManualSos
            )
                ?: HomeScreen(
                    onStartTrip = {
                        isTripStartPending = true
                        validateTripStartRequirements()
                    },
                    monitoringReadiness = monitoringReadiness,
                    onLocationReadinessAction = ::showLocationReadinessAction,
                    onNotificationReadinessAction = ::showNotificationReadinessAction,
                    onBluetoothReadinessAction = ::showBluetoothReadinessAction,
                    onSosSelected = ::openManualSos,
                    onProfileSelected = { selectedScreen = MotoSosAppScreen.Profile },
                    modifier = modifier
                )

            MotoSosAppScreen.Sos -> Unit
        }

        TripSessionState.Active -> MonitoringScreen(
            modifier = modifier,
            tripTimingStates = resolvedTripTimingStore?.states,
            elapsedRealtimeClock = elapsedRealtimeClock,
            snapshot = signalSnapshots.collectAsState().value,
            riskAssessmentState = riskAssessmentStates.collectAsState().value,
            offlineQueueSummary = offlineQueueSummary,
            onSosSelected = ::openManualSos,
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
                if (isTripStartPending) {
                    validateTripStartRequirements()
                } else {
                    showLocationReadinessAction()
                }
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
                if (isTripStartPending) {
                    validateTripStartRequirements()
                } else {
                    showNotificationReadinessAction()
                }
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
                if (isTripStartPending) {
                    validateTripStartRequirements()
                } else {
                    showBluetoothReadinessAction()
                }
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

private fun FalsePositiveValidationState.emergencyScreenKey(): String? = when (this) {
    is FalsePositiveValidationState.HelpRequested -> "help-${metadata.sessionId}-${metadata.assessmentId}-$responseId"
    is FalsePositiveValidationState.IncidentGenerated -> "incident-${incident.sessionId}-${incident.assessmentId}-${incident.incidentId}-${incident.cause}"
    is FalsePositiveValidationState.ImmediateAlertRequested -> "immediate-${incident.sessionId}-${incident.assessmentId}-${incident.incidentId}"
    is FalsePositiveValidationState.Error -> "error-${metadata?.sessionId}-${metadata?.assessmentId}-$message"
    else -> null
}

private fun FalsePositiveValidationState.canShowNormalTripSummary(): Boolean = when (this) {
    is FalsePositiveValidationState.CountdownActive,
    is FalsePositiveValidationState.HelpRequested,
    is FalsePositiveValidationState.IncidentGenerated,
    is FalsePositiveValidationState.ImmediateAlertRequested,
    is FalsePositiveValidationState.Error -> false
    else -> true
}

private enum class MotoSosAppScreen { Home, Profile, Sos }

@Preview(showBackground = true)
@Composable
fun MotoSosAppPreview() {
    SOS_SegundoPlanoTheme {
        MotoSosApp()
    }
}
