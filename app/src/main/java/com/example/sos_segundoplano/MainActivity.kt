package com.example.sos_segundoplano

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Button
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.tooling.preview.Preview
import com.example.sos_segundoplano.core.background.AndroidMonitoringServiceStarter
import com.example.sos_segundoplano.core.background.AndroidMonitoringServiceStopper
import com.example.sos_segundoplano.core.background.MonitoringServiceStartResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStarter
import com.example.sos_segundoplano.core.background.MonitoringServiceStopResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStopper
import com.example.sos_segundoplano.core.auth.AuthProvider
import com.example.sos_segundoplano.core.push.MonitorAlertProvider
import com.example.sos_segundoplano.core.push.RiderMonitorFeedbackProvider
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
import com.example.sos_segundoplano.data.remote.emergency.EmergencyContactsProvider
import com.example.sos_segundoplano.data.repository.RiderHistoryProvider
import com.example.sos_segundoplano.data.remote.incident.IncidentRemoteProvider
import com.example.sos_segundoplano.data.remote.incident.ManualSosRequestState
import com.example.sos_segundoplano.data.remote.incident.ManualSosSubmissionOptions
import com.example.sos_segundoplano.data.remote.incident.AndroidCurrentManualSosLocationProvider
import com.example.sos_segundoplano.data.remote.incident.TripSignalManualSosLocationProvider
import com.example.sos_segundoplano.data.remote.trip.FinishTripRequestDto
import com.example.sos_segundoplano.data.remote.trip.toTripLocationDto
import com.example.sos_segundoplano.data.remote.trip.RemoteTripFinisher
import com.example.sos_segundoplano.data.remote.trip.ResolvedRemoteTripStarter
import com.example.sos_segundoplano.data.remote.trip.TripMutationResult
import com.example.sos_segundoplano.data.remote.trip.TripStartLocationCaptureState
import com.example.sos_segundoplano.data.remote.trip.TripRemoteSessionProvider
import com.example.sos_segundoplano.data.remote.trip.PendingTripFinish
import com.example.sos_segundoplano.data.remote.trip.PendingTripFinishState
import com.example.sos_segundoplano.data.remote.trip.UserTripFinishCoordinator
import com.example.sos_segundoplano.data.remote.trip.UserTripFinishResult
import com.example.sos_segundoplano.data.rules.RiskAssessmentStoreProvider
import com.example.sos_segundoplano.data.route.TripRouteProvider
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationCoordinatorProvider
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationStoreProvider
import com.example.sos_segundoplano.data.signals.TripSignalStoreProvider
import com.example.sos_segundoplano.data.trip.InMemoryTripSessionStore
import com.example.sos_segundoplano.data.trip.TripSessionStore
import com.example.sos_segundoplano.data.trip.TripSessionStoreProvider
import com.example.sos_segundoplano.data.trip.RecoveredMonitoringStatus
import com.example.sos_segundoplano.data.trip.TripProcessRecoveryProvider
import com.example.sos_segundoplano.data.trip.TripProcessRecoveryState
import com.example.sos_segundoplano.data.trip.AndroidElapsedRealtimeClock
import com.example.sos_segundoplano.data.trip.TripTimingStoreProvider
import com.example.sos_segundoplano.domain.emergency.EmergencyContact
import com.example.sos_segundoplano.domain.auth.SessionRevoked
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.emergency.EmergencyContactsResult
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
import com.example.sos_segundoplano.domain.trip.TripTimingClearResult
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
import com.example.sos_segundoplano.features.history.RiderHistoryScreen
import com.example.sos_segundoplano.features.history.RiderHistoryViewModel
import com.example.sos_segundoplano.features.map.RiderMapScreen
import com.example.sos_segundoplano.features.trip.HomeScreen
import com.example.sos_segundoplano.features.trip.RiderSyncUiState
import com.example.sos_segundoplano.features.trip.RiderEmergencyContactUiState
import com.example.sos_segundoplano.features.trip.RiderEmergencyContactDetailScreen
import com.example.sos_segundoplano.features.trip.RiderMonitorMessagesScreen
import com.example.sos_segundoplano.features.trip.RiderTripRecoveryGate
import com.example.sos_segundoplano.features.trip.RemoteTripFinishFailureDialog
import com.example.sos_segundoplano.features.trip.TripLocalSummaryScreen
import com.example.sos_segundoplano.push.MonitorAlertIntent
import com.example.sos_segundoplano.push.RiderMonitorFeedbackNotificationFactory
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        capturePendingMonitorAlert(intent)
        captureRiderFeedbackNavigation(intent)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val authRepository = AuthProvider.get(applicationContext)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                authRepository.observeSession()
                    .map { it is SessionState.Authenticated || it is SessionState.Refreshing }
                    .distinctUntilChanged()
                    .collectLatest { authenticated ->
                    if (!authenticated) return@collectLatest
                    while (isActive) {
                        when (authRepository.validateCurrentSession()) {
                            SessionRevoked -> {
                                AndroidMonitoringServiceStopper(applicationContext).stop()
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.login_session_revoked),
                                    Toast.LENGTH_LONG
                                ).show()
                                break
                            }
                            else -> Unit
                        }
                        delay(SESSION_LIVENESS_INTERVAL_MILLIS)
                    }
                }
            }
        }
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
                        onRegisterWebSelected = ::openRegistrationWeb,
                        onPasswordRecoverySelected = ::openPasswordRecoveryWeb,
                        riderContent = {
                            val recoveryCoordinator = remember {
                                TripProcessRecoveryProvider.get(applicationContext)
                            }
                            RiderTripRecoveryGate(
                                authRepository = authRepository,
                                coordinator = recoveryCoordinator
                            ) { recoveryState ->
                                val recoveryIdentity = (recoveryState as? TripProcessRecoveryState.Active)?.identity
                                val remoteTripDependencies = remember {
                                    TripRemoteSessionProvider.get(applicationContext)
                                }
                                MotoSosApp(
                                    locationPermissionStatusProvider = BackgroundLocationPermissionChecker(applicationContext),
                                    notificationStatusProvider = AppNotificationStatusChecker(applicationContext),
                                    bluetoothRequirementStatusProvider = BluetoothRequirementChecker(applicationContext),
                                    monitoringServiceStarter = AndroidMonitoringServiceStarter(applicationContext),
                                    monitoringServiceStopper = AndroidMonitoringServiceStopper(applicationContext),
                                    tripSessionStore = TripSessionStoreProvider.store,
                                    tripTimingStore = TripTimingStoreProvider.store,
                                    remoteTripStarter = remoteTripDependencies.resolvedStarter,
                                    remoteTripFinisher = remoteTripDependencies.finisher,
                                    userTripFinishCoordinator = remoteTripDependencies.finishCoordinator,
                                    pendingTripFinishStates = remoteTripDependencies.pendingFinishStore.state,
                                    remoteTripStartLocationCaptureStates = remoteTripDependencies.startLocationCaptureStates,
                                    onManualSosWithOptions = { options ->
                                        IncidentRemoteProvider.requestManualSos(applicationContext, options)
                                    },
                                    manualSosRequestStates = IncidentRemoteProvider.manualSosRequestState,
                                    offlineQueueSummaries = OfflineQueueProvider.get(applicationContext).repository.observeSummary(),
                                    routePointPendingCountProvider = { ownerUserId ->
                                        TripRouteProvider.get(applicationContext).database.routePointDao().observePendingCount(ownerUserId)
                                    },
                                    profileContent = { openWatchConnectionInitially, onHomeSelected, onTripsSelected, onSosSelected, onMapSelected ->
                                        ProfileRoute(
                                            profileRepository = ProfileProvider.get(applicationContext),
                                            authRepository = authRepository,
                                            openWatchConnectionInitially = openWatchConnectionInitially,
                                            onHomeSelected = onHomeSelected,
                                            onTripsSelected = onTripsSelected,
                                            onSosSelected = onSosSelected,
                                            onMapSelected = onMapSelected
                                        )
                                    },
                                    onOpenAppSettings = ::openAppSettings,
                                    onOpenNotificationSettings = ::openNotificationSettings,
                                    onOpenBluetoothSettings = ::openBluetoothSettings,
                                    onEditEmergencyContactWeb = ::openEmergencyContactsWeb,
                                    recoveredMonitoringStatus = (recoveryState as? TripProcessRecoveryState.Active)?.monitoringStatus,
                                    onRetryRecoveredMonitoring = {
                                        recoveryIdentity?.let { identity ->
                                            lifecycleScope.launch {
                                                recoveryCoordinator.retryMonitoring(identity)
                                            }
                                        }
                                    }
                                )
                            }
                        },
                        monitorContent = { MonitorRoot(authRepository) }
                    )
                }
            }
        }
    }

    private companion object {
        const val SESSION_LIVENESS_INTERVAL_MILLIS = 10_000L
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        capturePendingMonitorAlert(intent)
        captureRiderFeedbackNavigation(intent)
    }

    private fun capturePendingMonitorAlert(intent: Intent?) {
        val payload = MonitorAlertIntent.parse(intent) ?: return
        MonitorAlertProvider.get(applicationContext).record(payload)
    }

    private fun captureRiderFeedbackNavigation(intent: Intent?) {
        if (intent?.getBooleanExtra(RiderMonitorFeedbackNotificationFactory.EXTRA_OPEN_RIDER_MESSAGES, false) == true) {
            RiderMonitorFeedbackProvider.requestOpenMessages()
            intent.removeExtra(RiderMonitorFeedbackNotificationFactory.EXTRA_OPEN_RIDER_MESSAGES)
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

    private fun openRegistrationWeb() = openMotoSosWebUrl(
        BuildConfig.MOTOSOS_WEB_REGISTER_URL,
        "registro"
    )

    private fun openPasswordRecoveryWeb() = openMotoSosWebUrl(
        BuildConfig.MOTOSOS_WEB_PASSWORD_RECOVERY_URL,
        "recuperación de contraseña"
    )

    private fun openEmergencyContactsWeb() = openMotoSosWebUrl(
        BuildConfig.MOTOSOS_WEB_CONTACTS_URL,
        "contactos de emergencia"
    )

    private fun openMotoSosWebUrl(url: String, destinationName: String) {
        val destination = url.trim()
        if (destination.isBlank()) {
            Toast.makeText(
                this,
                "Falta configurar la URL web de $destinationName.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No hay un navegador disponible para abrir MotoSOS Web.", Toast.LENGTH_LONG).show()
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
    userTripFinishCoordinator: UserTripFinishCoordinator? = null,
    pendingTripFinishStates: StateFlow<PendingTripFinish?>? = null,
    remoteTripStartLocationCaptureStates: StateFlow<TripStartLocationCaptureState>? = null,
    elapsedRealtimeClock: ElapsedRealtimeClock = AndroidElapsedRealtimeClock,
    signalSnapshots: StateFlow<TripSignalSnapshot> = TripSignalStoreProvider.store.snapshots,
    validationStates: StateFlow<FalsePositiveValidationState> = FalsePositiveValidationStoreProvider.store.states,
    riskAssessmentStates: StateFlow<RiskAssessmentState> = RiskAssessmentStoreProvider.store.states,
    offlineQueueSummaries: Flow<OfflineQueueSummary>? = null,
    routePointPendingCountProvider: (String) -> Flow<Int> = { flowOf(0) },
    onConfirmSafe: (Long, Long, String) -> Unit = { sessionId, assessmentId, responseId ->
        FalsePositiveValidationCoordinatorProvider.coordinator.confirmSafe(sessionId, assessmentId, UserResponseSource.Mobile, responseId)
    },
    onRequestHelp: (Long, Long, String) -> Unit = { sessionId, assessmentId, responseId ->
        FalsePositiveValidationCoordinatorProvider.coordinator.requestHelp(sessionId, assessmentId, UserResponseSource.Mobile, responseId)
    },
    onManualSos: () -> Unit = {},
    onManualSosWithOptions: (ManualSosSubmissionOptions) -> Unit = { onManualSos() },
    manualSosRequestStates: StateFlow<ManualSosRequestState>? = null,
    onOpenAppSettings: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenBluetoothSettings: () -> Unit = {},
    onEditEmergencyContactWeb: () -> Unit = {},
    readinessLifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    recoveredMonitoringStatus: RecoveredMonitoringStatus? = null,
    onRetryRecoveredMonitoring: () -> Unit = {},
    profileContent: (@Composable (Boolean, () -> Unit, () -> Unit, () -> Unit, () -> Unit) -> Unit)? = null
) {
    val resolvedTripSessionStore = tripSessionStore ?: remember { InMemoryTripSessionStore() }
    val context = LocalContext.current.applicationContext
    val authRepository = remember(context) { AuthProvider.get(context) }
    val riderSessionState = authRepository.observeSession().collectAsState().value
    val riderUserId = when (riderSessionState) {
        is SessionState.Authenticated -> riderSessionState.user.id
        is SessionState.Refreshing -> riderSessionState.user.id
        else -> ""
    }
    val riderFeedbackCoordinator = remember(context) { RiderMonitorFeedbackProvider.get(context) }
    val allRiderFeedbackMessages = riderFeedbackCoordinator.messages.collectAsState().value
    val riderFeedbackMessages = remember(allRiderFeedbackMessages, riderUserId) {
        allRiderFeedbackMessages.filter { it.ownerUserId == riderUserId }
    }
    val riderFeedbackOpenRevision = RiderMonitorFeedbackProvider.openRequestRevision.collectAsState().value
    var handledRiderFeedbackOpenRevision by rememberSaveable { mutableStateOf(0L) }
    val tripLocationProvider = remember(context) { TripSignalManualSosLocationProvider(TripSignalStoreProvider.store, currentLocationProvider = AndroidCurrentManualSosLocationProvider(context)) }
    val manualSosRequestState = manualSosRequestStates?.collectAsState()?.value ?: ManualSosRequestState.Idle
    val resolvedTripTimingStore = tripTimingStore
    val coroutineScope = rememberCoroutineScope()
    var selectedScreen by remember { mutableStateOf(MotoSosAppScreen.Home) }
    var openWatchConnectionRequested by remember { mutableStateOf(false) }
    val riderHistoryViewModel = remember(context) { RiderHistoryViewModel(RiderHistoryProvider.get(context)) }
    val emergencyContactsRepository = remember(context) { EmergencyContactsProvider.get(context) }
    var emergencyContactState by remember { mutableStateOf<RiderEmergencyContactUiState>(RiderEmergencyContactUiState.Loading) }
    var emergencyContactRefreshRevision by remember { mutableStateOf(0) }
    var sosReturnScreen by remember { mutableStateOf(MotoSosAppScreen.Home) }
    var isTripStartPending by remember { mutableStateOf(false) }
    var isRemoteTripStartInProgress by remember { mutableStateOf(false) }
    var startLocationDecisionVisible by remember { mutableStateOf(false) }
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
    var remoteTripFinishFailureVisible by remember { mutableStateOf(false) }
    var tripFinishSavedDialogVisible by remember { mutableStateOf(false) }
    var tripFinishNeedsAttentionDialogVisible by remember { mutableStateOf(false) }
    var remoteFinishConfirmedForTripKey by remember { mutableStateOf<String?>(null) }
    var tripSummaryVisible by rememberSaveable { mutableStateOf(false) }
    var tripSummaryDuration by rememberSaveable { mutableStateOf<String?>(null) }
    var manualSosSentDialogVisible by remember { mutableStateOf(false) }
    var manualSosSavedDialogVisible by remember { mutableStateOf(false) }
    var automaticSosSavedDialogKey by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(riderFeedbackOpenRevision, riderUserId) {
        if (
            riderFeedbackOpenRevision > handledRiderFeedbackOpenRevision &&
            riderUserId.isNotBlank()
        ) {
            handledRiderFeedbackOpenRevision = riderFeedbackOpenRevision
            selectedScreen = MotoSosAppScreen.Messages
        }
    }

    LaunchedEffect(selectedScreen, riderUserId) {
        if (selectedScreen == MotoSosAppScreen.Messages && riderUserId.isNotBlank()) {
            riderFeedbackCoordinator.markAllRead(riderUserId)
        }
    }
    val offlineQueueSummary = (offlineQueueSummaries ?: kotlinx.coroutines.flow.flowOf(OfflineQueueSummary()))
        .collectAsState(OfflineQueueSummary())
        .value
    val pendingTripFinish = pendingTripFinishStates?.collectAsState()?.value
    val manualSosPending = remember(manualSosRequestState, riderUserId) {
        riderUserId.isNotBlank() && IncidentRemoteProvider.hasPendingManualSosForCurrentRider(context)
    }
    val routePointPendingCount = remember(riderUserId) {
        if (riderUserId.isBlank()) flowOf(0) else routePointPendingCountProvider(riderUserId)
    }.collectAsState(initial = 0).value.coerceAtLeast(0)
    val riderSyncUiState = remember(offlineQueueSummary, manualSosPending, pendingTripFinish, riderUserId, routePointPendingCount) {
        val ownedTripFinish = pendingTripFinish?.takeIf { it.ownerUserId == riderUserId }
        RiderSyncUiState(
            automaticSosPendingCount = offlineQueueSummary.automaticSosPendingCount +
                offlineQueueSummary.automaticSosInFlightCount +
                offlineQueueSummary.automaticSosRetryPendingCount,
            automaticSosFailedCount = offlineQueueSummary.automaticSosFailedCount,
            manualSosPending = manualSosPending,
            tripFinishPending = ownedTripFinish != null && ownedTripFinish.state != PendingTripFinishState.FailedPermanent,
            tripFinishNeedsAttention = ownedTripFinish?.state == PendingTripFinishState.FailedPermanent,
            routePointPendingCount = routePointPendingCount
        )
    }
    var dismissedEmergencyStateKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(emergencyContactRefreshRevision) {
        emergencyContactState = RiderEmergencyContactUiState.Loading
        emergencyContactState = when (val result = emergencyContactsRepository.list()) {
            is EmergencyContactsResult.Success -> {
                result.value.preferredHomeEmergencyContact()?.let(RiderEmergencyContactUiState::Content)
                    ?: RiderEmergencyContactUiState.Empty
            }
            is EmergencyContactsResult.Failure -> RiderEmergencyContactUiState.Unavailable
        }
    }

    LaunchedEffect(recoveredMonitoringStatus) {
        if (currentState !is TripSessionState.Active) return@LaunchedEffect
        permissionDialogStatus = null
        isNotificationDialogVisible = false
        bluetoothDialogStatus = null
        monitoringStartFailureVisible = false
        when (val status = recoveredMonitoringStatus) {
            is RecoveredMonitoringStatus.Blocked -> when (status.readiness) {
                com.example.sos_segundoplano.data.trip.MonitoringRecoveryReadiness.LocationMissing ->
                    permissionDialogStatus = locationPermissionStatusProvider.getStatus()
                        .takeUnless { it == BackgroundLocationPermissionStatus.Granted }
                com.example.sos_segundoplano.data.trip.MonitoringRecoveryReadiness.NotificationsMissing ->
                    isNotificationDialogVisible = true
                com.example.sos_segundoplano.data.trip.MonitoringRecoveryReadiness.BluetoothMissing ->
                    bluetoothDialogStatus = bluetoothRequirementStatusProvider.getStatus()
                        .takeUnless { it == BluetoothRequirementStatus.Enabled }
                com.example.sos_segundoplano.data.trip.MonitoringRecoveryReadiness.Ready -> Unit
            }
            RecoveredMonitoringStatus.Failed -> monitoringStartFailureVisible = true
            RecoveredMonitoringStatus.Started,
            RecoveredMonitoringStatus.AlreadyStarted,
            null -> Unit
        }
    }

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
                emergencyContactRefreshRevision++
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
            emergencyContactRefreshRevision++
        }
    }

    LaunchedEffect(manualSosRequestState) {
        when (manualSosRequestState) {
            ManualSosRequestState.Sent -> {
                manualSosSavedDialogVisible = false
                manualSosSentDialogVisible = true
            }
            ManualSosRequestState.SavedOffline -> manualSosSavedDialogVisible = true
            else -> Unit
        }
    }

    LaunchedEffect(validationState) {
        val retry = validationState as? FalsePositiveValidationState.IncidentDeliveryRetrying
        if (retry != null) {
            automaticSosSavedDialogKey = validationState.emergencyScreenKey()
        } else if (
            validationState is FalsePositiveValidationState.IncidentGenerated ||
            validationState is FalsePositiveValidationState.ImmediateAlertRequested
        ) {
            automaticSosSavedDialogKey = null
        }
    }

    LaunchedEffect(currentState) {
        if (currentState !is TripSessionState.Active) automaticSosSavedDialogKey = null
    }

    fun openProfile() {
        openWatchConnectionRequested = false
        selectedScreen = MotoSosAppScreen.Profile
    }

    fun openWatchConnection() {
        openWatchConnectionRequested = true
        selectedScreen = MotoSosAppScreen.Profile
    }

    fun openManualSos() {
        if (manualSosRequestState !is ManualSosRequestState.Preparing &&
            manualSosRequestState !is ManualSosRequestState.Retrying &&
            manualSosRequestState !is ManualSosRequestState.WaitingForLocation &&
            manualSosRequestState !is ManualSosRequestState.Sending
        ) {
            IncidentRemoteProvider.clearManualSosRequestState()
        }
        if (selectedScreen != MotoSosAppScreen.Sos) {
            sosReturnScreen = if (currentState is TripSessionState.Active) {
                MotoSosAppScreen.Home
            } else {
                selectedScreen
            }
            selectedScreen = MotoSosAppScreen.Sos
        }
    }

    fun closeManualSos() {
        if (manualSosRequestState !is ManualSosRequestState.Preparing &&
            manualSosRequestState !is ManualSosRequestState.Retrying &&
            manualSosRequestState !is ManualSosRequestState.WaitingForLocation &&
            manualSosRequestState !is ManualSosRequestState.Sending
        ) {
            IncidentRemoteProvider.clearManualSosRequestState()
        }
        selectedScreen = if (currentState is TripSessionState.Active) {
            MotoSosAppScreen.Home
        } else {
            sosReturnScreen
        }
    }

    BackHandler(enabled = currentState == TripSessionState.Idle && selectedScreen == MotoSosAppScreen.Profile) {
        selectedScreen = MotoSosAppScreen.Home
    }

    BackHandler(enabled = selectedScreen == MotoSosAppScreen.EmergencyContact) {
        selectedScreen = MotoSosAppScreen.Home
    }

    BackHandler(enabled = selectedScreen == MotoSosAppScreen.Messages) {
        selectedScreen = MotoSosAppScreen.Home
    }

    fun dismissTripSummary() {
        tripSummaryVisible = false
        tripSummaryDuration = null
        selectedScreen = MotoSosAppScreen.Home
    }

    fun startMonitoringAfterRemoteTrip() {
        if (!isTripStartPending) return
        resolvedTripSessionStore.beginTripSession()
        when (monitoringServiceStarter.start()) {
            MonitoringServiceStartResult.Started -> {
                dismissTripSummary()
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

    fun resolveRemoteTripThenStartMonitoring(allowMissingStartLocation: Boolean = false) {
        val starter = remoteTripStarter
        if (starter == null) {
            startMonitoringAfterRemoteTrip()
            return
        }
        if (isRemoteTripStartInProgress) return
        val tripSession = resolvedTripSessionStore.beginTripSession()
        isRemoteTripStartInProgress = true
        coroutineScope.launch {
            val result = if (allowMissingStartLocation) {
                starter.startTripWithoutInitialLocation()
            } else {
                starter.startTrip()
            }
            isRemoteTripStartInProgress = false
            if (result is TripMutationResult.Success) {
                if (remoteTripStartLocationCaptureStates?.value == TripStartLocationCaptureState.Unavailable) {
                    Toast.makeText(context, "Viaje iniciado sin ubicación inicial.", Toast.LENGTH_LONG).show()
                }
                startMonitoringAfterRemoteTrip()
            } else if (result is TripMutationResult.MissingRequiredData && result.sanitizedMessage == "start_location_unavailable") {
                startLocationDecisionVisible = true
            } else if (result is TripMutationResult.MissingRequiredData) {
                resolvedTripSessionStore.setIdleIfMatches(tripSession.tripSessionKey)
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
        val activeTrip = currentState as? TripSessionState.Active ?: return
        if (isTripFinishInProgress) return

        isTripFinishInProgress = true
        monitoringStopFailureVisible = false
        remoteTripFinishFailureVisible = false
        val frozenSummary = if (validationState.canShowNormalTripSummary()) {
            val timingState = resolvedTripTimingStore?.states?.value ?: TripTimingState.Unknown
            TripLocalSummaryFactory(elapsedRealtimeClock).capture(timingState)
        } else {
            null
        }

        coroutineScope.launch {
            val finishRequest = FinishTripRequestDto(
                clientFinishedAtUtc = java.time.Instant.now().toString(),
                endLocation = tripLocationProvider.currentRealLocation()?.toTripLocationDto()
            )
            if (userTripFinishCoordinator != null) {
                when (userTripFinishCoordinator.finishTrip(activeTrip.tripSessionKey, finishRequest)) {
                    UserTripFinishResult.RemoteConfirmed -> remoteFinishConfirmedForTripKey = activeTrip.tripSessionKey
                    UserTripFinishResult.SavedForSync -> tripFinishSavedDialogVisible = true
                    UserTripFinishResult.SavedNeedsAttention -> tripFinishNeedsAttentionDialogVisible = true
                    is UserTripFinishResult.PersistenceFailed -> {
                        isTripFinishInProgress = false
                        remoteTripFinishFailureVisible = true
                        return@launch
                    }
                }
            } else if (remoteTripFinisher != null && remoteFinishConfirmedForTripKey != activeTrip.tripSessionKey) {
                val remoteResult = remoteTripFinisher.finishTrip(finishRequest)
                if (remoteResult !is TripMutationResult.Success) {
                    isTripFinishInProgress = false
                    remoteTripFinishFailureVisible = true
                    return@launch
                }
                remoteFinishConfirmedForTripKey = activeTrip.tripSessionKey
            }

            when (monitoringServiceStopper.stop()) {
                MonitoringServiceStopResult.Stopped,
                MonitoringServiceStopResult.AlreadyStopped -> {
                    val timingCleared = resolvedTripTimingStore?.let { timingStore ->
                        when (timingStore.clearIfMatches(activeTrip.tripSessionKey)) {
                            TripTimingClearResult.Cleared,
                            TripTimingClearResult.AlreadyEmpty -> true
                            TripTimingClearResult.DifferentTrip,
                            TripTimingClearResult.LegacyUncorrelated -> false
                        }
                    } ?: true
                    if (!timingCleared) {
                        isTripFinishInProgress = false
                        monitoringStopFailureVisible = true
                        return@launch
                    }
                    val nextState = finishTripUseCase(activeTrip)
                    val sessionCleared = if (nextState == TripSessionState.Idle) {
                        resolvedTripSessionStore.setIdleIfMatches(activeTrip.tripSessionKey) !=
                            com.example.sos_segundoplano.data.trip.TripSessionClearResult.DifferentTrip
                    } else {
                        resolvedTripSessionStore.setState(nextState)
                        true
                    }
                    if (!sessionCleared) {
                        isTripFinishInProgress = false
                        monitoringStopFailureVisible = true
                        return@launch
                    }
                    tripSummaryDuration = frozenSummary?.durationText
                    tripSummaryVisible = frozenSummary != null
                    remoteFinishConfirmedForTripKey = null
                    isTripFinishInProgress = false
                    monitoringStopFailureVisible = false
                    remoteTripFinishFailureVisible = false
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
    }

    val emergencyStateKey = validationState.emergencyScreenKey()
    if (currentState is TripSessionState.Active && emergencyStateKey != null && emergencyStateKey != dismissedEmergencyStateKey) {
        // Automatic emergency UI always wins over Map/History/SOS screens. Countdown actions must
        // remain wired here; otherwise an active countdown shown over another tab would be inert.
        AccidentCountdownScreen(
            state = validationState,
            modifier = modifier,
            onConfirmSafe = onConfirmSafe,
            onRequestHelp = onRequestHelp,
            onContinueTrip = { dismissedEmergencyStateKey = emergencyStateKey }
        )
    } else if (selectedScreen == MotoSosAppScreen.Messages) {
        RiderMonitorMessagesScreen(
            messages = riderFeedbackMessages,
            onBack = { selectedScreen = MotoSosAppScreen.Home },
            modifier = modifier
        )
    } else if (selectedScreen == MotoSosAppScreen.EmergencyContact) {
        RiderEmergencyContactDetailScreen(
            state = emergencyContactState,
            onBack = { selectedScreen = MotoSosAppScreen.Home },
            onEditMonitorWeb = onEditEmergencyContactWeb,
            modifier = modifier
        )
    } else if (selectedScreen == MotoSosAppScreen.Map) {
        RiderMapScreen(
            snapshot = signalSnapshots.collectAsState().value,
            tripActive = currentState is TripSessionState.Active,
            onHomeSelected = { selectedScreen = MotoSosAppScreen.Home },
            onTripsSelected = { selectedScreen = MotoSosAppScreen.History },
            onSosSelected = ::openManualSos,
            onProfileSelected = ::openProfile
        )
    } else if (selectedScreen == MotoSosAppScreen.History) {
        RiderHistoryScreen(
            viewModel = riderHistoryViewModel,
            onBack = { selectedScreen = MotoSosAppScreen.Home },
            onHomeSelected = { selectedScreen = MotoSosAppScreen.Home },
            onSosSelected = ::openManualSos,
            onMapSelected = { selectedScreen = MotoSosAppScreen.Map },
            onProfileSelected = ::openProfile
        )
    } else if (selectedScreen == MotoSosAppScreen.Profile) {
        profileContent?.invoke(
            openWatchConnectionRequested,
            { selectedScreen = MotoSosAppScreen.Home },
            { selectedScreen = MotoSosAppScreen.History },
            ::openManualSos,
            { selectedScreen = MotoSosAppScreen.Map }
        ) ?: HomeScreen(
            onStartTrip = {
                isTripStartPending = true
                validateTripStartRequirements()
            },
            isTripStartInProgress = isRemoteTripStartInProgress,
            monitoringReadiness = monitoringReadiness,
            onLocationReadinessAction = ::showLocationReadinessAction,
            onNotificationReadinessAction = ::showNotificationReadinessAction,
            onBluetoothReadinessAction = ::showBluetoothReadinessAction,
            onSosSelected = ::openManualSos,
            onMapSelected = { selectedScreen = MotoSosAppScreen.Map },
            onProfileSelected = ::openProfile,
            onTripsSelected = { selectedScreen = MotoSosAppScreen.History },
            onDeviceSelected = ::openWatchConnection,
            onEmergencyContactSelected = { selectedScreen = MotoSosAppScreen.EmergencyContact },
            emergencyContactState = emergencyContactState,
            monitorFeedbackMessages = riderFeedbackMessages,
            syncState = riderSyncUiState,
            onMessagesSelected = { selectedScreen = MotoSosAppScreen.Messages },
            modifier = modifier
        )
    } else if (tripSummaryVisible && currentState == TripSessionState.Idle) {
        TripLocalSummaryScreen(
            summary = TripLocalSummary(tripSummaryDuration),
            onReturnHome = ::dismissTripSummary,
            modifier = modifier
        )
    } else if (selectedScreen == MotoSosAppScreen.Sos) {
        RiderSosScreen(
            canSubmitManualSos = true,
            requestState = manualSosRequestState,
            onSubmitManualSos = { options ->
                onManualSosWithOptions(options)
            },
            onNavigateBack = ::closeManualSos,
            onHomeSelected = { selectedScreen = MotoSosAppScreen.Home },
            onTripsSelected = { selectedScreen = MotoSosAppScreen.History },
            onMapSelected = { selectedScreen = MotoSosAppScreen.Map },
            onProfileSelected = ::openProfile,
            modifier = modifier
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
                onMapSelected = { selectedScreen = MotoSosAppScreen.Map },
                onProfileSelected = {
                    refreshMonitoringReadiness()
                    openProfile()
                },
                isTripStartInProgress = isRemoteTripStartInProgress,
                onTripsSelected = { selectedScreen = MotoSosAppScreen.History },
                onDeviceSelected = ::openWatchConnection,
                onEmergencyContactSelected = { selectedScreen = MotoSosAppScreen.EmergencyContact },
                emergencyContactState = emergencyContactState,
                monitorFeedbackMessages = riderFeedbackMessages,
                syncState = riderSyncUiState,
                onMessagesSelected = { selectedScreen = MotoSosAppScreen.Messages },
                modifier = modifier
            )

            MotoSosAppScreen.Profile -> Unit

            MotoSosAppScreen.Sos -> Unit
            MotoSosAppScreen.History -> RiderHistoryScreen(
                viewModel = riderHistoryViewModel,
                onBack = { selectedScreen = MotoSosAppScreen.Home },
                onHomeSelected = { selectedScreen = MotoSosAppScreen.Home },
                onSosSelected = ::openManualSos,
                onMapSelected = { selectedScreen = MotoSosAppScreen.Map },
                onProfileSelected = ::openProfile
            )
            MotoSosAppScreen.Map -> Unit
            MotoSosAppScreen.EmergencyContact -> Unit
            MotoSosAppScreen.Messages -> Unit
        }

        is TripSessionState.Active -> MonitoringScreen(
            modifier = modifier,
            tripTimingStates = resolvedTripTimingStore?.states,
            elapsedRealtimeClock = elapsedRealtimeClock,
            snapshot = signalSnapshots.collectAsState().value,
            riskAssessmentState = riskAssessmentStates.collectAsState().value,
            onHomeSelected = { selectedScreen = MotoSosAppScreen.Home },
            onTripsSelected = { selectedScreen = MotoSosAppScreen.History },
            onSosSelected = ::openManualSos,
            onMapSelected = { selectedScreen = MotoSosAppScreen.Map },
            onProfileSelected = ::openProfile,
            onMessagesSelected = { selectedScreen = MotoSosAppScreen.Messages },
            unreadMessageCount = riderFeedbackMessages.count { !it.isRead },
            onFinishTrip = {
                finishActiveTrip()
            },
            isFinishTripEnabled = !isTripFinishInProgress,
            isFinishingTrip = isTripFinishInProgress
        )
    }

    permissionDialogStatus?.let { status ->
        BackgroundLocationPermissionDialog(
            status = status,
            onOpenSettings = onOpenAppSettings,
            onRecheckPermissions = {
                if (currentState is TripSessionState.Active) {
                    onRetryRecoveredMonitoring()
                } else if (isTripStartPending) {
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
                if (currentState is TripSessionState.Active) {
                    onRetryRecoveredMonitoring()
                } else if (isTripStartPending) {
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
                if (currentState is TripSessionState.Active) {
                    onRetryRecoveredMonitoring()
                } else if (isTripStartPending) {
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
                if (currentState is TripSessionState.Active) {
                    onRetryRecoveredMonitoring()
                } else {
                    validateTripStartRequirements()
                }
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
    if (manualSosSentDialogVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.sos_manual_sent_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        if (currentState is TripSessionState.Active) {
                            R.string.sos_manual_sent_dialog_trip_active
                        } else {
                            R.string.sos_manual_sent_dialog_idle
                        }
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        manualSosSentDialogVisible = false
                        IncidentRemoteProvider.clearManualSosRequestState()
                        if (currentState is TripSessionState.Active) {
                            selectedScreen = MotoSosAppScreen.Home
                        } else {
                            closeManualSos()
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (currentState is TripSessionState.Active) {
                                R.string.sos_manual_sent_dialog_button_active
                            } else {
                                R.string.sos_manual_sent_dialog_button_idle
                            }
                        )
                    )
                }
            }
        )
    }

    if (manualSosSavedDialogVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.sos_manual_saved_dialog_title)) },
            text = { Text(stringResource(R.string.sos_manual_saved_dialog_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        manualSosSavedDialogVisible = false
                        if (currentState is TripSessionState.Active) {
                            selectedScreen = MotoSosAppScreen.Home
                        } else {
                            closeManualSos()
                        }
                    }
                ) {
                    Text(stringResource(R.string.sos_manual_saved_dialog_button))
                }
            }
        )
    }

    automaticSosSavedDialogKey?.let { savedKey ->
        if (currentState is TripSessionState.Active) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.automatic_sos_saved_dialog_title)) },
                text = { Text(stringResource(R.string.automatic_sos_saved_dialog_message)) },
                confirmButton = {
                    Button(
                        onClick = {
                            automaticSosSavedDialogKey = null
                            dismissedEmergencyStateKey = savedKey
                            selectedScreen = MotoSosAppScreen.Home
                        }
                    ) {
                        Text(stringResource(R.string.automatic_sos_saved_dialog_button))
                    }
                }
            )
        }
    }

    if (startLocationDecisionVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { startLocationDecisionVisible = false; isTripStartPending = false },
            title = { Text("No pudimos obtener tu ubicación inicial.") },
            text = { Text("Reintenta para registrar el inicio o continúa sin ubicación.") },
            confirmButton = {
                Button(onClick = { startLocationDecisionVisible = false; resolveRemoteTripThenStartMonitoring() }) {
                    Text("Reintentar")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        startLocationDecisionVisible = false
                        resolveRemoteTripThenStartMonitoring(allowMissingStartLocation = true)
                    }
                ) { Text("Iniciar sin ubicación") }
            }
        )
    }
    if (tripFinishSavedDialogVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.trip_finish_saved_dialog_title)) },
            text = { Text(stringResource(R.string.trip_finish_saved_dialog_message)) },
            confirmButton = {
                Button(onClick = { tripFinishSavedDialogVisible = false }) {
                    Text(stringResource(R.string.trip_finish_saved_dialog_button))
                }
            }
        )
    }
    if (tripFinishNeedsAttentionDialogVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.trip_finish_saved_dialog_title)) },
            text = { Text(stringResource(R.string.trip_finish_attention_dialog_message)) },
            confirmButton = {
                Button(onClick = { tripFinishNeedsAttentionDialogVisible = false }) {
                    Text(stringResource(R.string.trip_finish_saved_dialog_button))
                }
            }
        )
    }

    if (remoteTripFinishFailureVisible) {
        RemoteTripFinishFailureDialog(
            onRetry = { finishActiveTrip() },
            onDismiss = { remoteTripFinishFailureVisible = false }
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
    is FalsePositiveValidationState.CountdownActive -> "countdown-${metadata.sessionId}-${metadata.assessmentId}"
    is FalsePositiveValidationState.HelpRequested -> "help-${metadata.sessionId}-${metadata.assessmentId}-$responseId"
    // One durable SOS should not reopen the emergency screen for every background retry attempt.
    is FalsePositiveValidationState.IncidentDeliveryRetrying -> "retry-${metadata.sessionId}-${metadata.assessmentId}"
    is FalsePositiveValidationState.IncidentGenerated -> "incident-${incident.sessionId}-${incident.assessmentId}-${incident.incidentId}-${incident.cause}"
    is FalsePositiveValidationState.ImmediateAlertRequested -> "immediate-${incident.sessionId}-${incident.assessmentId}-${incident.incidentId}"
    is FalsePositiveValidationState.Error -> "error-${metadata?.sessionId}-${metadata?.assessmentId}-$message"
    else -> null
}

private fun FalsePositiveValidationState.canShowNormalTripSummary(): Boolean = when (this) {
    is FalsePositiveValidationState.CountdownActive,
    is FalsePositiveValidationState.HelpRequested,
    is FalsePositiveValidationState.IncidentDeliveryRetrying,
    is FalsePositiveValidationState.IncidentGenerated,
    is FalsePositiveValidationState.ImmediateAlertRequested,
    is FalsePositiveValidationState.Error -> false
    else -> true
}

private fun List<EmergencyContact>.preferredHomeEmergencyContact(): EmergencyContact? {
    val active = filter { it.isActive }
    val candidates = if (active.isNotEmpty()) active else this
    return candidates.sortedWith(
        compareByDescending<EmergencyContact> { it.isPrimary }
            .thenByDescending { it.invitationStatus.equals("Linked", ignoreCase = true) && it.linkedUserId != null }
            .thenBy { it.priority }
    ).firstOrNull()
}

private enum class MotoSosAppScreen { Home, Profile, Sos, History, Map, EmergencyContact, Messages }

@Preview(showBackground = true)
@Composable
fun MotoSosAppPreview() {
    SOS_SegundoPlanoTheme {
        MotoSosApp()
    }
}
