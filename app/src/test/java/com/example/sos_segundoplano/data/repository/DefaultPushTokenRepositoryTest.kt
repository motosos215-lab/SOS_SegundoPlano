package com.example.sos_segundoplano.data.repository

import com.example.sos_segundoplano.data.remote.push.PushNotificationTokenDto
import com.example.sos_segundoplano.data.remote.push.PushTokenRemoteDataSource
import com.example.sos_segundoplano.data.remote.push.PushTokenRemoteResult
import com.example.sos_segundoplano.data.remote.push.RegisterPushTokenRequestDto
import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthSessionIdentity
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.push.PushTokenCoordinator
import com.example.sos_segundoplano.domain.push.PushTokenState
import com.example.sos_segundoplano.domain.push.PushTokenStore
import com.example.sos_segundoplano.domain.push.PushTokenStoreResult
import com.example.sos_segundoplano.domain.push.PushTokenSyncResult
import com.example.sos_segundoplano.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DefaultPushTokenRepositoryTest {
    @Test fun monitorRegistrationUsesConfirmedBodyAndPersistsNestedRegistrationId() = runBlocking {
        val remote = FakePushRemote()
        val store = FakePushStore(PushTokenState(FAKE_TOKEN, FAKE_TOKEN))

        val result = repository(FakeAuthRepository(UserRole.Monitor), store, remote)
            .syncPendingMonitorToken()

        assertEquals(PushTokenSyncResult.Registered, result)
        assertEquals("Bearer access-token", remote.authorization)
        assertEquals("Android", remote.request?.platform)
        assertEquals("Fcm", remote.request?.channel)
        assertEquals(FAKE_TOKEN, remote.request?.token)
        assertEquals("1.0-test", remote.request?.metadata?.appVersion)
        assertEquals("Android-test", remote.request?.metadata?.osVersion)
        val propertyNames = RegisterPushTokenRequestDto::class.java.declaredFields.map { it.name }
        assertFalse(propertyNames.contains("userId"))
        assertFalse(propertyNames.contains("deviceId"))
        assertEquals(FAKE_TOKEN, store.state.currentToken)
        assertNull(store.state.pendingToken)
        assertEquals(FIRST_REGISTRATION_ID, store.state.remoteRegistrationId)
        assertEquals(MONITOR_USER_ID, store.state.remoteRegistrationOwnerUserId)
        assertEquals(0, remote.revokedIds.size)
    }

    @Test fun repeatedPostForSameTokenKeepsBackendIdWithoutLocalDuplicateState() = runBlocking {
        val remote = FakePushRemote()
        val store = FakePushStore(PushTokenState(FAKE_TOKEN, FAKE_TOKEN))
        val repository = repository(FakeAuthRepository(UserRole.Monitor), store, remote)

        assertEquals(PushTokenSyncResult.Registered, repository.syncPendingMonitorToken())
        store.state = store.state.copy(pendingToken = FAKE_TOKEN)
        assertEquals(PushTokenSyncResult.Registered, repository.syncPendingMonitorToken())

        assertEquals(2, remote.registerCalls)
        assertEquals(FIRST_REGISTRATION_ID, store.state.remoteRegistrationId)
        assertNull(store.state.pendingToken)
    }

    @Test fun rotatedTokenReplacesStaleRegistrationIdAfterAuthenticatedPost() = runBlocking {
        val store = FakePushStore(PushTokenState(FAKE_TOKEN, null, FIRST_REGISTRATION_ID))
        PushTokenCoordinator(store).recordToken(ROTATED_FAKE_TOKEN)
        val remote = FakePushRemote(registerResult = registered(SECOND_REGISTRATION_ID))

        val result = repository(FakeAuthRepository(UserRole.Monitor), store, remote)
            .syncPendingMonitorToken()

        assertEquals(PushTokenSyncResult.Registered, result)
        assertEquals(ROTATED_FAKE_TOKEN, remote.request?.token)
        assertEquals(ROTATED_FAKE_TOKEN, store.state.currentToken)
        assertNull(store.state.pendingToken)
        assertEquals(SECOND_REGISTRATION_ID, store.state.remoteRegistrationId)
        assertEquals(MONITOR_USER_ID, store.state.remoteRegistrationOwnerUserId)
        assertEquals(0, remote.revokedIds.size)
    }

    @Test fun riderRegistersFcmSoMonitorFeedbackCanReturnToRider() = runBlocking {
        val remote = FakePushRemote()
        val result = repository(
            FakeAuthRepository(UserRole.Rider),
            FakePushStore(PushTokenState(FAKE_TOKEN, FAKE_TOKEN)),
            remote
        ).syncPendingMonitorToken()

        assertEquals(PushTokenSyncResult.Registered, result)
        assertEquals(1, remote.registerCalls)
    }

    @Test fun registrationFailureKeepsPendingTokenAndDoesNotExposeItInResult() = runBlocking {
        val store = FakePushStore(PushTokenState(FAKE_TOKEN, FAKE_TOKEN))
        val remote = FakePushRemote(registerResult = PushTokenRemoteResult.NetworkFailure)

        val result = repository(FakeAuthRepository(UserRole.Monitor), store, remote)
            .syncPendingMonitorToken()

        assertEquals(PushTokenSyncResult.RemoteFailure(), result)
        assertEquals(FAKE_TOKEN, store.state.pendingToken)
        assertFalse(result.toString().contains(FAKE_TOKEN))
        assertFalse(remote.request.toString().contains(FAKE_TOKEN))
    }

    @Test fun unauthorizedRegistrationRefreshesOnceAndRetriesWithNewBearer() = runBlocking {
        val auth = FakeAuthRepository(UserRole.Monitor)
        val remote = FakePushRemote(
            registrationResults = ArrayDeque(
                listOf(PushTokenRemoteResult.HttpFailure(401), registered(FIRST_REGISTRATION_ID))
            )
        )

        val result = repository(auth, FakePushStore(PushTokenState(FAKE_TOKEN, FAKE_TOKEN)), remote)
            .syncPendingMonitorToken()

        assertEquals(PushTokenSyncResult.Registered, result)
        assertEquals(listOf("Bearer access-token", "Bearer refreshed-access-token"), remote.authorizations)
        assertEquals(1, auth.refreshCalls)
        assertEquals(0, remote.revokedIds.size)
    }

    @Test fun concurrentReconciliationsAreSingleFlightAndNeverRevoke() = runBlocking {
        val remote = FakePushRemote()
        val store = FakePushStore(PushTokenState(FAKE_TOKEN, FAKE_TOKEN))
        val repository = repository(FakeAuthRepository(UserRole.Monitor), store, remote)

        val results = coroutineScope {
            listOf(
                async { repository.syncPendingMonitorToken() },
                async { repository.syncPendingMonitorToken() }
            ).awaitAll()
        }

        assertTrue(results.contains(PushTokenSyncResult.Registered))
        assertTrue(results.contains(PushTokenSyncResult.NothingPending))
        assertEquals(1, remote.registerCalls)
        assertTrue(remote.revokedIds.isEmpty())
    }

    @Test fun recreationRebindsCurrentFcmTokenToCurrentBackendSessionWithoutRevoking() = runBlocking {
        val remote = FakePushRemote()
        val store = FakePushStore(
            PushTokenState(FAKE_TOKEN, null, FIRST_REGISTRATION_ID, MONITOR_USER_ID)
        )
        val auth = FakeAuthRepository(UserRole.Monitor)
        val firstRepository = repository(auth, store, remote)
        val recreatedRepository = repository(auth, store, remote)

        assertEquals(PushTokenSyncResult.Registered, firstRepository.syncPendingMonitorToken())
        assertTrue(auth.refreshSession() is AuthResult.Success)
        assertEquals(PushTokenSyncResult.Registered, recreatedRepository.syncPendingMonitorToken())
        assertEquals(2, remote.registerCalls)
        assertEquals(0, remote.revokedIds.size)
        assertEquals(FIRST_REGISTRATION_ID, store.state.remoteRegistrationId)
    }

    @Test fun previousSessionCleanupCannotRevokeCurrentMonitorRegistration() = runBlocking {
        val remote = FakePushRemote()
        val store = FakePushStore(
            PushTokenState(FAKE_TOKEN, null, SECOND_REGISTRATION_ID, MONITOR_USER_ID)
        )
        val repository = repository(FakeAuthRepository(UserRole.Monitor), store, remote)

        val staleCleanup = repository.revokeMonitorRegistration(
            AuthSessionIdentity(MONITOR_USER_ID, generation = 99L)
        )

        assertEquals(PushTokenSyncResult.NoMonitorSession, staleCleanup)
        assertEquals(0, remote.revokedIds.size)
        assertEquals(SECOND_REGISTRATION_ID, store.state.remoteRegistrationId)
        assertEquals(MONITOR_USER_ID, store.state.remoteRegistrationOwnerUserId)
    }

    @Test fun logoutDoesNotRegisterOrRevokeWhenRegistrationIsOwnedByAnotherUser() = runBlocking {
        val remote = FakePushRemote()
        val store = FakePushStore(
            PushTokenState(FAKE_TOKEN, null, FIRST_REGISTRATION_ID, "previous-monitor-fixture")
        )
        val repository = repository(FakeAuthRepository(UserRole.Monitor), store, remote)

        assertEquals(PushTokenSyncResult.NothingPending, repository.revokeMonitorRegistration(monitorSession()))

        assertEquals(0, remote.registerCalls)
        assertTrue(remote.revokedIds.isEmpty())
        assertFalse(remote.revokedIds.contains(FIRST_REGISTRATION_ID))
    }

    @Test fun logoutDoesNotRegisterOrRevokeLegacyRegistrationWithoutOwner() = runBlocking {
        val remote = FakePushRemote()
        val store = FakePushStore(
            PushTokenState(FAKE_TOKEN, null, FIRST_REGISTRATION_ID, remoteRegistrationOwnerUserId = null)
        )
        val repository = repository(FakeAuthRepository(UserRole.Monitor), store, remote)

        assertEquals(
            PushTokenSyncResult.NothingPending,
            repository.revokeMonitorRegistration(monitorSession())
        )

        assertEquals(0, remote.registerCalls)
        assertTrue(remote.revokedIds.isEmpty())
        assertFalse(remote.revokedIds.contains(FIRST_REGISTRATION_ID))
    }

    @Test fun explicitLogoutThenLaterLoginCanRegisterActiveTokenAgain() = runBlocking {
        val remote = FakePushRemote(registerResult = registered(SECOND_REGISTRATION_ID))
        val store = FakePushStore(
            PushTokenState(FAKE_TOKEN, null, FIRST_REGISTRATION_ID, MONITOR_USER_ID)
        )
        val repository = repository(FakeAuthRepository(UserRole.Monitor), store, remote)

        assertEquals(PushTokenSyncResult.Revoked, repository.revokeMonitorRegistration(monitorSession()))
        assertEquals(PushTokenSyncResult.Registered, repository.syncPendingMonitorToken())

        assertEquals(listOf(FIRST_REGISTRATION_ID), remote.revokedIds)
        assertEquals(SECOND_REGISTRATION_ID, store.state.remoteRegistrationId)
        assertEquals(MONITOR_USER_ID, store.state.remoteRegistrationOwnerUserId)
        assertNull(store.state.pendingToken)
    }

    @Test fun revokeUsesKnownIdAndOnlyClearsItAfterConfirmedRevokedResponse() = runBlocking {
        val successStore = FakePushStore(PushTokenState(FAKE_TOKEN, null, FIRST_REGISTRATION_ID, MONITOR_USER_ID))
        val successRemote = FakePushRemote()
        val success = repository(FakeAuthRepository(UserRole.Monitor), successStore, successRemote)
            .revokeMonitorRegistration(monitorSession())

        assertEquals(PushTokenSyncResult.Revoked, success)
        assertEquals(FIRST_REGISTRATION_ID, successRemote.revokedIds.single())
        assertEquals(0, successRemote.registerCalls)
        assertNull(successStore.state.remoteRegistrationId)
        assertEquals(FAKE_TOKEN, successStore.state.pendingToken)

        val failedStore = FakePushStore(PushTokenState(FAKE_TOKEN, null, FIRST_REGISTRATION_ID, MONITOR_USER_ID))
        val failure = repository(
            FakeAuthRepository(UserRole.Monitor),
            failedStore,
            FakePushRemote(revokeResult = PushTokenRemoteResult.NetworkFailure)
        ).revokeMonitorRegistration(monitorSession())

        assertTrue(failure is PushTokenSyncResult.RemoteFailure)
        assertEquals(FIRST_REGISTRATION_ID, failedStore.state.remoteRegistrationId)
    }

    @Test fun missingLocalIdDoesNotRegisterOrRevokeAndKeepsPendingTokenForLaterSync() = runBlocking {
        val remote = FakePushRemote()
        val store = FakePushStore(PushTokenState(FAKE_TOKEN, FAKE_TOKEN, null))
        val repository = repository(FakeAuthRepository(UserRole.Monitor), store, remote)

        val result = repository.revokeMonitorRegistration(monitorSession())

        assertEquals(PushTokenSyncResult.NothingPending, result)
        assertEquals(0, remote.registerCalls)
        assertTrue(remote.revokedIds.isEmpty())
        assertEquals(0, remote.listCalls)
        assertEquals(0, remote.statusCalls)
        assertNull(store.state.remoteRegistrationId)
        assertEquals(FAKE_TOKEN, store.state.pendingToken)

        assertEquals(PushTokenSyncResult.Registered, repository.syncPendingMonitorToken())
        assertEquals(1, remote.registerCalls)
        assertEquals(FIRST_REGISTRATION_ID, store.state.remoteRegistrationId)
    }

    @Test fun revokeIsSafeForAlreadyRevokedAndNotFoundRegistrations() = runBlocking {
        val idempotentRemote = FakePushRemote()
        val idempotentStore = FakePushStore(PushTokenState(FAKE_TOKEN, null, FIRST_REGISTRATION_ID, MONITOR_USER_ID))
        val repository = repository(FakeAuthRepository(UserRole.Monitor), idempotentStore, idempotentRemote)

        assertEquals(PushTokenSyncResult.Revoked, repository.revokeMonitorRegistration(monitorSession()))
        assertEquals(PushTokenSyncResult.NothingPending, repository.revokeMonitorRegistration(monitorSession()))
        assertEquals(1, idempotentRemote.revokedIds.size)

        val notFoundStore = FakePushStore(PushTokenState(FAKE_TOKEN, null, FIRST_REGISTRATION_ID, MONITOR_USER_ID))
        val notFound = repository(
            FakeAuthRepository(UserRole.Monitor),
            notFoundStore,
            FakePushRemote(revokeResult = PushTokenRemoteResult.NotFound("push_notification_token_not_available"))
        ).revokeMonitorRegistration(monitorSession())

        assertEquals(PushTokenSyncResult.NotFound, notFound)
        assertNull(notFoundStore.state.remoteRegistrationId)
        assertEquals(FAKE_TOKEN, notFoundStore.state.pendingToken)
    }

    private fun repository(
        auth: FakeAuthRepository,
        store: FakePushStore,
        remote: FakePushRemote
    ) = DefaultPushTokenRepository(
        authRepository = auth,
        store = store,
        remoteDataSource = remote,
        metadataProvider = PushAppMetadataProvider { PushAppMetadata("1.0-test", "Android-test") }
    )

    private companion object {
        const val FAKE_TOKEN = "fake-monitor-fcm-token-not-real"
        const val ROTATED_FAKE_TOKEN = "fake-monitor-fcm-token-v2-not-real"
        const val FIRST_REGISTRATION_ID = "registration-test-id"
        const val SECOND_REGISTRATION_ID = "registration-test-id-v2"
        const val MONITOR_USER_ID = "monitor-user-fixture"
    }
}

