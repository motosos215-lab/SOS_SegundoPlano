package com.example.sos_segundoplano.data.preprocessing

import com.example.sos_segundoplano.domain.preprocessing.PreprocessingConfig
import com.example.sos_segundoplano.domain.preprocessing.PreprocessingPipeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class SignalPreprocessingCoordinator(
    private val rawEvents: RawSignalEventSink,
    private val processedStore: ProcessedSignalStore = ProcessedSignalStoreProvider.store,
    private val config: PreprocessingConfig = PreprocessingConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val sessionIdGenerator: () -> Long = { nextSessionId.incrementAndGet() }
) {
    private val pipeline = PreprocessingPipeline(config)
    private var scope: CoroutineScope? = null
    private var collector: Job? = null
    private var started = false
    private var sessionId = 0L
    private var observedSamples = 0
    private val processingLock = Any()

    fun start() {
        if (started) return
        started = true
        sessionId = sessionIdGenerator()
        observedSamples = 0
        pipeline.reset()
        processedStore.clear()
        processedStore.start(sessionId)
        val nextScope = CoroutineScope(SupervisorJob() + dispatcher)
        scope = nextScope
        collector = nextScope.launch {
            rawEvents.events.collect { event ->
                if (!started) return@collect
                synchronized(processingLock) {
                    if (!started) return@synchronized
                    observedSamples++
                    val windows = pipeline.accept(sessionId, event, rawEvents.droppedEvents)
                    windows.forEach(processedStore::publish)
                }
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        synchronized(processingLock) {
            pipeline.stop(sessionId, rawEvents.droppedEvents).forEach(processedStore::publish)
            if (observedSamples < config.minimumSamplesPerWindow) {
                processedStore.insufficient(sessionId, observedSamples)
            }
        }
        collector?.cancel()
        scope?.cancel()
        collector = null
        scope = null
        processedStore.stop()
    }

    companion object {
        private val nextSessionId = AtomicLong(0L)
    }
}
