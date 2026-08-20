package com.example.sos_segundoplano.data.remote.trip

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class TripFinishRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (TripRemoteSessionProvider.get(applicationContext).finishRecoveryProcessor.process()) {
        TripFinishRecoveryResult.NothingToDo,
        TripFinishRecoveryResult.Completed,
        TripFinishRecoveryResult.NeedsAttention,
        // The processor already persists nextAttemptAt and schedules the uniquely named retry work.
        // Returning success here prevents WorkManager from adding a second independent backoff chain.
        TripFinishRecoveryResult.RetryRequired -> Result.success()
    }
}
