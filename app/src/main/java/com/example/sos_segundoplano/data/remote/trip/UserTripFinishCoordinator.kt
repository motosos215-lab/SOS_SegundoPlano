package com.example.sos_segundoplano.data.remote.trip

import com.example.sos_segundoplano.data.trip.TripLocalStateReconciler
import java.time.Instant

sealed interface UserTripFinishResult {
    data object RemoteConfirmed : UserTripFinishResult
    data object SavedForSync : UserTripFinishResult
    data object SavedNeedsAttention : UserTripFinishResult
    data class PersistenceFailed(val sanitizedMessage: String) : UserTripFinishResult
}

class UserTripFinishCoordinator(
    private val finisher: RemoteTripIdFinisher,
    private val remoteTripStore: RemoteTripSessionStore,
    private val pendingStore: PendingTripFinishStore,
    private val scheduler: TripFinishWorkScheduler,
    private val currentOwnerUserId: () -> String?,
    private val isInternetAvailable: () -> Boolean = { true },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun finishTrip(tripSessionKey: String, request: FinishTripRequestDto): UserTripFinishResult {
        val owner = currentOwnerUserId()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return UserTripFinishResult.PersistenceFailed("rider_session_missing")
        val remoteTripId = remoteTripStore.remoteTripId.value?.trim()?.takeIf { it.isNotEmpty() }
            ?: return UserTripFinishResult.PersistenceFailed("remote_trip_id_missing")
        val logicalKey = tripSessionKey.trim().takeIf { it.isNotEmpty() }
            ?: return UserTripFinishResult.PersistenceFailed("trip_session_key_missing")
        val finishedAtUtc = request.clientFinishedAtUtc?.trim()?.takeIf { it.isNotEmpty() } ?: Instant.now().toString()
        val now = nowEpochMillis().coerceAtLeast(0L)

        val persisted = pendingStore.savePending(
            PendingTripFinish(
                ownerUserId = owner,
                remoteTripId = remoteTripId,
                tripSessionKey = logicalKey,
                clientFinishedAtUtc = finishedAtUtc,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now
            )
        )
        if (!persisted) return UserTripFinishResult.PersistenceFailed("trip_finish_persistence_failed")

        // Finishing the local trip must not wait on a network timeout. Once the durable record
        // exists, WorkManager owns remote delivery as soon as Wi-Fi or cellular data is validated.
        if (!isInternetAvailable()) {
            pendingStore.markRetry(owner, remoteTripId, now, "network_unavailable")
            scheduler.scheduleImmediate()
            return UserTripFinishResult.SavedForSync
        }

        return when (val remote = finisher.finishTrip(remoteTripId, request.copy(clientFinishedAtUtc = finishedAtUtc))) {
            is TripMutationResult.Success -> {
                val remoteCleared = when (remoteTripStore.clearIfMatches(logicalKey, remoteTripId)) {
                    RemoteTripSessionClearResult.Cleared,
                    RemoteTripSessionClearResult.AlreadyEmpty -> true
                    RemoteTripSessionClearResult.DifferentTrip,
                    RemoteTripSessionClearResult.LegacyUncorrelated -> false
                }
                if (remoteCleared && pendingStore.clearIfMatches(owner, remoteTripId)) {
                    UserTripFinishResult.RemoteConfirmed
                } else {
                    pendingStore.markRetry(owner, remoteTripId, now, "trip_finish_remote_session_reconcile_pending")
                    scheduler.scheduleImmediate()
                    UserTripFinishResult.SavedForSync
                }
            }
            is TripMutationResult.HttpError -> handleHttpFailure(owner, remoteTripId, remote.statusCode)
            is TripMutationResult.NetworkUnavailable -> saveRetry(owner, remoteTripId, "network_unavailable")
            is TripMutationResult.Timeout -> saveRetry(owner, remoteTripId, "timeout")
            is TripMutationResult.InvalidResponse -> saveRetry(owner, remoteTripId, remote.sanitizedMessage ?: "invalid_response")
            is TripMutationResult.MissingRequiredData -> saveRetry(owner, remoteTripId, remote.sanitizedMessage ?: "required_data_missing")
        }
    }

    private fun handleHttpFailure(owner: String, remoteTripId: String, statusCode: Int): UserTripFinishResult {
        return if (statusCode == 401 || statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500) {
            saveRetry(owner, remoteTripId, "http_$statusCode")
        } else {
            pendingStore.markFailed(owner, remoteTripId, "http_$statusCode")
            UserTripFinishResult.SavedNeedsAttention
        }
    }

    private fun saveRetry(owner: String, remoteTripId: String, code: String): UserTripFinishResult {
        pendingStore.markRetry(owner, remoteTripId, nowEpochMillis().coerceAtLeast(0L), code)
        scheduler.scheduleImmediate()
        return UserTripFinishResult.SavedForSync
    }
}

