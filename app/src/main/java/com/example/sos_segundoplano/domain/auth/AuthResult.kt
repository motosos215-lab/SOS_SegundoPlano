package com.example.sos_segundoplano.domain.auth

sealed interface AuthResult<out T> {
    data class Success<T>(val value: T) : AuthResult<T>
}

sealed interface AuthFailure : AuthResult<Nothing>

data class InvalidCredentials(
    val httpStatus: Int?,
    val errorCode: String?,
    val sanitizedMessage: String?
) : AuthFailure

data class Unauthorized(
    val httpStatus: Int?,
    val errorCode: String?,
    val sanitizedMessage: String?
) : AuthFailure

data class AccessDenied(val role: UserRole) : AuthFailure
data object InactiveAccount : AuthFailure

data class RateLimited(
    val retryAfter: String?,
    val httpStatus: Int = 429,
    val errorCode: String? = null,
    val sanitizedMessage: String? = null
) : AuthFailure

data class NetworkUnavailable(val sanitizedMessage: String? = null) : AuthFailure
data class Timeout(val sanitizedMessage: String? = null) : AuthFailure

data class ServerFailure(
    val httpStatus: Int,
    val errorCode: String?,
    val sanitizedMessage: String?
) : AuthFailure

data class InvalidResponse(
    val httpStatus: Int? = null,
    val errorCode: String? = null,
    val sanitizedMessage: String? = null
) : AuthFailure

data class StorageFailure(val reason: SessionStorageFailureReason) : AuthFailure
data object SessionExpired : AuthFailure

data class RemoteLogoutFailed(
    val httpStatus: Int?,
    val errorCode: String?,
    val sanitizedMessage: String?
) : AuthFailure