private fun monitorSession(): AuthSessionIdentity = AuthSessionIdentity(
    userId = "monitor-user-fixture",
    generation = 0L
)

private fun registered(id: String): PushTokenRemoteResult.Registered =
    PushTokenRemoteResult.Registered(tokenDto(id, "Active"))

private fun tokenDto(id: String, status: String): PushNotificationTokenDto = PushNotificationTokenDto(
    id = id,
    platform = "Android",
    channel = "Fcm",
    deviceId = null,
    tokenPreview = "abcdef****wxyz",
    status = status,
    registeredAtUtc = "2026-08-11T00:00:00Z",
    lastSeenAtUtc = "2026-08-11T00:30:00Z",
    revokedAtUtc = if (status == "Revoked") "2026-08-11T01:00:00Z" else null
)

private class FakePushRemote(
    private val registerResult: PushTokenRemoteResult = registered("registration-test-id"),
    private val revokeResult: PushTokenRemoteResult = PushTokenRemoteResult.Revoked(
        tokenDto("registration-test-id", "Revoked")
    ),
    private val registrationResults: ArrayDeque<PushTokenRemoteResult> = ArrayDeque()
) : PushTokenRemoteDataSource {
    var authorization: String? = null
    val authorizations = mutableListOf<String>()
    var request: RegisterPushTokenRequestDto? = null
    val revokedIds = mutableListOf<String>()
    var registerCalls = 0
    var listCalls = 0
    var statusCalls = 0

    override suspend fun register(
        authorization: String,
        request: RegisterPushTokenRequestDto
    ): PushTokenRemoteResult {
        registerCalls++
        this.authorization = authorization
        authorizations += authorization
        this.request = request
        return if (registrationResults.isEmpty()) registerResult else registrationResults.removeFirst()
    }

    override suspend fun listActiveAndroidFcm(authorization: String): PushTokenRemoteResult {
        listCalls++
        return PushTokenRemoteResult.NetworkFailure
    }

    override suspend fun status(authorization: String): PushTokenRemoteResult {
        statusCalls++
        return PushTokenRemoteResult.NetworkFailure
    }

    override suspend fun revoke(authorization: String, registrationId: String): PushTokenRemoteResult {
        this.authorization = authorization
        revokedIds += registrationId
        return when (revokeResult) {
            is PushTokenRemoteResult.Revoked -> PushTokenRemoteResult.Revoked(
                revokeResult.token.copy(id = registrationId)
            )
            else -> revokeResult
        }
    }
}

