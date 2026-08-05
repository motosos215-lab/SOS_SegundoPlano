package com.example.sos_segundoplano.data.repository

import com.example.sos_segundoplano.data.remote.auth.AuthRemoteDataSource
import com.example.sos_segundoplano.data.remote.auth.AuthUserDto
import com.example.sos_segundoplano.data.remote.auth.LoginDataDto
import com.example.sos_segundoplano.data.remote.auth.LoginRequestDto
import com.example.sos_segundoplano.data.remote.auth.LogoutRequestDto
import com.example.sos_segundoplano.data.remote.auth.RefreshDataDto
import com.example.sos_segundoplano.data.remote.auth.RefreshTokenRequestDto
import com.example.sos_segundoplano.domain.auth.AccessDenied
import com.example.sos_segundoplano.domain.auth.AuthClock
import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthSession
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.InactiveAccount
import com.example.sos_segundoplano.domain.auth.InvalidCredentials
import com.example.sos_segundoplano.domain.auth.InvalidResponse
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.RemoteLogoutFailed
import com.example.sos_segundoplano.domain.auth.SessionExpired
import com.example.sos_segundoplano.domain.auth.SessionSecrets
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.SessionStorageFailureReason
import com.example.sos_segundoplano.domain.auth.SessionStore
import com.example.sos_segundoplano.domain.auth.SessionStoreResult
import com.example.sos_segundoplano.domain.auth.StorageFailure
import com.example.sos_segundoplano.domain.auth.Unauthorized
import com.example.sos_segundoplano.domain.auth.UserRole
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class DefaultAuthRepositoryTest {
    private val now = Instant.parse("2026-08-04T10:00:00Z")
    private val clock = MutableAuthClock(now)

    @Test fun riderLoginTrimsOnlyEmailKeepsPasswordAndPublishesNoTokens() = runBlocking {
        val remote = FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(300))))
        val store = FakeSessionStore()
        val repository = repository(remote, store)
        val password = "  StrongPass1!  "

        val result = repository.login("  rider@example.com  ", password, rememberMe = false)

        assertTrue(result is AuthResult.Success)
        assertEquals("rider@example.com", remote.lastLoginRequest?.email)
        assertEquals(password, remote.lastLoginRequest?.password)
        assertEquals(0, store.saveCalls)
        assertTrue(store.clearCalls > 0)
        val stateText = repository.observeSession().value.toString()
        assertTrue(repository.observeSession().value is SessionState.Authenticated)
        assertFalse(stateText.contains("access-token"))
        assertFalse(stateText.contains("refresh-token"))
        assertFalse(result.toString().contains(password))
    }

    @Test fun rememberMeTruePersistsAndFalseDoesNotPersist() = runBlocking {
        val persistentStore = FakeSessionStore()
        val persistentRepository = repository(
            FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(300)))),
            persistentStore
        )
        assertTrue(persistentRepository.login("rider@example.com", "password", true) is AuthResult.Success)
        assertEquals(1, persistentStore.saveCalls)
        assertTrue(requireNotNull(persistentStore.stored).rememberMe)

        val memoryStore = FakeSessionStore()
        val memoryRepository = repository(
            FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(300)))),
            memoryStore
        )
        assertTrue(memoryRepository.login("rider@example.com", "password", false) is AuthResult.Success)
        assertEquals(0, memoryStore.saveCalls)
        assertNull(memoryStore.stored)
    }

    @Test fun invalidCredentialsAndInvalidPayloadsDoNotCreateSession() = runBlocking {
        val invalidCredentials = repository(
            FakeAuthRemoteDataSource(InvalidCredentials(401, "invalid_credentials", "invalid")),
            FakeSessionStore()
        )
        assertTrue(invalidCredentials.login("rider@example.com", "password", false) is InvalidCredentials)
        assertTrue(invalidCredentials.observeSession().value is SessionState.LoggedOut)

        val cases = listOf(
            validLoginData(now.plusSeconds(300)).copy(accessToken = ""),
            validLoginData(now.plusSeconds(300)).copy(refreshToken = ""),
            validLoginData(now.plusSeconds(300)).copy(accessTokenExpiresAtUtc = "not-a-date"),
            validLoginData(now.minusSeconds(1))
        )
        cases.forEach { data ->
            val store = FakeSessionStore()
            val repo = repository(FakeAuthRemoteDataSource(AuthResult.Success(data)), store)
            assertTrue(repo.login("rider@example.com", "password", true) is InvalidResponse)
            assertEquals(0, store.saveCalls)
        }
    }

    @Test fun offsetAndFractionalExpirationIsAccepted() = runBlocking {
        val data = validLoginData(now.plusSeconds(300)).copy(
            accessTokenExpiresAtUtc = "2026-08-04T12:00:00.123456+02:00"
        )
        val result = repository(
            FakeAuthRemoteDataSource(AuthResult.Success(data)),
            FakeSessionStore()
        ).login("rider@example.com", "password", false)
        assertTrue(result is AuthResult.Success)
    }

    @Test fun monitorUnknownInactiveAndIncompleteUsersAreRejectedWithoutSaving() = runBlocking {
        val cases = listOf(
            RejectedLoginCase(
                "monitor",
                validLoginData(now.plusSeconds(300)).copy(user = validUser().copy(role = "Monitor")),
                AccessDenied(UserRole.Monitor)
            ),
            RejectedLoginCase(
                "unknown",
                validLoginData(now.plusSeconds(300)).copy(user = validUser().copy(role = "Administrator")),
                AccessDenied(UserRole.Unknown)
            ),
            RejectedLoginCase(
                "empty-role",
                validLoginData(now.plusSeconds(300)).copy(user = validUser().copy(role = "")),
                AccessDenied(UserRole.Unknown)
            ),
            RejectedLoginCase(
                "padded-rider",
                validLoginData(now.plusSeconds(300)).copy(user = validUser().copy(role = " Rider ")),
                AccessDenied(UserRole.Unknown)
            ),
            RejectedLoginCase(
                "inactive",
                validLoginData(now.plusSeconds(300)).copy(user = validUser().copy(isActive = false)),
                InactiveAccount
            ),
            RejectedLoginCase(
                "empty-id",
                validLoginData(now.plusSeconds(300)).copy(user = validUser().copy(id = "")),
                InvalidResponse(sanitizedMessage = "auth_user_invalid")
            ),
            RejectedLoginCase(
                "invalid-email",
                validLoginData(now.plusSeconds(300)).copy(user = validUser().copy(email = "invalid")),
                InvalidResponse(sanitizedMessage = "auth_user_invalid")
            )
        )
        cases.forEach { case ->
            val store = FakeSessionStore(stored = validSession(now.plusSeconds(300)))
            val repo = repository(FakeAuthRemoteDataSource(AuthResult.Success(case.data)), store)
            val result = repo.login("rider@example.com", "password", true)
            assertEquals("${case.label}: result type", case.expected::class, result::class)
            assertEquals("${case.label}: save calls", 0, store.saveCalls)
            assertTrue("${case.label}: clear calls", store.clearCalls > 0)
            assertNull("${case.label}: persisted session", store.stored)
            assertFalse(
                "${case.label}: public state must not be authenticated",
                repo.observeSession().value is SessionState.Authenticated
            )
        }
    }

    @Test fun validTokenDoesNotRefreshAndNearExpiryRefreshesBothTokens() = runBlocking {
        val remote = FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(300))))
        val store = FakeSessionStore()
        val repo = repository(remote, store)
        repo.login("rider@example.com", "password", true)

        assertTrue(repo.ensureValidAccessToken() is AuthResult.Success)
        assertEquals(0, remote.refreshCalls.get())

        clock.current = now.plusSeconds(250)
        remote.refreshResult = AuthResult.Success(
            RefreshDataDto("new-access-token", "new-refresh-token", now.plusSeconds(900).toString())
        )
        val refreshed = repo.ensureValidAccessToken()

        assertTrue(refreshed is AuthResult.Success)
        assertEquals("new-access-token", (refreshed as AuthResult.Success).value.reveal())
        assertEquals(1, remote.refreshCalls.get())
        assertEquals("new-access-token", requireNotNull(store.stored).secrets.accessToken())
        assertEquals("new-refresh-token", requireNotNull(store.stored).secrets.refreshToken())
        assertEquals(validUserDomain(), requireNotNull(store.stored).user)
        assertTrue(requireNotNull(store.stored).rememberMe)
    }

    @Test fun twoConcurrentEnsureCallsShareOneRefresh() = runBlocking {
        val remote = FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(30))))
        remote.refreshBlock = {
            delay(100)
            AuthResult.Success(RefreshDataDto("new-access", "new-refresh", now.plusSeconds(600).toString()))
        }
        val repo = repository(remote, FakeSessionStore())
        repo.login("rider@example.com", "password", false)

        val results = coroutineScope {
            listOf(async { repo.ensureValidAccessToken() }, async { repo.ensureValidAccessToken() }).awaitAll()
        }

        assertTrue(results.all { it is AuthResult.Success })
        assertEquals(1, remote.refreshCalls.get())
    }

    @Test fun refreshUnauthorizedClearsSessionAndPublishesExpired() = runBlocking {
        val remote = FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(30))))
        val store = FakeSessionStore()
        val repo = repository(remote, store)
        repo.login("rider@example.com", "password", true)
        remote.refreshResult = Unauthorized(401, "unauthorized", "invalid")

        assertEquals(SessionExpired, repo.refreshSession())
        assertTrue(repo.observeSession().value is SessionState.Expired)
        assertNull(store.stored)
        assertEquals(SessionExpired, repo.ensureValidAccessToken())
    }

    @Test fun logoutDuringRefreshCannotRestoreTheOldSession() = runBlocking {
        val remote = FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(30))))
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        remote.refreshBlock = {
            refreshStarted.complete(Unit)
            releaseRefresh.await()
            AuthResult.Success(RefreshDataDto("late-access", "late-refresh", now.plusSeconds(600).toString()))
        }
        val store = FakeSessionStore()
        val repo = repository(remote, store)
        repo.login("rider@example.com", "password", true)

        val refresh = async { repo.refreshSession() }
        refreshStarted.await()
        assertTrue(repo.logout() is AuthResult.Success)
        releaseRefresh.complete(Unit)

        assertEquals(SessionExpired, refresh.await())
        assertTrue(repo.observeSession().value is SessionState.LoggedOut)
        assertNull(store.stored)
    }

    @Test fun logoutInvalidatesAnEarlierLoginStillInFlight() = runBlocking {
        val remote = FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(300))))
        val loginStarted = CompletableDeferred<Unit>()
        val releaseLogin = CompletableDeferred<Unit>()
        remote.loginBlock = {
            loginStarted.complete(Unit)
            releaseLogin.await()
            AuthResult.Success(validLoginData(now.plusSeconds(300)))
        }
        val store = FakeSessionStore()
        val repo = repository(remote, store)

        val login = async { repo.login("rider@example.com", "password", true) }
        loginStarted.await()
        assertTrue(repo.logout() is AuthResult.Success)
        releaseLogin.complete(Unit)

        assertEquals(SessionExpired, login.await())
        assertTrue(repo.observeSession().value is SessionState.LoggedOut)
        assertNull(store.stored)
    }

    @Test fun invalidRefreshResponseExpiresSession() = runBlocking {
        val remote = FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(30))))
        val store = FakeSessionStore()
        val repo = repository(remote, store)
        repo.login("rider@example.com", "password", true)
        remote.refreshResult = AuthResult.Success(RefreshDataDto("", "new-refresh", now.plusSeconds(600).toString()))

        assertTrue(repo.refreshSession() is InvalidResponse)
        assertTrue(repo.observeSession().value is SessionState.Expired)
        assertNull(store.stored)
    }

    @Test fun networkFailureDuringRefreshKeepsPersistedSession() = runBlocking {
        val remote = FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(30))))
        val store = FakeSessionStore()
        val repo = repository(remote, store)
        repo.login("rider@example.com", "password", true)
        val original = store.stored
        remote.refreshResult = NetworkUnavailable("network_unavailable")

        assertTrue(repo.refreshSession() is NetworkUnavailable)
        assertTrue(repo.observeSession().value is SessionState.Authenticated)
        assertEquals(original, store.stored)
    }

    @Test fun persistenceFailureAfterRefreshDropsMemoryAndStoredSession() = runBlocking {
        val remote = FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(30))))
        val store = FakeSessionStore()
        val repo = repository(remote, store)
        repo.login("rider@example.com", "password", true)
        remote.refreshResult = AuthResult.Success(
            RefreshDataDto("rotated-access", "rotated-refresh", now.plusSeconds(600).toString())
        )
        store.saveResult = SessionStoreResult.Failure(SessionStorageFailureReason.Unavailable)

        assertTrue(repo.refreshSession() is StorageFailure)
        assertTrue(repo.observeSession().value is SessionState.StorageUnavailable)
        assertNull(store.stored)
        assertEquals(SessionExpired, repo.ensureValidAccessToken())
    }

    @Test fun logout204AndRemoteFailureAlwaysClearLocalSession() = runBlocking {
        val successRemote = FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(300))))
        val successStore = FakeSessionStore()
        val successRepo = repository(successRemote, successStore)
        successRepo.login("rider@example.com", "password", true)
        assertTrue(successRepo.logout() is AuthResult.Success)
        assertNull(successStore.stored)
        assertTrue(successRepo.observeSession().value is SessionState.LoggedOut)

        val failedRemote = FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(300))))
        val failedStore = FakeSessionStore()
        val failedRepo = repository(failedRemote, failedStore)
        failedRepo.login("rider@example.com", "password", true)
        failedRemote.logoutResult = NetworkUnavailable("network_unavailable")
        assertTrue(failedRepo.logout() is RemoteLogoutFailed)
        assertNull(failedStore.stored)
        assertTrue(failedRepo.observeSession().value is SessionState.LoggedOut)
    }

    @Test fun persistentSessionRestoresAndMemoryOnlySessionDoesNot() = runBlocking {
        val sharedStore = FakeSessionStore()
        val first = repository(
            FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(300)))),
            sharedStore
        )
        first.login("rider@example.com", "password", true)
        val restored = repository(FakeAuthRemoteDataSource(InvalidResponse()), sharedStore)
        assertTrue(restored.restoreSession() is AuthResult.Success)
        assertTrue(restored.observeSession().value is SessionState.Authenticated)

        val memoryStore = FakeSessionStore()
        val memoryOnly = repository(
            FakeAuthRemoteDataSource(AuthResult.Success(validLoginData(now.plusSeconds(300)))),
            memoryStore
        )
        memoryOnly.login("rider@example.com", "password", false)
        val restarted = repository(FakeAuthRemoteDataSource(InvalidResponse()), memoryStore)
        val result = restarted.restoreSession()
        assertEquals(AuthResult.Success(null), result)
        assertTrue(restarted.observeSession().value is SessionState.LoggedOut)
    }

    @Test fun restoreNearExpiryKeepsPersistentSessionWhenNetworkIsUnavailable() = runBlocking {
        val stored = validSession(now.plusSeconds(30))
        val store = FakeSessionStore(stored)
        val remote = FakeAuthRemoteDataSource(InvalidResponse())
        remote.refreshResult = NetworkUnavailable("network_unavailable")
        val repo = repository(remote, store)

        assertTrue(repo.restoreSession() is NetworkUnavailable)
        assertEquals(stored, store.stored)
        assertTrue(repo.observeSession().value is SessionState.Authenticated)
    }

    @Test fun restoreRejectsStoredMonitorAndInactiveRider() = runBlocking {
        val monitorStore = FakeSessionStore(
            validSession(now.plusSeconds(300), validUserDomain().copy(role = UserRole.Monitor))
        )
        val monitorRepo = repository(FakeAuthRemoteDataSource(InvalidResponse()), monitorStore)
        assertEquals(AccessDenied(UserRole.Monitor), monitorRepo.restoreSession())
        assertNull(monitorStore.stored)

        val inactiveStore = FakeSessionStore(
            validSession(now.plusSeconds(300), validUserDomain().copy(isActive = false))
        )
        val inactiveRepo = repository(FakeAuthRemoteDataSource(InvalidResponse()), inactiveStore)
        assertEquals(InactiveAccount, inactiveRepo.restoreSession())
        assertNull(inactiveStore.stored)
    }

    @Test fun corruptStorageReturnsStorageFailureAndPublishesUnavailable() = runBlocking {
        val store = FakeSessionStore()
        store.readResult = SessionStoreResult.Failure(SessionStorageFailureReason.Tampered)
        val repo = repository(FakeAuthRemoteDataSource(InvalidResponse()), store)

        assertEquals(StorageFailure(SessionStorageFailureReason.Tampered), repo.restoreSession())
        assertTrue(repo.observeSession().value is SessionState.StorageUnavailable)
    }

    @Test fun secretModelsNeverExposeTokenValuesInToString() {
        val session = validSession(now.plusSeconds(300))
        assertFalse(session.toString().contains("access-token"))
        assertFalse(session.toString().contains("refresh-token"))
        assertFalse(session.secrets.toString().contains("access-token"))
        assertFalse(session.secrets.toString().contains("refresh-token"))
    }

    private fun repository(remote: FakeAuthRemoteDataSource, store: FakeSessionStore) = DefaultAuthRepository(
        remoteDataSource = remote,
        sessionStore = store,
        clock = clock,
        expirationMargin = Duration.ofSeconds(60)
    )
}

