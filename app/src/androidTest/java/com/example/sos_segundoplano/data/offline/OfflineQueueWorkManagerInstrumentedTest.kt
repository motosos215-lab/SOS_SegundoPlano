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

    @Test fun schedulerUsesSeparateImmediateChainForGenericSync() {
        val fake = FakeEnqueuer()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = OfflineQueueWorkScheduler(context, fake)

        scheduler.scheduleImmediateSync()

        assertEquals(OfflineQueueWorkScheduler.GENERIC_IMMEDIATE_WORK_NAME, fake.name)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, fake.policy)
        assertEquals(0L, fake.request?.workSpec?.initialDelay)
        assertEquals(1, fake.count)
    }

    @Test fun schedulerReplacesLaterDeferredSyncWithEarlierRetry() {
        val fake = FakeEnqueuer()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = OfflineQueueWorkScheduler(context, fake)

        scheduler.scheduleDeferredSync(60_000L)

        assertEquals(OfflineQueueWorkScheduler.GENERIC_RETRY_WORK_NAME, fake.name)
        assertEquals(ExistingWorkPolicy.REPLACE, fake.policy)
        assertEquals(60_000L, fake.request?.workSpec?.initialDelay)
        assertEquals(NetworkType.CONNECTED, fake.request?.workSpec?.constraints?.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, fake.request?.workSpec?.backoffPolicy)
    }


    @Test fun automaticSosWorkAcceptsConnectedNetworksAndUsesDedicatedWorker() {
        val request = OfflineQueueWorkScheduler.createAutomaticSosWorkRequest()

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(AutomaticSosSyncWorker::class.java.name, request.workSpec.workerClassName)
    }

    @Test fun automaticSosImmediateSyncUsesSeparateNonBlockingEmergencyChain() {
        val fake = FakeEnqueuer()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = OfflineQueueWorkScheduler(context, fake)

        scheduler.scheduleImmediateAutomaticSos()

        assertEquals(OfflineQueueWorkScheduler.AUTOMATIC_SOS_IMMEDIATE_WORK_NAME, fake.name)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, fake.policy)
        assertEquals(0L, fake.request?.workSpec?.initialDelay)
        assertEquals(NetworkType.CONNECTED, fake.request?.workSpec?.constraints?.requiredNetworkType)
    }


    @Test fun manualSosWorkAcceptsConnectedNetworksAndUsesDedicatedWorker() {
        val request = OfflineQueueWorkScheduler.createManualSosWorkRequest()

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(ManualSosSyncWorker::class.java.name, request.workSpec.workerClassName)
    }

    @Test fun manualSosImmediateSyncUsesItsOwnChain() {
        val fake = FakeEnqueuer()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = OfflineQueueWorkScheduler(context, fake)

        scheduler.scheduleImmediateManualSos()

        assertEquals(OfflineQueueWorkScheduler.MANUAL_SOS_IMMEDIATE_WORK_NAME, fake.name)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, fake.policy)
        assertEquals(NetworkType.CONNECTED, fake.request?.workSpec?.constraints?.requiredNetworkType)
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
