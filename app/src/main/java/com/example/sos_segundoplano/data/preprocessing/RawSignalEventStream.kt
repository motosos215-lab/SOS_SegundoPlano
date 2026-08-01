package com.example.sos_segundoplano.data.preprocessing

import com.example.sos_segundoplano.domain.preprocessing.PreprocessingConfig
import com.example.sos_segundoplano.domain.preprocessing.RawSignalEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import java.util.concurrent.atomic.AtomicLong

interface RawSignalEventSink {
    val events: SharedFlow<RawSignalEvent>
    val droppedEvents: Long
    fun tryEmit(event: RawSignalEvent)
}

class RawSignalEventStream(
    config: PreprocessingConfig = PreprocessingConfig()
) : RawSignalEventSink {
    private val capacity = config.rawEventBufferCapacity
    private val emitted = AtomicLong(0L)
    private val dropped = AtomicLong(0L)
    private val _events = MutableSharedFlow<RawSignalEvent>(
        replay = 0,
        extraBufferCapacity = config.rawEventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<RawSignalEvent> = _events
    override val droppedEvents: Long get() = dropped.get()

    override fun tryEmit(event: RawSignalEvent) {
        if (_events.subscriptionCount.value == 0 && emitted.incrementAndGet() > capacity) {
            dropped.incrementAndGet()
        }
        if (!_events.tryEmit(event)) dropped.incrementAndGet()
    }
}
