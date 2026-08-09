package com.example.sos_segundoplano.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.sos_segundoplano.data.preprocessing.ProcessedSignalStoreProvider
import com.example.sos_segundoplano.data.rules.RiskAssessmentStoreProvider
import com.example.sos_segundoplano.data.trip.TripSessionStoreProvider
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationCoordinatorProvider
import com.example.sos_segundoplano.data.validation.FalsePositiveValidationStoreProvider
import com.example.sos_segundoplano.domain.model.TripSessionState
import com.example.sos_segundoplano.domain.preprocessing.ProcessedSignalState
import com.example.sos_segundoplano.domain.rules.BatteryReadinessStatus
import com.example.sos_segundoplano.domain.rules.ConnectivityReadinessStatus
import com.example.sos_segundoplano.domain.rules.DeviceReadinessEvaluation
import com.example.sos_segundoplano.domain.rules.GpsQualityEvaluation
import com.example.sos_segundoplano.domain.rules.GpsQualityStatus
import com.example.sos_segundoplano.domain.rules.MovementContinuityState
import com.example.sos_segundoplano.domain.rules.RiskAssessment
import com.example.sos_segundoplano.domain.rules.RiskAssessmentState
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.rules.RiskContribution
import com.example.sos_segundoplano.domain.rules.RuleEvaluationStatus
import com.example.sos_segundoplano.domain.rules.RuleEvidence
import com.example.sos_segundoplano.domain.rules.RuleId
import com.example.sos_segundoplano.domain.rules.RuleOutcome
import com.example.sos_segundoplano.domain.validation.UserResponseSource
import com.example.sos_segundoplano.domain.validation.activeCountdown

class DebugAccidentAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TRIGGER_ACCIDENT -> triggerAccident()
            ACTION_RESET_ACCIDENT -> resetAccident()
        }
    }

    private fun triggerAccident() {
        val session = activeSession()
        val now = session.endNanos.coerceAtLeast(System.nanoTime())
        val assessment = debugRiskAssessment(session.sessionId, now)
        TripSessionStoreProvider.store.setState(TripSessionState.Active)
        RiskAssessmentStoreProvider.store.publish(assessment)
    }

    private fun resetAccident() {
        val countdown = FalsePositiveValidationStoreProvider.store.states.value.activeCountdown ?: return
        FalsePositiveValidationCoordinatorProvider.coordinator.confirmSafe(
            sessionId = countdown.metadata.sessionId,
            assessmentId = countdown.metadata.assessmentId,
            source = UserResponseSource.Mobile,
            responseId = "debug-reset-${countdown.metadata.sessionId}-${countdown.metadata.assessmentId}"
        )
    }

    private fun activeSession(): DebugSession {
        when (val riskState = RiskAssessmentStoreProvider.store.states.value) {
            is RiskAssessmentState.AssessmentReady -> return DebugSession(riskState.assessment.sessionId, riskState.assessment.endNanos + ONE_SECOND_NANOS)
            is RiskAssessmentState.Collecting -> return DebugSession(riskState.sessionId, System.nanoTime())
            is RiskAssessmentState.InsufficientData -> return DebugSession(riskState.sessionId, System.nanoTime())
            RiskAssessmentState.Idle,
            RiskAssessmentState.Stopped,
            is RiskAssessmentState.Error -> Unit
        }
        return when (val processedState = ProcessedSignalStoreProvider.store.states.value) {
            is ProcessedSignalState.WindowReady -> DebugSession(processedState.window.sessionId, processedState.window.endNanos + ONE_SECOND_NANOS)
            is ProcessedSignalState.Collecting -> DebugSession(processedState.sessionId, System.nanoTime())
            is ProcessedSignalState.InsufficientData -> DebugSession(processedState.sessionId, System.nanoTime())
            ProcessedSignalState.Idle,
            ProcessedSignalState.Stopped,
            is ProcessedSignalState.Error -> DebugSession(1L, System.nanoTime())
        }
    }

    private fun debugRiskAssessment(sessionId: Long, now: Long): RiskAssessment {
        val outcomes = listOf(
            triggered(RuleId.Fall, 0.8, now),
            triggered(RuleId.Immobility, 0.7, now),
            triggered(RuleId.Impact, 0.7, now)
        )
        return RiskAssessment(
            sessionId = sessionId,
            assessmentId = now,
            windowId = now,
            startNanos = now - 1_000_000_000L,
            endNanos = now,
            score = 75,
            riskLevel = RiskLevel.High,
            confidence = 0.8,
            outcomes = outcomes,
            contributions = outcomes.map { RiskContribution(it.ruleId, 1.0, 1.0) },
            gpsQuality = GpsQualityEvaluation(GpsQualityStatus.Good, null, null, 0.9),
            deviceReadiness = DeviceReadinessEvaluation(
                batteryStatus = BatteryReadinessStatus.Normal,
                batteryPercentage = null,
                charging = null,
                connectivityStatus = ConnectivityReadinessStatus.Available,
                connectivityValidated = true,
                transport = null,
                wearableStatus = null,
                canCommunicateLater = true,
                confidence = 0.8
            ),
            movementContinuity = MovementContinuityState.Stopped,
            droppedProcessedWindows = 0L,
            lateWindows = 0L,
            droppedRawEvents = 0L,
            ruleSetVersion = "debug-trigger",
            partialWindow = false
        )
    }

    private fun triggered(ruleId: RuleId, severity: Double, now: Long): RuleOutcome = RuleOutcome(
        ruleId = ruleId,
        status = RuleEvaluationStatus.Triggered,
        severity = severity,
        confidence = 0.8,
        evidence = RuleEvidence(text = "debug_trigger"),
        usedSignals = emptySet(),
        missingSignals = emptySet(),
        thresholds = emptyMap(),
        startNanos = now - 1_000_000_000L,
        endNanos = now,
        reason = "debug_trigger",
        contribution = RiskContribution(ruleId, 1.0, 1.0),
        ruleSetVersion = "debug-trigger"
    )

    private companion object {
        const val ACTION_TRIGGER_ACCIDENT = "com.example.sos_segundoplano.DEBUG_TRIGGER_ACCIDENT"
        const val ACTION_RESET_ACCIDENT = "com.example.sos_segundoplano.DEBUG_RESET_ACCIDENT"
        const val ONE_SECOND_NANOS = 1_000_000_000L
    }

    private data class DebugSession(val sessionId: Long, val endNanos: Long)
}
