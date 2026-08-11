package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.sos_segundoplano.MotoSosApp
import com.example.sos_segundoplano.core.background.MonitoringServiceStopResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStopper
import com.example.sos_segundoplano.data.trip.InMemoryTripSessionStore
import com.example.sos_segundoplano.data.remote.trip.RemoteTripFinisher
import com.example.sos_segundoplano.data.remote.trip.TripMutationResult
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.trip.ElapsedRealtimeClock
import com.example.sos_segundoplano.domain.trip.TripTimingState
import com.example.sos_segundoplano.domain.trip.TripTimingStore
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TripLocalSummaryFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun normalFinishShowsFrozenDurationAfterTimingWasCleared() {
        val timing = SummaryFakeTimingStore(TripTimingState.Active(10_000L))
        setActiveTrip(timing)

        composeRule.onNodeWithTag("finish_trip_button").performScrollTo().performClick()

        composeRule.onNodeWithTag("trip_local_summary_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Viaje finalizado").assertIsDisplayed()
        composeRule.onNodeWithText("00:00:47").assertIsDisplayed()
        assertEquals(TripTimingState.Unknown, timing.states.value)
    }

    @Test fun returnHomeRemovesSummaryAndNewTripDoesNotReuseIt() {
        val timing = SummaryFakeTimingStore(TripTimingState.Active(10_000L))
        setActiveTrip(timing)
        composeRule.onNodeWithTag("finish_trip_button").performScrollTo().performClick()

        composeRule.onNodeWithTag("trip_summary_return_home").performClick()

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("trip_local_summary_screen").assertCountEquals(0)
        composeRule.onNodeWithTag("start_trip_button").performClick()
        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("trip_local_summary_screen").assertCountEquals(0)
    }

    @Test fun unknownTimingShowsNeutralTextInsteadOfZero() {
        setActiveTrip(SummaryFakeTimingStore(TripTimingState.Unknown))

        composeRule.onNodeWithTag("finish_trip_button").performScrollTo().performClick()

        composeRule.onNodeWithText("Duración no disponible").assertIsDisplayed()
        composeRule.onAllNodesWithTag("trip_summary_duration").assertCountEquals(1)
    }

    @Test fun savedStateRestorationKeepsCompletedSummary() {
        val restorationTester = StateRestorationTester(composeRule)
        val timing = SummaryFakeTimingStore(TripTimingState.Active(10_000L))
        val session = InMemoryTripSessionStore(TripSessionState.Active)
        restorationTester.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    tripSessionStore = session,
                    tripTimingStore = timing,
                    elapsedRealtimeClock = ElapsedRealtimeClock { 57_000L },
                    monitoringServiceStopper = successfulStopper()
                )
            }
        }
        composeRule.onNodeWithTag("finish_trip_button").performScrollTo().performClick()
        composeRule.onNodeWithText("00:00:47").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("trip_local_summary_screen").assertIsDisplayed()
        composeRule.onNodeWithText("00:00:47").assertIsDisplayed()
    }

    @Test fun localSummaryRemainsVisibleWhileRemoteFinishRunsAfterCaptureStops() {
        val remoteFinishCalls = AtomicInteger(0)
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    tripSessionStore = InMemoryTripSessionStore(TripSessionState.Active),
                    tripTimingStore = SummaryFakeTimingStore(TripTimingState.Active(10_000L)),
                    elapsedRealtimeClock = ElapsedRealtimeClock { 57_000L },
                    monitoringServiceStopper = successfulStopper(),
                    remoteTripFinisher = RemoteTripFinisher {
                        remoteFinishCalls.incrementAndGet()
                        TripMutationResult.Success("remote-trip-1", "Finished")
                    }
                )
            }
        }

        composeRule.onNodeWithTag("finish_trip_button").performScrollTo().performClick()

        composeRule.onNodeWithTag("trip_local_summary_screen").assertIsDisplayed()
        composeRule.onNodeWithText("00:00:47").assertIsDisplayed()
        composeRule.waitUntil { remoteFinishCalls.get() == 1 }
    }

    private fun setActiveTrip(timing: SummaryFakeTimingStore) {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    tripSessionStore = InMemoryTripSessionStore(TripSessionState.Active),
                    tripTimingStore = timing,
                    elapsedRealtimeClock = ElapsedRealtimeClock { 57_000L },
                    monitoringServiceStopper = successfulStopper()
                )
            }
        }
    }

    private fun successfulStopper() = MonitoringServiceStopper { MonitoringServiceStopResult.Stopped }
}

private class SummaryFakeTimingStore(initial: TripTimingState) : TripTimingStore {
    private val mutableStates = MutableStateFlow(initial)
    override val states: StateFlow<TripTimingState> = mutableStates
    override fun beginConfirmedTrip() {
        mutableStates.value = TripTimingState.Active(57_000L)
    }
    override fun clear() {
        mutableStates.value = TripTimingState.Unknown
    }
}
