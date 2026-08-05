package com.example.sos_segundoplano.data.repository

import com.example.sos_segundoplano.data.remote.auth.AuthRemoteDataSource
import com.example.sos_segundoplano.data.remote.auth.AuthUserDto
import com.example.sos_segundoplano.data.remote.auth.LoginDataDto
import com.example.sos_segundoplano.data.remote.auth.LoginRequestDto
import com.example.sos_segundoplano.data.remote.auth.LogoutRequestDto
import com.example.sos_segundoplano.data.remote.auth.RefreshDataDto
import com.example.sos_segundoplano.data.remote.auth.RefreshTokenRequestDto
import com.example.sos_segundoplano.domain.auth.AccessDenied
import com.example.sos_segundoplano.domain.auth.AccessToken
import com.example.sos_segundoplano.domain.auth.AuthClock
import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.AuthSession
import com.example.sos_segundoplano.domain.auth.AuthUser
import com.example.sos_segundoplano.domain.auth.InactiveAccount
import com.example.sos_segundoplano.domain.auth.InvalidCredentials
import com.example.sos_segundoplano.domain.auth.InvalidResponse
import com.example.sos_segundoplano.domain.auth.RateLimited
import com.example.sos_segundoplano.domain.auth.RemoteLogoutFailed
import com.example.sos_segundoplano.domain.auth.ServerFailure
import com.example.sos_segundoplano.domain.auth.SessionExpired
import com.example.sos_segundoplano.domain.auth.SessionSecrets
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.auth.SessionStorageFailureReason
import com.example.sos_segundoplano.domain.auth.SessionStore
import com.example.sos_segundoplano.domain.auth.SessionStoreResult
import com.example.sos_segundoplano.domain.auth.StorageFailure
import com.example.sos_segundoplano.domain.auth.Unauthorized
import com.example.sos_segundoplano.domain.auth.UserRole
import com.example.sos_segundoplano.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

