package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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

class TripFinishFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun beforeStartFinishTripButtonDoesNotExistAndStopperIsNotInvoked() {
        val stopper = FakeMonitoringServiceStopper(MonitoringServiceStopResult.Stopped)

        setAppContent(monitoringServiceStopper = stopper)

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("finish_trip_button").assertCountEquals(0)
        assertEquals(0, stopper.invocationCount)
    }

    @Test
    fun activeTripShowsEnabledFinishButtonWithoutInvokingStopper() {
        val starter = FakeTripFinishMonitoringServiceStarter(MonitoringServiceStartResult.Started)
        val stopper = FakeMonitoringServiceStopper(MonitoringServiceStopResult.Stopped)

        setAppContent(
            monitoringServiceStarter = starter,
            monitoringServiceStopper = stopper
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("finish_trip_button").assertIsEnabled()
        assertEquals(1, starter.invocationCount)
        assertEquals(0, stopper.invocationCount)
    }

    @Test
    fun successfulStopReturnsToHomeAndHidesFailureDialog() {
        val stopper = FakeMonitoringServiceStopper(MonitoringServiceStopResult.Stopped)
        var finishTripCallCount = 0

        setAppContent(
            monitoringServiceStopper = stopper,
            finishTrip = { currentState ->
                finishTripCallCount++
                when (currentState) {
                    TripSessionState.Active -> TripSessionState.Idle
                    TripSessionState.Idle -> TripSessionState.Idle
                }
            }
        )

        startAndFinishTrip()

        assertEquals(1, stopper.invocationCount)
        assertEquals(1, finishTripCallCount)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_stop_failure_dialog").assertCountEquals(0)
    }

    @Test
    fun alreadyStoppedReturnsToHomeWithoutFailureDialog() {
        val stopper = FakeMonitoringServiceStopper(MonitoringServiceStopResult.AlreadyStopped)

        setAppContent(monitoringServiceStopper = stopper)

        startAndFinishTrip()

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_stop_failure_dialog").assertCountEquals(0)
        assertEquals(1, stopper.invocationCount)
    }

    @Test
    fun failedStopKeepsActiveAndShowsFailureDialog() {
        val stopper = FakeMonitoringServiceStopper(MonitoringServiceStopResult.Failed)
        var finishTripCallCount = 0

        setAppContent(
            monitoringServiceStopper = stopper,
            finishTrip = { currentState ->
                finishTripCallCount++
                when (currentState) {
                    TripSessionState.Active -> TripSessionState.Idle
                    TripSessionState.Idle -> TripSessionState.Idle
                }
            }
        )

        startAndFinishTrip()

        assertEquals(1, stopper.invocationCount)
        assertEquals(0, finishTripCallCount)
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("home_screen").assertCountEquals(0)
        composeRule.onNodeWithTag("monitoring_stop_failure_dialog").assertIsDisplayed()
    }

    @Test
    fun successfulRetryClosesDialogAndReturnsToHome() {
        val stopper = FakeMonitoringServiceStopper(MonitoringServiceStopResult.Failed)

        setAppContent(monitoringServiceStopper = stopper)

        startAndFinishTrip()
        stopper.updateResult(MonitoringServiceStopResult.Stopped)
        composeRule.onNodeWithTag("retry_monitoring_stop_button").performClick()

        assertEquals(2, stopper.invocationCount)
        composeRule.onAllNodesWithTag("monitoring_stop_failure_dialog").assertCountEquals(0)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
    }

    @Test
    fun failedRetryKeepsDialogAndMonitoring() {
        val stopper = FakeMonitoringServiceStopper(MonitoringServiceStopResult.Failed)

        setAppContent(monitoringServiceStopper = stopper)

        startAndFinishTrip()
        composeRule.onNodeWithTag("retry_monitoring_stop_button").performClick()

        assertEquals(2, stopper.invocationCount)
        composeRule.onNodeWithTag("monitoring_stop_failure_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
    }

    @Test
    fun continueTripDismissesDialogWithoutStoppingAgain() {
        val stopper = FakeMonitoringServiceStopper(MonitoringServiceStopResult.Failed)

        setAppContent(monitoringServiceStopper = stopper)
        startAndFinishTrip()
        composeRule.onNodeWithTag("dismiss_monitoring_stop_failure_button").performClick()

        composeRule.onAllNodesWithTag("monitoring_stop_failure_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        assertEquals(1, stopper.invocationCount)
    }

    @Test
    fun canStartNewTripAfterSuccessfulFinish() {
        val starter = FakeTripFinishMonitoringServiceStarter(MonitoringServiceStartResult.Started)
        val stopper = FakeMonitoringServiceStopper(MonitoringServiceStopResult.Stopped)

        setAppContent(
            monitoringServiceStarter = starter,
            monitoringServiceStopper = stopper
        )

        startAndFinishTrip()
        composeRule.onNodeWithTag("start_trip_button").performClick()

        assertEquals(2, starter.invocationCount)
        assertEquals(1, stopper.invocationCount)
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
    }

    @Test
    fun revokedRequirementsAfterActiveDoNotBlockFinish() {
        val locationProvider = FakeTripFinishLocationPermissionStatusProvider(
            BackgroundLocationPermissionStatus.Granted
        )
        val notificationProvider = FakeTripFinishNotificationStatusProvider(AppNotificationStatus.Enabled)
        val bluetoothProvider = FakeTripFinishBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Enabled)
        val stopper = FakeMonitoringServiceStopper(MonitoringServiceStopResult.Stopped)

        setAppContent(
            locationProvider = locationProvider,
            notificationProvider = notificationProvider,
            bluetoothProvider = bluetoothProvider,
            monitoringServiceStopper = stopper
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        assertEquals(1, locationProvider.invocationCount)
        assertEquals(1, notificationProvider.invocationCount)
        assertEquals(1, bluetoothProvider.invocationCount)

        locationProvider.updateStatus(BackgroundLocationPermissionStatus.BackgroundMissing)
        notificationProvider.updateStatus(AppNotificationStatus.Disabled)
        bluetoothProvider.updateStatus(BluetoothRequirementStatus.Disabled)
        composeRule.onNodeWithTag("finish_trip_button").performClick()

        assertEquals(1, locationProvider.invocationCount)
        assertEquals(1, notificationProvider.invocationCount)
        assertEquals(1, bluetoothProvider.invocationCount)
        assertEquals(1, stopper.invocationCount)
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
    }

    private fun startAndFinishTrip() {
        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("finish_trip_button").performClick()
    }

    private fun setAppContent(
        locationProvider: BackgroundLocationPermissionStatusProvider =
            FakeTripFinishLocationPermissionStatusProvider(BackgroundLocationPermissionStatus.Granted),
        notificationProvider: AppNotificationStatusProvider =
            FakeTripFinishNotificationStatusProvider(AppNotificationStatus.Enabled),
        bluetoothProvider: BluetoothRequirementStatusProvider =
            FakeTripFinishBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Enabled),
        monitoringServiceStarter: MonitoringServiceStarter =
            FakeTripFinishMonitoringServiceStarter(MonitoringServiceStartResult.Started),
        monitoringServiceStopper: MonitoringServiceStopper,
        finishTrip: (TripSessionState) -> TripSessionState = { currentState ->
            when (currentState) {
                TripSessionState.Active -> TripSessionState.Idle
                TripSessionState.Idle -> TripSessionState.Idle
            }
        }
    ) {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    locationPermissionStatusProvider = locationProvider,
                    notificationStatusProvider = notificationProvider,
                    bluetoothRequirementStatusProvider = bluetoothProvider,
                    monitoringServiceStarter = monitoringServiceStarter,
                    monitoringServiceStopper = monitoringServiceStopper,
                    finishTripUseCase = finishTrip
                )
            }
        }
    }
}

