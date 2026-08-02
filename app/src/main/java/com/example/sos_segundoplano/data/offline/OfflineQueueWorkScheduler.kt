package com.example.sos_segundoplano.data.offline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

interface UniqueWorkEnqueuer {
    fun enqueueUnique(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest)
}

class WorkManagerUniqueWorkEnqueuer(private val context: Context) : UniqueWorkEnqueuer {
    override fun enqueueUnique(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, policy, request)
    }
}

class OfflineQueueWorkScheduler(
    context: Context,
    private val enqueuer: UniqueWorkEnqueuer = WorkManagerUniqueWorkEnqueuer(context)
) {
    fun scheduleImmediateSync(): ScheduleResult = schedule(ExistingWorkPolicy.KEEP, 0L)

    fun scheduleDeferredSync(delayMillis: Long): ScheduleResult = schedule(ExistingWorkPolicy.APPEND_OR_REPLACE, delayMillis)

    private fun schedule(policy: ExistingWorkPolicy, delayMillis: Long): ScheduleResult = try {
        enqueuer.enqueueUnique(UNIQUE_WORK_NAME, policy, createSyncWorkRequest(delayMillis))
        ScheduleResult.Scheduled
    } catch (_: IllegalStateException) {
        ScheduleResult.Deferred
    }

    companion object {
        const val UNIQUE_WORK_NAME = "offline-queue-sync"
        const val WORK_BACKOFF_MILLIS = 30_000L

        fun createSyncWorkRequest(initialDelayMillis: Long = 0L): OneTimeWorkRequest = OneTimeWorkRequestBuilder<OfflineQueueSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WORK_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .setInitialDelay(initialDelayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
    }
}
