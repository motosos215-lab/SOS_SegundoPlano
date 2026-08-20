package com.example.sos_segundoplano.data.trip

import com.example.sos_segundoplano.data.remote.trip.InMemoryRemoteTripSessionStore
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.trip.TripTimingClearResult
import com.example.sos_segundoplano.domain.trip.TripTimingState
import com.example.sos_segundoplano.domain.trip.TripTimingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripLocalStateReconcilerTest {
    private val tripA = "trip-session-A"

    @Test fun matchingTripClearsRemoteTimingAndSessionTogether() {
        val remote = InMemoryRemoteTripSessionStore().apply { setActiveSession("remote-A", 1L, tripA) }
        val sessions = InMemoryTripSessionStore(TripSessionState.Active(tripA))
        val timing = FakeTimingStore(TripTimingState.Active(100L, tripA))
        val reconciler = TripLocalStateReconciler(remote, sessions, timing)

        assertTrue(reconciler.reconcileFinishedRemoteTrip("remote-A"))

        assertNull(remote.remoteTripId.value)
        assertNull(remote.tripSessionKey.value)
        assertEquals(TripTimingState.Unknown, timing.states.value)
        assertEquals(TripSessionState.Idle, sessions.states.value)
    }

    @Test fun lateFinalizationForTripADoesNotClearTripB() {
        val remote = InMemoryRemoteTripSessionStore().apply { setActiveSession("remote-B", 2L, "trip-session-B") }
        val sessions = InMemoryTripSessionStore(TripSessionState.Active("trip-session-B"))
        val timing = FakeTimingStore(TripTimingState.Active(200L, "trip-session-B"))
        val reconciler = TripLocalStateReconciler(remote, sessions, timing)

        assertTrue(reconciler.reconcileFinishedRemoteTrip("remote-A"))

        assertEquals("remote-B", remote.remoteTripId.value)
        assertEquals("trip-session-B", remote.tripSessionKey.value)
        assertEquals(TripTimingState.Active(200L, "trip-session-B"), timing.states.value)
        assertEquals(TripSessionState.Active("trip-session-B"), sessions.states.value)
    }

    @Test fun legacyUncorrelatedRemoteStateIsRetainedForSafeRetry() {
        val remote = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-A") }
        val sessions = InMemoryTripSessionStore(TripSessionState.Active(tripA))
        val timing = FakeTimingStore(TripTimingState.Active(100L, tripA))
        val reconciler = TripLocalStateReconciler(remote, sessions, timing)

        assertFalse(reconciler.reconcileFinishedRemoteTrip("remote-A"))

        assertEquals("remote-A", remote.remoteTripId.value)
        assertEquals(TripSessionState.Active(tripA), sessions.states.value)
        assertEquals(TripTimingState.Active(100L, tripA), timing.states.value)
    }

    @Test fun differentTimingIdentityPreventsAnyCleanup() {
        val remote = InMemoryRemoteTripSessionStore().apply { setActiveSession("remote-A", 1L, tripA) }
        val sessions = InMemoryTripSessionStore(TripSessionState.Active(tripA))
        val timing = FakeTimingStore(TripTimingState.Active(100L, "trip-session-B"))
        val reconciler = TripLocalStateReconciler(remote, sessions, timing)

        assertFalse(reconciler.reconcileFinishedRemoteTrip("remote-A"))

        assertEquals("remote-A", remote.remoteTripId.value)
        assertEquals(TripSessionState.Active(tripA), sessions.states.value)
        assertEquals(TripTimingState.Active(100L, "trip-session-B"), timing.states.value)
    }

    private class FakeTimingStore(initial: TripTimingState) : TripTimingStore {
        private val mutableStates = MutableStateFlow(initial)
        override val states: StateFlow<TripTimingState> = mutableStates

        override fun beginConfirmedTrip(tripSessionKey: String?) = Unit

        override fun clear() {
            mutableStates.value = TripTimingState.Unknown
        }

        override fun clearIfMatches(tripSessionKey: String): TripTimingClearResult = when (val state = mutableStates.value) {
            TripTimingState.Unknown -> TripTimingClearResult.AlreadyEmpty
            is TripTimingState.Active -> when {
                state.tripSessionKey == null -> TripTimingClearResult.LegacyUncorrelated
                state.tripSessionKey != tripSessionKey -> TripTimingClearResult.DifferentTrip
                else -> {
                    clear()
                    TripTimingClearResult.Cleared
                }
            }
        }
    }
}
