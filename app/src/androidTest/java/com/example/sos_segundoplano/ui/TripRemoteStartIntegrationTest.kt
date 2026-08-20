package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.sos_segundoplano.MotoSosApp
import com.example.sos_segundoplano.core.background.MonitoringServiceStartResult
import com.example.sos_segundoplano.core.background.MonitoringServiceStarter
import com.example.sos_segundoplano.data.remote.trip.ResolvedRemoteTripStarter
import com.example.sos_segundoplano.data.remote.trip.TripMutationResult
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.trip.TripTimingState
import com.example.sos_segundoplano.domain.trip.TripTimingStore
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TripRemoteStartIntegrationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun remoteStartCompletesBeforeServiceAndDoesNotStartTimerDirectly() {
        val order = mutableListOf<String>()
        val timing = CountingTimingStore()
        setApp(
            remoteStarter = ResolvedRemoteTripStarter {
                order += "remote-start"
                TripMutationResult.Success("remote-trip-canonical", "Active")
            },
            monitoringStarter = MonitoringServiceStarter {
                order += "service"
                MonitoringServiceStartResult.Started
            },
            timingStore = timing,
            startTrip = {
                order += "local-session"
                TripSessionState.Active("00000000-0000-0000-0000-000000000001")
            }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("monitoring_screen").assertIsDisplayed()
        assertEquals(listOf("remote-start", "service", "local-session"), order)
        assertEquals(0, timing.beginCalls)
    }

    @Test fun resourceFailureNeverStartsServiceOrCreatesLocalTrip() =
        assertFailureKeepsLocalTripIdle(TripMutationResult.NetworkUnavailable("network_unavailable"))

    @Test fun conflictNeverStartsServiceOrCreatesLocalTrip() =
        assertFailureKeepsLocalTripIdle(TripMutationResult.HttpError(409, "active_trip_exists"))

    @Test fun invalidStartResponseNeverStartsServiceOrCreatesLocalTrip() =
        assertFailureKeepsLocalTripIdle(TripMutationResult.InvalidResponse("trip_id_missing"))

    private fun assertFailureKeepsLocalTripIdle(failure: TripMutationResult) {
        var serviceCalls = 0
        var localStartCalls = 0
        setApp(
            remoteStarter = ResolvedRemoteTripStarter { failure },
            monitoringStarter = MonitoringServiceStarter {
                serviceCalls++
                MonitoringServiceStartResult.Started
            },
            startTrip = {
                localStartCalls++
                TripSessionState.Active("00000000-0000-0000-0000-000000000001")
            }
        )

        composeRule.onNodeWithTag("start_trip_button").performClick()

        composeRule.onNodeWithTag("monitoring_start_failure_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        assertEquals(0, serviceCalls)
        assertEquals(0, localStartCalls)
    }

    private fun setApp(
        remoteStarter: ResolvedRemoteTripStarter,
        monitoringStarter: MonitoringServiceStarter,
        timingStore: TripTimingStore? = null,
        startTrip: (TripSessionState) -> TripSessionState
    ) {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosApp(
                    startTripUseCase = startTrip,
                    remoteTripStarter = remoteStarter,
                    monitoringServiceStarter = monitoringStarter,
                    tripTimingStore = timingStore
                )
            }
        }
    }
}

private class CountingTimingStore : TripTimingStore {
    private val mutableStates = MutableStateFlow<TripTimingState>(TripTimingState.Unknown)
    override val states: StateFlow<TripTimingState> = mutableStates
    var beginCalls = 0
        private set

    override fun beginConfirmedTrip(tripSessionKey: String?) {
        beginCalls++
        mutableStates.value = TripTimingState.Active(0L, tripSessionKey)
    }

    override fun clear() {
        mutableStates.value = TripTimingState.Unknown
    }
}
