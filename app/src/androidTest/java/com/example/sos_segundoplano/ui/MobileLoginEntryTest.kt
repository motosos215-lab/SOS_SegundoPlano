package com.example.sos_segundoplano.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.pressBackUnconditionally
import com.example.sos_segundoplano.MotoSosApp
import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.InvalidCredentials
import com.example.sos_segundoplano.domain.auth.SessionExpired
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.features.auth.InitialSessionRestoration
import com.example.sos_segundoplano.features.auth.MotoSosRoot
import com.example.sos_segundoplano.ui.theme.SOS_SegundoPlanoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class MobileLoginEntryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loggedOutShowsFormAndNeverComposesAuthenticatedContentOrPermissions() {
        val repository = UiFakeAuthRepository()
        var authenticatedCompositionCount = 0
        setRoot(repository) {
            authenticatedCompositionCount++
            MotoSosApp()
        }

        composeRule.onNodeWithTag("login_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Correo electrónico").assertIsDisplayed()
        composeRule.onNodeWithText("Contraseña").assertIsDisplayed()
        composeRule.onAllNodesWithTag("home_screen").assertCountEquals(0)
        composeRule.onAllNodesWithTag("location_permission_dialog").assertCountEquals(0)
        assertEquals(0, authenticatedCompositionCount)
    }

    @Test
    fun restoringShowsOnlyNeutralLoadingScreen() {
        val restoration = CompletableDeferred<Unit>()
        setRoot(UiFakeAuthRepository(), InitialSessionRestoration { restoration.await() })

        composeRule.onNodeWithTag("auth_restoring_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("auth_restoring_progress").assertIsDisplayed()
        composeRule.onAllNodesWithTag("login_screen").assertCountEquals(0)
        composeRule.onAllNodesWithTag("home_screen").assertCountEquals(0)
    }

    @Test
    fun passwordVisibilityRememberMeAndFormControlsWork() {
        setRoot(UiFakeAuthRepository())

        composeRule.onNodeWithContentDescription("Mostrar contraseña").assertIsDisplayed()
        composeRule.onNodeWithTag("login_password_visibility").performClick()
        composeRule.onNodeWithContentDescription("Ocultar contraseña").assertIsDisplayed()
        composeRule.onNodeWithTag("login_password_visibility").performClick()
        composeRule.onNodeWithContentDescription("Mostrar contraseña").assertIsDisplayed()

        composeRule.onNodeWithTag("login_remember_me").performClick()
        composeRule.onNodeWithTag("login_remember_me").assertIsEnabled().assertIsOn()
    }

    @Test
    fun submittingDisablesButtonAndShowsProgressWithoutDuplicateLogin() {
        val pending = CompletableDeferred<AuthResult<AuthUser>>()
        val repository = UiFakeAuthRepository(loginHandler = { pending.await() })
        setRoot(repository)
        enterCredentials()

        composeRule.onNodeWithTag("login_submit_button").performClick()
        composeRule.waitUntil { repository.loginCalls == 1 }

        composeRule.onNodeWithTag("login_submit_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("login_submit_progress", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(1, repository.loginCalls)
        pending.complete(AuthResult.Success(rider()))
    }

    @Test
    fun invalidCredentialsAreVisibleAsAccessibleText() {
        val repository = UiFakeAuthRepository(
            loginResult = InvalidCredentials(401, "ignored", "ignored")
        )
        setRoot(repository)
        enterCredentials()

        composeRule.onNodeWithTag("login_submit_button").performClick()

        composeRule.onNodeWithText("Correo o contraseña incorrectos.").assertIsDisplayed()
    }

    @Test
    fun webReferencesOnlyShowLocalComingSoonSnackbar() {
        val repository = UiFakeAuthRepository()
        setRoot(repository)

        composeRule.onNodeWithText("¿No tienes cuenta? Regístrate en la web").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Disponible próximamente en la web").assertIsDisplayed()
        composeRule.onNodeWithText("Cerrar").performClick()
        composeRule.onNodeWithText("¿Olvidaste tu contraseña?").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Disponible próximamente en la web").assertIsDisplayed()
        assertEquals(0, repository.loginCalls)
    }

    @Test
    fun authenticatedAndRefreshingShowExistingContentWithoutLogin() {
        val repository = UiFakeAuthRepository(initialState = authenticated())
        setRoot(repository) { MotoSosApp() }

        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("login_screen").assertCountEquals(0)

        repository.session.value = SessionState.Refreshing(
            user = rider(),
            accessTokenExpiresAt = Instant.parse("2026-08-05T22:00:00Z"),
            rememberMe = true
        )
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("login_screen").assertCountEquals(0)
    }

    @Test
    fun expiredAndAccessDeniedNeverRevealHome() {
        val repository = UiFakeAuthRepository(initialState = SessionState.Expired)
        setRoot(repository) { MotoSosApp() }

        composeRule.onNodeWithText("Tu sesión expiró. Inicia sesión nuevamente.").assertIsDisplayed()
        composeRule.onAllNodesWithTag("home_screen").assertCountEquals(0)

        repository.session.value = SessionState.AccessDenied(UserRole.Monitor)
        composeRule.onNodeWithText("Esta cuenta no tiene acceso a la aplicación del conductor.").assertIsDisplayed()
        composeRule.onAllNodesWithTag("home_screen").assertCountEquals(0)
    }

    @Test
    fun backFromLoginCannotRevealAuthenticatedContent() {
        val repository = UiFakeAuthRepository()
        var authenticatedCompositionCount = 0
        setRoot(repository) { authenticatedCompositionCount++ }

        composeRule.onNodeWithTag("login_screen").assertIsDisplayed()
        pressBackUnconditionally()

        assertTrueLoggedOut(repository)
        assertEquals(0, authenticatedCompositionCount)
    }

    private fun enterCredentials() {
        composeRule.onNodeWithTag("login_email_field").performTextInput("rider@example.com")
        composeRule.onNodeWithTag("login_password_field").performTextInput("password")
        composeRule.onNodeWithTag("login_submit_button").assertIsEnabled()
    }

    private fun setRoot(
        repository: UiFakeAuthRepository,
        restoration: InitialSessionRestoration = InitialSessionRestoration {},
        authenticatedContent: @androidx.compose.runtime.Composable () -> Unit = { MotoSosApp() }
    ) {
        composeRule.setContent {
            SOS_SegundoPlanoTheme {
                MotoSosRoot(
                    authRepository = repository,
                    initialSessionRestoration = restoration,
                    authenticatedContent = authenticatedContent
                )
            }
        }
    }

    private fun assertTrueLoggedOut(repository: UiFakeAuthRepository) {
        assertFalse(repository.session.value is SessionState.Authenticated)
    }
}

private class UiFakeAuthRepository(
    initialState: SessionState = SessionState.LoggedOut,
    private val loginResult: AuthResult<AuthUser> = AuthResult.Success(rider()),
    private val loginHandler: (suspend () -> AuthResult<AuthUser>)? = null
) : AuthRepository {
    val session = MutableStateFlow(initialState)
    var loginCalls = 0

    override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> {
        loginCalls++
        val result = loginHandler?.invoke() ?: loginResult
        if (result is AuthResult.Success) {
            session.value = authenticated(result.value, rememberMe)
        }
        return result
    }

    override suspend fun restoreSession(): AuthResult<AuthUser?> = AuthResult.Success(null)
    override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> = SessionExpired
    override suspend fun refreshSession(): AuthResult<AuthUser> = SessionExpired
    override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
    override fun observeSession(): StateFlow<SessionState> = session
}

private fun rider(): AuthUser = AuthUser(
    id = "rider-id",
    email = "rider@example.com",
    fullName = "Moto Rider",
    phoneNumber = "+520000000000",
    role = UserRole.Rider,
    isActive = true
)

private fun authenticated(
    user: AuthUser = rider(),
    rememberMe: Boolean = true
): SessionState.Authenticated = SessionState.Authenticated(
    user = user,
    accessTokenExpiresAt = Instant.parse("2026-08-05T22:00:00Z"),
    rememberMe = rememberMe
)
