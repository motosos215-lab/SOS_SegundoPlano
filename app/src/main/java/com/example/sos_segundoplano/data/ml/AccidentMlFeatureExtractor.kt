package com.example.sos_segundoplano.data.ml

import com.example.sos_segundoplano.domain.preprocessing.ProcessedSignalWindow
import com.example.sos_segundoplano.domain.preprocessing.RawSignalValue
import com.example.sos_segundoplano.domain.preprocessing.SignalKind
import com.example.sos_segundoplano.domain.preprocessing.SignalSourceId
import com.example.sos_segundoplano.domain.rules.RiskAssessment
import com.example.sos_segundoplano.domain.rules.RuleId
import com.example.sos_segundoplano.domain.rules.RuleEvaluationStatus
import com.example.sos_segundoplano.domain.signals.LocationSample
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AccidentMlFeatureExtractor {
    private var previousSpeedKmh: Double? = null
    private var previousWindowEndNanos: Long? = null
    private var previousLocation: LocationSample? = null

    fun reset() {
        previousSpeedKmh = null
        previousWindowEndNanos = null
        previousLocation = null
    }

    /**
     * Returns null while the GPS accuracy feature is not available. Using a sentinel such as 999 m
     * would be far outside the training distribution and can artificially inflate this pilot model.
     * Explainable hard rules continue running even when ML is skipped.
     */
    fun extract(window: ProcessedSignalWindow, assessment: RiskAssessment): AccidentMlInput? {
        val gpsAccuracyM = window.features.gps?.meanAccuracyMeters
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return null

        val fallbackSpeedKmh = window.features.scalar[SignalKind.Speed]?.mean
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.times(KMH_PER_MPS)
        val currentLocation = newestLocation(window)
        val computedSpeedKmh = computedSpeedKmh(previousLocation, currentLocation)
        val speedKmh = computedSpeedKmh ?: fallbackSpeedKmh ?: previousSpeedKmh ?: 0.0

        val previousSpeed = previousSpeedKmh
        val previousEnd = previousWindowEndNanos
        val deltaSeconds = if (previousEnd != null && window.endNanos > previousEnd) {
            (window.endNanos - previousEnd) / NANOS_PER_SECOND
        } else {
            0.0
        }
        val deltaSpeedKmh = if (previousSpeed != null) speedKmh - previousSpeed else 0.0
        val decelerationKmhS = if (previousSpeed != null && deltaSeconds > 0.0) {
            ((previousSpeed - speedKmh) / deltaSeconds).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val accelSamples = vectorSamples(window, SignalKind.Accelerometer)
        val gyroSamples = vectorSamples(window, SignalKind.Gyroscope)
        val accelPeakG = accelSamples.maxOfOrNull { it.magnitude / G_TO_MS2 } ?: 0.0
        val gyroPeakDps = gyroSamples.maxOfOrNull { it.magnitude * RAD_TO_DEG } ?: 0.0
        val jerkPeakGS = jerkPeakGS(accelSamples)
        val stillnessSeconds = assessment.outcomes
            .firstOrNull { it.ruleId == RuleId.Immobility }
            ?.evidence
            ?.metrics
            ?.get("durationNanos")
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.div(NANOS_PER_SECOND)
            ?: 0.0

        previousSpeedKmh = speedKmh
        previousWindowEndNanos = window.endNanos
        if (currentLocation != null && (previousLocation == null || currentLocation.timestampMillis > previousLocation!!.timestampMillis)) {
            previousLocation = currentLocation
        }

        return AccidentMlInput(
            speedKmh = speedKmh.coerceIn(0.0, MAX_REASONABLE_SPEED_KMH),
            deltaSpeedKmh = deltaSpeedKmh,
            decelerationKmhS = decelerationKmhS.coerceAtLeast(0.0),
            gpsAccuracyM = gpsAccuracyM,
            accelPeakG = accelPeakG,
            gyroPeakDps = gyroPeakDps,
            stillnessSeconds = stillnessSeconds,
            jerkPeakGS = jerkPeakGS,
        )
    }

    private fun newestLocation(window: ProcessedSignalWindow): LocationSample? = window.samples.asSequence()
        .filter { it.sample.valid && it.sample.event.signalKind == SignalKind.Location }
        .mapNotNull { sample ->
            val value = sample.sample.event.value as? RawSignalValue.Location
                ?: sample.sample.filteredValue as? RawSignalValue.Location
            value?.sample
        }
        .filter { it.latitude.isFinite() && it.longitude.isFinite() }
        .maxByOrNull { it.timestampMillis }

    private fun computedSpeedKmh(previous: LocationSample?, current: LocationSample?): Double? {
        if (previous == null || current == null || current.timestampMillis <= previous.timestampMillis) return null
        val elapsedSeconds = (current.timestampMillis - previous.timestampMillis) / 1000.0
        if (elapsedSeconds !in MIN_LOCATION_DT_SECONDS..MAX_LOCATION_DT_SECONDS) return null
        if (!previous.accuracyMeters.isFinite() || !current.accuracyMeters.isFinite()) return null
        val distance = distanceMeters(previous, current)
        val speed = distance / elapsedSeconds * KMH_PER_MPS
        if (!speed.isFinite() || speed !in 0.0..MAX_REASONABLE_SPEED_KMH) return null
        return speed
    }

    private fun distanceMeters(from: LocationSample, to: LocationSample): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val deltaLat = Math.toRadians(to.latitude - from.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return EARTH_RADIUS_METERS * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun vectorSamples(window: ProcessedSignalWindow, kind: SignalKind): List<VectorPoint> = window.samples.asSequence()
        .filter { it.sample.valid && it.sample.event.signalKind == kind }
        .mapNotNull { sample ->
            val vector = sample.sample.event.value as? RawSignalValue.Vector
                ?: sample.sample.filteredValue as? RawSignalValue.Vector
                ?: return@mapNotNull null
            if (!vector.x.isFinite() || !vector.y.isFinite() || !vector.z.isFinite()) return@mapNotNull null
            VectorPoint(
                source = sample.sample.event.sourceId,
                timestampNanos = sample.sample.event.timestamp.phoneTimeNanos,
                magnitude = sqrt(vector.x * vector.x + vector.y * vector.y + vector.z * vector.z),
            )
        }
        .toList()

    private fun jerkPeakGS(samples: List<VectorPoint>): Double = samples
        .groupBy { it.source }
        .values
        .maxOfOrNull { sourceSamples ->
            sourceSamples.sortedBy { it.timestampNanos }.zipWithNext().maxOfOrNull { (a, b) ->
                val dt = (b.timestampNanos - a.timestampNanos) / NANOS_PER_SECOND
                if (dt <= 0.0) 0.0 else abs((b.magnitude - a.magnitude) / G_TO_MS2) / dt
            } ?: 0.0
        } ?: 0.0

    private data class VectorPoint(
        val source: SignalSourceId,
        val timestampNanos: Long,
        val magnitude: Double,
    )

    private companion object {
        const val G_TO_MS2 = 9.80665
        const val RAD_TO_DEG = 57.29577951308232
        const val KMH_PER_MPS = 3.6
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val MIN_LOCATION_DT_SECONDS = 0.25
        const val MAX_LOCATION_DT_SECONDS = 30.0
        const val MAX_REASONABLE_SPEED_KMH = 220.0
    }
}

internal fun RiskAssessment.hasMlHardRuleSupport(): Boolean {
    fun triggered(id: RuleId): Boolean = outcomes.firstOrNull { it.ruleId == id }?.status == RuleEvaluationStatus.Triggered
    val fall = triggered(RuleId.Fall)
    val impact = triggered(RuleId.Impact)
    val braking = triggered(RuleId.HarshBraking)
    val orientation = triggered(RuleId.OrientationChange)
    val immobility = triggered(RuleId.Immobility)
    return fall || (impact && (orientation || immobility || braking)) || (braking && immobility)
}
