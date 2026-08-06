package com.example.sos_segundoplano.features.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.profile.ProfileAccessDenied
import com.example.sos_segundoplano.domain.profile.ProfileAccountUnavailable
import com.example.sos_segundoplano.domain.profile.ProfileInactiveAccount
import com.example.sos_segundoplano.domain.profile.ProfileInvalidResponse
import com.example.sos_segundoplano.domain.profile.ProfileNetworkUnavailable
import com.example.sos_segundoplano.domain.profile.ProfileRateLimited
import com.example.sos_segundoplano.domain.profile.ProfileResult
import com.example.sos_segundoplano.domain.profile.ProfileServerFailure
import com.example.sos_segundoplano.domain.profile.ProfileSessionExpired
import com.example.sos_segundoplano.domain.profile.ProfileStorageFailure
import com.example.sos_segundoplano.domain.profile.ProfileTimeout
import com.example.sos_segundoplano.domain.profile.ProfileUnauthorized
import com.example.sos_segundoplano.domain.profile.RiderProfile
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class ProfileUiError {
    NetworkUnavailable,
    Timeout,
    ServerFailure,
    RateLimited,
    InvalidResponse,
    SessionUnavailable
}

data class ProfileUiState(
    val loading: Boolean = false,
    val profile: RiderProfile? = null,
    val error: ProfileUiError? = null,
    val isRetrying: Boolean = false,
    val isLogoutDialogVisible: Boolean = false,
    val isLoggingOut: Boolean = false
) {
    override fun toString(): String =
        "ProfileUiState(loading=$loading, profile=${profile?.id}, error=$error, " +
            "isRetrying=$isRetrying, isLogoutDialogVisible=$isLogoutDialogVisible, isLoggingOut=$isLoggingOut)"
}

class ProfileViewModel(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = mutableUiState.asStateFlow()

    private var loadJob: Job? = null
    private var servingSessionUserId: String? = null
    private var sessionEpoch: Long = 0L

    init {
        viewModelScope.launch {
            authRepository.observeSession().collect(::onSessionEmitted)
        }
    }

    private fun onSessionEmitted(state: SessionState) {
        when (state) {
            is SessionState.Authenticated -> ensureSession(state.user.id)
            SessionState.LoggedOut,
            SessionState.Expired,
            is SessionState.AccessDenied,
            SessionState.InactiveAccount,
            SessionState.StorageUnavailable -> clearForLoggedOut()
            SessionState.Restoring,
            is SessionState.Refreshing -> Unit
        }
    }

    private fun ensureSession(userId: String) {
        if (servingSessionUserId == userId) return
        beginSession(userId)
    }

    private fun beginSession(userId: String) {
        loadJob?.cancel()
        sessionEpoch++
        val myEpoch = sessionEpoch
        servingSessionUserId = userId
        mutableUiState.value = ProfileUiState(loading = true)
        loadJob = viewModelScope.launch {
            val result = try {
                profileRepository.loadProfile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                ProfileInvalidResponse
            }
            if (sessionEpoch != myEpoch || servingSessionUserId != userId) return@launch
            applyProfileResult(result)
        }
    }

    private fun clearForLoggedOut() {
        sessionEpoch++
        loadJob?.cancel()
        loadJob = null
        servingSessionUserId = null
        mutableUiState.value = ProfileUiState()
    }

    fun retry() {
        val current = mutableUiState.value
        if (current.loading || current.isRetrying || current.isLoggingOut) return
        val userId = servingSessionUserId ?: return
        loadJob?.cancel()
        sessionEpoch++
        val myEpoch = sessionEpoch
        mutableUiState.update {
            it.copy(loading = false, profile = null, error = null, isRetrying = true)
        }
        loadJob = viewModelScope.launch {
            val result = try {
                profileRepository.loadProfile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                ProfileInvalidResponse
            }
            if (sessionEpoch != myEpoch || servingSessionUserId != userId) return@launch
            applyProfileResult(result)
        }
    }

    fun showLogoutDialog() {
        if (!mutableUiState.value.isLoggingOut) {
            mutableUiState.update { it.copy(isLogoutDialogVisible = true) }
        }
    }

    fun dismissLogoutDialog() {
        if (!mutableUiState.value.isLoggingOut) {
            mutableUiState.update { it.copy(isLogoutDialogVisible = false) }
        }
    }

    fun confirmLogout() {
        if (mutableUiState.value.isLoggingOut) return
        if (servingSessionUserId == null) return
        mutableUiState.update { it.copy(isLoggingOut = true, isLogoutDialogVisible = false) }
        viewModelScope.launch {
            try {
                authRepository.logout()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                Unit
            }
        }
    }

    private fun applyProfileResult(result: ProfileResult<RiderProfile>) {
        mutableUiState.update { current ->
            when (result) {
                is ProfileResult.Success -> current.copy(
                    loading = false,
                    profile = result.value,
                    error = null,
                    isRetrying = false
                )
                else -> current.copy(
                    loading = false,
                    profile = null,
                    error = result.toUiError(),
                    isRetrying = false
                )
            }
        }
    }

    class Factory(
        private val profileRepository: ProfileRepository,
        private val authRepository: AuthRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ProfileViewModel::class.java))
            return ProfileViewModel(profileRepository, authRepository) as T
        }
    }
}

fun formatProfileDate(
    instant: Instant?,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale("es", "MX")
): String? = instant?.atZone(zoneId)?.format(
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
)

private fun Any.toUiError(): ProfileUiError = when (this) {
    ProfileNetworkUnavailable -> ProfileUiError.NetworkUnavailable
    ProfileTimeout -> ProfileUiError.Timeout
    ProfileServerFailure -> ProfileUiError.ServerFailure
    ProfileRateLimited -> ProfileUiError.RateLimited
    ProfileInvalidResponse -> ProfileUiError.InvalidResponse
    ProfileUnauthorized,
    ProfileSessionExpired,
    ProfileAccessDenied,
    ProfileInactiveAccount,
    ProfileAccountUnavailable,
    ProfileStorageFailure -> ProfileUiError.SessionUnavailable
    else -> ProfileUiError.InvalidResponse
}
