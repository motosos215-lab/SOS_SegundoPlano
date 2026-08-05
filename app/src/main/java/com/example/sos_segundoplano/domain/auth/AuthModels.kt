package com.example.sos_segundoplano.domain.auth

import java.time.Instant

enum class UserRole {
    Rider,
    Monitor,
    Unknown;

    companion object {
        fun fromApiValue(value: String?): UserRole = when {
            value == null -> Unknown
            value.equals("Rider", ignoreCase = true) -> Rider
            value.equals("Monitor", ignoreCase = true) -> Monitor
            else -> Unknown
        }
    }
}

data class AuthUser(
    val id: String,
    val email: String,
    val fullName: String,
    val phoneNumber: String,
    val role: UserRole,
    val isActive: Boolean
)

class SessionSecrets(accessToken: String, refreshToken: String) {
    private val accessTokenValue = accessToken
    private val refreshTokenValue = refreshToken

    init {
        require(accessToken.isNotBlank())
        require(refreshToken.isNotBlank())
    }

    internal fun accessToken(): String = accessTokenValue
    internal fun refreshToken(): String = refreshTokenValue

    override fun toString(): String = "SessionSecrets([REDACTED])"
}

class AuthSession(
    internal val secrets: SessionSecrets,
    val accessTokenExpiresAt: Instant,
    val user: AuthUser,
    val rememberMe: Boolean
) {
    override fun toString(): String =
        "AuthSession(userId=${user.id}, role=${user.role}, accessTokenExpiresAt=$accessTokenExpiresAt, rememberMe=$rememberMe)"
}

class AccessToken internal constructor(token: String) {
    private val value = token

    internal fun reveal(): String = value

    override fun toString(): String = "AccessToken([REDACTED])"
}

fun interface AuthClock {
    fun now(): Instant
}

object SystemAuthClock : AuthClock {
    override fun now(): Instant = Instant.now()
}
