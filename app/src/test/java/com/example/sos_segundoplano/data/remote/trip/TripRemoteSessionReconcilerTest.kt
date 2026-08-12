package com.example.sos_segundoplano.data.remote.trip

import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TripRemoteSessionReconcilerTest {
    @Test fun activeTripLookupStoresRemoteTripIdAndReconcilesItAgain() = runBlocking {
        val store = InMemoryRemoteTripSessionStore()
        val remote = FakeTripRemoteDataSource(ActiveTripLookupResult.Found("remote-trip-1"))
        val reconciler = TripRemoteSessionReconciler(FakeAuthRepository(), remote, store)

        assertEquals(ActiveTripLookupResult.Found("remote-trip-1"), reconciler.resolveActiveTrip())
        assertEquals("remote-trip-1", store.remoteTripId.value)
        assertEquals(ActiveTripLookupResult.Found("remote-trip-1"), reconciler.resolveActiveTrip())
        assertEquals(2, remote.calls)
    }

    @Test fun noActiveTripClearsRemoteTripIdWithoutInventingOne() = runBlocking {
        val store = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("stale-trip") }
        val reconciler = TripRemoteSessionReconciler(
            FakeAuthRepository(),
            FakeTripRemoteDataSource(ActiveTripLookupResult.NoActiveTrip),
            store
        )

        assertEquals(ActiveTripLookupResult.NoActiveTrip, reconciler.resolveActiveTrip())

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

    @Test fun remoteLookupFailurePreservesDurableTripForLaterReconciliation() = runBlocking {
        val store = InMemoryRemoteTripSessionStore().apply { setRemoteTripId("remote-trip-1") }
        val reconciler = TripRemoteSessionReconciler(
            FakeAuthRepository(),
            FakeTripRemoteDataSource(ActiveTripLookupResult.NetworkUnavailable("network_unavailable")),
            store
        )

        assertEquals(
            ActiveTripLookupResult.NetworkUnavailable("network_unavailable"),
            reconciler.resolveActiveTrip()
        )
        assertEquals("remote-trip-1", store.remoteTripId.value)
    }

    @Test fun activeTripLookupPersistsRemoteTripIdAcrossStoreRecreation() = runBlocking {
        val persistence = TestTripPersistence()
        val store = PersistentRemoteTripSessionStore(persistence, RemoteTripSessionClock { 1234L })
        val reconciler = TripRemoteSessionReconciler(
            FakeAuthRepository(),
            FakeTripRemoteDataSource(ActiveTripLookupResult.Found("remote-trip-1")),
            store
        )

        assertEquals(ActiveTripLookupResult.Found("remote-trip-1"), reconciler.resolveActiveTrip())

        val restored = PersistentRemoteTripSessionStore(persistence)
        assertEquals("remote-trip-1", restored.remoteTripId.value)
    }

    @Test fun unauthorizedLookupRefreshesOnceAndRetriesWithoutStartingTrip() = runBlocking {
        val store = InMemoryRemoteTripSessionStore()
        val remote = SequencedTripRemoteDataSource(
            listOf(
                ActiveTripLookupResult.HttpError(401, "unauthorized"),
                ActiveTripLookupResult.Found("remote-trip-1")
            )
        )
        val auth = RefreshingFakeAuthRepository()
        val reconciler = TripRemoteSessionReconciler(auth, remote, store)

        assertEquals(ActiveTripLookupResult.Found("remote-trip-1"), reconciler.resolveActiveTrip())
        assertEquals(listOf("Bearer access-token", "Bearer refreshed-token"), remote.authorizations)
        assertEquals(1, auth.refreshCalls)
        assertEquals("remote-trip-1", store.remoteTripId.value)
    }

    @Test fun activeTripIsNotExposedWhenDurablePersistenceFails() = runBlocking {
        val persistence = TestTripPersistence(saveSucceeds = false)
        val store = PersistentRemoteTripSessionStore(persistence)
        val reconciler = TripRemoteSessionReconciler(
            FakeAuthRepository(),
            FakeTripRemoteDataSource(ActiveTripLookupResult.Found("remote-trip-1")),
            store
        )

        assertEquals(
            ActiveTripLookupResult.InvalidResponse("remote_trip_persistence_failed"),
            reconciler.resolveActiveTrip()
        )
        assertEquals(null, store.remoteTripId.value)
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

    private class SequencedTripRemoteDataSource(
        results: List<ActiveTripLookupResult>
    ) : TripRemoteDataSource {
        private val remaining = ArrayDeque(results)
        val authorizations = mutableListOf<String>()
        override suspend fun activeTrip(authorization: String): ActiveTripLookupResult {
            authorizations += authorization
            return remaining.removeFirst()
        }
    }

    private class RefreshingFakeAuthRepository : AuthRepository {
        var refreshCalls = 0
        private var refreshed = false
        override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> = error("unused")
        override suspend fun restoreSession(): AuthResult<AuthUser?> = AuthResult.Success(null)
        override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> = AuthResult.Success(
            AccessToken(if (refreshed) "refreshed-token" else "access-token")
        )
        override suspend fun refreshSession(): AuthResult<AuthUser> {
            refreshCalls++
            refreshed = true
            return AuthResult.Success(AuthUser("rider-1", "rider@example.com", "Rider", "", UserRole.Rider, true))
        }
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override fun observeSession(): StateFlow<SessionState> = MutableStateFlow(SessionState.LoggedOut)
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

    private class TestTripPersistence(
        private val saveSucceeds: Boolean = true
    ) : RemoteTripSessionPersistence {
        var value: PersistedRemoteTripSession? = null

        override fun read(): PersistedRemoteTripSession? = value

        override fun save(session: PersistedRemoteTripSession): Boolean {
            if (saveSucceeds) value = session
            return saveSucceeds
        }

        override fun clear(): Boolean {
            value = null
            return true
        }
    }
}
