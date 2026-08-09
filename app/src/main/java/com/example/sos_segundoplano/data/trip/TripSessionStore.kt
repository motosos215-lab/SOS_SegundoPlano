package com.example.sos_segundoplano.data.trip

import com.example.sos_segundoplano.domain.model.TripSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface TripSessionStore {
    val states: StateFlow<TripSessionState>
    fun setState(state: TripSessionState)
}

class InMemoryTripSessionStore(
    initialState: TripSessionState = TripSessionState.Idle
) : TripSessionStore {
    private val _states = MutableStateFlow(initialState)
    override val states: StateFlow<TripSessionState> = _states

    override fun setState(state: TripSessionState) {
        _states.value = state
    }
}

object TripSessionStoreProvider {
    val store: TripSessionStore = InMemoryTripSessionStore()
}
