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
import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.AlertPayloadSummary
import com.example.sos_segundoplano.domain.validation.AlertPriority
import com.example.sos_segundoplano.domain.validation.IncidentCause
import com.example.sos_segundoplano.domain.validation.IncidentRemoteCreationStatus
import com.example.sos_segundoplano.domain.validation.LocalIncident
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedAutomaticSosAlertCreatorTest {
    @Test fun timeoutUsesCanonicalMobileSosRequestWithDurableValues() = runBlocking {
        val remote = RecordingRemote(success())
        val result = creator(remote).createAutomaticSosAlert(incident(IncidentCause.Timeout), request())

        val sent = remote.requests.single()
        assertEquals("CountdownTimeout", sent.incidentType)
        assertEquals("CountdownTimeout", sent.reason)
        assertEquals(CLIENT_INCIDENT_ID, sent.clientIncidentId)
        assertEquals(CLIENT_ALERT_ID, sent.clientAlertRequestId)
        assertEquals("2024-08-01T12:00:00Z", sent.detectedAtUtc)
        assertEquals("High", sent.severity)
        assertEquals("High", sent.priority)
        assertEquals(IncidentRemoteCreationStatus.Success("incident-1"), result.remoteCreationStatus)
    }

    @Test fun userRequestedHelpUsesCanonicalMobileSosRequest() = runBlocking {
        val remote = RecordingRemote(success())
        creator(remote).createAutomaticSosAlert(incident(IncidentCause.UserRequestedHelp), request())

        assertEquals("UserRequestedHelp", remote.requests.single().incidentType)
        assertEquals("UserRequestedHelp", remote.requests.single().reason)
    }

    @Test fun captureLocationUsesExistingLocationWithoutQueryingProviderAndPreservesIt() = runBlocking {
        var providerCalls = 0
        val creator = creator(
            RecordingRemote(success()),
            locationProvider = ManualSosLocationProvider {
                providerCalls++
                error("provider must not be called")
            }
        )

        val captured = creator.captureLocation(incident(IncidentCause.Timeout))

        assertEquals(0, providerCalls)
        assertEquals(19.4326, captured.latitude)
        assertEquals(-99.1332, captured.longitude)
    }

    @Test fun resolveRemoteTripReturnsDurableTripBeforeSubmission() = runBlocking {
        val resolved = creator(RecordingRemote(success())).resolveRemoteTrip(
            incident(IncidentCause.Timeout).copy(remoteTripId = null)
        )

        assertEquals("trip-1", resolved.remoteTripId)
        assertEquals(IncidentRemoteCreationStatus.Pending, resolved.remoteCreationStatus)
    }

    @Test fun missingLocationDoesNotSubmitHttpRequest() = runBlocking {
        val remote = RecordingRemote(success())
        val creator = creator(remote, locationProvider = ManualSosLocationProvider { null })
        val located = creator.captureLocation(incident(IncidentCause.Timeout).copy(latitude = null, longitude = null))
        val result = creator.createAutomaticSosAlert(located, request())

        assertEquals(IncidentRemoteCreationStatus.MissingRequiredData("location_unavailable"), result.remoteCreationStatus)
        assertTrue(remote.requests.isEmpty())
    }

    @Test fun missingPreparedAttemptIsInvalidResponseAndDoesNotBecomeSuccess() = runBlocking {
        val remote = RecordingRemote(
            ManualSosAlertSubmissionStatus.Success("incident-1", "dispatch-1", emptyList(), ManualSosAlertSummaryDto(totalPrepared = 0))
        )

        val result = creator(remote).createAutomaticSosAlert(incident(IncidentCause.Timeout), request())

        assertEquals(IncidentRemoteCreationStatus.InvalidResponse("alert_not_prepared"), result.remoteCreationStatus)
    }

    private fun creator(
        remote: ManualSosAlertRemoteDataSource,
        locationProvider: ManualSosLocationProvider = ManualSosLocationProvider { location() }
    ) = AuthenticatedAutomaticSosAlertCreator(
        authRepository = FakeAuthRepository(),
        remoteDataSource = remote,
        activeTripRemoteResolver = ActiveTripRemoteResolver { ActiveTripLookupResult.NoActiveTrip },
        remoteTripSessionStore = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("trip-1") },
        locationProvider = locationProvider
    )

    private fun incident(cause: IncidentCause) = LocalIncident(
        incidentId = 1L, sessionId = 2L, assessmentId = 3L, windowId = 4L,
        createdAtElapsedRealtimeNanos = 5L, cause = cause, score = 70, riskLevel = RiskLevel.High,
        confidence = 0.9, relevantOutcomes = emptyList(), ruleSetVersion = "rules", validationPolicyVersion = "policy",
        gpsQuality = GpsQualityStatus.Good, clientIncidentId = CLIENT_INCIDENT_ID,
        detectedAtEpochMillis = 1_722_513_600_000L, latitude = 19.4326, longitude = -99.1332,
        remoteCreationStatus = IncidentRemoteCreationStatus.Pending
    )

    private fun request() = AlertDispatchRequest(
        requestId = 6L, incidentId = 1L, sessionId = 2L, assessmentId = 3L, priority = AlertPriority.High,
        reason = IncidentCause.Timeout, createdAtElapsedRealtimeNanos = 7L, score = 70, confidence = 0.9,
        payload = AlertPayloadSummary(2L, 3L, 1L, 70, RiskLevel.High, IncidentCause.Timeout, "policy"),
        clientAlertRequestId = CLIENT_ALERT_ID
    )

    private fun success() = ManualSosAlertSubmissionStatus.Success(
        remoteIncidentId = "incident-1", remoteAlertDispatchId = "dispatch-1",
        notificationAttempts = listOf(ManualSosNotificationAttemptDto(status = "Prepared")),
        summary = ManualSosAlertSummaryDto(totalPrepared = 1)
    )

    private fun location() = LocationSample(
        latitude = 19.4326,
        longitude = -99.1332,
        accuracyMeters = 5f,
        timestampMillis = 1_722_513_600_000L,
        provider = "gps",
        isMock = false
    )

    private class RecordingRemote(private val result: ManualSosAlertSubmissionStatus) : ManualSosAlertRemoteDataSource {
        val requests = mutableListOf<ManualSosAlertRequestDto>()
        override suspend fun createManualSosAlert(authorization: String, request: ManualSosAlertRequestDto): ManualSosAlertSubmissionStatus {
            requests += request
            return result
        }
    }

    private class FakeAuthRepository : AuthRepository {
        override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> = error("unused")
        override suspend fun restoreSession(): AuthResult<AuthUser?> = AuthResult.Success(null)
        override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> = AuthResult.Success(AccessToken("access-token"))
        override suspend fun refreshSession(): AuthResult<AuthUser> = AuthResult.Success(AuthUser("rider", "rider@example.invalid", "Rider", "0", UserRole.Rider, true))
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override fun observeSession(): StateFlow<SessionState> = MutableStateFlow(SessionState.LoggedOut)
    }

    private companion object {
        const val CLIENT_INCIDENT_ID = "123e4567-e89b-12d3-a456-426614174000"
        const val CLIENT_ALERT_ID = "223e4567-e89b-12d3-a456-426614174000"
    }
}
