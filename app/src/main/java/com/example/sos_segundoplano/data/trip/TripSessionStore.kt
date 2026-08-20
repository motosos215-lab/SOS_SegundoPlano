package com.example.sos_segundoplano.data.trip

import android.content.Context
import com.example.sos_segundoplano.domain.model.TripSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface TripSessionStore {
    val states: StateFlow<TripSessionState>
    fun setState(state: TripSessionState)
    fun beginTripSession(): TripSessionState.Active
    fun setIdleIfMatches(tripSessionKey: String): TripSessionClearResult
}

enum class TripSessionClearResult { Cleared, AlreadyIdle, DifferentTrip }

class InMemoryTripSessionStore(
    initialState: TripSessionState = TripSessionState.Idle
) : TripSessionStore {
    private val _states = MutableStateFlow(initialState)
    override val states: StateFlow<TripSessionState> = _states

    override fun setState(state: TripSessionState) {
        _states.value = state
    }
    override fun beginTripSession(): TripSessionState.Active = (_states.value as? TripSessionState.Active)
        ?: TripSessionState.Active(java.util.UUID.randomUUID().toString()).also { _states.value = it }
    override fun setIdleIfMatches(tripSessionKey: String): TripSessionClearResult = when (val state = _states.value) {
        TripSessionState.Idle -> TripSessionClearResult.AlreadyIdle
        is TripSessionState.Active -> if (state.tripSessionKey == tripSessionKey) {
            _states.value = TripSessionState.Idle; TripSessionClearResult.Cleared
        } else TripSessionClearResult.DifferentTrip
    }
}

class PersistentTripSessionStore(context: Context) : TripSessionStore {
    private val preferences = context.applicationContext.getSharedPreferences("trip_session_v1", Context.MODE_PRIVATE)
    private val _states = MutableStateFlow(readState())
    override val states: StateFlow<TripSessionState> = _states
    override fun setState(state: TripSessionState) {
        when (state) {
            TripSessionState.Idle -> { if (preferences.edit().remove("trip_session_key").commit()) _states.value = state }
            is TripSessionState.Active -> { if (isValid(state.tripSessionKey) && preferences.edit().putString("trip_session_key", state.tripSessionKey).commit()) _states.value = state }
        }
    }
    override fun beginTripSession(): TripSessionState.Active = (_states.value as? TripSessionState.Active) ?: TripSessionState.Active(java.util.UUID.randomUUID().toString()).also(::setState)
    override fun setIdleIfMatches(tripSessionKey: String): TripSessionClearResult = when (val state = _states.value) {
        TripSessionState.Idle -> TripSessionClearResult.AlreadyIdle
        is TripSessionState.Active -> if (state.tripSessionKey == tripSessionKey && preferences.edit().remove("trip_session_key").commit()) { _states.value = TripSessionState.Idle; TripSessionClearResult.Cleared } else TripSessionClearResult.DifferentTrip
    }
    private fun readState(): TripSessionState {
        val key = preferences.getString("trip_session_key", null)
        return if (key != null && isValid(key)) TripSessionState.Active(key) else { if (key != null) preferences.edit().remove("trip_session_key").commit(); TripSessionState.Idle }
    }
    private fun isValid(value: String) = runCatching { java.util.UUID.fromString(value) }.isSuccess
}

object TripSessionStoreProvider {
    @Volatile private var installed: TripSessionStore? = null
    val store: TripSessionStore get() = checkNotNull(installed) { "TripSessionStoreProvider is not initialized" }
    fun initialize(context: Context): TripSessionStore = installed ?: synchronized(this) { installed ?: PersistentTripSessionStore(context).also { installed = it } }
}
