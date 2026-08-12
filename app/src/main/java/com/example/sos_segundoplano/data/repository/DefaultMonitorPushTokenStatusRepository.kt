package com.example.sos_segundoplano.data.repository

import com.example.sos_segundoplano.data.remote.push.PushTokenRemoteDataSource
import com.example.sos_segundoplano.data.remote.push.PushTokenRemoteResult
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.push.MonitorPushTokenStatus
import com.example.sos_segundoplano.domain.push.MonitorPushTokenStatusRepository
import com.example.sos_segundoplano.domain.push.MonitorPushTokenStatusResult
import com.example.sos_segundoplano.domain.repository.AuthRepository

class DefaultMonitorPushTokenStatusRepository(
    private val authRepository: AuthRepository,
    private val remote: PushTokenRemoteDataSource
) : MonitorPushTokenStatusRepository {
    override suspend fun getStatus(): MonitorPushTokenStatusResult {
        val role = when (val state = authRepository.observeSession().value) {
            is SessionState.Authenticated -> state.user.role
            is SessionState.Refreshing -> state.user.role
            else -> null
        }
        if (role != UserRole.Monitor) return MonitorPushTokenStatusResult.Failure(403, "forbidden")
        val token = (authRepository.ensureValidAccessToken() as? AuthResult.Success)?.value?.reveal()
            ?: return MonitorPushTokenStatusResult.Failure(401, null)
        val first = remote.status("Bearer $token")
        val result = if (first is PushTokenRemoteResult.HttpFailure && first.status == 401 && authRepository.refreshSession() is AuthResult.Success) {
            val refreshed = (authRepository.ensureValidAccessToken() as? AuthResult.Success)?.value?.reveal() ?: return MonitorPushTokenStatusResult.Failure(401, null)
            remote.status("Bearer $refreshed")
        } else first
        return when (result) {
            is PushTokenRemoteResult.StatusLoaded -> MonitorPushTokenStatusResult.Success(
                MonitorPushTokenStatus(result.data.activeTokenCount, result.data.revokedTokenCount, result.data.hasActiveAndroidFcm, result.data.hasActiveIosApns, result.data.hasActiveWebPush, result.data.hasActiveWebFcm, result.data.lastRegisteredAtUtc)
            )
            is PushTokenRemoteResult.HttpFailure -> MonitorPushTokenStatusResult.Failure(result.status, result.errorCode)
            is PushTokenRemoteResult.NotFound -> MonitorPushTokenStatusResult.Failure(404, result.errorCode)
            is PushTokenRemoteResult.InvalidResponse -> MonitorPushTokenStatusResult.Failure(null, result.errorCode)
            else -> MonitorPushTokenStatusResult.Failure(null, null)
        }
    }
}
