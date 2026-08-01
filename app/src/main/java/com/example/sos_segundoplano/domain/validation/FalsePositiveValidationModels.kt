package com.example.sos_segundoplano.domain.validation

import com.example.sos_segundoplano.domain.rules.GpsQualityStatus
import com.example.sos_segundoplano.domain.rules.MovementContinuityState
import com.example.sos_segundoplano.domain.rules.RiskAssessment
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.rules.RuleEvaluationStatus
import com.example.sos_segundoplano.domain.rules.RuleId
import com.example.sos_segundoplano.domain.rules.RuleOutcome

interface MonotonicClock {
    fun elapsedRealtimeNanos(): Long
}

data class FalsePositiveValidationConfig(
    val assessmentBufferCapacity: Int = 64,
    val maxMinorEvents: Int = 32,
    val maxIncidents: Int = 16,
    val maxAlertRequests: Int = 16,
    val lateAssessmentToleranceNanos: Long = 500_000_000L,
    val countdownDurationNanos: Long = 20_000_000_000L,
    val visualUpdateIntervalNanos: Long = 1_000_000_000L,
    val minimumValidationScore: Int = 40,
    val minimumConfidence: Double = 0.60,
    val criticalScore: Int = 90,
    val criticalConfidence: Double = 0.80,
    val criticalFallSeverity: Double = 0.90,
    val criticalImpactSeverity: Double = 0.90,
    val criticalOrientationSeverity: Double = 0.90,
    val bumpMinimumImpactSeverity: Double = 0.30,
    val bumpMaximumScore: Int = 39,
    val brakingContinuityMaximumGapNanos: Long = 2_000_000_000L,
    val immobilityCountdownMinimumSeverity: Double = 0.50,
    val policyVersion: String = "false-positive-validation-v1"
) {
    init {
        require(assessmentBufferCapacity > 0)
        require(maxMinorEvents > 0)
        require(maxIncidents > 0)
        require(maxAlertRequests > 0)
        require(lateAssessmentToleranceNanos >= 0L)
        require(countdownDurationNanos > 0L)
        require(visualUpdateIntervalNanos > 0L)
        require(minimumValidationScore in 0..100)
        require(criticalScore in minimumValidationScore..100)
        require(minimumConfidence in 0.0..1.0)
        require(criticalConfidence in minimumConfidence..1.0)
        require(criticalFallSeverity in 0.0..1.0)
        require(criticalImpactSeverity in 0.0..1.0)
        require(criticalOrientationSeverity in 0.0..1.0)
        require(bumpMinimumImpactSeverity in 0.0..1.0)
        require(bumpMaximumScore in 0 until minimumValidationScore)
        require(brakingContinuityMaximumGapNanos > 0L)
        require(immobilityCountdownMinimumSeverity in 0.0..1.0)
        require(policyVersion.isNotBlank())
    }
}

enum class ValidationDecisionReason {
    CandidatePhysicalRisk,
    IsolatedBump,
    HarshBrakingWithContinuedMovement,
    UserConfirmedSafe,
    UserRequestedHelp,
    Timeout,
    CriticalPhysicalEvent,
    TripStopped,
    DuplicateIgnored,
    LateAssessmentIgnored,
    InvalidResponse,
    SystemReset
}

enum class UserResponseSource { Mobile, Wear, ForegroundNotification }
enum class ValidationOrigin { Mobile, Wear, ForegroundNotification, Timeout, System }
enum class MinorEventType { Bump, RoadIrregularity }
enum class IncidentCause { UserRequestedHelp, Timeout, CriticalPhysicalEvent }
enum class AlertPriority { Normal, High, Critical }
enum class AlertDeliveryStatus { Pending }
enum class AlertRetryState { NotStarted }

data class AssessmentIdentifier(
    val sessionId: Long,
    val assessmentId: Long,
    val windowId: Long
)

data class OutcomeSummary(
    val ruleId: RuleId,
    val status: RuleEvaluationStatus,
    val severity: Double?,
    val reason: String
)

data class ValidationEvidence(
    val impact: OutcomeSummary? = null,
    val fall: OutcomeSummary? = null,
    val harshBraking: OutcomeSummary? = null,
    val orientation: OutcomeSummary? = null,
    val immobility: OutcomeSummary? = null,
    val movementContinuity: MovementContinuityState,
    val gpsQuality: GpsQualityStatus,
    val ruleSetVersion: String
)

data class ValidationMetadata(
    val sessionId: Long,
    val assessmentId: Long,
    val windowId: Long,
    val timestampElapsedRealtimeNanos: Long,
    val reason: ValidationDecisionReason,
    val score: Int?,
    val confidence: Double,
    val origin: ValidationOrigin,
    val policyVersion: String
)

data class MinorEvent(
    val eventId: Long,
    val sessionId: Long,
    val assessmentId: Long,
    val windowId: Long,
    val type: MinorEventType,
    val createdAtElapsedRealtimeNanos: Long,
    val score: Int?,
    val confidence: Double,
    val evidence: ValidationEvidence,
    val policyVersion: String
)