private class FakeTripFinishLocationPermissionStatusProvider(
    initialStatus: BackgroundLocationPermissionStatus
) : BackgroundLocationPermissionStatusProvider {
    private var currentStatus = initialStatus

    var invocationCount: Int = 0
        private set

    override fun getStatus(): BackgroundLocationPermissionStatus {
        invocationCount++
        return currentStatus
    }

    fun updateStatus(newStatus: BackgroundLocationPermissionStatus) {
        currentStatus = newStatus
    }
}

private class FakeTripFinishNotificationStatusProvider(
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

private class FakeTripFinishBluetoothRequirementStatusProvider(
    initialStatus: BluetoothRequirementStatus
) : BluetoothRequirementStatusProvider {
    private var currentStatus = initialStatus

    var invocationCount: Int = 0
        private set

    override fun getStatus(): BluetoothRequirementStatus {
        invocationCount++
        return currentStatus
    }

    fun updateStatus(newStatus: BluetoothRequirementStatus) {
        currentStatus = newStatus
    }
}

private class FakeTripFinishMonitoringServiceStarter(
    initialResult: MonitoringServiceStartResult
) : MonitoringServiceStarter {
    private var currentResult = initialResult

    var invocationCount: Int = 0
        private set

    override fun start(): MonitoringServiceStartResult {
        invocationCount++
        return currentResult
    }
}

private class FakeMonitoringServiceStopper(
    initialResult: MonitoringServiceStopResult
) : MonitoringServiceStopper {
    private var currentResult = initialResult

    var invocationCount: Int = 0
        private set

    override fun stop(): MonitoringServiceStopResult {
        invocationCount++
        return currentResult
    }

    fun updateResult(newResult: MonitoringServiceStopResult) {
        currentResult = newResult
    }
}
