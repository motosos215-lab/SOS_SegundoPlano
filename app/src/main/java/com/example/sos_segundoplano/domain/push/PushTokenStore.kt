package com.example.sos_segundoplano.domain.push

data class PushTokenState(
    val currentToken: String? = null,
    val pendingToken: String? = null,
    val remoteRegistrationId: String? = null
) {
    override fun toString(): String =
        "PushTokenState(currentToken=[REDACTED], pendingToken=[REDACTED], " +
            "remoteRegistrationId=${if (remoteRegistrationId == null) "null" else "[REDACTED]"})"
}

sealed interface PushTokenStoreResult<out T> {
    data class Success<T>(val value: T) : PushTokenStoreResult<T>
    data object Failure : PushTokenStoreResult<Nothing>
}

interface PushTokenStore {
    fun read(): PushTokenStoreResult<PushTokenState>
    fun save(state: PushTokenState): PushTokenStoreResult<Unit>
}

class PushTokenCoordinator(private val store: PushTokenStore) {
    fun recordToken(token: String): PushTokenStoreResult<Unit> {
        if (token.isBlank()) return PushTokenStoreResult.Failure
        val previous = when (val result = store.read()) {
            is PushTokenStoreResult.Success -> result.value
            PushTokenStoreResult.Failure -> PushTokenState()
        }
        return store.save(
            previous.copy(
                currentToken = token,
                pendingToken = token,
                remoteRegistrationId = null
            )
        )
    }

    fun state(): PushTokenStoreResult<PushTokenState> = store.read()

    internal fun store(): PushTokenStore = store
}

class PushTokenHandler(private val coordinator: PushTokenCoordinator) {
    fun onNewToken(token: String): PushTokenStoreResult<Unit> = coordinator.recordToken(token)
}
