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
    /**
     * Generic secondary-event synchronization has an immediate chain separate from delayed retry.
     * This avoids a legacy/delayed `offline-queue-sync` request keeping newly connected events stuck.
     */
    fun scheduleImmediateSync(): ScheduleResult = scheduleGenericImmediate(ExistingWorkPolicy.APPEND_OR_REPLACE, 0L)

    /** REPLACE lets an earlier durable generic retry supersede a previously scheduled later retry. */
    fun scheduleDeferredSync(delayMillis: Long): ScheduleResult = scheduleGenericRetry(ExistingWorkPolicy.REPLACE, delayMillis)

    /**
     * Emergency work has an immediate chain separate from its delayed retry chain.
     * APPEND_OR_REPLACE never waits behind a delayed retry and does not cancel an active send.
     */
    fun scheduleImmediateAutomaticSos(): ScheduleResult = scheduleAutomaticImmediate(ExistingWorkPolicy.APPEND_OR_REPLACE, 0L)

    /** Dedicated durable retry for automatic SOS and its trip-finalization follow-up. */
    fun scheduleDeferredAutomaticSos(delayMillis: Long): ScheduleResult =
        scheduleAutomaticRetry(ExistingWorkPolicy.REPLACE, delayMillis)

    /** Manual SOS has its own durable retry chain and never depends on the generic offline transport. */
    fun scheduleImmediateManualSos(): ScheduleResult = scheduleManualImmediate(ExistingWorkPolicy.APPEND_OR_REPLACE, 0L)

    /** Delayed manual SOS recovery; REPLACE keeps the earliest/current retry authoritative. */
    fun scheduleDeferredManualSos(delayMillis: Long): ScheduleResult =
        scheduleManualRetry(ExistingWorkPolicy.REPLACE, delayMillis)

    private fun scheduleGenericImmediate(policy: ExistingWorkPolicy, delayMillis: Long): ScheduleResult = try {
        enqueuer.enqueueUnique(GENERIC_IMMEDIATE_WORK_NAME, policy, createGenericSyncWorkRequest(delayMillis))
        ScheduleResult.Scheduled
    } catch (_: IllegalStateException) {
        ScheduleResult.Deferred
    }

    private fun scheduleGenericRetry(policy: ExistingWorkPolicy, delayMillis: Long): ScheduleResult = try {
        enqueuer.enqueueUnique(GENERIC_RETRY_WORK_NAME, policy, createGenericSyncWorkRequest(delayMillis))
        ScheduleResult.Scheduled
    } catch (_: IllegalStateException) {
        ScheduleResult.Deferred
    }

    private fun scheduleAutomaticImmediate(policy: ExistingWorkPolicy, delayMillis: Long): ScheduleResult = try {
        enqueuer.enqueueUnique(AUTOMATIC_SOS_IMMEDIATE_WORK_NAME, policy, createAutomaticSosWorkRequest(delayMillis))
        ScheduleResult.Scheduled
    } catch (_: IllegalStateException) {
        ScheduleResult.Deferred
    }

    private fun scheduleAutomaticRetry(policy: ExistingWorkPolicy, delayMillis: Long): ScheduleResult = try {
        enqueuer.enqueueUnique(AUTOMATIC_SOS_RETRY_WORK_NAME, policy, createAutomaticSosWorkRequest(delayMillis))
        ScheduleResult.Scheduled
    } catch (_: IllegalStateException) {
        ScheduleResult.Deferred
    }

    private fun scheduleManualImmediate(policy: ExistingWorkPolicy, delayMillis: Long): ScheduleResult = try {
        enqueuer.enqueueUnique(MANUAL_SOS_IMMEDIATE_WORK_NAME, policy, createManualSosWorkRequest(delayMillis))
        ScheduleResult.Scheduled
    } catch (_: IllegalStateException) {
        ScheduleResult.Deferred
    }

    private fun scheduleManualRetry(policy: ExistingWorkPolicy, delayMillis: Long): ScheduleResult = try {
        enqueuer.enqueueUnique(MANUAL_SOS_RETRY_WORK_NAME, policy, createManualSosWorkRequest(delayMillis))
        ScheduleResult.Scheduled
    } catch (_: IllegalStateException) {
        ScheduleResult.Deferred
    }

    companion object {
        /** Legacy name kept only so older installed work can finish safely. */
        const val LEGACY_GENERIC_UNIQUE_WORK_NAME = "offline-queue-sync"
        const val GENERIC_IMMEDIATE_WORK_NAME = "offline-queue-sync-immediate"
        const val GENERIC_RETRY_WORK_NAME = "offline-queue-sync-retry"
        @Deprecated("Use GENERIC_IMMEDIATE_WORK_NAME / GENERIC_RETRY_WORK_NAME")
        const val GENERIC_UNIQUE_WORK_NAME = LEGACY_GENERIC_UNIQUE_WORK_NAME
        @Deprecated("Use GENERIC_IMMEDIATE_WORK_NAME")
        const val UNIQUE_WORK_NAME = GENERIC_IMMEDIATE_WORK_NAME
        const val AUTOMATIC_SOS_IMMEDIATE_WORK_NAME = "automatic-sos-sync-immediate"
        const val AUTOMATIC_SOS_RETRY_WORK_NAME = "automatic-sos-sync-retry"
        const val MANUAL_SOS_IMMEDIATE_WORK_NAME = "manual-sos-sync-immediate"
        const val MANUAL_SOS_RETRY_WORK_NAME = "manual-sos-sync-retry"
        @Deprecated("Use AUTOMATIC_SOS_IMMEDIATE_WORK_NAME")
        const val AUTOMATIC_SOS_UNIQUE_WORK_NAME = AUTOMATIC_SOS_IMMEDIATE_WORK_NAME
        const val WORK_BACKOFF_MILLIS = 30_000L

        /**
         * NetworkType.CONNECTED intentionally accepts validated Wi-Fi and mobile/cellular data,
         * including metered networks. Emergency recovery must not require UNMETERED/Wi-Fi.
         */
        @Deprecated("Use createGenericSyncWorkRequest")
        fun createSyncWorkRequest(initialDelayMillis: Long = 0L): OneTimeWorkRequest = createGenericSyncWorkRequest(initialDelayMillis)

        fun createGenericSyncWorkRequest(initialDelayMillis: Long = 0L): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<OfflineQueueSyncWorker>()
                .setConstraints(connectedNetworkConstraint())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WORK_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .setInitialDelay(initialDelayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .build()

        fun createAutomaticSosWorkRequest(initialDelayMillis: Long = 0L): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<AutomaticSosSyncWorker>()
                .setConstraints(connectedNetworkConstraint())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WORK_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .setInitialDelay(initialDelayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .build()

        fun createManualSosWorkRequest(initialDelayMillis: Long = 0L): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ManualSosSyncWorker>()
                .setConstraints(connectedNetworkConstraint())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WORK_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .setInitialDelay(initialDelayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .build()

        private fun connectedNetworkConstraint(): Constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}
