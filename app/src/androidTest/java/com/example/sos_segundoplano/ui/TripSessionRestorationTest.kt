package com.example.sos_segundoplano.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.example.sos_segundoplano.data.trip.InMemoryTripSessionStore
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TripSessionRestorationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun idleSessionOpensHome() {
        setAppContent(store = InMemoryTripSessionStore(TripSessionState.Idle))

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test fun activeSessionOpensMonitoringWithoutStartClick() {
        setAppContent(store = InMemoryTripSessionStore(TripSessionState.Active))

        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("home_screen").assertCountEquals(0)
    }

    @Test fun activeSessionSurvivesNewCompositionWithSameStore() {
        val store = InMemoryTripSessionStore(TripSessionState.Idle)
        val starter = FakeRestorationMonitoringServiceStarter()
        var compositionKey by mutableStateOf(0)

        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                key(compositionKey) {
                    MotoSosApp(
                        tripSessionStore = store,
                        locationPermissionStatusProvider = BackgroundLocationPermissionStatusProvider { BackgroundLocationPermissionStatus.Granted },
                        notificationStatusProvider = AppNotificationStatusProvider { AppNotificationStatus.Enabled },
                        bluetoothRequirementStatusProvider = BluetoothRequirementStatusProvider { BluetoothRequirementStatus.Enabled },
                        monitoringServiceStarter = starter,
                        monitoringServiceStopper = FakeRestorationMonitoringServiceStopper(MonitoringServiceStopResult.Stopped)
                    )
                }
            }
        }

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        assertEquals(TripSessionState.Active, store.states.value)

        composeRule.runOnIdle { compositionKey++ }

        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("home_screen").assertCountEquals(0)
        assertEquals(1, starter.invocationCount)
    }

    @Test fun successfulFinishMarksIdleAndReturnsHome() {
        val store = InMemoryTripSessionStore(TripSessionState.Active)
        setAppContent(
            store = store,
            stopper = FakeRestorationMonitoringServiceStopper(MonitoringServiceStopResult.Stopped)
        )

        composeRule.onNodeWithTag("finish_trip_button").performScrollTo().performClick()

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(TripSessionState.Idle, store.states.value)
    }

    @Test fun alreadyStoppedFinishMarksIdleAndReturnsHome() {
        val store = InMemoryTripSessionStore(TripSessionState.Active)
        setAppContent(
            store = store,
            stopper = FakeRestorationMonitoringServiceStopper(MonitoringServiceStopResult.AlreadyStopped)
        )

        composeRule.onNodeWithTag("finish_trip_button").performScrollTo().performClick()

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(TripSessionState.Idle, store.states.value)
    }

    @Test fun failedFinishKeepsActiveMonitoring() {
        val store = InMemoryTripSessionStore(TripSessionState.Active)
        setAppContent(
            store = store,
            stopper = FakeRestorationMonitoringServiceStopper(MonitoringServiceStopResult.Failed)
        )

        composeRule.onNodeWithTag("finish_trip_button").performScrollTo().performClick()

        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("home_screen").assertCountEquals(0)
        assertEquals(TripSessionState.Active, store.states.value)
    }

    private fun setAppContent(
        store: InMemoryTripSessionStore,
        stopper: MonitoringServiceStopper = FakeRestorationMonitoringServiceStopper(MonitoringServiceStopResult.Stopped)
    ) {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    tripSessionStore = store,
                    locationPermissionStatusProvider = BackgroundLocationPermissionStatusProvider { BackgroundLocationPermissionStatus.Granted },
                    notificationStatusProvider = AppNotificationStatusProvider { AppNotificationStatus.Enabled },
                    bluetoothRequirementStatusProvider = BluetoothRequirementStatusProvider { BluetoothRequirementStatus.Enabled },
                    monitoringServiceStarter = FakeRestorationMonitoringServiceStarter(),
                    monitoringServiceStopper = stopper
                )
            }
        }
    }
}

private class FakeRestorationMonitoringServiceStarter : MonitoringServiceStarter {
    var invocationCount = 0
        private set

    override fun start(): MonitoringServiceStartResult {
        invocationCount++
        return MonitoringServiceStartResult.Started
    }
}

private class FakeRestorationMonitoringServiceStopper(
    private val result: MonitoringServiceStopResult
) : MonitoringServiceStopper {
    override fun stop(): MonitoringServiceStopResult = result
}
