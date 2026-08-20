package com.example.sos_segundoplano.domain.trip

import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

sealed interface TripTimingState {
    data object Unknown : TripTimingState
    data class Active(val startedAtElapsedRealtimeMillis: Long, val tripSessionKey: String? = null) : TripTimingState
}

fun interface ElapsedRealtimeClock {
    fun nowMillis(): Long
}

interface TripTimingStore {
    val states: StateFlow<TripTimingState>
    fun beginConfirmedTrip(tripSessionKey: String? = null)
    fun clear()
    fun clearIfMatches(tripSessionKey: String): TripTimingClearResult = TripTimingClearResult.LegacyUncorrelated
}
enum class TripTimingClearResult { Cleared, AlreadyEmpty, DifferentTrip, LegacyUncorrelated }

object TripDurationFormatter {
    fun format(state: TripTimingState, nowElapsedRealtimeMillis: Long): String? {
        val active = state as? TripTimingState.Active ?: return null
        if (active.startedAtElapsedRealtimeMillis < 0L || nowElapsedRealtimeMillis < active.startedAtElapsedRealtimeMillis) {
            return null
        }
        val totalSeconds = (nowElapsedRealtimeMillis - active.startedAtElapsedRealtimeMillis) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
