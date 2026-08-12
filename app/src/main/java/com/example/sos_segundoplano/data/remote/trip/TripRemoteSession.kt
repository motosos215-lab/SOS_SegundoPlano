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
    fun setRemoteTripId(remoteTripId: String): Boolean
    fun clearRemoteTripId(): Boolean
}

class InMemoryRemoteTripSessionStore : RemoteTripSessionStore {
    private val mutableRemoteTripId = MutableStateFlow<String?>(null)
    override val remoteTripId: StateFlow<String?> = mutableRemoteTripId

    override fun setRemoteTripId(remoteTripId: String): Boolean {
        val normalized = remoteTripId.trim().takeIf { it.isNotEmpty() } ?: return false
        mutableRemoteTripId.value = normalized
        return true
    }

    override fun clearRemoteTripId(): Boolean {
        mutableRemoteTripId.value = null
        return true
    }
}

fun interface ActiveTripRemoteResolver {
    suspend fun resolveActiveTrip(): ActiveTripLookupResult
}

fun interface RemoteTripStarter {
    suspend fun startTrip(request: StartTripRequestDto): TripMutationResult
}

fun interface RemoteTripFinisher {
    suspend fun finishTrip(request: FinishTripRequestDto): TripMutationResult
}

class AuthenticatedRemoteTripStarter(
    private val authRepository: AuthRepository,
    private val remoteDataSource: TripRemoteDataSource,
    private val store: RemoteTripSessionStore
) : RemoteTripStarter {
    private val mutex = Mutex()

    override suspend fun startTrip(request: StartTripRequestDto): TripMutationResult = mutex.withLock {
        if (request.vehicleId.isBlank()) {
            return@withLock TripMutationResult.MissingRequiredData("vehicle_id_missing")
        }
        if (request.mobileDeviceId.isBlank()) {
            return@withLock TripMutationResult.MissingRequiredData("mobile_device_id_missing")
        }
        val token = when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            is AuthFailure -> return@withLock result.toTripMutationResult()
        }
        val first = remoteDataSource.startTrip("Bearer $token", request)
        val mutation = retryStartOnceAfterUnauthorized(first, request)
        when (val result = mutation) {
            is TripMutationResult.Success -> {
                if (!store.setRemoteTripId(result.remoteTripId)) {
                    TripMutationResult.InvalidResponse("remote_trip_persistence_failed")
                } else {
                    result
                }
            }
            else -> result
        }
    }

    private suspend fun retryStartOnceAfterUnauthorized(
        first: TripMutationResult,
        request: StartTripRequestDto
    ): TripMutationResult {
        if (first !is TripMutationResult.HttpError || first.statusCode != 401) return first
        if (authRepository.refreshSession() !is AuthResult.Success) return first
        val refreshedToken = when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            is AuthFailure -> return result.toTripMutationResult()
        }
        return remoteDataSource.startTrip("Bearer $refreshedToken", request)
    }
}

class AuthenticatedRemoteTripFinisher(
    private val authRepository: AuthRepository,
    private val remoteDataSource: TripRemoteDataSource,
    private val store: RemoteTripSessionStore
) : RemoteTripFinisher {
    private val mutex = Mutex()

    override suspend fun finishTrip(request: FinishTripRequestDto): TripMutationResult = mutex.withLock {
        val remoteTripId = store.remoteTripId.value?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@withLock TripMutationResult.MissingRequiredData("remote_trip_id_missing")
        val token = when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            is AuthFailure -> return@withLock result.toTripMutationResult()
        }
        val first = remoteDataSource.finishTrip("Bearer $token", remoteTripId, request)
        val mutation = retryFinishOnceAfterUnauthorized(first, remoteTripId, request)
        when (val result = mutation) {
            is TripMutationResult.Success -> {
                if (result.remoteTripId != remoteTripId) {
                    TripMutationResult.InvalidResponse("remote_trip_id_mismatch")
                } else if (!store.clearRemoteTripId()) {
                    TripMutationResult.InvalidResponse("remote_trip_clear_failed")
                } else {
                    result
                }
            }
            else -> result
        }
    }

    private suspend fun retryFinishOnceAfterUnauthorized(
        first: TripMutationResult,
        remoteTripId: String,
        request: FinishTripRequestDto
    ): TripMutationResult {
        if (first !is TripMutationResult.HttpError || first.statusCode != 401) return first
        if (authRepository.refreshSession() !is AuthResult.Success) return first
        val refreshedToken = when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            is AuthFailure -> return result.toTripMutationResult()
        }
        return remoteDataSource.finishTrip("Bearer $refreshedToken", remoteTripId, request)
    }
}

