package com.example.sos_segundoplano.data.remote.auth

import com.example.sos_segundoplano.domain.auth.ClientDeviceInfo
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class ApiEnvelopeDto<T>(
    val success: Boolean,
    val data: T?,
    val error: ApiErrorDto?
)

@JsonClass(generateAdapter = false)
data class ApiErrorDto(val code: String?, val message: String?)

@JsonClass(generateAdapter = false)
data class ClientDeviceDto(
    val clientDeviceId: String,
    val deviceName: String,
    val platform: String,
    val osVersion: String,
    val appVersion: String
)

fun ClientDeviceInfo.toDto(): ClientDeviceDto = ClientDeviceDto(
    clientDeviceId = clientDeviceId,
    deviceName = deviceName,
    platform = platform,
    osVersion = osVersion,
    appVersion = appVersion
)

@JsonClass(generateAdapter = false)
data class LoginRequestDto(
    val email: String,
    val password: String,
    val rememberMe: Boolean,
    val clientDevice: ClientDeviceDto? = null
) {
    override fun toString(): String =
        "LoginRequestDto(email=[REDACTED], password=[REDACTED], rememberMe=$rememberMe, clientDevice=$clientDevice)"
}

@JsonClass(generateAdapter = false)
data class LoginWithCodeRequestDto(
    val email: String,
    val code: String,
    val clientDevice: ClientDeviceDto
) {
    override fun toString(): String =
        "LoginWithCodeRequestDto(email=[REDACTED], code=[REDACTED], clientDevice=$clientDevice)"
}

@JsonClass(generateAdapter = false)
data class UserSessionDto(
    val id: String? = null,
    val deviceName: String? = null,
    val platform: String? = null,
    val createdAtUtc: String? = null,
    val lastSeenAtUtc: String? = null
)

@JsonClass(generateAdapter = false)
data class LoginDataDto(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAtUtc: String? = null,
    val user: AuthUserDto? = null,
    val expiresAtUtc: String? = null,
    val session: UserSessionDto? = null,
    val activeTrip: ActiveTripConflictDto? = null
) {
    fun expirationUtc(): String? = expiresAtUtc?.takeIf { it.isNotBlank() }
        ?: accessTokenExpiresAtUtc?.takeIf { it.isNotBlank() }

    override fun toString(): String = "LoginDataDto([REDACTED], userRole=${user?.role}, sessionId=${session?.id})"
}

@JsonClass(generateAdapter = false)
data class CurrentUserDataDto(val user: AuthUserDto? = null)

@JsonClass(generateAdapter = false)
data class ActiveSessionConflictDto(
    val id: String? = null,
    val sessionId: String? = null,
    val deviceName: String? = null,
    val platform: String? = null,
    val lastSeenAtUtc: String? = null
)

@JsonClass(generateAdapter = false)
data class ActiveTripConflictDto(
    val id: String? = null,
    val status: String? = null,
    val startedAtUtc: String? = null,
    val mobileDeviceId: String? = null
)

@JsonClass(generateAdapter = false)
data class ActiveSessionConflictDataDto(
    val activeSession: ActiveSessionConflictDto? = null,
    val takeoverToken: String? = null,
    val takeoverExpiresAtUtc: String? = null,
    val hasActiveTrip: Boolean? = null,
    val activeTrip: ActiveTripConflictDto? = null
)

@JsonClass(generateAdapter = false)
data class SessionTakeoverRequestDto(
    val takeoverToken: String,
    val clientDevice: ClientDeviceDto,
    val transferActiveTrip: Boolean,
    val mobileDeviceId: String?
) {
    override fun toString(): String =
        "SessionTakeoverRequestDto(takeoverToken=[REDACTED], clientDevice=$clientDevice, " +
            "transferActiveTrip=$transferActiveTrip, mobileDeviceId=${if (mobileDeviceId == null) "null" else "[REDACTED]"})"
}

@JsonClass(generateAdapter = false)
data class RefreshTokenRequestDto(val refreshToken: String) {
    override fun toString(): String = "RefreshTokenRequestDto([REDACTED])"
}

@JsonClass(generateAdapter = false)
data class RefreshDataDto(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAtUtc: String? = null,
    val expiresAtUtc: String? = null
) {
    fun expirationUtc(): String? = expiresAtUtc?.takeIf { it.isNotBlank() }
        ?: accessTokenExpiresAtUtc?.takeIf { it.isNotBlank() }
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
