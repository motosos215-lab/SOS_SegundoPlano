package com.example.sos_segundoplano.domain.rules

import com.example.sos_segundoplano.domain.preprocessing.SignalKind
import com.example.sos_segundoplano.domain.signals.NetworkTransport
import com.example.sos_segundoplano.domain.signals.WearableStatus

data class RuleEngineConfig(
    val processedWindowBufferCapacity: Int = 64,
    val historyDurationNanos: Long = 15_000_000_000L,
    val maxRetainedWindows: Int = 32,
    val lateWindowToleranceNanos: Long = 500_000_000L,
    val duplicateTimestampToleranceNanos: Long = 0L,
    val impactThresholdMetersPerSecondSquared: Double = 24.5,
    val impactBaselineExcessMetersPerSecondSquared: Double = 10.0,
    val freeFallThresholdMetersPerSecondSquared: Double = 3.0,
    val freeFallMinimumDurationNanos: Long = 100_000_000L,
    val fallImpactWindowNanos: Long = 2_000_000_000L,
    val fallOrientationSupportSeverity: Double = 0.25,
    val fallCompletePatternBonus: Double = 0.25,
    val orientationChangeDegrees: Double = 45.0,
    val gyroscopeRotationThresholdRadians: Double = 0.8,
    val harshBrakingMetersPerSecondSquared: Double = 4.5,
    val harshBrakingMinimumInitialSpeedMetersPerSecond: Double = 5.0,
    val immobilityDurationNanos: Long = 3_000_000_000L,
    val immobilityMaxSpeedMetersPerSecond: Double = 0.5,
    val immobilityMaxAccelerometerStdDev: Double = 0.8,
    val immobilityMaxGyroscopeStdDev: Double = 0.25,
    val movementContinuityDurationNanos: Long = 2_000_000_000L,
    val movementContinuityMinSpeedMetersPerSecond: Double = 1.0,
    val minimumCoverageRatio: Double = 0.5,
    val gpsGoodAccuracyMeters: Double = 10.0,
    val gpsDegradedAccuracyMeters: Double = 25.0,
    val gpsPoorAccuracyMeters: Double = 50.0,
    val gpsMaxAgeNanos: Long = 5_000_000_000L,
    val lowBatteryPercentage: Int = 20,
    val criticalBatteryPercentage: Int = 10,
    val mediumRiskThreshold: Int = 40,
    val highRiskThreshold: Int = 70,
    val maxScore: Int = 100,
    val fallWeight: Double = 55.0,
    val impactWeight: Double = 25.0,
    val harshBrakingWeight: Double = 20.0,
    val orientationWeight: Double = 10.0,
    val immobilityWeight: Double = 20.0,
    val continuityWeight: Double = 0.0,
    val gpsWeight: Double = 0.0,
    val deviceReadinessWeight: Double = 0.0,
    val ruleSetVersion: String = "local-rules-v1"
) {
    init {
        require(processedWindowBufferCapacity > 0)
        require(historyDurationNanos > 0)
        require(maxRetainedWindows > 0)
        require(lateWindowToleranceNanos >= 0)
        require(duplicateTimestampToleranceNanos >= 0)
        require(impactThresholdMetersPerSecondSquared > 0.0)
        require(impactBaselineExcessMetersPerSecondSquared >= 0.0)
        require(freeFallThresholdMetersPerSecondSquared >= 0.0)
        require(freeFallMinimumDurationNanos > 0)
        require(fallImpactWindowNanos > 0)
        require(fallOrientationSupportSeverity in 0.0..1.0)
        require(fallCompletePatternBonus in 0.0..1.0)
        require(orientationChangeDegrees in 0.0..180.0)
        require(gyroscopeRotationThresholdRadians >= 0.0)
        require(harshBrakingMetersPerSecondSquared > 0.0)
        require(harshBrakingMinimumInitialSpeedMetersPerSecond >= 0.0)
        require(immobilityDurationNanos > 0)
        require(immobilityMaxSpeedMetersPerSecond >= 0.0)
        require(immobilityMaxAccelerometerStdDev >= 0.0)
        require(immobilityMaxGyroscopeStdDev >= 0.0)
        require(movementContinuityDurationNanos > 0)
        require(movementContinuityMinSpeedMetersPerSecond >= 0.0)
        require(minimumCoverageRatio in 0.0..1.0)
        require(gpsGoodAccuracyMeters > 0.0)
        require(gpsDegradedAccuracyMeters >= gpsGoodAccuracyMeters)
        require(gpsPoorAccuracyMeters >= gpsDegradedAccuracyMeters)
        require(gpsMaxAgeNanos > 0)
        require(criticalBatteryPercentage in 0..100)
        require(lowBatteryPercentage in criticalBatteryPercentage..100)
        require(mediumRiskThreshold in 0..maxScore)
        require(highRiskThreshold in mediumRiskThreshold..maxScore)
        require(maxScore > 0)
        listOf(fallWeight, impactWeight, harshBrakingWeight, orientationWeight, immobilityWeight, continuityWeight, gpsWeight, deviceReadinessWeight).forEach { require(it >= 0.0) }
        require(ruleSetVersion.isNotBlank())
    }
}

