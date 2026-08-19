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
import java.time.Instant

interface RemoteTripSessionStore {
    val remoteTripId: StateFlow<String?>
    val startedAtEpochMs: StateFlow<Long?>
    /** Durable logical session correlated with [remoteTripId], when known. */
    val tripSessionKey: StateFlow<String?>
    fun setActiveSession(remoteTripId: String, startedAtEpochMs: Long?, tripSessionKey: String? = null): Boolean
    fun setRemoteTripId(remoteTripId: String): Boolean
    fun setStartedAtEpochMs(startedAtEpochMs: Long?): Boolean
    fun clearRemoteTripId(): Boolean
    fun clearIfMatches(tripSessionKey: String, remoteTripId: String): RemoteTripSessionClearResult = RemoteTripSessionClearResult.LegacyUncorrelated
}

enum class RemoteTripSessionClearResult { Cleared, AlreadyEmpty, DifferentTrip, LegacyUncorrelated }

class InMemoryRemoteTripSessionStore : RemoteTripSessionStore {
    private val mutableRemoteTripId = MutableStateFlow<String?>(null)
    private val mutableStartedAtEpochMs = MutableStateFlow<Long?>(null)
    private val mutableTripSessionKey = MutableStateFlow<String?>(null)
    override val remoteTripId: StateFlow<String?> = mutableRemoteTripId
    override val startedAtEpochMs: StateFlow<Long?> = mutableStartedAtEpochMs
    override val tripSessionKey: StateFlow<String?> = mutableTripSessionKey

    override fun setActiveSession(remoteTripId: String, startedAtEpochMs: Long?, tripSessionKey: String?): Boolean {
        val normalized = remoteTripId.trim().takeIf { it.isNotEmpty() } ?: return false
        val nextStartedAt = if (mutableRemoteTripId.value == normalized && mutableStartedAtEpochMs.value != null) {
            mutableStartedAtEpochMs.value
        } else {
            startedAtEpochMs?.takeIf { it >= 0L }
        }
        val nextKey = tripSessionKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: mutableTripSessionKey.value.takeIf { mutableRemoteTripId.value == normalized }
        mutableRemoteTripId.value = normalized
        mutableStartedAtEpochMs.value = nextStartedAt
        mutableTripSessionKey.value = nextKey
        return true
    }

    override fun setRemoteTripId(remoteTripId: String): Boolean = setActiveSession(remoteTripId, null, null)

    override fun setStartedAtEpochMs(startedAtEpochMs: Long?): Boolean {
        val remoteTripId = mutableRemoteTripId.value ?: return false
        return setActiveSession(remoteTripId, startedAtEpochMs, null)
    }

    override fun clearRemoteTripId(): Boolean {
        mutableRemoteTripId.value = null
        mutableStartedAtEpochMs.value = null
        mutableTripSessionKey.value = null
        return true
    }

