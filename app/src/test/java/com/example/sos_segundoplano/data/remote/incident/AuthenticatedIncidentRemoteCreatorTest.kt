package com.example.sos_segundoplano.data.remote.incident

import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionExpired
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.data.remote.trip.ActiveTripLookupResult
import com.example.sos_segundoplano.data.remote.trip.ActiveTripRemoteResolver
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.rules.GpsQualityStatus
import com.example.sos_segundoplano.domain.rules.RiskLevel
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import com.example.sos_segundoplano.domain.validation.LocalIncident
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class AuthenticatedIncidentRemoteCreatorTest {
    @Test fun timeoutIncidentBuildsRequestFromAvailableMobileDataAndStoresRemoteId() = runBlocking {
        val remote = FakeIncidentRemoteDataSource(IncidentRemoteCreationStatus.Success("incident-remote-1"))
        val creator = AuthenticatedIncidentRemoteCreator(
            authRepository = FakeAuthRepository(),
            remoteDataSource = remote,
            activeTripRemoteResolver = FakeActiveTripRemoteResolver(ActiveTripLookupResult.Found("remote-trip-1")),
            nowUtc = { Instant.parse("2026-08-08T14:20:00Z") }
        )

        val created = creator.createIncident(incident())

        assertEquals("incident-remote-1", created.remoteIncidentId)
        assertEquals("remote-trip-1", created.remoteTripId)
        assertEquals(IncidentRemoteCreationStatus.Success("incident-remote-1"), created.remoteCreationStatus)
        assertEquals("Bearer access-token", remote.authorization)
        assertTrue(UUID.fromString(requireNotNull(created.clientIncidentId)).toString() == created.clientIncidentId)
        assertEquals(CreateIncidentRequestDto(
            tripId = "remote-trip-1",
            clientIncidentId = requireNotNull(created.clientIncidentId),
            source = "MobileDetection",
            cause = "CountdownTimeout",
            riskLevel = "High",
            score = 75,
            confidence = 0.8,
            gpsQuality = "Good",
            ruleSetVersion = "local-rules-v1",
            validationPolicyVersion = "false-positive-validation-v1",
            occurredAtUtc = "2026-08-08T14:20:00Z"
        ), remote.request)
    }

    @Test fun authFailureDoesNotCallRemoteAndKeepsExplicitStatus() = runBlocking {
        val remote = FakeIncidentRemoteDataSource(IncidentRemoteCreationStatus.Success("incident-remote-1"))
        val creator = AuthenticatedIncidentRemoteCreator(
            authRepository = FakeAuthRepository(tokenResult = SessionExpired),
            remoteDataSource = remote,
            activeTripRemoteResolver = FakeActiveTripRemoteResolver(ActiveTripLookupResult.Found("remote-trip-1"))
        )

        val created = creator.createIncident(incident())

        assertEquals(null, remote.request)
        assertEquals(IncidentRemoteCreationStatus.HttpError(401, "access_token_unavailable"), created.remoteCreationStatus)
    }

    @Test fun noActiveRemoteTripDoesNotPostInvalidIncident() = runBlocking {
        val remote = FakeIncidentRemoteDataSource(IncidentRemoteCreationStatus.Success("incident-remote-1"))
        val creator = AuthenticatedIncidentRemoteCreator(
            authRepository = FakeAuthRepository(),
            remoteDataSource = remote,
            activeTripRemoteResolver = FakeActiveTripRemoteResolver(ActiveTripLookupResult.NoActiveTrip)
        )

        val created = creator.createIncident(incident())

        assertEquals(null, remote.request)
        assertEquals(null, created.remoteTripId)
        assertEquals(IncidentRemoteCreationStatus.MissingRequiredData("active_remote_trip_missing"), created.remoteCreationStatus)
    }

    @Test fun timeoutIncidentWithoutScoreStillPostsAndStoresRemoteIncidentId() = runBlocking {
        val remote = FakeIncidentRemoteDataSource(IncidentRemoteCreationStatus.Success("incident-remote-1"))
        val creator = AuthenticatedIncidentRemoteCreator(
            authRepository = FakeAuthRepository(),
            remoteDataSource = remote,
            activeTripRemoteResolver = FakeActiveTripRemoteResolver(ActiveTripLookupResult.Found("remote-trip-1"))
        )

        val created = creator.createIncident(incident(score = null))

        assertEquals(null, remote.request?.score)
        assertEquals("incident-remote-1", created.remoteIncidentId)
        assertEquals(IncidentRemoteCreationStatus.Success("incident-remote-1"), created.remoteCreationStatus)
    }

    @Test fun tripLookupHttpAndNetworkFailuresDoNotCrashOrPostIncident() = runBlocking {
        val cases = listOf(
            ActiveTripLookupResult.HttpError(500, "server_error") to IncidentRemoteCreationStatus.HttpError(500, "trip_lookup_failed"),
            ActiveTripLookupResult.NetworkUnavailable("network_unavailable") to IncidentRemoteCreationStatus.NetworkUnavailable("network_unavailable")
        )
        cases.forEach { (tripResult, expectedStatus) ->
            val remote = FakeIncidentRemoteDataSource(IncidentRemoteCreationStatus.Success("incident-remote-1"))
            val creator = AuthenticatedIncidentRemoteCreator(
                authRepository = FakeAuthRepository(),
                remoteDataSource = remote,
                activeTripRemoteResolver = FakeActiveTripRemoteResolver(tripResult)
            )

            val created = creator.createIncident(incident())

            assertEquals(null, remote.request)
            assertEquals(expectedStatus, created.remoteCreationStatus)
        }
    }

    @Test fun successfulRemoteIdIsDurableAndPreventsDuplicatePostAfterCreatorRecreation() = runBlocking {
        val linkStore = InMemoryRemoteIncidentLinkStore()
        val firstRemote = FakeIncidentRemoteDataSource(IncidentRemoteCreationStatus.Success("incident-remote-1"))
        val firstCreator = AuthenticatedIncidentRemoteCreator(
            authRepository = FakeAuthRepository(),
            remoteDataSource = firstRemote,
            activeTripRemoteResolver = FakeActiveTripRemoteResolver(ActiveTripLookupResult.Found("remote-trip-1")),
            remoteIncidentLinkStore = linkStore,
            nowUtc = { Instant.parse("2026-08-08T14:20:00Z") }
        )

        val first = firstCreator.createIncident(incident())
        val secondRemote = FakeIncidentRemoteDataSource(IncidentRemoteCreationStatus.Success("unexpected-duplicate"))
        val restoredCreator = AuthenticatedIncidentRemoteCreator(
            authRepository = FakeAuthRepository(),
            remoteDataSource = secondRemote,
            activeTripRemoteResolver = FakeActiveTripRemoteResolver(ActiveTripLookupResult.NoActiveTrip),
            remoteIncidentLinkStore = linkStore
        )

        val restored = restoredCreator.createIncident(incident())

        assertEquals(1, firstRemote.calls)
        assertEquals(0, secondRemote.calls)
        assertEquals(first.clientIncidentId, restored.clientIncidentId)
        assertEquals("incident-remote-1", restored.remoteIncidentId)
        assertEquals(RemoteIncidentSyncState.Created, linkStore.read(requireNotNull(first.clientIncidentId))?.syncState)
    }

    @Test fun failedPostKeepsPendingLinkAndRetryReusesClientIncidentId() = runBlocking {
        val linkStore = InMemoryRemoteIncidentLinkStore()
        val failedCreator = AuthenticatedIncidentRemoteCreator(
            authRepository = FakeAuthRepository(),
            remoteDataSource = FakeIncidentRemoteDataSource(IncidentRemoteCreationStatus.NetworkUnavailable("network_unavailable")),
            activeTripRemoteResolver = FakeActiveTripRemoteResolver(ActiveTripLookupResult.Found("remote-trip-1")),
            remoteIncidentLinkStore = linkStore,
            nowUtc = { Instant.parse("2026-08-08T14:20:00Z") }
        )

        val failed = failedCreator.createIncident(incident())
        val pending = linkStore.read(requireNotNull(failed.clientIncidentId))
        val retryCreator = AuthenticatedIncidentRemoteCreator(
            authRepository = FakeAuthRepository(),
            remoteDataSource = FakeIncidentRemoteDataSource(IncidentRemoteCreationStatus.Success("incident-remote-1")),
            activeTripRemoteResolver = FakeActiveTripRemoteResolver(ActiveTripLookupResult.Found("remote-trip-1")),
            remoteIncidentLinkStore = linkStore,
            nowUtc = { Instant.parse("2026-08-08T14:21:00Z") }
        )

        val retried = retryCreator.createIncident(incident())

        assertEquals(RemoteIncidentSyncState.Pending, pending?.syncState)
        assertEquals(failed.clientIncidentId, retried.clientIncidentId)
        assertEquals("incident-remote-1", retried.remoteIncidentId)
        assertEquals(RemoteIncidentSyncState.Created, linkStore.read(requireNotNull(retried.clientIncidentId))?.syncState)
    }

    @Test fun persistenceFailurePreventsPostingAnIncidentWithoutDurableLink() = runBlocking {
        val remote = FakeIncidentRemoteDataSource(IncidentRemoteCreationStatus.Success("incident-remote-1"))
        val creator = AuthenticatedIncidentRemoteCreator(
            authRepository = FakeAuthRepository(),
            remoteDataSource = remote,
            activeTripRemoteResolver = FakeActiveTripRemoteResolver(ActiveTripLookupResult.Found("remote-trip-1")),
            remoteIncidentLinkStore = object : RemoteIncidentLinkStore {
                override fun read(clientIncidentId: String): RemoteIncidentLink? = null
                override fun save(link: RemoteIncidentLink): Boolean = false
            }
        )

        val created = creator.createIncident(incident())

        assertEquals(0, remote.calls)
        assertEquals(
            IncidentRemoteCreationStatus.InvalidResponse("incident_link_persistence_failed"),
            created.remoteCreationStatus
        )
    }

    @Test fun unconfirmedHelpAndCriticalWireCausesRemainBlockedWithoutInventedPosts() = runBlocking {
        listOf(IncidentCause.UserRequestedHelp, IncidentCause.CriticalPhysicalEvent).forEach { cause ->
            val remote = FakeIncidentRemoteDataSource(IncidentRemoteCreationStatus.Success("unexpected-remote-id"))
            val creator = AuthenticatedIncidentRemoteCreator(
                authRepository = FakeAuthRepository(),
                remoteDataSource = remote,
                activeTripRemoteResolver = FakeActiveTripRemoteResolver(ActiveTripLookupResult.Found("remote-trip-1"))
            )

            val unchanged = creator.createIncident(incident(cause = cause))

            assertEquals(0, remote.calls)
            assertEquals(IncidentRemoteCreationStatus.NotRequested, unchanged.remoteCreationStatus)
            assertEquals(null, unchanged.clientIncidentId)
        }
    }

    private class FakeIncidentRemoteDataSource(
        private val status: IncidentRemoteCreationStatus
    ) : IncidentRemoteDataSource {
        var calls = 0
        var authorization: String? = null
        var request: CreateIncidentRequestDto? = null

        override suspend fun createIncident(
            authorization: String,
            request: CreateIncidentRequestDto
        ): IncidentRemoteCreationStatus {
            calls++
            this.authorization = authorization
            this.request = request
            return status
        }
    }

    private class FakeAuthRepository(
        private val tokenResult: AuthResult<AccessToken> = AuthResult.Success(AccessToken("access-token"))
    ) : AuthRepository {
        override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> = error("unused")
        override suspend fun restoreSession(): AuthResult<AuthUser?> = AuthResult.Success(null)
        override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> = tokenResult
        override suspend fun refreshSession(): AuthResult<AuthUser> = error("unused")
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override fun observeSession(): StateFlow<SessionState> = MutableStateFlow(SessionState.LoggedOut)
    }

    private class FakeActiveTripRemoteResolver(
        private val result: ActiveTripLookupResult
    ) : ActiveTripRemoteResolver {
        override suspend fun resolveActiveTrip(): ActiveTripLookupResult = result
    }

    private fun incident(
        score: Int? = 75,
        cause: IncidentCause = IncidentCause.Timeout
    ): LocalIncident = LocalIncident(
        incidentId = 1L,
        sessionId = 1L,
        assessmentId = 2L,
        windowId = 3L,
        createdAtElapsedRealtimeNanos = 4L,
        cause = cause,
        score = score,
        riskLevel = RiskLevel.High,
        confidence = 0.8,
        relevantOutcomes = emptyList(),
        ruleSetVersion = "local-rules-v1",
        validationPolicyVersion = "false-positive-validation-v1",
        gpsQuality = GpsQualityStatus.Good
    )
}
