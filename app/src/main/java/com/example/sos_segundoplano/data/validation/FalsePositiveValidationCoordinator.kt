package com.example.sos_segundoplano.data.validation

import com.example.sos_segundoplano.data.rules.RiskAssessmentStore
import com.example.sos_segundoplano.data.rules.RiskAssessmentStoreProvider
import com.example.sos_segundoplano.data.trip.TripSessionStore
import com.example.sos_segundoplano.data.trip.TripSessionStoreProvider
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.data.remote.incident.IncidentRemoteCreator
import com.example.sos_segundoplano.data.remote.incident.NoOpIncidentRemoteCreator
import com.example.sos_segundoplano.data.remote.incident.AutomaticSosAlertCreator
import com.example.sos_segundoplano.data.remote.incident.NoOpAutomaticSosAlertCreator
import com.example.sos_segundoplano.domain.offline.NoOpOfflineEventSink
import com.example.sos_segundoplano.domain.offline.OfflineEventSink
import com.example.sos_segundoplano.domain.offline.AutomaticSosBundleClaimResult
import com.example.sos_segundoplano.domain.offline.AutomaticSosRemoteReceipt
import com.example.sos_segundoplano.domain.offline.OfflineQueueTransitionResult
import com.example.sos_segundoplano.domain.offline.isPersisted
import com.example.sos_segundoplano.domain.rules.MovementContinuityState
import com.example.sos_segundoplano.domain.rules.RiskAssessment
import com.example.sos_segundoplano.domain.rules.RiskAssessmentState
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.rules.RuleId
import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.AlertPayloadSummary
import com.example.sos_segundoplano.domain.validation.AlertPriority
import com.example.sos_segundoplano.domain.validation.AssessmentIdentifier
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationConfig
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

interface FalsePositiveValidationNotifier {
    fun onValidationStateChanged(state: FalsePositiveValidationState)
}

object NoOpFalsePositiveValidationNotifier : FalsePositiveValidationNotifier {
    override fun onValidationStateChanged(state: FalsePositiveValidationState) = Unit
}

interface FalsePositiveValidationLogger {
    fun countdownExpired()
    fun duplicateIncidentAttempt()
}

