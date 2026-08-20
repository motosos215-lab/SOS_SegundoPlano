package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.sos_segundoplano.MotoSosApp
import com.example.sos_segundoplano.core.background.MonitoringServiceStartResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStarter
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

class MonitoringReadinessTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun riderHomeShowsReadyWhenAllCurrentStartRequirementsAreAvailable() {
        setAppContent()

        composeRule.onNodeWithTag("monitoring_readiness_card").assertIsDisplayed()
        composeRule.onNodeWithText("Estado para monitoreo").assertIsDisplayed()
        composeRule.onNodeWithText("Listo para iniciar viaje").assertIsDisplayed()
        composeRule.onNodeWithTag("monitoring_readiness_location").assertIsDisplayed()
        composeRule.onNodeWithTag("monitoring_readiness_notifications").assertIsDisplayed()
        composeRule.onNodeWithTag("monitoring_readiness_bluetooth").assertIsDisplayed()
    }

    @Test fun missingBlockingRequirementNeedsAttentionAndUsesExistingAction() {
        var appSettingsOpenCount = 0
        setAppContent(
            location = MutableLocationProvider(BackgroundLocationPermissionStatus.BackgroundMissing),
            onOpenAppSettings = { appSettingsOpenCount++ }
        )

        composeRule.onNodeWithText("Requiere atención antes de iniciar").assertIsDisplayed()
        composeRule.onNodeWithTag("monitoring_readiness_location_action").performClick()
        composeRule.onNodeWithTag("location_permission_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("open_location_settings_button").performClick()

        assertEquals(1, appSettingsOpenCount)
    }

    @Test fun resumeRefreshesReadinessAfterReturningFromSettings() {
        val location = MutableLocationProvider(BackgroundLocationPermissionStatus.Granted)
        val lifecycleOwner = ReadinessLifecycleOwner()
        lifecycleOwner.resume()
        setAppContent(location = location, lifecycleOwner = lifecycleOwner)
        composeRule.onNodeWithText("Listo para iniciar viaje").assertIsDisplayed()

        composeRule.runOnIdle {
            location.currentStatus = BackgroundLocationPermissionStatus.BackgroundMissing
            lifecycleOwner.pauseAndResume()
        }

        composeRule.onNodeWithText("Requiere atención antes de iniciar").assertIsDisplayed()
    }

    @Test fun readinessDoesNotChangeExistingStartGate() {
        val starter = CountingReadinessStarter()
        setAppContent(
            notification = MutableNotificationProvider(AppNotificationStatus.Disabled),
            monitoringServiceStarter = starter
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("notification_permission_dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(0, starter.invocations)
    }

    @Test fun activeTripShowsMonitoringWithoutReadinessCard() {
        setAppContent(tripSessionState = TripSessionState.Active("00000000-0000-0000-0000-000000000001"))

        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_readiness_card").assertCountEquals(0)
    }

    private fun setAppContent(
        location: MutableLocationProvider = MutableLocationProvider(BackgroundLocationPermissionStatus.Granted),
        notification: MutableNotificationProvider = MutableNotificationProvider(AppNotificationStatus.Enabled),
        bluetooth: MutableBluetoothProvider = MutableBluetoothProvider(BluetoothRequirementStatus.Enabled),
        lifecycleOwner: LifecycleOwner = ReadinessLifecycleOwner().apply { resume() },
        monitoringServiceStarter: MonitoringServiceStarter = CountingReadinessStarter(),
        tripSessionState: TripSessionState = TripSessionState.Idle,
        onOpenAppSettings: () -> Unit = {}
    ) {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    locationPermissionStatusProvider = location,
                    notificationStatusProvider = notification,
                    bluetoothRequirementStatusProvider = bluetooth,
                    readinessLifecycleOwner = lifecycleOwner,
                    monitoringServiceStarter = monitoringServiceStarter,
                    tripSessionStore = InMemoryTripSessionStore(tripSessionState),
                    onOpenAppSettings = onOpenAppSettings
                )
            }
        }
    }
}

private class MutableLocationProvider(
    var currentStatus: BackgroundLocationPermissionStatus
) : BackgroundLocationPermissionStatusProvider {
    override fun getStatus(): BackgroundLocationPermissionStatus = currentStatus
}

private class MutableNotificationProvider(
    var currentStatus: AppNotificationStatus
) : AppNotificationStatusProvider {
    override fun getStatus(): AppNotificationStatus = currentStatus
}

private class MutableBluetoothProvider(
    var currentStatus: BluetoothRequirementStatus
) : BluetoothRequirementStatusProvider {
    override fun getStatus(): BluetoothRequirementStatus = currentStatus
}

private class CountingReadinessStarter : MonitoringServiceStarter {
    var invocations = 0
    override fun start(): MonitoringServiceStartResult {
        invocations++
        return MonitoringServiceStartResult.Started
    }
}

private class ReadinessLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle = registry

    fun resume() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun pauseAndResume() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
}