data class LocalIncident(
    val incidentId: Long,
    val sessionId: Long,
    val assessmentId: Long,
    val windowId: Long,
    val createdAtElapsedRealtimeNanos: Long,
    val cause: IncidentCause,
    val score: Int?,
    val riskLevel: RiskLevel,
    val confidence: Double,
    val relevantOutcomes: List<OutcomeSummary>,
    val ruleSetVersion: String,
    val validationPolicyVersion: String,
    val gpsQuality: GpsQualityStatus,
    val deliveryStatus: AlertDeliveryStatus = AlertDeliveryStatus.Pending
)

data class AlertPayloadSummary(
    val sessionId: Long,
    val assessmentId: Long,
    val incidentId: Long,
    val score: Int?,
    val riskLevel: RiskLevel,
    val cause: IncidentCause,
    val policyVersion: String
)

data class AlertDispatchRequest(
    val requestId: Long,
    val incidentId: Long,
    val sessionId: Long,
    val assessmentId: Long,
    val priority: AlertPriority,
    val reason: IncidentCause,
    val createdAtElapsedRealtimeNanos: Long,
    val score: Int?,
    val confidence: Double,
    val deliveryStatus: AlertDeliveryStatus = AlertDeliveryStatus.Pending,
    val retryState: AlertRetryState = AlertRetryState.NotStarted,
    val payload: AlertPayloadSummary
)

data class UserValidationResponse(
    val protocolVersion: Int,
    val action: UserValidationAction,
    val sessionId: Long,
    val assessmentId: Long,
    val responseId: String,
    val source: UserResponseSource
)

enum class UserValidationAction { ConfirmSafe, RequestHelp }

data class ValidationCounters(
    val duplicateAssessments: Long = 0L,
    val lateAssessments: Long = 0L,
    val duplicateResponses: Long = 0L,
    val invalidResponses: Long = 0L,
    val ignoredResponses: Long = 0L,
    val timeouts: Long = 0L
)

sealed interface FalsePositiveValidationState {
    data object Idle : FalsePositiveValidationState
    data class Monitoring(val sessionId: Long?, val timestampElapsedRealtimeNanos: Long, val policyVersion: String) : FalsePositiveValidationState
    data class CandidateDetected(val assessment: RiskAssessment, val metadata: ValidationMetadata, val evidence: ValidationEvidence) : FalsePositiveValidationState
    data class MinorEventRecorded(val event: MinorEvent, val metadata: ValidationMetadata) : FalsePositiveValidationState
    data class SuppressedFalsePositive(val assessment: RiskAssessment, val metadata: ValidationMetadata, val evidence: ValidationEvidence) : FalsePositiveValidationState
    data class CountdownActive(
        val assessment: RiskAssessment,
        val metadata: ValidationMetadata,
        val evidence: ValidationEvidence,
        val startedAtElapsedRealtimeNanos: Long,
        val deadlineElapsedRealtimeNanos: Long,
        val remainingNanos: Long
    ) : FalsePositiveValidationState
    data class SafeConfirmed(val metadata: ValidationMetadata, val responseId: String) : FalsePositiveValidationState
    data class HelpRequested(val metadata: ValidationMetadata, val responseId: String) : FalsePositiveValidationState
    data class IncidentGenerated(val incident: LocalIncident, val dispatchRequest: AlertDispatchRequest, val metadata: ValidationMetadata) : FalsePositiveValidationState
    data class ImmediateAlertRequested(val incident: LocalIncident, val dispatchRequest: AlertDispatchRequest, val metadata: ValidationMetadata) : FalsePositiveValidationState
    data class Stopped(val sessionId: Long?, val timestampElapsedRealtimeNanos: Long, val policyVersion: String) : FalsePositiveValidationState
    data class Error(val metadata: ValidationMetadata?, val message: String?) : FalsePositiveValidationState
}

val FalsePositiveValidationState.activeCountdown: FalsePositiveValidationState.CountdownActive?
    get() = this as? FalsePositiveValidationState.CountdownActive

fun RiskAssessment.identifier(): AssessmentIdentifier = AssessmentIdentifier(sessionId, assessmentId, windowId)

fun RiskAssessment.outcome(ruleId: RuleId): RuleOutcome? = outcomes.firstOrNull { it.ruleId == ruleId }

fun RuleOutcome?.isTriggered(): Boolean = this?.status == RuleEvaluationStatus.Triggered

fun RuleOutcome?.isNotTriggered(): Boolean = this == null || status == RuleEvaluationStatus.NotTriggered || status == RuleEvaluationStatus.NotApplicable

fun RuleOutcome?.summary(): OutcomeSummary? = this?.let { OutcomeSummary(it.ruleId, it.status, it.severity, it.reason) }

fun RiskAssessment.validationEvidence(): ValidationEvidence = ValidationEvidence(
    impact = outcome(RuleId.Impact).summary(),
    fall = outcome(RuleId.Fall).summary(),
    harshBraking = outcome(RuleId.HarshBraking).summary(),
    orientation = outcome(RuleId.OrientationChange).summary(),
    immobility = outcome(RuleId.Immobility).summary(),
    movementContinuity = movementContinuity,
    gpsQuality = gpsQuality.status,
    ruleSetVersion = ruleSetVersion
)

fun RiskAssessment.relevantOutcomeSummaries(): List<OutcomeSummary> = outcomes
    .filter { it.status == RuleEvaluationStatus.Triggered }
    .map { OutcomeSummary(it.ruleId, it.status, it.severity, it.reason) }
