package com.example.sos_segundoplano.core.auth

import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

class PushAwareAuthRepository(
    private val delegate: AuthRepository,
    private val onMonitorSessionAvailable: () -> Unit,
    private val beforeMonitorLogout: suspend () -> Unit
) : AuthRepository by delegate {
    override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> =
        delegate.login(email, password, rememberMe).also(::scheduleLoginForMonitor)

    override suspend fun restoreSession(): AuthResult<AuthUser?> =
        delegate.restoreSession().also(::scheduleRestoreForMonitor)

    override suspend fun logout(): AuthResult<Unit> {
        if (currentRole() == UserRole.Monitor) {
            try {
                beforeMonitorLogout()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Push is secondary: authentication logout must still complete.
            }
        }
        return delegate.logout()
    }

    override fun observeSession(): StateFlow<SessionState> = delegate.observeSession()

    private fun scheduleLoginForMonitor(result: AuthResult<AuthUser>) {
        if ((result as? AuthResult.Success)?.value?.role == UserRole.Monitor) {
            onMonitorSessionAvailable()
        }
    }

    private fun scheduleRestoreForMonitor(result: AuthResult<AuthUser?>) {
        if ((result as? AuthResult.Success)?.value?.role == UserRole.Monitor) {
            onMonitorSessionAvailable()
        }
    }

    private fun currentRole(): UserRole? = when (val state = delegate.observeSession().value) {
        is SessionState.Authenticated -> state.user.role
        is SessionState.Refreshing -> state.user.role
        else -> null
    }
}
