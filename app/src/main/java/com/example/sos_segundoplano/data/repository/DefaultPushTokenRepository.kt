package com.example.sos_segundoplano.data.repository

import com.example.sos_segundoplano.data.remote.push.PushNotificationTokenDto
import com.example.sos_segundoplano.data.remote.push.PushTokenMetadataDto
import com.example.sos_segundoplano.data.remote.push.PushTokenRemoteDataSource
import com.example.sos_segundoplano.data.remote.push.PushTokenRemoteResult
import com.example.sos_segundoplano.data.remote.push.RegisterPushTokenRequestDto
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthSessionIdentity
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.auth.authenticatedIdentityOrNull
import com.example.sos_segundoplano.domain.push.PushTokenRepository
import com.example.sos_segundoplano.domain.push.PushTokenState
import com.example.sos_segundoplano.domain.push.PushTokenStore
import com.example.sos_segundoplano.domain.push.PushTokenStoreResult
import com.example.sos_segundoplano.domain.push.PushTokenSyncResult
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.push.PushDiagnostics
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface PushAppMetadataProvider {
    fun get(): PushAppMetadata
}

data class PushAppMetadata(val appVersion: String, val osVersion: String)

class DefaultPushTokenRepository(
    private val authRepository: AuthRepository,
    private val store: PushTokenStore,
    private val remoteDataSource: PushTokenRemoteDataSource,
    private val metadataProvider: PushAppMetadataProvider
) : PushTokenRepository {
    private val operationMutex = Mutex()

    override suspend fun syncPendingMonitorToken(): PushTokenSyncResult = operationMutex.withLock {
        val ownerSession = currentMonitorIdentity() ?: return PushTokenSyncResult.NoMonitorSession
        val ownerUserId = ownerSession.userId.trim().takeIf { it.isNotEmpty() }
            ?: return PushTokenSyncResult.NoMonitorSession
        val state = readState() ?: return PushTokenSyncResult.StorageUnavailable
        logSyncState(state)
        val token = state.pendingToken?.takeIf { it.isNotBlank() }
            ?: state.currentToken?.takeIf {
                it.isNotBlank() && state.remoteRegistrationOwnerUserId != ownerUserId
            }
            ?: return PushTokenSyncResult.NothingPending
        when (val result = register(token)) {
            is PushTokenRemoteResult.Registered -> {
                if (currentMonitorIdentity() != ownerSession) return PushTokenSyncResult.NoMonitorSession
                val latest = readState() ?: return PushTokenSyncResult.StorageUnavailable
                val tokenIsStillCurrent = latest.pendingToken == token || latest.currentToken == token
                if (!tokenIsStillCurrent) return PushTokenSyncResult.NothingPending
                persistRegistration(latest, token, ownerUserId, result.token)
            }
            else -> mapFailure(result)
        }
    }

    override suspend fun revokeMonitorRegistration(
        ownerSession: AuthSessionIdentity
    ): PushTokenSyncResult = operationMutex.withLock {
        val normalizedOwnerUserId = ownerSession.userId.trim().takeIf { it.isNotEmpty() }
            ?: return PushTokenSyncResult.NoMonitorSession
        if (currentMonitorIdentity() != ownerSession) return PushTokenSyncResult.NoMonitorSession
        val state = readState() ?: return PushTokenSyncResult.StorageUnavailable
        val registrationId = state.remoteRegistrationId
            ?.takeIf { it.isNotBlank() && state.remoteRegistrationOwnerUserId == normalizedOwnerUserId }
        PushDiagnostics.debug(PushDiagnostics.logoutRevokeState(registrationId != null))
        if (registrationId == null) {
            PushDiagnostics.debug(PushDiagnostics.logoutRevokeResult("nothing_pending"))
            return PushTokenSyncResult.NothingPending
        }
        if (currentMonitorIdentity() != ownerSession) return PushTokenSyncResult.NoMonitorSession
        val outcome = when (val result = callWithRefresh { authorization ->
            remoteDataSource.revoke(authorization, registrationId)
        }) {
            is PushTokenRemoteResult.Revoked -> {
                if (!result.token.status.equals(STATUS_REVOKED, ignoreCase = true)) {
                    PushTokenSyncResult.RemoteFailure()
                } else {
                    markRevoked(normalizedOwnerUserId, registrationId, notFound = false)
                }
            }
            is PushTokenRemoteResult.NotFound -> markRevoked(
                normalizedOwnerUserId,
                registrationId,
                notFound = true
            )
            else -> mapFailure(result)
        }
        PushDiagnostics.debug(PushDiagnostics.logoutRevokeResult(outcome.diagnosticResult()))
        outcome
    }

    private suspend fun register(token: String): PushTokenRemoteResult? {
        val metadata = metadataProvider.get()
        val request = RegisterPushTokenRequestDto(
            platform = PLATFORM_ANDROID,
            channel = CHANNEL_FCM,
            token = token,
            metadata = PushTokenMetadataDto(
                appVersion = metadata.appVersion,
                osVersion = metadata.osVersion
            )
        )
        PushDiagnostics.debug(PushDiagnostics.registerStarted())
        val result = callWithRefresh { authorization -> remoteDataSource.register(authorization, request) }
        PushDiagnostics.debug(result.diagnosticRegisterResult())
        return result
    }

    private fun persistRegistration(
        state: PushTokenState,
        token: String,
        ownerUserId: String,
        remote: PushNotificationTokenDto
    ): PushTokenSyncResult {
        val registrationId = remote.id.takeIf { it.isNotBlank() }
            ?: return PushTokenSyncResult.RemoteFailure()
        val saved = store.save(
            state.copy(
                currentToken = token,
                pendingToken = null,
                remoteRegistrationId = registrationId,
                remoteRegistrationOwnerUserId = ownerUserId
            )
        )
        return if (saved is PushTokenStoreResult.Success) {
            PushDiagnostics.debug(PushDiagnostics.registrationPersisted())
            PushTokenSyncResult.Registered
        } else {
            PushDiagnostics.debug(PushDiagnostics.registrationPersistFailed())
            PushTokenSyncResult.StorageUnavailable
        }
    }

    private suspend fun callWithRefresh(
        operation: suspend (authorization: String) -> PushTokenRemoteResult
    ): PushTokenRemoteResult? {
        val firstToken = accessToken() ?: return null
        val first = operation("Bearer $firstToken")
        if (first !is PushTokenRemoteResult.HttpFailure || first.status != 401) return first
        if (authRepository.refreshSession() !is AuthResult.Success) return first
        val refreshedToken = accessToken() ?: return first
        return operation("Bearer $refreshedToken")
    }

    private suspend fun accessToken(): String? =
        when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            else -> null
        }

    private fun mapFailure(result: PushTokenRemoteResult?): PushTokenSyncResult = when (result) {
        null -> PushTokenSyncResult.AuthUnavailable
        is PushTokenRemoteResult.NotFound -> PushTokenSyncResult.NotFound
        is PushTokenRemoteResult.HttpFailure -> PushTokenSyncResult.RemoteFailure(result.status, result.errorCode)
        is PushTokenRemoteResult.InvalidResponse -> PushTokenSyncResult.RemoteFailure(errorCode = result.errorCode)
        PushTokenRemoteResult.NetworkFailure,
        PushTokenRemoteResult.Timeout -> PushTokenSyncResult.RemoteFailure()
        is PushTokenRemoteResult.Registered,
        is PushTokenRemoteResult.Listed,
        is PushTokenRemoteResult.StatusLoaded,
        is PushTokenRemoteResult.Revoked -> PushTokenSyncResult.RemoteFailure()
    }

    private fun markRevoked(
        ownerUserId: String,
        registrationId: String,
        notFound: Boolean
    ): PushTokenSyncResult {
        val latest = readState() ?: return PushTokenSyncResult.StorageUnavailable
        if (
            latest.remoteRegistrationId != registrationId ||
            latest.remoteRegistrationOwnerUserId != ownerUserId
        ) {
            return PushTokenSyncResult.NoMonitorSession
        }
        val saved = store.save(
            latest.copy(
                pendingToken = latest.currentToken,
                remoteRegistrationId = null,
                remoteRegistrationOwnerUserId = null
            )
        )
        if (saved !is PushTokenStoreResult.Success) return PushTokenSyncResult.StorageUnavailable
        return if (notFound) PushTokenSyncResult.NotFound else PushTokenSyncResult.Revoked
    }

    private fun currentMonitorIdentity(): AuthSessionIdentity? = when (val session = authRepository.observeSession().value) {
        is SessionState.Authenticated -> session.takeIf { it.user.role == UserRole.Monitor }?.authenticatedIdentityOrNull()
        is SessionState.Refreshing -> session.takeIf { it.user.role == UserRole.Monitor }?.authenticatedIdentityOrNull()
        else -> null
    }?.takeIf { it.userId.isNotBlank() }

    private fun readState(): PushTokenState? =
        (store.read() as? PushTokenStoreResult.Success)?.value

    private fun logSyncState(state: PushTokenState) {
        PushDiagnostics.debug(
            PushDiagnostics.syncState(
                pendingTokenPresent = !state.pendingToken.isNullOrBlank(),
                remoteRegistrationPresent = !state.remoteRegistrationId.isNullOrBlank()
            )
        )
    }

    private companion object {
        const val PLATFORM_ANDROID = "Android"
        const val CHANNEL_FCM = "Fcm"
        const val STATUS_REVOKED = "Revoked"
    }
}

