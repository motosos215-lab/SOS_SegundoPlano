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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Instant
import java.util.UUID

class ManualSosIncidentCoordinatorTest {
    @Test fun manualSosCreatesOneLocalIncidentWithUnknownRiskAndCanonicalRemoteMappingInput() = runBlocking {
        val store = InMemoryBoundedValidationStore<LocalIncident>(4)
        val remote = CapturingRemoteCreator()
        val persistence = CapturingOfflineEventSink()
        val links = InMemoryRemoteIncidentLinkStore()
        val coordinator = ManualSosIncidentCoordinator(
            remoteCreator = remote,
            offlineEventSink = persistence,
            remoteIncidentLinkStore = links,
            incidentStore = store,
            nextIncidentId = { 41L },
            nextClientIncidentId = { "123e4567-e89b-12d3-a456-426614174041" },
            nextClientAlertRequestId = { "223e4567-e89b-12d3-a456-426614174041" },
            nowUtc = { Instant.parse("2026-08-11T15:55:00Z") },
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
        val link = links.read("123e4567-e89b-12d3-a456-426614174041")
        assertEquals("223e4567-e89b-12d3-a456-426614174041", link?.clientAlertRequestId)
        assertEquals("2026-08-11T15:55:00Z", link?.detectedAtUtc)
        assertNotNull(UUID.fromString(requireNotNull(link?.clientIncidentId)))
        assertNotNull(UUID.fromString(requireNotNull(link?.clientAlertRequestId)))
    }

    @Test fun offlineQueueFailureDoesNotBlockDurableRemoteIncidentFlow() = runBlocking {
        val store = InMemoryBoundedValidationStore<LocalIncident>(4)
        val remote = CapturingRemoteCreator()
        val links = InMemoryRemoteIncidentLinkStore()
        val coordinator = ManualSosIncidentCoordinator(
            remoteCreator = remote,
            offlineEventSink = CapturingOfflineEventSink(
                OfflineQueueEnqueueResult.PersistenceFailed(
                    OfflineSyncErrorCategory.Serialization,
                    "fixture_failure"
                )
            ),
            remoteIncidentLinkStore = links,
            incidentStore = store,
            nextIncidentId = { 42L },
            nextClientIncidentId = { "123e4567-e89b-12d3-a456-426614174042" },
            nextClientAlertRequestId = { "223e4567-e89b-12d3-a456-426614174042" },
            nowUtc = { Instant.parse("2026-08-11T15:56:00Z") }
        )

        val created = coordinator.requestManualSos()

        assertEquals("123e4567-e89b-12d3-a456-426614174042", remote.incident?.clientIncidentId)
        assertEquals(IncidentRemoteCreationStatus.Success("remote-incident-1"), created.remoteCreationStatus)
        assertEquals(listOf(created), store.items.value)
    }

    @Test fun retryReusesDurableCorrelationAndDetectedTime() = runBlocking {
        val links = InMemoryRemoteIncidentLinkStore()
        val remote = CapturingRemoteCreator(failFirst = true)
        var clientIncidentGenerations = 0
        var clientAlertGenerations = 0
        val coordinator = ManualSosIncidentCoordinator(
            remoteCreator = remote,
            offlineEventSink = CapturingOfflineEventSink(),
            remoteIncidentLinkStore = links,
            incidentStore = InMemoryBoundedValidationStore(4),
            nextIncidentId = { 43L },
            nextClientIncidentId = {
                clientIncidentGenerations++
                "123e4567-e89b-12d3-a456-426614174043"
            },
            nextClientAlertRequestId = {
                clientAlertGenerations++
                "223e4567-e89b-12d3-a456-426614174043"
            },
            nowUtc = { Instant.parse("2026-08-11T15:57:00Z") }
        )

        coordinator.requestManualSos()
        coordinator.requestManualSos()

        assertEquals(1, clientIncidentGenerations)
        assertEquals(1, clientAlertGenerations)
        assertEquals(2, remote.incidents.size)
        assertEquals(remote.incidents[0].clientIncidentId, remote.incidents[1].clientIncidentId)
        val link = links.read("123e4567-e89b-12d3-a456-426614174043")
        assertEquals("223e4567-e89b-12d3-a456-426614174043", link?.clientAlertRequestId)
        assertEquals("2026-08-11T15:57:00Z", link?.detectedAtUtc)
    }

    @Test fun concurrentDoubleTapJoinsOneLogicalManualSos() = runBlocking {
        val remote = BlockingRemoteCreator()
        val coordinator = ManualSosIncidentCoordinator(
            remoteCreator = remote,
            offlineEventSink = CapturingOfflineEventSink(),
            remoteIncidentLinkStore = InMemoryRemoteIncidentLinkStore(),
            incidentStore = InMemoryBoundedValidationStore(4),
            nextIncidentId = { 44L },
            nextClientIncidentId = { "123e4567-e89b-12d3-a456-426614174044" },
            nextClientAlertRequestId = { "223e4567-e89b-12d3-a456-426614174044" },
            nowUtc = { Instant.parse("2026-08-11T15:58:00Z") }
        )

        val first = async { coordinator.requestManualSos() }
        remote.entered.await()
        val second = async { coordinator.requestManualSos() }
        yield()
        remote.release.complete(Unit)

        assertEquals(first.await(), second.await())
        assertEquals(1, remote.calls)
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

    private class CapturingRemoteCreator(
        private val failFirst: Boolean = false
    ) : ManualSosAlertCreator {
        var incident: LocalIncident? = null
        val incidents = mutableListOf<LocalIncident>()

        override suspend fun createManualSosAlert(incident: LocalIncident): LocalIncident {
            this.incident = incident
            incidents += incident
            if (failFirst && incidents.size == 1) {
                return incident.copy(
                    remoteCreationStatus = IncidentRemoteCreationStatus.NetworkUnavailable("fixture_offline")
                )
            }
            return incident.copy(
                remoteTripId = "remote-trip-1",
                remoteIncidentId = "remote-incident-1",
                remoteCreationStatus = IncidentRemoteCreationStatus.Success("remote-incident-1")
            )
        }
    }

    private class BlockingRemoteCreator : ManualSosAlertCreator {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun createManualSosAlert(incident: LocalIncident): LocalIncident {
            calls++
            entered.complete(Unit)
            release.await()
            return incident.copy(
                remoteTripId = "remote-trip-1",
                remoteIncidentId = "remote-incident-1",
                remoteCreationStatus = IncidentRemoteCreationStatus.Success("remote-incident-1")
            )
        }
    }
}
