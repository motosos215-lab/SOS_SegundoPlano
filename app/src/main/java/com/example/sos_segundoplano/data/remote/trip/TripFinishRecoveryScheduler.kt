package com.example.sos_segundoplano.data.remote.trip

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

interface TripFinishWorkScheduler {
    fun scheduleImmediate()
    fun scheduleRetry(delayMillis: Long)
}

class TripFinishRecoveryScheduler(private val context: Context) : TripFinishWorkScheduler {
    override fun scheduleImmediate() {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(0L)
        )
    }

    override fun scheduleRetry(delayMillis: Long) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            RETRY_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request(delayMillis.coerceAtLeast(0L))
        )
    }

    private fun request(delayMillis: Long) = OneTimeWorkRequestBuilder<TripFinishRecoveryWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WORK_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
        .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
        .build()

    companion object {
        const val IMMEDIATE_WORK_NAME = "trip-finish-sync-immediate"
        const val RETRY_WORK_NAME = "trip-finish-sync-retry"
        const val WORK_BACKOFF_MILLIS = 30_000L
    }
}
