package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBackUnconditionally
import com.example.sos_segundoplano.MotoSosApp
import com.example.sos_segundoplano.core.background.MonitoringServiceStopResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStopper
import com.example.sos_segundoplano.data.trip.InMemoryTripSessionStore
import com.example.sos_segundoplano.data.remote.incident.ManualSosRequestState
import com.example.sos_segundoplano.data.remote.incident.ManualSosSubmissionOptions
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.sos.MobileSosPriority
import com.example.sos_segundoplano.domain.sos.MobileSosSeverity
import com.example.sos_segundoplano.features.sos.RiderSosScreen
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow

class RiderSosScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun riderCanOpenManualSosFromHomeAndCancelReturnsWithoutRequestingHelp() {
        var helpRequests = 0
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(onManualSos = { helpRequests++ })
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
                MotoSosApp(onManualSos = { helpRequests++ })
            }
        }

        composeRule.onNodeWithTag("bottom_nav_sos").performClick()
        pressBackUnconditionally()

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        assertEquals(0, helpRequests)
    }

    @Test fun manualSosDefaultsToUnknownRiskAndHighPriorityWithoutExtraTap() {
        var submitted: ManualSosSubmissionOptions? = null
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                RiderSosScreen(
                    canSubmitManualSos = true,
                    onSubmitManualSos = { submitted = it },
                    onNavigateBack = {}
                )
            }
        }

        composeRule.onNodeWithTag("sos_manual_classification").assertIsDisplayed()
        composeRule.onNodeWithTag("send_sos_button").performClick()

        composeRule.runOnIdle {
            assertEquals(MobileSosSeverity.Unknown, submitted?.severity)
            assertEquals(MobileSosPriority.High, submitted?.priority)
        }
    }

    @Test fun manualSosCanSelectHighRiskAndCriticalPriority() {
        var submitted: ManualSosSubmissionOptions? = null
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                RiderSosScreen(
                    canSubmitManualSos = true,
                    onSubmitManualSos = { submitted = it },
                    onNavigateBack = {}
                )
            }
        }

        composeRule.onNodeWithTag("sos_manual_classification").performClick()
        composeRule.onNodeWithText("Alto").performClick()
        composeRule.onNodeWithText("Crítica").performClick()
        composeRule.onNodeWithText("Listo").performClick()
        composeRule.onNodeWithTag("send_sos_button").performClick()

        composeRule.runOnIdle {
            assertEquals(MobileSosSeverity.High, submitted?.severity)
            assertEquals(MobileSosPriority.Critical, submitted?.priority)
        }
    }

    @Test fun preparingManualSosIsNotPresentedAsSentAndBlocksDuplicateTap() {
        var helpRequests = 0
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                RiderSosScreen(
                    canSubmitManualSos = true,
                    onSubmitManualSos = { helpRequests++ },
                    requestState = ManualSosRequestState.WaitingForLocation,
                    onNavigateBack = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Botón visual SOS con anillos de alerta").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Regresar").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancelar SOS y volver").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Registrar alerta SOS de emergencia").assertIsDisplayed()
        composeRule.onNodeWithTag("send_sos_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("sos_request_status").assertIsDisplayed()
        composeRule.onNodeWithText("Obteniendo ubicación para enviar la alerta…").assertIsDisplayed()
        assertEquals(0, helpRequests)
    }

    @Test fun sentManualSosLeavesConfirmationToHostModalAndAllowsANewRequest() {
        var helpRequests = 0
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                RiderSosScreen(
                    canSubmitManualSos = true,
                    onSubmitManualSos = { helpRequests++ },
                    requestState = ManualSosRequestState.Sent,
                    onNavigateBack = {}
                )
            }
        }

        composeRule.onAllNodesWithText("Alerta SOS enviada.").assertCountEquals(0)
        composeRule.onNodeWithTag("send_sos_button").assertIsEnabled().performClick()
        assertEquals(1, helpRequests)
    }

    @Test fun sentManualSosDuringTripShowsConfirmationModalAndReturnsToTrip() {
        val tripStore = InMemoryTripSessionStore(TripSessionState.Active("00000000-0000-0000-0000-000000000001"))
        val requestState = MutableStateFlow<ManualSosRequestState>(ManualSosRequestState.Idle)
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    tripSessionStore = tripStore,
                    manualSosRequestStates = requestState
                )
            }
        }

        composeRule.onNodeWithTag("bottom_nav_sos").performClick()
        composeRule.runOnIdle { requestState.value = ManualSosRequestState.Sent }
        composeRule.onNodeWithText("Alerta SOS enviada").assertIsDisplayed()
        composeRule.onNodeWithText("Continuar viaje").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        assertEquals(TripSessionState.Active("00000000-0000-0000-0000-000000000001"), tripStore.states.value)
    }

    @Test fun missingLocationShowsRetryableGuidance() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                RiderSosScreen(
                    canSubmitManualSos = true,
                    onSubmitManualSos = {},
                    requestState = ManualSosRequestState.LocationUnavailable,
                    onNavigateBack = {}
                )
            }
        }

        composeRule.onNodeWithText("No fue posible obtener tu ubicación. Puedes reintentar.").assertIsDisplayed()
        composeRule.onNodeWithTag("send_sos_button").assertIsEnabled()
    }

    @Test fun manualSosDoesNotRequireValidationAndNeverUsesRequestHelp() {
        var manualRequests = 0
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    onManualSos = { manualRequests++ },
                    onRequestHelp = { _, _, _ -> error("Manual SOS must not use UserRequestedHelp") }
                )
            }
        }

        composeRule.onNodeWithTag("bottom_nav_sos").performClick()
        composeRule.onNodeWithTag("send_sos_button").performClick()

        composeRule.onNodeWithTag("rider_sos_screen").assertIsDisplayed()
        assertEquals(1, manualRequests)
    }

    @Test fun explicitlyUnavailableActionShowsMessageWithoutInvokingCallback() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                RiderSosScreen(
                    canSubmitManualSos = false,
                    onSubmitManualSos = { error("Unavailable SOS must not submit") },
                    onNavigateBack = {}
                )
            }
        }

        composeRule.onNodeWithTag("send_sos_button").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("sos_local_unavailable").assertIsDisplayed()
    }

    @Test fun cancelDuringActiveTripPreservesTripAndDoesNotStopMonitoring() {
        val tripStore = InMemoryTripSessionStore(TripSessionState.Active("00000000-0000-0000-0000-000000000001"))
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
        assertEquals(TripSessionState.Active("00000000-0000-0000-0000-000000000001"), tripStore.states.value)
        assertEquals(0, stopCalls)
    }
}
