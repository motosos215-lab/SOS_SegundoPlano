package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.domain.offline.ClaimedOfflineQueueItem
import com.example.sos_segundoplano.domain.offline.OfflineQueueClaim
import com.example.sos_segundoplano.domain.offline.OfflineQueueTransitionResult
import com.example.sos_segundoplano.domain.offline.OfflineSyncErrorCategory
import com.example.sos_segundoplano.domain.offline.OfflineSyncPayload

interface OfflineQueueSyncRepository {
    suspend fun recoverAbandonedItems(nowMillis: Long): Int
    suspend fun cleanSentBefore(cutoffEpochMillis: Long): Int
    suspend fun claimReadyBatch(workerId: String, nowMillis: Long): List<ClaimedOfflineQueueItem>
    suspend fun decryptPayload(item: ClaimedOfflineQueueItem): OfflineCryptoResult<OfflineSyncPayload>
    suspend fun markSent(claim: OfflineQueueClaim, ackSanitized: String, nowMillis: Long): OfflineQueueTransitionResult
    suspend fun markRetry(claim: OfflineQueueClaim, category: OfflineSyncErrorCategory, code: String?, sanitizedMessage: String?, nextAttemptAtMillis: Long, nowMillis: Long): OfflineQueueTransitionResult
    suspend fun markNotConfigured(claim: OfflineQueueClaim, code: String?, sanitizedMessage: String?, nextAttemptAtMillis: Long, nowMillis: Long): OfflineQueueTransitionResult
    suspend fun markPermanentFailure(claim: OfflineQueueClaim, category: OfflineSyncErrorCategory, code: String?, sanitizedMessage: String?, nowMillis: Long): OfflineQueueTransitionResult
    suspend fun hasUnfinishedWork(): Boolean
    suspend fun earliestPendingAttemptAt(nowMillis: Long): Long?
}
