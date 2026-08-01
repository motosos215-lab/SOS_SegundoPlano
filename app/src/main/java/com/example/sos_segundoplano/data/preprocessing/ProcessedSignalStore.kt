package com.example.sos_segundoplano.data.preprocessing

import com.example.sos_segundoplano.domain.preprocessing.ProcessedSignalState
import com.example.sos_segundoplano.domain.preprocessing.ProcessedSignalWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface ProcessedSignalStore {
    val states: StateFlow<ProcessedSignalState>
    fun start(sessionId: Long)
    fun publish(window: ProcessedSignalWindow)
    fun insufficient(sessionId: Long, observedSamples: Int)
    fun stop()
    fun clear()
}

class InMemoryProcessedSignalStore : ProcessedSignalStore {
    private val _states = MutableStateFlow<ProcessedSignalState>(ProcessedSignalState.Idle)
    override val states: StateFlow<ProcessedSignalState> = _states

    override fun start(sessionId: Long) {
        _states.value = ProcessedSignalState.Collecting(sessionId)
    }

    override fun publish(window: ProcessedSignalWindow) {
        _states.value = ProcessedSignalState.WindowReady(window)
    }

    override fun insufficient(sessionId: Long, observedSamples: Int) {
        _states.value = ProcessedSignalState.InsufficientData(sessionId, observedSamples)
    }

    override fun stop() {
        _states.value = ProcessedSignalState.Stopped
    }

    override fun clear() {
        _states.value = ProcessedSignalState.Idle
    }
}

object ProcessedSignalStoreProvider {
    val store: ProcessedSignalStore = InMemoryProcessedSignalStore()
}
