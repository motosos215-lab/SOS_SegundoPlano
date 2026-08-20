package com.example.sos_segundoplano.core.auth

import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthSessionIdentity
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.SessionExpired
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.SessionTakeoverChallenge
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
        delegate.login(email, password, rememberMe).also(::scheduleLoginForPush)

    override suspend fun restoreSession(): AuthResult<AuthUser?> =
        delegate.restoreSession().also(::scheduleRestoreForPush)

    override suspend fun takeover(challenge: SessionTakeoverChallenge): AuthResult<AuthUser> =
        delegate.takeover(challenge).also(::scheduleLoginForPush)

    override suspend fun logout(): AuthResult<Unit> = logoutCurrentSession(expectedSession = null)

    override suspend fun logoutIfCurrent(expectedSession: AuthSessionIdentity): AuthResult<Unit> =
        logoutCurrentSession(expectedSession)

    private suspend fun logoutCurrentSession(expectedSession: AuthSessionIdentity?): AuthResult<Unit> {
        val currentState = delegate.observeSession().value
        val currentIdentity = currentState.authenticatedIdentityOrNull()
        if (expectedSession != null && currentIdentity != expectedSession) return SessionExpired
        val monitorOwner = currentState.pushIdentityOrNull()
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

    private fun scheduleLoginForPush(result: AuthResult<AuthUser>) {
        val role = (result as? AuthResult.Success)?.value?.role
        if (role == UserRole.Monitor || role == UserRole.Rider) {
            PushDiagnostics.debug(PushDiagnostics.syncTrigger("mobile_login"))
            onMonitorSessionAvailable()
        }
    }

    private fun scheduleRestoreForPush(result: AuthResult<AuthUser?>) {
        val role = (result as? AuthResult.Success)?.value?.role
        if (role == UserRole.Monitor || role == UserRole.Rider) {
            PushDiagnostics.debug(PushDiagnostics.syncTrigger("mobile_restore"))
            onMonitorSessionAvailable()
        }
    }

    private fun SessionState.pushIdentityOrNull(): AuthSessionIdentity? = when (this) {
        is SessionState.Authenticated -> takeIf { user.role == UserRole.Monitor || user.role == UserRole.Rider }?.authenticatedIdentityOrNull()
        is SessionState.Refreshing -> takeIf { user.role == UserRole.Monitor || user.role == UserRole.Rider }?.authenticatedIdentityOrNull()
        else -> null
    }?.takeIf { it.userId.isNotBlank() }
}
