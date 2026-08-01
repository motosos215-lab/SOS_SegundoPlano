package com.example.sos_segundoplano.data.rules

import com.example.sos_segundoplano.domain.rules.RiskAssessment
import com.example.sos_segundoplano.domain.rules.RiskAssessmentState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface RiskAssessmentStore {
    val states: StateFlow<RiskAssessmentState>
    fun start(sessionId: Long)
    fun publish(assessment: RiskAssessment)
    fun insufficient(sessionId: Long)
    fun stop()
    fun clear()
}

class InMemoryRiskAssessmentStore : RiskAssessmentStore {
    private val _states = MutableStateFlow<RiskAssessmentState>(RiskAssessmentState.Idle)
    override val states: StateFlow<RiskAssessmentState> = _states

    override fun start(sessionId: Long) {
        _states.value = RiskAssessmentState.Collecting(sessionId)
    }

    override fun publish(assessment: RiskAssessment) {
        _states.value = RiskAssessmentState.AssessmentReady(assessment)
    }

    override fun insufficient(sessionId: Long) {
        _states.value = RiskAssessmentState.InsufficientData(sessionId)
    }

    override fun stop() {
        _states.value = RiskAssessmentState.Stopped
    }

    override fun clear() {
        _states.value = RiskAssessmentState.Idle
    }
}

object RiskAssessmentStoreProvider {
    val store: RiskAssessmentStore = InMemoryRiskAssessmentStore()
}
