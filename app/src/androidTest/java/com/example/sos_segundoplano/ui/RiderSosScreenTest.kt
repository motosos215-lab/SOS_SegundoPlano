package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.test.espresso.Espresso.pressBackUnconditionally
import com.example.sos_segundoplano.MotoSosApp
import com.example.sos_segundoplano.core.background.MonitoringServiceStopResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStopper
import com.example.sos_segundoplano.data.trip.InMemoryTripSessionStore
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.features.sos.RiderSosScreen
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RiderSosScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun riderCanOpenManualSosFromHomeAndCancelReturnsWithoutRequestingHelp() {
        var helpRequests = 0
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(onRequestHelp = { _, _, _ -> helpRequests++ })
            }
        }

        composeRule.onNodeWithTag("bottom_nav_sos").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("rider_sos_screen").assertIsDisplayed()
        composeRule.onNodeWithText("SOS Manual").assertIsDisplayed()
        composeRule.onNodeWithText("¿Necesitas ayuda?").assertIsDisplayed()
        composeRule.onNodeWithText("Enviar alerta SOS").assertIsDisplayed()
        composeRule.onNodeWithText("Cancelar").assertIsDisplayed()
        composeRule.onNodeWithTag("sos_central_badge").assertIsDisplayed()

        composeRule.onNodeWithTag("cancel_sos_button").performClick()

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        assertEquals(0, helpRequests)
    }

    @Test fun systemBackReturnsHomeWithoutRequestingHelp() {
        var helpRequests = 0
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(onRequestHelp = { _, _, _ -> helpRequests++ })
            }
        }

        composeRule.onNodeWithTag("bottom_nav_sos").performClick()
        pressBackUnconditionally()

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        assertEquals(0, helpRequests)
    }

    @Test fun localSendIsSingleShotAndExposesAccessibleActions() {
        var helpRequests = 0
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                RiderSosScreen(
                    canRequestLocalHelp = true,
                    onRequestLocalHelp = { helpRequests++ },
                    onNavigateBack = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Botón visual SOS con anillos de alerta").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Regresar").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancelar SOS y volver").assertIsDisplayed()
        composeRule.onNodeWithTag("send_sos_button").assertIsEnabled().performTouchInput { doubleClick() }
        composeRule.onNodeWithTag("send_sos_button").assertIsNotEnabled()

        assertEquals(1, helpRequests)
    }

    @Test fun sendIsSafelyBlockedWithoutActiveLocalValidation() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                RiderSosScreen(
                    canRequestLocalHelp = false,
                    onRequestLocalHelp = { error("Blocked SOS must not request help") },
                    onNavigateBack = {}
                )
            }
        }

        composeRule.onNodeWithTag("send_sos_button").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("sos_local_unavailable").assertIsDisplayed()
    }

    @Test fun cancelDuringActiveTripPreservesTripAndDoesNotStopMonitoring() {
        val tripStore = InMemoryTripSessionStore(TripSessionState.Active)
        var stopCalls = 0
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    tripSessionStore = tripStore,
                    monitoringServiceStopper = MonitoringServiceStopper {
                        stopCalls++
                        MonitoringServiceStopResult.Stopped
                    }
                )
            }
        }

        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom_nav_sos").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("rider_sos_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("cancel_sos_button").performClick()

        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        assertEquals(TripSessionState.Active, tripStore.states.value)
        assertEquals(0, stopCalls)
    }
}
