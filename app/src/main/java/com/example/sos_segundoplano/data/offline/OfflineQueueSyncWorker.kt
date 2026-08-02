package com.example.sos_segundoplano.data.offline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.sos_segundoplano.domain.offline.OfflineQueueSyncResult

class OfflineQueueSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val provider = OfflineQueueProvider.get(applicationContext)
        return when (val syncResult = provider.processor.process("worker-$id")) {
            OfflineQueueSyncResult.Completed -> Result.success()
            OfflineQueueSyncResult.NothingToDo -> Result.success()
            OfflineQueueSyncResult.RetryRequired -> Result.retry()
            is OfflineQueueSyncResult.DeferredUntil -> {
                val delayMillis = syncResult.epochMillis - provider.clock.currentTimeMillis()
                provider.scheduler.scheduleDeferredSync(delayMillis)
                Result.success()
            }
            is OfflineQueueSyncResult.InitializationFailure -> Result.retry()
        }
    }
}
