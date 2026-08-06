package com.example.sos_segundoplano.features.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sos_segundoplano.domain.repository.AuthRepository

@Composable
fun MotoSosRoot(
    authRepository: AuthRepository,
    initialSessionRestoration: InitialSessionRestoration,
    authenticatedContent: @Composable () -> Unit
) {
    val factory = remember(authRepository, initialSessionRestoration) {
        LoginViewModel.Factory(authRepository, initialSessionRestoration)
    }
    val loginViewModel: LoginViewModel = viewModel(factory = factory)
    val state by loginViewModel.uiState.collectAsStateWithLifecycle()

    MotoSosRoot(
        state = state,
        onEmailChanged = loginViewModel::onEmailChanged,
        onPasswordChanged = loginViewModel::onPasswordChanged,
        onRememberMeChanged = loginViewModel::onRememberMeChanged,
        onPasswordVisibilityChanged = loginViewModel::onPasswordVisibilityChanged,
        onSubmit = loginViewModel::submitLogin,
        onDismissMessage = loginViewModel::dismissMessage,
        onRegisterWebSelected = loginViewModel::onRegisterWebSelected,
        onPasswordRecoverySelected = loginViewModel::onPasswordRecoverySelected,
        authenticatedContent = authenticatedContent
    )
}

@Composable
fun MotoSosRoot(
    state: LoginUiState,
    onEmailChanged: (String) -> Unit = {},
    onPasswordChanged: (String) -> Unit = {},
    onRememberMeChanged: (Boolean) -> Unit = {},
    onPasswordVisibilityChanged: () -> Unit = {},
    onSubmit: () -> Unit = {},
    onDismissMessage: () -> Unit = {},
    onRegisterWebSelected: () -> Unit = {},
    onPasswordRecoverySelected: () -> Unit = {},
    authenticatedContent: @Composable () -> Unit
) {
    when (state.startupState) {
        AuthEntryStatus.Restoring -> AuthRestoringScreen()
        AuthEntryStatus.Authenticated,
        AuthEntryStatus.Refreshing -> authenticatedContent()
        AuthEntryStatus.LoggedOut,
        AuthEntryStatus.Expired,
        AuthEntryStatus.AccessDenied,
        AuthEntryStatus.InactiveAccount,
        AuthEntryStatus.StorageUnavailable -> LoginScreen(
            state = state,
            onEmailChanged = onEmailChanged,
            onPasswordChanged = onPasswordChanged,
            onRememberMeChanged = onRememberMeChanged,
            onPasswordVisibilityChanged = onPasswordVisibilityChanged,
            onSubmit = onSubmit,
            onDismissMessage = onDismissMessage,
            onRegisterWebSelected = onRegisterWebSelected,
            onPasswordRecoverySelected = onPasswordRecoverySelected
        )
    }
}
