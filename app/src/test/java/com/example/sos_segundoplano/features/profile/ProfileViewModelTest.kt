package com.example.sos_segundoplano.features.profile

import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionExpired
import com.example.sos_segundoplano.domain.auth.SessionState
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

    @Test fun loadsAutomaticallyOnceAndShowsSuccessWithoutSecrets() = runTest {
        val repository = FakeProfileRepository(ProfileResult.Success(validProfile()))
        val viewModel = ProfileViewModel(repository, FakeProfileViewModelAuthRepository())

        assertEquals(1, repository.loadCalls)
        assertEquals("Moto Rider", viewModel.uiState.value.profile?.fullName)
        viewModel.loadOnce()
        assertEquals(1, repository.loadCalls)
        assertFalse(viewModel.uiState.value.toString().contains("access-token"))
        assertFalse(viewModel.uiState.value.toString().contains("password"))
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
            val viewModel = ProfileViewModel(FakeProfileRepository(result), FakeProfileViewModelAuthRepository())
            assertEquals(expected, viewModel.uiState.value.error)
        }
    }

    @Test fun retryIsManualAndPreventsDuplicateLoads() = runTest {
        val pending = CompletableDeferred<ProfileResult<RiderProfile>>()
        val repository = FakeProfileRepository(ProfileNetworkUnavailable) { pending.await() }
        val viewModel = ProfileViewModel(repository, FakeProfileViewModelAuthRepository())

        viewModel.retry()
        viewModel.retry()

        assertEquals(2, repository.loadCalls)
        assertTrue(viewModel.uiState.value.isRetrying)
        pending.complete(ProfileResult.Success(validProfile()))
        assertEquals("Moto Rider", viewModel.uiState.value.profile?.fullName)
    }

    @Test fun logoutDialogAndConfirmPreventDuplicates() = runTest {
        val auth = FakeProfileViewModelAuthRepository()
        val viewModel = ProfileViewModel(FakeProfileRepository(ProfileResult.Success(validProfile())), auth)

        viewModel.showLogoutDialog()
        assertTrue(viewModel.uiState.value.isLogoutDialogVisible)
        viewModel.dismissLogoutDialog()
        assertFalse(viewModel.uiState.value.isLogoutDialogVisible)
        viewModel.showLogoutDialog()
        viewModel.confirmLogout()
        viewModel.confirmLogout()

        assertEquals(1, auth.logoutCalls)
        assertTrue(viewModel.uiState.value.isLoggingOut)
        assertFalse(viewModel.uiState.value.isLogoutDialogVisible)
    }

    @Test fun dateFormatterPhoneAbsentLastLoginAbsentAndInitialsAreSafe() = runTest {
        val profile = validProfile(phoneNumber = null, lastLoginAtUtc = null)
        val viewModel = ProfileViewModel(FakeProfileRepository(ProfileResult.Success(profile)), FakeProfileViewModelAuthRepository())

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

private class FakeProfileViewModelAuthRepository : AuthRepository {
    var logoutCalls = 0
    override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> = error("unused")
    override suspend fun restoreSession(): AuthResult<AuthUser?> = AuthResult.Success(null)
    override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> = SessionExpired
    override suspend fun refreshSession(): AuthResult<AuthUser> = SessionExpired
    override suspend fun logout(): AuthResult<Unit> {
        logoutCalls++
        return AuthResult.Success(Unit)
    }
    override fun observeSession(): StateFlow<SessionState> = MutableStateFlow(SessionState.LoggedOut)
}

private fun validProfile(
    phoneNumber: String? = "+52 555 555 5555",
    lastLoginAtUtc: Instant? = Instant.parse("2026-08-04T12:05:00Z")
) = RiderProfile(
    id = "rider-id",
    email = "rider@example.com",
    fullName = "Moto Rider",
    phoneNumber = phoneNumber,
    role = "Rider",
    isActive = true,
    createdAtUtc = Instant.parse("2026-08-04T12:00:00Z"),
    updatedAtUtc = Instant.parse("2026-08-04T12:05:00Z"),
    lastLoginAtUtc = lastLoginAtUtc
)
