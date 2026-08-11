package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.validation.InMemoryBoundedValidationStore
import com.example.sos_segundoplano.domain.offline.OfflineEventSink
import com.example.sos_segundoplano.domain.offline.OfflineQueueEnqueueResult
import com.example.sos_segundoplano.domain.offline.OfflineSyncErrorCategory
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.validation.MinorEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualSosIncidentCoordinatorTest {
    @Test fun manualSosCreatesOneLocalIncidentWithUnknownRiskAndCanonicalRemoteMappingInput() = runBlocking {
        val store = InMemoryBoundedValidationStore<LocalIncident>(4)
        val remote = CapturingRemoteCreator()
        val persistence = CapturingOfflineEventSink()
        val coordinator = ManualSosIncidentCoordinator(
            remoteCreator = remote,
            offlineEventSink = persistence,
            incidentStore = store,
            nextIncidentId = { 41L },
            nextClientIncidentId = { "123e4567-e89b-12d3-a456-426614174041" },
            nowElapsedRealtimeNanos = { 99L }
        )

        val created = coordinator.requestManualSos()

        assertEquals(IncidentCause.ManualSos, remote.incident?.cause)
        assertEquals(RiskLevel.Unknown, remote.incident?.riskLevel)
        assertEquals(false, remote.incident?.hasAssessmentEvidence)
        assertEquals(0L, remote.incident?.sessionId)
        assertEquals(0L, remote.incident?.assessmentId)
        assertEquals(0L, remote.incident?.windowId)
        assertEquals("123e4567-e89b-12d3-a456-426614174041", remote.incident?.clientIncidentId)
        assertEquals(remote.incident, persistence.incident)
        assertEquals(null, remote.incident?.remoteTripId)
        assertEquals(IncidentRemoteCreationStatus.Success("remote-incident-1"), created.remoteCreationStatus)
        assertEquals(listOf(created), store.items.value)
    }

    @Test fun offlineQueueFailureDoesNotBlockDurableRemoteIncidentFlow() = runBlocking {
        val store = InMemoryBoundedValidationStore<LocalIncident>(4)
        val remote = CapturingRemoteCreator()
        val coordinator = ManualSosIncidentCoordinator(
            remoteCreator = remote,
            offlineEventSink = CapturingOfflineEventSink(
                OfflineQueueEnqueueResult.PersistenceFailed(
                    OfflineSyncErrorCategory.Serialization,
                    "fixture_failure"
                )
            ),
            incidentStore = store,
            nextIncidentId = { 42L },
            nextClientIncidentId = { "123e4567-e89b-12d3-a456-426614174042" }
        )

        val created = coordinator.requestManualSos()

        assertEquals("123e4567-e89b-12d3-a456-426614174042", remote.incident?.clientIncidentId)
        assertEquals(IncidentRemoteCreationStatus.Success("remote-incident-1"), created.remoteCreationStatus)
        assertEquals(listOf(created), store.items.value)
    }

    private class CapturingOfflineEventSink(
        private val result: OfflineQueueEnqueueResult =
            OfflineQueueEnqueueResult.PersistedAndScheduled(1L, "local-incident:41")
    ) : OfflineEventSink {
        var incident: LocalIncident? = null

        override suspend fun enqueueIncident(incident: LocalIncident): OfflineQueueEnqueueResult {
            this.incident = incident
            return result
        }

        override suspend fun enqueueMinorEvent(event: MinorEvent): OfflineQueueEnqueueResult = error("unused")
        override suspend fun enqueueAlertRequest(request: AlertDispatchRequest): OfflineQueueEnqueueResult = error("unused")
        override suspend fun enqueueIncidentBundle(
            incident: LocalIncident,
            request: AlertDispatchRequest
        ): OfflineQueueEnqueueResult = error("unused")
    }

    private class CapturingRemoteCreator : IncidentRemoteCreator {
        var incident: LocalIncident? = null

        override suspend fun createIncident(incident: LocalIncident): LocalIncident {
            this.incident = incident
            return incident.copy(
                remoteTripId = "remote-trip-1",
                remoteIncidentId = "remote-incident-1",
                remoteCreationStatus = IncidentRemoteCreationStatus.Success("remote-incident-1")
            )
        }
    }
}
