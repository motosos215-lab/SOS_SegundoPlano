package com.example.sos_segundoplano.domain.rules

import com.example.sos_segundoplano.domain.preprocessing.DataQuality
import com.example.sos_segundoplano.domain.preprocessing.FilteredSignalSample
import com.example.sos_segundoplano.domain.preprocessing.GpsFeatures
import com.example.sos_segundoplano.domain.preprocessing.OutlierFlag
import com.example.sos_segundoplano.domain.preprocessing.PreprocessingContext
import com.example.sos_segundoplano.domain.preprocessing.ProcessedSignalWindow
import com.example.sos_segundoplano.domain.preprocessing.ProcessingTimestamp
import com.example.sos_segundoplano.domain.preprocessing.RawSignalEvent
import com.example.sos_segundoplano.domain.preprocessing.RawSignalValue
import com.example.sos_segundoplano.domain.preprocessing.ScalarFeatures
import com.example.sos_segundoplano.domain.preprocessing.SignalFeatures
import com.example.sos_segundoplano.domain.preprocessing.SignalKind
import com.example.sos_segundoplano.domain.preprocessing.SignalSourceId
import com.example.sos_segundoplano.domain.preprocessing.VectorFeatures
import com.example.sos_segundoplano.domain.preprocessing.WindowedSample
import com.example.sos_segundoplano.domain.signals.BatterySample
import com.example.sos_segundoplano.domain.signals.ConnectivitySample
import com.example.sos_segundoplano.domain.signals.NetworkTransport
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import com.example.sos_segundoplano.domain.signals.WearableStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    @Test fun configRejectsInvalidCapacitiesAndThresholds() {
        assertRejected { RuleEngineConfig(processedWindowBufferCapacity = 0) }
        assertRejected { RuleEngineConfig(maxRetainedWindows = 0) }
        assertRejected { RuleEngineConfig(mediumRiskThreshold = 80, highRiskThreshold = 70) }
        assertRejected { RuleEngineConfig(fallWeight = -1.0) }
        assertRejected { RuleEngineConfig(impactThresholdMetersPerSecondSquared = 0.0) }
        assertRejected { RuleEngineConfig(fallOrientationSupportSeverity = -0.1) }
        assertRejected { RuleEngineConfig(fallCompletePatternBonus = 1.1) }
    }

    @Test fun impactBelowThresholdDoesNotTriggerAndAboveThresholdKeepsPeakEvidence() {
        val engine = RuleEngine()
        val low = engine.evaluateImpact(window(1, accel = listOf(vector(0, 9.8), vector(1, 11.0))))
        val high = engine.evaluateImpact(window(2, accel = listOf(vector(0, 9.8), vector(1, 38.0, outlier = true))))

        assertEquals(RuleEvaluationStatus.NotTriggered, low.status)
        assertEquals(RuleEvaluationStatus.Triggered, high.status)
        assertEquals(38.0, high.evidence.metrics["peak"]!!, 0.0001)
        assertTrue(high.severity!! > 0.0)
    }

    @Test fun nonFiniteOrMissingAccelerationDoesNotBecomeZeroImpact() {
        val engine = RuleEngine()
        val missing = engine.evaluateImpact(window(1))
        val invalid = engine.evaluateImpact(window(2, accel = listOf(vector(0, Double.NaN))))

        assertEquals(RuleEvaluationStatus.Indeterminate, missing.status)
        assertEquals(RuleEvaluationStatus.Indeterminate, invalid.status)
        assertNull(missing.severity)
        assertNull(invalid.severity)
    }

    @Test fun singleOutlierWithoutCoverageDoesNotConfirmImpact() {
        val engine = RuleEngine(RuleEngineConfig(minimumCoverageRatio = 1.0))
        val outcome = engine.evaluateImpact(window(1, accel = listOf(vector(0, 40.0, outlier = true)), mobileCoverage = 0.2))

        assertEquals(RuleEvaluationStatus.Indeterminate, outcome.status)
    }

    @Test fun fallRequiresFreeFallThenImpactWithinTimeAndDoesNotCrossSessions() {
        val engine = RuleEngine()
        val freeFall = window(1, session = 1, accel = listOf(vector(0, 2.0), vector(120_000_000, 2.0)))
        val impact = window(2, session = 1, start = 1_000_000_000, accel = listOf(vector(1_000_000_000, 9.8), vector(1_100_000_000, 35.0)))
        val impactOutcome = engine.evaluateImpact(impact)
        val orientation = engine.evaluateOrientation(window(3, accel = listOf(vector3(0, 0.0, 0.0, 9.8), vector3(1, 9.8, 0.0, 0.0))))

        assertEquals(RuleEvaluationStatus.Triggered, engine.evaluateFall(listOf(freeFall, impact), impactOutcome, orientation).status)
        assertEquals(RuleEvaluationStatus.NotTriggered, engine.evaluateFall(listOf(impact), impactOutcome, orientation).status)

        val otherSessionFreeFall = freeFall.copy(sessionId = 99)
        assertNotEquals(RuleEvaluationStatus.Triggered, engine.evaluateFall(listOf(otherSessionFreeFall, impact), impactOutcome, orientation).status)
    }

    @Test fun fallRejectsFreeFallWithoutImpactImpactWithoutFreeFallAndLateSequence() {
        val engine = RuleEngine()
        val freeFall = window(1, accel = listOf(vector(0, 2.0), vector(120_000_000, 2.0)))
        val lateImpact = window(2, start = 5_000_000_000, accel = listOf(vector(5_000_000_000, 9.8), vector(5_100_000_000, 35.0)))
        val impactOutcome = engine.evaluateImpact(lateImpact)
        val noOrientation = engine.evaluateOrientation(lateImpact)

        assertEquals(RuleEvaluationStatus.NotTriggered, engine.evaluateFall(listOf(freeFall), engine.evaluateImpact(freeFall), noOrientation).status)
        assertEquals(RuleEvaluationStatus.NotTriggered, engine.evaluateFall(listOf(lateImpact), impactOutcome, noOrientation).status)
        assertEquals(RuleEvaluationStatus.NotTriggered, engine.evaluateFall(listOf(freeFall, lateImpact), impactOutcome, noOrientation).status)
    }

    @Test fun harshBrakingUsesSpeedAndTimestampsDeterministically() {
        val engine = RuleEngine()
        val braking = engine.evaluateHarshBraking(listOf(window(1, speeds = listOf(speed(0, 20.0), speed(1_000_000_000, 10.0)))))
        val normal = engine.evaluateHarshBraking(listOf(window(2, speeds = listOf(speed(0, 20.0), speed(1_000_000_000, 18.0)))))
        val lowSpeed = engine.evaluateHarshBraking(listOf(window(3, speeds = listOf(speed(0, 2.0), speed(1_000_000_000, 0.0)))))
        val duplicate = engine.evaluateHarshBraking(listOf(window(4, speeds = listOf(speed(0, 20.0), speed(0, 1.0), speed(1_000_000_000, 10.0)))))

        assertEquals(RuleEvaluationStatus.Triggered, braking.status)
        assertEquals(10.0, braking.evidence.metrics["deceleration"]!!, 0.0001)
        assertEquals(RuleEvaluationStatus.NotTriggered, normal.status)
        assertEquals(RuleEvaluationStatus.NotTriggered, lowSpeed.status)
        assertEquals(RuleEvaluationStatus.Triggered, duplicate.status)
    }

    @Test fun orientationUsesVectorAngleAndGyroIrregularDeltas() {
        val engine = RuleEngine()
        val changed = engine.evaluateOrientation(window(1, accel = listOf(vector3(0, 0.0, 0.0, 9.8), vector3(1, 9.8, 0.0, 0.0))))
        val zero = engine.evaluateOrientation(window(2, accel = listOf(vector3(0, 0.0, 0.0, 0.0), vector3(1, 1.0, 0.0, 0.0))))
        val gyro = engine.evaluateOrientation(window(3, accel = listOf(vector3(0, 0.0, 0.0, 9.8), vector3(1, 0.0, 0.0, 9.8)), gyro = listOf(vector3(0, 1.0, 0.0, 0.0, SignalKind.Gyroscope), vector3(1_000_000_000, 1.0, 0.0, 0.0, SignalKind.Gyroscope))))

        assertEquals(RuleEvaluationStatus.Triggered, changed.status)
        assertEquals(90.0, changed.evidence.metrics["angleDegrees"]!!, 0.0001)
        assertEquals(RuleEvaluationStatus.Indeterminate, zero.status)
        assertEquals(RuleEvaluationStatus.Triggered, gyro.status)
    }

    @Test fun immobilityRequiresTemporalDurationAndResetsOnMovement() {
        val engine = RuleEngine()
        val still1 = window(1, start = 0, end = 1_000_000_000, speeds = listOf(speed(0, 0.0)), accelStd = 0.1, gyroStd = 0.1)
        val still2 = window(2, start = 1_000_000_000, end = 4_000_000_000, speeds = listOf(speed(1_000_000_000, 0.0)), accelStd = 0.1, gyroStd = 0.1)
        val moving = window(3, start = 4_000_000_000, end = 5_000_000_000, speeds = listOf(speed(4_000_000_000, 5.0)), accelStd = 2.0)

        assertEquals(RuleEvaluationStatus.NotTriggered, engine.evaluateImmobility(listOf(still1)).status)
        assertEquals(RuleEvaluationStatus.Triggered, engine.evaluateImmobility(listOf(still1, still2)).status)
        assertEquals(RuleEvaluationStatus.NotTriggered, engine.evaluateImmobility(listOf(still1, still2, moving)).status)
    }

    @Test fun gpsAbsentDoesNotBecomeSpeedZeroAndContinuityRequiresDuration() {
        val engine = RuleEngine()
        val unknown = engine.evaluateMovementContinuity(listOf(window(1))).first
        val moving1 = window(2, start = 0, end = 1_000_000_000, speeds = listOf(speed(0, 5.0)))
        val moving2 = window(3, start = 1_000_000_000, end = 3_000_000_000, speeds = listOf(speed(1_000_000_000, 5.0)))

        assertEquals(MovementContinuityState.Unknown, unknown)
        assertEquals(MovementContinuityState.Intermittent, engine.evaluateMovementContinuity(listOf(moving1)).first)
        assertEquals(MovementContinuityState.Continuing, engine.evaluateMovementContinuity(listOf(moving1, moving2)).first)
    }

    @Test fun gpsQualityStatesDoNotIncreasePhysicalScore() {
        val engine = RuleEngine()
        assertEquals(GpsQualityStatus.Good, engine.evaluateGpsQuality(window(1, gpsAccuracy = 5.0)).first.status)
        assertEquals(GpsQualityStatus.Degraded, engine.evaluateGpsQuality(window(2, gpsAccuracy = 20.0)).first.status)
        assertEquals(GpsQualityStatus.Poor, engine.evaluateGpsQuality(window(3, gpsAccuracy = 70.0)).first.status)
        assertEquals(GpsQualityStatus.Stale, engine.evaluateGpsQuality(window(4, gpsAccuracy = 5.0, gpsAge = 9_000_000_000)).first.status)
        assertEquals(GpsQualityStatus.Unavailable, engine.evaluateGpsQuality(window(5)).first.status)

        val assessment = engine.evaluate(window(6, gpsAccuracy = 70.0))!!
        assertEquals(RiskLevel.Unknown, assessment.riskLevel)
        assertNull(assessment.score)
    }

    @Test fun batteryConnectivityAndWearAreOperationalContextOnly() {
        val engine = RuleEngine()
        val normal = engine.evaluateDeviceReadiness(window(1, battery = 80, connected = true)).first
        val low = engine.evaluateDeviceReadiness(window(2, battery = 15, connected = true)).first
        val critical = engine.evaluateDeviceReadiness(window(3, battery = 5, connected = true)).first
        val absent = engine.evaluateDeviceReadiness(window(4)).first
        val disconnected = engine.evaluateDeviceReadiness(window(5, battery = 80, connected = false)).first

        assertEquals(BatteryReadinessStatus.Normal, normal.batteryStatus)
        assertEquals(BatteryReadinessStatus.Low, low.batteryStatus)
        assertEquals(BatteryReadinessStatus.Critical, critical.batteryStatus)
        assertEquals(BatteryReadinessStatus.Unknown, absent.batteryStatus)
        assertEquals(ConnectivityReadinessStatus.Unavailable, disconnected.connectivityStatus)
        assertEquals(RiskLevel.Unknown, engine.evaluate(window(6, battery = 5, connected = false))!!.riskLevel)
        assertEquals(WearableStatus.PermissionRequired, engine.evaluateDeviceReadiness(window(7, wear = WearableStatus.PermissionRequired)).first.wearableStatus)
    }

    @Test fun scoreBoundariesAndUnknownAreExplicit() {
        val calculator = RiskScoreCalculator(RuleEngineConfig())

        assertEquals(RiskLevel.Unknown, calculator.calculate(emptyList()).level)
        assertNull(calculator.calculate(emptyList()).value)
        assertEquals(RiskLevel.Low, RiskScoreCalculator(RuleEngineConfig(harshBrakingWeight = 39.0)).calculate(listOf(outcome(RuleId.HarshBraking, 1.0, 39.0))).level)
        assertEquals(RiskLevel.Medium, RiskScoreCalculator(RuleEngineConfig(harshBrakingWeight = 40.0)).calculate(listOf(outcome(RuleId.HarshBraking, 1.0, 40.0))).level)
        assertEquals(RiskLevel.Medium, RiskScoreCalculator(RuleEngineConfig(fallWeight = 69.0)).calculate(listOf(outcome(RuleId.Fall, 1.0, 69.0))).level)
        assertEquals(RiskLevel.High, RiskScoreCalculator(RuleEngineConfig(fallWeight = 70.0)).calculate(listOf(outcome(RuleId.Fall, 1.0, 70.0))).level)
        assertEquals(100, RiskScoreCalculator(RuleEngineConfig(fallWeight = 300.0)).calculate(listOf(outcome(RuleId.Fall, 1.0, 300.0))).value)
    }

    @Test fun fallSubsumesImpactAndOrientationButKeepsEvidence() {
        val score = RiskScoreCalculator().calculate(listOf(
            outcome(RuleId.Fall, 1.0, 55.0),
            outcome(RuleId.Impact, 1.0, 25.0),
            outcome(RuleId.OrientationChange, 1.0, 10.0),
            outcome(RuleId.Immobility, 1.0, 20.0)
        ))

        assertEquals(RiskLevel.High, score.level)
        assertEquals(RuleId.Fall, score.contributions.first { it.ruleId == RuleId.Impact }.absorbedBy)
        assertEquals(0.0, score.contributions.first { it.ruleId == RuleId.OrientationChange }.effectiveContribution, 0.0001)
    }

    @Test fun isolatedHarshBrakingIsNotHighAndFallWithImmobilityCanBeHigh() {
        val calculator = RiskScoreCalculator()
        val braking = calculator.calculate(listOf(outcome(RuleId.HarshBraking, 1.0, 20.0)))
        val fallStill = calculator.calculate(listOf(outcome(RuleId.Fall, 1.0, 55.0), outcome(RuleId.Immobility, 1.0, 20.0)))

        assertEquals(RiskLevel.Low, braking.level)
        assertEquals(RiskLevel.High, fallStill.level)
    }

    @Test fun missingDataReducesConfidenceWithoutInventingRiskAndIsDeterministic() {
        val engine = RuleEngine()
        val input = window(1, accel = listOf(vector(0, 9.8)), mobileCoverage = 0.2, dataQuality = DataQuality.Partial)
        val first = engine.evaluate(input)!!
        val duplicate = engine.evaluate(input)

        assertNull(duplicate)
        assertTrue(first.confidence < 0.8)
        assertNull(first.score)
    }

    @Test fun lateWindowsIncrementTraceabilityAndHistoryIsBounded() {
        val engine = RuleEngine(RuleEngineConfig(maxRetainedWindows = 2, historyDurationNanos = 2_000_000_000L, lateWindowToleranceNanos = 0))
        val first = engine.evaluate(window(1, start = 5_000_000_000, end = 6_000_000_000, accel = listOf(vector(5_000_000_000, 9.8))))!!
        val late = engine.evaluate(window(2, start = 1_000_000_000, end = 2_000_000_000, accel = listOf(vector(1_000_000_000, 9.8))))
        val next = engine.evaluate(window(3, start = 6_000_000_000, end = 7_000_000_000, accel = listOf(vector(6_000_000_000, 9.8))))!!

        assertNotNull(first)
        assertNull(late)
        assertEquals(1L, next.lateWindows)
    }

    @Test fun noOutOfScopeTermsAreProducedByRuleModels() {
        val names = listOf(RuleEngine::class.java.simpleName, RiskAssessment::class.java.simpleName, RuleOutcome::class.java.simpleName).joinToString(" ")

        assertFalse(names.contains("Incident"))
        assertFalse(names.contains("Alert"))
        assertFalse(names.contains("Countdown"))
        assertFalse(names.contains("FalsePositive"))
        assertFalse(names.contains("Room"))
        assertFalse(names.contains("SQLite"))
        assertFalse(names.contains("DataStore"))
    }

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private fun outcome(id: RuleId, severity: Double, raw: Double) = RuleOutcome(
        id,
        RuleEvaluationStatus.Triggered,
        severity,
        1.0,
        RuleEvidence(mapOf("raw" to raw)),
        emptySet(),
        emptySet(),
        emptyMap(),
        0L,
        1L,
        "test",
        RiskContribution(id, raw, raw),
        "local-rules-v1"
    )

    private fun window(
        id: Long,
        session: Long = 1L,
        start: Long = id * 1_000_000_000L,
        end: Long = start + 1_000_000_000L,
        accel: List<WindowedSample> = emptyList(),
        gyro: List<WindowedSample> = emptyList(),
        speeds: List<WindowedSample> = emptyList(),
        gpsAccuracy: Double? = null,
        gpsAge: Long? = null,
        battery: Int? = null,
        connected: Boolean? = null,
        wear: WearableStatus? = null,
        accelStd: Double? = null,
        gyroStd: Double? = null,
        mobileCoverage: Double = 1.0,
        dataQuality: DataQuality = DataQuality.Sufficient
    ): ProcessedSignalWindow {
        val samples = accel + gyro + speeds
        val scalar = if (speeds.isEmpty()) emptyMap() else mapOf(SignalKind.Speed to scalarFeatures(speeds.mapNotNull { (it.sample.filteredValue as? RawSignalValue.Scalar)?.value }))
        val vector = buildMap {
            if (accel.isNotEmpty()) put(SignalKind.Accelerometer, vectorFeatures(accelStd ?: 1.0))
            if (gyro.isNotEmpty() || gyroStd != null) put(SignalKind.Gyroscope, vectorFeatures(gyroStd ?: 1.0))
        }
        val features = SignalFeatures(
            scalar = scalar,
            vector = vector,
            gps = gpsAccuracy?.let { GpsFeatures(1, it, it, gpsAge ?: 0L) },
            latestBattery = battery?.let { BatterySample(it, false, 1L) },
            latestConnectivity = connected?.let { ConnectivitySample(it, it, false, if (it) NetworkTransport.Wifi else NetworkTransport.None, 1L) },
            latestWearableStatus = wear
        )
        return ProcessedSignalWindow(session, id, start, end, samples, emptyList(), features, context(samples, mobileCoverage, dataQuality, end - start))
    }

    private fun context(samples: List<WindowedSample>, mobileCoverage: Double, quality: DataQuality, duration: Long) = PreprocessingContext(
        quality = quality,
        mobileCoverageRatio = mobileCoverage,
        wearCoverageRatio = if (samples.any { it.sample.event.sourceId == SignalSourceId.Wear }) mobileCoverage else 0.0,
        wearableAvailable = samples.any { it.sample.event.sourceId == SignalSourceId.Wear },
        heartRateAvailable = false,
        gpsAvailable = false,
        connectivityAvailable = false,
        batteryAvailable = false,
        droppedEvents = 0L,
        averageSynchronizationDeltaNanos = null,
        presentSignals = samples.map { it.sample.event.signalKind }.toSet(),
        absentSignals = SignalKind.values().toSet() - samples.map { it.sample.event.signalKind }.toSet(),
        partialWindow = duration < 1_000_000_000L
    )

    private fun vector(time: Long, magnitude: Double, outlier: Boolean = false) = vector3(time, magnitude, 0.0, 0.0, SignalKind.Accelerometer, outlier)
    private fun vector3(time: Long, x: Double, y: Double, z: Double, kind: SignalKind = SignalKind.Accelerometer, outlier: Boolean = false) = WindowedSample(
        FilteredSignalSample(RawSignalEvent(SignalSourceId.Phone, kind, SignalAvailability.Available, RawSignalValue.Vector(x, y, z), ProcessingTimestamp(time)), RawSignalValue.Vector(x, y, z), x.isFinite() && y.isFinite() && z.isFinite()),
        OutlierFlag(outlier)
    )
    private fun speed(time: Long, value: Double) = WindowedSample(
        FilteredSignalSample(RawSignalEvent(SignalSourceId.Phone, SignalKind.Speed, SignalAvailability.Available, RawSignalValue.Scalar(value), ProcessingTimestamp(time)), RawSignalValue.Scalar(value), value.isFinite())
    )
    private fun scalarFeatures(values: List<Double>) = ScalarFeatures(values.size, values.size, 0, values.average(), values.minOrNull(), values.maxOrNull(), values.sorted()[values.size / 2], 0.0, 0.0, (values.maxOrNull() ?: 0.0) - (values.minOrNull() ?: 0.0), 1.0, true)
    private fun vectorFeatures(std: Double) = VectorFeatures(scalarFeatures(listOf(1.0, 1.0)), scalarFeatures(listOf(1.0, 1.0)), scalarFeatures(listOf(1.0, 1.0)), 1.0, 1.0, std, 1.0, 0)
}
