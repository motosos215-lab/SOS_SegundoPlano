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
import com.example.sos_segundoplano.domain.trip.TripTimingState
import com.example.sos_segundoplano.domain.trip.TripTimingStore
import com.example.sos_segundoplano.domain.rules.BatteryReadinessStatus
import com.example.sos_segundoplano.domain.rules.ConnectivityReadinessStatus
import com.example.sos_segundoplano.domain.rules.DeviceReadinessEvaluation
import com.example.sos_segundoplano.domain.rules.GpsQualityEvaluation
import com.example.sos_segundoplano.domain.rules.GpsQualityStatus
import com.example.sos_segundoplano.domain.rules.MovementContinuityState
import com.example.sos_segundoplano.domain.rules.RiskAssessment
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import com.example.sos_segundoplano.domain.validation.ValidationDecisionReason
import com.example.sos_segundoplano.domain.validation.ValidationEvidence
import com.example.sos_segundoplano.domain.validation.ValidationMetadata
import com.example.sos_segundoplano.domain.validation.ValidationOrigin
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        composeRule.onAllNodesWithTag("trip_local_summary_screen").assertCountEquals(0)
    }

    @Test fun activeSessionWithCountdownOpensAccidentScreenFirst() {
        setAppContent(
            store = InMemoryTripSessionStore(TripSessionState.Active),
            validationState = fakeCountdownState()
        )

        composeRule.onNodeWithTag("accident_countdown_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        composeRule.onAllNodesWithTag("home_screen").assertCountEquals(0)
        composeRule.onAllNodesWithTag("trip_local_summary_screen").assertCountEquals(0)
    }

    @Test fun safeConfirmedKeepsActiveSessionOnMonitoring() {
        val store = InMemoryTripSessionStore(TripSessionState.Active)
        setAppContent(
            store = store,
            validationState = FalsePositiveValidationState.SafeConfirmed(fakeMetadata(), "safe-1")
        )

        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("accident_countdown_screen").assertCountEquals(0)
        composeRule.onAllNodesWithTag("home_screen").assertCountEquals(0)
        composeRule.onAllNodesWithTag("trip_local_summary_screen").assertCountEquals(0)
        assertEquals(TripSessionState.Active, store.states.value)
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

    @Test fun successfulFinishMarksIdleShowsSummaryAndReturnsHome() {
        val store = InMemoryTripSessionStore(TripSessionState.Active)
        setAppContent(
            store = store,
            stopper = FakeRestorationMonitoringServiceStopper(MonitoringServiceStopResult.Stopped)
        )

        composeRule.onNodeWithTag("finish_trip_button").performScrollTo().performClick()

        composeRule.onNodeWithTag("trip_local_summary_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("home_screen").assertCountEquals(0)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(TripSessionState.Idle, store.states.value)

        composeRule.onNodeWithTag("trip_summary_return_home").performClick()

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("trip_local_summary_screen").assertCountEquals(0)
    }

    @Test fun alreadyStoppedFinishMarksIdleShowsSummaryAndReturnsHome() {
        val store = InMemoryTripSessionStore(TripSessionState.Active)
        setAppContent(
            store = store,
            stopper = FakeRestorationMonitoringServiceStopper(MonitoringServiceStopResult.AlreadyStopped)
        )

        composeRule.onNodeWithTag("finish_trip_button").performScrollTo().performClick()

        composeRule.onNodeWithTag("trip_local_summary_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("home_screen").assertCountEquals(0)
        composeRule.onAllNodesWithTag("monitoring_screen").assertCountEquals(0)
        assertEquals(TripSessionState.Idle, store.states.value)

        composeRule.onNodeWithTag("trip_summary_return_home").performClick()

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("trip_local_summary_screen").assertCountEquals(0)
    }

    @Test fun successfulFinishClearsActiveTripTiming() {
        val sessionStore = InMemoryTripSessionStore(TripSessionState.Active)
        val timingStore = FakeRestorationTripTimingStore(TripTimingState.Active(1_000L))
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    tripSessionStore = sessionStore,
                    tripTimingStore = timingStore,
                    monitoringServiceStopper = FakeRestorationMonitoringServiceStopper(MonitoringServiceStopResult.Stopped)
                )
            }
        }

        composeRule.onNodeWithTag("finish_trip_button").performScrollTo().performClick()

        assertEquals(TripTimingState.Unknown, timingStore.states.value)
        assertEquals(TripSessionState.Idle, sessionStore.states.value)
        composeRule.onNodeWithTag("trip_local_summary_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("home_screen").assertCountEquals(0)

        composeRule.onNodeWithTag("trip_summary_return_home").performClick()

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("trip_local_summary_screen").assertCountEquals(0)
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
        stopper: MonitoringServiceStopper = FakeRestorationMonitoringServiceStopper(MonitoringServiceStopResult.Stopped),
        validationState: FalsePositiveValidationState = FalsePositiveValidationState.Idle
    ) {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    tripSessionStore = store,
                    locationPermissionStatusProvider = BackgroundLocationPermissionStatusProvider { BackgroundLocationPermissionStatus.Granted },
                    notificationStatusProvider = AppNotificationStatusProvider { AppNotificationStatus.Enabled },
                    bluetoothRequirementStatusProvider = BluetoothRequirementStatusProvider { BluetoothRequirementStatus.Enabled },
                    monitoringServiceStarter = FakeRestorationMonitoringServiceStarter(),
                    monitoringServiceStopper = stopper,
                    validationStates = MutableStateFlow(validationState)
                )
            }
        }
    }

    private fun fakeCountdownState() = FalsePositiveValidationState.CountdownActive(
        assessment = RiskAssessment(
            sessionId = 1L,
            assessmentId = 1L,
            windowId = 1L,
            startNanos = 1L,
            endNanos = 2L,
            score = 55,
            riskLevel = RiskLevel.Medium,
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
                transport = null,
                wearableStatus = null,
                canCommunicateLater = true,
                confidence = 0.8
            ),
            movementContinuity = MovementContinuityState.Stopped,
            droppedProcessedWindows = 0L,
            lateWindows = 0L,
            droppedRawEvents = 0L,
            ruleSetVersion = "test-rules",
            partialWindow = false
        ),
        metadata = fakeMetadata(),
        evidence = ValidationEvidence(
            movementContinuity = MovementContinuityState.Stopped,
            gpsQuality = GpsQualityStatus.Good,
            ruleSetVersion = "test-rules"
        ),
        startedAtElapsedRealtimeNanos = 1L,
        deadlineElapsedRealtimeNanos = 21_000_000_000L,
        remainingNanos = 20_000_000_000L
    )

    private fun fakeMetadata() = ValidationMetadata(
        sessionId = 1L,
        assessmentId = 1L,
        windowId = 1L,
        timestampElapsedRealtimeNanos = 1L,
        reason = ValidationDecisionReason.CandidatePhysicalRisk,
        score = 55,
        confidence = 0.8,
        origin = ValidationOrigin.System,
        policyVersion = "test-policy"
    )
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

private class FakeRestorationTripTimingStore(initial: TripTimingState) : TripTimingStore {
    private val mutableStates = MutableStateFlow(initial)
    override val states: StateFlow<TripTimingState> = mutableStates
    override fun beginConfirmedTrip() {
        mutableStates.value = TripTimingState.Active(0L)
    }
    override fun clear() {
        mutableStates.value = TripTimingState.Unknown
    }
}
