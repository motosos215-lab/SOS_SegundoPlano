package com.example.sos_segundoplano.features.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.repository.AuthRepository

@Composable
fun MotoSosRoot(
    authRepository: AuthRepository,
    initialSessionRestoration: InitialSessionRestoration,
    onRegisterWebSelected: () -> Unit = {},
    onPasswordRecoverySelected: () -> Unit = {},
    riderContent: @Composable () -> Unit,
    monitorContent: @Composable () -> Unit
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
        onConfirmTakeover = loginViewModel::confirmTakeover,
        onCancelTakeover = loginViewModel::cancelTakeover,
        onDismissMessage = loginViewModel::dismissMessage,
        onRegisterWebSelected = onRegisterWebSelected,
        onPasswordRecoverySelected = onPasswordRecoverySelected,
        riderContent = riderContent,
        monitorContent = monitorContent
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
    onConfirmTakeover: () -> Unit = {},
    onCancelTakeover: () -> Unit = {},
    onDismissMessage: () -> Unit = {},
    onRegisterWebSelected: () -> Unit = {},
    onPasswordRecoverySelected: () -> Unit = {},
    riderContent: @Composable () -> Unit,
    monitorContent: @Composable () -> Unit
) {
    when (resolveRootDestination(state)) {
        RootDestination.Restoring -> AuthRestoringScreen()
        RootDestination.Rider -> riderContent()
        RootDestination.Monitor -> monitorContent()
        RootDestination.Login -> LoginScreen(
            state = state,
            onEmailChanged = onEmailChanged,
            onPasswordChanged = onPasswordChanged,
            onRememberMeChanged = onRememberMeChanged,
            onPasswordVisibilityChanged = onPasswordVisibilityChanged,
            onSubmit = onSubmit,
            onConfirmTakeover = onConfirmTakeover,
            onCancelTakeover = onCancelTakeover,
            onDismissMessage = onDismissMessage,
            onRegisterWebSelected = onRegisterWebSelected,
            onPasswordRecoverySelected = onPasswordRecoverySelected
        )
    }
}

enum class RootDestination { Restoring, Login, Rider, Monitor }

fun resolveRootDestination(state: LoginUiState): RootDestination = when (state.startupState) {
    AuthEntryStatus.Restoring -> RootDestination.Restoring
    AuthEntryStatus.Authenticated,
    AuthEntryStatus.Refreshing -> when (state.authenticatedRole) {
        UserRole.Rider -> RootDestination.Rider
        UserRole.Monitor -> RootDestination.Monitor
        UserRole.Unknown,
        null -> RootDestination.Login
    }
    AuthEntryStatus.LoggedOut,
    AuthEntryStatus.Expired,
    AuthEntryStatus.AccessDenied,
    AuthEntryStatus.InactiveAccount,
    AuthEntryStatus.StorageUnavailable -> RootDestination.Login
}
