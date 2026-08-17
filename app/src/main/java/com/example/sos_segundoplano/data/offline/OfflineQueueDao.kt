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

    @Query(
        "UPDATE offline_queue_items SET encryptedPayload = :encryptedPayload, encryptionNonce = :encryptionNonce, " +
            "encryptionKeyVersion = :encryptionKeyVersion, occurredAtEpochMillis = :occurredAtEpochMillis, updatedAtEpochMillis = :nowMillis " +
            "WHERE idempotencyKey = :idempotencyKey AND ownerUserId IS :ownerUserId AND bundleKey IS :bundleKey"
    )
    suspend fun updatePayload(
        idempotencyKey: String,
        ownerUserId: String?,
        bundleKey: String?,
        encryptedPayload: ByteArray,
        encryptionNonce: ByteArray,
        encryptionKeyVersion: Int,
        occurredAtEpochMillis: Long,
        nowMillis: Long
    ): Int

    @Transaction
    suspend fun updateBundlePayload(incident: OfflineQueueEntity, request: OfflineQueueEntity, nowMillis: Long) {
        if (updatePayload(incident.idempotencyKey, incident.ownerUserId, incident.bundleKey, incident.encryptedPayload, incident.encryptionNonce, incident.encryptionKeyVersion, incident.occurredAtEpochMillis, nowMillis) != 1 ||
            updatePayload(request.idempotencyKey, request.ownerUserId, request.bundleKey, request.encryptedPayload, request.encryptionNonce, request.encryptionKeyVersion, request.occurredAtEpochMillis, nowMillis) != 1
        ) throw IncompleteOfflineBundleException()
    }

    @Query(
        "SELECT * FROM offline_queue_items WHERE ownerUserId = :ownerUserId AND bundleKey = :bundleKey " +
            "ORDER BY queueItemId ASC"
    )
    suspend fun bundleForOwner(ownerUserId: String, bundleKey: String): List<OfflineQueueEntity>

    @Query(
        "SELECT DISTINCT bundleKey FROM offline_queue_items WHERE ownerUserId = :ownerUserId " +
            "AND bundleKey IS NOT NULL ORDER BY bundleKey ASC"
    )
    suspend fun recoverableBundleKeysForOwner(ownerUserId: String): List<String>

    @Query(
        "SELECT DISTINCT bundleKey FROM offline_queue_items WHERE ownerUserId = :ownerUserId AND bundleKey IS NOT NULL " +
            "AND eventType = 'local-incident' AND status = 'Sent' AND remoteIncidentId IS NOT NULL AND remoteSuccessAtEpochMillis IS NOT NULL"
    )
    suspend fun confirmedAutomaticBundleKeysForOwner(ownerUserId: String): List<String>

    @Query("SELECT * FROM offline_queue_items WHERE queueItemId = :queueItemId")
    suspend fun getById(queueItemId: Long): OfflineQueueEntity?

    @Query("SELECT * FROM offline_queue_items WHERE idempotencyKey = :idempotencyKey")
    suspend fun getByIdempotencyKey(idempotencyKey: String): OfflineQueueEntity?

    @Query(
        "SELECT * FROM offline_queue_items " +
            "WHERE status IN (:readyStatuses) AND (nextAttemptAtEpochMillis IS NULL OR nextAttemptAtEpochMillis <= :nowMillis) " +
            "AND NOT (ownerUserId IS NOT NULL AND bundleKey IS NOT NULL AND eventType IN ('local-incident', 'alert-dispatch-request')) " +
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

    @Query(
        "SELECT bundleKey FROM offline_queue_items WHERE ownerUserId = :ownerUserId AND bundleKey IS NOT NULL " +
            "AND status IN ('Pending', 'RetryPending', 'PausedNotConfigured') " +
            "AND (nextAttemptAtEpochMillis IS NULL OR nextAttemptAtEpochMillis <= :nowMillis) " +
            "AND eventType IN ('local-incident', 'alert-dispatch-request') " +
            "GROUP BY bundleKey HAVING COUNT(*) = 2 AND COUNT(DISTINCT eventType) = 2 ORDER BY MIN(occurredAtEpochMillis) ASC LIMIT 1"
    )
    suspend fun nextAutomaticBundleKey(ownerUserId: String, nowMillis: Long): String?

    @Query(
        "SELECT MIN(nextAttemptAtEpochMillis) FROM offline_queue_items WHERE ownerUserId = :ownerUserId " +
            "AND bundleKey IS NOT NULL AND status = 'RetryPending' AND nextAttemptAtEpochMillis IS NOT NULL " +
            "AND eventType IN ('local-incident', 'alert-dispatch-request') AND bundleKey IN (" +
            "SELECT bundleKey FROM offline_queue_items WHERE ownerUserId = :ownerUserId AND bundleKey IS NOT NULL " +
            "AND status = 'RetryPending' AND eventType IN ('local-incident', 'alert-dispatch-request') " +
            "GROUP BY bundleKey HAVING COUNT(*) = 2 AND COUNT(DISTINCT eventType) = 2)"
    )
    suspend fun earliestAutomaticBundleRetryForOwner(ownerUserId: String): Long?

    @Query(
        "UPDATE offline_queue_items SET status = 'InFlight', claimedAtEpochMillis = :nowMillis, claimedBy = :workerId, " +
            "attemptCount = attemptCount + 1, updatedAtEpochMillis = :nowMillis, lastAttemptAtEpochMillis = :nowMillis, " +
            "nextAttemptAtEpochMillis = NULL, claimToken = :claimToken " +
            "WHERE ownerUserId = :ownerUserId AND bundleKey = :bundleKey AND eventType IN ('local-incident', 'alert-dispatch-request') " +
            "AND status IN ('Pending', 'RetryPending', 'PausedNotConfigured') " +
            "AND (nextAttemptAtEpochMillis IS NULL OR nextAttemptAtEpochMillis <= :nowMillis)"
    )
    suspend fun claimAutomaticBundleRows(ownerUserId: String, bundleKey: String, workerId: String, claimToken: String, nowMillis: Long): Int

    @Query(
        "SELECT * FROM offline_queue_items WHERE ownerUserId = :ownerUserId AND bundleKey = :bundleKey AND status = 'InFlight' " +
            "AND claimedBy = :workerId AND claimToken = :claimToken ORDER BY queueItemId ASC"
    )
    suspend fun claimedAutomaticBundleRows(ownerUserId: String, bundleKey: String, workerId: String, claimToken: String): List<OfflineQueueEntity>

    @Transaction
    suspend fun claimAutomaticBundle(ownerUserId: String, bundleKey: String, workerId: String, nowMillis: Long): List<OfflineQueueEntity> {
        val token = "$workerId-$nowMillis-$bundleKey"
        if (claimAutomaticBundleRows(ownerUserId, bundleKey, workerId, token, nowMillis) != 2) return emptyList()
        val rows = claimedAutomaticBundleRows(ownerUserId, bundleKey, workerId, token)
        return if (rows.size == 2 && rows.map { it.eventType }.toSet() == setOf("local-incident", "alert-dispatch-request")) rows else emptyList()
    }

    @Transaction
    suspend fun claimNextAutomaticBundle(ownerUserId: String, workerId: String, nowMillis: Long): List<OfflineQueueEntity> {
        val bundleKey = nextAutomaticBundleKey(ownerUserId, nowMillis) ?: return emptyList()
        return claimAutomaticBundle(ownerUserId, bundleKey, workerId, nowMillis)
    }

    @Query(
        "UPDATE offline_queue_items SET status = 'Sent', sentAtEpochMillis = COALESCE(sentAtEpochMillis, :nowMillis), updatedAtEpochMillis = :nowMillis, " +
            "claimedAtEpochMillis = NULL, claimedBy = NULL, claimToken = NULL, lastErrorCategory = NULL, lastErrorCode = NULL, " +
            "lastErrorMessageSanitized = NULL, ackSanitized = 'automatic_sos_ack', " +
            "remoteIncidentId = :remoteIncidentId, remoteAlertDispatchId = :remoteAlertDispatchId, remoteSuccessAtEpochMillis = :nowMillis " +
            "WHERE ownerUserId = :ownerUserId AND bundleKey = :bundleKey AND eventType IN ('local-incident', 'alert-dispatch-request') " +
            "AND status = 'InFlight' AND claimedBy = :workerId AND claimToken = :claimToken"
    )
    suspend fun acknowledgeAutomaticBundle(ownerUserId: String, bundleKey: String, workerId: String, claimToken: String, remoteIncidentId: String, remoteAlertDispatchId: String, nowMillis: Long): Int

    @Transaction
    suspend fun acknowledgeAutomaticBundleAtomically(ownerUserId: String, bundleKey: String, workerId: String, claimToken: String, remoteIncidentId: String, remoteAlertDispatchId: String, nowMillis: Long): Boolean =
        acknowledgeAutomaticBundle(ownerUserId, bundleKey, workerId, claimToken, remoteIncidentId, remoteAlertDispatchId, nowMillis) == 2

    @Query(
        "UPDATE offline_queue_items SET status = :status, nextAttemptAtEpochMillis = :nextAttemptAtMillis, updatedAtEpochMillis = :nowMillis, " +
            "claimedAtEpochMillis = NULL, claimedBy = NULL, claimToken = NULL, lastErrorCategory = :category, lastErrorCode = :code, lastErrorMessageSanitized = :message " +
            "WHERE ownerUserId = :ownerUserId AND bundleKey = :bundleKey AND eventType IN ('local-incident', 'alert-dispatch-request') " +
            "AND status = 'InFlight' AND claimedBy = :workerId AND claimToken = :claimToken"
    )
    suspend fun releaseAutomaticBundle(ownerUserId: String, bundleKey: String, workerId: String, claimToken: String, status: String, category: String, code: String, message: String, nextAttemptAtMillis: Long?, nowMillis: Long): Int

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

    @Query("SELECT MIN(nextAttemptAtEpochMillis) FROM offline_queue_items WHERE status IN ('Pending', 'RetryPending', 'PausedNotConfigured') AND nextAttemptAtEpochMillis IS NOT NULL AND NOT (ownerUserId IS NOT NULL AND bundleKey IS NOT NULL AND eventType IN ('local-incident', 'alert-dispatch-request'))")
    suspend fun earliestPendingAttemptAt(): Long?

    @Query("SELECT COUNT(*) FROM offline_queue_items WHERE status IN ('Pending', 'RetryPending', 'PausedNotConfigured') AND (nextAttemptAtEpochMillis IS NULL OR nextAttemptAtEpochMillis <= :nowMillis) AND NOT (ownerUserId IS NOT NULL AND bundleKey IS NOT NULL AND eventType IN ('local-incident', 'alert-dispatch-request'))")
    suspend fun countReadyPending(nowMillis: Long): Int

    @Query("DELETE FROM offline_queue_items WHERE status = 'Sent' AND sentAtEpochMillis IS NOT NULL AND sentAtEpochMillis < :cutoffEpochMillis")
    suspend fun deleteSentBefore(cutoffEpochMillis: Long): Int

    @Query("SELECT COUNT(*) FROM offline_queue_items WHERE status IN ('Pending', 'RetryPending', 'InFlight', 'PausedNotConfigured') AND NOT (ownerUserId IS NOT NULL AND bundleKey IS NOT NULL AND eventType IN ('local-incident', 'alert-dispatch-request'))")
    suspend fun countUnfinished(): Int

    @Query("SELECT status, COUNT(*) AS count FROM offline_queue_items GROUP BY status")
    fun observeStatusCounts(): Flow<List<QueueStatusCount>>

    @Query("SELECT COUNT(*) FROM offline_sync_errors")
    fun observeErrorCount(): Flow<Int>

    @Query("SELECT MAX(sentAtEpochMillis) AS lastSuccessfulSyncAt, MAX(lastAttemptAtEpochMillis) AS lastAttemptAt FROM offline_queue_items")
    fun observeSyncStats(): Flow<QueueSyncStats>
}
