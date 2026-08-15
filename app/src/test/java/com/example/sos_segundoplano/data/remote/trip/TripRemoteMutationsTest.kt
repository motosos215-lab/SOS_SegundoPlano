package com.example.sos_segundoplano.data.remote.trip

import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.data.remote.incident.CurrentManualSosLocationProvider
import com.example.sos_segundoplano.data.remote.incident.TripSignalManualSosLocationProvider
import com.example.sos_segundoplano.data.signals.InMemoryTripSignalStore
import com.example.sos_segundoplano.domain.signals.LocationSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TripRemoteMutationsTest {
    @Test fun startPersistsCanonicalRemoteIdAndAcceptsIdempotentSuccess() = runBlocking {
        val store = InMemoryRemoteTripSessionStore()
        val remote = FakeTripRemoteDataSource(
            startResult = TripMutationResult.Success("remote-trip-1", "Active")
        )
        val starter = AuthenticatedRemoteTripStarter(FakeAuthRepository(), remote, store)
        val request = StartTripRequestDto(
            vehicleId = "vehicle-fixture-1",
            mobileDeviceId = "mobile-device-fixture-1",
            smartwatchDeviceId = null
        )

        val result = starter.startTrip(request)

        assertEquals(TripMutationResult.Success("remote-trip-1", "Active"), result)
        assertEquals("remote-trip-1", store.remoteTripId.value)
        assertEquals(request, remote.startRequest)
        assertEquals("Bearer access-token", remote.authorization)
    }

    @Test fun missingRequiredIdsNeverCallRemoteAndConflictIsNotRetried() = runBlocking {
        val missingRemote = FakeTripRemoteDataSource()
        val starter = AuthenticatedRemoteTripStarter(FakeAuthRepository(), missingRemote, InMemoryRemoteTripSessionStore())

        assertEquals(
            TripMutationResult.MissingRequiredData("vehicle_id_missing"),
            starter.startTrip(StartTripRequestDto("", "mobile-device-fixture-1"))
        )
        assertEquals(0, missingRemote.startCalls)

        val conflictRemote = FakeTripRemoteDataSource(
            startResult = TripMutationResult.HttpError(409, "active_trip_exists")
        )
        val conflictStarter = AuthenticatedRemoteTripStarter(FakeAuthRepository(), conflictRemote, InMemoryRemoteTripSessionStore())
        assertEquals(
            TripMutationResult.HttpError(409, "active_trip_exists"),
            conflictStarter.startTrip(StartTripRequestDto("vehicle-fixture-1", "mobile-device-fixture-1"))
        )
        assertEquals(1, conflictRemote.startCalls)
    }

    @Test fun finishSuccessClearsRemoteIdAndRepeatedBackendSuccessIsAccepted() = runBlocking {
        val store = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-trip-1") }
        val remote = FakeTripRemoteDataSource(
            finishResult = TripMutationResult.Success("remote-trip-1", "Finished")
        )
        val finisher = AuthenticatedRemoteTripFinisher(FakeAuthRepository(), remote, store)

        val result = finisher.finishTrip(FinishTripRequestDto())

        assertEquals(TripMutationResult.Success("remote-trip-1", "Finished"), result)
        assertEquals(null, store.remoteTripId.value)
        assertEquals(FinishTripRequestDto(), remote.finishRequest)
    }

    @Test fun finishNetworkFailureKeepsRemoteIdForRecovery() = runBlocking {
        val store = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-trip-1") }
        val finisher = AuthenticatedRemoteTripFinisher(
            FakeAuthRepository(),
            FakeTripRemoteDataSource(
                finishResult = TripMutationResult.NetworkUnavailable("network_unavailable")
            ),
            store
        )

        val result = finisher.finishTrip(FinishTripRequestDto())

        assertEquals(TripMutationResult.NetworkUnavailable("network_unavailable"), result)
        assertEquals("remote-trip-1", store.remoteTripId.value)
    }

    @Test fun finishAfterProcessRecoveryUsesTheRecoveredRemoteTripId() = runBlocking {
        val store = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("recovered-trip-7") }
        val remote = FakeTripRemoteDataSource(
            finishResult = TripMutationResult.Success("recovered-trip-7", "Finished")
        )
        val finisher = AuthenticatedRemoteTripFinisher(FakeAuthRepository(), remote, store)

        val result = finisher.finishTrip(FinishTripRequestDto())

        assertEquals(TripMutationResult.Success("recovered-trip-7", "Finished"), result)
        assertEquals("recovered-trip-7", remote.finishedRemoteTripId)
        assertEquals(null, store.remoteTripId.value)
    }

    @Test fun finishContinuesWithNoEndLocationWhenSharedProviderRejectsMockLocation() = runBlocking {
        val rejectedLocation = TripSignalManualSosLocationProvider(
            store = InMemoryTripSignalStore(),
            nowEpochMillis = { 15_000L },
            currentLocationProvider = CurrentManualSosLocationProvider {
                LocationSample(
                    latitude = 19.4350,
                    longitude = -99.1360,
                    accuracyMeters = 10f,
                    timestampMillis = 14_000L,
                    provider = "gps",
                    isMock = true
                )
            }
        ).currentRealLocation()
        val remote = FakeTripRemoteDataSource(
            finishResult = TripMutationResult.Success("remote-trip-1", "Finished")
        )
        val finisher = AuthenticatedRemoteTripFinisher(
            FakeAuthRepository(),
            remote,
            InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-trip-1") }
        )

        val result = finisher.finishTrip(
            FinishTripRequestDto(
                clientFinishedAtUtc = "2026-08-12T16:52:16Z",
                endLocation = rejectedLocation?.toTripLocationDto()
            )
        )

        assertEquals(TripMutationResult.Success("remote-trip-1", "Finished"), result)
        assertEquals("remote-trip-1", remote.finishedRemoteTripId)
        assertEquals("2026-08-12T16:52:16Z", remote.finishRequest?.clientFinishedAtUtc)
        assertEquals(null, remote.finishRequest?.endLocation)
    }

    private class FakeTripRemoteDataSource(
        private val startResult: TripMutationResult = TripMutationResult.InvalidResponse("unused"),
        private val finishResult: TripMutationResult = TripMutationResult.InvalidResponse("unused")
    ) : TripRemoteDataSource {
        var startCalls = 0
        var authorization: String? = null
        var startRequest: StartTripRequestDto? = null
        var finishRequest: FinishTripRequestDto? = null
        var finishedRemoteTripId: String? = null

        override suspend fun activeTrip(authorization: String): ActiveTripLookupResult = ActiveTripLookupResult.NoActiveTrip

        override suspend fun startTrip(authorization: String, request: StartTripRequestDto): TripMutationResult {
            startCalls++
            this.authorization = authorization
            startRequest = request
            return startResult
        }

        override suspend fun finishTrip(
            authorization: String,
            remoteTripId: String,
            request: FinishTripRequestDto
        ): TripMutationResult {
            this.authorization = authorization
            finishedRemoteTripId = remoteTripId
            finishRequest = request
            return finishResult
        }
    }

    private class FakeAuthRepository : AuthRepository {
        override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> = error("unused")
        override suspend fun restoreSession(): AuthResult<AuthUser?> = AuthResult.Success(null)
        override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> = AuthResult.Success(AccessToken("access-token"))
        override suspend fun refreshSession(): AuthResult<AuthUser> = error("unused")
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override fun observeSession(): StateFlow<SessionState> = MutableStateFlow(SessionState.LoggedOut)
    }
}
