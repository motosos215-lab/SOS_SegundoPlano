package com.example.sos_segundoplano.data.trip

import com.example.sos_segundoplano.data.remote.trip.RemoteTripSessionClearResult
import com.example.sos_segundoplano.data.remote.trip.RemoteTripSessionStore
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.trip.TripTimingClearResult
import com.example.sos_segundoplano.domain.trip.TripTimingState
import com.example.sos_segundoplano.domain.trip.TripTimingStore

/**
 * Correlated cleanup for a remotely finished trip.
 *
 * The reconciler never clears state belonging to a different logical trip.  This matters when
 * automatic incident finalization races with a newly started trip after a retry or process death.
 */
class TripLocalStateReconciler(
    private val remoteTripStore: RemoteTripSessionStore,
    private val tripSessionStore: TripSessionStore,
    private val tripTimingStore: TripTimingStore,
) {
    fun reconcileFinishedRemoteTrip(remoteTripId: String): Boolean {
        val normalizedRemoteTripId = remoteTripId.trim().takeIf { it.isNotEmpty() } ?: return false
        val currentRemoteTripId = remoteTripStore.remoteTripId.value

        // The target trip is no longer the local trip. Do not let a late retry touch the new trip.
        if (currentRemoteTripId != null && currentRemoteTripId != normalizedRemoteTripId) return true
        if (currentRemoteTripId == null) {
            return tripSessionStore.states.value == TripSessionState.Idle &&
                tripTimingStore.states.value == TripTimingState.Unknown
        }

        val tripSessionKey = remoteTripStore.tripSessionKey.value?.trim()?.takeIf { it.isNotEmpty() }
            ?: return false

        val sessionMatches = when (val session = tripSessionStore.states.value) {
            TripSessionState.Idle -> true
            is TripSessionState.Active -> session.tripSessionKey == tripSessionKey
        }
        val timingMatches = when (val timing = tripTimingStore.states.value) {
            TripTimingState.Unknown -> true
            is TripTimingState.Active -> timing.tripSessionKey == tripSessionKey
        }
        if (!sessionMatches || !timingMatches) return false

        val timingCleared = when (tripTimingStore.clearIfMatches(tripSessionKey)) {
            TripTimingClearResult.Cleared,
            TripTimingClearResult.AlreadyEmpty -> true
            TripTimingClearResult.DifferentTrip,
            TripTimingClearResult.LegacyUncorrelated -> false
        }
        if (!timingCleared) return false

        val sessionCleared = when (tripSessionStore.setIdleIfMatches(tripSessionKey)) {
            TripSessionClearResult.Cleared,
            TripSessionClearResult.AlreadyIdle -> true
            TripSessionClearResult.DifferentTrip -> false
        }
        if (!sessionCleared) return false

        return when (remoteTripStore.clearIfMatches(tripSessionKey, normalizedRemoteTripId)) {
            RemoteTripSessionClearResult.Cleared,
            RemoteTripSessionClearResult.AlreadyEmpty -> true
            RemoteTripSessionClearResult.DifferentTrip,
            RemoteTripSessionClearResult.LegacyUncorrelated -> false
        }
    }
}
