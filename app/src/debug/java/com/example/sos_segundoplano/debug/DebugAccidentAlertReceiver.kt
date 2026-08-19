package com.example.sos_segundoplano.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
        Log.d(TAG, "event=debug_accident_trigger_received")
        if (TripSessionStoreProvider.store.states.value !is TripSessionState.Active) {
            Log.w(TAG, "event=debug_accident_trigger_skipped reason=no_active_trip")
            return
        }
        val session = validationSessionOrNull() ?: activeSessionOrNull()
        if (session == null) {
            Log.w(TAG, "event=debug_accident_trigger_skipped reason=signal_pipeline_not_ready")
            return
        }
        val now = session.endNanos.coerceAtLeast(System.nanoTime())
        val assessment = debugRiskAssessment(session.sessionId, now)
        // Submit directly first so the debug trigger remains deterministic even if it is fired
        // immediately after monitoring starts and the SharedFlow collector is still subscribing.
        FalsePositiveValidationCoordinatorProvider.coordinator.submitAssessmentForDebug(assessment)
        // Keep the synthetic assessment visible in the risk store as well. If the collector also
        // observes it, the coordinator's assessment identity guard safely ignores the duplicate.
        RiskAssessmentStoreProvider.store.publish(assessment)
        Log.d(TAG, "event=debug_accident_trigger_submitted risk=high score=75 expected=countdown")
    }

    private fun resetAccident() {
        val countdown = FalsePositiveValidationStoreProvider.store.states.value.activeCountdown ?: run {
            Log.w(TAG, "event=debug_accident_reset_skipped reason=no_active_countdown")
            return
        }
        FalsePositiveValidationCoordinatorProvider.coordinator.confirmSafe(
            sessionId = countdown.metadata.sessionId,
            assessmentId = countdown.metadata.assessmentId,
            source = UserResponseSource.Mobile,
            responseId = "debug-reset-${countdown.metadata.sessionId}-${countdown.metadata.assessmentId}"
        )
    }

    private fun validationSessionOrNull(): DebugSession? = when (val state = FalsePositiveValidationStoreProvider.store.states.value) {
        is com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState.Monitoring ->
            state.sessionId?.let { DebugSession(it, System.nanoTime()) }
        is com.example.sos_segundoplano.domain.validation.FalsePositiveValidationState.CountdownActive ->
            DebugSession(state.assessment.sessionId, state.assessment.endNanos + ONE_SECOND_NANOS)
        else -> null
    }

    private fun activeSessionOrNull(): DebugSession? {
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
            is ProcessedSignalState.Error -> null
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
        const val TAG = "MotoSOS.DebugAccident"
        const val ACTION_TRIGGER_ACCIDENT = "com.example.sos_segundoplano.DEBUG_TRIGGER_ACCIDENT"
        const val ACTION_RESET_ACCIDENT = "com.example.sos_segundoplano.DEBUG_RESET_ACCIDENT"
        const val ONE_SECOND_NANOS = 1_000_000_000L
    }

    private data class DebugSession(val sessionId: Long, val endNanos: Long)
}