enum class RuleId { Impact, Fall, HarshBraking, OrientationChange, Immobility, MovementContinuity, GpsQuality, DeviceReadiness }
enum class RuleEvaluationStatus { Triggered, NotTriggered, Indeterminate, NotApplicable }
enum class RiskLevel { Low, Medium, High, Unknown }
enum class MovementContinuityState { Continuing, Stopped, Intermittent, Unknown }
enum class GpsQualityStatus { Good, Degraded, Poor, Unavailable, Stale }
enum class BatteryReadinessStatus { Normal, Low, Critical, Unknown }
enum class ConnectivityReadinessStatus { Available, Unavailable, Unknown }

data class RuleEvidence(
    val metrics: Map<String, Double?> = emptyMap(),
    val text: String? = null
)

data class RiskContribution(
    val ruleId: RuleId,
    val rawContribution: Double,
    val effectiveContribution: Double,
    val absorbedBy: RuleId? = null
)

data class RuleOutcome(
    val ruleId: RuleId,
    val status: RuleEvaluationStatus,
    val severity: Double?,
    val confidence: Double,
    val evidence: RuleEvidence,
    val usedSignals: Set<SignalKind>,
    val missingSignals: Set<SignalKind>,
    val thresholds: Map<String, Double>,
    val startNanos: Long?,
    val endNanos: Long?,
    val reason: String,
    val contribution: RiskContribution,
    val ruleSetVersion: String
)

data class GpsQualityEvaluation(
    val status: GpsQualityStatus,
    val accuracyMeters: Double?,
    val lastLocationAgeNanos: Long?,
    val confidence: Double
)

data class DeviceReadinessEvaluation(
    val batteryStatus: BatteryReadinessStatus,
    val batteryPercentage: Int?,
    val charging: Boolean?,
    val connectivityStatus: ConnectivityReadinessStatus,
    val connectivityValidated: Boolean?,
    val transport: NetworkTransport?,
    val wearableStatus: WearableStatus?,
    val canCommunicateLater: Boolean?,
    val confidence: Double
)

data class RiskScore(
    val value: Int?,
    val level: RiskLevel,
    val confidence: Double,
    val contributions: List<RiskContribution>,
    val ruleSetVersion: String
)

data class RiskAssessment(
    val sessionId: Long,
    val assessmentId: Long,
    val windowId: Long,
    val startNanos: Long,
    val endNanos: Long,
    val score: Int?,
    val riskLevel: RiskLevel,
    val confidence: Double,
    val outcomes: List<RuleOutcome>,
    val contributions: List<RiskContribution>,
    val gpsQuality: GpsQualityEvaluation,
    val deviceReadiness: DeviceReadinessEvaluation,
    val movementContinuity: MovementContinuityState,
    val droppedProcessedWindows: Long,
    val lateWindows: Long,
    val droppedRawEvents: Long,
    val ruleSetVersion: String,
    val partialWindow: Boolean
)

sealed interface RiskAssessmentState {
    data object Idle : RiskAssessmentState
    data class Collecting(val sessionId: Long) : RiskAssessmentState
    data class AssessmentReady(val assessment: RiskAssessment) : RiskAssessmentState
    data class InsufficientData(val sessionId: Long) : RiskAssessmentState
    data object Stopped : RiskAssessmentState
    data class Error(val reason: String? = null) : RiskAssessmentState
}
