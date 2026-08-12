package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.data.remote.trip.ActiveTripLookupResult
import com.example.sos_segundoplano.data.remote.trip.ActiveTripRemoteResolver
import com.example.sos_segundoplano.data.remote.trip.InMemoryRemoteTripSessionStore
import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.rules.GpsQualityStatus
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.signals.LocationSample
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import com.example.sos_segundoplano.domain.validation.LocalIncident
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthenticatedManualSosAlertCreatorTest {
    @Test fun successUsesCanonicalMappingRealTripAndLocationAndPersistsBothRemoteIds() = runBlocking {
        val links = pendingLinks()
        val tripStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-trip-1") }
        val remote = FakeManualSosRemoteDataSource(success())
        val creator = creator(remote, links, tripStore)

        val result = creator.createManualSosAlert(incident())

        assertEquals(IncidentRemoteCreationStatus.Success("incident-fixture-1"), result.remoteCreationStatus)
        assertEquals("remote-trip-1", result.remoteTripId)
        assertEquals("incident-fixture-1", result.remoteIncidentId)
        assertEquals("ManualSos", remote.requests.single().incidentType)
        assertEquals("High", remote.requests.single().severity)
        assertEquals("High", remote.requests.single().priority)
        assertEquals("ManualSos", remote.requests.single().reason)
        assertEquals(19.4326, remote.requests.single().latitude, 0.0)
        assertEquals(-99.1332, remote.requests.single().longitude, 0.0)
        val persisted = links.read(CLIENT_INCIDENT_ID)
        assertEquals(RemoteIncidentSyncState.Created, persisted?.syncState)
        assertEquals("incident-fixture-1", persisted?.remoteIncidentId)
        assertEquals("dispatch-fixture-1", persisted?.remoteAlertDispatchId)
    }

    @Test fun missingTripReconcilesAndMissingActiveTripDoesNotPost() = runBlocking {
        val foundStore = InMemoryRemoteTripSessionStore()
        val foundRemote = FakeManualSosRemoteDataSource(success())
        creator(
            remote = foundRemote,
            links = pendingLinks(),
            tripStore = foundStore,
            resolver = ActiveTripRemoteResolver {
                foundStore.setRemoteTripId("reconciled-trip")
                ActiveTripLookupResult.Found("reconciled-trip")
            }
        ).createManualSosAlert(incident())
        assertEquals("reconciled-trip", foundRemote.requests.single().tripId)

        val missingRemote = FakeManualSosRemoteDataSource(success())
        val missing = creator(
            remote = missingRemote,
            links = pendingLinks(),
            tripStore = InMemoryRemoteTripSessionStore(),
            resolver = ActiveTripRemoteResolver { ActiveTripLookupResult.NoActiveTrip }
        ).createManualSosAlert(incident())
        assertEquals(
            IncidentRemoteCreationStatus.MissingRequiredData("active_remote_trip_missing"),
            missing.remoteCreationStatus
        )
        assertEquals(0, missingRemote.requests.size)
    }

    @Test fun missingLocationPreservesCorrelationAndNeverPostsInvalidRequest() = runBlocking {
        val links = pendingLinks()
        val remote = FakeManualSosRemoteDataSource(success())
        val result = creator(
            remote = remote,
            links = links,
            tripStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-trip-1") },
            locationProvider = ManualSosLocationProvider { null }
        ).createManualSosAlert(incident())

        assertEquals(
            IncidentRemoteCreationStatus.MissingRequiredData("manual_sos_location_missing"),
            result.remoteCreationStatus
        )
        assertEquals(0, remote.requests.size)
        assertEquals(CLIENT_ALERT_ID, links.readPendingManualSos()?.clientAlertRequestId)
        assertEquals(DETECTED_AT, links.readPendingManualSos()?.detectedAtUtc)
    }

    @Test fun unauthorizedRefreshesOnceAndReusesExactRequest() = runBlocking {
        val auth = FakeAuthRepository()
        val remote = SequenceManualSosRemoteDataSource(
            mutableListOf(
                ManualSosAlertSubmissionStatus.HttpError(401, "unauthorized"),
                success()
            )
        )
        val creator = creator(
            remote = remote,
            links = pendingLinks(),
            tripStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-trip-1") },
            auth = auth
        )

        creator.createManualSosAlert(incident())

        assertEquals(1, auth.refreshCalls)
        assertEquals(2, remote.requests.size)
        assertEquals(remote.requests[0], remote.requests[1])
        assertEquals(listOf("Bearer access-token-1", "Bearer access-token-2"), remote.authorizations)
    }

    @Test fun functional4xxAndNetworkFailureDoNotRegenerateOrRetryInternally() = runBlocking {
        val statuses = listOf(
            ManualSosAlertSubmissionStatus.HttpError(400, "validation_error"),
            ManualSosAlertSubmissionStatus.HttpError(400, "onboarding_not_ready"),
            ManualSosAlertSubmissionStatus.HttpError(400, "trip_not_ready"),
            ManualSosAlertSubmissionStatus.HttpError(403, "forbidden"),
            ManualSosAlertSubmissionStatus.NetworkUnavailable("network_unavailable")
        )
        statuses.forEach { status ->
            val links = pendingLinks()
            val remote = FakeManualSosRemoteDataSource(status)
            creator(
                remote = remote,
                links = links,
                tripStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-trip-1") }
            ).createManualSosAlert(incident())
            assertEquals(1, remote.requests.size)
            assertEquals(CLIENT_INCIDENT_ID, remote.requests.single().clientIncidentId)
            assertEquals(CLIENT_ALERT_ID, remote.requests.single().clientAlertRequestId)
            assertEquals(DETECTED_AT, remote.requests.single().detectedAtUtc)
            assertEquals(RemoteIncidentSyncState.Pending, links.read(CLIENT_INCIDENT_ID)?.syncState)
            assertNull(links.read(CLIENT_INCIDENT_ID)?.remoteIncidentId)
        }
    }

    private fun creator(
        remote: ManualSosAlertRemoteDataSource,
        links: RemoteIncidentLinkStore,
        tripStore: InMemoryRemoteTripSessionStore,
        resolver: ActiveTripRemoteResolver = ActiveTripRemoteResolver { error("resolver must not be called") },
        locationProvider: ManualSosLocationProvider = ManualSosLocationProvider { location() },
        auth: FakeAuthRepository = FakeAuthRepository()
    ) = AuthenticatedManualSosAlertCreator(
        authRepository = auth,
        remoteDataSource = remote,
        activeTripRemoteResolver = resolver,
        remoteTripSessionStore = tripStore,
        remoteIncidentLinkStore = links,
        locationProvider = locationProvider,
        nowEpochMillis = { 1_723_392_901_000L }
    )

    private fun pendingLinks(): InMemoryRemoteIncidentLinkStore = InMemoryRemoteIncidentLinkStore().apply {
        save(
            RemoteIncidentLink(
                localIncidentId = 1L,
                clientIncidentId = CLIENT_INCIDENT_ID,
                remoteTripId = null,
                remoteIncidentId = null,
                syncState = RemoteIncidentSyncState.Pending,
                updatedAtEpochMillis = 1_723_392_900_000L,
                clientAlertRequestId = CLIENT_ALERT_ID,
                detectedAtUtc = DETECTED_AT
            )
        )
    }

    private fun success() = ManualSosAlertSubmissionStatus.Success(
        remoteIncidentId = "incident-fixture-1",
        remoteAlertDispatchId = "dispatch-fixture-1",
        notificationAttempts = emptyList(),
        summary = null
    )

    private fun incident() = LocalIncident(
        incidentId = 1L,
        sessionId = 0L,
        assessmentId = 0L,
        windowId = 0L,
        createdAtElapsedRealtimeNanos = 10L,
        cause = IncidentCause.ManualSos,
        score = null,
        riskLevel = RiskLevel.Unknown,
        confidence = 0.0,
        relevantOutcomes = emptyList(),
        ruleSetVersion = "manual-sos",
        validationPolicyVersion = "manual-sos-v1",
        gpsQuality = GpsQualityStatus.Unavailable,
        hasAssessmentEvidence = false,
        clientIncidentId = CLIENT_INCIDENT_ID,
        remoteCreationStatus = IncidentRemoteCreationStatus.Pending
    )

    private fun location() = LocationSample(
        latitude = 19.4326,
        longitude = -99.1332,
        accuracyMeters = 8f,
        timestampMillis = 1_723_392_900_000L,
        provider = "gps",
        isMock = false
    )

    private class FakeManualSosRemoteDataSource(
        private val result: ManualSosAlertSubmissionStatus
    ) : ManualSosAlertRemoteDataSource {
        val requests = mutableListOf<ManualSosAlertRequestDto>()
        override suspend fun createManualSosAlert(
            authorization: String,
            request: ManualSosAlertRequestDto
        ): ManualSosAlertSubmissionStatus {
            requests += request
            return result
        }
    }

    private class SequenceManualSosRemoteDataSource(
        private val results: MutableList<ManualSosAlertSubmissionStatus>
    ) : ManualSosAlertRemoteDataSource {
        val authorizations = mutableListOf<String>()
        val requests = mutableListOf<ManualSosAlertRequestDto>()
        override suspend fun createManualSosAlert(
            authorization: String,
            request: ManualSosAlertRequestDto
        ): ManualSosAlertSubmissionStatus {
            authorizations += authorization
            requests += request
            return results.removeAt(0)
        }
    }

    private class FakeAuthRepository : AuthRepository {
        var ensureCalls = 0
        var refreshCalls = 0
        override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> = error("unused")
        override suspend fun restoreSession(): AuthResult<AuthUser?> = AuthResult.Success(null)
        override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> {
            ensureCalls++
            return AuthResult.Success(AccessToken("access-token-$ensureCalls"))
        }
        override suspend fun refreshSession(): AuthResult<AuthUser> {
            refreshCalls++
            return AuthResult.Success(
                AuthUser("rider-fixture", "rider@example.invalid", "Rider Fixture", "0000000000", UserRole.Rider, true)
            )
        }
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override fun observeSession(): StateFlow<SessionState> = MutableStateFlow(SessionState.LoggedOut)
    }

    private companion object {
        const val CLIENT_INCIDENT_ID = "123e4567-e89b-12d3-a456-426614174000"
        const val CLIENT_ALERT_ID = "223e4567-e89b-12d3-a456-426614174000"
        const val DETECTED_AT = "2026-08-11T15:55:00Z"
    }
}
