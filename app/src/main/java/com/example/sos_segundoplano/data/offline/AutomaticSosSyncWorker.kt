package com.example.sos_segundoplano.data.offline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.sos_segundoplano.data.validation.AutoIncidentDiagnostics
import com.example.sos_segundoplano.domain.offline.OfflineQueueSyncResult

/**
 * Dedicated emergency worker. Automatic SOS never shares its WorkManager chain with
 * secondary/offline-generic events, so a delayed generic retry cannot block an emergency.
 */
class AutomaticSosSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val provider = OfflineQueueProvider.get(applicationContext)
        val sosResult = provider.automaticSosProcessor.process("worker-$id-auto")
        val finalizationResult = provider.automaticTripFinalizationProcessor.process("worker-$id-finalize")
        val now = provider.clock.currentTimeMillis()

        val nextAttemptAt = listOfNotNull(
            provider.repository.earliestAutomaticSosRetryForCurrentRider(),
            provider.repository.earliestAutomaticTripFinalizationRetryForCurrentRider()
        ).minOrNull()

        if (nextAttemptAt != null) {
            provider.scheduler.scheduleDeferredAutomaticSos((nextAttemptAt - now).coerceAtLeast(0L))
            AutoIncidentDiagnostics.retryScheduled("scheduled")
            AutoIncidentDiagnostics.tripFinalizationRetryScheduled("scheduled")
        } else {
            AutoIncidentDiagnostics.retryScheduled("none")
            AutoIncidentDiagnostics.tripFinalizationRetryScheduled("none")
        }

        val syncResult = combineEmergencyResults(sosResult, finalizationResult)
        return when (syncResult) {
            OfflineQueueSyncResult.Completed,
            OfflineQueueSyncResult.NothingToDo -> Result.success()

            OfflineQueueSyncResult.RetryRequired -> {
                // A durable retry timestamp was already scheduled above when available.
                // If no timestamp is available, let WorkManager apply its own bounded backoff.
                if (nextAttemptAt != null) Result.success() else Result.retry()
            }

            is OfflineQueueSyncResult.DeferredUntil -> {
                provider.scheduler.scheduleDeferredAutomaticSos((syncResult.epochMillis - now).coerceAtLeast(0L))
                Result.success()
            }

            is OfflineQueueSyncResult.InitializationFailure -> Result.retry()
        }
    }

    private fun combineEmergencyResults(
        sos: OfflineQueueSyncResult,
        finalization: OfflineQueueSyncResult
    ): OfflineQueueSyncResult = when {
        sos is OfflineQueueSyncResult.InitializationFailure -> sos
        finalization is OfflineQueueSyncResult.InitializationFailure -> finalization
        sos == OfflineQueueSyncResult.RetryRequired || finalization == OfflineQueueSyncResult.RetryRequired -> OfflineQueueSyncResult.RetryRequired
        sos is OfflineQueueSyncResult.DeferredUntil -> sos
        finalization is OfflineQueueSyncResult.DeferredUntil -> finalization
        sos == OfflineQueueSyncResult.Completed || finalization == OfflineQueueSyncResult.Completed -> OfflineQueueSyncResult.Completed
        else -> OfflineQueueSyncResult.NothingToDo
    }
}
