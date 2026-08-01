package com.example.sos_segundoplano.data.rules

import com.example.sos_segundoplano.data.preprocessing.InMemoryProcessedSignalStore
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
import com.example.sos_segundoplano.domain.rules.BatteryReadinessStatus
import com.example.sos_segundoplano.domain.rules.ConnectivityReadinessStatus
import com.example.sos_segundoplano.domain.rules.MovementContinuityState
import com.example.sos_segundoplano.domain.rules.RiskAssessmentState
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.rules.RuleEngineConfig
import com.example.sos_segundoplano.domain.rules.RuleEvaluationStatus
import com.example.sos_segundoplano.domain.rules.RuleId
import com.example.sos_segundoplano.domain.signals.BatterySample
import com.example.sos_segundoplano.domain.signals.ConnectivitySample
import com.example.sos_segundoplano.domain.signals.NetworkTransport
import com.example.sos_segundoplano.domain.signals.SignalAvailability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuleEngineCoordinatorTest {
    @Test fun normalImpactAndStopFlowPublishesLatestAssessmentOnlyWhileStarted() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val processed = InMemoryProcessedSignalStore(windowBufferCapacity = 4)
        val risk = InMemoryRiskAssessmentStore()
        val coordinator = RuleEngineCoordinator(processed, risk, RuleEngineConfig(), dispatcher)

        coordinator.start()
        advanceUntilIdle()
        processed.start(1L)
        processed.publish(window(1, speeds = listOf(speed(0, 6.0), speed(1_000_000_000, 6.0)), accel = listOf(vector(0, 9.8), vector(1, 10.0))))
        advanceUntilIdle()
        val normal = (risk.states.value as RiskAssessmentState.AssessmentReady).assessment
        assertEquals(RiskLevel.Low, normal.riskLevel)

        processed.publish(window(2, accel = listOf(vector(1_000_000_000, 9.8), vector(1_100_000_000, 38.0, outlier = true))))
        advanceUntilIdle()
        val impact = (risk.states.value as RiskAssessmentState.AssessmentReady).assessment
        assertEquals(RuleEvaluationStatus.Triggered, impact.outcomes.first { it.ruleId == RuleId.Impact }.status)
        assertTrue(impact.contributions.first { it.ruleId == RuleId.Impact }.rawContribution > 0.0)

        coordinator.stop()
        processed.publish(window(3, accel = listOf(vector(2_000_000_000, 50.0))))
        advanceUntilIdle()
        assertEquals(RiskAssessmentState.Stopped, risk.states.value)
    }

    @Test fun possibleFallWithImmobilityIsHighWithoutIncidentsOrAlerts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val processed = InMemoryProcessedSignalStore(windowBufferCapacity = 8)
        val risk = InMemoryRiskAssessmentStore()
        val coordinator = RuleEngineCoordinator(processed, risk, RuleEngineConfig(), dispatcher)
        coordinator.start()
        advanceUntilIdle()

        processed.publish(window(1, start = 0, end = 200_000_000, accel = listOf(vector(0, 2.0), vector(120_000_000, 2.0))))
        processed.publish(window(2, start = 1_000_000_000, end = 2_000_000_000, accel = listOf(vector(1_000_000_000, 9.8), vector(1_100_000_000, 38.0, outlier = true))))
        processed.publish(window(3, start = 2_000_000_000, end = 3_000_000_000, accel = listOf(vector3(2_000_000_000, 0.0, 0.0, 9.8), vector3(2_100_000_000, 9.8, 0.0, 0.0))))
        processed.publish(window(4, start = 3_000_000_000, end = 6_500_000_000, speeds = listOf(speed(3_000_000_000, 0.0)), accel = listOf(vector(3_000_000_000, 9.8), vector(3_100_000_000, 9.9)), accelStd = 0.1, gyroStd = 0.1))
        advanceUntilIdle()

        val assessment = (risk.states.value as RiskAssessmentState.AssessmentReady).assessment
        val fallOutcome = assessment.outcomes.first { it.ruleId == RuleId.Fall }
        val impactContribution = assessment.contributions.first { it.ruleId == RuleId.Impact }
        val immobilityContribution = assessment.contributions.first { it.ruleId == RuleId.Immobility }
        assertEquals(
            "Fall must be triggered for free-fall followed by impact",
            RuleEvaluationStatus.Triggered,
            fallOutcome.status
        )
        assertEquals(
            "Complete fall followed by immobility must reach High risk. score=${assessment.score}, fall=${fallOutcome.severity}, immobility=${immobilityContribution.effectiveContribution}",
            RiskLevel.High,
            assessment.riskLevel
        )
        assertEquals(
            "Impact must be absorbed by Fall to avoid double counting",
            RuleId.Fall,
            impactContribution.absorbedBy
        )
        assertFalse(assessment.outcomes.joinToString(" ") { it.reason }.contains("incident", ignoreCase = true))
        assertFalse(assessment.outcomes.joinToString(" ") { it.reason }.contains("alert", ignoreCase = true))
    }

    @Test fun harshBrakingWithMovementContinuityDoesNotDecideFalsePositive() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val processed = InMemoryProcessedSignalStore(windowBufferCapacity = 4)
        val risk = InMemoryRiskAssessmentStore()
        val coordinator = RuleEngineCoordinator(processed, risk, RuleEngineConfig(), dispatcher)
        coordinator.start()
        advanceUntilIdle()

        processed.publish(window(1, start = 0, end = 1_000_000_000, speeds = listOf(speed(0, 20.0), speed(1_000_000_000, 10.0))))
        processed.publish(window(2, start = 1_000_000_000, end = 3_500_000_000, speeds = listOf(speed(1_000_000_000, 10.0), speed(3_000_000_000, 8.0))))
        advanceUntilIdle()

        val assessment = (risk.states.value as RiskAssessmentState.AssessmentReady).assessment
        assertEquals(RuleEvaluationStatus.Triggered, assessment.outcomes.first { it.ruleId == RuleId.HarshBraking }.status)
        assertEquals(MovementContinuityState.Continuing, assessment.movementContinuity)
        assertFalse(assessment.outcomes.joinToString(" ") { it.reason }.contains("FalsePositive"))
    }

    @Test fun absentWearAndDegradedOperatingContextRemainLocalContext() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val processed = InMemoryProcessedSignalStore(windowBufferCapacity = 4)
        val risk = InMemoryRiskAssessmentStore()
        val coordinator = RuleEngineCoordinator(processed, risk, RuleEngineConfig(), dispatcher)
        coordinator.start()
        advanceUntilIdle()

        processed.publish(window(1, gpsAccuracy = 90.0, battery = 15, connected = false, speeds = listOf(speed(0, 5.0), speed(1_000_000_000, 5.0)), accel = listOf(vector(0, 9.8), vector(1, 10.0))))
        advanceUntilIdle()

        val assessment = (risk.states.value as RiskAssessmentState.AssessmentReady).assessment
        assertEquals(RiskLevel.Low, assessment.riskLevel)
        assertEquals(BatteryReadinessStatus.Low, assessment.deviceReadiness.batteryStatus)
        assertEquals(ConnectivityReadinessStatus.Unavailable, assessment.deviceReadiness.connectivityStatus)
        assertFalse(assessment.outcomes.any { it.usedSignals.contains(SignalKind.HeartRate) })
        assertTrue(assessment.confidence < 1.0)
    }

    @Test fun startStopAreIdempotentNewTripClearsHistoryAndOverflowIsCounted() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val processed = InMemoryProcessedSignalStore(windowBufferCapacity = 1)
        val risk = InMemoryRiskAssessmentStore()
        val coordinator = RuleEngineCoordinator(processed, risk, RuleEngineConfig(processedWindowBufferCapacity = 1), dispatcher)

        processed.publish(window(99, session = 1, accel = listOf(vector(0, 9.8))))
        processed.publish(window(100, session = 1, accel = listOf(vector(1, 9.8))))
        assertTrue(processed.droppedWindows > 0L)

        coordinator.start()
        coordinator.start()
        advanceUntilIdle()
        processed.publish(window(1, session = 10, accel = listOf(vector(0, 9.8), vector(1, 10.0))))
        advanceUntilIdle()
        assertEquals(10L, (risk.states.value as RiskAssessmentState.AssessmentReady).assessment.sessionId)
        coordinator.stop()
        coordinator.stop()
        processed.publish(window(2, session = 10, accel = listOf(vector(1, 40.0))))
        advanceUntilIdle()
        assertEquals(RiskAssessmentState.Stopped, risk.states.value)

        coordinator.reset()
        coordinator.start()
        advanceUntilIdle()
        processed.publish(window(1, session = 11, accel = listOf(vector(0, 9.8), vector(1, 10.0))))
        advanceUntilIdle()
        assertEquals(11L, (risk.states.value as RiskAssessmentState.AssessmentReady).assessment.sessionId)
    }

    @Test fun stopProcessesLastVisiblePartialWindowBeforeStopping() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val processed = InMemoryProcessedSignalStore(windowBufferCapacity = 4)
        val risk = InMemoryRiskAssessmentStore()
        val coordinator = RuleEngineCoordinator(processed, risk, RuleEngineConfig(), dispatcher)

        coordinator.start()
        processed.publish(window(1, session = 20, start = 0L, end = 500_000_000L, accel = listOf(vector(0, 9.8), vector(1, 10.0))))
        coordinator.stop()

        assertEquals(RiskAssessmentState.Stopped, risk.states.value)
    }

    private fun window(
        id: Long,
        session: Long = 1L,
        start: Long = id * 1_000_000_000L,
        end: Long = start + 1_000_000_000L,
        accel: List<WindowedSample> = emptyList(),
        speeds: List<WindowedSample> = emptyList(),
        gpsAccuracy: Double? = null,
        battery: Int? = null,
        connected: Boolean? = null,
        accelStd: Double? = null,
        gyroStd: Double? = null
    ): ProcessedSignalWindow {
        val samples = accel + speeds
        val scalar = if (speeds.isEmpty()) emptyMap() else mapOf(SignalKind.Speed to scalarFeatures(speeds.mapNotNull { (it.sample.filteredValue as? RawSignalValue.Scalar)?.value }))
        val vector = if (accel.isEmpty()) emptyMap() else mapOf(SignalKind.Accelerometer to vectorFeatures(accelStd ?: 1.0), SignalKind.Gyroscope to vectorFeatures(gyroStd ?: 1.0))
        val features = SignalFeatures(
            scalar = scalar,
            vector = vector,
            gps = gpsAccuracy?.let { GpsFeatures(1, it, it, 0L) },
            latestBattery = battery?.let { BatterySample(it, false, 1L) },
            latestConnectivity = connected?.let { ConnectivitySample(it, it, false, if (it) NetworkTransport.Wifi else NetworkTransport.None, 1L) }
        )
        val present = samples.map { it.sample.event.signalKind }.toSet()
        val context = PreprocessingContext(DataQuality.Sufficient, 1.0, 0.0, false, false, gpsAccuracy != null, connected != null, battery != null, 0L, null, present, SignalKind.values().toSet() - present, false)
        return ProcessedSignalWindow(session, id, start, end, samples, emptyList(), features, context)
    }

    private fun vector(time: Long, magnitude: Double, outlier: Boolean = false) = vector3(time, magnitude, 0.0, 0.0, outlier)
    private fun vector3(time: Long, x: Double, y: Double, z: Double, outlier: Boolean = false) = WindowedSample(
        FilteredSignalSample(RawSignalEvent(SignalSourceId.Phone, SignalKind.Accelerometer, SignalAvailability.Available, RawSignalValue.Vector(x, y, z), ProcessingTimestamp(time)), RawSignalValue.Vector(x, y, z), true),
        OutlierFlag(outlier)
    )
    private fun speed(time: Long, value: Double) = WindowedSample(
        FilteredSignalSample(RawSignalEvent(SignalSourceId.Phone, SignalKind.Speed, SignalAvailability.Available, RawSignalValue.Scalar(value), ProcessingTimestamp(time)), RawSignalValue.Scalar(value), true)
    )
    private fun scalarFeatures(values: List<Double>) = ScalarFeatures(values.size, values.size, 0, values.average(), values.minOrNull(), values.maxOrNull(), values.sorted()[values.size / 2], 0.0, 0.0, (values.maxOrNull() ?: 0.0) - (values.minOrNull() ?: 0.0), 1.0, true)
    private fun vectorFeatures(std: Double) = VectorFeatures(scalarFeatures(listOf(1.0, 1.0)), scalarFeatures(listOf(1.0, 1.0)), scalarFeatures(listOf(1.0, 1.0)), 1.0, 1.0, std, 1.0, 0)
}