object NoOpFalsePositiveValidationLogger : FalsePositiveValidationLogger {
    override fun countdownExpired() = Unit
    override fun duplicateIncidentAttempt() = Unit
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
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private var notifier: FalsePositiveValidationNotifier = NoOpFalsePositiveValidationNotifier,
    private var offlineEventSink: OfflineEventSink = NoOpOfflineEventSink,
    private var incidentRemoteCreator: IncidentRemoteCreator = NoOpIncidentRemoteCreator,
    private var automaticSosAlertCreator: AutomaticSosAlertCreator = NoOpAutomaticSosAlertCreator,
    private var logger: FalsePositiveValidationLogger = NoOpFalsePositiveValidationLogger,
    private val tripSessionStore: TripSessionStore = TripSessionStoreProvider.store,
    private val externalScope: CoroutineScope? = null
) {
    private var scope: CoroutineScope? = null
    private var collector: Job? = null
    private var countdown: Job? = null
    private var deferredTransition: Job? = null
    private var started = false
    private var activeSessionId: Long? = null
    private var lastEndNanos: Long? = null
    private var suppressEscalationUntilEndNanos: Long? = null
    private var ownsScope = false
    private var counters = ValidationCounters()
    private val mutex = Mutex()
    private val processedAssessments = BoundedIdSet(config.assessmentBufferCapacity)
    private val terminalAssessments = BoundedIdSet(config.assessmentBufferCapacity)
    private val pendingPersistenceAssessments = BoundedIdSet(config.assessmentBufferCapacity)
    private val persistenceDecisions = BoundedDecisionMap(config.assessmentBufferCapacity)
    private val persistenceRetryJobs = LinkedHashMap<AssessmentIdentifier, Job>()
    private val persistenceRetryAttempts = LinkedHashMap<AssessmentIdentifier, Int>()
    private val processedResponses = BoundedStringSet(config.assessmentBufferCapacity)
    private val remoteIncidentAttempts = BoundedIdSet(config.assessmentBufferCapacity)

    val validationCounters: ValidationCounters get() = counters

    fun setNotifier(nextNotifier: FalsePositiveValidationNotifier) {
        notifier = nextNotifier
        notifier.onValidationStateChanged(validationStore.states.value)
    }

    fun setOfflineEventSink(nextSink: OfflineEventSink) {
        offlineEventSink = nextSink
    }

    fun setIncidentRemoteCreator(nextCreator: IncidentRemoteCreator) {
        incidentRemoteCreator = nextCreator
    }

    fun setAutomaticSosAlertCreator(nextCreator: AutomaticSosAlertCreator) {
        automaticSosAlertCreator = nextCreator
    }

    fun setLogger(nextLogger: FalsePositiveValidationLogger) {
        logger = nextLogger
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

    /**
     * Debug-only entry point used by the debug accident broadcast. It submits the synthetic
     * assessment directly to the running validation coordinator so the test trigger cannot be
     * lost during the small SharedFlow subscription race at monitoring startup.
     * Production sensor assessments continue to arrive through RiskAssessmentStore.
     */
    fun submitAssessmentForDebug(assessment: RiskAssessment) {
        scope?.launch { handleAssessment(assessment, ignoreSuppression = true) }
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
        cancelPersistenceRetries()
        collectorJob?.cancelAndJoin()
        if (ownsScope) currentScope?.cancel()
        ownsScope = false
    }

    fun reset() {
        countdown?.cancel()
        deferredTransition?.cancel()
        persistenceRetryJobs.values.forEach { it.cancel() }
        collector?.cancel()
        if (ownsScope) scope?.cancel()
        countdown = null
        deferredTransition = null
        collector = null
        persistenceRetryJobs.clear()
        persistenceRetryAttempts.clear()
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
        cancelPersistenceRetries()
        collectorJob?.cancelAndJoin()
        if (ownsScope) currentScope?.cancel()
        ownsScope = false
    }

    private suspend fun handleAssessment(
        assessment: RiskAssessment,
        allowEscalation: Boolean = true,
        ignoreSuppression: Boolean = false
    ) {
        val offlineEvents: List<PendingOfflineEvent> = mutex.withLock {
            if (!started) return@withLock emptyList()
            if (activeSessionId == null) activeSessionId = assessment.sessionId
            if (activeSessionId != assessment.sessionId) return@withLock emptyList()
            val key = assessment.identifier()
            persistenceDecisions.get(key)?.let { retry ->
                pendingPersistenceAssessments.add(key)
                return@withLock listOf(retry)
            }
            if (processedAssessments.contains(key)) {
                counters = counters.copy(duplicateAssessments = counters.duplicateAssessments + 1)
                return@withLock emptyList()
            }
            if (pendingPersistenceAssessments.contains(key)) return@withLock emptyList()
            if (isLate(assessment)) {
                counters = counters.copy(lateAssessments = counters.lateAssessments + 1)
                return@withLock emptyList()
            }
            lastEndNanos = maxOf(lastEndNanos ?: assessment.endNanos, assessment.endNanos)
            if (terminalAssessments.contains(key)) return@withLock emptyList()
            val currentCountdown = validationStore.states.value.activeCountdown
            if (currentCountdown != null) return@withLock emptyList()
            if (!ignoreSuppression) {
                suppressEscalationUntilEndNanos?.let { suppressionEnd ->
                    if (assessment.endNanos <= suppressionEnd) {
                        processedAssessments.add(key)
                        return@withLock emptyList()
                    }
                    suppressEscalationUntilEndNanos = null
                }
            }

            val evidence = assessment.validationEvidence()
            if (allowEscalation && isCritical(assessment, explicitHelp = false)) {
                AutoIncidentDiagnostics.validationCandidate("critical_immediate")
                return@withLock createIncidentLocked(assessment, IncidentCause.CriticalPhysicalEvent, ValidationDecisionReason.CriticalPhysicalEvent, ValidationOrigin.System, immediate = true).toOfflineEvents(key)
            }
            // The pilot ML contract is an OR with the hard-rule path. When ML crosses its model
            // threshold it must reach user confirmation before bump/braking suppressors can discard
            // the same window. It still only opens the countdown; it never sends immediately.
            if (allowEscalation && assessment.mlDetected) {
                AutoIncidentDiagnostics.validationCandidate("ml_countdown")
                processedAssessments.add(key)
                publish(FalsePositiveValidationState.CandidateDetected(assessment, metadata(assessment, ValidationDecisionReason.CandidatePhysicalRisk, ValidationOrigin.System), evidence))
                startCountdownLocked(assessment, evidence)
                return@withLock emptyList()
            }
            if (isIsolatedBump(assessment, evidence)) {
                return@withLock listOf(recordMinorEventLocked(assessment, evidence, key))
            }
            if (isHarshBrakingSuppressed(assessment, evidence)) {
                processedAssessments.add(key)
                publish(FalsePositiveValidationState.SuppressedFalsePositive(assessment, metadata(assessment, ValidationDecisionReason.HarshBrakingWithContinuedMovement, ValidationOrigin.System), evidence))
                return@withLock emptyList()
            }
            if (allowEscalation && requiresCountdown(assessment)) {
                AutoIncidentDiagnostics.validationCandidate("countdown")
                processedAssessments.add(key)
                publish(FalsePositiveValidationState.CandidateDetected(assessment, metadata(assessment, ValidationDecisionReason.CandidatePhysicalRisk, ValidationOrigin.System), evidence))
                startCountdownLocked(assessment, evidence)
            }
            if (!allowEscalation) processedAssessments.add(key)
            emptyList()
        }
        enqueueOffline(offlineEvents)
    }

    private suspend fun handleResponse(response: UserValidationResponse) {
        val offlineEvents: List<PendingOfflineEvent> = mutex.withLock {
            if (!started || response.protocolVersion != PROTOCOL_VERSION) {
                counters = counters.copy(invalidResponses = counters.invalidResponses + 1)
                return@withLock emptyList()
            }
            if (!processedResponses.add(response.responseId)) {
                counters = counters.copy(duplicateResponses = counters.duplicateResponses + 1)
                return@withLock emptyList()
            }
            val countdownState = validationStore.states.value.activeCountdown
            if (countdownState == null || countdownState.assessment.sessionId != response.sessionId || countdownState.assessment.assessmentId != response.assessmentId) {
                counters = counters.copy(ignoredResponses = counters.ignoredResponses + 1)
                return@withLock emptyList()
            }
            val assessment = countdownState.assessment
            when (response.action) {
                UserValidationAction.ConfirmSafe -> {
                    terminalAssessments.add(assessment.identifier())
                    processedAssessments.add(assessment.identifier())
                    suppressEscalationUntilEndNanos = assessment.endNanos + config.postSafeSuppressionNanos
                    cancelCountdownLocked()
                    val safe = FalsePositiveValidationState.SafeConfirmed(metadata(assessment, ValidationDecisionReason.UserConfirmedSafe, response.source.toOrigin()), response.responseId)
                    publish(safe)
                    returnToMonitoringLater(safe)
                    emptyList()
                }
                UserValidationAction.RequestHelp -> {
                    publish(FalsePositiveValidationState.HelpRequested(metadata(assessment, ValidationDecisionReason.UserRequestedHelp, response.source.toOrigin()), response.responseId))
                    createIncidentLocked(assessment, IncidentCause.UserRequestedHelp, ValidationDecisionReason.UserRequestedHelp, response.source.toOrigin(), immediate = false).toOfflineEvents(assessment.identifier())
                }
            }
        }
        enqueueOffline(offlineEvents)
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
                val offlineEvents: List<PendingOfflineEvent> = mutex.withLock {
                    val state = validationStore.states.value.activeCountdown ?: return@launch
                    if (state.assessment.assessmentId != assessment.assessmentId || state.assessment.sessionId != assessment.sessionId) return@launch
                    val remaining = (deadline - now()).coerceAtLeast(0L)
                    if (remaining <= 0L) {
                        logger.countdownExpired()
                        counters = counters.copy(timeouts = counters.timeouts + 1)
                        return@withLock createIncidentLocked(assessment, IncidentCause.Timeout, ValidationDecisionReason.Timeout, ValidationOrigin.Timeout, immediate = false).toOfflineEvents(assessment.identifier())
                    }
                    publish(state.copy(remainingNanos = remaining))
                    emptyList()
                }
                enqueueOffline(offlineEvents)
                if (offlineEvents.isNotEmpty()) return@launch
            }
        }
    }

    private fun recordMinorEventLocked(assessment: RiskAssessment, evidence: ValidationEvidence, key: AssessmentIdentifier): PendingOfflineEvent.Minor {
        val createdAt = now()
        val event = MinorEvent(
            eventId = nextMinorEventId(),
            sessionId = assessment.sessionId,
            assessmentId = assessment.assessmentId,
            windowId = assessment.windowId,
            type = MinorEventType.Bump,
            createdAtElapsedRealtimeNanos = createdAt,
            score = assessment.score,
            confidence = assessment.confidence,
            evidence = evidence,
            policyVersion = config.policyVersion
        )
        val pending = PendingOfflineEvent.Minor(key, event, metadata(assessment, ValidationDecisionReason.IsolatedBump, ValidationOrigin.System, createdAt))
        persistenceDecisions.put(key, pending)
        pendingPersistenceAssessments.add(key)
        return pending
    }

    private fun createIncidentLocked(
        assessment: RiskAssessment,
        cause: IncidentCause,
        reason: ValidationDecisionReason,
        origin: ValidationOrigin,
        immediate: Boolean
    ): IncidentBundle? {
        val key = assessment.identifier()
        if (terminalAssessments.contains(key) || pendingPersistenceAssessments.contains(key)) return null
        cancelCountdownLocked()
        AutoIncidentDiagnostics.incidentStarted(cause)
        val createdAt = now()
        val incident = LocalIncident(
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
            gpsQuality = assessment.gpsQuality.status,
            clientIncidentId = UUID.randomUUID().toString(),
            detectedAtEpochMillis = nowEpochMillis(),
            tripSessionKey = (tripSessionStore.states.value as? TripSessionState.Active)?.tripSessionKey
        )
        val request = AlertDispatchRequest(
            requestId = nextDispatchRequestId(),
            incidentId = incident.incidentId,
            sessionId = assessment.sessionId,
            assessmentId = assessment.assessmentId,
            priority = if (immediate) AlertPriority.Critical else AlertPriority.High,
            reason = cause,
            createdAtElapsedRealtimeNanos = createdAt,
            score = assessment.score,
            confidence = assessment.confidence,
            payload = AlertPayloadSummary(assessment.sessionId, assessment.assessmentId, incident.incidentId, assessment.score, assessment.riskLevel, cause, config.policyVersion),
            clientAlertRequestId = UUID.randomUUID().toString()
        )
        return IncidentBundle(key, incident, request, metadata(assessment, reason, origin, createdAt), immediate)
    }

    private suspend fun enqueueOffline(events: List<PendingOfflineEvent>) {
        events.forEach { event ->
            when (event) {
                is PendingOfflineEvent.Minor -> {
                    val result = try {
                        offlineEventSink.enqueueMinorEvent(event.event)
                    } catch (failure: IllegalStateException) {
                        AutoIncidentDiagnostics.storageException("coordinator_minor_enqueue", failure)
                        com.example.sos_segundoplano.domain.offline.OfflineQueueEnqueueResult.PersistenceFailed(com.example.sos_segundoplano.domain.offline.OfflineSyncErrorCategory.Serialization, "offline_queue_storage_unavailable")
                    }
                    if (result.isPersisted) mutex.withLock {
                        minorEventStore.add(event.event)
                        pendingPersistenceAssessments.remove(event.key)
                        persistenceDecisions.remove(event.key)
                        cancelPersistenceRetry(event.key)
                        processedAssessments.add(event.key)
                        publish(FalsePositiveValidationState.MinorEventRecorded(event.event, event.metadata))
                    } else if (result is com.example.sos_segundoplano.domain.offline.OfflineQueueEnqueueResult.PersistenceFailed) {
                        publishPersistenceError(event.key, event.metadata)
                    }
                }
                is PendingOfflineEvent.IncidentBundleEvent -> {
                    // Emergency intent is made durable BEFORE GPS/remote-trip resolution. If the
                    // process dies or connectivity changes while context is being completed, the
                    // dedicated automatic SOS worker can recover the same client IDs from Room.
                    val eventPreparedForPersistence = event
                    AutoIncidentDiagnostics.localPersistenceStarted()
                    val result = try {
                        offlineEventSink.enqueueIncidentBundle(eventPreparedForPersistence.incident, eventPreparedForPersistence.request)
                    } catch (failure: IllegalStateException) {
                        AutoIncidentDiagnostics.storageException("coordinator_incident_bundle_enqueue", failure)
                        com.example.sos_segundoplano.domain.offline.OfflineQueueEnqueueResult.PersistenceFailed(com.example.sos_segundoplano.domain.offline.OfflineSyncErrorCategory.Serialization, "offline_queue_storage_unavailable")
                    }
                    AutoIncidentDiagnostics.localPersistenceResult(result)
                    if (result.isPersisted) {
                        processPersistedIncidentBundle(eventPreparedForPersistence)
                    } else if (result is com.example.sos_segundoplano.domain.offline.OfflineQueueEnqueueResult.PersistenceFailed) {
                        publishIncidentPersistenceFailure(eventPreparedForPersistence.key, eventPreparedForPersistence.metadata)
                    }
                }
            }
        }
    }

    private suspend fun processPersistedIncidentBundle(event: PendingOfflineEvent.IncidentBundleEvent) {
        val automatic = event.incident.isAutomaticSosCause() && automaticSosAlertCreator !== NoOpAutomaticSosAlertCreator
        AutoIncidentDiagnostics.remoteCreateStarted()

        val tripPreparedEvent: PendingOfflineEvent.IncidentBundleEvent
        val incident: LocalIncident

        if (automatic) {
            // Claim the durable bundle BEFORE resolving trip/GPS context. enqueueIncidentBundle()
            // schedules a WorkManager safety fallback, so claiming first prevents the foreground
            // coordinator and the recovery worker from racing each other for the same SOS.
            val bundleKey = event.incident.clientIncidentId
            val claim = bundleKey?.let {
                offlineEventSink.claimAutomaticSosBundle(it, "coordinator-${event.key.assessmentId}", nowEpochMillis())
            } ?: AutomaticSosBundleClaimResult.NotRecoverable
            val acquired = (claim as? AutomaticSosBundleClaimResult.Acquired)?.bundle

            if (acquired == null) {
                when (claim) {
                    AutomaticSosBundleClaimResult.BusyOrUnavailable -> {
                        // Another durable owner (normally AutomaticSosSyncWorker) already has the
                        // exact same bundle. Do not start the old in-memory retry loop: that loop
                        // could end in a false "Registro local no completado" while the worker was
                        // actually sending the SOS.
                        AutoIncidentDiagnostics.bundleClaim("busy_worker_owns_bundle")
                        mutex.withLock {
                            pendingPersistenceAssessments.remove(event.key)
                            persistenceDecisions.remove(event.key)
                            cancelPersistenceRetry(event.key)
                            processedAssessments.add(event.key)
                            publish(FalsePositiveValidationState.IncidentDeliveryRetrying(event.metadata, 1, MAX_PERSISTENCE_RETRY_ATTEMPTS))
                        }
                        return
                    }

                    else -> {
                        AutoIncidentDiagnostics.bundleClaim("invalid")
                        publishRemoteFailureForRetry(
                            event,
                            event.incident.copy(
                                remoteCreationStatus = IncidentRemoteCreationStatus.InvalidResponse("offline_bundle_claim_unavailable")
                            )
                        )
                        return
                    }
                }
            }

            AutoIncidentDiagnostics.bundleClaim("acquired")
            tripPreparedEvent = prepareDurableAutomaticSosContext(event, acquired) ?: return

            val submitted = automaticSosAlertCreator.createAutomaticSosAlert(
                tripPreparedEvent.incident.copy(remoteCreationStatus = IncidentRemoteCreationStatus.Pending),
                tripPreparedEvent.request
            )
            val status = submitted.remoteCreationStatus
            val receipt = if (status is IncidentRemoteCreationStatus.Success) {
                submitted.remoteIncidentId?.takeIf { it.isNotBlank() }?.let { remoteIncidentId ->
                    submitted.remoteAlertDispatchId?.takeIf { it.isNotBlank() }?.let { remoteAlertDispatchId ->
                        AutomaticSosRemoteReceipt(remoteIncidentId, remoteAlertDispatchId)
                    }
                }
            } else null

            if (status !is IncidentRemoteCreationStatus.Success) {
                AutoIncidentDiagnostics.remoteCreateResult(status)
                val permanent = status.isPermanentAutomaticSosFailure()
                val released = offlineEventSink.releaseAutomaticSosBundle(
                    acquired,
                    permanent = permanent,
                    code = "remote_submission_failed",
                    nowMillis = nowEpochMillis()
                )
                AutoIncidentDiagnostics.bundleRelease(
                    if (released == OfflineQueueTransitionResult.Applied) {
                        if (permanent) "permanent" else "retry"
                    } else {
                        "failure"
                    }
                )
                publishRemoteFailureForRetry(tripPreparedEvent, submitted)
                return
            }

            if (receipt == null || offlineEventSink.acknowledgeAutomaticSosBundle(acquired, receipt, nowEpochMillis()) != OfflineQueueTransitionResult.Applied) {
                if (receipt == null) {
                    val released = offlineEventSink.releaseAutomaticSosBundle(
                        acquired,
                        permanent = false,
                        code = "receipt_missing",
                        nowMillis = nowEpochMillis()
                    )
                    AutoIncidentDiagnostics.bundleRelease(if (released == OfflineQueueTransitionResult.Applied) "retry" else "failure")
                }
                publishRemoteFailureForRetry(
                    tripPreparedEvent,
                    submitted.copy(
                        remoteCreationStatus = IncidentRemoteCreationStatus.InvalidResponse("offline_receipt_persistence_failed")
                    )
                )
                return
            }
            incident = submitted
        } else {
            tripPreparedEvent = event
            incident = createRemoteIncidentOnce(tripPreparedEvent)
        }

        AutoIncidentDiagnostics.remoteCreateResult(incident.remoteCreationStatus)
        if (incident.remoteCreationStatus !is IncidentRemoteCreationStatus.Success && automatic) {
            publishRemoteFailureForRetry(tripPreparedEvent.copy(incident = incident), incident)
            return
        }
        mutex.withLock {
            incidentStore.add(incident)
            dispatchRequestStore.add(tripPreparedEvent.request)
            pendingPersistenceAssessments.remove(tripPreparedEvent.key)
            persistenceDecisions.remove(tripPreparedEvent.key)
            cancelPersistenceRetry(tripPreparedEvent.key)
            processedAssessments.add(tripPreparedEvent.key)
            terminalAssessments.add(tripPreparedEvent.key)
            if (incident.remoteCreationStatus is IncidentRemoteCreationStatus.Success) {
                if (tripPreparedEvent.immediate) {
                    publish(FalsePositiveValidationState.ImmediateAlertRequested(incident, tripPreparedEvent.request, tripPreparedEvent.metadata))
                } else {
                    publish(FalsePositiveValidationState.IncidentGenerated(incident, tripPreparedEvent.request, tripPreparedEvent.metadata))
                }
                AutoIncidentDiagnostics.terminalState("incident_generated")
            } else {
                publish(FalsePositiveValidationState.Error(tripPreparedEvent.metadata, "RemoteIncidentCreationFailed"))
                AutoIncidentDiagnostics.terminalState("error", "remote_incident_creation_failed")
            }
        }
    }

    private suspend fun publishRemoteFailureForRetry(
        event: PendingOfflineEvent.IncidentBundleEvent,
        incident: LocalIncident
    ) {
        val permanent = incident.remoteCreationStatus.isPermanentAutomaticSosFailure()
        mutex.withLock {
            pendingPersistenceAssessments.remove(event.key)
            if (permanent) {
                persistenceDecisions.remove(event.key)
                processedAssessments.add(event.key)
                terminalAssessments.add(event.key)
            } else {
                persistenceDecisions.put(event.key, event.copy(incident = incident))
            }
        }
        if (permanent) {
            publish(FalsePositiveValidationState.Error(event.metadata, "RemoteIncidentCreationFailed"))
            AutoIncidentDiagnostics.terminalState("error", "remote_incident_creation_permanent")
            return
        }

        val attempt = schedulePersistenceRetry(event.key)
        mutex.withLock {
            if (attempt != null) {
                publish(FalsePositiveValidationState.IncidentDeliveryRetrying(event.metadata, attempt, MAX_PERSISTENCE_RETRY_ATTEMPTS))
                AutoIncidentDiagnostics.retryScheduled("remote_delivery_attempt_$attempt")
            } else {
                // The exact SOS bundle is already durable in Room and the dedicated WorkManager
                // recovery path owns further retries. Do not turn a transient remote problem into
                // the misleading terminal UI state "Registro local no completado".
                pendingPersistenceAssessments.remove(event.key)
                persistenceDecisions.remove(event.key)
                processedAssessments.add(event.key)
                publish(FalsePositiveValidationState.IncidentDeliveryRetrying(event.metadata, MAX_PERSISTENCE_RETRY_ATTEMPTS, MAX_PERSISTENCE_RETRY_ATTEMPTS))
                AutoIncidentDiagnostics.retryScheduled("durable_worker_takeover")
            }
        }
    }

    private suspend fun prepareDurableAutomaticSosContext(
        event: PendingOfflineEvent.IncidentBundleEvent,
        acquired: com.example.sos_segundoplano.domain.offline.ClaimedAutomaticSosBundle
    ): PendingOfflineEvent.IncidentBundleEvent? {
        var incident = event.incident.copy(remoteCreationStatus = IncidentRemoteCreationStatus.Pending)

        if (incident.remoteTripId.isNullOrBlank()) {
            incident = automaticSosAlertCreator.resolveRemoteTrip(incident)
            if (incident.remoteCreationStatus !is IncidentRemoteCreationStatus.Pending) {
                releaseClaimAndPublishRetry(event.copy(incident = incident), incident, acquired, "remote_trip_context_unavailable")
                return null
            }
        }

        if (!incident.hasValidAutomaticSosLocation()) {
            incident = automaticSosAlertCreator.captureLocation(incident)
            if (incident.remoteCreationStatus !is IncidentRemoteCreationStatus.Pending) {
                releaseClaimAndPublishRetry(event.copy(incident = incident), incident, acquired, "location_context_unavailable")
                return null
            }
        }

        val prepared = event.copy(incident = incident)
        val updateResult = try {
            offlineEventSink.updateIncidentBundle(prepared.incident, prepared.request)
        } catch (failure: IllegalStateException) {
            AutoIncidentDiagnostics.storageException("coordinator_incident_bundle_context_update", failure)
            com.example.sos_segundoplano.domain.offline.OfflineQueueEnqueueResult.PersistenceFailed(
                com.example.sos_segundoplano.domain.offline.OfflineSyncErrorCategory.Serialization,
                "offline_queue_storage_unavailable"
            )
        }
        if (!updateResult.isPersisted) {
            val failed = prepared.incident.copy(
                remoteCreationStatus = IncidentRemoteCreationStatus.InvalidResponse("durable_context_update_failed")
            )
            releaseClaimAndPublishRetry(prepared.copy(incident = failed), failed, acquired, "durable_context_update_failed")
            return null
        }

        mutex.withLock { persistenceDecisions.put(event.key, prepared) }
        return prepared
    }

    private suspend fun releaseClaimAndPublishRetry(
        event: PendingOfflineEvent.IncidentBundleEvent,
        incident: LocalIncident,
        acquired: com.example.sos_segundoplano.domain.offline.ClaimedAutomaticSosBundle,
        code: String
    ) {
        AutoIncidentDiagnostics.remoteCreateResult(incident.remoteCreationStatus)
        val permanent = incident.remoteCreationStatus.isPermanentAutomaticSosFailure()
        val released = offlineEventSink.releaseAutomaticSosBundle(
            acquired,
            permanent = permanent,
            code = code,
            nowMillis = nowEpochMillis()
        )
        AutoIncidentDiagnostics.bundleRelease(
            if (released == OfflineQueueTransitionResult.Applied) {
                if (permanent) "permanent" else "retry"
            } else {
                "failure"
            }
        )
        publishRemoteFailureForRetry(event, incident)
    }

    private suspend fun createRemoteIncidentOnce(event: PendingOfflineEvent.IncidentBundleEvent): LocalIncident {
        val firstAttempt = mutex.withLock { remoteIncidentAttempts.add(event.key) }
        if (!firstAttempt) return event.incident.copy(
            remoteCreationStatus = IncidentRemoteCreationStatus.DuplicateAttempt
        ).also { logger.duplicateIncidentAttempt() }
        return try {
            incidentRemoteCreator.createIncident(
                event.incident.copy(remoteCreationStatus = IncidentRemoteCreationStatus.Pending)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            event.incident.copy(
                remoteCreationStatus = IncidentRemoteCreationStatus.InvalidResponse("incident_remote_creator_failed")
            )
        } catch (_: RuntimeException) {
            event.incident.copy(
                remoteCreationStatus = IncidentRemoteCreationStatus.InvalidResponse("incident_remote_creator_failed")
            )
        }
    }

    private fun IncidentBundle?.toOfflineEvents(key: AssessmentIdentifier): List<PendingOfflineEvent> = this?.let {
        val pending = PendingOfflineEvent.IncidentBundleEvent(it.key, it.incident, it.request, it.metadata, it.immediate)
        persistenceDecisions.put(key, pending)
        pendingPersistenceAssessments.add(key)
        listOf(pending)
    }.orEmpty()

    private fun LocalIncident.hasValidAutomaticSosLocation(): Boolean =
        latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 && !(latitude == 0.0 && longitude == 0.0)

    private fun LocalIncident.isAutomaticSosCause(): Boolean = when (cause) {
        IncidentCause.Timeout,
        IncidentCause.UserRequestedHelp,
        IncidentCause.CriticalPhysicalEvent -> true
        IncidentCause.ManualSos -> false
    }

    private fun IncidentRemoteCreationStatus.isPermanentAutomaticSosFailure(): Boolean = when (this) {
        // During an active local trip, remote-trip reconciliation or GPS may become available a
        // moment later. Treat missing runtime context as bounded-retryable, not terminal.
        is IncidentRemoteCreationStatus.MissingRequiredData -> false
        is IncidentRemoteCreationStatus.HttpError -> statusCode in 400..499 && statusCode !in setOf(401, 408, 429)
        else -> false
    }

    private suspend fun publishPersistenceError(key: AssessmentIdentifier, metadata: ValidationMetadata) {
        mutex.withLock {
            pendingPersistenceAssessments.remove(key)
            publish(FalsePositiveValidationState.Error(metadata, "OfflinePersistenceFailed"))
            AutoIncidentDiagnostics.terminalState("error", "offline_persistence_failed")
        }
        schedulePersistenceRetry(key)
    }

    /**
     * Automatic incidents remain in an emergency/retry state while the bounded local persistence
     * retry is active. The UI must not present a terminal failure that contradicts the durable retry.
     */
    private suspend fun publishIncidentPersistenceFailure(key: AssessmentIdentifier, metadata: ValidationMetadata) {
        val attempt = schedulePersistenceRetry(key)
        mutex.withLock {
            pendingPersistenceAssessments.remove(key)
            if (attempt != null) {
                publish(FalsePositiveValidationState.IncidentDeliveryRetrying(metadata, attempt, MAX_PERSISTENCE_RETRY_ATTEMPTS))
                AutoIncidentDiagnostics.retryScheduled("local_persistence_attempt_$attempt")
            } else {
                publish(FalsePositiveValidationState.Error(metadata, "OfflinePersistenceFailed"))
                AutoIncidentDiagnostics.terminalState("error", "offline_persistence_failed")
            }
        }
    }

    private fun schedulePersistenceRetry(key: AssessmentIdentifier): Int? {
        val currentScope = scope ?: return null
        if (persistenceRetryJobs.containsKey(key)) return persistenceRetryAttempts[key]
        val attempt = (persistenceRetryAttempts[key] ?: 0) + 1
        persistenceRetryAttempts[key] = attempt
        trimPersistenceRetryJobs()
        if (attempt > MAX_PERSISTENCE_RETRY_ATTEMPTS) return null
        persistenceRetryJobs[key] = currentScope.launch {
            delay(PERSISTENCE_RETRY_BACKOFF_MILLIS * attempt)
            val pending = mutex.withLock {
                persistenceRetryJobs.remove(key)
                val decision = persistenceDecisions.get(key) ?: return@launch
                if (pendingPersistenceAssessments.contains(key)) return@launch
                pendingPersistenceAssessments.add(key)
                decision
            }
            enqueueOffline(listOf(pending))
        }
        return attempt
    }

    private suspend fun cancelPersistenceRetries() {
        val jobs = persistenceRetryJobs.values.toList()
        persistenceRetryJobs.clear()
        persistenceRetryAttempts.clear()
        jobs.forEach { it.cancelAndJoin() }
    }

    private fun cancelPersistenceRetry(key: AssessmentIdentifier) {
        persistenceRetryJobs.remove(key)?.cancel()
        persistenceRetryAttempts.remove(key)
    }

    private fun trimPersistenceRetryJobs() {
        while (persistenceRetryJobs.size > config.assessmentBufferCapacity) {
            cancelPersistenceRetry(persistenceRetryJobs.keys.first())
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
            impact?.endNanos == assessment.endNanos &&
            (impact.severity ?: 0.0) >= config.bumpMinimumImpactSeverity &&
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

    private fun requiresCountdown(assessment: RiskAssessment): Boolean {
        // Pilot ML is an additional support signal only. It can open the same user-confirmation
        // countdown, but it never bypasses confirmation and never creates an immediate critical SOS.
        // If the model is unavailable, mlDetected remains false and the existing explainable rules
        // continue unchanged.
        if (assessment.mlDetected) return true

        val score = assessment.score ?: return false
        if (score < config.correlatedCandidateMinimumScore ||
            assessment.confidence < config.correlatedCandidateMinimumConfidence
        ) return false

        // A correlated fall is already a multi-stage physical pattern (free-fall/impact and,
        // when available, orientation support). It is enough to ask the Rider for confirmation.
        val fall = assessment.outcome(RuleId.Fall)
        if (fall.isTriggered()) return true

        val impact = assessment.outcome(RuleId.Impact)
        if (!impact.isTriggered()) return false
        val impactSeverity = impact?.severity ?: 0.0

        // The previous tuning became too strict here: a real impact followed by sustained
        // immobility could score in the low/mid 30s and never reach countdown unless a third rule
        // also triggered. Impact + post-event immobility is already two independent pieces of
        // evidence, so it should start the false-positive countdown. Immobility by itself still
        // contributes zero risk in RuleEngine and can never enter this branch.
        val immobility = assessment.outcome(RuleId.Immobility)
        val immobilitySeverity = immobility?.severity ?: 0.0
        if (immobility.isTriggered() &&
            impactSeverity >= config.impactImmobilityMinimumImpactSeverity &&
            immobilitySeverity >= config.immobilityCountdownMinimumSeverity
        ) return true

        // An impact plus a meaningful orientation change is another coherent crash/fall pattern.
        // This lets the countdown appear before the three-second immobility window when the device
        // actually rotates sharply during the event.
        val orientation = assessment.outcome(RuleId.OrientationChange)
        val orientationSeverity = orientation?.severity ?: 0.0
        if (orientation.isTriggered() &&
            impactSeverity >= config.impactImmobilityMinimumImpactSeverity &&
            orientationSeverity >= config.secondarySupportMinimumSeverity
        ) return true

        // Impact plus harsh braking is relevant only when movement is no longer clearly
        // continuing. Ordinary hard braking while the motorcycle keeps moving remains suppressed.
        val braking = assessment.outcome(RuleId.HarshBraking)
        val brakingSeverity = braking?.severity ?: 0.0
        if (braking.isTriggered() &&
            impactSeverity >= config.impactImmobilityMinimumImpactSeverity &&
            brakingSeverity >= config.secondarySupportMinimumSeverity &&
            assessment.movementContinuity != MovementContinuityState.Continuing
        ) return true

        // A very strong impact with stopped/intermittent movement should not be ignored merely
        // because the immobility duration has not reached its full threshold yet. This only opens
        // the countdown; it does not immediately send an SOS.
        return impactSeverity >= config.impactImmobilityDirectImpactSeverity &&
            assessment.movementContinuity != MovementContinuityState.Continuing
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
        pendingPersistenceAssessments.clear()
        persistenceDecisions.clear()
        persistenceRetryJobs.values.forEach { it.cancel() }
        persistenceRetryJobs.clear()
        persistenceRetryAttempts.clear()
        processedResponses.clear()
        remoteIncidentAttempts.clear()
        counters = ValidationCounters()
        lastEndNanos = null
        suppressEscalationUntilEndNanos = null
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
        fun remove(id: Any) {
            if (set.remove(id)) ids.remove(id)
        }
        fun clear() {
            ids.clear()
            set.clear()
        }
    }

    private class BoundedDecisionMap(private val capacity: Int) {
        private val keys = ArrayDeque<Any>()
        private val values = LinkedHashMap<Any, PendingOfflineEvent>()
        fun get(key: Any): PendingOfflineEvent? = values[key]
        fun put(key: Any, value: PendingOfflineEvent) {
            if (!values.containsKey(key)) keys.addLast(key)
            values[key] = value
            while (keys.size > capacity) values.remove(keys.removeFirst())
        }
        fun remove(key: Any) {
            values.remove(key)
            keys.remove(key)
        }
        fun clear() {
            keys.clear()
            values.clear()
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

    private data class IncidentBundle(
        val key: AssessmentIdentifier,
        val incident: LocalIncident,
        val request: AlertDispatchRequest,
        val metadata: ValidationMetadata,
        val immediate: Boolean
    )

    private sealed interface PendingOfflineEvent {
        val key: AssessmentIdentifier
        data class Minor(override val key: AssessmentIdentifier, val event: MinorEvent, val metadata: ValidationMetadata) : PendingOfflineEvent
        data class IncidentBundleEvent(override val key: AssessmentIdentifier, val incident: LocalIncident, val request: AlertDispatchRequest, val metadata: ValidationMetadata, val immediate: Boolean) : PendingOfflineEvent
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MAX_PERSISTENCE_RETRY_ATTEMPTS = 3
        private const val PERSISTENCE_RETRY_BACKOFF_MILLIS = 1_000L
    }
}

object FalsePositiveValidationCoordinatorProvider {
    val coordinator: FalsePositiveValidationCoordinator = FalsePositiveValidationCoordinator()

    fun setNotifier(notifier: FalsePositiveValidationNotifier) {
        coordinator.setNotifier(notifier)
    }

    fun setOfflineEventSink(sink: OfflineEventSink) {
        coordinator.setOfflineEventSink(sink)
    }

    fun setIncidentRemoteCreator(creator: IncidentRemoteCreator) {
        coordinator.setIncidentRemoteCreator(creator)
    }

    fun setAutomaticSosAlertCreator(creator: AutomaticSosAlertCreator) {
        coordinator.setAutomaticSosAlertCreator(creator)
    }

    fun setLogger(logger: FalsePositiveValidationLogger) {
        coordinator.setLogger(logger)
    }
}
