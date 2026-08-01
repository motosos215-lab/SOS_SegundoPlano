package com.example.sos_segundoplano.data.validation

import com.example.sos_segundoplano.data.rules.RiskAssessmentStore
import com.example.sos_segundoplano.data.rules.RiskAssessmentStoreProvider
import com.example.sos_segundoplano.domain.rules.MovementContinuityState
import com.example.sos_segundoplano.domain.rules.RiskAssessment
import com.example.sos_segundoplano.domain.rules.RiskAssessmentState
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.rules.RuleId
import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.AlertPayloadSummary
import com.example.sos_segundoplano.domain.validation.AlertPriority
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationConfig
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.validation.MinorEvent
import com.example.sos_segundoplano.domain.validation.MinorEventType
import com.example.sos_segundoplano.domain.validation.MonotonicClock
import com.example.sos_segundoplano.domain.validation.UserResponseSource
import com.example.sos_segundoplano.domain.validation.UserValidationAction
import com.example.sos_segundoplano.domain.validation.UserValidationResponse
import com.example.sos_segundoplano.domain.validation.ValidationCounters
import com.example.sos_segundoplano.domain.validation.ValidationDecisionReason
import com.example.sos_segundoplano.domain.validation.ValidationEvidence
import com.example.sos_segundoplano.domain.validation.ValidationMetadata
import com.example.sos_segundoplano.domain.validation.ValidationOrigin
import com.example.sos_segundoplano.domain.validation.activeCountdown
import com.example.sos_segundoplano.domain.validation.identifier
import com.example.sos_segundoplano.domain.validation.isNotTriggered
import com.example.sos_segundoplano.domain.validation.isTriggered
import com.example.sos_segundoplano.domain.validation.outcome
import com.example.sos_segundoplano.domain.validation.relevantOutcomeSummaries
import com.example.sos_segundoplano.domain.validation.validationEvidence
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface FalsePositiveValidationNotifier {
    fun onValidationStateChanged(state: FalsePositiveValidationState)
}

object NoOpFalsePositiveValidationNotifier : FalsePositiveValidationNotifier {
    override fun onValidationStateChanged(state: FalsePositiveValidationState) = Unit
}