private class FakePushStore(initial: PushTokenState) : PushTokenStore {
    var state = initial
    override fun read(): PushTokenStoreResult<PushTokenState> = PushTokenStoreResult.Success(state)
    override fun save(state: PushTokenState): PushTokenStoreResult<Unit> {
        this.state = state
        return PushTokenStoreResult.Success(Unit)
    }
}

private class FakeAuthRepository(role: UserRole) : AuthRepository {
    private val user = AuthUser("monitor-user-fixture", "monitor@example.com", "Monitor Test", "+520000000000", role, true)
    private val state = MutableStateFlow<SessionState>(SessionState.Authenticated(user, Instant.MAX, true))
    private var accessToken = "access-token"
    var refreshCalls = 0

    override suspend fun login(email: String, password: String, rememberMe: Boolean) = AuthResult.Success(user)
    override suspend fun restoreSession() = AuthResult.Success<AuthUser?>(user)
    override suspend fun ensureValidAccessToken() = AuthResult.Success(AccessToken(accessToken))
    override suspend fun refreshSession(): AuthResult<AuthUser> {
        refreshCalls++
        accessToken = "refreshed-access-token"
        return AuthResult.Success(user)
    }
    override suspend fun logout() = AuthResult.Success(Unit)
    override fun observeSession(): StateFlow<SessionState> = state
}
