package com.example.sos_segundoplano.features.auth

import com.example.sos_segundoplano.domain.auth.AccessDenied
import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.InactiveAccount
import com.example.sos_segundoplano.domain.auth.InvalidCredentials
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.RateLimited
import com.example.sos_segundoplano.domain.auth.ServerFailure
import com.example.sos_segundoplano.domain.auth.SessionExpired
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.SessionStorageFailureReason
import com.example.sos_segundoplano.domain.auth.StorageFailure
import com.example.sos_segundoplano.domain.auth.Timeout
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.repository.AuthRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialFormIsEmptyAndRestoredLoggedOutSelectsLogin() = runTest {
        val viewModel = viewModel(FakeAuthRepository())

        assertEquals("", viewModel.uiState.value.email)
        assertEquals("", viewModel.uiState.value.password)
        assertFalse(viewModel.uiState.value.rememberMe)
        assertFalse(viewModel.uiState.value.isPasswordVisible)
        assertFalse(viewModel.uiState.value.isSubmitting)
        assertNull(viewModel.uiState.value.authMessage)
        assertEquals(AuthEntryStatus.LoggedOut, viewModel.uiState.value.startupState)
    }

    @Test
    fun emptyAndInvalidFieldsAreRejectedLocally() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = viewModel(repository)

        viewModel.submitLogin()
        assertEquals(EmailValidationError.Required, viewModel.uiState.value.emailError)
        assertEquals(PasswordValidationError.Required, viewModel.uiState.value.passwordError)

        viewModel.onEmailChanged("not-an-email")
        viewModel.onPasswordChanged("password")
        viewModel.submitLogin()
        assertEquals(EmailValidationError.Invalid, viewModel.uiState.value.emailError)
        assertNull(viewModel.uiState.value.passwordError)
        assertEquals(0, repository.loginCalls)
    }

    @Test
    fun passwordAndRememberMeArePassedWithoutModification() = runTest {
        val repository = FakeAuthRepository(loginResult = InvalidCredentials(401, null, null))
        val viewModel = viewModel(repository)
        val password = "  exact password  "

        viewModel.onEmailChanged("  rider@example.com  ")
        viewModel.onPasswordChanged(password)
        viewModel.onRememberMeChanged(true)
        viewModel.submitLogin()

        assertEquals("  rider@example.com  ", repository.lastEmail)
        assertEquals(password, repository.lastPassword)
        assertEquals(true, repository.lastRememberMe)
    }

    @Test
    fun duplicateSubmitRunsOnlyOneLogin() = runTest {
        val pending = CompletableDeferred<AuthResult<AuthUser>>()
        val repository = FakeAuthRepository(loginHandler = { pending.await() })
        val viewModel = viewModel(repository)
        enterValidCredentials(viewModel)

        viewModel.submitLogin()
        viewModel.submitLogin()

        assertEquals(1, repository.loginCalls)
        assertTrue(viewModel.uiState.value.isSubmitting)
        pending.complete(AuthResult.Success(validUser()))
        assertFalse(viewModel.uiState.value.isSubmitting)
    }

    @Test
    fun successfulLoginClearsPasswordAndExposesNoSecrets() = runTest {
        val repository = FakeAuthRepository(
            loginResult = AuthResult.Success(validUser()),
            publishAuthenticatedOnSuccess = true
        )
        val viewModel = viewModel(repository)
        enterValidCredentials(viewModel)

        viewModel.submitLogin()

        assertEquals("", viewModel.uiState.value.password)
        assertEquals(AuthEntryStatus.Authenticated, viewModel.uiState.value.startupState)
        assertFalse(viewModel.uiState.value.toString().contains("access-token"))
        assertFalse(viewModel.uiState.value.toString().contains("refresh-token"))
    }

    @Test
    fun formStateStringRedactsCredentials() = runTest {
        val viewModel = viewModel(FakeAuthRepository())
        viewModel.onEmailChanged("private@example.com")
        viewModel.onPasswordChanged("secret-password")

        val stateText = viewModel.uiState.value.toString()

        assertFalse(stateText.contains("private@example.com"))
        assertFalse(stateText.contains("secret-password"))
        assertTrue(stateText.contains("[REDACTED]"))
    }

    @Test
    fun recoverableAuthenticationFailuresMapToLocalMessages() = runTest {
        val cases = listOf(
            InvalidCredentials(401, "ignored", "ignored") to AuthUiMessage.InvalidCredentials,
            NetworkUnavailable() to AuthUiMessage.NetworkUnavailable,
            Timeout() to AuthUiMessage.Timeout,
            RateLimited(null) to AuthUiMessage.RateLimited,
            ServerFailure(500, null, null) to AuthUiMessage.ServerFailure,
            SessionExpired to AuthUiMessage.SessionExpired,
            StorageFailure(SessionStorageFailureReason.Unavailable) to AuthUiMessage.StorageFailure
        )

        cases.forEach { (failure, expected) ->
            val viewModel = viewModel(FakeAuthRepository(loginResult = failure))
            enterValidCredentials(viewModel)
            viewModel.submitLogin()
            assertEquals(expected, viewModel.uiState.value.authMessage)
        }
    }

    @Test
    fun deniedAndInactiveAccountsMapMessageAndClearPassword() = runTest {
        val cases = listOf(
            AccessDenied(UserRole.Monitor) to AuthUiMessage.AccessDenied,
            InactiveAccount to AuthUiMessage.InactiveAccount
        )

        cases.forEach { (failure, expected) ->
            val repository = FakeAuthRepository(loginResult = failure, publishFailureState = true)
            val viewModel = viewModel(repository)
            enterValidCredentials(viewModel)
            viewModel.submitLogin()
            assertEquals(expected, viewModel.uiState.value.authMessage)
            assertEquals("", viewModel.uiState.value.password)
            assertFalse(viewModel.uiState.value.startupState == AuthEntryStatus.Authenticated)
        }
    }

    @Test
    fun changingFieldClearsRecoverableMessageAndFieldError() = runTest {
        val viewModel = viewModel(FakeAuthRepository(loginResult = InvalidCredentials(401, null, null)))
        enterValidCredentials(viewModel)
        viewModel.submitLogin()
        assertEquals(AuthUiMessage.InvalidCredentials, viewModel.uiState.value.authMessage)

        viewModel.onEmailChanged("new@example.com")

        assertNull(viewModel.uiState.value.authMessage)
        assertNull(viewModel.uiState.value.emailError)
    }

    @Test
    fun webReferencesOnlyPublishLocalMessage() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = viewModel(repository)

        viewModel.onRegisterWebSelected()
        assertEquals(AuthUiMessage.WebComingSoon, viewModel.uiState.value.authMessage)
        viewModel.dismissMessage()
        viewModel.onPasswordRecoverySelected()
        assertEquals(AuthUiMessage.WebComingSoon, viewModel.uiState.value.authMessage)
        assertEquals(0, repository.loginCalls)
    }

    @Test
    fun restorationKeepsNeutralGateUntilDecisionAndMapsSessionStates() = runTest {
        val restoration = CompletableDeferred<Unit>()
        val repository = FakeAuthRepository(initialState = authenticatedState())
        val viewModel = viewModel(repository, InitialSessionRestoration { restoration.await() })
        assertEquals(AuthEntryStatus.Restoring, viewModel.uiState.value.startupState)

        restoration.complete(Unit)
        assertEquals(AuthEntryStatus.Authenticated, viewModel.uiState.value.startupState)

        repository.session.value = SessionState.Expired
        assertEquals(AuthEntryStatus.Expired, viewModel.uiState.value.startupState)
        assertEquals(AuthUiMessage.SessionExpired, viewModel.uiState.value.authMessage)

        repository.session.value = SessionState.AccessDenied(UserRole.Monitor)
        assertEquals(AuthEntryStatus.AccessDenied, viewModel.uiState.value.startupState)
        assertFalse(viewModel.uiState.value.startupState == AuthEntryStatus.Authenticated)
    }

    private fun viewModel(
        repository: FakeAuthRepository,
        restoration: InitialSessionRestoration = InitialSessionRestoration {}
    ): LoginViewModel = LoginViewModel(repository, restoration)

    private fun enterValidCredentials(viewModel: LoginViewModel) {
        viewModel.onEmailChanged("rider@example.com")
        viewModel.onPasswordChanged("password")
    }
}

