package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.sos_segundoplano.MotoSosApp
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatus
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatusProvider
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BackgroundLocationPermissionGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withoutClickDoesNotShowDialogOrMonitoring() {
        var settingsOpenCount = 0

        setAppContent(
            provider = FakeBackgroundLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.ForegroundMissing
            ),
            onOpenAppSettings = { settingsOpenCount++ }
        )

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("location_permission_dialog").assertCountEquals(0)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(0, settingsOpenCount)
    }

    @Test
    fun foregroundMissingShowsDialogAndDismissKeepsHomeVisible() {
        setAppContent(
            provider = FakeBackgroundLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.ForegroundMissing
            )
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("location_permission_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Permiso de ubicación requerido").assertIsDisplayed()
        composeRule.onAllNodesWithText("Monitoreo").assertCountEquals(0)
        composeRule.onAllNodesWithText("Viaje activo").assertCountEquals(0)

        composeRule.onNodeWithTag("dismiss_location_permission_button").performClick()

        composeRule.onAllNodesWithTag("location_permission_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
    }

    @Test
    fun backgroundMissingOpenSettingsDoesNotStartTrip() {
        var settingsOpenCount = 0
        setAppContent(
            provider = FakeBackgroundLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.BackgroundMissing
            ),
            onOpenAppSettings = { settingsOpenCount++ }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithText("Ubicación en segundo plano requerida").assertIsDisplayed()
        composeRule.onAllNodesWithText("Monitoreo").assertCountEquals(0)

        composeRule.onNodeWithTag("open_location_settings_button").performClick()

        assertEquals(1, settingsOpenCount)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun recheckStillDeniedKeepsDialogVisible() {
        setAppContent(
            provider = FakeBackgroundLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.BackgroundMissing
            )
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("recheck_location_permissions_button").performClick()

        composeRule.onNodeWithTag("location_permission_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun recheckGrantedClosesDialogAndShowsMonitoring() {
        val provider = FakeBackgroundLocationPermissionStatusProvider(
            BackgroundLocationPermissionStatus.BackgroundMissing
        )
        var startTripCallCount = 0

        setAppContent(
            provider = provider,
            startTrip = { currentState ->
                startTripCallCount++
                when (currentState) {
                    TripSessionState.Idle -> TripSessionState.Active
                    TripSessionState.Active -> TripSessionState.Active
                }
            }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("location_permission_dialog").assertIsDisplayed()

        provider.updateStatus(BackgroundLocationPermissionStatus.Granted)
        composeRule.onNodeWithTag("recheck_location_permissions_button").performClick()

        composeRule.onAllNodesWithTag("location_permission_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        assertEquals(1, startTripCallCount)
    }

    @Test
    fun grantedFromStartShowsMonitoringDirectly() {
        setAppContent(
            provider = FakeBackgroundLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.Granted
            )
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onAllNodesWithTag("location_permission_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
    }

    private fun setAppContent(
        provider: BackgroundLocationPermissionStatusProvider,
        onOpenAppSettings: () -> Unit = {},
        startTrip: (TripSessionState) -> TripSessionState = { currentState ->
            when (currentState) {
                TripSessionState.Idle -> TripSessionState.Active
                TripSessionState.Active -> TripSessionState.Active
            }
        }
    ) {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    startTripUseCase = startTrip,
                    locationPermissionStatusProvider = provider,
                    onOpenAppSettings = onOpenAppSettings
                )
            }
        }
    }
}

private class FakeBackgroundLocationPermissionStatusProvider(
    initialStatus: BackgroundLocationPermissionStatus
) : BackgroundLocationPermissionStatusProvider {
    private var currentStatus: BackgroundLocationPermissionStatus = initialStatus

    override fun getStatus(): BackgroundLocationPermissionStatus = currentStatus

    fun updateStatus(newStatus: BackgroundLocationPermissionStatus) {
        currentStatus = newStatus
    }
}
