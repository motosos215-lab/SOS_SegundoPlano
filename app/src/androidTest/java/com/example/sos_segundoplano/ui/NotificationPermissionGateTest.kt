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
import com.example.sos_segundoplano.core.permissions.AppNotificationStatus
import com.example.sos_segundoplano.core.permissions.AppNotificationStatusProvider
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatus
import com.example.sos_segundoplano.core.permissions.BackgroundLocationPermissionStatusProvider
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NotificationPermissionGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withoutClickDoesNotCheckNotificationsOrShowDialog() {
        val notificationProvider = FakeAppNotificationStatusProvider(AppNotificationStatus.Disabled)
        var notificationSettingsOpenCount = 0

        setAppContent(
            locationProvider = FakeNotificationGateLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.Granted
            ),
            notificationProvider = notificationProvider,
            onOpenNotificationSettings = { notificationSettingsOpenCount++ }
        )

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("notification_permission_dialog").assertCountEquals(0)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(0, notificationProvider.invocationCount)
        assertEquals(0, notificationSettingsOpenCount)
    }

    @Test
    fun disabledNotificationsShowDialogAndDoNotStartTrip() {
        var startTripCallCount = 0

        setAppContent(
            locationProvider = FakeNotificationGateLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.Granted
            ),
            notificationProvider = FakeAppNotificationStatusProvider(AppNotificationStatus.Disabled),
            startTrip = { currentState ->
                startTripCallCount++
                when (currentState) {
                    TripSessionState.Idle -> TripSessionState.Active
                    TripSessionState.Active -> TripSessionState.Active
                }
            }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("notification_permission_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Notificaciones requeridas").assertIsDisplayed()
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithText("Monitoreo").assertCountEquals(0)
        assertEquals(0, startTripCallCount)
    }

    @Test
    fun openNotificationSettingsDoesNotStartTrip() {
        var notificationSettingsOpenCount = 0
        var startTripCallCount = 0

        setAppContent(
            locationProvider = FakeNotificationGateLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.Granted
            ),
            notificationProvider = FakeAppNotificationStatusProvider(AppNotificationStatus.Disabled),
            onOpenNotificationSettings = { notificationSettingsOpenCount++ },
            startTrip = { currentState ->
                startTripCallCount++
                when (currentState) {
                    TripSessionState.Idle -> TripSessionState.Active
                    TripSessionState.Active -> TripSessionState.Active
                }
            }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("open_notification_settings_button").performClick()

        assertEquals(1, notificationSettingsOpenCount)
        assertEquals(0, startTripCallCount)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun dismissNotificationDialogKeepsHomeVisible() {
        setAppContent(
            locationProvider = FakeNotificationGateLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.Granted
            ),
            notificationProvider = FakeAppNotificationStatusProvider(AppNotificationStatus.Disabled)
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("dismiss_notification_permission_button").performClick()

        composeRule.onAllNodesWithTag("notification_permission_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun recheckStillDisabledKeepsNotificationDialogVisible() {
        setAppContent(
            locationProvider = FakeNotificationGateLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.Granted
            ),
            notificationProvider = FakeAppNotificationStatusProvider(AppNotificationStatus.Disabled)
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("recheck_notification_permission_button").performClick()

        composeRule.onNodeWithTag("notification_permission_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun recheckEnabledClosesDialogAndShowsMonitoring() {
        val notificationProvider = FakeAppNotificationStatusProvider(AppNotificationStatus.Disabled)
        var startTripCallCount = 0

        setAppContent(
            locationProvider = FakeNotificationGateLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.Granted
            ),
            notificationProvider = notificationProvider,
            startTrip = { currentState ->
                startTripCallCount++
                when (currentState) {
                    TripSessionState.Idle -> TripSessionState.Active
                    TripSessionState.Active -> TripSessionState.Active
                }
            }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("notification_permission_dialog").assertIsDisplayed()

        notificationProvider.updateStatus(AppNotificationStatus.Enabled)
        composeRule.onNodeWithTag("recheck_notification_permission_button").performClick()

        composeRule.onAllNodesWithTag("notification_permission_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        assertEquals(1, startTripCallCount)
    }

    @Test
    fun missingLocationTakesPrecedenceAndDoesNotCheckNotifications() {
        val notificationProvider = FakeAppNotificationStatusProvider(AppNotificationStatus.Disabled)

        setAppContent(
            locationProvider = FakeNotificationGateLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.BackgroundMissing
            ),
            notificationProvider = notificationProvider
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("location_permission_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("notification_permission_dialog").assertCountEquals(0)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(0, notificationProvider.invocationCount)
    }

    @Test
    fun locationGrantedDuringRecheckSwitchesToNotificationDialog() {
        val locationProvider = FakeNotificationGateLocationPermissionStatusProvider(
            BackgroundLocationPermissionStatus.BackgroundMissing
        )

        setAppContent(
            locationProvider = locationProvider,
            notificationProvider = FakeAppNotificationStatusProvider(AppNotificationStatus.Disabled)
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("location_permission_dialog").assertIsDisplayed()

        locationProvider.updateStatus(BackgroundLocationPermissionStatus.Granted)
        composeRule.onNodeWithTag("recheck_location_permissions_button").performClick()

        composeRule.onAllNodesWithTag("location_permission_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("notification_permission_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun revokedLocationDuringRecheckSwitchesToLocationDialog() {
        val locationProvider = FakeNotificationGateLocationPermissionStatusProvider(
            BackgroundLocationPermissionStatus.Granted
        )

        setAppContent(
            locationProvider = locationProvider,
            notificationProvider = FakeAppNotificationStatusProvider(AppNotificationStatus.Disabled)
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("notification_permission_dialog").assertIsDisplayed()

        locationProvider.updateStatus(BackgroundLocationPermissionStatus.BackgroundMissing)
        composeRule.onNodeWithTag("recheck_notification_permission_button").performClick()

        composeRule.onAllNodesWithTag("notification_permission_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("location_permission_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun bothRequirementsEnabledShowsMonitoringDirectlyAndStartsOnce() {
        var startTripCallCount = 0

        setAppContent(
            locationProvider = FakeNotificationGateLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.Granted
            ),
            notificationProvider = FakeAppNotificationStatusProvider(AppNotificationStatus.Enabled),
            startTrip = { currentState ->
                startTripCallCount++
                when (currentState) {
                    TripSessionState.Idle -> TripSessionState.Active
                    TripSessionState.Active -> TripSessionState.Active
                }
            }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onAllNodesWithTag("location_permission_dialog").assertCountEquals(0)
        composeRule.onAllNodesWithTag("notification_permission_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        assertEquals(1, startTripCallCount)
    }

    private fun setAppContent(
        locationProvider: BackgroundLocationPermissionStatusProvider,
        notificationProvider: AppNotificationStatusProvider,
        onOpenNotificationSettings: () -> Unit = {},
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
                    locationPermissionStatusProvider = locationProvider,
                    notificationStatusProvider = notificationProvider,
                    onOpenNotificationSettings = onOpenNotificationSettings
                )
            }
        }
    }
}

private class FakeNotificationGateLocationPermissionStatusProvider(
    initialStatus: BackgroundLocationPermissionStatus
) : BackgroundLocationPermissionStatusProvider {
    private var currentStatus: BackgroundLocationPermissionStatus = initialStatus

    override fun getStatus(): BackgroundLocationPermissionStatus = currentStatus

    fun updateStatus(newStatus: BackgroundLocationPermissionStatus) {
        currentStatus = newStatus
    }
}

private class FakeAppNotificationStatusProvider(
    initialStatus: AppNotificationStatus
) : AppNotificationStatusProvider {
    private var currentStatus = initialStatus

    var invocationCount: Int = 0
        private set

    override fun getStatus(): AppNotificationStatus {
        invocationCount++
        return currentStatus
    }

    fun updateStatus(newStatus: AppNotificationStatus) {
        currentStatus = newStatus
    }
}