    override fun clearIfMatches(tripSessionKey: String, remoteTripId: String): RemoteTripSessionClearResult {
        val currentTrip = mutableRemoteTripId.value ?: return RemoteTripSessionClearResult.AlreadyEmpty
        val currentKey = mutableTripSessionKey.value ?: return RemoteTripSessionClearResult.LegacyUncorrelated
        if (currentTrip != remoteTripId || currentKey != tripSessionKey) return RemoteTripSessionClearResult.DifferentTrip
        clearRemoteTripId()
        return RemoteTripSessionClearResult.Cleared
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

interface RemoteTripIdFinisher : RemoteTripFinisher {
    suspend fun finishTrip(remoteTripId: String, request: FinishTripRequestDto): TripMutationResult
}

class AuthenticatedRemoteTripStarter(
    private val authRepository: AuthRepository,
    private val remoteDataSource: TripRemoteDataSource,
    private val store: RemoteTripSessionStore,
    private val tripSessionKey: () -> String? = { null }
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
            is AuthFailure -> return result.toTripMutationResult()
        }
        val first = remoteDataSource.startTrip("Bearer $token", request)
        val mutation = retryStartOnceAfterUnauthorized(first, request)
        when (val result = mutation) {
            is TripMutationResult.Success -> {
                val key = tripSessionKey()?.trim()?.takeIf { it.isNotEmpty() }
                if (key == null || !store.setActiveSession(result.remoteTripId, request.clientStartedAtUtc.toEpochMillisOrNull(), key)) {
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

private fun String?.toEpochMillisOrNull(): Long? = try {
    this?.let(Instant::parse)?.toEpochMilli()?.takeIf { it >= 0L }
} catch (_: java.time.format.DateTimeParseException) {
    null
}

class AuthenticatedRemoteTripFinisher(
    private val authRepository: AuthRepository,
    private val remoteDataSource: TripRemoteDataSource,
    private val store: RemoteTripSessionStore
) : RemoteTripIdFinisher {
    private val mutex = Mutex()

    override suspend fun finishTrip(request: FinishTripRequestDto): TripMutationResult = mutex.withLock {
        val remoteTripId = store.remoteTripId.value?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@withLock TripMutationResult.MissingRequiredData("remote_trip_id_missing")
        val tripSessionKey = store.tripSessionKey.value
        finishTripLocked(remoteTripId, request, clearSession = true, tripSessionKey = tripSessionKey)
    }
    override suspend fun finishTrip(remoteTripId: String, request: FinishTripRequestDto): TripMutationResult = mutex.withLock {
        finishTripLocked(
            remoteTripId.trim().takeIf { it.isNotEmpty() }
                ?: return@withLock TripMutationResult.MissingRequiredData("remote_trip_id_missing"),
            request,
            clearSession = false,
            tripSessionKey = null
        )
    }

    private suspend fun finishTripLocked(
        remoteTripId: String,
        request: FinishTripRequestDto,
        clearSession: Boolean,
        tripSessionKey: String?
    ): TripMutationResult {
        val token = when (val result = authRepository.ensureValidAccessToken()) {
            is AuthResult.Success -> result.value.reveal()
            is AuthFailure -> return result.toTripMutationResult()
        }
        val first = remoteDataSource.finishTrip("Bearer $token", remoteTripId, request)
        val mutation = retryFinishOnceAfterUnauthorized(first, remoteTripId, request)
        return when (val result = mutation) {
            is TripMutationResult.Success -> {
                if (result.remoteTripId != remoteTripId) {
                    TripMutationResult.InvalidResponse("remote_trip_id_mismatch")
                } else if (clearSession && !clearFinishedSession(tripSessionKey, remoteTripId)) {
                    TripMutationResult.InvalidResponse("remote_trip_clear_failed")
                } else {
                    result
                }
            }
            else -> result
        }
    }

    private fun clearFinishedSession(tripSessionKey: String?, remoteTripId: String): Boolean {
        if (tripSessionKey.isNullOrBlank()) return store.clearRemoteTripId()
        return when (store.clearIfMatches(tripSessionKey, remoteTripId)) {
            RemoteTripSessionClearResult.Cleared,
            RemoteTripSessionClearResult.AlreadyEmpty -> true
            RemoteTripSessionClearResult.DifferentTrip,
            RemoteTripSessionClearResult.LegacyUncorrelated -> false
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
    private val logger: TripRemoteSessionLogger = NoOpTripRemoteSessionLogger,
    private val tripSessionKey: () -> String? = { null },
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
                val previousRemoteTripId = store.remoteTripId.value
                val currentTripSessionKey = tripSessionKey()?.trim()?.takeIf { it.isNotEmpty() }
                val persisted = if (currentTripSessionKey == null) {
                    store.setRemoteTripId(lookup.remoteTripId)
                } else {
                    store.setActiveSession(
                        remoteTripId = lookup.remoteTripId,
                        startedAtEpochMs = store.startedAtEpochMs.value.takeIf { previousRemoteTripId == lookup.remoteTripId },
                        tripSessionKey = currentTripSessionKey,
                    )
                }
                if (!persisted) {
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
