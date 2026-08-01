package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.sos_segundoplano.MotoSosApp
import com.example.sos_segundoplano.core.background.MonitoringServiceStartResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStarter
import com.example.sos_segundoplano.core.background.MonitoringServiceStopResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStopper
import com.example.sos_segundoplano.core.permissions.AppNotificationStatus
import com.example.sos_segundoplano.core.permissions.AppNotificationStatusProvider
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatus
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatusProvider
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatus
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatusProvider
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TripStartFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startsIdleAndShowsMonitoringOnlyAfterStartTripClick() {
        var monitoringStarterCallCount = 0
        var monitoringStopperCallCount = 0
        var startTripCallCount = 0

        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    startTripUseCase = { currentState ->
                        startTripCallCount++
                        when (currentState) {
                            TripSessionState.Idle -> TripSessionState.Active
                            TripSessionState.Active -> TripSessionState.Active
                        }
                    },
                    locationPermissionStatusProvider = BackgroundLocationPermissionStatusProvider {
                        BackgroundLocationPermissionStatus.Granted
                    },
                    notificationStatusProvider = AppNotificationStatusProvider {
                        AppNotificationStatus.Enabled
                    },
                    bluetoothRequirementStatusProvider = BluetoothRequirementStatusProvider {
                        BluetoothRequirementStatus.Enabled
                    },
                    monitoringServiceStarter = MonitoringServiceStarter {
                        monitoringStarterCallCount++
                        MonitoringServiceStartResult.Started
                    },
                    monitoringServiceStopper = MonitoringServiceStopper {
                        monitoringStopperCallCount++
                        MonitoringServiceStopResult.Stopped
                    }
                )
            }
        }

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onNodeWithText("¡Listo para tu viaje!").assertIsDisplayed()
        composeRule.onNodeWithTag("start_trip_button").assertIsDisplayed()
        composeRule
            .onAllNodesWithText("Monitoreo")
            .assertCountEquals(0)
        composeRule
            .onAllNodesWithText("Viaje activo")
            .assertCountEquals(0)

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Monitoreo").assertIsDisplayed()
        composeRule.onNodeWithText("Viaje activo").assertIsDisplayed()
        composeRule.onNodeWithText("Sesión iniciada").assertIsDisplayed()
        composeRule.onNodeWithText("Monitoreo en segundo plano activo").assertIsDisplayed()
        composeRule.onAllNodesWithText("--").assertCountEquals(0)
        composeRule.onNodeWithText("Precisión GPS").assertIsDisplayed()
        composeRule.onNodeWithText("Esperando GPS").assertIsDisplayed()
        composeRule.onAllNodesWithText("Esperando señal").assertCountEquals(4)
        composeRule.onAllNodesWithText("57").assertCountEquals(0)
        composeRule.onAllNodesWithText("78%").assertCountEquals(0)
        composeRule.onAllNodesWithText("Excelente").assertCountEquals(0)
        composeRule.onAllNodesWithText("Fuerte").assertCountEquals(0)
        composeRule.onNodeWithTag("finish_trip_button").assertIsEnabled()
        assertEquals(1, monitoringStarterCallCount)
        assertEquals(0, monitoringStopperCallCount)
        assertEquals(1, startTripCallCount)
    }
}
