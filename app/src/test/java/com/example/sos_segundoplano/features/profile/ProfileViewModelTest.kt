package com.example.sos_segundoplano.features.profile

import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionExpired
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.profile.ProfileInvalidResponse
import com.example.sos_segundoplano.domain.profile.ProfileNetworkUnavailable
import com.example.sos_segundoplano.domain.profile.ProfileRateLimited
import com.example.sos_segundoplano.domain.profile.ProfileResult
import com.example.sos_segundoplano.domain.profile.ProfileServerFailure
import com.example.sos_segundoplano.domain.profile.ProfileTimeout
import com.example.sos_segundoplano.domain.profile.RiderProfile
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.repository.ProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun authenticatedSessionLoadsProfileOnceAndStaysStable() = runTest {
        val repository = FakeProfileRepository(ProfileResult.Success(validProfile("rider-a")))
        val auth = FakeAuthRepository()
        val viewModel = ProfileViewModel(repository, auth)

        assertEquals(1, repository.loadCalls)
        assertEquals("rider-a", viewModel.uiState.value.profile?.id)

        auth.emitSession(SessionState.Refreshing(aUser(), expiresAt(), true))
        auth.emitSession(SessionState.Authenticated(aUser(), expiresAt(), true))
        auth.emitSession(SessionState.Authenticated(aUser(), expiresAt(), true))

        assertEquals(1, repository.loadCalls)
        assertEquals("rider-a", viewModel.uiState.value.profile?.id)
        assertFalse(viewModel.uiState.value.loading)
        assertFalse(viewModel.uiState.value.toString().contains("access-token"))
        assertFalse(viewModel.uiState.value.toString().contains("password"))
    }

    @Test fun switchedSessionReloadsProfileShowsLoadingIntermediate() = runTest {
        val pendingB = CompletableDeferred<ProfileResult<RiderProfile>>()
        val repository = object : ProfileRepository {
            var loadCalls = 0
            override suspend fun loadProfile(): ProfileResult<RiderProfile> {
                loadCalls++
                return if (loadCalls == 1) ProfileResult.Success(validProfile("rider-a"))
                else pendingB.await()
            }
        }
        val auth = FakeAuthRepository()
        val viewModel = ProfileViewModel(repository, auth)

        assertEquals(1, repository.loadCalls)
        assertEquals("rider-a", viewModel.uiState.value.profile?.id)

        auth.emitSession(SessionState.LoggedOut)
        assertEquals(1, repository.loadCalls)
        assertFalse(viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.profile)
        assertNull(viewModel.uiState.value.error)

        auth.emitSession(SessionState.Authenticated(bUser(), expiresAt(), true))

        assertEquals(2, repository.loadCalls)
        assertTrue(viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.profile)
        assertNull(viewModel.uiState.value.error)

        pendingB.complete(ProfileResult.Success(validProfile("rider-b")))

        assertEquals(2, repository.loadCalls)
        assertEquals("rider-b", viewModel.uiState.value.profile?.id)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test fun loggedOutSessionDoesNotLoadProfile() = runTest {
        val repository = FakeProfileRepository(ProfileResult.Success(validProfile("rider-a")))
        val auth = FakeAuthRepository(initialSession = SessionState.LoggedOut)
        val viewModel = ProfileViewModel(repository, auth)

        assertEquals(0, repository.loadCalls)
        assertFalse(viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.profile)
    }

    @Test fun staleProfileResultFromPreviousSessionIsDiscarded() = runTest {
        val pending = CompletableDeferred<ProfileResult<RiderProfile>>()
        val repository = object : ProfileRepository {
            var loadCalls = 0
            override suspend fun loadProfile(): ProfileResult<RiderProfile> {
                loadCalls++
                return if (loadCalls == 1) pending.await() else ProfileResult.Success(validProfile("rider-b"))
            }
        }
        val auth = FakeAuthRepository()
        val viewModel = ProfileViewModel(repository, auth)

        assertEquals(1, repository.loadCalls)
        assertTrue(viewModel.uiState.value.loading)

        auth.emitSession(SessionState.Authenticated(bUser(), expiresAt(), true))
        pending.complete(ProfileResult.Success(validProfile("rider-a")))

        assertEquals(2, repository.loadCalls)
        assertEquals("rider-b", viewModel.uiState.value.profile?.id)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test fun logoutLeavesFullyCleanState() = runTest {
        val repository = FakeProfileRepository(ProfileResult.Success(validProfile("rider-a")))
        val auth = FakeAuthRepository()
        val viewModel = ProfileViewModel(repository, auth)

        assertEquals("rider-a", viewModel.uiState.value.profile?.id)

        viewModel.showLogoutDialog()
        assertTrue(viewModel.uiState.value.isLogoutDialogVisible)

        viewModel.confirmLogout()
        viewModel.confirmLogout()

        assertEquals(1, auth.logoutCalls)
        assertTrue(auth.session.value is SessionState.LoggedOut)
        assertNull(viewModel.uiState.value.profile)
        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)
        assertFalse(viewModel.uiState.value.isRetrying)
        assertFalse(viewModel.uiState.value.isLogoutDialogVisible)
        assertFalse(viewModel.uiState.value.isLoggingOut)
    }

    @Test fun sameUserReloadsAfterLoggedOut() = runTest {
        val repository = FakeProfileRepository(ProfileResult.Success(validProfile("rider-a")))
        val auth = FakeAuthRepository()
        val viewModel = ProfileViewModel(repository, auth)

        assertEquals(1, repository.loadCalls)
        assertEquals("rider-a", viewModel.uiState.value.profile?.id)

        auth.emitSession(SessionState.LoggedOut)
        assertNull(viewModel.uiState.value.profile)

        auth.emitSession(SessionState.Authenticated(aUser(), expiresAt(), true))

        assertEquals(2, repository.loadCalls)
        assertEquals("rider-a", viewModel.uiState.value.profile?.id)
    }

    @Test fun mapsRecoverableErrors() = runTest {
        val cases = listOf(
            ProfileNetworkUnavailable to ProfileUiError.NetworkUnavailable,
            ProfileTimeout to ProfileUiError.Timeout,
            ProfileServerFailure to ProfileUiError.ServerFailure,
            ProfileRateLimited to ProfileUiError.RateLimited,
            ProfileInvalidResponse to ProfileUiError.InvalidResponse
        )
        cases.forEach { (result, expected) ->
            val viewModel = ProfileViewModel(FakeProfileRepository(result), FakeAuthRepository())
            assertEquals(expected, viewModel.uiState.value.error)
        }
    }

    @Test fun retryIsManualAndPreventsDuplicateLoads() = runTest {
        val pending = CompletableDeferred<ProfileResult<RiderProfile>>()
        val repository = FakeProfileRepository(ProfileNetworkUnavailable) { pending.await() }
        val viewModel = ProfileViewModel(repository, FakeAuthRepository())

        viewModel.retry()
        viewModel.retry()

        assertEquals(2, repository.loadCalls)
        assertTrue(viewModel.uiState.value.isRetrying)
        pending.complete(ProfileResult.Success(validProfile("rider-a")))
        assertEquals("rider-a", viewModel.uiState.value.profile?.id)
    }

    @Test fun retryAfterLoggedOutDoesNothing() = runTest {
        val repository = FakeProfileRepository(ProfileNetworkUnavailable)
        val auth = FakeAuthRepository(initialSession = SessionState.LoggedOut)
        val viewModel = ProfileViewModel(repository, auth)

        viewModel.retry()
        viewModel.retry()

        assertEquals(0, repository.loadCalls)
    }

    @Test fun dateFormatterPhoneAbsentLastLoginAbsentAndInitialsAreSafe() = runTest {
        val profile = validProfile("rider-a", phoneNumber = null, lastLoginAtUtc = null)
        val viewModel = ProfileViewModel(FakeProfileRepository(ProfileResult.Success(profile)), FakeAuthRepository())

        assertNull(viewModel.uiState.value.profile?.phoneNumber)
        assertNull(viewModel.uiState.value.profile?.lastLoginAtUtc)
        assertEquals("MR", profileInitials("Moto Rider", Locale("es", "MX")))
        assertEquals("M", profileInitials("Moto", Locale("es", "MX")))
        assertEquals("?", profileInitials("   ", Locale("es", "MX")))
        assertTrue(formatProfileDate(Instant.parse("2026-08-04T12:00:00Z"), ZoneId.of("America/Mexico_City"), Locale("es", "MX"))!!.contains("2026"))
    }
}