class FalsePositiveValidationCoordinator(
    private val riskAssessmentStore: RiskAssessmentStore = RiskAssessmentStoreProvider.store,
    private val validationStore: FalsePositiveValidationStore = FalsePositiveValidationStoreProvider.store,
    private val minorEventStore: MinorEventStore = IncidentStoreProvider.minorEvents,
    private val incidentStore: LocalIncidentStore = IncidentStoreProvider.incidents,
    private val dispatchRequestStore: AlertDispatchRequestStore = IncidentStoreProvider.dispatchRequests,
    private val config: FalsePositiveValidationConfig = FalsePositiveValidationConfig(),
    private val clock: MonotonicClock = AndroidMonotonicClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nextMinorEventId: () -> Long = { IncidentStoreProvider.minorEventIds.incrementAndGet() },
    private val nextIncidentId: () -> Long = { IncidentStoreProvider.incidentIds.incrementAndGet() },
    private val nextDispatchRequestId: () -> Long = { IncidentStoreProvider.dispatchRequestIds.incrementAndGet() },
    private var notifier: FalsePositiveValidationNotifier = NoOpFalsePositiveValidationNotifier,
    private val externalScope: CoroutineScope? = null
) {
    private var scope: CoroutineScope? = null
    private var collector: Job? = null
    private var countdown: Job? = null
    private var deferredTransition: Job? = null
    private var started = false
    private var activeSessionId: Long? = null
    private var lastEndNanos: Long? = null
    private var ownsScope = false
    private var counters = ValidationCounters()
    private val mutex = Mutex()
    private val processedAssessments = BoundedIdSet(config.assessmentBufferCapacity)
    private val terminalAssessments = BoundedIdSet(config.assessmentBufferCapacity)
    private val processedResponses = BoundedStringSet(config.assessmentBufferCapacity)

    val validationCounters: ValidationCounters get() = counters

    fun setNotifier(nextNotifier: FalsePositiveValidationNotifier) {
        notifier = nextNotifier
        notifier.onValidationStateChanged(validationStore.states.value)
    }

    fun start(expectedSessionId: Long? = null) {
        if (started) return
        started = true
        clearSessionMemory(clearStores = true)
        activeSessionId = expectedSessionId
        publish(FalsePositiveValidationState.Monitoring(expectedSessionId, now(), config.policyVersion))
        val nextScope = externalScope ?: CoroutineScope(SupervisorJob() + dispatcher)
        ownsScope = externalScope == null
        scope = nextScope
        collector = nextScope.launch {
            riskAssessmentStore.assessments.collect { assessment ->
                handleAssessment(assessment)
            }
        }
    }

    fun processLatestAssessmentBeforeStop() {
        val assessment = (riskAssessmentStore.states.value as? RiskAssessmentState.AssessmentReady)?.assessment ?: return
        scope?.launch { handleAssessment(assessment, allowEscalation = false) }
    }

    fun confirmSafe(sessionId: Long, assessmentId: Long, source: UserResponseSource, responseId: String) {
        submitResponse(UserValidationResponse(PROTOCOL_VERSION, UserValidationAction.ConfirmSafe, sessionId, assessmentId, responseId, source))
    }

    fun requestHelp(sessionId: Long, assessmentId: Long, source: UserResponseSource, responseId: String) {
        submitResponse(UserValidationResponse(PROTOCOL_VERSION, UserValidationAction.RequestHelp, sessionId, assessmentId, responseId, source))
    }

    fun submitResponse(response: UserValidationResponse) {
        scope?.launch { handleResponse(response) }
    }

    fun stop() {
        if (!started) return
        scope?.launch { stopAndJoin() }
    }

    suspend fun stopAndJoin() {
        if (!started && scope == null) return
        val latest = (riskAssessmentStore.states.value as? RiskAssessmentState.AssessmentReady)?.assessment
        if (started) latest?.let { handleAssessment(it, allowEscalation = false) }
        val countdownJob: Job?
        val transitionJob: Job?
        val collectorJob: Job?
        val currentScope: CoroutineScope?
        mutex.withLock {
            started = false
            publish(FalsePositiveValidationState.Stopped(activeSessionId, now(), config.policyVersion))
            activeSessionId = null
            countdownJob = countdown
            transitionJob = deferredTransition
            collectorJob = collector
            currentScope = scope
            countdown = null
            deferredTransition = null
            collector = null
            scope = null
        }
        countdownJob?.cancelAndJoin()
        transitionJob?.cancelAndJoin()
        collectorJob?.cancelAndJoin()
        if (ownsScope) currentScope?.cancel()
        ownsScope = false
    }

    fun reset() {
        countdown?.cancel()
        deferredTransition?.cancel()
        collector?.cancel()
        if (ownsScope) scope?.cancel()
        countdown = null
        deferredTransition = null
        collector = null
        scope = null
        ownsScope = false
        started = false
        activeSessionId = null
        clearSessionMemory(clearStores = true)
        validationStore.clear()
    }

    suspend fun resetAndJoin() {
        val countdownJob = countdown
        val transitionJob = deferredTransition
        val collectorJob = collector
        val currentScope = scope
        countdown = null
        deferredTransition = null
        collector = null
        scope = null
        started = false
        activeSessionId = null
        clearSessionMemory(clearStores = true)
        validationStore.clear()
        countdownJob?.cancelAndJoin()
        transitionJob?.cancelAndJoin()
        collectorJob?.cancelAndJoin()
        if (ownsScope) currentScope?.cancel()
        ownsScope = false
    }

    private suspend fun handleAssessment(assessment: RiskAssessment, allowEscalation: Boolean = true) = mutex.withLock {
        if (!started) return@withLock
        if (activeSessionId == null) activeSessionId = assessment.sessionId
        if (activeSessionId != assessment.sessionId) return@withLock
        val key = assessment.identifier()
        if (!processedAssessments.add(key)) {
            counters = counters.copy(duplicateAssessments = counters.duplicateAssessments + 1)
            return@withLock
        }
        if (isLate(assessment)) {
            counters = counters.copy(lateAssessments = counters.lateAssessments + 1)
            return@withLock
        }
        lastEndNanos = maxOf(lastEndNanos ?: assessment.endNanos, assessment.endNanos)
        if (terminalAssessments.contains(key)) return@withLock
        val currentCountdown = validationStore.states.value.activeCountdown
        if (currentCountdown != null) return@withLock

        val evidence = assessment.validationEvidence()
        if (allowEscalation && isCritical(assessment, explicitHelp = false)) {
            createIncidentLocked(assessment, IncidentCause.CriticalPhysicalEvent, ValidationDecisionReason.CriticalPhysicalEvent, ValidationOrigin.System, immediate = true)
            return@withLock
        }
        if (isIsolatedBump(assessment, evidence)) {
            recordMinorEventLocked(assessment, evidence)
            return@withLock
        }
        if (isHarshBrakingSuppressed(assessment, evidence)) {
            publish(FalsePositiveValidationState.SuppressedFalsePositive(assessment, metadata(assessment, ValidationDecisionReason.HarshBrakingWithContinuedMovement, ValidationOrigin.System), evidence))
            return@withLock
        }
        if (allowEscalation && requiresCountdown(assessment, evidence)) {
            publish(FalsePositiveValidationState.CandidateDetected(assessment, metadata(assessment, ValidationDecisionReason.CandidatePhysicalRisk, ValidationOrigin.System), evidence))
            startCountdownLocked(assessment, evidence)
        }
    }

    private suspend fun handleResponse(response: UserValidationResponse) = mutex.withLock {
        if (!started || response.protocolVersion != PROTOCOL_VERSION) {
            counters = counters.copy(invalidResponses = counters.invalidResponses + 1)
            return@withLock
        }
        if (!processedResponses.add(response.responseId)) {
            counters = counters.copy(duplicateResponses = counters.duplicateResponses + 1)
            return@withLock
        }
        val countdownState = validationStore.states.value.activeCountdown
        if (countdownState == null || countdownState.assessment.sessionId != response.sessionId || countdownState.assessment.assessmentId != response.assessmentId) {
            counters = counters.copy(ignoredResponses = counters.ignoredResponses + 1)
            return@withLock
        }
        val assessment = countdownState.assessment
        when (response.action) {
            UserValidationAction.ConfirmSafe -> {
                terminalAssessments.add(assessment.identifier())
                cancelCountdownLocked()
                val safe = FalsePositiveValidationState.SafeConfirmed(metadata(assessment, ValidationDecisionReason.UserConfirmedSafe, response.source.toOrigin()), response.responseId)
                publish(safe)
                returnToMonitoringLater(safe)
            }
            UserValidationAction.RequestHelp -> {
                publish(FalsePositiveValidationState.HelpRequested(metadata(assessment, ValidationDecisionReason.UserRequestedHelp, response.source.toOrigin()), response.responseId))
                createIncidentLocked(assessment, IncidentCause.UserRequestedHelp, ValidationDecisionReason.UserRequestedHelp, response.source.toOrigin(), immediate = false)
            }
        }
    }

    private fun startCountdownLocked(assessment: RiskAssessment, evidence: ValidationEvidence) {
        cancelCountdownLocked()
        val startedAt = now()
        val deadline = startedAt + config.countdownDurationNanos
        publish(
            FalsePositiveValidationState.CountdownActive(
                assessment = assessment,
                metadata = metadata(assessment, ValidationDecisionReason.CandidatePhysicalRisk, ValidationOrigin.System, startedAt),
                evidence = evidence,
                startedAtElapsedRealtimeNanos = startedAt,
                deadlineElapsedRealtimeNanos = deadline,
                remainingNanos = config.countdownDurationNanos
            )
        )
        val currentScope = scope ?: return
        countdown = currentScope.launch {
            while (true) {
                delay(config.visualUpdateIntervalNanos / NANOS_PER_MILLI)
                mutex.withLock {
                    val state = validationStore.states.value.activeCountdown ?: return@launch
                    if (state.assessment.assessmentId != assessment.assessmentId || state.assessment.sessionId != assessment.sessionId) return@launch
                    val remaining = (deadline - now()).coerceAtLeast(0L)
                    if (remaining <= 0L) {
                        counters = counters.copy(timeouts = counters.timeouts + 1)
                        createIncidentLocked(assessment, IncidentCause.Timeout, ValidationDecisionReason.Timeout, ValidationOrigin.Timeout, immediate = false)
                        return@launch
                    }
                    publish(state.copy(remainingNanos = remaining))
                }
            }
        }
    }

    private fun recordMinorEventLocked(assessment: RiskAssessment, evidence: ValidationEvidence) {
        val event = minorEventStore.add(
            MinorEvent(
                eventId = nextMinorEventId(),
                sessionId = assessment.sessionId,
                assessmentId = assessment.assessmentId,
                windowId = assessment.windowId,
                type = MinorEventType.Bump,
                createdAtElapsedRealtimeNanos = now(),
                score = assessment.score,
                confidence = assessment.confidence,
                evidence = evidence,
                policyVersion = config.policyVersion
            )
        )
        publish(FalsePositiveValidationState.MinorEventRecorded(event, metadata(assessment, ValidationDecisionReason.IsolatedBump, ValidationOrigin.System)))
    }

    private fun createIncidentLocked(
        assessment: RiskAssessment,
        cause: IncidentCause,
        reason: ValidationDecisionReason,
        origin: ValidationOrigin,
        immediate: Boolean
    ) {
        val key = assessment.identifier()
        if (!terminalAssessments.add(key)) return
        cancelCountdownLocked()
        val createdAt = now()
        val incident = incidentStore.add(
            LocalIncident(
                incidentId = nextIncidentId(),
                sessionId = assessment.sessionId,
                assessmentId = assessment.assessmentId,
                windowId = assessment.windowId,
                createdAtElapsedRealtimeNanos = createdAt,
                cause = cause,
                score = assessment.score,
                riskLevel = assessment.riskLevel,
                confidence = assessment.confidence,
                relevantOutcomes = assessment.relevantOutcomeSummaries(),
                ruleSetVersion = assessment.ruleSetVersion,
                validationPolicyVersion = config.policyVersion,
                gpsQuality = assessment.gpsQuality.status
            )
        )
        val request = dispatchRequestStore.add(
            AlertDispatchRequest(
                requestId = nextDispatchRequestId(),
                incidentId = incident.incidentId,
                sessionId = assessment.sessionId,
                assessmentId = assessment.assessmentId,
                priority = if (immediate) AlertPriority.Critical else AlertPriority.High,
                reason = cause,
                createdAtElapsedRealtimeNanos = createdAt,
                score = assessment.score,
                confidence = assessment.confidence,
                payload = AlertPayloadSummary(assessment.sessionId, assessment.assessmentId, incident.incidentId, assessment.score, assessment.riskLevel, cause, config.policyVersion)
            )
        )
        val meta = metadata(assessment, reason, origin, createdAt)
        if (immediate) {
            publish(FalsePositiveValidationState.ImmediateAlertRequested(incident, request, meta))
        } else {
            publish(FalsePositiveValidationState.IncidentGenerated(incident, request, meta))
        }
    }

    private fun returnToMonitoringLater(expectedState: FalsePositiveValidationState) {
        val currentScope = scope ?: return
        deferredTransition?.cancel()
        deferredTransition = currentScope.launch {
            delay(config.visualUpdateIntervalNanos / NANOS_PER_MILLI)
            mutex.withLock {
                if (started && validationStore.states.value == expectedState) {
                    publish(FalsePositiveValidationState.Monitoring(activeSessionId, now(), config.policyVersion))
                }
                deferredTransition = null
            }
        }
    }

    private fun isIsolatedBump(assessment: RiskAssessment, evidence: ValidationEvidence): Boolean {
        val impact = assessment.outcome(RuleId.Impact)
        val fall = assessment.outcome(RuleId.Fall)
        val immobility = assessment.outcome(RuleId.Immobility)
        val orientation = assessment.outcome(RuleId.OrientationChange)
        return impact.isTriggered() &&
            (impact?.severity ?: 0.0) >= config.bumpMinimumImpactSeverity &&
            fall.isNotTriggered() &&
            immobility.isNotTriggered() &&
            (!orientation.isTriggered() || (orientation?.severity ?: 0.0) < config.criticalOrientationSeverity) &&
            evidence.movementContinuity == MovementContinuityState.Continuing &&
            assessment.riskLevel != RiskLevel.High &&
            (assessment.score ?: return false) <= config.bumpMaximumScore &&
            assessment.confidence >= config.minimumConfidence
    }

    private fun isHarshBrakingSuppressed(assessment: RiskAssessment, evidence: ValidationEvidence): Boolean {
        val braking = assessment.outcome(RuleId.HarshBraking)
        val fall = assessment.outcome(RuleId.Fall)
        val immobility = assessment.outcome(RuleId.Immobility)
        return braking.isTriggered() &&
            evidence.movementContinuity == MovementContinuityState.Continuing &&
            (assessment.endNanos - (braking?.endNanos ?: assessment.endNanos)) <= config.brakingContinuityMaximumGapNanos &&
            fall.isNotTriggered() &&
            immobility.isNotTriggered() &&
            assessment.riskLevel != RiskLevel.High &&
            assessment.confidence >= config.minimumConfidence
    }

    private fun requiresCountdown(assessment: RiskAssessment, evidence: ValidationEvidence): Boolean {
        val score = assessment.score ?: return false
        if (score < config.minimumValidationScore || assessment.confidence < config.minimumConfidence) return false
        val fall = assessment.outcome(RuleId.Fall)
        val impact = assessment.outcome(RuleId.Impact)
        val immobility = assessment.outcome(RuleId.Immobility)
        return fall.isTriggered() || (impact.isTriggered() && immobility.isTriggered() && (immobility?.severity ?: 0.0) >= config.immobilityCountdownMinimumSeverity)
    }

    private fun isCritical(assessment: RiskAssessment, explicitHelp: Boolean): Boolean {
        val score = assessment.score ?: return false
        if (score < config.criticalScore || assessment.confidence < config.criticalConfidence) return false
        val fall = assessment.outcome(RuleId.Fall)
        if (!fall.isTriggered() || (fall?.severity ?: 0.0) < config.criticalFallSeverity) return false
        val impact = assessment.outcome(RuleId.Impact)
        val immobility = assessment.outcome(RuleId.Immobility)
        val orientation = assessment.outcome(RuleId.OrientationChange)
        return explicitHelp ||
            (impact.isTriggered() && (impact?.severity ?: 0.0) >= config.criticalImpactSeverity) ||
            (immobility.isTriggered() && (immobility?.severity ?: 0.0) >= config.immobilityCountdownMinimumSeverity) ||
            (orientation.isTriggered() && (orientation?.severity ?: 0.0) >= config.criticalOrientationSeverity)
    }

    private fun isLate(assessment: RiskAssessment): Boolean {
        val lastEnd = lastEndNanos ?: return false
        return assessment.endNanos + config.lateAssessmentToleranceNanos < lastEnd
    }

    private fun cancelCountdownLocked() {
        countdown?.cancel()
        countdown = null
    }

    private fun clearSessionMemory(clearStores: Boolean) {
        cancelCountdownLocked()
        processedAssessments.clear()
        terminalAssessments.clear()
        processedResponses.clear()
        counters = ValidationCounters()
        lastEndNanos = null
        if (clearStores) {
            minorEventStore.clear()
            incidentStore.clear()
            dispatchRequestStore.clear()
        }
    }

    private fun metadata(
        assessment: RiskAssessment,
        reason: ValidationDecisionReason,
        origin: ValidationOrigin,
        timestamp: Long = now()
    ): ValidationMetadata = ValidationMetadata(
        sessionId = assessment.sessionId,
        assessmentId = assessment.assessmentId,
        windowId = assessment.windowId,
        timestampElapsedRealtimeNanos = timestamp,
        reason = reason,
        score = assessment.score,
        confidence = assessment.confidence,
        origin = origin,
        policyVersion = config.policyVersion
    )

    private fun publish(state: FalsePositiveValidationState) {
        validationStore.publish(state)
        notifier.onValidationStateChanged(state)
    }

    private fun now(): Long = clock.elapsedRealtimeNanos()

    private fun UserResponseSource.toOrigin(): ValidationOrigin = when (this) {
        UserResponseSource.Mobile -> ValidationOrigin.Mobile
        UserResponseSource.Wear -> ValidationOrigin.Wear
        UserResponseSource.ForegroundNotification -> ValidationOrigin.ForegroundNotification
    }

    private class BoundedIdSet(private val capacity: Int) {
        private val ids = ArrayDeque<Any>()
        private val set = LinkedHashSet<Any>()
        fun add(id: Any): Boolean {
            if (!set.add(id)) return false
            ids.addLast(id)
            if (ids.size > capacity) set.remove(ids.removeFirst())
            return true
        }
        fun contains(id: Any): Boolean = set.contains(id)
        fun clear() {
            ids.clear()
            set.clear()
        }
    }

    private class BoundedStringSet(private val capacity: Int) {
        private val ids = ArrayDeque<String>()
        private val set = LinkedHashSet<String>()
        fun add(id: String): Boolean {
            if (id.isBlank()) return false
            if (!set.add(id)) return false
            ids.addLast(id)
            if (ids.size > capacity) set.remove(ids.removeFirst())
            return true
        }
        fun clear() {
            ids.clear()
            set.clear()
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

object FalsePositiveValidationCoordinatorProvider {
    val coordinator: FalsePositiveValidationCoordinator = FalsePositiveValidationCoordinator()

    fun setNotifier(notifier: FalsePositiveValidationNotifier) {
        coordinator.setNotifier(notifier)
    }
}
