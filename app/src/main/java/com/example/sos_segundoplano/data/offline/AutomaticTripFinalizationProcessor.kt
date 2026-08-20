package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.data.remote.trip.FinishTripRequestDto
import com.example.sos_segundoplano.data.remote.trip.RemoteTripIdFinisher
import com.example.sos_segundoplano.data.remote.trip.TripMutationResult
import com.example.sos_segundoplano.data.validation.AutoIncidentDiagnostics
import com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult
import com.example.sos_segundoplano.domain.offline.OfflineQueueSyncResult
import com.example.sos_segundoplano.domain.offline.WallClock
import java.time.Instant

/** Owner-scoped, lease-protected recovery for the trip following a confirmed automatic SOS. */
class AutomaticTripFinalizationProcessor(
    private val repository: AutomaticTripFinalizationRepository,
    private val finisher: RemoteTripIdFinisher,
    private val reconcileLocal: (String) -> Boolean,
    private val clock: WallClock
) {
    suspend fun process(workerId: String): OfflineQueueSyncResult {
        val now = clock.currentTimeMillis()
        return handleClaim(repository.claimNextAutomaticTripFinalization(workerId, now), now)
    }

    suspend fun processBundle(bundleKey: String, workerId: String): OfflineQueueSyncResult {
        val now = clock.currentTimeMillis()
        return handleClaim(repository.claimAutomaticTripFinalization(bundleKey, workerId, now), now)
    }

    private suspend fun handleClaim(result: AutomaticTripFinalizationClaimResult, now: Long): OfflineQueueSyncResult = when (result) {
            AutomaticTripFinalizationClaimResult.BusyOrUnavailable -> {
                AutoIncidentDiagnostics.tripFinalizationClaim("busy")
                OfflineQueueSyncResult.NothingToDo
            }
            AutomaticTripFinalizationClaimResult.NotRecoverable -> {
                AutoIncidentDiagnostics.tripFinalizationClaim("invalid")
                OfflineQueueSyncResult.NothingToDo
            }
            is AutomaticTripFinalizationClaimResult.Acquired -> {
                val claim = result.value
                AutoIncidentDiagnostics.tripFinalizationClaim("acquired")
                AutoIncidentDiagnostics.tripFinalizationStarted()
                val remoteResult = finisher.finishTrip(claim.remoteTripId, FinishTripRequestDto(Instant.ofEpochMilli(now).toString()))
                when (remoteResult) {
                    is TripMutationResult.Success -> {
                        val reconciled = reconcileLocal(claim.remoteTripId)
                        AutoIncidentDiagnostics.tripLocalReconcileResult(if (reconciled) "success" else "failure")
                        if (reconciled && repository.completeAutomaticTripFinalization(claim, clock.currentTimeMillis())) {
                            AutoIncidentDiagnostics.tripFinalizationComplete("success")
                            OfflineQueueSyncResult.Completed
                        } else {
                            repository.releaseAutomaticTripFinalization(claim, permanent = false, now = clock.currentTimeMillis())
                            AutoIncidentDiagnostics.tripFinalizationComplete("failure")
                            OfflineQueueSyncResult.RetryRequired
                        }
                    }
                    is TripMutationResult.HttpError -> {
                        val permanent = remoteResult.statusCode.let { it in 400..499 && it !in setOf(401,408,429) }
                        repository.releaseAutomaticTripFinalization(claim, permanent, clock.currentTimeMillis())
                        AutoIncidentDiagnostics.tripFinishResult(if (permanent) "permanent" else "retry")
                        if (permanent) OfflineQueueSyncResult.Completed else OfflineQueueSyncResult.RetryRequired
                    }
                    else -> {
                        repository.releaseAutomaticTripFinalization(claim, permanent = false, now = clock.currentTimeMillis())
                        AutoIncidentDiagnostics.tripFinishResult("retry")
                        OfflineQueueSyncResult.RetryRequired
                    }
                }
            }
        }
}
