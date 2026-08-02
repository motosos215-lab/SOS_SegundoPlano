package com.example.sos_segundoplano.data.offline

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineQueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: OfflineQueueEntity): Long

    @Transaction
    suspend fun insertBundle(incident: OfflineQueueEntity, request: OfflineQueueEntity): BundleInsertResult.Persisted {
        insertIgnore(incident)
        insertIgnore(request)
        val storedIncident = getByIdempotencyKey(incident.idempotencyKey) ?: throw IncompleteOfflineBundleException()
        val storedRequest = getByIdempotencyKey(request.idempotencyKey) ?: throw IncompleteOfflineBundleException()
        return BundleInsertResult.Persisted(storedIncident.queueItemId, storedRequest.queueItemId)
    }

    @Query("SELECT * FROM offline_queue_items WHERE queueItemId = :queueItemId")
    suspend fun getById(queueItemId: Long): OfflineQueueEntity?

    @Query("SELECT * FROM offline_queue_items WHERE idempotencyKey = :idempotencyKey")
    suspend fun getByIdempotencyKey(idempotencyKey: String): OfflineQueueEntity?

    @Query(
        "SELECT * FROM offline_queue_items " +
            "WHERE status IN (:readyStatuses) AND (nextAttemptAtEpochMillis IS NULL OR nextAttemptAtEpochMillis <= :nowMillis) " +
            "ORDER BY priority ASC, occurredAtEpochMillis ASC, queueItemId ASC LIMIT :limit"
    )
    suspend fun readyCandidates(readyStatuses: List<String>, nowMillis: Long, limit: Int): List<OfflineQueueEntity>

    @Query(
        "SELECT * FROM offline_queue_items " +
            "WHERE eventType = 'minor-event' AND status IN (:readyStatuses) AND (nextAttemptAtEpochMillis IS NULL OR nextAttemptAtEpochMillis <= :nowMillis) " +
            "ORDER BY occurredAtEpochMillis ASC, queueItemId ASC LIMIT 1"
    )
    suspend fun oldestReadyMinorEvent(readyStatuses: List<String>, nowMillis: Long): OfflineQueueEntity?

    @Query(
        "UPDATE offline_queue_items SET status = :inFlightStatus, claimedAtEpochMillis = :nowMillis, claimedBy = :workerId, " +
            "attemptCount = attemptCount + 1, updatedAtEpochMillis = :nowMillis, lastAttemptAtEpochMillis = :nowMillis, nextAttemptAtEpochMillis = NULL, claimToken = :claimToken " +
            "WHERE queueItemId IN (:ids) AND status IN (:readyStatuses)"
    )
    suspend fun markCandidatesInFlight(ids: List<Long>, readyStatuses: List<String>, inFlightStatus: String, nowMillis: Long, workerId: String, claimToken: String): Int

    @Query("SELECT * FROM offline_queue_items WHERE queueItemId IN (:ids) AND status = :inFlightStatus AND claimedBy = :workerId AND claimToken = :claimToken ORDER BY priority ASC, occurredAtEpochMillis ASC, queueItemId ASC")
    suspend fun claimedByWorker(ids: List<Long>, inFlightStatus: String, workerId: String, claimToken: String): List<OfflineQueueEntity>

    @Transaction
    suspend fun claimReadyBatch(workerId: String, nowMillis: Long, limit: Int): List<OfflineQueueEntity> {
        val readyStatuses = listOf("Pending", "RetryPending", "PausedNotConfigured")
        val priorityReady = readyCandidates(readyStatuses, nowMillis, limit)
        val oldestMinor = if (limit > 1) oldestReadyMinorEvent(readyStatuses, nowMillis) else null
        val selected = if (oldestMinor != null && priorityReady.none { it.queueItemId == oldestMinor.queueItemId } && priorityReady.size >= limit) {
            priorityReady.dropLast(1) + oldestMinor
        } else {
            (priorityReady + listOfNotNull(oldestMinor)).distinctBy { it.queueItemId }.take(limit)
        }
        if (selected.isEmpty()) return emptyList()
        val ids = selected.map { it.queueItemId }
        val token = "$workerId-$nowMillis-${ids.joinToString("-")}"
        markCandidatesInFlight(ids, readyStatuses, "InFlight", nowMillis, workerId, token)
        return claimedByWorker(ids, "InFlight", workerId, token)
    }

    @Query(
        "UPDATE offline_queue_items SET status = 'Sent', sentAtEpochMillis = COALESCE(sentAtEpochMillis, :nowMillis), " +
            "updatedAtEpochMillis = :nowMillis, claimedAtEpochMillis = NULL, claimedBy = NULL, ackSanitized = COALESCE(ackSanitized, :ackSanitized), " +
            "claimToken = NULL, lastErrorCategory = NULL, lastErrorCode = NULL, lastErrorMessageSanitized = NULL " +
            "WHERE queueItemId = :queueItemId AND status = 'InFlight' AND claimedBy = :claimedBy AND attemptCount = :attemptCount AND claimedAtEpochMillis = :claimedAtEpochMillis AND claimToken = :claimToken"
    )
    suspend fun markSent(queueItemId: Long, claimedBy: String, attemptCount: Int, claimedAtEpochMillis: Long, claimToken: String, ackSanitized: String, nowMillis: Long): Int

    @Query(
        "UPDATE offline_queue_items SET status = 'RetryPending', nextAttemptAtEpochMillis = :nextAttemptAtMillis, updatedAtEpochMillis = :nowMillis, " +
            "claimedAtEpochMillis = NULL, claimedBy = NULL, claimToken = NULL, lastErrorCategory = :category, lastErrorCode = :code, lastErrorMessageSanitized = :message " +
            "WHERE queueItemId = :queueItemId AND status = 'InFlight' AND claimedBy = :claimedBy AND attemptCount = :attemptCount AND claimedAtEpochMillis = :claimedAtEpochMillis AND claimToken = :claimToken"
    )
    suspend fun markRetry(queueItemId: Long, claimedBy: String, attemptCount: Int, claimedAtEpochMillis: Long, claimToken: String, category: String, code: String?, message: String?, nextAttemptAtMillis: Long, nowMillis: Long): Int

    @Query(
        "UPDATE offline_queue_items SET status = 'PausedNotConfigured', nextAttemptAtEpochMillis = :nextAttemptAtMillis, updatedAtEpochMillis = :nowMillis, " +
            "claimedAtEpochMillis = NULL, claimedBy = NULL, claimToken = NULL, lastErrorCategory = 'NotConfigured', lastErrorCode = :code, lastErrorMessageSanitized = :message " +
            "WHERE queueItemId = :queueItemId AND status = 'InFlight' AND claimedBy = :claimedBy AND attemptCount = :attemptCount AND claimedAtEpochMillis = :claimedAtEpochMillis AND claimToken = :claimToken"
    )
    suspend fun markNotConfigured(queueItemId: Long, claimedBy: String, attemptCount: Int, claimedAtEpochMillis: Long, claimToken: String, code: String?, message: String?, nextAttemptAtMillis: Long, nowMillis: Long): Int

    @Query(
        "UPDATE offline_queue_items SET status = 'FailedPermanent', updatedAtEpochMillis = :nowMillis, claimedAtEpochMillis = NULL, claimedBy = NULL, claimToken = NULL, " +
            "lastErrorCategory = :category, lastErrorCode = :code, lastErrorMessageSanitized = :message " +
            "WHERE queueItemId = :queueItemId AND status = 'InFlight' AND claimedBy = :claimedBy AND attemptCount = :attemptCount AND claimedAtEpochMillis = :claimedAtEpochMillis AND claimToken = :claimToken"
    )
    suspend fun markPermanentFailure(queueItemId: Long, claimedBy: String, attemptCount: Int, claimedAtEpochMillis: Long, claimToken: String, category: String, code: String?, message: String?, nowMillis: Long): Int

    @Query(
        "UPDATE offline_queue_items SET status = 'RetryPending', updatedAtEpochMillis = :nowMillis, claimedAtEpochMillis = NULL, claimedBy = NULL, " +
            "claimToken = NULL, lastErrorCategory = 'Transport', lastErrorCode = 'lease_timeout', lastErrorMessageSanitized = 'inflight_lease_expired' " +
            "WHERE status = 'InFlight' AND claimedAtEpochMillis IS NOT NULL AND claimedAtEpochMillis <= :abandonedBeforeMillis"
    )
    suspend fun recoverAbandoned(abandonedBeforeMillis: Long, nowMillis: Long): Int

    @Query("SELECT MIN(nextAttemptAtEpochMillis) FROM offline_queue_items WHERE status IN ('Pending', 'RetryPending', 'PausedNotConfigured') AND nextAttemptAtEpochMillis IS NOT NULL")
    suspend fun earliestPendingAttemptAt(): Long?

    @Query("SELECT COUNT(*) FROM offline_queue_items WHERE status IN ('Pending', 'RetryPending', 'PausedNotConfigured') AND (nextAttemptAtEpochMillis IS NULL OR nextAttemptAtEpochMillis <= :nowMillis)")
    suspend fun countReadyPending(nowMillis: Long): Int

    @Query("DELETE FROM offline_queue_items WHERE status = 'Sent' AND sentAtEpochMillis IS NOT NULL AND sentAtEpochMillis < :cutoffEpochMillis")
    suspend fun deleteSentBefore(cutoffEpochMillis: Long): Int

    @Query("SELECT COUNT(*) FROM offline_queue_items WHERE status IN ('Pending', 'RetryPending', 'InFlight', 'PausedNotConfigured')")
    suspend fun countUnfinished(): Int

    @Query("SELECT status, COUNT(*) AS count FROM offline_queue_items GROUP BY status")
    fun observeStatusCounts(): Flow<List<QueueStatusCount>>

    @Query("SELECT COUNT(*) FROM offline_sync_errors")
    fun observeErrorCount(): Flow<Int>

    @Query("SELECT MAX(sentAtEpochMillis) AS lastSuccessfulSyncAt, MAX(lastAttemptAtEpochMillis) AS lastAttemptAt FROM offline_queue_items")
    fun observeSyncStats(): Flow<QueueSyncStats>
}
