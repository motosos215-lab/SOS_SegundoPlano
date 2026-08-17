package com.example.sos_segundoplano.wear

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WearMotoSosAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unconfirmedTripStateShowsConnectingState() {
        render(WearTripState())

        composeRule.onNodeWithTag("wear_trip_status").assertIsDisplayed()
        composeRule.onAllNodesWithText("Conectando con teléfono").assertCountEquals(1)
        composeRule.onAllNodesWithText("Listo para iniciar").assertCountEquals(0)
        composeRule.onAllNodesWithText("Viaje activo").assertCountEquals(0)
    }

    @Test
    fun confirmedInactiveStateShowsReadyScreen() {
        render(WearTripState(active = false, connected = true))

        composeRule.onAllNodesWithText("¡Listo para tu viaje!").assertCountEquals(1)
        composeRule.onNodeWithTag("wear_start_trip").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Finalizar viaje").assertCountEquals(0)
        composeRule.onAllNodesWithText("Teléfono conectado").assertCountEquals(1)
    }

    @Test
    fun startButtonDelegatesOnce() {
        var starts = 0
        render(WearTripState(active = false, connected = true), onStart = { starts += 1 })

        composeRule.onNodeWithTag("wear_start_trip")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, starts)
    }

    @Test
    fun startButtonIsDisabledWhileStarting() {
        render(
            tripState = WearTripState(active = false, connected = true),
            actionState = WearTripUiActionState.InFlight(WearTripUiOperation.Start)
        )

        composeRule.onNodeWithTag("wear_start_trip").assertIsNotEnabled()
        composeRule.onAllNodesWithText("Iniciando…").assertCountEquals(1)
    }

    @Test
    fun confirmedActiveStateShowsActiveTripScreen() {
        render(WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = true))

        composeRule.onAllNodesWithText("Viaje activo").assertCountEquals(1)
        composeRule.onNodeWithTag("wear_finish_trip").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Iniciar viaje").assertCountEquals(0)
    }

    @Test
    fun activeTripOpensManualSosWithoutSendingImmediately() {
        var manualSosCalls = 0
        render(
            tripState = WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = true),
            onConfirmManualSosHold = { manualSosCalls += 1 }
        )

        composeRule.onNodeWithTag("wear_manual_sos_open").performScrollTo().performClick()

        composeRule.onNodeWithTag("wear_manual_sos_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("wear_manual_sos_hold").performScrollTo().assertIsDisplayed()
        assertEquals(0, manualSosCalls)
    }

    @Test
    fun manualSosSuccessShowsAlertSentOnlyAfterConfirmedState() {
        render(
            tripState = WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = true),
            manualSosState = WearManualSosUiState.Success
        )

        composeRule.onNodeWithTag("wear_manual_sos_open").performScrollTo().performClick()

        composeRule.onNodeWithTag("wear_manual_sos_alert_sent").assertIsDisplayed()
        composeRule.onNodeWithTag("wear_manual_sos_back_to_trip").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun confirmedValidationKeepsPriorityOverManualSosNavigation() {
        render(
            tripState = WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = true),
            validationStatus = countdown()
        )

        composeRule.onNodeWithTag("wear_validation_countdown").assertIsDisplayed()
        composeRule.onAllNodesWithTag("wear_manual_sos_open").assertCountEquals(0)
        composeRule.onAllNodesWithTag("wear_manual_sos_screen").assertCountEquals(0)
    }

    @Test
    fun disconnectedActiveTripRemainsOnActiveTripScreen() {
        render(WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = false))

        composeRule.onAllNodesWithText("Viaje activo").assertCountEquals(1)
        composeRule.onAllNodesWithText("Sin conexión con el teléfono").assertCountEquals(1)
        composeRule.onAllNodesWithText("Listo para iniciar").assertCountEquals(0)
    }

    @Test
    fun activeTripWithoutConfirmedRemoteTripIdDisablesFinish() {
        render(WearTripState(active = true, remoteTripId = null, connected = true))

        composeRule.onAllNodesWithText("Viaje activo").assertCountEquals(1)
        composeRule.onNodeWithTag("wear_finish_trip").assertIsNotEnabled()
    }

    @Test
    fun finishButtonDelegatesOnce() {
        var finishes = 0
        render(
            tripState = WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = true),
            onFinish = { finishes += 1 }
        )

        composeRule.onNodeWithTag("wear_finish_trip")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, finishes)
    }

    @Test
    fun finishButtonIsDisabledWhileFinishing() {
        render(
            tripState = WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = true),
            actionState = WearTripUiActionState.InFlight(WearTripUiOperation.Finish)
        )

        composeRule.onNodeWithTag("wear_finish_trip").assertIsNotEnabled()
        composeRule.onAllNodesWithText("Finalizando…").assertCountEquals(1)
    }

    @Test
    fun activeTripShowsElapsedTimePlaceholderWhenStartTimeIsMissing() {
        render(WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = true))

        composeRule.onAllNodesWithText("--:--").assertCountEquals(1)
        composeRule.onAllNodesWithText("Tiempo de viaje").assertCountEquals(1)
    }

    @Test
    fun activeTripWithHealthPermissionRequiredShowsClickablePermissionAction() {
        var opensPermission = 0
        render(
            tripState = WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = true),
            signalSnapshot = WearSignalSnapshot(status = WearCaptureStatus.PermissionRequired),
            onOpenHeartRatePermission = { opensPermission += 1 }
        )

        composeRule.onNodeWithTag("wear_heart_rate_permission")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, opensPermission)
    }

    @Test
    fun activeTripWithGrantedCaptureDoesNotShowPermissionAction() {
        render(
            tripState = WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = true),
            signalSnapshot = WearSignalSnapshot(status = WearCaptureStatus.Capturing)
        )

        composeRule.onAllNodesWithTag("wear_heart_rate_permission").assertCountEquals(0)
    }

    @Test
    fun confirmedCountdownHasPriorityOverActiveTripAndShowsPhoneRemainingTime() {
        render(
            tripState = WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = true),
            validationStatus = countdown(12_000L)
        )

        composeRule.onNodeWithTag("wear_validation_countdown").assertIsDisplayed()
        composeRule.onNodeWithTag("wear_validation_remaining_time").assertIsDisplayed()
        composeRule.onAllNodesWithText("00:12").assertCountEquals(1)
        composeRule.onAllNodesWithText("Viaje activo").assertCountEquals(0)
    }

    @Test
    fun countdownButtonsDelegateOnlyToValidationActions() {
        var safeCalls = 0
        var helpCalls = 0
        render(
            tripState = WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = true),
            validationStatus = countdown(),
            onConfirmSafe = { safeCalls += 1 },
            onRequestHelp = { helpCalls += 1 }
        )

        composeRule.onNodeWithTag("wear_validation_confirm_safe").performScrollTo().performClick()
        composeRule.onNodeWithTag("wear_validation_request_help").performScrollTo().performClick()

        assertEquals(1, safeCalls)
        assertEquals(1, helpCalls)
    }

    @Test
    fun countdownBlocksBothActionsWhileValidationRequestIsInFlight() {
        render(
            tripState = WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = true),
            validationStatus = countdown(),
            validationActionState = WearValidationUiActionState.InFlight(WearValidationUiOperation.ConfirmSafe)
        )

        composeRule.onNodeWithTag("wear_validation_confirm_safe").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("wear_validation_request_help").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun confirmedValidationEndReturnsToThePhoneDerivedTripScreen() {
        render(WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = true))

        composeRule.onNodeWithTag("wear_trip_status").assertIsDisplayed()
        composeRule.onAllNodesWithText("Viaje activo").assertCountEquals(1)
    }

    @Test
    fun confirmedValidationEndWithoutTripReturnsToReady() {
        render(WearTripState(active = false, connected = true))

        composeRule.onNodeWithTag("wear_start_trip").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("¡Listo para tu viaje!").assertCountEquals(1)
    }

    @Test
    fun countdownKeepsLastConfirmedStateWhenPhoneIsDisconnected() {
        render(
            tripState = WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = false),
            validationStatus = countdown()
        )

        composeRule.onNodeWithTag("wear_validation_countdown").assertIsDisplayed()
        composeRule.onNodeWithTag("wear_validation_connection_state")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("Sin conexión con el teléfono").assertCountEquals(1)
    }

    @Test
    fun openMonitoringShowsAvailableSignalValuesAndReturnsToTrip() {
        render(
            tripState = WearTripState(active = true, remoteTripId = "trip-ui-test-123", connected = true),
            signalSnapshot = WearSignalSnapshot(
                accelerometer = WearVectorSample(3f, 4f, 0f, 1L, null),
                gyroscope = WearVectorSample(1f, 2f, 3f, 1L, null),
                heartRateBpm = 72.0,
                watchBatteryPercentage = 84
            )
        )

        composeRule.onNodeWithTag("wear_open_monitoring").performScrollTo().performClick()
        composeRule.onNodeWithTag("wear_monitoring_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("wear_monitoring_accelerometer").assertIsDisplayed()
        composeRule.onNodeWithTag("wear_monitoring_gyroscope").assertIsDisplayed()
        composeRule.onNodeWithTag("wear_monitoring_heart_rate").assertIsDisplayed()
        composeRule.onNodeWithTag("wear_monitoring_battery").assertIsDisplayed()
        composeRule.onAllNodesWithText("Simular incidente").assertCountEquals(0)

        composeRule.onNodeWithText("Volver al viaje").performScrollTo().performClick()
        composeRule.onNodeWithTag("wear_trip_status").assertIsDisplayed()
    }

    @Test
    fun retryableErrorShowsMessageAndRetryAction() {
        render(
            tripState = WearTripState(active = false, connected = true),
            actionState = WearTripUiActionState.Error(WearTripUiOperation.Start, "Teléfono no disponible.", true)
        )

        composeRule.onAllNodesWithText("Teléfono no disponible.").assertCountEquals(1)
        composeRule.onNodeWithTag("wear_retry_action").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun retryButtonDelegatesOnce() {
        var retries = 0
        render(
            tripState = WearTripState(active = false, connected = true),
            actionState = WearTripUiActionState.Error(WearTripUiOperation.Start, "Teléfono no disponible.", true),
            onRetry = { retries += 1 }
        )

        composeRule.onNodeWithTag("wear_retry_action").performScrollTo().performClick()

        assertEquals(1, retries)
    }

    @Test
    fun terminalErrorDoesNotShowRetryAction() {
        render(
            tripState = WearTripState(active = false, connected = true),
            actionState = WearTripUiActionState.Error(WearTripUiOperation.Start, "Revisa MotoSOS en tu teléfono.", false)
        )

        composeRule.onAllNodesWithText("Revisa MotoSOS en tu teléfono.").assertCountEquals(1)
        composeRule.onAllNodesWithText("Reintentar").assertCountEquals(0)
    }

    private fun render(
        tripState: WearTripState,
        actionState: WearTripUiActionState = WearTripUiActionState.Idle,
        onStart: () -> Unit = {},
        onFinish: () -> Unit = {},
        onRetry: () -> Unit = {},
        signalSnapshot: WearSignalSnapshot = WearSignalSnapshot(),
        onOpenHeartRatePermission: () -> Unit = {},
        validationStatus: WearDataLayerProtocol.ValidationStatus? = null,
        validationActionState: WearValidationUiActionState = WearValidationUiActionState.Idle,
        onConfirmSafe: () -> Unit = {},
        onRequestHelp: () -> Unit = {},
        manualSosState: WearManualSosUiState = WearManualSosUiState.Idle,
        onConfirmManualSosHold: () -> Unit = {}
    ) {
        composeRule.setContent {
            WearMotoSosScreen(
                tripState = tripState,
                actionState = actionState,
                onStartTrip = onStart,
                onFinishTrip = onFinish,
                onRetry = onRetry,
                onRefresh = {},
                onOpenHeartRatePermission = onOpenHeartRatePermission,
                signalSnapshot = signalSnapshot,
                validationStatus = validationStatus,
                validationActionState = validationActionState,
                onConfirmSafe = onConfirmSafe,
                onRequestHelp = onRequestHelp,
                manualSosState = manualSosState,
                onConfirmManualSosHold = onConfirmManualSosHold
            )
        }
    }

    private fun countdown(remainingMillis: Long = 20_000L) = WearDataLayerProtocol.ValidationStatus(
        state = "countdown_active",
        sessionId = 101L,
        assessmentId = 202L,
        remainingMillis = remainingMillis
    )
}