sealed interface TripFinishRecoveryResult {
    data object NothingToDo : TripFinishRecoveryResult
    data object Completed : TripFinishRecoveryResult
    data object RetryRequired : TripFinishRecoveryResult
    data object NeedsAttention : TripFinishRecoveryResult
}

class TripFinishRecoveryProcessor(
    private val finisher: RemoteTripIdFinisher,
    private val pendingStore: PendingTripFinishStore,
    private val scheduler: TripFinishWorkScheduler,
    private val localStateReconciler: TripLocalStateReconciler,
    private val currentOwnerUserId: () -> String?,
    private val isInternetAvailable: () -> Boolean = { true },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun process(): TripFinishRecoveryResult {
        // A Rider login observer schedules this worker again when a valid owner becomes available.
        val owner = currentOwnerUserId()?.trim()?.takeIf { it.isNotEmpty() } ?: return TripFinishRecoveryResult.NothingToDo
        val pending = pendingStore.readForOwner(owner) ?: return TripFinishRecoveryResult.NothingToDo
        if (pending.state == PendingTripFinishState.FailedPermanent) return TripFinishRecoveryResult.NeedsAttention
        val now = nowEpochMillis().coerceAtLeast(0L)
        val next = pending.nextAttemptAtEpochMillis
        if (next != null && next > now) {
            scheduler.scheduleRetry(next - now)
            return TripFinishRecoveryResult.RetryRequired
        }
        if (!isInternetAvailable()) {
            return retry(owner, pending.remoteTripId, "network_unavailable")
        }

        val request = FinishTripRequestDto(clientFinishedAtUtc = pending.clientFinishedAtUtc)
        return when (val remote = finisher.finishTrip(pending.remoteTripId, request)) {
            is TripMutationResult.Success -> {
                if (localStateReconciler.reconcileFinishedRemoteTrip(pending.remoteTripId) &&
                    pendingStore.clearIfMatches(owner, pending.remoteTripId)
                ) TripFinishRecoveryResult.Completed
                else retry(owner, pending.remoteTripId, "trip_finish_local_reconcile_pending")
            }
            is TripMutationResult.HttpError -> {
                if (remote.statusCode == 401 || remote.statusCode == 408 || remote.statusCode == 409 || remote.statusCode == 429 || remote.statusCode >= 500) {
                    retry(owner, pending.remoteTripId, "http_${remote.statusCode}")
                } else {
                    pendingStore.markFailed(owner, pending.remoteTripId, "http_${remote.statusCode}")
                    TripFinishRecoveryResult.NeedsAttention
                }
            }
            is TripMutationResult.NetworkUnavailable -> retry(owner, pending.remoteTripId, "network_unavailable")
            is TripMutationResult.Timeout -> retry(owner, pending.remoteTripId, "timeout")
            is TripMutationResult.InvalidResponse -> retry(owner, pending.remoteTripId, remote.sanitizedMessage ?: "invalid_response")
            is TripMutationResult.MissingRequiredData -> retry(owner, pending.remoteTripId, remote.sanitizedMessage ?: "required_data_missing")
        }
    }

    private fun retry(owner: String, remoteTripId: String, code: String): TripFinishRecoveryResult {
        val now = nowEpochMillis().coerceAtLeast(0L)
        val pending = pendingStore.readForOwner(owner)
        val attempt = (pending?.attemptCount ?: 0).coerceAtLeast(0)
        val multiplier = 1L shl attempt.coerceAtMost(5)
        val delay = (30_000L * multiplier).coerceAtMost(30L * 60_000L)
        val next = now + delay
        pendingStore.markRetry(owner, remoteTripId, next, code)
        scheduler.scheduleRetry(delay)
        return TripFinishRecoveryResult.RetryRequired
    }
}
