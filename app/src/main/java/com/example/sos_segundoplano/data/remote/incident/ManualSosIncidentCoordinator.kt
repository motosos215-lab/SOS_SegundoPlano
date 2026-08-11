package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.validation.IncidentStoreProvider
import com.example.sos_segundoplano.data.validation.LocalIncidentStore
import com.example.sos_segundoplano.domain.offline.OfflineEventSink
import com.example.sos_segundoplano.domain.rules.GpsQualityStatus
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import com.example.sos_segundoplano.domain.validation.LocalIncident
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class ManualSosIncidentCoordinator(
    private val remoteCreator: IncidentRemoteCreator,
    private val offlineEventSink: OfflineEventSink,
    private val incidentStore: LocalIncidentStore = IncidentStoreProvider.incidents,
    private val nextIncidentId: () -> Long = { IncidentStoreProvider.incidentIds.incrementAndGet() },
    private val nextClientIncidentId: () -> String = { UUID.randomUUID().toString() },
    private val nowElapsedRealtimeNanos: () -> Long = System::nanoTime
) {
    private val mutex = Mutex()

    suspend fun requestManualSos(): LocalIncident = mutex.withLock {
        val localIncidentId = nextIncidentId()
        val incident = LocalIncident(
            incidentId = localIncidentId,
            sessionId = MANUAL_CONTEXT_ID,
            assessmentId = MANUAL_CONTEXT_ID,
            windowId = MANUAL_CONTEXT_ID,
            createdAtElapsedRealtimeNanos = nowElapsedRealtimeNanos(),
            cause = IncidentCause.ManualSos,
            score = null,
            riskLevel = RiskLevel.Unknown,
            confidence = 0.0,
            relevantOutcomes = emptyList(),
            ruleSetVersion = MANUAL_RULE_SET_VERSION,
            validationPolicyVersion = MANUAL_POLICY_VERSION,
            gpsQuality = GpsQualityStatus.Unavailable,
            hasAssessmentEvidence = false,
            clientIncidentId = nextClientIncidentId(),
            remoteCreationStatus = IncidentRemoteCreationStatus.Pending
        )
        try {
            offlineEventSink.enqueueIncident(incident)
        } catch (_: IllegalStateException) {
            Unit
        }
        val result = remoteCreator.createIncident(incident)
        incidentStore.add(result)
        result
    }

    private companion object {
        const val MANUAL_RULE_SET_VERSION = "manual-sos"
        const val MANUAL_POLICY_VERSION = "manual-sos-v1"
        const val MANUAL_CONTEXT_ID = 0L
    }
}
