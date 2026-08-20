package com.example.sos_segundoplano.data.route

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class TripRouteWorkScheduler(context: Context) {
    private val appContext = context.applicationContext

    fun schedule() {
        val request = OneTimeWorkRequestBuilder<TripRouteSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        runCatching {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "trip-route-points-sync"
    }
}