private fun PushTokenRemoteResult?.diagnosticRegisterResult(): String = when (this) {
    is PushTokenRemoteResult.Registered -> PushDiagnostics.registerResult("success")
    is PushTokenRemoteResult.HttpFailure -> PushDiagnostics.registerResult("http_error", status, errorCode)
    is PushTokenRemoteResult.InvalidResponse -> PushDiagnostics.registerResult("invalid_response", code = errorCode)
    PushTokenRemoteResult.NetworkFailure -> PushDiagnostics.registerResult("network_error")
    PushTokenRemoteResult.Timeout -> PushDiagnostics.registerResult("timeout")
    null -> PushDiagnostics.registerResult("auth_unavailable")
    else -> PushDiagnostics.registerResult("unexpected_result")
}

private fun PushTokenSyncResult.diagnosticResult(): String = when (this) {
    PushTokenSyncResult.Registered -> "success"
    PushTokenSyncResult.NothingPending -> "nothing_pending"
    PushTokenSyncResult.NoMonitorSession -> "not_monitor"
    PushTokenSyncResult.AuthUnavailable -> "auth_unavailable"
    PushTokenSyncResult.StorageUnavailable -> "storage_failure"
    PushTokenSyncResult.Revoked -> "revoked"
    PushTokenSyncResult.NotFound -> "not_found"
    is PushTokenSyncResult.RemoteFailure -> "remote_failure"
}
