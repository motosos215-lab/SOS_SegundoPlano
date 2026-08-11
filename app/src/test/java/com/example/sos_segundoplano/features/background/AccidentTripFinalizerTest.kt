package com.example.sos_segundoplano.features.background

import com.example.sos_segundoplano.domain.rules.GpsQualityStatus
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.AlertPayloadSummary
import com.example.sos_segundoplano.domain.validation.AlertPriority
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.validation.ValidationDecisionReason
import com.example.sos_segundoplano.domain.validation.ValidationMetadata
import com.example.sos_segundoplano.domain.validation.ValidationOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.sos_segundoplano.domain.trip.TripTimingState

class AccidentTripFinalizerTest {
    @Test fun safeConfirmedNeverFinishesTrip() {
        var finishCount = 0
        val finalizer = AccidentTripFinalizer { finishCount++ }

        finalizer.onValidationStateChanged(FalsePositiveValidationState.SafeConfirmed(metadata(), "safe-1"))

        assertEquals(0, finishCount)
    }

    @Test fun safeConfirmedKeepsTripTimingWhilePersistedAccidentClearsIt() {
        var timingState: TripTimingState = TripTimingState.Active(1_000L)
        val finalizer = AccidentTripFinalizer { timingState = TripTimingState.Unknown }

        finalizer.onValidationStateChanged(FalsePositiveValidationState.SafeConfirmed(metadata(), "safe-timing"))
        assertTrue(timingState is TripTimingState.Active)

        finalizer.onValidationStateChanged(incidentGenerated(IncidentCause.Timeout))
        assertEquals(TripTimingState.Unknown, timingState)
    }

    @Test fun helpRequestedDoesNotFinishBeforePersistence() {
        var finishCount = 0
        val finalizer = AccidentTripFinalizer { finishCount++ }

        finalizer.onValidationStateChanged(FalsePositiveValidationState.HelpRequested(metadata(), "help-1"))

        assertEquals(0, finishCount)
    }

    @Test fun incidentGeneratedFinishesTripOncePerIncident() {
        var finishCount = 0
        val finalizer = AccidentTripFinalizer { finishCount++ }
        val state = incidentGenerated(IncidentCause.UserRequestedHelp)

        finalizer.onValidationStateChanged(state)
        finalizer.onValidationStateChanged(state)

        assertEquals(1, finishCount)
    }

    @Test fun timeoutIncidentGeneratedFinishesTripOnce() {
        var finishCount = 0
        val finalizer = AccidentTripFinalizer { finishCount++ }

        finalizer.onValidationStateChanged(incidentGenerated(IncidentCause.Timeout))

        assertEquals(1, finishCount)
    }

    @Test fun immediateAlertRequestedFinishesBecauseItIsPostPersistence() {
        var finishCount = 0
        val finalizer = AccidentTripFinalizer { finishCount++ }
        val incident = incident(IncidentCause.CriticalPhysicalEvent)

        finalizer.onValidationStateChanged(FalsePositiveValidationState.ImmediateAlertRequested(incident, dispatch(incident), metadata()))

        assertEquals(1, finishCount)
    }

    @Test fun errorDoesNotFinishTripOrClaimSuccess() {
        var finishCount = 0
        val finalizer = AccidentTripFinalizer { finishCount++ }

        finalizer.onValidationStateChanged(FalsePositiveValidationState.Error(metadata(), "OfflinePersistenceFailed"))

        assertEquals(0, finishCount)
    }

    @Test fun resetAllowsNewTripToFinishForSameLocalIds() {
        var finishCount = 0
        val finalizer = AccidentTripFinalizer { finishCount++ }
        val state = incidentGenerated(IncidentCause.Timeout)

        finalizer.onValidationStateChanged(state)
        finalizer.reset()
        finalizer.onValidationStateChanged(state)

        assertEquals(2, finishCount)
    }

    private fun incidentGenerated(cause: IncidentCause): FalsePositiveValidationState.IncidentGenerated {
        val incident = incident(cause)
        return FalsePositiveValidationState.IncidentGenerated(incident, dispatch(incident), metadata())
    }

    private fun incident(cause: IncidentCause) = LocalIncident(
        incidentId = 1L,
        sessionId = 1L,
        assessmentId = 1L,
        windowId = 1L,
        createdAtElapsedRealtimeNanos = 1L,
        cause = cause,
        score = 75,
        riskLevel = RiskLevel.High,
        confidence = 0.8,
        relevantOutcomes = emptyList(),
        ruleSetVersion = "local-rules-v1",
        validationPolicyVersion = "false-positive-validation-v1",
        gpsQuality = GpsQualityStatus.Good
    )

    private fun dispatch(incident: LocalIncident) = AlertDispatchRequest(
        requestId = 1L,
        incidentId = incident.incidentId,
        sessionId = incident.sessionId,
        assessmentId = incident.assessmentId,
        priority = if (incident.cause == IncidentCause.CriticalPhysicalEvent) AlertPriority.Critical else AlertPriority.High,
        reason = incident.cause,
        createdAtElapsedRealtimeNanos = 1L,
        score = incident.score,
        confidence = incident.confidence,
        payload = AlertPayloadSummary(
            sessionId = incident.sessionId,
            assessmentId = incident.assessmentId,
            incidentId = incident.incidentId,
            score = incident.score,
            riskLevel = incident.riskLevel,
            cause = incident.cause,
            policyVersion = incident.validationPolicyVersion
        )
    )

    private fun metadata() = ValidationMetadata(
        sessionId = 1L,
        assessmentId = 1L,
        windowId = 1L,
        timestampElapsedRealtimeNanos = 1L,
        reason = ValidationDecisionReason.CandidatePhysicalRisk,
        score = 75,
        confidence = 0.8,
        origin = ValidationOrigin.System,
        policyVersion = "false-positive-validation-v1"
    )
}
