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
        onOpenHeartRatePermission: () -> Unit = {}
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
                signalSnapshot = signalSnapshot
            )
        }
    }
}
