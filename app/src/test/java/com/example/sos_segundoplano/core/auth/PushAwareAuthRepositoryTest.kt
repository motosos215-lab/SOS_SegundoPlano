package com.example.sos_segundoplano.core.auth

import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthSessionIdentity
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PushAwareAuthRepositoryTest {
    @Test fun monitorLoginAndRestoreSchedulePushWithoutChangingAuthSuccess() = runBlocking {
        val delegate = HookAuthRepository(UserRole.Monitor)
        var syncCalls = 0
        val repository = PushAwareAuthRepository(delegate, { syncCalls++ }, { _ -> })

        assertTrue(repository.login("monitor@example.com", "password", true) is AuthResult.Success)
        assertTrue(repository.restoreSession() is AuthResult.Success)
        assertEquals(2, syncCalls)
    }

    @Test fun riderNeverSchedulesMonitorRegistration() = runBlocking {
        val delegate = HookAuthRepository(UserRole.Rider)
        var syncCalls = 0
        val repository = PushAwareAuthRepository(delegate, { syncCalls++ }, { _ -> })

        repository.login("rider@example.com", "password", true)
        repository.restoreSession()

        assertEquals(0, syncCalls)
        assertTrue(repository.observeSession().value is SessionState.Authenticated)
    }

    @Test fun monitorLogoutAttemptsRevokeBeforeAuthIsClearedEvenWhenRevokeFails() = runBlocking {
        val events = mutableListOf<String>()
        val delegate = HookAuthRepository(UserRole.Monitor, events)
        val repository = PushAwareAuthRepository(
            delegate,
            {},
            { ownerSession ->
                events += "revoke:${ownerSession.userId}:${ownerSession.generation}"
                throw IllegalStateException("network_failure")
            }
        )

        val result = repository.logoutIfCurrent(AuthSessionIdentity("user-id", generation = 0L))

        assertTrue(result is AuthResult.Success)
        assertEquals(
            listOf("revoke:user-id:0", "auth_logout"),
            events
        )
        assertEquals(SessionState.LoggedOut, repository.observeSession().value)
    }

    @Test fun staleLogoutFromOlderLogicalSessionCannotRevokeNewMonitorSession() = runBlocking {
        val events = mutableListOf<String>()
        val delegate = HookAuthRepository(UserRole.Monitor, events, generation = 2L)
        val repository = PushAwareAuthRepository(
            delegate,
            {},
            { ownerSession -> events += "revoke:${ownerSession.generation}" }
        )

        val result = repository.logoutIfCurrent(AuthSessionIdentity("user-id", generation = 1L))

        assertEquals(com.example.sos_segundoplano.domain.auth.SessionExpired, result)
        assertTrue(events.isEmpty())
        assertTrue(repository.observeSession().value is SessionState.Authenticated)
    }

    @Test fun refreshAndLifecycleLikeSessionObservationsNeverInvokeRevoke() = runBlocking {
        val events = mutableListOf<String>()
        val delegate = HookAuthRepository(UserRole.Monitor, events, generation = 3L)
        val repository = PushAwareAuthRepository(
            delegate,
            {},
            { ownerSession -> events += "revoke:${ownerSession.generation}" }
        )

        assertTrue(repository.refreshSession() is AuthResult.Success)
        repository.observeSession().value
        repository.observeSession().value

        assertTrue(events.isEmpty())
    }
}

private class HookAuthRepository(
    role: UserRole,
    private val events: MutableList<String> = mutableListOf(),
    generation: Long = 0L
) : AuthRepository {
    private val user = AuthUser("user-id", "user@example.com", "User Test", "+520000000000", role, true)
    private val state = MutableStateFlow<SessionState>(
        SessionState.Authenticated(user, Instant.MAX, true, generation)
    )

    override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> =
        AuthResult.Success(user)
    override suspend fun restoreSession(): AuthResult<AuthUser?> = AuthResult.Success(user)
    override suspend fun ensureValidAccessToken() = AuthResult.Success(AccessToken("access-token"))
    override suspend fun refreshSession(): AuthResult<AuthUser> = AuthResult.Success(user)
    override suspend fun logout(): AuthResult<Unit> {
        events += "auth_logout"
        state.value = SessionState.LoggedOut
        return AuthResult.Success(Unit)
    }
    override fun observeSession(): StateFlow<SessionState> = state
}
