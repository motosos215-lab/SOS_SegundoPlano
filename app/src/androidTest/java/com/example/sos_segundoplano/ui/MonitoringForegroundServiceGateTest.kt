package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.sos_segundoplano.MotoSosApp
import com.example.sos_segundoplano.core.background.MonitoringServiceStartResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStarter
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

class MonitoringForegroundServiceGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withoutClickDoesNotStartMonitoringService() {
        val starter = FakeMonitoringServiceStarter(MonitoringServiceStartResult.Started)

        setAppContent(monitoringServiceStarter = starter)

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_start_failure_dialog").assertCountEquals(0)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(0, starter.invocationCount)
    }

    @Test
    fun missingLocationDoesNotStartMonitoringService() {
        val starter = FakeMonitoringServiceStarter(MonitoringServiceStartResult.Started)

        setAppContent(
            locationProvider = FakeForegroundLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.BackgroundMissing
            ),
            monitoringServiceStarter = starter
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("location_permission_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(0, starter.invocationCount)
    }

    @Test
    fun disabledNotificationsDoNotStartMonitoringService() {
        val starter = FakeMonitoringServiceStarter(MonitoringServiceStartResult.Started)

        setAppContent(
            notificationProvider = FakeForegroundNotificationStatusProvider(AppNotificationStatus.Disabled),
            monitoringServiceStarter = starter
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("notification_permission_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(0, starter.invocationCount)
    }

    @Test
    fun disabledBluetoothDoesNotStartMonitoringService() {
        val starter = FakeMonitoringServiceStarter(MonitoringServiceStartResult.Started)

        setAppContent(
            bluetoothProvider = FakeForegroundBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Disabled),
            monitoringServiceStarter = starter
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("bluetooth_requirement_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(0, starter.invocationCount)
    }

    @Test
    fun successfulStartShowsMonitoringAndStartsOnce() {
        val starter = FakeMonitoringServiceStarter(MonitoringServiceStartResult.Started)
        var startTripCallCount = 0

        setAppContent(
            monitoringServiceStarter = starter,
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
        composeRule.onAllNodesWithTag("bluetooth_requirement_dialog").assertCountEquals(0)
        composeRule.onAllNodesWithTag("monitoring_start_failure_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Monitoreo en segundo plano activo").assertIsDisplayed()
        assertEquals(1, starter.invocationCount)
        assertEquals(1, startTripCallCount)
    }

    @Test
    fun failedStartShowsFailureDialogAndKeepsIdle() {
        val starter = FakeMonitoringServiceStarter(MonitoringServiceStartResult.Failed)
        var startTripCallCount = 0

        setAppContent(
            monitoringServiceStarter = starter,
            startTrip = { currentState ->
                startTripCallCount++
                when (currentState) {
                    TripSessionState.Idle -> TripSessionState.Active
                    TripSessionState.Active -> TripSessionState.Active
                }
            }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("monitoring_start_failure_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(1, starter.invocationCount)
        assertEquals(0, startTripCallCount)
    }

    @Test
    fun openNotificationsFromFailureDoesNotStartTrip() {
        val starter = FakeMonitoringServiceStarter(MonitoringServiceStartResult.Failed)
        var notificationSettingsOpenCount = 0
        var startTripCallCount = 0

        setAppContent(
            monitoringServiceStarter = starter,
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
        composeRule.onNodeWithTag("open_monitoring_notification_settings_button").performClick()

        assertEquals(1, notificationSettingsOpenCount)
        assertEquals(0, startTripCallCount)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun retryFailedKeepsFailureDialog() {
        val starter = FakeMonitoringServiceStarter(MonitoringServiceStartResult.Failed)
        var startTripCallCount = 0

        setAppContent(
            monitoringServiceStarter = starter,
            startTrip = { currentState ->
                startTripCallCount++
                when (currentState) {
                    TripSessionState.Idle -> TripSessionState.Active
                    TripSessionState.Active -> TripSessionState.Active
                }
            }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("retry_monitoring_start_button").performClick()

        composeRule.onNodeWithTag("monitoring_start_failure_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(2, starter.invocationCount)
        assertEquals(0, startTripCallCount)
    }

    @Test
    fun retrySuccessfulStartsMonitoringOnce() {
        val starter = FakeMonitoringServiceStarter(MonitoringServiceStartResult.Failed)
        var startTripCallCount = 0

        setAppContent(
            monitoringServiceStarter = starter,
            startTrip = { currentState ->
                startTripCallCount++
                when (currentState) {
                    TripSessionState.Idle -> TripSessionState.Active
                    TripSessionState.Active -> TripSessionState.Active
                }
            }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        starter.updateResult(MonitoringServiceStartResult.Started)
        composeRule.onNodeWithTag("retry_monitoring_start_button").performClick()

        composeRule.onAllNodesWithTag("monitoring_start_failure_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        assertEquals(2, starter.invocationCount)
        assertEquals(1, startTripCallCount)
    }

    @Test
    fun locationRevokedDuringRetryShowsLocationDialog() {
        val locationProvider = FakeForegroundLocationPermissionStatusProvider(
            BackgroundLocationPermissionStatus.Granted
        )
        val starter = FakeMonitoringServiceStarter(MonitoringServiceStartResult.Failed)

        setAppContent(
            locationProvider = locationProvider,
            monitoringServiceStarter = starter
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        locationProvider.updateStatus(BackgroundLocationPermissionStatus.BackgroundMissing)
        composeRule.onNodeWithTag("retry_monitoring_start_button").performClick()

        composeRule.onAllNodesWithTag("monitoring_start_failure_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("location_permission_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(1, starter.invocationCount)
    }

    @Test
    fun bluetoothRevokedDuringRetryShowsBluetoothDialog() {
        val bluetoothProvider = FakeForegroundBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Enabled)
        val starter = FakeMonitoringServiceStarter(MonitoringServiceStartResult.Failed)

        setAppContent(
            bluetoothProvider = bluetoothProvider,
            monitoringServiceStarter = starter
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        bluetoothProvider.updateStatus(BluetoothRequirementStatus.Disabled)
        composeRule.onNodeWithTag("retry_monitoring_start_button").performClick()

        composeRule.onAllNodesWithTag("monitoring_start_failure_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("bluetooth_requirement_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(1, starter.invocationCount)
    }

    @Test
    fun dismissFailureDialogCancelsPendingAttempt() {
        val starter = FakeMonitoringServiceStarter(MonitoringServiceStartResult.Failed)

        setAppContent(monitoringServiceStarter = starter)

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("dismiss_monitoring_start_failure_button").performClick()

        composeRule.onAllNodesWithTag("monitoring_start_failure_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    private fun setAppContent(
        locationProvider: BackgroundLocationPermissionStatusProvider =
            FakeForegroundLocationPermissionStatusProvider(BackgroundLocationPermissionStatus.Granted),
        notificationProvider: AppNotificationStatusProvider =
            FakeForegroundNotificationStatusProvider(AppNotificationStatus.Enabled),
        bluetoothProvider: BluetoothRequirementStatusProvider =
            FakeForegroundBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Enabled),
        monitoringServiceStarter: MonitoringServiceStarter,
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
                    bluetoothRequirementStatusProvider = bluetoothProvider,
                    monitoringServiceStarter = monitoringServiceStarter,
                    onOpenNotificationSettings = onOpenNotificationSettings
                )
            }
        }
    }
}

private class FakeForegroundLocationPermissionStatusProvider(
    initialStatus: BackgroundLocationPermissionStatus
) : BackgroundLocationPermissionStatusProvider {
    private var currentStatus: BackgroundLocationPermissionStatus = initialStatus

    override fun getStatus(): BackgroundLocationPermissionStatus = currentStatus

    fun updateStatus(newStatus: BackgroundLocationPermissionStatus) {
        currentStatus = newStatus
    }
}

private class FakeForegroundNotificationStatusProvider(
    initialStatus: AppNotificationStatus
) : AppNotificationStatusProvider {
    private var currentStatus = initialStatus

    override fun getStatus(): AppNotificationStatus = currentStatus
}

private class FakeForegroundBluetoothRequirementStatusProvider(
    initialStatus: BluetoothRequirementStatus
) : BluetoothRequirementStatusProvider {
    private var currentStatus = initialStatus

    override fun getStatus(): BluetoothRequirementStatus = currentStatus

    fun updateStatus(newStatus: BluetoothRequirementStatus) {
        currentStatus = newStatus
    }
}

private class FakeMonitoringServiceStarter(
    initialResult: MonitoringServiceStartResult
) : MonitoringServiceStarter {
    private var currentResult = initialResult

    var invocationCount: Int = 0
        private set

    override fun start(): MonitoringServiceStartResult {
        invocationCount++
        return currentResult
    }

    fun updateResult(newResult: MonitoringServiceStartResult) {
        currentResult = newResult
    }
}
