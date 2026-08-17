package com.example.sos_segundoplano.core.auth

import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthSessionIdentity
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionExpired
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.auth.authenticatedIdentityOrNull
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.push.PushDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

class PushAwareAuthRepository(
    private val delegate: AuthRepository,
    private val onMonitorSessionAvailable: () -> Unit,
    private val beforeMonitorLogout: suspend (ownerSession: AuthSessionIdentity) -> Unit
) : AuthRepository by delegate {
    override suspend fun login(email: String, password: String, rememberMe: Boolean): AuthResult<AuthUser> =
        delegate.login(email, password, rememberMe).also(::scheduleLoginForMonitor)

    override suspend fun restoreSession(): AuthResult<AuthUser?> =
        delegate.restoreSession().also(::scheduleRestoreForMonitor)

    override suspend fun logout(): AuthResult<Unit> = logoutCurrentSession(expectedSession = null)

    override suspend fun logoutIfCurrent(expectedSession: AuthSessionIdentity): AuthResult<Unit> =
        logoutCurrentSession(expectedSession)

    private suspend fun logoutCurrentSession(expectedSession: AuthSessionIdentity?): AuthResult<Unit> {
        val currentState = delegate.observeSession().value
        val currentIdentity = currentState.authenticatedIdentityOrNull()
        if (expectedSession != null && currentIdentity != expectedSession) return SessionExpired
        val monitorOwner = currentState.monitorIdentityOrNull()
        if (monitorOwner != null) {
            try {
                beforeMonitorLogout(monitorOwner)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Push is secondary: authentication logout must still complete.
            }
        }
        return if (expectedSession == null) {
            delegate.logout()
        } else {
            delegate.logoutIfCurrent(expectedSession)
        }
    }

    override fun observeSession(): StateFlow<SessionState> = delegate.observeSession()

    private fun scheduleLoginForMonitor(result: AuthResult<AuthUser>) {
        if ((result as? AuthResult.Success)?.value?.role == UserRole.Monitor) {
            PushDiagnostics.debug(PushDiagnostics.syncTrigger("monitor_login"))
            onMonitorSessionAvailable()
        }
    }

    private fun scheduleRestoreForMonitor(result: AuthResult<AuthUser?>) {
        if ((result as? AuthResult.Success)?.value?.role == UserRole.Monitor) {
            PushDiagnostics.debug(PushDiagnostics.syncTrigger("monitor_restore"))
            onMonitorSessionAvailable()
        }
    }

    private fun SessionState.monitorIdentityOrNull(): AuthSessionIdentity? = when (this) {
        is SessionState.Authenticated -> takeIf { user.role == UserRole.Monitor }?.authenticatedIdentityOrNull()
        is SessionState.Refreshing -> takeIf { user.role == UserRole.Monitor }?.authenticatedIdentityOrNull()
        else -> null
    }?.takeIf { it.userId.isNotBlank() }
}
