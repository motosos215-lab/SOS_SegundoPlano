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
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatus
import com.example.sos_segundoplano.core.permissions.BluetoothRequirementStatusProvider
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BluetoothRequirementGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withoutClickDoesNotCheckBluetoothOrShowDialog() {
        val bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Disabled)
        var appSettingsOpenCount = 0
        var bluetoothSettingsOpenCount = 0

        setAppContent(
            locationProvider = FakeBluetoothGateLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.Granted
            ),
            notificationProvider = FakeBluetoothGateNotificationStatusProvider(AppNotificationStatus.Enabled),
            bluetoothProvider = bluetoothProvider,
            onOpenAppSettings = { appSettingsOpenCount++ },
            onOpenBluetoothSettings = { bluetoothSettingsOpenCount++ }
        )

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("bluetooth_requirement_dialog").assertCountEquals(0)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(0, bluetoothProvider.invocationCount)
        assertEquals(0, appSettingsOpenCount)
        assertEquals(0, bluetoothSettingsOpenCount)
    }

    @Test
    fun missingLocationTakesPrecedenceAndDoesNotCheckBluetooth() {
        val bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Disabled)

        setAppContent(
            locationProvider = FakeBluetoothGateLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.BackgroundMissing
            ),
            notificationProvider = FakeBluetoothGateNotificationStatusProvider(AppNotificationStatus.Enabled),
            bluetoothProvider = bluetoothProvider
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("location_permission_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("bluetooth_requirement_dialog").assertCountEquals(0)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(0, bluetoothProvider.invocationCount)
    }

    @Test
    fun disabledNotificationsTakePrecedenceAndDoNotCheckBluetooth() {
        val bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Disabled)

        setAppContent(
            locationProvider = FakeBluetoothGateLocationPermissionStatusProvider(
                BackgroundLocationPermissionStatus.Granted
            ),
            notificationProvider = FakeBluetoothGateNotificationStatusProvider(AppNotificationStatus.Disabled),
            bluetoothProvider = bluetoothProvider
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("notification_permission_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("bluetooth_requirement_dialog").assertCountEquals(0)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(0, bluetoothProvider.invocationCount)
    }

    @Test
    fun missingBluetoothPermissionShowsDialogAndDoesNotStartTrip() {
        var startTripCallCount = 0

        setAppContent(
            bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.PermissionMissing),
            startTrip = { currentState ->
                startTripCallCount++
                when (currentState) {
                    TripSessionState.Idle -> TripSessionState.Active
                    TripSessionState.Active -> TripSessionState.Active
                }
            }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("bluetooth_requirement_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Permiso de Bluetooth requerido").assertIsDisplayed()
        composeRule.onNodeWithTag("open_bluetooth_permission_settings_button").assertIsDisplayed()
        composeRule.onAllNodesWithTag("open_bluetooth_settings_button").assertCountEquals(0)
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(0, startTripCallCount)
    }

    @Test
    fun openMotoSosSettingsDoesNotStartTrip() {
        var appSettingsOpenCount = 0
        var startTripCallCount = 0

        setAppContent(
            bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.PermissionMissing),
            onOpenAppSettings = { appSettingsOpenCount++ },
            startTrip = { currentState ->
                startTripCallCount++
                when (currentState) {
                    TripSessionState.Idle -> TripSessionState.Active
                    TripSessionState.Active -> TripSessionState.Active
                }
            }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("open_bluetooth_permission_settings_button").performClick()

        assertEquals(1, appSettingsOpenCount)
        assertEquals(0, startTripCallCount)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun disabledBluetoothShowsDialogAndDoesNotStartTrip() {
        var startTripCallCount = 0

        setAppContent(
            bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Disabled),
            startTrip = { currentState ->
                startTripCallCount++
                when (currentState) {
                    TripSessionState.Idle -> TripSessionState.Active
                    TripSessionState.Active -> TripSessionState.Active
                }
            }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithText("Bluetooth desactivado").assertIsDisplayed()
        composeRule.onNodeWithTag("open_bluetooth_settings_button").assertIsDisplayed()
        composeRule.onAllNodesWithTag("open_bluetooth_permission_settings_button").assertCountEquals(0)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(0, startTripCallCount)
    }

    @Test
    fun openBluetoothSettingsDoesNotStartTrip() {
        var bluetoothSettingsOpenCount = 0

        setAppContent(
            bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Disabled),
            onOpenBluetoothSettings = { bluetoothSettingsOpenCount++ }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("open_bluetooth_settings_button").performClick()

        assertEquals(1, bluetoothSettingsOpenCount)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun unsupportedBluetoothShowsNoSettingsButtons() {
        setAppContent(
            bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Unsupported)
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithText("Bluetooth no disponible").assertIsDisplayed()
        composeRule.onAllNodesWithTag("open_bluetooth_permission_settings_button").assertCountEquals(0)
        composeRule.onAllNodesWithTag("open_bluetooth_settings_button").assertCountEquals(0)
        composeRule.onNodeWithText("Revisar requisitos").assertIsDisplayed()
        composeRule.onNodeWithText("Ahora no").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun dismissBluetoothDialogKeepsHomeVisible() {
        setAppContent(
            bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Disabled)
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("dismiss_bluetooth_requirement_button").performClick()

        composeRule.onAllNodesWithTag("bluetooth_requirement_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun recheckStillDisabledKeepsBluetoothDialogVisible() {
        setAppContent(
            bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Disabled)
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("recheck_bluetooth_requirement_button").performClick()

        composeRule.onNodeWithTag("bluetooth_requirement_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun recheckEnabledClosesDialogAndShowsMonitoring() {
        val bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Disabled)
        var startTripCallCount = 0

        setAppContent(
            bluetoothProvider = bluetoothProvider,
            startTrip = { currentState ->
                startTripCallCount++
                when (currentState) {
                    TripSessionState.Idle -> TripSessionState.Active
                    TripSessionState.Active -> TripSessionState.Active
                }
            }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        bluetoothProvider.updateStatus(BluetoothRequirementStatus.Enabled)
        composeRule.onNodeWithTag("recheck_bluetooth_requirement_button").performClick()

        composeRule.onAllNodesWithTag("bluetooth_requirement_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        assertEquals(1, startTripCallCount)
    }

    @Test
    fun permissionGrantedButBluetoothDisabledChangesDialog() {
        val bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.PermissionMissing)

        setAppContent(bluetoothProvider = bluetoothProvider)

        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithText("Permiso de Bluetooth requerido").assertIsDisplayed()

        bluetoothProvider.updateStatus(BluetoothRequirementStatus.Disabled)
        composeRule.onNodeWithTag("recheck_bluetooth_requirement_button").performClick()

        composeRule.onNodeWithText("Bluetooth desactivado").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun notificationsRevokedDuringRecheckSwitchesToNotificationDialog() {
        val notificationProvider = FakeBluetoothGateNotificationStatusProvider(AppNotificationStatus.Enabled)

        setAppContent(
            notificationProvider = notificationProvider,
            bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Disabled)
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        notificationProvider.updateStatus(AppNotificationStatus.Disabled)
        composeRule.onNodeWithTag("recheck_bluetooth_requirement_button").performClick()

        composeRule.onAllNodesWithTag("bluetooth_requirement_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("notification_permission_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun locationRevokedDuringRecheckSwitchesToLocationDialog() {
        val locationProvider = FakeBluetoothGateLocationPermissionStatusProvider(
            BackgroundLocationPermissionStatus.Granted
        )

        setAppContent(
            locationProvider = locationProvider,
            bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Disabled)
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()
        locationProvider.updateStatus(BackgroundLocationPermissionStatus.BackgroundMissing)
        composeRule.onNodeWithTag("recheck_bluetooth_requirement_button").performClick()

        composeRule.onAllNodesWithTag("bluetooth_requirement_dialog").assertCountEquals(0)
        composeRule.onNodeWithTag("location_permission_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
    }

    @Test
    fun allRequirementsEnabledShowsMonitoringDirectlyAndStartsOnce() {
        var startTripCallCount = 0

        setAppContent(
            bluetoothProvider = FakeBluetoothRequirementStatusProvider(BluetoothRequirementStatus.Enabled),
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
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        assertEquals(1, startTripCallCount)
    }

    private fun setAppContent(
        locationProvider: BackgroundLocationPermissionStatusProvider =
            FakeBluetoothGateLocationPermissionStatusProvider(BackgroundLocationPermissionStatus.Granted),
        notificationProvider: AppNotificationStatusProvider =
            FakeBluetoothGateNotificationStatusProvider(AppNotificationStatus.Enabled),
        bluetoothProvider: BluetoothRequirementStatusProvider,
        onOpenAppSettings: () -> Unit = {},
        onOpenBluetoothSettings: () -> Unit = {},
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
                    onOpenAppSettings = onOpenAppSettings,
                    onOpenBluetoothSettings = onOpenBluetoothSettings
                )
            }
        }
    }
}

private class FakeBluetoothGateLocationPermissionStatusProvider(
    initialStatus: BackgroundLocationPermissionStatus
) : BackgroundLocationPermissionStatusProvider {
    private var currentStatus: BackgroundLocationPermissionStatus = initialStatus

    override fun getStatus(): BackgroundLocationPermissionStatus = currentStatus

    fun updateStatus(newStatus: BackgroundLocationPermissionStatus) {
        currentStatus = newStatus
    }
}

private class FakeBluetoothGateNotificationStatusProvider(
    initialStatus: AppNotificationStatus
) : AppNotificationStatusProvider {
    private var currentStatus = initialStatus

    override fun getStatus(): AppNotificationStatus = currentStatus

    fun updateStatus(newStatus: AppNotificationStatus) {
        currentStatus = newStatus
    }
}

private class FakeBluetoothRequirementStatusProvider(
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
