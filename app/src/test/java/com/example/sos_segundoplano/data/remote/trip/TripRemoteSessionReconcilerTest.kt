package com.example.sos_segundoplano.data.remote.trip

import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TripRemoteSessionReconcilerTest {
    @Test fun activeTripLookupStoresRemoteTripIdAndReusesIt() = runBlocking {
        val store = InMemoryRemoteTripSessionStore()
        val remote = FakeTripRemoteDataSource(ActiveTripLookupResult.Found("remote-trip-1"))
        val reconciler = TripRemoteSessionReconciler(FakeAuthRepository(), remote, store)

        assertEquals(ActiveTripLookupResult.Found("remote-trip-1"), reconciler.resolveActiveTrip())
        assertEquals("remote-trip-1", store.remoteTripId.value)
        assertEquals(ActiveTripLookupResult.Found("remote-trip-1"), reconciler.resolveActiveTrip())
        assertEquals(1, remote.calls)
    }

    @Test fun noActiveTripClearsRemoteTripIdWithoutInventingOne() = runBlocking {
        val store = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("stale-trip") }
        clearAndLookup(store, ActiveTripLookupResult.NoActiveTrip)

        assertEquals(null, store.remoteTripId.value)
    }

    @Test fun authNetworkFailureIsControlledAndDoesNotCallRemote() = runBlocking {
        val remote = FakeTripRemoteDataSource(ActiveTripLookupResult.Found("remote-trip-1"))
        val reconciler = TripRemoteSessionReconciler(
            FakeAuthRepository(NetworkUnavailable("network_unavailable")),
            remote,
            InMemoryRemoteTripSessionStore()
        )

        assertEquals(ActiveTripLookupResult.NetworkUnavailable("network_unavailable"), reconciler.resolveActiveTrip())
        assertEquals(0, remote.calls)
    }

    private suspend fun clearAndLookup(store: InMemoryRemoteTripSessionStore, result: ActiveTripLookupResult) {
        store.clearRemoteTripId()
        val reconciler = TripRemoteSessionReconciler(FakeAuthRepository(), FakeTripRemoteDataSource(result), store)
        assertEquals(result, reconciler.resolveActiveTrip())
    }

    private class FakeTripRemoteDataSource(
        private val result: ActiveTripLookupResult
    ) : TripRemoteDataSource {
        var calls = 0
        override suspend fun activeTrip(authorization: String): ActiveTripLookupResult {
            calls++
            assertEquals("Bearer access-token", authorization)
            return result
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
}
