package com.example.sos_segundoplano.domain.auth

import java.time.Instant

sealed interface SessionState {
    data object LoggedOut : SessionState
    data object Restoring : SessionState

    data class Authenticated(
        val user: AuthUser,
        val accessTokenExpiresAt: Instant,
        val rememberMe: Boolean,
        val generation: Long = 0L
    ) : SessionState

    data class Refreshing(
        val user: AuthUser,
        val accessTokenExpiresAt: Instant,
        val rememberMe: Boolean,
        val generation: Long = 0L
    ) : SessionState

    data object Expired : SessionState
    data class AccessDenied(val role: UserRole) : SessionState
    data object InactiveAccount : SessionState
    data object StorageUnavailable : SessionState
}

data class AuthSessionIdentity(
    val userId: String,
    val generation: Long
)

fun SessionState.authenticatedIdentityOrNull(): AuthSessionIdentity? = when (this) {
    is SessionState.Authenticated -> AuthSessionIdentity(user.id, generation)
    is SessionState.Refreshing -> AuthSessionIdentity(user.id, generation)
    else -> null
}