private class FakeAuthRepository(
    initialState: SessionState = SessionState.LoggedOut,
    var loginResult: AuthResult<AuthUser> = AuthResult.Success(validUser()),
    private val loginHandler: (suspend () -> AuthResult<AuthUser>)? = null,
    private val publishAuthenticatedOnSuccess: Boolean = false,
    private val publishFailureState: Boolean = false
) : AuthRepository {
    val session = MutableStateFlow(initialState)
    var loginCalls = 0
    var lastEmail: String? = null
    var lastPassword: String? = null
    var lastRememberMe: Boolean? = null

    override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> {
        loginCalls++
        lastEmail = email
        lastPassword = password
        lastRememberMe = rememberMe
        val result = loginHandler?.invoke() ?: loginResult
        if (result is AuthResult.Success && publishAuthenticatedOnSuccess) {
            session.value = authenticatedState(result.value, rememberMe)
        } else if (publishFailureState) {
            session.value = when (result) {
                is AccessDenied -> SessionState.AccessDenied(result.role)
                InactiveAccount -> SessionState.InactiveAccount
                else -> session.value
            }
        }
        return result
    }

    override suspend fun restoreSession(): AuthResult<AuthUser?> = AuthResult.Success(null)
    override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> = SessionExpired
    override suspend fun refreshSession(): AuthResult<AuthUser> = SessionExpired
    override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
    override fun observeSession(): StateFlow<SessionState> = session
}

private fun validUser(): AuthUser = AuthUser(
    id = "rider-id",
    email = "rider@example.com",
    fullName = "Moto Rider",
    phoneNumber = "+520000000000",
    role = UserRole.Rider,
    isActive = true
)

private fun authenticatedState(
    user: AuthUser = validUser(),
    rememberMe: Boolean = true
): SessionState.Authenticated = SessionState.Authenticated(
    user = user,
    accessTokenExpiresAt = Instant.parse("2026-08-05T22:00:00Z"),
    rememberMe = rememberMe
)
