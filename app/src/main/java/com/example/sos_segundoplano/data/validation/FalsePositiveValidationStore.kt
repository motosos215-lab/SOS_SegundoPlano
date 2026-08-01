package com.example.sos_segundoplano.data.validation

import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface FalsePositiveValidationStore {
    val states: StateFlow<FalsePositiveValidationState>
    fun publish(state: FalsePositiveValidationState)
    fun clear()
}

class InMemoryFalsePositiveValidationStore : FalsePositiveValidationStore {
    private val _states = MutableStateFlow<FalsePositiveValidationState>(FalsePositiveValidationState.Idle)
    override val states: StateFlow<FalsePositiveValidationState> = _states

    override fun publish(state: FalsePositiveValidationState) {
        _states.value = state
    }

    override fun clear() {
        _states.value = FalsePositiveValidationState.Idle
    }
}

object FalsePositiveValidationStoreProvider {
    val store: FalsePositiveValidationStore = InMemoryFalsePositiveValidationStore()
}
