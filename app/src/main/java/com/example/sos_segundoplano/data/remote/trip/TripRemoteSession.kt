package com.example.sos_segundoplano.data.remote.trip

import com.example.sos_segundoplano.domain.auth.AuthFailure
import com.example.sos_segundoplano.domain.auth.AuthResult
import com.example.sos_segundoplano.domain.auth.InvalidResponse
import com.example.sos_segundoplano.domain.auth.NetworkUnavailable
import com.example.sos_segundoplano.domain.auth.Timeout
import com.example.sos_segundoplano.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface RemoteTripSessionStore {
    val remoteTripId: StateFlow<String?>
    fun setRemoteTripId(remoteTripId: String)
    fun clearRemoteTripId()
}

class InMemoryRemoteTripSessionStore : RemoteTripSessionStore {
    private val mutableRemoteTripId = MutableStateFlow<String?>(null)
    override val remoteTripId: StateFlow<String?> = mutableRemoteTripId

    override fun setRemoteTripId(remoteTripId: String) {
        mutableRemoteTripId.value = remoteTripId.takeIf { it.isNotBlank() }
    }

    override fun clearRemoteTripId() {
        mutableRemoteTripId.value = null
    }
}

fun interface ActiveTripRemoteResolver {
    suspend fun resolveActiveTrip(): ActiveTripLookupResult
}

class TripRemoteSessionReconciler(
    private val authRepository: AuthRepository,
    private val remoteDataSource: TripRemoteDataSource,
    private val store: RemoteTripSessionStore,
    private val logger: TripRemoteSessionLogger = NoOpTripRemoteSessionLogger
) : ActiveTripRemoteResolver {
    private val mutex = Mutex()

    override suspend fun resolveActiveTrip(): ActiveTripLookupResult = mutex.withLock {
        store.remoteTripId.value?.takeIf { it.isNotBlank() }?.let { remoteTripId ->
            logger.remoteTripIdAvailable()
            return@withLock ActiveTripLookupResult.Found(remoteTripId)
        }
        val token = when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            is AuthFailure -> return@withLock result.toTripLookupResult().also { logger.tripLookupFailed() }
        }
        when (val lookup = remoteDataSource.activeTrip("Bearer $token")) {
            is ActiveTripLookupResult.Found -> {
                store.setRemoteTripId(lookup.remoteTripId)
                logger.activeRemoteTripFound()
                logger.remoteTripIdAvailable()
                lookup
            }
            ActiveTripLookupResult.NoActiveTrip -> {
                store.clearRemoteTripId()
                logger.noActiveRemoteTrip()
                lookup
            }
            is ActiveTripLookupResult.HttpError,
            is ActiveTripLookupResult.NetworkUnavailable,
            is ActiveTripLookupResult.Timeout,
            is ActiveTripLookupResult.InvalidResponse -> lookup.also { logger.tripLookupFailed() }
        }
    }

    private fun AuthFailure.toTripLookupResult(): ActiveTripLookupResult = when (this) {
        is NetworkUnavailable -> ActiveTripLookupResult.NetworkUnavailable(sanitizedMessage ?: "network_unavailable")
        is Timeout -> ActiveTripLookupResult.Timeout(sanitizedMessage ?: "network_timeout")
        is InvalidResponse -> ActiveTripLookupResult.InvalidResponse(sanitizedMessage ?: "access_token_invalid")
        else -> ActiveTripLookupResult.HttpError(401, "access_token_unavailable")
    }
}

interface TripRemoteSessionLogger {
    fun activeRemoteTripFound()
    fun remoteTripIdAvailable()
    fun noActiveRemoteTrip()
    fun tripLookupFailed()
}

object NoOpTripRemoteSessionLogger : TripRemoteSessionLogger {
    override fun activeRemoteTripFound() = Unit
    override fun remoteTripIdAvailable() = Unit
    override fun noActiveRemoteTrip() = Unit
    override fun tripLookupFailed() = Unit
}
