package com.example.sos_segundoplano.domain.auth

enum class SessionStorageFailureReason {
    Unavailable,
    Corrupt,
    Tampered,
    Serialization
}

sealed interface SessionStoreResult<out T> {
    data class Success<T>(val value: T) : SessionStoreResult<T>
    data object Empty : SessionStoreResult<Nothing>
    data class Failure(val reason: SessionStorageFailureReason) : SessionStoreResult<Nothing>
}

interface SessionStore {
    suspend fun save(session: AuthSession): SessionStoreResult<Unit>
    suspend fun read(): SessionStoreResult<AuthSession>
    suspend fun clear(): SessionStoreResult<Unit>
}
