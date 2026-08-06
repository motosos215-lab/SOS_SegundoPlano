package com.example.sos_segundoplano.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sos_segundoplano.domain.auth.AccessDenied
import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.InactiveAccount
import com.example.sos_segundoplano.domain.auth.InvalidCredentials
import com.example.sos_segundoplano.domain.auth.InvalidResponse
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.RateLimited
import com.example.sos_segundoplano.domain.auth.RemoteLogoutFailed
import com.example.sos_segundoplano.domain.auth.ServerFailure
import com.example.sos_segundoplano.domain.auth.SessionExpired
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.StorageFailure
import com.example.sos_segundoplano.domain.auth.Timeout
import com.example.sos_segundoplano.domain.auth.Unauthorized
import com.example.sos_segundoplano.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun interface InitialSessionRestoration {
    suspend fun await()
}

enum class AuthEntryStatus {
    Restoring,
    LoggedOut,
    Authenticated,
    Refreshing,
    Expired,
    AccessDenied,
    InactiveAccount,
    StorageUnavailable
}

enum class EmailValidationError { Required, Invalid }
enum class PasswordValidationError { Required }

enum class AuthUiMessage {
    InvalidCredentials,
    Unauthorized,
    AccessDenied,
    InactiveAccount,
    RateLimited,
    NetworkUnavailable,
    Timeout,
    ServerFailure,
    InvalidResponse,
    StorageFailure,
    SessionExpired,
    StorageUnavailable,
    WebComingSoon
}

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val rememberMe: Boolean = false,
    val isPasswordVisible: Boolean = false,
    val isSubmitting: Boolean = false,
    val emailError: EmailValidationError? = null,
    val passwordError: PasswordValidationError? = null,
    val authMessage: AuthUiMessage? = null,
    val startupState: AuthEntryStatus = AuthEntryStatus.Restoring
) {
    override fun toString(): String =
        "LoginUiState(email=[REDACTED], password=[REDACTED], rememberMe=$rememberMe, " +
            "isPasswordVisible=$isPasswordVisible, isSubmitting=$isSubmitting, " +
            "emailError=$emailError, passwordError=$passwordError, authMessage=$authMessage, " +
            "startupState=$startupState)"
}

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val initialSessionRestoration: InitialSessionRestoration
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = mutableUiState.asStateFlow()

    private var restorationCompleted = false

    init {
        viewModelScope.launch {
            authRepository.observeSession().collect(::applySessionState)
        }
        viewModelScope.launch {
            try {
                initialSessionRestoration.await()
                restorationCompleted = true
                applySessionState(authRepository.observeSession().value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                restorationCompleted = true
                mutableUiState.update {
                    it.copy(
                        password = "",
                        isPasswordVisible = false,
                        isSubmitting = false,
                        authMessage = AuthUiMessage.StorageUnavailable,
                        startupState = AuthEntryStatus.StorageUnavailable
                    )
                }
            }
        }
    }

    fun onEmailChanged(email: String) {
        if (mutableUiState.value.isSubmitting) return
        mutableUiState.update { it.copy(email = email, emailError = null, authMessage = null) }
    }

    fun onPasswordChanged(password: String) {
        if (mutableUiState.value.isSubmitting) return
        mutableUiState.update { it.copy(password = password, passwordError = null, authMessage = null) }
    }

    fun onRememberMeChanged(rememberMe: Boolean) {
        if (mutableUiState.value.isSubmitting) return
        mutableUiState.update { it.copy(rememberMe = rememberMe, authMessage = null) }
    }

    fun onPasswordVisibilityChanged() {
        if (mutableUiState.value.isSubmitting) return
        mutableUiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun submitLogin() {
        val current = mutableUiState.value
        if (current.isSubmitting) return

        val emailError = validateEmail(current.email)
        val passwordError = if (current.password.isEmpty()) PasswordValidationError.Required else null
        if (emailError != null || passwordError != null) {
            mutableUiState.update {
                it.copy(emailError = emailError, passwordError = passwordError, authMessage = null)
            }
            return
        }

        mutableUiState.update { it.copy(isSubmitting = true, authMessage = null) }
        viewModelScope.launch {
            val result = try {
                authRepository.login(current.email, current.password, current.rememberMe)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(isSubmitting = false, authMessage = AuthUiMessage.InvalidResponse)
                }
                return@launch
            }
            when (result) {
                is AuthResult.Success -> mutableUiState.update {
                    it.copy(password = "", isPasswordVisible = false, isSubmitting = false, authMessage = null)
                }
                is AuthFailure -> mutableUiState.update {
                    val clearPassword = result is AccessDenied || result === InactiveAccount
                    it.copy(
                        password = if (clearPassword) "" else it.password,
                        isPasswordVisible = if (clearPassword) false else it.isPasswordVisible,
                        isSubmitting = false,
                        authMessage = result.toUiMessage()
                    )
                }
            }
        }
    }

    fun dismissMessage() {
        mutableUiState.update { it.copy(authMessage = null) }
    }

    fun onRegisterWebSelected() {
        if (!mutableUiState.value.isSubmitting) {
            mutableUiState.update { it.copy(authMessage = AuthUiMessage.WebComingSoon) }
        }
    }

    fun onPasswordRecoverySelected() {
        if (!mutableUiState.value.isSubmitting) {
            mutableUiState.update { it.copy(authMessage = AuthUiMessage.WebComingSoon) }
        }
    }

    private fun applySessionState(sessionState: SessionState) {
        if (!restorationCompleted) {
            mutableUiState.update { it.copy(startupState = AuthEntryStatus.Restoring) }
            return
        }

        val status = sessionState.toEntryStatus()
        mutableUiState.update { current ->
            val terminalLoginState = status == AuthEntryStatus.Authenticated ||
                status == AuthEntryStatus.Expired ||
                status == AuthEntryStatus.AccessDenied ||
                status == AuthEntryStatus.InactiveAccount ||
                status == AuthEntryStatus.StorageUnavailable
            current.copy(
                password = if (terminalLoginState) "" else current.password,
                isPasswordVisible = if (terminalLoginState) false else current.isPasswordVisible,
                isSubmitting = if (terminalLoginState) false else current.isSubmitting,
                authMessage = sessionState.entryMessage() ?: current.authMessage,
                startupState = status
            )
        }
    }

    private fun validateEmail(email: String): EmailValidationError? {
        val candidate = email.trim()
        return when {
            candidate.isEmpty() -> EmailValidationError.Required
            !EMAIL_PATTERN.matches(candidate) -> EmailValidationError.Invalid
            else -> null
        }
    }

    class Factory(
        private val authRepository: AuthRepository,
        private val initialSessionRestoration: InitialSessionRestoration
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LoginViewModel::class.java))
            return LoginViewModel(authRepository, initialSessionRestoration) as T
        }
    }

    private companion object {
        val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}

