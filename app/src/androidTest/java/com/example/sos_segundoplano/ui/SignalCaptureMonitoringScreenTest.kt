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
import com.example.sos_segundoplano.domain.signals.BatterySample
import com.example.sos_segundoplano.domain.signals.ConnectivitySample
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.signals.NetworkTransport
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.SignalReading
import com.example.sos_segundoplano.domain.signals.SpeedSample
import com.example.sos_segundoplano.domain.signals.SpeedSource
import com.example.sos_segundoplano.domain.signals.TripSignalSnapshot
import com.example.sos_segundoplano.domain.signals.WearableSample
import com.example.sos_segundoplano.domain.signals.WearableStatus
import com.example.sos_segundoplano.features.background.MonitoringScreen
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SignalCaptureMonitoringScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun beforeStartingThereIsNoCaptureScreen() {
        val starter = CountingStarter()
        setContent(starter = starter)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(0, starter.starts)
    }

    @Test fun startingTripStartsCaptureOnceAndShowsFakeSignals() {
        val starter = CountingStarter()
        setContent(starter = starter, snapshot = fakeSnapshot())
        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithText("36").assertIsDisplayed()
        composeRule.onNodeWithText("80%").assertIsDisplayed()
        composeRule.onNodeWithText("Internet disponible").assertIsDisplayed()
        composeRule.onNodeWithText("72 bpm").assertIsDisplayed()
        assertEquals(1, starter.starts)
    }

    @Test fun absentSignalIsNotConvertedToZero() {
        setContent(snapshot = TripSignalSnapshot())
        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onAllNodesWithText("0").assertCountEquals(0)
        composeRule.onAllNodesWithText("Esperando señal").assertCountEquals(4)
        composeRule.onAllNodesWithText("Acelerómetro móvil").assertCountEquals(0)
        composeRule.onAllNodesWithText("Giroscopio móvil").assertCountEquals(0)
        composeRule.onAllNodesWithText("Sensores móviles").assertCountEquals(0)
        composeRule.onAllNodesWithText("latitude").assertCountEquals(0)
        composeRule.onAllNodesWithText("longitude").assertCountEquals(0)
        composeRule.onAllNodesWithText("x").assertCountEquals(0)
        composeRule.onAllNodesWithText("y").assertCountEquals(0)
        composeRule.onAllNodesWithText("z").assertCountEquals(0)
    }

    @Test fun disconnectedWatchAndRevokedPermissionAreExplicit() {
        setContent(
            snapshot = TripSignalSnapshot(
                location = SignalReading(SignalAvailability.PermissionMissing),
                wearable = WearableSample(status = WearableStatus.Disconnected)
            )
        )
        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithText("Permiso de ubicación faltante").assertIsDisplayed()
        composeRule.onNodeWithText("Smartwatch desconectado").assertIsDisplayed()
    }

    @Test fun requiredWatchPermissionIsShownWithoutBlockingMobileTrip() {
        val starter = CountingStarter()
        setContent(
            starter = starter,
            snapshot = TripSignalSnapshot(
                wearable = WearableSample(status = WearableStatus.PermissionRequired)
            )
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithText("Permiso requerido en el reloj").assertIsDisplayed()
        assertEquals(1, starter.starts)
    }

    @Test fun monitoringScreenContainsLongResponsiveTexts() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitoringScreen(
                    snapshot = TripSignalSnapshot(
                        location = SignalReading(
                            SignalAvailability.Available,
                            LocationSample(
                                latitude = 0.0,
                                longitude = 0.0,
                                accuracyMeters = 4f,
                                timestampMillis = 1L,
                                provider = "gps",
                                isMock = false
                            )
                        ),
                        connectivity = SignalReading(
                            SignalAvailability.Available,
                            ConnectivitySample(false, false, false, NetworkTransport.None, 1L)
                        ),
                        wearable = WearableSample(status = WearableStatus.Stale, lastUpdatedMillis = 1L)
                    )
                )
            }
        }

        composeRule.onNodeWithText("Precisión GPS").assertIsDisplayed()
        composeRule.onNodeWithText("Excelente").assertIsDisplayed()
        composeRule.onNodeWithText("Conectividad").assertIsDisplayed()
        composeRule.onNodeWithText("Sin conexión").assertIsDisplayed()
        composeRule.onNodeWithText("Estado del smartwatch").assertIsDisplayed()
        composeRule.onNodeWithText("Datos del reloj desactualizados").assertIsDisplayed()
        composeRule.onNodeWithText("Frecuencia cardiaca").assertIsDisplayed()
        composeRule.onNodeWithText("Finalizar viaje").assertIsDisplayed()
        composeRule.onAllNodesWithText("Acelerómetro móvil").assertCountEquals(0)
        composeRule.onAllNodesWithText("Giroscopio móvil").assertCountEquals(0)
        composeRule.onAllNodesWithText("Sensores móviles").assertCountEquals(0)
        composeRule.onAllNodesWithText("latitude").assertCountEquals(0)
        composeRule.onAllNodesWithText("longitude").assertCountEquals(0)
        composeRule.onAllNodesWithText("x").assertCountEquals(0)
        composeRule.onAllNodesWithText("y").assertCountEquals(0)
        composeRule.onAllNodesWithText("z").assertCountEquals(0)
    }

    @Test fun finishingTripStopsAndSecondTripStartsAgain() {
        val starter = CountingStarter()
        val stopper = CountingStopper()
        setContent(starter = starter, stopper = stopper, snapshot = fakeSnapshot())
        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("finish_trip_button").performClick()
        composeRule.onNodeWithTag("start_trip_button").performClick()
        assertEquals(2, starter.starts)
        assertEquals(1, stopper.stops)
    }

    private fun setContent(
        starter: CountingStarter = CountingStarter(),
        stopper: CountingStopper = CountingStopper(),
        snapshot: TripSignalSnapshot = TripSignalSnapshot()
    ) {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    locationPermissionStatusProvider = BackgroundLocationPermissionStatusProvider { BackgroundLocationPermissionStatus.Granted },
                    notificationStatusProvider = AppNotificationStatusProvider { AppNotificationStatus.Enabled },
                    bluetoothRequirementStatusProvider = BluetoothRequirementStatusProvider { BluetoothRequirementStatus.Enabled },
                    monitoringServiceStarter = starter,
                    monitoringServiceStopper = stopper,
                    signalSnapshots = MutableStateFlow(snapshot)
                )
            }
        }
    }

    private fun fakeSnapshot() = TripSignalSnapshot(
        speed = SignalReading(SignalAvailability.Available, SpeedSample(10f, 1L, SpeedSource.DirectLocation)),
        phoneBattery = SignalReading(SignalAvailability.Available, BatterySample(80, false, 1L)),
        connectivity = SignalReading(SignalAvailability.Available, ConnectivitySample(true, true, false, NetworkTransport.Wifi, 1L)),
        wearable = WearableSample(heartRateBpm = 72.0, captureActive = true, status = WearableStatus.Capturing, lastUpdatedMillis = 1L)
    )
}

private class CountingStarter : MonitoringServiceStarter {
    var starts = 0
    override fun start(): MonitoringServiceStartResult {
        starts++
        return MonitoringServiceStartResult.Started
    }
}

private class CountingStopper : MonitoringServiceStopper {
    var stops = 0
    override fun stop(): MonitoringServiceStopResult {
        stops++
        return MonitoringServiceStopResult.Stopped
    }
}
