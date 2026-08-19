package com.example.sos_segundoplano.data.rules

import com.example.sos_segundoplano.data.ml.AccidentMlEvaluator
import com.example.sos_segundoplano.data.preprocessing.ProcessedSignalStore
import com.example.sos_segundoplano.data.preprocessing.ProcessedSignalStoreProvider
import com.example.sos_segundoplano.domain.preprocessing.ProcessedSignalState
import com.example.sos_segundoplano.domain.rules.RuleEngine
import com.example.sos_segundoplano.domain.rules.RuleEngineConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RuleEngineCoordinator(
    private val processedSignalStore: ProcessedSignalStore = ProcessedSignalStoreProvider.store,
    private val riskAssessmentStore: RiskAssessmentStore = RiskAssessmentStoreProvider.store,
    private val config: RuleEngineConfig = RuleEngineConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val engine: RuleEngine = RuleEngine(config),
    private val mlEvaluator: AccidentMlEvaluator? = null
) {
    private var scope: CoroutineScope? = null
    private var collector: Job? = null
    private var started = false
    private var sessionId: Long? = null
    private val processingLock = Any()

    fun start(expectedSessionId: Long? = null) {
        if (started) return
        started = true
        sessionId = expectedSessionId
        engine.reset()
        mlEvaluator?.reset()
        riskAssessmentStore.clear()
        expectedSessionId?.let(riskAssessmentStore::start)
        val nextScope = CoroutineScope(SupervisorJob() + dispatcher)
        scope = nextScope
        collector = nextScope.launch {
            processedSignalStore.windows.collect { window ->
                if (!started) return@collect
                if (sessionId == null) {
                    sessionId = window.sessionId
                    riskAssessmentStore.start(window.sessionId)
                }
                if (sessionId != window.sessionId) return@collect
                synchronized(processingLock) {
                    engine.evaluate(window, processedSignalStore.droppedWindows)
                        ?.let { assessment -> mlEvaluator?.evaluate(window, assessment) ?: assessment }
                        ?.let(riskAssessmentStore::publish)
                }
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        val state = processedSignalStore.states.value
        if (state is ProcessedSignalState.WindowReady && (sessionId == null || sessionId == state.window.sessionId)) {
            synchronized(processingLock) {
                engine.evaluate(state.window, processedSignalStore.droppedWindows)
                    ?.let { assessment -> mlEvaluator?.evaluate(state.window, assessment) ?: assessment }
                    ?.let(riskAssessmentStore::publish)
            }
        }
        collector?.cancel()
        scope?.cancel()
        collector = null
        scope = null
        riskAssessmentStore.stop()
    }

    fun reset() {
        stop()
        engine.reset()
        mlEvaluator?.reset()
        sessionId = null
        riskAssessmentStore.clear()
    }
}
