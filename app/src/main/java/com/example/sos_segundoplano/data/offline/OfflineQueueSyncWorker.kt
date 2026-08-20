package com.example.sos_segundoplano.data.offline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.sos_segundoplano.domain.offline.OfflineQueueSyncResult

/** Processes only secondary/generic offline events. Emergency SOS has its own worker. */
class OfflineQueueSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val provider = OfflineQueueProvider.get(applicationContext)
        val now = provider.clock.currentTimeMillis()
        return when (val result = provider.processor.process("worker-$id")) {
            OfflineQueueSyncResult.Completed,
            OfflineQueueSyncResult.NothingToDo -> Result.success()

            OfflineQueueSyncResult.RetryRequired -> Result.retry()

            is OfflineQueueSyncResult.DeferredUntil -> {
                provider.scheduler.scheduleDeferredSync((result.epochMillis - now).coerceAtLeast(0L))
                Result.success()
            }

            is OfflineQueueSyncResult.InitializationFailure -> Result.retry()
        }
    }
}
