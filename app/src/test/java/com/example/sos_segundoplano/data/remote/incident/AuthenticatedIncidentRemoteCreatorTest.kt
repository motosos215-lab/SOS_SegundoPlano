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

    private class FakeIncidentRemoteDataSource(
        private val status: IncidentRemoteCreationStatus
    ) : IncidentRemoteDataSource {
        var authorization: String? = null
        var request: CreateIncidentRequestDto? = null

        override suspend fun createIncident(
            authorization: String,
            request: CreateIncidentRequestDto
        ): IncidentRemoteCreationStatus {
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

    private fun incident(score: Int? = 75): LocalIncident = LocalIncident(
        incidentId = 1L,
        sessionId = 1L,
        assessmentId = 2L,
        windowId = 3L,
        createdAtElapsedRealtimeNanos = 4L,
        cause = IncidentCause.Timeout,
        score = score,
        riskLevel = RiskLevel.High,
        confidence = 0.8,
        relevantOutcomes = emptyList(),
        ruleSetVersion = "local-rules-v1",
        validationPolicyVersion = "false-positive-validation-v1",
        gpsQuality = GpsQualityStatus.Good
    )
}
