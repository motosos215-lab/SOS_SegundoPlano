package com.example.sos_segundoplano.data.repository

import com.example.sos_segundoplano.data.remote.profile.ProfileRemoteDataSource
import com.example.sos_segundoplano.data.remote.profile.ProfileUserDto
import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionExpired
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.profile.ProfileAccessDenied
import com.example.sos_segundoplano.domain.profile.ProfileAccountUnavailable
import com.example.sos_segundoplano.domain.profile.ProfileInactiveAccount
import com.example.sos_segundoplano.domain.profile.ProfileInvalidResponse
import com.example.sos_segundoplano.domain.profile.ProfileNetworkUnavailable
import com.example.sos_segundoplano.domain.profile.ProfileRateLimited
import com.example.sos_segundoplano.domain.profile.ProfileResult
import com.example.sos_segundoplano.domain.profile.ProfileServerFailure
import com.example.sos_segundoplano.domain.profile.ProfileTimeout
import com.example.sos_segundoplano.domain.profile.ProfileUnauthorized
import com.example.sos_segundoplano.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProfileRepositoryTest {
    @Test fun successRequestsTokenBeforeGetAndDoesNotExposeToken() = runBlocking {
        val auth = FakeProfileAuthRepository()
        val remote = FakeProfileRemoteDataSource(ProfileResult.Success(validDto()))

        val result = DefaultProfileRepository(remote, auth).loadProfile()

        assertTrue(result is ProfileResult.Success)
        assertEquals(listOf("ensure"), auth.events)
        assertEquals(listOf("Bearer access-token-1"), remote.authorizations)
        assertFalse(result.toString().contains("access-token"))
    }

    @Test fun first401RefreshesOnceGetsNewTokenAndRetriesOnce() = runBlocking {
        val auth = FakeProfileAuthRepository(tokens = mutableListOf("old-token", "new-token"))
        val remote = FakeProfileRemoteDataSource(ProfileUnauthorized, ProfileResult.Success(validDto()))

        val result = DefaultProfileRepository(remote, auth).loadProfile()

        assertTrue(result is ProfileResult.Success)
        assertEquals(listOf("ensure", "refresh", "ensure"), auth.events)
        assertEquals(listOf("Bearer old-token", "Bearer new-token"), remote.authorizations)
    }

    @Test fun second401LogsOutWithoutAnotherRefresh() = runBlocking {
        val auth = FakeProfileAuthRepository(tokens = mutableListOf("old-token", "new-token"))
        val remote = FakeProfileRemoteDataSource(ProfileUnauthorized, ProfileUnauthorized)

        assertEquals(ProfileUnauthorized, DefaultProfileRepository(remote, auth).loadProfile())

        assertEquals(1, auth.refreshCalls)
        assertEquals(1, auth.logoutCalls)
        assertEquals(2, remote.authorizations.size)
    }

    @Test fun refreshSessionExpiredDoesNotCallSecondGet() = runBlocking {
        val auth = FakeProfileAuthRepository(refreshResult = SessionExpired)
        val remote = FakeProfileRemoteDataSource(ProfileUnauthorized)

        DefaultProfileRepository(remote, auth).loadProfile()

        assertEquals(1, auth.refreshCalls)
        assertEquals(1, remote.authorizations.size)
    }

    @Test fun recoverableFailuresKeepSession() = runBlocking {
        val cases = listOf(ProfileNetworkUnavailable, ProfileTimeout, ProfileRateLimited, ProfileServerFailure, ProfileInvalidResponse)
        cases.forEach { failure ->
            val auth = FakeProfileAuthRepository()
            val result = DefaultProfileRepository(FakeProfileRemoteDataSource(failure), auth).loadProfile()
            assertEquals(failure, result)
            assertEquals(0, auth.logoutCalls)
        }
    }

    @Test fun unavailableInactiveAndWrongRoleInvalidateSession() = runBlocking {
        val cases = listOf(
            FakeProfileRemoteDataSource(ProfileAccountUnavailable) to ProfileAccountUnavailable,
            FakeProfileRemoteDataSource(ProfileResult.Success(validDto().copy(isActive = false))) to ProfileInactiveAccount,
            FakeProfileRemoteDataSource(ProfileResult.Success(validDto().copy(role = "Monitor"))) to ProfileAccessDenied,
            FakeProfileRemoteDataSource(ProfileResult.Success(validDto().copy(role = " Rider "))) to ProfileAccessDenied
        )
        cases.forEach { (remote, expected) ->
            val auth = FakeProfileAuthRepository()
            assertEquals(expected, DefaultProfileRepository(remote, auth).loadProfile())
            assertEquals(1, auth.logoutCalls)
        }
    }

    @Test fun invalidRequiredFieldsAreInvalidResponse() = runBlocking {
        val invalidDtos = listOf(
            validDto().copy(id = ""),
            validDto().copy(email = ""),
            validDto().copy(fullName = ""),
            validDto().copy(createdAtUtc = "invalid"),
            validDto().copy(updatedAtUtc = "invalid"),
            validDto().copy(lastLoginAtUtc = "invalid")
        )
        invalidDtos.forEach { dto ->
            val auth = FakeProfileAuthRepository()
            assertEquals(ProfileInvalidResponse, DefaultProfileRepository(FakeProfileRemoteDataSource(ProfileResult.Success(dto)), auth).loadProfile())
            assertEquals(0, auth.logoutCalls)
        }
    }
}

private class FakeProfileRemoteDataSource(
    vararg results: ProfileResult<ProfileUserDto>
) : ProfileRemoteDataSource {
    private val queue = results.toMutableList()
    val authorizations = mutableListOf<String>()

    override suspend fun me(authorization: String): ProfileResult<ProfileUserDto> {
        authorizations += authorization
        return queue.removeFirst()
    }
}

private class FakeProfileAuthRepository(
    private val tokens: MutableList<String> = mutableListOf("access-token-1"),
    private val refreshResult: AuthResult<AuthUser> = AuthResult.Success(profileAuthUser())
) : AuthRepository {
    val events = mutableListOf<String>()
    var refreshCalls = 0
    var logoutCalls = 0

    override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> = error("unused")
    override suspend fun restoreSession(): AuthResult<AuthUser?> = AuthResult.Success(null)
    override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> {
        events += "ensure"
        return AuthResult.Success(AccessToken(tokens.removeFirst()))
    }
    override suspend fun refreshSession(): AuthResult<AuthUser> {
        events += "refresh"
        refreshCalls++
        return refreshResult
    }
    override suspend fun logout(): AuthResult<Unit> {
        logoutCalls++
        return AuthResult.Success(Unit)
    }
    override fun observeSession(): StateFlow<SessionState> = MutableStateFlow(SessionState.LoggedOut)
}

private fun validDto(): ProfileUserDto = ProfileUserDto(
    id = "rider-id",
    email = "rider@example.com",
    fullName = "Moto Rider",
    phoneNumber = "+52 555 555 5555",
    role = "Rider",
    isActive = true,
    createdAtUtc = "2026-08-04T12:00:00+00:00",
    updatedAtUtc = "2026-08-04T12:05:00+00:00",
    lastLoginAtUtc = "2026-08-04T12:05:00+00:00"
)

private fun profileAuthUser(): AuthUser = AuthUser(
    id = "rider-id",
    email = "rider@example.com",
    fullName = "Moto Rider",
    phoneNumber = "+52 555 555 5555",
    role = UserRole.Rider,
    isActive = true
)