private class FakeProfileRepository(
    private var result: ProfileResult<RiderProfile>,
    private val handler: (suspend () -> ProfileResult<RiderProfile>)? = null
) : ProfileRepository {
    var loadCalls = 0
    override suspend fun loadProfile(): ProfileResult<RiderProfile> {
        loadCalls++
        return if (loadCalls > 1 && handler != null) handler.invoke() else result
    }
}

private class FakeAuthRepository(
    initialSession: SessionState = SessionState.Authenticated(aUser(), expiresAt(), true)
) : AuthRepository {
    val session = MutableStateFlow(initialSession)
    var logoutCalls = 0

    override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> = error("unused")
    override suspend fun restoreSession(): AuthResult<AuthUser?> = AuthResult.Success(null)
    override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> = SessionExpired
    override suspend fun refreshSession(): AuthResult<AuthUser> = SessionExpired
    override suspend fun logout(): AuthResult<Unit> {
        logoutCalls++
        session.value = SessionState.LoggedOut
        return AuthResult.Success(Unit)
    }
    override fun observeSession(): StateFlow<SessionState> = session

    fun emitSession(state: SessionState) {
        session.value = state
    }
}

private fun aUser(): AuthUser = authUser("rider-a", "Moto Rider A")
private fun bUser(): AuthUser = authUser("rider-b", "Moto Rider B")

private fun authUser(id: String, fullName: String): AuthUser = AuthUser(
    id = id,
    email = "$id@example.com",
    fullName = fullName,
    phoneNumber = "+52 555 555 5555",
    role = UserRole.Rider,
    isActive = true
)

private fun expiresAt(): Instant = Instant.parse("2026-08-06T12:00:00.000Z")

private fun validProfile(
    id: String,
    phoneNumber: String? = "+52 555 555 5555",
    lastLoginAtUtc: Instant? = Instant.parse("2026-08-04T12:05:00Z")
) = RiderProfile(
    id = id,
    email = "$id@example.com",
    fullName = "Moto Rider",
    phoneNumber = phoneNumber,
    role = "Rider",
    isActive = true,
    createdAtUtc = Instant.parse("2026-08-04T12:00:00Z"),
    updatedAtUtc = Instant.parse("2026-08-04T12:05:00Z"),
    lastLoginAtUtc = lastLoginAtUtc
)