private fun SessionState.toEntryStatus(): AuthEntryStatus = when (this) {
    SessionState.Restoring -> AuthEntryStatus.Restoring
    SessionState.LoggedOut -> AuthEntryStatus.LoggedOut
    is SessionState.Authenticated -> AuthEntryStatus.Authenticated
    is SessionState.Refreshing -> AuthEntryStatus.Refreshing
    SessionState.Expired -> AuthEntryStatus.Expired
    is SessionState.AccessDenied -> AuthEntryStatus.AccessDenied
    SessionState.InactiveAccount -> AuthEntryStatus.InactiveAccount
    SessionState.StorageUnavailable -> AuthEntryStatus.StorageUnavailable
}

private fun SessionState.entryMessage(): AuthUiMessage? = when (this) {
    SessionState.Expired -> AuthUiMessage.SessionExpired
    is SessionState.AccessDenied -> AuthUiMessage.AccessDenied
    SessionState.InactiveAccount -> AuthUiMessage.InactiveAccount
    SessionState.StorageUnavailable -> AuthUiMessage.StorageUnavailable
    else -> null
}

private fun AuthFailure.toUiMessage(): AuthUiMessage = when (this) {
    is InvalidCredentials -> AuthUiMessage.InvalidCredentials
    is Unauthorized -> AuthUiMessage.Unauthorized
    is AccessDenied -> AuthUiMessage.AccessDenied
    InactiveAccount -> AuthUiMessage.InactiveAccount
    is RateLimited -> AuthUiMessage.RateLimited
    is NetworkUnavailable -> AuthUiMessage.NetworkUnavailable
    is Timeout -> AuthUiMessage.Timeout
    is ServerFailure -> AuthUiMessage.ServerFailure
    is InvalidResponse -> AuthUiMessage.InvalidResponse
    is StorageFailure -> AuthUiMessage.StorageFailure
    SessionExpired -> AuthUiMessage.SessionExpired
    is RemoteLogoutFailed -> AuthUiMessage.InvalidResponse
}
