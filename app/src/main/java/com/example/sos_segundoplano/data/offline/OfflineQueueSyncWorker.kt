package com.example.sos_segundoplano.data.offline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.sos_segundoplano.domain.offline.OfflineQueueSyncResult
import com.example.sos_segundoplano.data.validation.AutoIncidentDiagnostics

class OfflineQueueSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val provider = OfflineQueueProvider.get(applicationContext)
        val bundleResult = provider.automaticSosProcessor.process("worker-$id-auto")
        val genericResult = provider.processor.process("worker-$id")
        val now = provider.clock.currentTimeMillis()
        provider.repository.earliestAutomaticSosRetryForCurrentRider()?.let { nextAttemptAt ->
            provider.scheduler.scheduleDeferredSync((nextAttemptAt - now).coerceAtLeast(0L))
            AutoIncidentDiagnostics.retryScheduled("scheduled")
        } ?: AutoIncidentDiagnostics.retryScheduled("none")
        val syncResult = when {
            bundleResult is OfflineQueueSyncResult.InitializationFailure -> bundleResult
            genericResult is OfflineQueueSyncResult.InitializationFailure -> genericResult
            bundleResult == OfflineQueueSyncResult.RetryRequired || genericResult == OfflineQueueSyncResult.RetryRequired -> OfflineQueueSyncResult.RetryRequired
            bundleResult is OfflineQueueSyncResult.DeferredUntil -> bundleResult
            genericResult is OfflineQueueSyncResult.DeferredUntil -> genericResult
            bundleResult == OfflineQueueSyncResult.Completed || genericResult == OfflineQueueSyncResult.Completed -> OfflineQueueSyncResult.Completed
            else -> OfflineQueueSyncResult.NothingToDo
        }
        return when (syncResult) {
            OfflineQueueSyncResult.Completed -> Result.success()
            OfflineQueueSyncResult.NothingToDo -> Result.success()
            OfflineQueueSyncResult.RetryRequired -> Result.retry()
            is OfflineQueueSyncResult.DeferredUntil -> {
                val delayMillis = syncResult.epochMillis - now
                provider.scheduler.scheduleDeferredSync(delayMillis)
                Result.success()
            }
            is OfflineQueueSyncResult.InitializationFailure -> Result.retry()
        }
    }
}