private class MutableAuthClock(var current: Instant) : AuthClock {
    override fun now(): Instant = current
}

private data class RejectedLoginCase(
    val label: String,
    val data: LoginDataDto,
    val expected: AuthFailure
)

private class FakeAuthRemoteDataSource(
    var loginResult: AuthResult<LoginDataDto>
) : AuthRemoteDataSource {
    var lastLoginRequest: LoginRequestDto? = null
    var loginBlock: (suspend () -> AuthResult<LoginDataDto>)? = null
    var refreshResult: AuthResult<RefreshDataDto> = InvalidResponse()
    var logoutResult: AuthResult<Unit> = AuthResult.Success(Unit)
    var refreshBlock: (suspend () -> AuthResult<RefreshDataDto>)? = null
    val refreshCalls = AtomicInteger(0)

    override suspend fun login(request: LoginRequestDto): AuthResult<LoginDataDto> {
        lastLoginRequest = request
        return loginBlock?.invoke() ?: loginResult
    }

    override suspend fun refresh(request: RefreshTokenRequestDto): AuthResult<RefreshDataDto> {
        refreshCalls.incrementAndGet()
        return refreshBlock?.invoke() ?: refreshResult
    }

    override suspend fun logout(request: LogoutRequestDto): AuthResult<Unit> = logoutResult
}

