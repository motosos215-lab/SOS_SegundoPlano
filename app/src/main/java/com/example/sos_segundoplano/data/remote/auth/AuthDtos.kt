package com.example.sos_segundoplano.data.remote.auth

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class ApiEnvelopeDto<T>(
    val success: Boolean,
    val data: T?,
    val error: ApiErrorDto?
)

@JsonClass(generateAdapter = false)
data class ApiErrorDto(
    val code: String?,
    val message: String?
)

@JsonClass(generateAdapter = false)
data class LoginRequestDto(
    val email: String,
    val password: String,
    val rememberMe: Boolean
) {
    override fun toString(): String = "LoginRequestDto(email=[REDACTED], password=[REDACTED], rememberMe=$rememberMe)"
}

@JsonClass(generateAdapter = false)
data class LoginDataDto(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAtUtc: String,
    val user: AuthUserDto
) {
    override fun toString(): String = "LoginDataDto([REDACTED], userRole=${user.role})"
}

@JsonClass(generateAdapter = false)
data class RefreshTokenRequestDto(val refreshToken: String) {
    override fun toString(): String = "RefreshTokenRequestDto([REDACTED])"
}

@JsonClass(generateAdapter = false)
data class RefreshDataDto(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAtUtc: String
) {
    override fun toString(): String = "RefreshDataDto([REDACTED])"
}

@JsonClass(generateAdapter = false)
data class LogoutRequestDto(val refreshToken: String) {
    override fun toString(): String = "LogoutRequestDto([REDACTED])"
}

@JsonClass(generateAdapter = false)
data class AuthUserDto(
    val id: String,
    val email: String,
    val fullName: String,
    val phoneNumber: String,
    val role: String,
    val isActive: Boolean
)
