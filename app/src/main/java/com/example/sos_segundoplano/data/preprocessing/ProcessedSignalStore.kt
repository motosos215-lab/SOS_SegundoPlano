package com.example.sos_segundoplano.data.preprocessing

import com.example.sos_segundoplano.domain.preprocessing.ProcessedSignalState
import com.example.sos_segundoplano.domain.preprocessing.ProcessedSignalWindow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

interface ProcessedSignalStore {
    val states: StateFlow<ProcessedSignalState>
    val windows: SharedFlow<ProcessedSignalWindow>
    val droppedWindows: Long
    fun start(sessionId: Long)
    fun publish(window: ProcessedSignalWindow)
    fun insufficient(sessionId: Long, observedSamples: Int)
    fun stop()
    fun clear()
}

class InMemoryProcessedSignalStore(
    windowBufferCapacity: Int = 64
) : ProcessedSignalStore {
    init {
        require(windowBufferCapacity > 0)
    }

    private val capacity = windowBufferCapacity
    private val emitted = AtomicLong(0L)
    private val _states = MutableStateFlow<ProcessedSignalState>(ProcessedSignalState.Idle)
    private val dropped = AtomicLong(0L)
    private val _windows = MutableSharedFlow<ProcessedSignalWindow>(
        replay = 0,
        extraBufferCapacity = windowBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val states: StateFlow<ProcessedSignalState> = _states
    override val windows: SharedFlow<ProcessedSignalWindow> = _windows
    override val droppedWindows: Long get() = dropped.get()

    override fun start(sessionId: Long) {
        _states.value = ProcessedSignalState.Collecting(sessionId)
    }

    override fun publish(window: ProcessedSignalWindow) {
        _states.value = ProcessedSignalState.WindowReady(window)
        if (_windows.subscriptionCount.value == 0 && emitted.incrementAndGet() > capacity) {
            dropped.incrementAndGet()
        }
        if (!_windows.tryEmit(window)) dropped.incrementAndGet()
    }

    override fun insufficient(sessionId: Long, observedSamples: Int) {
        _states.value = ProcessedSignalState.InsufficientData(sessionId, observedSamples)
    }

    override fun stop() {
        _states.value = ProcessedSignalState.Stopped
    }

    override fun clear() {
        _states.value = ProcessedSignalState.Idle
        emitted.set(0L)
        dropped.set(0L)
    }
}

object ProcessedSignalStoreProvider {
    val store: ProcessedSignalStore = InMemoryProcessedSignalStore()
}
