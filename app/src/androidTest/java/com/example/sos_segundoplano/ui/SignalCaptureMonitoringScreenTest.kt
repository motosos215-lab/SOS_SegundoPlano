package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import com.example.sos_segundoplano.domain.signals.BatterySample
import com.example.sos_segundoplano.domain.rules.BatteryReadinessStatus
import com.example.sos_segundoplano.domain.rules.ConnectivityReadinessStatus
import com.example.sos_segundoplano.domain.rules.DeviceReadinessEvaluation
import com.example.sos_segundoplano.domain.rules.GpsQualityEvaluation
import com.example.sos_segundoplano.domain.rules.GpsQualityStatus
import com.example.sos_segundoplano.domain.rules.MovementContinuityState
import com.example.sos_segundoplano.domain.rules.RiskAssessment
import com.example.sos_segundoplano.domain.rules.RiskAssessmentState
import com.example.sos_segundoplano.domain.rules.RiskLevel
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
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import com.example.sos_segundoplano.domain.validation.ValidationDecisionReason
import com.example.sos_segundoplano.domain.validation.ValidationEvidence
import com.example.sos_segundoplano.domain.validation.ValidationMetadata
import com.example.sos_segundoplano.domain.validation.ValidationOrigin
import com.example.sos_segundoplano.features.background.MonitoringScreen
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        composeRule.onNodeWithText("36").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("80%").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Internet disponible").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("72 bpm").performScrollTo().assertIsDisplayed()
        assertEquals(1, starter.starts)
    }

    @Test fun riskScoreShowsRealAssessmentValue() {
        setContent(riskAssessmentState = RiskAssessmentState.AssessmentReady(fakeRiskAssessment(score = 73, level = RiskLevel.High)))

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithText("Riesgo del viaje").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("73").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Riesgo alto").performScrollTo().assertIsDisplayed()
    }

    @Test fun riskScoreWaitsForAssessmentWithoutShowingZero() {
        setContent(riskAssessmentState = RiskAssessmentState.Idle)

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithText("Esperando datos de riesgo").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("0").assertCountEquals(0)
    }

    @Test fun absentSignalIsNotConvertedToZero() {
        setContent(snapshot = TripSignalSnapshot())
        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onAllNodesWithText("0").assertCountEquals(0)
        composeRule.onAllNodesWithText("Esperando señal").assertCountEquals(3)
        composeRule.onAllNodesWithText("Acelerómetro móvil").assertCountEquals(0)
        composeRule.onAllNodesWithText("Giroscopio móvil").assertCountEquals(0)
        composeRule.onAllNodesWithText("Sensores móviles").assertCountEquals(0)
        composeRule.onAllNodesWithText("Frecuencia cardiaca").assertCountEquals(0)
        composeRule.onAllNodesWithText("Batería del reloj").assertCountEquals(0)
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
        composeRule.onNodeWithText("Permiso de ubicación faltante").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Smartwatch desconectado").performScrollTo().assertIsDisplayed()
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

        composeRule.onNodeWithText("Permiso requerido en el reloj").performScrollTo().assertIsDisplayed()
        assertEquals(1, starter.starts)
    }

    @Test fun connectedNearbyAndCapturingWatchStatesUseWearableSnapshot() {
        setContent(
            snapshot = TripSignalSnapshot(
                wearable = WearableSample(status = WearableStatus.ConnectedNearby)
            )
        )
        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithText("Smartwatch conectado y cercano").performScrollTo().assertIsDisplayed()

        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitoringScreen(
                    snapshot = TripSignalSnapshot(
                        wearable = WearableSample(status = WearableStatus.Capturing, captureActive = true, lastUpdatedMillis = 1L)
                    )
                )
            }
        }
        composeRule.onNodeWithText("Smartwatch capturando señales").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Se recibió información del reloj").performScrollTo().assertIsDisplayed()
    }

    @Test fun wearableBatteryAndHeartRateOnlyAppearWhenPresent() {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitoringScreen(
                    snapshot = TripSignalSnapshot(
                        wearable = WearableSample(status = WearableStatus.Capturing)
                    )
                )
            }
        }
        composeRule.onAllNodesWithText("Frecuencia cardiaca").assertCountEquals(0)
        composeRule.onAllNodesWithText("Batería del reloj").assertCountEquals(0)

        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitoringScreen(
                    snapshot = TripSignalSnapshot(
                        wearable = WearableSample(
                            status = WearableStatus.Capturing,
                            heartRateBpm = 81.0,
                            watchBatteryPercentage = 64
                        )
                    )
                )
            }
        }
        composeRule.onNodeWithText("Frecuencia cardiaca").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("81 bpm").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Batería del reloj").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("64%").performScrollTo().assertIsDisplayed()
    }

    @Test fun falsePositiveCountdownRemainsVisibleAndActionable() {
        var confirmCalled = false
        var helpCalled = false
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MonitoringScreen(
                    validationState = fakeCountdownState(),
                    onConfirmSafe = { sessionId, assessmentId, _ ->
                        confirmCalled = sessionId == 1L && assessmentId == 1L
                    },
                    onRequestHelp = { sessionId, assessmentId, _ ->
                        helpCalled = sessionId == 1L && assessmentId == 1L
                    }
                )
            }
        }

        composeRule.onNodeWithText("Posible accidente detectado").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Estoy bien").performScrollTo().performClick()
        composeRule.onNodeWithText("Necesito ayuda").performScrollTo().performClick()

        assertTrue(confirmCalled)
        assertTrue(helpCalled)
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

        composeRule.onNodeWithText("Precisión GPS").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Excelente").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Conectividad").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sin conexión").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Estado del smartwatch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Datos del reloj desactualizados").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Se recibió información del reloj").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Frecuencia cardiaca").assertCountEquals(0)
        composeRule.onNodeWithText("Finalizar viaje").performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithTag("finish_trip_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("start_trip_button").performClick()
        assertEquals(2, starter.starts)
        assertEquals(1, stopper.stops)
    }

    private fun setContent(
        starter: CountingStarter = CountingStarter(),
        stopper: CountingStopper = CountingStopper(),
        snapshot: TripSignalSnapshot = TripSignalSnapshot(),
        riskAssessmentState: RiskAssessmentState = RiskAssessmentState.Idle
    ) {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    locationPermissionStatusProvider = BackgroundLocationPermissionStatusProvider { BackgroundLocationPermissionStatus.Granted },
                    notificationStatusProvider = AppNotificationStatusProvider { AppNotificationStatus.Enabled },
                    bluetoothRequirementStatusProvider = BluetoothRequirementStatusProvider { BluetoothRequirementStatus.Enabled },
                    monitoringServiceStarter = starter,
                    monitoringServiceStopper = stopper,
                    signalSnapshots = MutableStateFlow(snapshot),
                    riskAssessmentStates = MutableStateFlow(riskAssessmentState)
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

    private fun fakeRiskAssessment(score: Int?, level: RiskLevel) = RiskAssessment(
        sessionId = 1L,
        assessmentId = 1L,
        windowId = 1L,
        startNanos = 1L,
        endNanos = 2L,
        score = score,
        riskLevel = level,
        confidence = 0.8,
        outcomes = emptyList(),
        contributions = emptyList(),
        gpsQuality = GpsQualityEvaluation(GpsQualityStatus.Good, 4.0, 1L, 0.9),
        deviceReadiness = DeviceReadinessEvaluation(
            batteryStatus = BatteryReadinessStatus.Normal,
            batteryPercentage = 80,
            charging = false,
            connectivityStatus = ConnectivityReadinessStatus.Available,
            connectivityValidated = true,
            transport = NetworkTransport.Wifi,
            wearableStatus = WearableStatus.Capturing,
            canCommunicateLater = true,
            confidence = 0.8
        ),
        movementContinuity = MovementContinuityState.Continuing,
        droppedProcessedWindows = 0L,
        lateWindows = 0L,
        droppedRawEvents = 0L,
        ruleSetVersion = "test-rules",
        partialWindow = false
    )

    private fun fakeCountdownState() = FalsePositiveValidationState.CountdownActive(
        assessment = fakeRiskAssessment(score = 55, level = RiskLevel.Medium),
        metadata = ValidationMetadata(
            sessionId = 1L,
            assessmentId = 1L,
            windowId = 1L,
            timestampElapsedRealtimeNanos = 1L,
            reason = ValidationDecisionReason.CandidatePhysicalRisk,
            score = 55,
            confidence = 0.8,
            origin = ValidationOrigin.System,
            policyVersion = "test-policy"
        ),
        evidence = ValidationEvidence(
            movementContinuity = MovementContinuityState.Intermittent,
            gpsQuality = GpsQualityStatus.Good,
            ruleSetVersion = "test-rules"
        ),
        startedAtElapsedRealtimeNanos = 1L,
        deadlineElapsedRealtimeNanos = 21_000_000_000L,
        remainingNanos = 20_000_000_000L
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
