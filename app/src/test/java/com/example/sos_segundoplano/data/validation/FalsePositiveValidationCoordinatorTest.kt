package com.example.sos_segundoplano.data.validation

import com.example.sos_segundoplano.data.rules.InMemoryRiskAssessmentStore
import com.example.sos_segundoplano.data.remote.incident.IncidentRemoteCreator
import com.example.sos_segundoplano.data.remote.incident.AutomaticSosAlertCreator
import com.example.sos_segundoplano.data.trip.InMemoryTripSessionStore
import com.example.sos_segundoplano.data.trip.TripSessionStore
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
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.validation.MinorEvent
import com.example.sos_segundoplano.domain.validation.MinorEventType
import com.example.sos_segundoplano.domain.validation.MonotonicClock
import com.example.sos_segundoplano.domain.validation.UserResponseSource
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.model.TripSessionState
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
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class FalsePositiveValidationCoordinatorTest {
    @Test fun configDefaultsAndValidationAreExplicit() {
        val config = FalsePositiveValidationConfig()
        assertEquals(20_000_000_000L, config.countdownDurationNanos)
        assertEquals(1_000_000_000L, config.visualUpdateIntervalNanos)
        assertEquals(40, config.minimumValidationScore)
        assertEquals(30, config.correlatedCandidateMinimumScore)
        assertEquals(90, config.criticalScore)
        assertEquals(0.60, config.minimumConfidence, 0.0)
        assertEquals(0.50, config.correlatedCandidateMinimumConfidence, 0.0)
        assertEquals(0.80, config.criticalConfidence, 0.0)
        assertEquals(0.90, config.criticalFallSeverity, 0.0)
        assertEquals(0.90, config.criticalImpactSeverity, 0.0)
        assertEquals(39, config.bumpMaximumScore)
        assertEquals(0.50, config.immobilityCountdownMinimumSeverity, 0.0)
        assertEquals(0.55, config.impactImmobilityMinimumImpactSeverity, 0.0)
        assertEquals(0.85, config.impactImmobilityDirectImpactSeverity, 0.0)
        assertEquals(0.30, config.secondarySupportMinimumSeverity, 0.0)
        assertEquals(10_000_000_000L, config.postSafeSuppressionNanos)
        assertEquals("false-positive-validation-v1", config.policyVersion)
        rejects { FalsePositiveValidationConfig(countdownDurationNanos = 0L) }
        rejects { FalsePositiveValidationConfig(maxMinorEvents = 0) }
        rejects { FalsePositiveValidationConfig(minimumConfidence = -0.1) }
        rejects { FalsePositiveValidationConfig(minimumValidationScore = -1) }
        rejects { FalsePositiveValidationConfig(correlatedCandidateMinimumScore = 41) }
        rejects { FalsePositiveValidationConfig(correlatedCandidateMinimumConfidence = 0.61) }
        rejects { FalsePositiveValidationConfig(impactImmobilityMinimumImpactSeverity = -0.1) }
        rejects { FalsePositiveValidationConfig(impactImmobilityDirectImpactSeverity = 0.4) }
        rejects { FalsePositiveValidationConfig(secondarySupportMinimumSeverity = 1.1) }
        rejects { FalsePositiveValidationConfig(postSafeSuppressionNanos = -1L) }
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

    @Test fun mlDetectionStartsCountdownWithoutBypassingUserConfirmation() = runTest {
        val fixture = fixture()
        try {
            start(fixture)
            fixture.risk.publish(
                assessment(
                    score = 0,
                    riskLevel = RiskLevel.Low,
                    confidence = 0.40,
                    continuity = MovementContinuityState.Continuing,
                    outcomes = listOf(
                        notTriggered(RuleId.Impact),
                        notTriggered(RuleId.Fall),
                        notTriggered(RuleId.Immobility),
                        notTriggered(RuleId.OrientationChange),
                        notTriggered(RuleId.HarshBraking)
                    )
                ).copy(
                    mlProbability = 0.91,
                    mlDetected = true,
                    mlModelVersion = "1.1.0-pilot-ready"
                )
            )
            runCurrent()

            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.CountdownActive)
            assertTrue(fixture.incidents.items.value.isEmpty())
            assertTrue(fixture.requests.items.value.isEmpty())
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun correlatedCrashPatternStartsCountdownBelowGenericFortyPointGate() = runTest {
        val fixture = fixture()
        try {
            start(fixture)
            fixture.risk.publish(
                assessment(
                    score = 34,
                    riskLevel = RiskLevel.Low,
                    confidence = 0.55,
                    continuity = MovementContinuityState.Stopped,
                    outcomes = listOf(
                        triggered(RuleId.Impact, 0.70),
                        triggered(RuleId.Immobility, 0.70),
                        triggered(RuleId.OrientationChange, 0.40),
                        notTriggered(RuleId.Fall),
                        notTriggered(RuleId.HarshBraking)
                    )
                )
            )
            runCurrent()

            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.CountdownActive)
            assertTrue(fixture.minor.items.value.isEmpty())
            assertTrue(fixture.incidents.items.value.isEmpty())
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun moderateImpactAndImmobilityStartsFalsePositiveCountdown() = runTest {
        val fixture = fixture()
        try {
            start(fixture)
            fixture.risk.publish(
                assessment(
                    score = 45,
                    riskLevel = RiskLevel.Medium,
                    outcomes = listOf(
                        triggered(RuleId.Impact, 0.70),
                        triggered(RuleId.Immobility, 0.70),
                        notTriggered(RuleId.Fall),
                        notTriggered(RuleId.OrientationChange),
                        notTriggered(RuleId.HarshBraking)
                    )
                )
            )
            runCurrent()

            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.CountdownActive)
            assertTrue(fixture.incidents.items.value.isEmpty())
            assertTrue(fixture.requests.items.value.isEmpty())
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun impactAndOrientationCanStartCountdownBeforeImmobilityWindowCompletes() = runTest {
        val fixture = fixture()
        try {
            start(fixture)
            fixture.risk.publish(
                assessment(
                    score = 33,
                    riskLevel = RiskLevel.Low,
                    confidence = 0.55,
                    continuity = MovementContinuityState.Intermittent,
                    outcomes = listOf(
                        triggered(RuleId.Impact, 0.65),
                        triggered(RuleId.OrientationChange, 0.45),
                        notTriggered(RuleId.Immobility),
                        notTriggered(RuleId.Fall),
                        notTriggered(RuleId.HarshBraking)
                    )
                )
            )
            runCurrent()

            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.CountdownActive)
            assertTrue(fixture.incidents.items.value.isEmpty())
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun moderateImpactImmobilityAndOrientationSupportStartsCountdown() = runTest {
        val fixture = fixture()
        try {
            start(fixture)
            fixture.risk.publish(
                assessment(
                    score = 45,
                    riskLevel = RiskLevel.Medium,
                    outcomes = listOf(
                        triggered(RuleId.Impact, 0.70),
                        triggered(RuleId.Immobility, 0.70),
                        triggered(RuleId.OrientationChange, 0.45),
                        notTriggered(RuleId.Fall),
                        notTriggered(RuleId.HarshBraking)
                    )
                )
            )
            runCurrent()

            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.CountdownActive)
            assertTrue(fixture.incidents.items.value.isEmpty())
            assertTrue(fixture.requests.items.value.isEmpty())
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun strongImpactFollowedByImmobilityStartsCountdownWithoutExtraSignal() = runTest {
        val fixture = fixture()
        try {
            start(fixture)
            fixture.risk.publish(
                assessment(
                    score = 45,
                    riskLevel = RiskLevel.Medium,
                    outcomes = listOf(
                        triggered(RuleId.Impact, 0.90),
                        triggered(RuleId.Immobility, 0.70),
                        notTriggered(RuleId.Fall),
                        notTriggered(RuleId.OrientationChange),
                        notTriggered(RuleId.HarshBraking)
                    )
                )
            )
            runCurrent()

            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.CountdownActive)
        } finally {
            fixture.close()
            runCurrent()
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
            assertEquals(0, fixture.remoteCreator.calls)
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

    @Test fun confirmSafeSuppressesImmediateReEscalationFromSamePhysicalEventWindow() = runTest {
        val fixture = fixture()
        try {
            startCountdown(fixture)
            fixture.coordinator.confirmSafe(1L, 1L, UserResponseSource.Mobile, "safe-suppression")
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.Monitoring)

            fixture.risk.publish(
                assessment(
                    assessmentId = 2L,
                    windowId = 2L,
                    start = 4_000_000_000L,
                    end = 5_000_000_000L,
                    score = 75,
                    riskLevel = RiskLevel.High,
                    outcomes = listOf(triggered(RuleId.Fall, 0.8), triggered(RuleId.Immobility, 0.7))
                )
            )
            runCurrent()
            assertFalse(fixture.validation.states.value is FalsePositiveValidationState.CountdownActive)

            fixture.risk.publish(
                assessment(
                    assessmentId = 3L,
                    windowId = 3L,
                    start = 11_000_000_000L,
                    end = 12_000_000_000L,
                    score = 75,
                    riskLevel = RiskLevel.High,
                    outcomes = listOf(triggered(RuleId.Fall, 0.8), triggered(RuleId.Immobility, 0.7))
                )
            )
            runCurrent()
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.CountdownActive)
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
            assertEquals(1, fixture.remoteCreator.calls)
            assertEquals("remote-incident-1", state.incident.remoteIncidentId)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun requestHelpPublishesTransientHelpBeforePersistedIncidentGenerated() = runTest {
        val sink = FakeOfflineEventSink()
        val fixture = fixture(sink)
        sink.onBeforeBundleReturn = {
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.HelpRequested)
            assertTrue(fixture.incidents.items.value.isEmpty())
            assertTrue(fixture.requests.items.value.isEmpty())
        }
        try {
            startCountdown(fixture)
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-before-persist")
            runCurrent()

            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.IncidentGenerated)
            assertEquals(1, fixture.incidents.items.value.size)
            assertEquals(1, fixture.requests.items.value.size)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun requestHelpRemoteHttpFailurePreservesEvidenceWithoutPublishingTerminalIncident() = runTest {
        val failure = IncidentRemoteCreationStatus.HttpError(500, "server_error")
        val fixture = fixture(incidentRemoteCreator = FakeIncidentRemoteCreator(failure))
        try {
            startCountdown(fixture)
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-http-failure")
            runCurrent()

            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.Error)
            assertEquals(1, fixture.incidents.items.value.size)
            assertEquals(1, fixture.requests.items.value.size)
            assertEquals(IncidentCause.UserRequestedHelp, fixture.incidents.items.value.single().cause)
            assertEquals(failure, fixture.incidents.items.value.single().remoteCreationStatus)
            assertEquals(1, fixture.remoteCreator.calls)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun requestHelpRemoteNetworkFailurePreservesEvidenceWithoutPublishingTerminalIncident() = runTest {
        val failure = IncidentRemoteCreationStatus.NetworkUnavailable("network_unavailable")
        val fixture = fixture(incidentRemoteCreator = FakeIncidentRemoteCreator(failure))
        try {
            startCountdown(fixture)
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-network-failure")
            runCurrent()

            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.Error)
            assertEquals(1, fixture.incidents.items.value.size)
            assertEquals(1, fixture.requests.items.value.size)
            assertEquals(IncidentCause.UserRequestedHelp, fixture.incidents.items.value.single().cause)
            assertEquals(failure, fixture.incidents.items.value.single().remoteCreationStatus)
            assertEquals(1, fixture.remoteCreator.calls)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun requestHelpRemoteTimeoutPreservesEvidenceWithoutPublishingTerminalIncident() = runTest {
        val failure = IncidentRemoteCreationStatus.Timeout("network_timeout")
        val fixture = fixture(incidentRemoteCreator = FakeIncidentRemoteCreator(failure))
        try {
            startCountdown(fixture)
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-timeout-failure")
            runCurrent()

            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.Error)
            assertEquals(1, fixture.incidents.items.value.size)
            assertEquals(IncidentCause.UserRequestedHelp, fixture.incidents.items.value.single().cause)
            assertEquals(failure, fixture.incidents.items.value.single().remoteCreationStatus)
            assertEquals(1, fixture.remoteCreator.calls)
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
            val state = fixture.validation.states.value as FalsePositiveValidationState.IncidentGenerated
            assertEquals("remote-incident-1", state.incident.remoteIncidentId)
            assertEquals(IncidentRemoteCreationStatus.Success("remote-incident-1"), state.incident.remoteCreationStatus)
            assertEquals("remote-incident-1", fixture.incidents.items.value.single().remoteIncidentId)
            assertEquals(1, fixture.remoteCreator.calls)
            assertEquals(1L, fixture.coordinator.validationCounters.timeouts)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun countdownTimeoutCreatesStableDurableIdentifiersAndUsesInjectedDetectionTime() = runTest {
        val expectedDetectedAtEpochMillis = 1_725_000_123_456L
        val fixture = fixture(nowEpochMillis = { expectedDetectedAtEpochMillis })
        try {
            startCountdown(fixture)
            fixture.clock.now = 20_000_000_000L
            advanceTimeBy(1_000L)
            runCurrent()

            val state = fixture.validation.states.value as FalsePositiveValidationState.IncidentGenerated
            val incidentId = requireNotNull(state.incident.clientIncidentId)
            val requestId = requireNotNull(state.dispatchRequest.clientAlertRequestId)
            UUID.fromString(incidentId)
            UUID.fromString(requestId)
            assertTrue(incidentId != requestId)
            assertEquals(expectedDetectedAtEpochMillis, state.incident.detectedAtEpochMillis)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun persistenceRetryReusesDurableIncidentAndAlertIdentifiers() = runTest {
        val sink = FailingThenRecordingIncidentBundleSink()
        val expectedDetectedAtEpochMillis = 1_725_000_654_321L
        val fixture = fixture(offlineEventSink = sink, nowEpochMillis = { expectedDetectedAtEpochMillis })
        try {
            startCountdown(fixture)
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-retry-identities")
            runCurrent()
            assertEquals(1, sink.bundles.size)

            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(2, sink.bundles.size)

            val initial = sink.bundles[0]
            val retry = sink.bundles[1]
            assertEquals(initial.first.clientIncidentId, retry.first.clientIncidentId)
            assertEquals(initial.second.clientAlertRequestId, retry.second.clientAlertRequestId)
            assertEquals(initial.first.detectedAtEpochMillis, retry.first.detectedAtEpochMillis)
            assertEquals(expectedDetectedAtEpochMillis, retry.first.detectedAtEpochMillis)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun automaticBundleResolvesRemoteTripBeforeDurableEnqueue() = runTest {
        val sink = RecordingAutomaticBundleSink()
        val automatic = RecordingAutomaticSosCreator(location = location(), resolvedRemoteTripId = "trip-A")
        val fixture = fixture(offlineEventSink = sink, automaticSosAlertCreator = automatic)
        try {
            startCountdown(fixture)
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-remote-trip-before-enqueue")
            runCurrent()

            val enqueued = sink.enqueuedBundles.single()
            assertEquals(TRIP_SESSION_KEY, enqueued.first.tripSessionKey)
            assertEquals("trip-A", enqueued.first.remoteTripId)
            UUID.fromString(requireNotNull(enqueued.first.clientIncidentId))
            assertNotNull(enqueued.second.clientAlertRequestId)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun automaticBundlePersistenceRetryPreservesResolvedRemoteTripAndIdentity() = runTest {
        val sink = RecordingAutomaticBundleSink(failInitialBundleAttempts = 1)
        val automatic = RecordingAutomaticSosCreator(
            location = location(),
            retryLocation = location(latitude = 20.6736, longitude = -103.3440),
            resolvedRemoteTripId = "trip-A"
        )
        val fixture = fixture(offlineEventSink = sink, automaticSosAlertCreator = automatic)
        try {
            startCountdown(fixture)
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-remote-trip-retry")
            runCurrent()
            val retryState = fixture.validation.states.value as FalsePositiveValidationState.IncidentDeliveryRetrying
            assertEquals(1, retryState.attempt)
            assertEquals(3, retryState.maxAttempts)
            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(2, sink.enqueuedBundles.size)
            val initial = sink.enqueuedBundles[0]
            val retry = sink.enqueuedBundles[1]
            assertEquals(initial.first.clientIncidentId, retry.first.clientIncidentId)
            assertEquals(initial.second.clientAlertRequestId, retry.second.clientAlertRequestId)
            assertEquals(initial.first.tripSessionKey, retry.first.tripSessionKey)
            assertEquals(initial.first.remoteTripId, retry.first.remoteTripId)
            assertEquals(initial.first.latitude, retry.first.latitude)
            assertEquals(initial.first.longitude, retry.first.longitude)
            assertEquals("trip-A", retry.first.remoteTripId)
            assertEquals(1, automatic.remoteTripResolutionCalls)
            assertEquals(1, automatic.locationCalls)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun automaticBundleWithoutRemoteTripDoesNotEnqueueOrInventIdentity() = runTest {
        val sink = RecordingAutomaticBundleSink()
        val automatic = RecordingAutomaticSosCreator(location = location(), resolvedRemoteTripId = null)
        val fixture = fixture(offlineEventSink = sink, automaticSosAlertCreator = automatic)
        try {
            startCountdown(fixture)
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-remote-trip-missing")
            runCurrent()

            assertTrue(sink.enqueuedBundles.isEmpty())
            assertTrue(automatic.submitted.isEmpty())
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.IncidentDeliveryRetrying)
            assertEquals(1, automatic.remoteTripResolutionCalls)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun userRequestedHelpCreatesValidDurableIdentifiersAndInjectedDetectionTime() = runTest {
        val expectedDetectedAtEpochMillis = 1_725_000_999_999L
        val fixture = fixture(nowEpochMillis = { expectedDetectedAtEpochMillis })
        try {
            startCountdown(fixture)
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-durable-identities")
            runCurrent()

            val state = fixture.validation.states.value as FalsePositiveValidationState.IncidentGenerated
            val incidentId = requireNotNull(state.incident.clientIncidentId)
            val requestId = requireNotNull(state.dispatchRequest.clientAlertRequestId)
            UUID.fromString(incidentId)
            UUID.fromString(requestId)
            assertTrue(incidentId != requestId)
            assertEquals(expectedDetectedAtEpochMillis, state.incident.detectedAtEpochMillis)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun countdownTimeoutPersistsCapturedLocationBeforeAutomaticSosWithoutUsingLegacyCreator() = runTest {
        val sink = RecordingAutomaticBundleSink()
        val automatic = RecordingAutomaticSosCreator(location = location())
        val legacy = FakeIncidentRemoteCreator()
        val detectedAt = 1_725_000_123_456L
        val fixture = fixture(sink, legacy, { detectedAt }, automatic)
        try {
            startCountdown(fixture)
            fixture.clock.now = 20_000_000_000L
            advanceTimeBy(1_000L)
            runCurrent()

            val persisted = sink.enqueuedBundles.single()
            val submitted = automatic.submitted.single()
            assertEquals(1, automatic.locationCalls)
            assertEquals(0, legacy.calls)
            assertEquals(0, sink.updatedBundles.size)
            assertEquals(persisted.first.clientIncidentId, submitted.first.clientIncidentId)
            assertEquals(persisted.second.clientAlertRequestId, submitted.second.clientAlertRequestId)
            assertEquals(detectedAt, persisted.first.detectedAtEpochMillis)
            assertEquals(1L, persisted.first.incidentId)
            assertEquals(1L, persisted.second.requestId)
            assertEquals(19.4326, persisted.first.latitude)
            assertEquals(-99.1332, persisted.first.longitude)
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.IncidentGenerated)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun userRequestedHelpWithUnavailableLocationDoesNotSubmitOrGenerateIncident() = runTest {
        val sink = RecordingAutomaticBundleSink()
        val automatic = RecordingAutomaticSosCreator(location = null)
        val fixture = fixture(offlineEventSink = sink, automaticSosAlertCreator = automatic)
        try {
            startCountdown(fixture)
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-location-unavailable")
            runCurrent()

            assertEquals(1, automatic.locationCalls)
            assertTrue(automatic.submitted.isEmpty())
            assertTrue(fixture.incidents.items.value.isEmpty())
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.IncidentDeliveryRetrying)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun automaticSosPersistsCompleteBundleWithoutSecondLocationMutation() = runTest {
        val sink = RecordingAutomaticBundleSink(failUpdate = true)
        val automatic = RecordingAutomaticSosCreator(location = location())
        val fixture = fixture(offlineEventSink = sink, automaticSosAlertCreator = automatic)
        try {
            startCountdown(fixture)
            fixture.clock.now = 20_000_000_000L
            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(0, sink.updatedBundles.size)
            assertEquals(1, sink.enqueuedBundles.size)
            assertEquals(19.4326, sink.enqueuedBundles.single().first.latitude)
            assertEquals(-99.1332, sink.enqueuedBundles.single().first.longitude)
            assertEquals(1, automatic.submitted.size)
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.IncidentGenerated)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun automaticSosFailuresDoNotGenerateTerminalIncident() = runTest {
        val statuses = listOf(
            IncidentRemoteCreationStatus.HttpError(400, "request_rejected"),
            IncidentRemoteCreationStatus.NetworkUnavailable("network_unavailable"),
            IncidentRemoteCreationStatus.Timeout("network_timeout"),
            IncidentRemoteCreationStatus.InvalidResponse("response_invalid"),
            IncidentRemoteCreationStatus.MissingRequiredData("active_remote_trip_missing")
        )
        statuses.forEach { status ->
            val fixture = fixture(
                offlineEventSink = RecordingAutomaticBundleSink(),
                automaticSosAlertCreator = RecordingAutomaticSosCreator(location = location(), status = status)
            )
            try {
                startCountdown(fixture)
                fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-${status::class.simpleName}")
                runCurrent()

                assertTrue(fixture.incidents.items.value.isEmpty())
                if (status is IncidentRemoteCreationStatus.HttpError && status.statusCode == 400) {
                    assertTrue(fixture.validation.states.value is FalsePositiveValidationState.Error)
                } else {
                    assertTrue(fixture.validation.states.value is FalsePositiveValidationState.IncidentDeliveryRetrying)
                }
            } finally {
                fixture.close()
                runCurrent()
            }
        }
    }

    @Test fun automaticPermanentHttpFailureDoesNotScheduleAnotherCoordinatorSubmission() = runTest {
        val sink = RecordingAutomaticBundleSink()
        val automatic = RecordingAutomaticSosCreator(
            location = location(),
            status = IncidentRemoteCreationStatus.HttpError(400, "request_rejected")
        )
        val fixture = fixture(offlineEventSink = sink, automaticSosAlertCreator = automatic)
        try {
            startCountdown(fixture)
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-permanent-http")
            runCurrent()

            assertEquals(1, sink.releaseCalls)
            assertEquals(true, sink.lastReleasePermanent)
            assertEquals(1, automatic.submitted.size)
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.Error)

            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(1, automatic.submitted.size)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun automaticNetworkFailureReleasesClaimForDurableRetry() = runTest {
        val sink = RecordingAutomaticBundleSink()
        val fixture = fixture(
            offlineEventSink = sink,
            automaticSosAlertCreator = RecordingAutomaticSosCreator(
                location = location(),
                status = IncidentRemoteCreationStatus.NetworkUnavailable("network_unavailable")
            )
        )
        try {
            startCountdown(fixture)
            fixture.coordinator.requestHelp(1L, 1L, UserResponseSource.Mobile, "help-network")
            runCurrent()

            assertEquals(1, sink.releaseCalls)
            assertEquals(false, sink.lastReleasePermanent)
            assertTrue(fixture.incidents.items.value.isEmpty())
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.IncidentDeliveryRetrying)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun timeoutRemoteNetworkFailureKeepsLocalIncidentWithoutPublishingTerminalIncident() = runTest {
        val fixture = fixture(incidentRemoteCreator = FakeIncidentRemoteCreator(IncidentRemoteCreationStatus.NetworkUnavailable("network_unavailable")))
        try {
            startCountdown(fixture)
            fixture.clock.now = 20_000_000_000L
            advanceTimeBy(1_000L)
            runCurrent()

            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.Error)
            assertEquals(1, fixture.incidents.items.value.size)
            assertEquals(1, fixture.requests.items.value.size)
            assertEquals(IncidentCause.Timeout, fixture.incidents.items.value.single().cause)
            assertEquals(null, fixture.incidents.items.value.single().remoteIncidentId)
            assertEquals(IncidentRemoteCreationStatus.NetworkUnavailable("network_unavailable"), fixture.incidents.items.value.single().remoteCreationStatus)
            assertEquals(1, fixture.remoteCreator.calls)
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
            assertEquals(1, fixture.remoteCreator.calls)
            val state = fixture.validation.states.value as FalsePositiveValidationState.ImmediateAlertRequested
            assertEquals("remote-incident-1", state.incident.remoteIncidentId)
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
            assertEquals(TRIP_SESSION_KEY, incident.tripSessionKey)
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
        val fixture = fixture(sink, tripSessionState = TripSessionState.Active(TRIP_SESSION_KEY))
        try {
            start(fixture)
            fixture.risk.publish(assessment(score = 95, riskLevel = RiskLevel.High, confidence = 0.95, outcomes = listOf(triggered(RuleId.Impact, 0.95), triggered(RuleId.Fall, 0.95))))
            runCurrent()

            assertTrue(fixture.incidents.items.value.isEmpty())
            assertTrue(fixture.requests.items.value.isEmpty())
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.IncidentDeliveryRetrying)
            assertFalse(fixture.validation.states.value is FalsePositiveValidationState.IncidentGenerated)
            assertFalse(fixture.validation.states.value is FalsePositiveValidationState.ImmediateAlertRequested)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun bundlePersistenceFailureBecomesTerminalOnlyAfterBoundedRetriesAreExhausted() = runTest {
        val sink = FakeOfflineEventSink(failBundle = true)
        val fixture = fixture(sink, tripSessionState = TripSessionState.Active(TRIP_SESSION_KEY))
        try {
            start(fixture)
            fixture.risk.publish(assessment(score = 95, riskLevel = RiskLevel.High, confidence = 0.95, outcomes = listOf(triggered(RuleId.Impact, 0.95), triggered(RuleId.Fall, 0.95))))
            runCurrent()
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.IncidentDeliveryRetrying)

            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.IncidentDeliveryRetrying)
            advanceTimeBy(2_000L)
            runCurrent()
            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.IncidentDeliveryRetrying)
            advanceTimeBy(3_000L)
            runCurrent()

            assertTrue(fixture.validation.states.value is FalsePositiveValidationState.Error)
            assertTrue(fixture.incidents.items.value.isEmpty())
            assertTrue(fixture.requests.items.value.isEmpty())
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    @Test fun bundleSuccessUpdatesBothStoresAfterSinkCall() = runTest {
        val sink = FakeOfflineEventSink()
        val fixture = fixture(sink, tripSessionState = TripSessionState.Active(TRIP_SESSION_KEY))
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

    private fun TestScope.fixture(
        offlineEventSink: OfflineEventSink? = null,
        incidentRemoteCreator: FakeIncidentRemoteCreator = FakeIncidentRemoteCreator(),
        nowEpochMillis: () -> Long = { 1_700_000_000_000L },
        automaticSosAlertCreator: AutomaticSosAlertCreator? = null,
        tripSessionState: TripSessionState = TripSessionState.Idle,
    ): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixtureJob = SupervisorJob()
        val fixtureScope = CoroutineScope(fixtureJob + dispatcher)
        val clock = FakeClock()
        val risk = InMemoryRiskAssessmentStore()
        val validation = InMemoryFalsePositiveValidationStore()
        val minor = InMemoryBoundedValidationStore<com.example.sos_segundoplano.domain.validation.MinorEvent>(4)
        val incidents = InMemoryBoundedValidationStore<com.example.sos_segundoplano.domain.validation.LocalIncident>(4)
        val requests = InMemoryBoundedValidationStore<com.example.sos_segundoplano.domain.validation.AlertDispatchRequest>(4)
        val tripSessionStore = InMemoryTripSessionStore(tripSessionState)
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
            nowEpochMillis,
            externalScope = fixtureScope,
            tripSessionStore = tripSessionStore,
        )
        coordinator.setOfflineEventSink(offlineEventSink ?: FakeOfflineEventSink())
        coordinator.setIncidentRemoteCreator(incidentRemoteCreator)
        automaticSosAlertCreator?.let(coordinator::setAutomaticSosAlertCreator)
        return Fixture(clock, risk, validation, minor, incidents, requests, coordinator, incidentRemoteCreator, fixtureJob, tripSessionStore)
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
        fixture.tripSessionStore.setState(TripSessionState.Active(TRIP_SESSION_KEY))
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
        val remoteCreator: FakeIncidentRemoteCreator,
        val fixtureJob: Job,
        val tripSessionStore: TripSessionStore,
    )

    private class FakeIncidentRemoteCreator(
        private val status: IncidentRemoteCreationStatus = IncidentRemoteCreationStatus.Success("remote-incident-1")
    ) : IncidentRemoteCreator {
        var calls = 0

        override suspend fun createIncident(incident: LocalIncident): LocalIncident {
            calls++
            return when (status) {
                is IncidentRemoteCreationStatus.Success -> incident.copy(
                    remoteIncidentId = status.incidentId,
                    remoteCreationStatus = status
                )
                else -> incident.copy(remoteCreationStatus = status)
            }
        }
    }

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

    private class FailingThenRecordingIncidentBundleSink : OfflineEventSink {
        val bundles = mutableListOf<Pair<LocalIncident, AlertDispatchRequest>>()

        override suspend fun enqueueMinorEvent(event: MinorEvent): OfflineQueueEnqueueResult = error("unused")

        override suspend fun enqueueIncident(incident: LocalIncident): OfflineQueueEnqueueResult = error("unused")

        override suspend fun enqueueAlertRequest(request: AlertDispatchRequest): OfflineQueueEnqueueResult = error("unused")

        override suspend fun enqueueIncidentBundle(
            incident: LocalIncident,
            request: AlertDispatchRequest
        ): OfflineQueueEnqueueResult {
            bundles += incident to request
            return if (bundles.size == 1) {
                OfflineQueueEnqueueResult.PersistenceFailed(
                    OfflineSyncErrorCategory.Serialization,
                    "offline_queue_storage_failed"
                )
            } else {
                OfflineQueueEnqueueResult.PersistedAndScheduled(
                    queueItemId = 1L,
                    idempotencyKey = "incident-bundle"
                )
            }
        }
    }

    private class RecordingAutomaticBundleSink(
        private val failUpdate: Boolean = false,
        private val failInitialBundleAttempts: Int = 0
    ) : OfflineEventSink {
        val enqueuedBundles = mutableListOf<Pair<LocalIncident, AlertDispatchRequest>>()
        val updatedBundles = mutableListOf<Pair<LocalIncident, AlertDispatchRequest>>()
        var releaseCalls = 0
        var lastReleasePermanent: Boolean? = null

        override suspend fun enqueueMinorEvent(event: MinorEvent): OfflineQueueEnqueueResult = error("unused")
        override suspend fun enqueueIncident(incident: LocalIncident): OfflineQueueEnqueueResult = error("unused")
        override suspend fun enqueueAlertRequest(request: AlertDispatchRequest): OfflineQueueEnqueueResult = error("unused")
        override suspend fun enqueueIncidentBundle(incident: LocalIncident, request: AlertDispatchRequest): OfflineQueueEnqueueResult {
            enqueuedBundles += incident to request
            return if (enqueuedBundles.size <= failInitialBundleAttempts) {
                OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_queue_storage_failed")
            } else {
                OfflineQueueEnqueueResult.PersistedAndScheduled(1L, "bundle")
            }
        }

        override suspend fun updateIncidentBundle(incident: LocalIncident, request: AlertDispatchRequest): OfflineQueueEnqueueResult {
            updatedBundles += incident to request
            return if (failUpdate) {
                OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_location_persistence_failed")
            } else {
                OfflineQueueEnqueueResult.PersistedAndScheduled(1L, "bundle")
            }
        }

        override suspend fun claimAutomaticSosBundle(
            bundleKey: String,
            workerId: String,
            nowMillis: Long
        ): com.example.sos_segundoplano.domain.offline.AutomaticSosBundleClaimResult {
            val claim = com.example.sos_segundoplano.domain.offline.OfflineQueueClaim(1L, workerId, 1, nowMillis, "test-claim")
            fun item(id: Long, type: com.example.sos_segundoplano.domain.offline.OfflineEventType) =
                com.example.sos_segundoplano.domain.offline.ClaimedOfflineQueueItem(
                    com.example.sos_segundoplano.domain.offline.OfflineQueueItem(
                        id, "test-$id", type, 1, null, null, id.toString(), nowMillis, nowMillis, nowMillis,
                        null, nowMillis, null, 1, com.example.sos_segundoplano.domain.offline.OfflineQueueStatus.InFlight,
                        null, null, null, null, "rider", bundleKey
                    ), byteArrayOf(1), byteArrayOf(2), 1, claim.copy(queueItemId = id)
                )
            return com.example.sos_segundoplano.domain.offline.AutomaticSosBundleClaimResult.Acquired(
                com.example.sos_segundoplano.domain.offline.ClaimedAutomaticSosBundle("rider", bundleKey, item(1L, com.example.sos_segundoplano.domain.offline.OfflineEventType.LocalIncident), item(2L, com.example.sos_segundoplano.domain.offline.OfflineEventType.AlertDispatchRequest))
            )
        }

        override suspend fun acknowledgeAutomaticSosBundle(
            bundle: com.example.sos_segundoplano.domain.offline.ClaimedAutomaticSosBundle,
            receipt: com.example.sos_segundoplano.domain.offline.AutomaticSosRemoteReceipt,
            nowMillis: Long
        ) = com.example.sos_segundoplano.domain.offline.OfflineQueueTransitionResult.Applied

        override suspend fun releaseAutomaticSosBundle(
            bundle: com.example.sos_segundoplano.domain.offline.ClaimedAutomaticSosBundle,
            permanent: Boolean,
            code: String,
            nowMillis: Long
        ): com.example.sos_segundoplano.domain.offline.OfflineQueueTransitionResult {
            releaseCalls++
            lastReleasePermanent = permanent
            return com.example.sos_segundoplano.domain.offline.OfflineQueueTransitionResult.Applied
        }
    }

    private class RecordingAutomaticSosCreator(
        private val location: LocationSample?,
        private val status: IncidentRemoteCreationStatus = IncidentRemoteCreationStatus.Success("remote-incident-automatic"),
        private val resolvedRemoteTripId: String? = "trip-automatic",
        private val retryLocation: LocationSample? = location
    ) : AutomaticSosAlertCreator {
        var locationCalls = 0
        var remoteTripResolutionCalls = 0
        val submitted = mutableListOf<Pair<LocalIncident, AlertDispatchRequest>>()

        override suspend fun captureLocation(incident: LocalIncident): LocalIncident {
            locationCalls++
            val capturedLocation = if (locationCalls == 1) location else retryLocation
            return capturedLocation?.let {
                incident.copy(latitude = it.latitude, longitude = it.longitude, remoteCreationStatus = IncidentRemoteCreationStatus.Pending)
            } ?: incident.copy(remoteCreationStatus = IncidentRemoteCreationStatus.MissingRequiredData("location_unavailable"))
        }

        override suspend fun resolveRemoteTrip(incident: LocalIncident): LocalIncident {
            remoteTripResolutionCalls++
            val remoteTripId = incident.remoteTripId ?: resolvedRemoteTripId
            return remoteTripId?.let {
                incident.copy(remoteTripId = it, remoteCreationStatus = IncidentRemoteCreationStatus.Pending)
            } ?: incident.copy(
                remoteCreationStatus = IncidentRemoteCreationStatus.MissingRequiredData("active_remote_trip_missing")
            )
        }

        override suspend fun createAutomaticSosAlert(incident: LocalIncident, request: AlertDispatchRequest): LocalIncident {
            submitted += incident to request
            return when (status) {
                is IncidentRemoteCreationStatus.Success -> incident.copy(remoteIncidentId = status.incidentId, remoteAlertDispatchId = "remote-dispatch-automatic", remoteCreationStatus = status)
                else -> incident.copy(remoteCreationStatus = status)
            }
        }
    }

    private fun location(
        latitude: Double = 19.4326,
        longitude: Double = -99.1332
    ) = LocationSample(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = 5f,
        timestampMillis = 1_700_000_000_000L,
        provider = "gps",
        isMock = false
    )

    private companion object {
        const val TRIP_SESSION_KEY = "trip-session-A"
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