private class FakeSessionStore(
    var stored: AuthSession? = null
) : SessionStore {
    var saveCalls = 0
    var clearCalls = 0
    var saveResult: SessionStoreResult<Unit> = SessionStoreResult.Success(Unit)
    var readResult: SessionStoreResult<AuthSession>? = null

    override suspend fun save(session: AuthSession): SessionStoreResult<Unit> {
        saveCalls++
        if (saveResult is SessionStoreResult.Success) stored = session
        return saveResult
    }

    override suspend fun read(): SessionStoreResult<AuthSession> = readResult
        ?: stored?.let { SessionStoreResult.Success(it) }
        ?: SessionStoreResult.Empty

    override suspend fun clear(): SessionStoreResult<Unit> {
        clearCalls++
        stored = null
        return SessionStoreResult.Success(Unit)
    }
}

private fun validLoginData(expiration: Instant): LoginDataDto = LoginDataDto(
    accessToken = "access-token",
    refreshToken = "refresh-token",
    accessTokenExpiresAtUtc = expiration.toString(),
    user = validUser()
)

private fun validUser(): AuthUserDto = AuthUserDto(
    id = "rider-id",
    email = "rider@example.com",
    fullName = "Moto Rider",
    phoneNumber = "+52 555 555 5555",
    role = "Rider",
    isActive = true
)

private fun validUserDomain(): AuthUser = AuthUser(
    id = "rider-id",
    email = "rider@example.com",
    fullName = "Moto Rider",
    phoneNumber = "+52 555 555 5555",
    role = UserRole.Rider,
    isActive = true
)

private fun validSession(
    expiration: Instant,
    user: AuthUser = validUserDomain()
): AuthSession = AuthSession(
    secrets = SessionSecrets("access-token", "refresh-token"),
    accessTokenExpiresAt = expiration,
    user = user,
    rememberMe = true
)
