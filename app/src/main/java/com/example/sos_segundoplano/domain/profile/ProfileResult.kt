package com.example.sos_segundoplano.domain.profile

sealed interface ProfileResult<out T> {
    data class Success<T>(val value: T) : ProfileResult<T>
}

sealed interface ProfileFailure : ProfileResult<Nothing>

data object ProfileUnauthorized : ProfileFailure
data object ProfileSessionExpired : ProfileFailure
data object ProfileAccessDenied : ProfileFailure
data object ProfileInactiveAccount : ProfileFailure
data object ProfileAccountUnavailable : ProfileFailure
data object ProfileRateLimited : ProfileFailure
data object ProfileNetworkUnavailable : ProfileFailure
data object ProfileTimeout : ProfileFailure
data object ProfileServerFailure : ProfileFailure
data object ProfileInvalidResponse : ProfileFailure
data object ProfileStorageFailure : ProfileFailure
data object ProfileUnknownFailure : ProfileFailure
