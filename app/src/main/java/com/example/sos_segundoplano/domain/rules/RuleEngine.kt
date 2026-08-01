package com.example.sos_segundoplano.domain.rules

import com.example.sos_segundoplano.domain.preprocessing.DataQuality
import com.example.sos_segundoplano.domain.preprocessing.ProcessedSignalWindow
import com.example.sos_segundoplano.domain.preprocessing.RawSignalValue
import com.example.sos_segundoplano.domain.preprocessing.SignalKind
import com.example.sos_segundoplano.domain.preprocessing.WindowedSample
import com.example.sos_segundoplano.domain.signals.NetworkTransport
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class RuleEngine(private val config: RuleEngineConfig = RuleEngineConfig()) {
    private val history = ArrayDeque<ProcessedSignalWindow>()
    private val evaluated = mutableSetOf<Pair<Long, Long>>()
    private var assessmentId = 0L
    private var sessionId: Long? = null
    private var latestEndNanos: Long? = null
    private var lateWindows = 0L

    fun reset() {
        history.clear()
        evaluated.clear()
        assessmentId = 0L
        sessionId = null
        latestEndNanos = null
        lateWindows = 0L
    }

    fun evaluate(window: ProcessedSignalWindow, droppedProcessedWindows: Long = 0L): RiskAssessment? {
        if (sessionId != null && sessionId != window.sessionId) reset()
        sessionId = window.sessionId
        val key = window.sessionId to window.windowId
        if (!evaluated.add(key)) return null
        val latest = latestEndNanos
        if (latest != null && window.endNanos + config.lateWindowToleranceNanos < latest) {
            lateWindows++
            return null
        }
        latestEndNanos = max(latest ?: window.endNanos, window.endNanos)
        history.addLast(window)
        trimHistory(window.endNanos)

        val orderedHistory = history.toList().sortedWith(compareBy<ProcessedSignalWindow> { it.startNanos }.thenBy { it.windowId })
        val gps = evaluateGpsQuality(window)
        val device = evaluateDeviceReadiness(window)
        val impact = evaluateImpact(window)
        val braking = evaluateHarshBraking(orderedHistory)
        val orientation = evaluateOrientation(window)
        val immobility = evaluateImmobility(orderedHistory)
        val continuity = evaluateMovementContinuity(orderedHistory)
        val historicalImpact = orderedHistory.map { evaluateImpact(it) }.lastOrNull { it.status == RuleEvaluationStatus.Triggered } ?: impact
        val historicalOrientation = orderedHistory.map { evaluateOrientation(it) }.lastOrNull { it.status == RuleEvaluationStatus.Triggered } ?: orientation
        val fall = evaluateFall(orderedHistory, historicalImpact, historicalOrientation)
        val outcomes = listOf(gps.second, device.second, impact, braking, orientation, immobility, continuity.second, fall)
        val score = RiskScoreCalculator(config).calculate(outcomes)
        val confidence = confidence(window, outcomes, score.confidence, droppedProcessedWindows)
        return RiskAssessment(
            sessionId = window.sessionId,
            assessmentId = ++assessmentId,
            windowId = window.windowId,
            startNanos = window.startNanos,
            endNanos = window.endNanos,
            score = score.value,
            riskLevel = score.level,
            confidence = confidence,
            outcomes = outcomes,
            contributions = score.contributions,
            gpsQuality = gps.first,
            deviceReadiness = device.first,
            movementContinuity = continuity.first,
            droppedProcessedWindows = droppedProcessedWindows,
            lateWindows = lateWindows,
            droppedRawEvents = window.context.droppedEvents,
            ruleSetVersion = config.ruleSetVersion,
            partialWindow = window.context.partialWindow
        )
    }

    private fun trimHistory(nowNanos: Long) {
        while (history.isNotEmpty() && nowNanos - history.first().endNanos > config.historyDurationNanos) history.removeFirst()
        while (history.size > config.maxRetainedWindows) history.removeFirst()
    }

    fun evaluateImpact(window: ProcessedSignalWindow): RuleOutcome {
        val accelerometer = window.samples.filter { it.sample.valid && it.sample.event.signalKind == SignalKind.Accelerometer }
        val values = accelerometer.mapNotNull { it.vectorMagnitude() }.filter { it.isFinite() }
        if (values.isEmpty()) return outcome(RuleId.Impact, RuleEvaluationStatus.Indeterminate, null, 0.0, "accelerometer_missing", setOf(SignalKind.Accelerometer), window)
        if (window.context.mobileCoverageRatio < config.minimumCoverageRatio) return outcome(RuleId.Impact, RuleEvaluationStatus.Indeterminate, null, 0.25, "insufficient_coverage", setOf(SignalKind.Accelerometer), window)
        val peak = values.maxOrNull() ?: return outcome(RuleId.Impact, RuleEvaluationStatus.Indeterminate, null, 0.0, "invalid_peak", setOf(SignalKind.Accelerometer), window)
        val baseline = values.median() ?: values.average()
        val excess = peak - baseline
        val outliers = accelerometer.count { it.outlier.isOutlier }
        val triggered = peak >= config.impactThresholdMetersPerSecondSquared && excess >= config.impactBaselineExcessMetersPerSecondSquared && values.size > 1
        val severity = ratio(max(peak - config.impactThresholdMetersPerSecondSquared, excess), config.impactThresholdMetersPerSecondSquared)
        val confidence = if (outliers > 0) 0.85 else 0.7
        return outcome(
            RuleId.Impact,
            if (triggered) RuleEvaluationStatus.Triggered else RuleEvaluationStatus.NotTriggered,
            if (triggered) severity else 0.0,
            confidence,
            if (triggered) "impact_threshold_exceeded" else "impact_below_threshold",
            emptySet(),
            window,
            metrics = mapOf("peak" to peak, "baseline" to baseline, "excess" to excess, "outliers" to outliers.toDouble()),
            thresholds = mapOf("impact" to config.impactThresholdMetersPerSecondSquared, "baselineExcess" to config.impactBaselineExcessMetersPerSecondSquared)
        )
    }

    fun evaluateFall(windows: List<ProcessedSignalWindow>, impact: RuleOutcome, orientation: RuleOutcome): RuleOutcome {
        val sameSession = windows.filter { it.sessionId == windows.lastOrNull()?.sessionId }
        val accel = sameSession.flatMap { it.samples }.filter { it.sample.valid && it.sample.event.signalKind == SignalKind.Accelerometer }
            .mapNotNull { sample -> sample.vectorMagnitude()?.let { sample.sample.event.timestamp.phoneTimeNanos to it } }
            .sortedBy { it.first }
        if (accel.isEmpty()) return outcome(RuleId.Fall, RuleEvaluationStatus.Indeterminate, null, 0.0, "accelerometer_missing", setOf(SignalKind.Accelerometer), windows.lastOrNull())
        val low = accel.filter { it.second <= config.freeFallThresholdMetersPerSecondSquared }
        val freeFallStart = low.firstOrNull()?.first
        val freeFallEnd = low.lastOrNull()?.first
        val hasFreeFall = freeFallStart != null && freeFallEnd != null && freeFallEnd - freeFallStart >= config.freeFallMinimumDurationNanos
        val impactTime = if (impact.status == RuleEvaluationStatus.Triggered) impact.endNanos else null
        val impactInWindow = hasFreeFall && impactTime != null && impactTime - freeFallEnd!! in 0..config.fallImpactWindowNanos
        val orientationSupports = orientation.status == RuleEvaluationStatus.Triggered
        val triggered = impactInWindow && (orientationSupports || impact.confidence >= 0.7)
        val status = when {
            triggered -> RuleEvaluationStatus.Triggered
            hasFreeFall || impact.status == RuleEvaluationStatus.Triggered -> RuleEvaluationStatus.NotTriggered
            impact.status == RuleEvaluationStatus.Indeterminate && orientation.status == RuleEvaluationStatus.Indeterminate -> RuleEvaluationStatus.Indeterminate
            else -> RuleEvaluationStatus.NotTriggered
        }
        val severity = if (triggered) {
            val impactComponent = impact.severity ?: 0.0
            val orientationComponent = if (orientationSupports) config.fallOrientationSupportSeverity else 0.0
            val completePatternBonus = if (impactInWindow && orientationSupports) config.fallCompletePatternBonus else 0.0
            (impactComponent + orientationComponent + completePatternBonus).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return outcome(
            RuleId.Fall,
            status,
            severity,
            if (triggered) 0.8 else 0.55,
            if (triggered) "free_fall_impact_pattern" else "fall_pattern_not_complete",
            emptySet(),
            windows.lastOrNull(),
            metrics = mapOf("freeFallStart" to freeFallStart?.toDouble(), "freeFallEnd" to freeFallEnd?.toDouble(), "impactTime" to impactTime?.toDouble()),
            thresholds = mapOf("freeFall" to config.freeFallThresholdMetersPerSecondSquared, "fallImpactWindowNanos" to config.fallImpactWindowNanos.toDouble())
        )
    }

    fun evaluateHarshBraking(windows: List<ProcessedSignalWindow>): RuleOutcome {
        val speeds = windows.flatMap { it.samples }.mapNotNull { sample ->
            val value = sample.sample.filteredValue as? RawSignalValue.Scalar
            if (sample.sample.valid && sample.sample.event.signalKind == SignalKind.Speed && value != null && value.value.isFinite()) sample.sample.event.timestamp.phoneTimeNanos to value.value else null
        }.distinctBy { it.first }.sortedBy { it.first }
        if (speeds.size < 2) return outcome(RuleId.HarshBraking, RuleEvaluationStatus.Indeterminate, null, 0.0, "speed_missing", setOf(SignalKind.Speed), windows.lastOrNull())
        var best: BrakingEvidence? = null
        for (index in 1 until speeds.size) {
            val previous = speeds[index - 1]
            val current = speeds[index]
            val dt = (current.first - previous.first) / NANOS_PER_SECOND
            if (dt <= 0.0 || previous.second < config.harshBrakingMinimumInitialSpeedMetersPerSecond) continue
            val deceleration = (previous.second - current.second) / dt
            if (deceleration.isFinite() && (best == null || deceleration > best.deceleration)) best = BrakingEvidence(previous.second, current.second, dt, deceleration)
        }
        val evidence = best ?: return outcome(RuleId.HarshBraking, RuleEvaluationStatus.NotTriggered, 0.0, 0.5, "no_valid_deceleration", emptySet(), windows.lastOrNull())
        val triggered = evidence.deceleration >= config.harshBrakingMetersPerSecondSquared
        return outcome(
            RuleId.HarshBraking,
            if (triggered) RuleEvaluationStatus.Triggered else RuleEvaluationStatus.NotTriggered,
            if (triggered) ratio(evidence.deceleration, config.harshBrakingMetersPerSecondSquared * 2.0) else 0.0,
            0.8,
            if (triggered) "harsh_deceleration" else "normal_deceleration",
            emptySet(),
            windows.lastOrNull(),
            metrics = mapOf("initialSpeed" to evidence.initialSpeed, "finalSpeed" to evidence.finalSpeed, "seconds" to evidence.seconds, "deceleration" to evidence.deceleration),
            thresholds = mapOf("deceleration" to config.harshBrakingMetersPerSecondSquared, "minimumInitialSpeed" to config.harshBrakingMinimumInitialSpeedMetersPerSecond)
        )
    }

    fun evaluateOrientation(window: ProcessedSignalWindow): RuleOutcome {
        val vectors = window.samples.filter { it.sample.valid && it.sample.event.signalKind == SignalKind.Accelerometer }.mapNotNull { it.vector() }
        val gyro = window.samples.filter { it.sample.valid && it.sample.event.signalKind == SignalKind.Gyroscope }
        if (vectors.size < 2) return outcome(RuleId.OrientationChange, RuleEvaluationStatus.Indeterminate, null, 0.0, "accelerometer_insufficient", setOf(SignalKind.Accelerometer), window)
        val first = vectors.take(max(1, vectors.size / 3)).meanVector()
        val last = vectors.takeLast(max(1, vectors.size / 3)).meanVector()
        val angle = angleDegrees(first, last) ?: return outcome(RuleId.OrientationChange, RuleEvaluationStatus.Indeterminate, null, 0.0, "zero_magnitude_vector", emptySet(), window)
        val gyroRotation = integrateGyro(gyro)
        val triggered = angle >= config.orientationChangeDegrees || gyroRotation >= config.gyroscopeRotationThresholdRadians
        val severity = ratio(max(angle / config.orientationChangeDegrees, gyroRotation / config.gyroscopeRotationThresholdRadians), 2.0)
        return outcome(
            RuleId.OrientationChange,
            if (triggered) RuleEvaluationStatus.Triggered else RuleEvaluationStatus.NotTriggered,
            if (triggered) severity else 0.0,
            if (gyro.isEmpty()) 0.6 else 0.85,
            if (triggered) "orientation_change" else "orientation_stable",
            if (gyro.isEmpty()) setOf(SignalKind.Gyroscope) else emptySet(),
            window,
            metrics = mapOf("angleDegrees" to angle, "gyroRotationRadians" to gyroRotation),
            thresholds = mapOf("angleDegrees" to config.orientationChangeDegrees, "gyroRotationRadians" to config.gyroscopeRotationThresholdRadians)
        )
    }

    fun evaluateImmobility(windows: List<ProcessedSignalWindow>): RuleOutcome {
        val latest = windows.lastOrNull() ?: return outcome(RuleId.Immobility, RuleEvaluationStatus.Indeterminate, null, 0.0, "no_window", emptySet(), null)
        if (latest.context.presentSignals.intersect(setOf(SignalKind.Speed, SignalKind.Accelerometer, SignalKind.Gyroscope)).isEmpty()) {
            return outcome(RuleId.Immobility, RuleEvaluationStatus.Indeterminate, null, 0.0, "motion_signals_missing", setOf(SignalKind.Speed, SignalKind.Accelerometer, SignalKind.Gyroscope), latest)
        }
        if (latest.context.quality != DataQuality.Sufficient) {
            return outcome(RuleId.Immobility, RuleEvaluationStatus.Indeterminate, null, 0.25, "insufficient_context_quality", emptySet(), latest)
        }
        val candidates = windows.asReversed().takeWhile { isStill(it) }.asReversed()
        if (candidates.isEmpty()) return outcome(RuleId.Immobility, RuleEvaluationStatus.NotTriggered, 0.0, 0.6, "movement_detected", emptySet(), latest)
        val duration = candidates.last().endNanos - candidates.first().startNanos
        val triggered = duration >= config.immobilityDurationNanos
        return outcome(
            RuleId.Immobility,
            if (triggered) RuleEvaluationStatus.Triggered else RuleEvaluationStatus.NotTriggered,
            if (triggered) ratio(duration.toDouble(), config.immobilityDurationNanos.toDouble()) else 0.0,
            0.75,
            if (triggered) "immobility_duration_met" else "immobility_duration_short",
            emptySet(),
            latest,
            metrics = mapOf("durationNanos" to duration.toDouble()),
            thresholds = mapOf("durationNanos" to config.immobilityDurationNanos.toDouble())
        )
    }

    fun evaluateMovementContinuity(windows: List<ProcessedSignalWindow>): Pair<MovementContinuityState, RuleOutcome> {
        val latest = windows.lastOrNull() ?: return MovementContinuityState.Unknown to outcome(RuleId.MovementContinuity, RuleEvaluationStatus.Indeterminate, null, 0.0, "no_window", emptySet(), null)
        val moving = windows.asReversed().takeWhile { isMoving(it) }.asReversed()
        val duration = if (moving.isEmpty()) 0L else moving.last().endNanos - moving.first().startNanos
        val state = when {
            duration >= config.movementContinuityDurationNanos -> MovementContinuityState.Continuing
            isStill(latest) -> MovementContinuityState.Stopped
            latest.context.presentSignals.intersect(setOf(SignalKind.Speed, SignalKind.Accelerometer, SignalKind.Gyroscope)).isEmpty() -> MovementContinuityState.Unknown
            else -> MovementContinuityState.Intermittent
        }
        return state to outcome(
            RuleId.MovementContinuity,
            if (state == MovementContinuityState.Unknown) RuleEvaluationStatus.Indeterminate else RuleEvaluationStatus.NotTriggered,
            0.0,
            if (state == MovementContinuityState.Unknown) 0.2 else 0.75,
            state.name,
            if (state == MovementContinuityState.Unknown) setOf(SignalKind.Speed, SignalKind.Accelerometer) else emptySet(),
            latest,
            metrics = mapOf("durationNanos" to duration.toDouble()),
            thresholds = mapOf("durationNanos" to config.movementContinuityDurationNanos.toDouble())
        )
    }

    fun evaluateGpsQuality(window: ProcessedSignalWindow): Pair<GpsQualityEvaluation, RuleOutcome> {
        val gps = window.features.gps
        val status = when {
            gps == null || gps.validLocationCount == 0 -> GpsQualityStatus.Unavailable
            (gps.lastLocationAgeNanos ?: 0L) > config.gpsMaxAgeNanos -> GpsQualityStatus.Stale
            (gps.maxAccuracyMeters ?: Double.POSITIVE_INFINITY) <= config.gpsGoodAccuracyMeters -> GpsQualityStatus.Good
            (gps.meanAccuracyMeters ?: Double.POSITIVE_INFINITY) <= config.gpsDegradedAccuracyMeters -> GpsQualityStatus.Degraded
            else -> GpsQualityStatus.Poor
        }
        val evaluation = GpsQualityEvaluation(status, gps?.meanAccuracyMeters, gps?.lastLocationAgeNanos, if (status == GpsQualityStatus.Good) 0.95 else 0.6)
        return evaluation to outcome(RuleId.GpsQuality, RuleEvaluationStatus.NotTriggered, 0.0, evaluation.confidence, status.name, emptySet(), window)
    }

    fun evaluateDeviceReadiness(window: ProcessedSignalWindow): Pair<DeviceReadinessEvaluation, RuleOutcome> {
        val battery = window.features.latestBattery
        val connectivity = window.features.latestConnectivity
        val batteryStatus = when {
            battery == null -> BatteryReadinessStatus.Unknown
            battery.percentage <= config.criticalBatteryPercentage -> BatteryReadinessStatus.Critical
            battery.percentage <= config.lowBatteryPercentage -> BatteryReadinessStatus.Low
            else -> BatteryReadinessStatus.Normal
        }
        val connectivityStatus = when {
            connectivity == null -> ConnectivityReadinessStatus.Unknown
            connectivity.connected && connectivity.validated -> ConnectivityReadinessStatus.Available
            else -> ConnectivityReadinessStatus.Unavailable
        }
        val evaluation = DeviceReadinessEvaluation(
            batteryStatus,
            battery?.percentage,
            battery?.isCharging,
            connectivityStatus,
            connectivity?.validated,
            connectivity?.transport ?: if (connectivityStatus == ConnectivityReadinessStatus.Unavailable) NetworkTransport.None else null,
            window.features.latestWearableStatus,
            if (connectivity == null) null else connectivity.connected,
            when {
                battery == null && connectivity == null -> 0.2
                batteryStatus == BatteryReadinessStatus.Critical || connectivityStatus == ConnectivityReadinessStatus.Unavailable -> 0.55
                else -> 0.85
            }
        )
        return evaluation to outcome(RuleId.DeviceReadiness, RuleEvaluationStatus.NotTriggered, 0.0, evaluation.confidence, "device_context", emptySet(), window)
    }

    private fun confidence(window: ProcessedSignalWindow, outcomes: List<RuleOutcome>, scoreConfidence: Double, droppedProcessedWindows: Long): Double {
        var value = scoreConfidence * 0.5 + window.context.mobileCoverageRatio * 0.3 + outcomes.map { it.confidence }.average() * 0.2
        if (window.context.partialWindow) value -= 0.1
        if (window.context.droppedEvents > 0 || droppedProcessedWindows > 0) value -= 0.1
        return value.coerceIn(0.0, 1.0)
    }

    private fun isMoving(window: ProcessedSignalWindow): Boolean {
        val speed = window.features.scalar[SignalKind.Speed]?.mean
        val accelStd = window.features.vector[SignalKind.Accelerometer]?.magnitudeStandardDeviation
        val gyroStd = window.features.vector[SignalKind.Gyroscope]?.magnitudeStandardDeviation
        return (speed != null && speed >= config.movementContinuityMinSpeedMetersPerSecond) || (accelStd != null && accelStd > config.immobilityMaxAccelerometerStdDev) || (gyroStd != null && gyroStd > config.immobilityMaxGyroscopeStdDev)
    }

    private fun isStill(window: ProcessedSignalWindow): Boolean {
        val speed = window.features.scalar[SignalKind.Speed]?.mean
        val accelStd = window.features.vector[SignalKind.Accelerometer]?.magnitudeStandardDeviation
        val gyroStd = window.features.vector[SignalKind.Gyroscope]?.magnitudeStandardDeviation
        if (speed == null && accelStd == null && gyroStd == null) return false
        return (speed == null || speed <= config.immobilityMaxSpeedMetersPerSecond) &&
            (accelStd == null || accelStd <= config.immobilityMaxAccelerometerStdDev) &&
            (gyroStd == null || gyroStd <= config.immobilityMaxGyroscopeStdDev)
    }

    private fun outcome(
        id: RuleId,
        status: RuleEvaluationStatus,
        severity: Double?,
        confidence: Double,
        reason: String,
        missingSignals: Set<SignalKind>,
        window: ProcessedSignalWindow?,
        metrics: Map<String, Double?> = emptyMap(),
        thresholds: Map<String, Double> = emptyMap()
    ): RuleOutcome {
        val effectiveSeverity = severity?.coerceIn(0.0, 1.0)
        return RuleOutcome(
            ruleId = id,
            status = status,
            severity = effectiveSeverity,
            confidence = confidence.coerceIn(0.0, 1.0),
            evidence = RuleEvidence(metrics, reason),
            usedSignals = window?.context?.presentSignals.orEmpty() - missingSignals,
            missingSignals = missingSignals + window?.context?.absentSignals.orEmpty().filter { it in missingSignals }.toSet(),
            thresholds = thresholds,
            startNanos = window?.startNanos,
            endNanos = window?.endNanos,
            reason = reason,
            contribution = RiskContribution(id, 0.0, 0.0),
            ruleSetVersion = config.ruleSetVersion
        )
    }

    private data class BrakingEvidence(val initialSpeed: Double, val finalSpeed: Double, val seconds: Double, val deceleration: Double)

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

class RiskScoreCalculator(private val config: RuleEngineConfig = RuleEngineConfig()) {
    fun calculate(outcomes: List<RuleOutcome>): RiskScore {
        val byId = outcomes.associateBy { it.ruleId }
        val physical = listOf(RuleId.Fall, RuleId.Impact, RuleId.HarshBraking, RuleId.OrientationChange, RuleId.Immobility)
        if (physical.all { byId[it]?.status == RuleEvaluationStatus.Indeterminate || byId[it] == null }) {
            return RiskScore(null, RiskLevel.Unknown, 0.0, emptyList(), config.ruleSetVersion)
        }
        val fallTriggered = byId[RuleId.Fall]?.status == RuleEvaluationStatus.Triggered
        val contributions = physical.map { id ->
            val outcome = byId[id]
            val severity = if (outcome?.status == RuleEvaluationStatus.Triggered) outcome.severity ?: 0.0 else 0.0
            val weight = weight(id)
            val raw = weight * severity
            val absorbed = fallTriggered && (id == RuleId.Impact || id == RuleId.OrientationChange)
            RiskContribution(id, raw, if (absorbed) 0.0 else raw, if (absorbed) RuleId.Fall else null)
        }
        val value = contributions.sumOf { it.effectiveContribution }.roundToInt().coerceIn(0, config.maxScore)
        val level = when {
            value >= config.highRiskThreshold -> RiskLevel.High
            value >= config.mediumRiskThreshold -> RiskLevel.Medium
            else -> RiskLevel.Low
        }
        return RiskScore(value, level, outcomes.map { it.confidence }.averageOrNull() ?: 0.0, contributions, config.ruleSetVersion)
    }

    private fun weight(id: RuleId): Double = when (id) {
        RuleId.Fall -> config.fallWeight
        RuleId.Impact -> config.impactWeight
        RuleId.HarshBraking -> config.harshBrakingWeight
        RuleId.OrientationChange -> config.orientationWeight
        RuleId.Immobility -> config.immobilityWeight
        RuleId.MovementContinuity -> config.continuityWeight
        RuleId.GpsQuality -> config.gpsWeight
        RuleId.DeviceReadiness -> config.deviceReadinessWeight
    }
}

private fun WindowedSample.vector(): RawSignalValue.Vector? = sample.filteredValue as? RawSignalValue.Vector
private fun WindowedSample.vectorMagnitude(): Double? = vector()?.let { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }
private fun List<RawSignalValue.Vector>.meanVector(): RawSignalValue.Vector = RawSignalValue.Vector(map { it.x }.average(), map { it.y }.average(), map { it.z }.average())
private fun List<Double>.median(): Double? = filter { it.isFinite() }.sorted().let { values ->
    if (values.isEmpty()) null else if (values.size % 2 == 0) (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0 else values[values.size / 2]
}
private fun List<Double>.averageOrNull(): Double? = filter { it.isFinite() }.takeIf { it.isNotEmpty() }?.average()
private fun ratio(value: Double, denominator: Double): Double = if (!value.isFinite() || denominator <= 0.0) 0.0 else (value / denominator).coerceIn(0.0, 1.0)
private fun angleDegrees(a: RawSignalValue.Vector, b: RawSignalValue.Vector): Double? {
    val am = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
    val bm = sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
    if (am == 0.0 || bm == 0.0) return null
    val cosine = ((a.x * b.x + a.y * b.y + a.z * b.z) / (am * bm)).coerceIn(-1.0, 1.0)
    return acos(cosine) * 180.0 / PI
}
private fun integrateGyro(samples: List<WindowedSample>): Double {
    val ordered = samples.mapNotNull { sample -> sample.vectorMagnitude()?.let { sample.sample.event.timestamp.phoneTimeNanos to it } }.sortedBy { it.first }
    var total = 0.0
    for (index in 1 until ordered.size) {
        val dt = (ordered[index].first - ordered[index - 1].first) / 1_000_000_000.0
        if (dt > 0.0 && ordered[index].second.isFinite()) total += ordered[index].second * dt
    }
    return total
}
