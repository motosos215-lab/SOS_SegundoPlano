package com.example.sos_segundoplano.data.validation

import com.example.sos_segundoplano.data.rules.InMemoryRiskAssessmentStore
import com.example.sos_segundoplano.domain.offline.OfflineEventSink
import com.example.sos_segundoplano.domain.offline.OfflineQueueEnqueueResult
import com.example.sos_segundoplano.domain.offline.OfflineSyncErrorCategory
import com.example.sos_segundoplano.domain.offline.idempotencyKey
import com.example.sos_segundoplano.domain.offline.OfflineEventType
import com.example.sos_segundoplano.domain.rules.BatteryReadinessStatus
import com.example.sos_segundoplano.domain.rules.ConnectivityReadinessStatus
import com.example.sos_segundoplano.domain.rules.DeviceReadinessEvaluation
import com.example.sos_segundoplano.domain.rules.GpsQualityEvaluation
import com.example.sos_segundoplano.domain.rules.GpsQualityStatus
import com.example.sos_segundoplano.domain.rules.MovementContinuityState
import com.example.sos_segundoplano.domain.rules.RiskAssessment
import com.example.sos_segundoplano.domain.rules.RiskContribution
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.rules.RuleEvaluationStatus
import com.example.sos_segundoplano.domain.rules.RuleEvidence
import com.example.sos_segundoplano.domain.rules.RuleId
import com.example.sos_segundoplano.domain.rules.RuleOutcome
import com.example.sos_segundoplano.domain.validation.AlertDeliveryStatus
import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.AlertRetryState
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationConfig
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.validation.MinorEvent
import com.example.sos_segundoplano.domain.validation.MinorEventType
import com.example.sos_segundoplano.domain.validation.MonotonicClock
import com.example.sos_segundoplano.domain.validation.UserResponseSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FalsePositiveValidationCoordinatorTest {
    @Test fun configDefaultsAndValidationAreExplicit() {
        val config = FalsePositiveValidationConfig()
        assertEquals(20_000_000_000L, config.countdownDurationNanos)
        assertEquals(1_000_000_000L, config.visualUpdateIntervalNanos)
        assertEquals(40, config.minimumValidationScore)
        assertEquals(90, config.criticalScore)
        assertEquals(0.60, config.minimumConfidence, 0.0)
        assertEquals(0.80, config.criticalConfidence, 0.0)
        assertEquals(0.90, config.criticalFallSeverity, 0.0)
        assertEquals(0.90, config.criticalImpactSeverity, 0.0)
        assertEquals(39, config.bumpMaximumScore)
        assertEquals("false-positive-validation-v1", config.policyVersion)
        rejects { FalsePositiveValidationConfig(countdownDurationNanos = 0L) }
        rejects { FalsePositiveValidationConfig(maxMinorEvents = 0) }
        rejects { FalsePositiveValidationConfig(minimumConfidence = -0.1) }
        rejects { FalsePositiveValidationConfig(minimumValidationScore = -1) }
        rejects { FalsePositiveValidationConfig(policyVersion = "") }
    }

    @Test fun isolatedBumpRecordsMinorEventWithoutCountdownIncidentOrAlert() = runTest {
        val fixture = fixture()
        try {
            start(fixture)
            fixture.risk.publish(assessment(score = 30, riskLevel = RiskLevel.Low, continuity = MovementContinuityState.Continuing, outcomes = listOf(triggered(RuleId.Impact, 0.5), notTriggered(RuleId.Fall), notTriggered(RuleId.Immobility), notTriggered(RuleId.OrientationChange))))
            runCurrent()

            val state = fixture.validation.states.value as FalsePositiveValidationState.MinorEventRecorded
            assertEquals(MinorEventType.Bump, state.event.type)
            assertEquals(1, fixture.minor.items.value.size)
            assertTrue(fixture.incidents.items.value.isEmpty())
            assertTrue(fixture.requests.items.value.isEmpty())
            assertFalse(fixture.validation.states.value is FalsePositiveValidationState.CountdownActive)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun bumpPolicyRejectsFallImmobilityHighRiskAndLowConfidence() = runTest {
        val cases = listOf(
            assessment(score = 30, riskLevel = RiskLevel.Low, continuity = MovementContinuityState.Continuing, outcomes = listOf(triggered(RuleId.Impact, 0.5), triggered(RuleId.Fall, 0.5))),
            assessment(score = 30, riskLevel = RiskLevel.Low, continuity = MovementContinuityState.Continuing, outcomes = listOf(triggered(RuleId.Impact, 0.5), triggered(RuleId.Immobility, 0.7))),
            assessment(score = 80, riskLevel = RiskLevel.High, continuity = MovementContinuityState.Continuing, outcomes = listOf(triggered(RuleId.Impact, 0.5))),
            assessment(score = 30, riskLevel = RiskLevel.Low, confidence = 0.4, continuity = MovementContinuityState.Continuing, outcomes = listOf(triggered(RuleId.Impact, 0.5)))
        )
        cases.forEachIndexed { index, input ->
            val fixture = fixture()
            try {
                start(fixture)
                fixture.risk.publish(input.copy(assessmentId = index + 1L, windowId = index + 1L))
                runCurrent()
                assertTrue(fixture.minor.items.value.isEmpty())
            } finally {
                fixture.close()
                runCurrent()
            }
        }
    }

    @Test fun harshBrakingWithContinuityIsSuppressedAndPreservesScoreWithoutAlert() = runTest {
        val fixture = fixture()
        try {
            start(fixture)
            fixture.risk.publish(assessment(score = 20, riskLevel = RiskLevel.Low, continuity = MovementContinuityState.Continuing, outcomes = listOf(triggered(RuleId.HarshBraking, 0.7), notTriggered(RuleId.Fall), notTriggered(RuleId.Immobility))))
            runCurrent()

            val state = fixture.validation.states.value as FalsePositiveValidationState.SuppressedFalsePositive
            assertEquals(20, state.assessment.score)
            assertEquals(RuleId.HarshBraking, state.evidence.harshBraking!!.ruleId)
            assertTrue(fixture.requests.items.value.isEmpty())
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun harshBrakingIsNotSuppressedWithoutContinuityFallOrImmobility() = runTest {
        val cases = listOf(
            assessment(score = 20, riskLevel = RiskLevel.Low, continuity = MovementContinuityState.Stopped, outcomes = listOf(triggered(RuleId.HarshBraking, 0.7))),
            assessment(score = 20, riskLevel = RiskLevel.Low, continuity = MovementContinuityState.Continuing, outcomes = listOf(triggered(RuleId.HarshBraking, 0.7), triggered(RuleId.Fall, 0.6))),
            assessment(score = 20, riskLevel = RiskLevel.Low, continuity = MovementContinuityState.Continuing, outcomes = listOf(triggered(RuleId.HarshBraking, 0.7), triggered(RuleId.Immobility, 0.6)))
        )
        cases.forEachIndexed { index, input ->
            val fixture = fixture()
            try {
                start(fixture)
                fixture.risk.publish(input.copy(assessmentId = index + 1L, windowId = index + 1L))
                runCurrent()
                assertFalse(fixture.validation.states.value is FalsePositiveValidationState.SuppressedFalsePositive)
            } finally {
                fixture.close()
                runCurrent()
            }
        }
    }

    @Test fun fallWithImmobilityStartsTwentySecondMonotonicCountdownAndDuplicateDoesNotReset() = runTest {
        val fixture = fixture()
        try {
            start(fixture)
            val input = assessment(score = 75, riskLevel = RiskLevel.High, outcomes = listOf(triggered(RuleId.Fall, 0.8), triggered(RuleId.Immobility, 0.7)))
            fixture.risk.publish(input)
            runCurrent()
            val first = fixture.validation.states.value as FalsePositiveValidationState.CountdownActive
            assertEquals(20_000_000_000L, first.remainingNanos)
            assertEquals(20_000_000_000L, first.deadlineElapsedRealtimeNanos - first.startedAtElapsedRealtimeNanos)

            fixture.clock.now += 5_000_000_000L
            fixture.risk.publish(input)
            runCurrent()
            val duplicate = fixture.validation.states.value as FalsePositiveValidationState.CountdownActive
            assertEquals(first.deadlineElapsedRealtimeNanos, duplicate.deadlineElapsedRealtimeNanos)
            assertEquals(1L, fixture.coordinator.validationCounters.duplicateAssessments)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun relatedAssessmentDoesNotExtendCountdownAndStopCancelsTimeout() = runTest {
        val fixture = fixture()
        try {
            start(fixture)
            fixture.risk.publish(assessment(score = 75, riskLevel = RiskLevel.High, outcomes = listOf(triggered(RuleId.Fall, 0.8))))
            runCurrent()
            val firstDeadline = (fixture.validation.states.value as FalsePositiveValidationState.CountdownActive).deadlineElapsedRealtimeNanos
            fixture.risk.publish(assessment(assessmentId = 2L, windowId = 2L, score = 75, riskLevel = RiskLevel.High, outcomes = listOf(triggered(RuleId.Fall, 0.8))))
            runCurrent()
            assertEquals(firstDeadline, (fixture.validation.states.value as FalsePositiveValidationState.CountdownActive).deadlineElapsedRealtimeNanos)
            fixture.coordinator.stopAndJoin()
            runCurrent()
            fixture.clock.now = firstDeadline + 1L
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(fixture.incidents.items.value.isEmpty())
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun confirmSafeValidProducesSafeConfirmedThenMonitoringAndNoIncidentOrAlert() = runTest {
        val fixture = fixture()
        try {
            startCountdown(fixture)
            fixture.coordinator.confirmSafe(1L, 1L, UserResponseSource.Mobile, "safe-1")
            runCurrent()
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.SafeConfirmed)
            assertTrue(fixture.incidents.items.value.isEmpty())
            assertTrue(fixture.requests.items.value.isEmpty())
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.Monitoring)
            fixture.coordinator.confirmSafe(1L, 1L, UserResponseSource.Mobile, "safe-1")
            runCurrent()
            assertEquals(1L, fixture.coordinator.validationCounters.ignoredResponses + fixture.coordinator.validationCounters.duplicateResponses)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun staleSessionStaleAssessmentAndLateResponseAreIgnored() = runTest {
        val fixture = fixture()
        try {
            startCountdown(fixture)
            fixture.coordinator.confirmSafe(99L, 1L, UserResponseSource.Mobile, "wrong-session")
            fixture.coordinator.confirmSafe(1L, 99L, UserResponseSource.Mobile, "wrong-assessment")
            runCurrent()
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.CountdownActive)
            fixture.clock.now = 20_000_000_000L
            advanceTimeBy(1_000L)
            runCurrent()
            fixture.coordinator.confirmSafe(1L, 1L, UserResponseSource.Mobile, "late-safe")
            runCurrent()
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.IncidentGenerated)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun requestHelpCreatesSinglePendingIncidentAndAlert() = runTest {
        val fixture = fixture()
        try {
            startCountdown(fixture)
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-1")
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-1")
            runCurrent()

            val state = fixture.validation.states.value as FalsePositiveValidationState.IncidentGenerated
            assertEquals(IncidentCause.UserRequestedHelp, state.incident.cause)
            assertEquals(AlertDeliveryStatus.Pending, state.dispatchRequest.deliveryStatus)
            assertEquals(AlertRetryState.NotStarted, state.dispatchRequest.retryState)
            assertEquals(1, fixture.incidents.items.value.size)
            assertEquals(1, fixture.requests.items.value.size)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun timeoutCreatesSinglePendingIncidentAndAlert() = runTest {
        val fixture = fixture()
        try {
            startCountdown(fixture)
            fixture.clock.now = 20_000_000_000L
            advanceTimeBy(1_000L)
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(1, fixture.incidents.items.value.size)
            assertEquals(1, fixture.requests.items.value.size)
            assertEquals(1L, fixture.coordinator.validationCounters.timeouts)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun criticalEventSkipsCountdownAndOperationalContextAloneIsNotCritical() = runTest {
        val fixture = fixture()
        try {
            start(fixture)
            fixture.risk.publish(assessment(score = 95, riskLevel = RiskLevel.High, confidence = 0.9, outcomes = listOf(triggered(RuleId.Fall, 0.95), triggered(RuleId.Impact, 0.95))))
            runCurrent()
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.ImmediateAlertRequested)
            assertEquals(1, fixture.requests.items.value.size)
        } finally {
            fixture.close()
            runCurrent()
        }

        val gpsOnly = fixture()
        try {
            start(gpsOnly)
            gpsOnly.risk.publish(assessment(score = 95, riskLevel = RiskLevel.High, confidence = 0.9, gps = GpsQualityStatus.Poor, outcomes = emptyList()))
            runCurrent()
            assertFalse(gpsOnly.validation.states.value is FalsePositiveValidationState.ImmediateAlertRequested)
        } finally {
            gpsOnly.close()
            runCurrent()
        }
    }

    @Test fun lateAssessmentsAndSessionsAreTrackedWithoutMixing() = runTest {
        val fixture = fixture()
        try {
            start(fixture, expectedSessionId = 1L)
            fixture.risk.publish(assessment(assessmentId = 2L, windowId = 2L, start = 1_000_000_000L, end = 2_000_000_000L, score = 20, riskLevel = RiskLevel.Low))
            fixture.risk.publish(assessment(assessmentId = 3L, windowId = 3L, start = 0L, end = 1_000_000_000L, score = 20, riskLevel = RiskLevel.Low))
            fixture.risk.publish(assessment(sessionId = 2L, assessmentId = 4L, windowId = 4L, score = 75, riskLevel = RiskLevel.High, outcomes = listOf(triggered(RuleId.Fall, 0.8))))
            runCurrent()
            assertEquals(1L, fixture.coordinator.validationCounters.lateAssessments)
            assertFalse(fixture.validation.states.value is FalsePositiveValidationState.CountdownActive)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun storesRespectCapacityDropOldestAndClear() {
        val store = InMemoryBoundedValidationStore<Int>(2)
        store.add(1)
        store.add(2)
        store.add(3)
        assertEquals(listOf(2, 3), store.items.value)
        assertEquals(1L, store.droppedCount)
        store.clear()
        assertTrue(store.items.value.isEmpty())
        assertEquals(0L, store.droppedCount)
    }

    @Test fun incidentAndDispatchModelsContainSafeTraceabilityOnly() = runTest {
        val fixture = fixture()
        try {
            startCountdown(fixture)
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-trace")
            runCurrent()
            val incident = fixture.incidents.items.value.single()
            val request = fixture.requests.items.value.single()
            assertEquals(1L, incident.sessionId)
            assertEquals(1L, incident.assessmentId)
            assertNotNull(incident.relevantOutcomes)
            assertEquals(AlertDeliveryStatus.Pending, incident.deliveryStatus)
            assertEquals(AlertDeliveryStatus.Pending, request.deliveryStatus)
            assertEquals(AlertRetryState.NotStarted, request.retryState)
            assertFalse(request.payload.toString().contains("contact", ignoreCase = true))
            assertFalse(request.payload.toString().contains("sensor", ignoreCase = true))
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun manualStopDoesNotCreateIncidentAndCanRecordLastMinorAssessment() = runTest {
        val fixture = fixture()
        try {
            start(fixture)
            fixture.risk.publish(assessment(score = 30, riskLevel = RiskLevel.Low, continuity = MovementContinuityState.Continuing, outcomes = listOf(triggered(RuleId.Impact, 0.5), notTriggered(RuleId.Fall), notTriggered(RuleId.Immobility), notTriggered(RuleId.OrientationChange))))
            fixture.coordinator.stopAndJoin()
            runCurrent()

            assertTrue(fixture.incidents.items.value.isEmpty())
            assertTrue(fixture.requests.items.value.isEmpty())
            assertEquals(1, fixture.minor.items.value.size)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun validationEventsAreOfferedToOfflineSinkOnceByIdempotencyKey() = runTest {
        val sink = FakeOfflineEventSink()
        val fixture = fixture(sink)
        try {
            start(fixture)
            fixture.risk.publish(assessment(score = 30, riskLevel = RiskLevel.Low, continuity = MovementContinuityState.Continuing, outcomes = listOf(triggered(RuleId.Impact, 0.5), notTriggered(RuleId.Fall), notTriggered(RuleId.Immobility), notTriggered(RuleId.OrientationChange))))
            runCurrent()

            assertEquals(listOf("minor-event:1:v1"), sink.keys)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun minorPersistenceFailurePublishesErrorAndDoesNotUpdateStore() = runTest {
        val sink = FakeOfflineEventSink(failMinor = true)
        val fixture = fixture(sink)
        try {
            start(fixture)
            fixture.risk.publish(assessment(score = 30, riskLevel = RiskLevel.Low, continuity = MovementContinuityState.Continuing, outcomes = listOf(triggered(RuleId.Impact, 0.5), notTriggered(RuleId.Fall), notTriggered(RuleId.Immobility), notTriggered(RuleId.OrientationChange))))
            runCurrent()

            assertTrue(fixture.minor.items.value.isEmpty())
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.Error)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun bundlePersistenceFailureDoesNotUpdateStores() = runTest {
        val sink = FakeOfflineEventSink(failBundle = true)
        val fixture = fixture(sink)
        try {
            start(fixture)
            fixture.risk.publish(assessment(score = 95, riskLevel = RiskLevel.High, confidence = 0.95, outcomes = listOf(triggered(RuleId.Impact, 0.95), triggered(RuleId.Fall, 0.95))))
            runCurrent()

            assertTrue(fixture.incidents.items.value.isEmpty())
            assertTrue(fixture.requests.items.value.isEmpty())
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.Error)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun bundleSuccessUpdatesBothStoresAfterSinkCall() = runTest {
        val sink = FakeOfflineEventSink()
        val fixture = fixture(sink)
        sink.onBeforeBundleReturn = {
            assertTrue(fixture.incidents.items.value.isEmpty())
            assertTrue(fixture.requests.items.value.isEmpty())
        }
        try {
            start(fixture)
            fixture.risk.publish(assessment(score = 95, riskLevel = RiskLevel.High, confidence = 0.95, outcomes = listOf(triggered(RuleId.Impact, 0.95), triggered(RuleId.Fall, 0.95))))
            runCurrent()

            assertEquals(listOf("bundle"), sink.order)
            assertEquals(1, fixture.incidents.items.value.size)
            assertEquals(1, fixture.requests.items.value.size)
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.ImmediateAlertRequested)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun minorSuccessUpdatesStoreAfterSinkCall() = runTest {
        val sink = FakeOfflineEventSink()
        val fixture = fixture(sink)
        sink.onBeforeMinorReturn = {
            assertTrue(fixture.minor.items.value.isEmpty())
        }
        try {
            start(fixture)
            fixture.risk.publish(assessment(score = 30, riskLevel = RiskLevel.Low, continuity = MovementContinuityState.Continuing, outcomes = listOf(triggered(RuleId.Impact, 0.5), notTriggered(RuleId.Fall), notTriggered(RuleId.Immobility), notTriggered(RuleId.OrientationChange))))
            runCurrent()

            assertEquals(1, fixture.minor.items.value.size)
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.MinorEventRecorded)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun resetAndJoinCancelsCollectorCountdownAndDeferredTransition() = runTest {
        val fixture = fixture()
        try {
            startCountdown(fixture)
            fixture.coordinator.confirmSafe(1L, 1L, UserResponseSource.Mobile, "safe-cleanup")
            runCurrent()
            fixture.coordinator.resetAndJoin()
            runCurrent()

            assertEquals(FalsePositiveValidationState.Idle, fixture.validation.states.value)
            fixture.risk.publish(assessment(score = 75, riskLevel = RiskLevel.High, outcomes = listOf(triggered(RuleId.Fall, 0.8))))
            runCurrent()
            assertEquals(FalsePositiveValidationState.Idle, fixture.validation.states.value)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun stopAndJoinCancelsCountdownWithoutTimeout() = runTest {
        val fixture = fixture()
        try {
            startCountdown(fixture)
            fixture.coordinator.stopAndJoin()
            fixture.clock.now = 30_000_000_000L
            advanceTimeBy(1_000L)
            runCurrent()

            assertTrue(fixture.incidents.items.value.isEmpty())
            assertTrue(fixture.requests.items.value.isEmpty())
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    private fun TestScope.fixture(offlineEventSink: OfflineEventSink? = null): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixtureJob = SupervisorJob()
        val fixtureScope = CoroutineScope(fixtureJob + dispatcher)
        val clock = FakeClock()
        val risk = InMemoryRiskAssessmentStore()
        val validation = InMemoryFalsePositiveValidationStore()
        val minor = InMemoryBoundedValidationStore<com.example.sos_segundoplano.domain.validation.MinorEvent>(4)
        val incidents = InMemoryBoundedValidationStore<com.example.sos_segundoplano.domain.validation.LocalIncident>(4)
        val requests = InMemoryBoundedValidationStore<com.example.sos_segundoplano.domain.validation.AlertDispatchRequest>(4)
        val coordinator = FalsePositiveValidationCoordinator(
            risk,
            validation,
            minor,
            incidents,
            requests,
            FalsePositiveValidationConfig(),
            clock,
            dispatcher,
            { 1L },
            { 1L },
            { 1L },
            externalScope = fixtureScope
        )
        coordinator.setOfflineEventSink(offlineEventSink ?: FakeOfflineEventSink())
        return Fixture(clock, risk, validation, minor, incidents, requests, coordinator, fixtureJob)
    }

    private suspend fun Fixture.close() {
        coordinator.resetAndJoin()
        fixtureJob.cancelAndJoin()
    }

    private fun TestScope.start(fixture: Fixture, expectedSessionId: Long? = null) {
        fixture.coordinator.start(expectedSessionId)
        runCurrent()
    }

    private fun TestScope.startCountdown(fixture: Fixture) {
        start(fixture)
        fixture.risk.publish(assessment(score = 75, riskLevel = RiskLevel.High, outcomes = listOf(triggered(RuleId.Fall, 0.8), triggered(RuleId.Immobility, 0.7))))
        runCurrent()
    }

    private data class Fixture(
        val clock: FakeClock,
        val risk: InMemoryRiskAssessmentStore,
        val validation: InMemoryFalsePositiveValidationStore,
        val minor: InMemoryBoundedValidationStore<com.example.sos_segundoplano.domain.validation.MinorEvent>,
        val incidents: InMemoryBoundedValidationStore<com.example.sos_segundoplano.domain.validation.LocalIncident>,
        val requests: InMemoryBoundedValidationStore<com.example.sos_segundoplano.domain.validation.AlertDispatchRequest>,
        val coordinator: FalsePositiveValidationCoordinator,
        val fixtureJob: Job
    )

    private class FakeClock(var now: Long = 0L) : MonotonicClock {
        override fun elapsedRealtimeNanos(): Long = now
    }

    private class FakeOfflineEventSink(
        private val failMinor: Boolean = false,
        private val failBundle: Boolean = false
    ) : OfflineEventSink {
        val keys = mutableListOf<String>()
        val order = mutableListOf<String>()
        var onBeforeMinorReturn: () -> Unit = {}
        var onBeforeBundleReturn: () -> Unit = {}

        override suspend fun enqueueMinorEvent(event: MinorEvent): OfflineQueueEnqueueResult {
            order.add("minor")
            if (failMinor) return OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_queue_storage_failed")
            val result = enqueue(idempotencyKey(OfflineEventType.MinorEvent, event.eventId.toString(), 1))
            onBeforeMinorReturn()
            return result
        }

        override suspend fun enqueueIncident(incident: LocalIncident): OfflineQueueEnqueueResult = enqueue(
            idempotencyKey(OfflineEventType.LocalIncident, incident.incidentId.toString(), 1)
        )

        override suspend fun enqueueAlertRequest(request: AlertDispatchRequest): OfflineQueueEnqueueResult = enqueue(
            idempotencyKey(OfflineEventType.AlertDispatchRequest, request.requestId.toString(), 1)
        )

        override suspend fun enqueueIncidentBundle(incident: LocalIncident, request: AlertDispatchRequest): OfflineQueueEnqueueResult {
            order.add("bundle")
            if (failBundle) return OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_queue_storage_failed")
            enqueueIncident(incident)
            val result = enqueueAlertRequest(request)
            onBeforeBundleReturn()
            return result
        }

        private fun enqueue(key: String): OfflineQueueEnqueueResult {
            if (key in keys) return OfflineQueueEnqueueResult.AlreadyExists(key)
            keys.add(key)
            return OfflineQueueEnqueueResult.PersistedAndScheduled(keys.size.toLong(), key)
        }
    }

    private fun rejects(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private fun assessment(
        sessionId: Long = 1L,
        assessmentId: Long = 1L,
        windowId: Long = 1L,
        start: Long = 0L,
        end: Long = 1_000_000_000L,
        score: Int? = 20,
        riskLevel: RiskLevel = RiskLevel.Low,
        confidence: Double = 0.8,
        continuity: MovementContinuityState = MovementContinuityState.Stopped,
        gps: GpsQualityStatus = GpsQualityStatus.Good,
        outcomes: List<RuleOutcome> = emptyList()
    ): RiskAssessment = RiskAssessment(
        sessionId = sessionId,
        assessmentId = assessmentId,
        windowId = windowId,
        startNanos = start,
        endNanos = end,
        score = score,
        riskLevel = riskLevel,
        confidence = confidence,
        outcomes = outcomes,
        contributions = outcomes.map { RiskContribution(it.ruleId, 1.0, 1.0) },
        gpsQuality = GpsQualityEvaluation(gps, null, null, 1.0),
        deviceReadiness = DeviceReadinessEvaluation(BatteryReadinessStatus.Normal, 80, false, ConnectivityReadinessStatus.Available, true, null, null, true, 1.0),
        movementContinuity = continuity,
        droppedProcessedWindows = 0L,
        lateWindows = 0L,
        droppedRawEvents = 0L,
        ruleSetVersion = "local-rules-v1",
        partialWindow = false
    )

    private fun triggered(ruleId: RuleId, severity: Double): RuleOutcome = outcome(ruleId, RuleEvaluationStatus.Triggered, severity)
    private fun notTriggered(ruleId: RuleId): RuleOutcome = outcome(ruleId, RuleEvaluationStatus.NotTriggered, 0.0)
    private fun outcome(ruleId: RuleId, status: RuleEvaluationStatus, severity: Double?): RuleOutcome = RuleOutcome(
        ruleId = ruleId,
        status = status,
        severity = severity,
        confidence = 1.0,
        evidence = RuleEvidence(mapOf("severity" to severity)),
        usedSignals = emptySet(),
        missingSignals = emptySet(),
        thresholds = emptyMap(),
        startNanos = 0L,
        endNanos = 1_000_000_000L,
        reason = ruleId.name,
        contribution = RiskContribution(ruleId, 1.0, 1.0),
        ruleSetVersion = "local-rules-v1"
    )
}
