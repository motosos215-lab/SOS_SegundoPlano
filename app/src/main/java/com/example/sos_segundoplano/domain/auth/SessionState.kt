package com.example.sos_segundoplano.domain.auth

import java.time.Instant

sealed interface SessionState {
    data object LoggedOut : SessionState
    data object Restoring : SessionState

    data class Authenticated(
        val user: AuthUser,
        val accessTokenExpiresAt: Instant,
        val rememberMe: Boolean
    ) : SessionState

    data class Refreshing(
        val user: AuthUser,
        val accessTokenExpiresAt: Instant,
        val rememberMe: Boolean
    ) : SessionState

    data object Expired : SessionState
    data class AccessDenied(val role: UserRole) : SessionState
    data object InactiveAccount : SessionState
    data object StorageUnavailable : SessionState
}
