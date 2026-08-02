package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.domain.offline.ClaimedOfflineQueueItem
import com.example.sos_segundoplano.domain.offline.OfflineEventTransport
import com.example.sos_segundoplano.domain.offline.OfflineEventTransportResult
import com.example.sos_segundoplano.domain.offline.OfflineQueueConfig
import com.example.sos_segundoplano.domain.offline.OfflineQueuePolicy
import com.example.sos_segundoplano.domain.offline.OfflineQueueSyncResult
import com.example.sos_segundoplano.domain.offline.OfflineQueueTransitionResult
import com.example.sos_segundoplano.domain.offline.OfflineSyncErrorCategory
import com.example.sos_segundoplano.domain.offline.WallClock

class OfflineQueueSyncProcessor(
    private val repository: OfflineQueueSyncRepository,
    private val transport: OfflineEventTransport,
    private val policy: OfflineQueuePolicy,
    private val clock: WallClock,
    private val config: OfflineQueueConfig
) {
    suspend fun process(workerId: String): OfflineQueueSyncResult {
        val startedAt = currentTimeOrFailure() ?: return OfflineQueueSyncResult.InitializationFailure("offline_queue_clock_unavailable")
        try {
            repository.recoverAbandonedItems(startedAt)
            repository.cleanSentBefore(startedAt - config.sentRetentionMillis)
        } catch (_: IllegalStateException) {
            return OfflineQueueSyncResult.InitializationFailure("offline_queue_storage_unavailable")
        }

        val claimed = try {
            repository.claimReadyBatch(workerId, startedAt)
        } catch (_: IllegalStateException) {
            return OfflineQueueSyncResult.InitializationFailure("offline_queue_claim_failed")
        }

        if (claimed.isEmpty()) return emptyBatchResult(startedAt)

        var retryRequired = false
        var deferredUntil: Long? = null
        for (item in claimed) {
            when (val itemResult = processItem(item)) {
                is ItemResult.Retry -> retryRequired = true
                is ItemResult.Deferred -> deferredUntil = minOf(deferredUntil ?: itemResult.epochMillis, itemResult.epochMillis)
                ItemResult.Done -> Unit
            }
        }

        return when {
            retryRequired -> OfflineQueueSyncResult.RetryRequired
            deferredUntil != null -> OfflineQueueSyncResult.DeferredUntil(deferredUntil)
            else -> OfflineQueueSyncResult.Completed
        }
    }

    private suspend fun emptyBatchResult(nowMillis: Long): OfflineQueueSyncResult {
        val nextAttemptAt = repository.earliestPendingAttemptAt(nowMillis)
        return when {
            nextAttemptAt == null && !repository.hasUnfinishedWork() -> OfflineQueueSyncResult.NothingToDo
            nextAttemptAt == null || nextAttemptAt <= nowMillis -> OfflineQueueSyncResult.RetryRequired
            else -> OfflineQueueSyncResult.DeferredUntil(nextAttemptAt)
        }
    }

    private suspend fun processItem(item: ClaimedOfflineQueueItem): ItemResult {
        val attemptNow = currentTimeOrFailure() ?: return ItemResult.Retry
        val payload = when (val decrypted = repository.decryptPayload(item)) {
            is OfflineCryptoResult.Success -> decrypted.value
            is OfflineCryptoResult.Failure -> {
                repository.markPermanentFailure(item.claim, decrypted.category, "decrypt", decrypted.sanitizedMessage, attemptNow)
                return ItemResult.Done
            }
        }

        return when (val result = transport.send(item.item, payload)) {
            is OfflineEventTransportResult.Acknowledged -> {
                if (result.ackId.isBlank()) {
                    retryOrPermanent(item, OfflineSyncErrorCategory.Transport, "empty_ack", "transport_ack_empty", null, currentTimeOrFailure() ?: attemptNow)
                } else {
                    transitionApplied(repository.markSent(item.claim, result.ackId, currentTimeOrFailure() ?: attemptNow))
                    ItemResult.Done
                }
            }

            is OfflineEventTransportResult.TransientFailure -> retryOrPermanent(
                item,
                result.category,
                result.code,
                result.sanitizedMessage,
                result.retryAfterMillis,
                currentTimeOrFailure() ?: attemptNow
            )

            is OfflineEventTransportResult.PermanentFailure -> {
                transitionApplied(repository.markPermanentFailure(item.claim, result.category, result.code, result.sanitizedMessage, currentTimeOrFailure() ?: attemptNow))
                ItemResult.Done
            }

            is OfflineEventTransportResult.NotConfigured -> {
                val now = currentTimeOrFailure() ?: attemptNow
                val nextAttemptAt = policy.nextRetryAt(now, item.item.attemptCount, null)
                if (transitionApplied(repository.markNotConfigured(item.claim, "not_configured", result.sanitizedMessage, nextAttemptAt, now))) {
                    ItemResult.Deferred(nextAttemptAt)
                } else {
                    ItemResult.Done
                }
            }
        }
    }

    private suspend fun retryOrPermanent(
        item: ClaimedOfflineQueueItem,
        category: OfflineSyncErrorCategory,
        code: String?,
        message: String?,
        retryAfterMillis: Long?,
        nowMillis: Long
    ): ItemResult {
        return if (policy.shouldRetry(item.item.attemptCount)) {
            val nextAttemptAt = policy.nextRetryAt(nowMillis, item.item.attemptCount, retryAfterMillis)
            if (transitionApplied(repository.markRetry(item.claim, category, code, message, nextAttemptAt, nowMillis))) ItemResult.Retry else ItemResult.Done
        } else {
            transitionApplied(repository.markPermanentFailure(item.claim, category, code, message, nowMillis))
            ItemResult.Done
        }
    }

    private fun transitionApplied(result: OfflineQueueTransitionResult): Boolean = when (result) {
        OfflineQueueTransitionResult.Applied -> true
        OfflineQueueTransitionResult.AlreadySent,
        OfflineQueueTransitionResult.InvalidAcknowledgement,
        OfflineQueueTransitionResult.Missing,
        OfflineQueueTransitionResult.StaleClaim,
        is OfflineQueueTransitionResult.InvalidState -> false
    }

    private fun currentTimeOrFailure(): Long? = try {
        clock.currentTimeMillis()
    } catch (_: IllegalStateException) {
        null
    }

    private sealed interface ItemResult {
        data object Done : ItemResult
        data object Retry : ItemResult
        data class Deferred(val epochMillis: Long) : ItemResult
    }
}
