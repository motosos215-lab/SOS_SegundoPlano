package com.example.sos_segundoplano.data.offline

import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineQueueWorkManagerInstrumentedTest {
    @Test fun workRequestUsesConnectedNetworkBackoffAndWorkerClass() {
        val request = OfflineQueueWorkScheduler.createSyncWorkRequest()

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(OfflineQueueWorkScheduler.WORK_BACKOFF_MILLIS, request.workSpec.backoffDelayDuration)
        assertEquals(OfflineQueueSyncWorker::class.java.name, request.workSpec.workerClassName)
    }

    @Test fun schedulerUsesKeepPolicyForImmediateSync() {
        val fake = FakeEnqueuer()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = OfflineQueueWorkScheduler(context, fake)

        scheduler.scheduleImmediateSync()

        assertEquals(OfflineQueueWorkScheduler.UNIQUE_WORK_NAME, fake.name)
        assertEquals(ExistingWorkPolicy.KEEP, fake.policy)
        assertEquals(0L, fake.request?.workSpec?.initialDelay)
        assertEquals(1, fake.count)
    }

    @Test fun schedulerUsesAppendOrReplaceForDeferredSync() {
        val fake = FakeEnqueuer()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = OfflineQueueWorkScheduler(context, fake)

        scheduler.scheduleDeferredSync(60_000L)

        assertEquals(OfflineQueueWorkScheduler.UNIQUE_WORK_NAME, fake.name)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, fake.policy)
        assertEquals(60_000L, fake.request?.workSpec?.initialDelay)
        assertEquals(NetworkType.CONNECTED, fake.request?.workSpec?.constraints?.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, fake.request?.workSpec?.backoffPolicy)
    }

    private class FakeEnqueuer : UniqueWorkEnqueuer {
        var name: String? = null
        var policy: ExistingWorkPolicy? = null
        var request: OneTimeWorkRequest? = null
        var count = 0
        override fun enqueueUnique(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
            this.name = name
            this.policy = policy
            this.request = request
            count++
        }
    }
}