class DefaultAuthRepository(
    private val remoteDataSource: AuthRemoteDataSource,
    private val sessionStore: SessionStore,
    private val clock: AuthClock,
    private val expirationMargin: Duration = DEFAULT_EXPIRATION_MARGIN
) : AuthRepository {
    private val refreshMutex = Mutex()
    private val sessionMutationMutex = Mutex()
    private val mutableSessionState = MutableStateFlow<SessionState>(SessionState.LoggedOut)

    @Volatile
    private var currentSession: AuthSession? = null
    private var sessionGeneration: Long = 0L

    init {
        require(!expirationMargin.isNegative)
    }

    override fun observeSession(): StateFlow<SessionState> = mutableSessionState.asStateFlow()

    override suspend fun login(
        email: String,
        password: String,
        rememberMe: Boolean
    ): AuthResult<AuthUser> {
        val loginGeneration = sessionMutationMutex.withLock { ++sessionGeneration }
        val request = LoginRequestDto(
            email = email.trim(),
            password = password,
            rememberMe = rememberMe
        )
        return when (val remoteResult = remoteDataSource.login(request)) {
            is AuthResult.Success -> when (val validated = validateLoginData(remoteResult.value, rememberMe)) {
                is AuthResult.Success -> establishSession(validated.value, loginGeneration)
                is AuthFailure -> clearRejectedLoginPayload(validated, loginGeneration)
            }
            is AuthFailure -> finishFailedLogin(remoteResult, loginGeneration)
        }
    }

    override suspend fun restoreSession(): AuthResult<AuthUser?> {
        val restoreGeneration = sessionMutationMutex.withLock {
            if (currentSession == null) mutableSessionState.value = SessionState.Restoring
            sessionGeneration
        }
        val stored = sessionStore.read()
        val decision = sessionMutationMutex.withLock {
            currentSession?.let { return@withLock RestoreDecision.Completed(AuthResult.Success(it.user)) }
            if (sessionGeneration != restoreGeneration) {
                return@withLock RestoreDecision.Completed(AuthResult.Success(null))
            }
            when (stored) {
                SessionStoreResult.Empty -> {
                    mutableSessionState.value = SessionState.LoggedOut
                    RestoreDecision.Completed(AuthResult.Success(null))
                }
                is SessionStoreResult.Failure -> {
                    mutableSessionState.value = SessionState.StorageUnavailable
                    RestoreDecision.Completed(StorageFailure(stored.reason))
                }
                is SessionStoreResult.Success -> processStoredSessionLocked(stored.value)
            }
        }
        return when (decision) {
            is RestoreDecision.Completed -> decision.result
            is RestoreDecision.Refresh -> refreshWithLock(decision.session)
        }
    }

    override suspend fun ensureValidAccessToken(): AuthResult<AccessToken> {
        val snapshot = currentSession ?: return SessionExpired
        if (!isNearExpiry(snapshot.accessTokenExpiresAt)) {
            return AuthResult.Success(AccessToken(snapshot.secrets.accessToken()))
        }
        return when (val refreshed = refreshWithLock(snapshot)) {
            is AuthResult.Success -> {
                val session = currentSession ?: return SessionExpired
                AuthResult.Success(AccessToken(session.secrets.accessToken()))
            }
            is AuthFailure -> refreshed
        }
    }

    override suspend fun refreshSession(): AuthResult<AuthUser> {
        val snapshot = currentSession ?: return SessionExpired
        return refreshWithLock(snapshot)
    }

    override suspend fun logout(): AuthResult<Unit> {
        val localLogout = withContext(NonCancellable) {
            sessionMutationMutex.withLock {
                sessionGeneration++
                val session = currentSession
                currentSession = null
                val clearResult = sessionStore.clear()
                val storageFailure = (clearResult as? SessionStoreResult.Failure)?.reason
                mutableSessionState.value = if (storageFailure == null) {
                    SessionState.LoggedOut
                } else {
                    SessionState.StorageUnavailable
                }
                LocalLogout(session, storageFailure)
            }
        }
        val remoteResult = localLogout.session?.let {
            remoteDataSource.logout(LogoutRequestDto(it.secrets.refreshToken()))
        } ?: AuthResult.Success(Unit)
        localLogout.storageFailure?.let { return StorageFailure(it) }
        return when (remoteResult) {
            is AuthResult.Success -> AuthResult.Success(Unit)
            is AuthFailure -> remoteResult.toRemoteLogoutFailure()
        }
    }

    private suspend fun processStoredSessionLocked(session: AuthSession): RestoreDecision {
        if (!session.rememberMe) {
            return RestoreDecision.Completed(
                clearRestoredSessionLocked(SessionState.LoggedOut, AuthResult.Success(null))
            )
        }
        when (val userFailure = validateDomainUser(session.user)) {
            null -> Unit
            InactiveAccount -> return RestoreDecision.Completed(
                clearRestoredSessionLocked(SessionState.InactiveAccount, InactiveAccount)
            )
            is AccessDenied -> return RestoreDecision.Completed(
                clearRestoredSessionLocked(SessionState.AccessDenied(userFailure.role), userFailure)
            )
            else -> return RestoreDecision.Completed(
                clearRestoredSessionLocked(
                    SessionState.StorageUnavailable,
                    StorageFailure(SessionStorageFailureReason.Corrupt)
                )
            )
        }

        currentSession = session
        mutableSessionState.value = session.toAuthenticatedState()
        return if (isNearExpiry(session.accessTokenExpiresAt)) {
            RestoreDecision.Refresh(session)
        } else {
            RestoreDecision.Completed(AuthResult.Success(session.user))
        }
    }

    private suspend fun refreshWithLock(snapshot: AuthSession): AuthResult<AuthUser> = refreshMutex.withLock refreshLock@{
        val preparation = sessionMutationMutex.withLock preparationLock@{
            val latest = currentSession ?: return@preparationLock RefreshPreparation.Missing
            if (hasBeenRefreshed(snapshot, latest)) {
                return@preparationLock RefreshPreparation.AlreadyRefreshed(latest.user)
            }
            mutableSessionState.value = SessionState.Refreshing(
                latest.user,
                latest.accessTokenExpiresAt,
                latest.rememberMe
            )
            RefreshPreparation.Ready(latest)
        }
        val latest = when (preparation) {
            RefreshPreparation.Missing -> return@refreshLock SessionExpired
            is RefreshPreparation.AlreadyRefreshed -> return@refreshLock AuthResult.Success(preparation.user)
            is RefreshPreparation.Ready -> preparation.session
        }
        val remoteResult = remoteDataSource.refresh(
            RefreshTokenRequestDto(latest.secrets.refreshToken())
        )
        when (remoteResult) {
            is AuthResult.Success -> sessionMutationMutex.withLock applyLock@{
                if (currentSession !== latest) return@applyLock SessionExpired
                applyRefreshLocked(latest, remoteResult.value)
            }
            is Unauthorized,
            is InvalidCredentials -> sessionMutationMutex.withLock unauthorizedLock@{
                if (currentSession !== latest) return@unauthorizedLock SessionExpired
                expireAndReturnLocked(SessionExpired)
            }
            is InvalidResponse -> sessionMutationMutex.withLock invalidLock@{
                if (currentSession !== latest) return@invalidLock SessionExpired
                expireAndReturnLocked(remoteResult)
            }
            is AuthFailure -> sessionMutationMutex.withLock {
                if (currentSession === latest) {
                    mutableSessionState.value = latest.toAuthenticatedState()
                }
                remoteResult
            }
        }
    }

    private suspend fun applyRefreshLocked(
        previous: AuthSession,
        data: RefreshDataDto
    ): AuthResult<AuthUser> {
        val accessToken = data.accessToken.takeIf { it.isNotBlank() }
            ?: return expireAndReturnLocked(InvalidResponse(sanitizedMessage = "access_token_missing"))
        val refreshToken = data.refreshToken.takeIf { it.isNotBlank() }
            ?: return expireAndReturnLocked(InvalidResponse(sanitizedMessage = "refresh_token_missing"))
        val expiresAt = parseFutureInstant(data.accessTokenExpiresAtUtc)
            ?: return expireAndReturnLocked(InvalidResponse(sanitizedMessage = "access_token_expiration_invalid"))
        val refreshed = AuthSession(
            secrets = SessionSecrets(accessToken, refreshToken),
            accessTokenExpiresAt = expiresAt,
            user = previous.user,
            rememberMe = previous.rememberMe
        )

        if (refreshed.rememberMe) {
            when (val stored = sessionStore.save(refreshed)) {
                is SessionStoreResult.Success -> Unit
                SessionStoreResult.Empty -> return failRefreshPersistenceLocked(SessionStorageFailureReason.Unavailable)
                is SessionStoreResult.Failure -> return failRefreshPersistenceLocked(stored.reason)
            }
        }

        currentSession = refreshed
        mutableSessionState.value = refreshed.toAuthenticatedState()
        return AuthResult.Success(refreshed.user)
    }

    private suspend fun establishSession(
        session: AuthSession,
        expectedGeneration: Long
    ): AuthResult<AuthUser> = sessionMutationMutex.withLock {
        if (sessionGeneration != expectedGeneration) return@withLock SessionExpired
        val storageResult = if (session.rememberMe) {
            sessionStore.save(session)
        } else {
            sessionStore.clear()
        }
        if (storageResult is SessionStoreResult.Failure || storageResult === SessionStoreResult.Empty) {
            currentSession = null
            mutableSessionState.value = SessionState.StorageUnavailable
            val reason = (storageResult as? SessionStoreResult.Failure)?.reason
                ?: SessionStorageFailureReason.Unavailable
            return@withLock StorageFailure(reason)
        }
        currentSession = session
        mutableSessionState.value = session.toAuthenticatedState()
        AuthResult.Success(session.user)
    }

    private fun validateLoginData(data: LoginDataDto, rememberMe: Boolean): AuthResult<AuthSession> {
        if (data.accessToken.isBlank()) return InvalidResponse(sanitizedMessage = "access_token_missing")
        if (data.refreshToken.isBlank()) return InvalidResponse(sanitizedMessage = "refresh_token_missing")
        val expiresAt = parseFutureInstant(data.accessTokenExpiresAtUtc)
            ?: return InvalidResponse(sanitizedMessage = "access_token_expiration_invalid")
        val user = data.user.toDomain()
            ?: return InvalidResponse(sanitizedMessage = "auth_user_invalid")
        validateDomainUser(user)?.let { return it }
        return AuthResult.Success(
            AuthSession(
                secrets = SessionSecrets(data.accessToken, data.refreshToken),
                accessTokenExpiresAt = expiresAt,
                user = user,
                rememberMe = rememberMe
            )
        )
    }

    private fun AuthUserDto.toDomain(): AuthUser? {
        val normalizedId = id.trim()
        val normalizedEmail = email.trim()
        val normalizedFullName = fullName.trim()
        val normalizedPhone = phoneNumber.trim()
        if (normalizedId.isEmpty() || !EMAIL_PATTERN.matches(normalizedEmail)) return null
        if (normalizedFullName.isEmpty() || normalizedPhone.isEmpty()) return null
        return AuthUser(
            id = normalizedId,
            email = normalizedEmail,
            fullName = normalizedFullName,
            phoneNumber = normalizedPhone,
            role = UserRole.fromApiValue(role),
            isActive = isActive
        )
    }

    private fun validateDomainUser(user: AuthUser): AuthFailure? {
        if (user.id.isBlank() || !EMAIL_PATTERN.matches(user.email)) {
            return InvalidResponse(sanitizedMessage = "auth_user_invalid")
        }
        if (user.fullName.isBlank() || user.phoneNumber.isBlank()) {
            return InvalidResponse(sanitizedMessage = "auth_user_incomplete")
        }
        if (!user.isActive) return InactiveAccount
        if (user.role != UserRole.Rider) return AccessDenied(user.role)
        return null
    }

    private fun parseFutureInstant(value: String): Instant? = try {
        OffsetDateTime.parse(value).toInstant().takeIf { it.isAfter(clock.now()) }
    } catch (_: DateTimeException) {
        null
    }

    private fun isNearExpiry(expiration: Instant): Boolean =
        !expiration.isAfter(clock.now().plus(expirationMargin))

    private fun hasBeenRefreshed(snapshot: AuthSession, latest: AuthSession): Boolean =
        snapshot.secrets.refreshToken() != latest.secrets.refreshToken() ||
            snapshot.accessTokenExpiresAt != latest.accessTokenExpiresAt

    private suspend fun <T> clearRejectedLoginPayload(
        failure: AuthFailure,
        expectedGeneration: Long
    ): AuthResult<T> = sessionMutationMutex.withLock {
        if (sessionGeneration != expectedGeneration) return@withLock SessionExpired
        val state = when (failure) {
            InactiveAccount -> SessionState.InactiveAccount
            is AccessDenied -> SessionState.AccessDenied(failure.role)
            else -> SessionState.LoggedOut
        }
        clearRestoredSessionLocked(state, failure)
    }

    private suspend fun <T> finishFailedLogin(
        failure: AuthFailure,
        expectedGeneration: Long
    ): AuthResult<T> = sessionMutationMutex.withLock {
        if (
            sessionGeneration == expectedGeneration &&
            currentSession == null &&
            mutableSessionState.value is SessionState.Restoring
        ) {
            mutableSessionState.value = SessionState.LoggedOut
        }
        failure
    }

    private suspend fun <T> clearRestoredSessionLocked(
        targetState: SessionState,
        result: AuthResult<T>
    ): AuthResult<T> {
        currentSession = null
        val cleared = sessionStore.clear()
        if (cleared is SessionStoreResult.Failure) {
            mutableSessionState.value = SessionState.StorageUnavailable
            return StorageFailure(cleared.reason)
        }
        mutableSessionState.value = targetState
        return result
    }

    private suspend fun <T> expireAndReturnLocked(result: AuthResult<T>): AuthResult<T> {
        currentSession = null
        val cleared = sessionStore.clear()
        if (cleared is SessionStoreResult.Failure) {
            mutableSessionState.value = SessionState.StorageUnavailable
            return StorageFailure(cleared.reason)
        }
        mutableSessionState.value = SessionState.Expired
        return result
    }

    private suspend fun failRefreshPersistenceLocked(reason: SessionStorageFailureReason): AuthResult<AuthUser> {
        currentSession = null
        val cleared = sessionStore.clear()
        mutableSessionState.value = SessionState.StorageUnavailable
        return if (cleared is SessionStoreResult.Failure) {
            StorageFailure(cleared.reason)
        } else {
            StorageFailure(reason)
        }
    }

    private fun AuthSession.toAuthenticatedState(): SessionState.Authenticated = SessionState.Authenticated(
        user = user,
        accessTokenExpiresAt = accessTokenExpiresAt,
        rememberMe = rememberMe
    )

    private fun AuthFailure.toRemoteLogoutFailure(): RemoteLogoutFailed = when (this) {
        is InvalidCredentials -> RemoteLogoutFailed(httpStatus, errorCode, sanitizedMessage)
        is Unauthorized -> RemoteLogoutFailed(httpStatus, errorCode, sanitizedMessage)
        is RateLimited -> RemoteLogoutFailed(httpStatus, errorCode, sanitizedMessage)
        is ServerFailure -> RemoteLogoutFailed(httpStatus, errorCode, sanitizedMessage)
        is InvalidResponse -> RemoteLogoutFailed(httpStatus, errorCode, sanitizedMessage)
        else -> RemoteLogoutFailed(null, null, "remote_logout_failed")
    }

    companion object {
        val DEFAULT_EXPIRATION_MARGIN: Duration = Duration.ofSeconds(60)
        private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }

    private sealed interface RestoreDecision {
        data class Completed(val result: AuthResult<AuthUser?>) : RestoreDecision
        data class Refresh(val session: AuthSession) : RestoreDecision
    }

    private sealed interface RefreshPreparation {
        data object Missing : RefreshPreparation
        data class AlreadyRefreshed(val user: AuthUser) : RefreshPreparation
        data class Ready(val session: AuthSession) : RefreshPreparation
    }

    private data class LocalLogout(
        val session: AuthSession?,
        val storageFailure: SessionStorageFailureReason?
    )
}
