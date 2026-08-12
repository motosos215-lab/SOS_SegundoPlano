package com.example.sos_segundoplano.domain.push

import com.example.sos_segundoplano.domain.auth.AuthSessionIdentity

sealed interface PushTokenSyncResult {
    data object NoMonitorSession : PushTokenSyncResult
    data object NothingPending : PushTokenSyncResult
    data object Registered : PushTokenSyncResult
    data object Revoked : PushTokenSyncResult
    data object NotFound : PushTokenSyncResult
    data object AuthUnavailable : PushTokenSyncResult
    data object StorageUnavailable : PushTokenSyncResult
    data class RemoteFailure(
        val httpStatus: Int? = null,
        val errorCode: String? = null
    ) : PushTokenSyncResult
}

interface PushTokenRepository {
    suspend fun syncPendingMonitorToken(): PushTokenSyncResult
    suspend fun revokeMonitorRegistration(ownerSession: AuthSessionIdentity): PushTokenSyncResult
}
