package com.example.sos_segundoplano.data.rules

import com.example.sos_segundoplano.domain.rules.RiskAssessment
import com.example.sos_segundoplano.domain.rules.RiskAssessmentState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

interface RiskAssessmentStore {
    val states: StateFlow<RiskAssessmentState>
    val assessments: SharedFlow<RiskAssessment>
    val droppedAssessments: Long
    fun start(sessionId: Long)
    fun publish(assessment: RiskAssessment)
    fun insufficient(sessionId: Long)
    fun stop()
    fun clear()
}

class InMemoryRiskAssessmentStore(
    assessmentBufferCapacity: Int = 64
) : RiskAssessmentStore {
    init {
        require(assessmentBufferCapacity > 0)
    }

    private val capacity = assessmentBufferCapacity
    private val emitted = AtomicLong(0L)
    private val dropped = AtomicLong(0L)
    private val _states = MutableStateFlow<RiskAssessmentState>(RiskAssessmentState.Idle)
    private val _assessments = MutableSharedFlow<RiskAssessment>(
        replay = 0,
        extraBufferCapacity = assessmentBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val states: StateFlow<RiskAssessmentState> = _states
    override val assessments: SharedFlow<RiskAssessment> = _assessments
    override val droppedAssessments: Long get() = dropped.get()

    override fun start(sessionId: Long) {
        _states.value = RiskAssessmentState.Collecting(sessionId)
    }

    override fun publish(assessment: RiskAssessment) {
        _states.value = RiskAssessmentState.AssessmentReady(assessment)
        if (_assessments.subscriptionCount.value == 0 && emitted.incrementAndGet() > capacity) {
            dropped.incrementAndGet()
        }
        if (!_assessments.tryEmit(assessment)) dropped.incrementAndGet()
    }

    override fun insufficient(sessionId: Long) {
        _states.value = RiskAssessmentState.InsufficientData(sessionId)
    }

    override fun stop() {
        _states.value = RiskAssessmentState.Stopped
    }

    override fun clear() {
        _states.value = RiskAssessmentState.Idle
        emitted.set(0L)
        dropped.set(0L)
    }
}

object RiskAssessmentStoreProvider {
    val store: RiskAssessmentStore = InMemoryRiskAssessmentStore()
}
