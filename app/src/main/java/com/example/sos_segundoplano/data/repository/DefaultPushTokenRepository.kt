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
        var state = readState() ?: return PushTokenSyncResult.StorageUnavailable
        var registrationId = state.remoteRegistrationId
            ?.takeIf { it.isNotBlank() && state.remoteRegistrationOwnerUserId == normalizedOwnerUserId }

        if (registrationId == null) {
            val localToken = state.pendingToken?.takeIf { it.isNotBlank() }
                ?: state.currentToken?.takeIf { it.isNotBlank() }
                ?: return PushTokenSyncResult.NothingPending
            when (val recovered = register(localToken)) {
                is PushTokenRemoteResult.Registered -> {
                    registrationId = recovered.token.id.takeIf { it.isNotBlank() }
                        ?: return PushTokenSyncResult.RemoteFailure()
                    state = state.copy(
                        currentToken = localToken,
                        pendingToken = null,
                        remoteRegistrationId = registrationId,
                        remoteRegistrationOwnerUserId = normalizedOwnerUserId
                    )
                    if (store.save(state) !is PushTokenStoreResult.Success) {
                        return PushTokenSyncResult.StorageUnavailable
                    }
                }
                else -> return mapFailure(recovered)
            }
        }

        val resolvedRegistrationId = registrationId ?: return PushTokenSyncResult.NothingPending
        if (currentMonitorIdentity() != ownerSession) return PushTokenSyncResult.NoMonitorSession
        when (val result = callWithRefresh { authorization ->
            remoteDataSource.revoke(authorization, resolvedRegistrationId)
        }) {
            is PushTokenRemoteResult.Revoked -> {
                if (!result.token.status.equals(STATUS_REVOKED, ignoreCase = true)) {
                    PushTokenSyncResult.RemoteFailure()
                } else {
                    markRevoked(normalizedOwnerUserId, resolvedRegistrationId, notFound = false)
                }
            }
            is PushTokenRemoteResult.NotFound -> markRevoked(
                normalizedOwnerUserId,
                resolvedRegistrationId,
                notFound = true
            )
            else -> mapFailure(result)
        }
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
        return callWithRefresh { authorization -> remoteDataSource.register(authorization, request) }
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
            PushTokenSyncResult.Registered
        } else {
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

    private companion object {
        const val PLATFORM_ANDROID = "Android"
        const val CHANNEL_FCM = "Fcm"
        const val STATUS_REVOKED = "Revoked"
    }
}