class TripRemoteSessionReconciler(
    private val authRepository: AuthRepository,
    private val remoteDataSource: TripRemoteDataSource,
    private val store: RemoteTripSessionStore,
    private val logger: TripRemoteSessionLogger = NoOpTripRemoteSessionLogger
) : ActiveTripRemoteResolver {
    private val mutex = Mutex()

    override suspend fun resolveActiveTrip(): ActiveTripLookupResult = mutex.withLock {
        val token = when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            is AuthFailure -> return@withLock result.toTripLookupResult().also { logger.tripLookupFailed() }
        }
        val first = remoteDataSource.activeTrip("Bearer $token")
        when (val lookup = retryLookupOnceAfterUnauthorized(first)) {
            is ActiveTripLookupResult.Found -> {
                if (!store.setRemoteTripId(lookup.remoteTripId)) {
                    logger.tripPersistenceFailed()
                    return@withLock ActiveTripLookupResult.InvalidResponse("remote_trip_persistence_failed")
                }
                logger.activeRemoteTripFound()
                logger.remoteTripIdAvailable()
                lookup
            }
            ActiveTripLookupResult.NoActiveTrip -> {
                if (!store.clearRemoteTripId()) {
                    logger.tripPersistenceFailed()
                    return@withLock ActiveTripLookupResult.InvalidResponse("remote_trip_clear_failed")
                }
                logger.noActiveRemoteTrip()
                lookup
            }
            is ActiveTripLookupResult.HttpError,
            is ActiveTripLookupResult.NetworkUnavailable,
            is ActiveTripLookupResult.Timeout,
            is ActiveTripLookupResult.InvalidResponse -> lookup.also { logger.tripLookupFailed() }
        }
    }

    private suspend fun retryLookupOnceAfterUnauthorized(
        first: ActiveTripLookupResult
    ): ActiveTripLookupResult {
        if (first !is ActiveTripLookupResult.HttpError || first.statusCode != 401) return first
        if (authRepository.refreshSession() !is AuthResult.Success) return first
        val refreshedToken = when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            is AuthFailure -> return result.toTripLookupResult()
        }
        return remoteDataSource.activeTrip("Bearer $refreshedToken")
    }

    private fun AuthFailure.toTripLookupResult(): ActiveTripLookupResult = when (this) {
        is NetworkUnavailable -> ActiveTripLookupResult.NetworkUnavailable(sanitizedMessage ?: "network_unavailable")
        is Timeout -> ActiveTripLookupResult.Timeout(sanitizedMessage ?: "network_timeout")
        is InvalidResponse -> ActiveTripLookupResult.InvalidResponse(sanitizedMessage ?: "access_token_invalid")
        else -> ActiveTripLookupResult.HttpError(401, "access_token_unavailable")
    }
}

private fun AuthFailure.toTripMutationResult(): TripMutationResult = when (this) {
    is NetworkUnavailable -> TripMutationResult.NetworkUnavailable(sanitizedMessage ?: "network_unavailable")
    is Timeout -> TripMutationResult.Timeout(sanitizedMessage ?: "network_timeout")
    is InvalidResponse -> TripMutationResult.InvalidResponse(sanitizedMessage ?: "access_token_invalid")
    else -> TripMutationResult.HttpError(401, "access_token_unavailable")
}

interface TripRemoteSessionLogger {
    fun activeRemoteTripFound()
    fun remoteTripIdAvailable()
    fun noActiveRemoteTrip()
    fun tripLookupFailed()
    fun tripPersistenceFailed()
}

object NoOpTripRemoteSessionLogger : TripRemoteSessionLogger {
    override fun activeRemoteTripFound() = Unit
    override fun remoteTripIdAvailable() = Unit
    override fun noActiveRemoteTrip() = Unit
    override fun tripLookupFailed() = Unit
    override fun tripPersistenceFailed() = Unit
}
