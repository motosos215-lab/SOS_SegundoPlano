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
        var timingState: TripTimingState = TripTimingState.Active(1_000L, TRIP_SESSION_A)
        var finishedBundleKey: String? = null
        val finalizer = AccidentTripFinalizer { bundleKey ->
            finishedBundleKey = bundleKey
            timingState = TripTimingState.Unknown
        }

        finalizer.onValidationStateChanged(FalsePositiveValidationState.SafeConfirmed(metadata(), "safe-timing"))
        assertTrue(timingState is TripTimingState.Active)

        finalizer.onValidationStateChanged(incidentGenerated(IncidentCause.Timeout, BUNDLE_A))
        assertEquals(TripTimingState.Unknown, timingState)
        assertEquals(BUNDLE_A, finishedBundleKey)
    }

    @Test fun helpRequestedDoesNotFinishBeforePersistence() {
        var finishCount = 0
        val finalizer = AccidentTripFinalizer { finishCount++ }

        finalizer.onValidationStateChanged(FalsePositiveValidationState.HelpRequested(metadata(), "help-1"))

        assertEquals(0, finishCount)
    }

    @Test fun incidentGeneratedFinishesTripOncePerIncident() {
        val finishedBundleKeys = mutableListOf<String>()
        val finalizer = AccidentTripFinalizer(finishedBundleKeys::add)
        val state = incidentGenerated(IncidentCause.UserRequestedHelp, BUNDLE_A)

        finalizer.onValidationStateChanged(state)
        finalizer.onValidationStateChanged(state)

        assertEquals(listOf(BUNDLE_A), finishedBundleKeys)
    }

    @Test fun timeoutIncidentGeneratedFinishesTripOnce() {
        val finishedBundleKeys = mutableListOf<String>()
        val finalizer = AccidentTripFinalizer(finishedBundleKeys::add)

        finalizer.onValidationStateChanged(incidentGenerated(IncidentCause.Timeout, BUNDLE_A))

        assertEquals(listOf(BUNDLE_A), finishedBundleKeys)
    }

    @Test fun immediateAlertRequestedFinishesBecauseItIsPostPersistence() {
        val finishedBundleKeys = mutableListOf<String>()
        val finalizer = AccidentTripFinalizer(finishedBundleKeys::add)
        val incident = incident(IncidentCause.CriticalPhysicalEvent)

        finalizer.onValidationStateChanged(
            FalsePositiveValidationState.IncidentGenerated(incident, dispatch(incident), metadata(), BUNDLE_A)
        )
        finalizer.onValidationStateChanged(FalsePositiveValidationState.ImmediateAlertRequested(incident, dispatch(incident), metadata()))

        assertEquals(listOf(BUNDLE_A), finishedBundleKeys)
    }

    @Test fun errorDoesNotFinishTripOrClaimSuccess() {
        var finishCount = 0
        val finalizer = AccidentTripFinalizer { finishCount++ }

        finalizer.onValidationStateChanged(FalsePositiveValidationState.Error(metadata(), "OfflinePersistenceFailed"))

        assertEquals(0, finishCount)
    }

    @Test fun resetAllowsNewTripToFinishForSameLocalIds() {
        val finishedBundleKeys = mutableListOf<String>()
        val finalizer = AccidentTripFinalizer(finishedBundleKeys::add)
        val firstState = incidentGenerated(IncidentCause.Timeout, BUNDLE_A)
        val secondState = incidentGenerated(IncidentCause.Timeout, BUNDLE_B)

        finalizer.onValidationStateChanged(firstState)
        finalizer.reset()
        finalizer.onValidationStateChanged(secondState)

        assertEquals(listOf(BUNDLE_A, BUNDLE_B), finishedBundleKeys)
    }

    private fun incidentGenerated(
        cause: IncidentCause,
        bundleKey: String
    ): FalsePositiveValidationState.IncidentGenerated {
        val incident = incident(cause)
        return FalsePositiveValidationState.IncidentGenerated(incident, dispatch(incident), metadata(), bundleKey)
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

    private companion object {
        const val BUNDLE_A = "bundle-A"
        const val BUNDLE_B = "bundle-B"
        const val TRIP_SESSION_A = "trip-session-A"
    }
}
