package com.example.sos_segundoplano.data.offline

import android.database.sqlite.SQLiteException
import com.example.sos_segundoplano.domain.offline.ClaimedOfflineQueueItem
import com.example.sos_segundoplano.domain.offline.OfflineQueueClaim
import com.example.sos_segundoplano.domain.offline.OfflineQueueConfig
import com.example.sos_segundoplano.domain.offline.OfflineQueueEnqueueResult
import com.example.sos_segundoplano.domain.offline.OfflineQueueRepository
import com.example.sos_segundoplano.domain.offline.OfflineQueueStatus
import com.example.sos_segundoplano.domain.offline.OfflineQueueSummary
import com.example.sos_segundoplano.domain.offline.OfflineQueueTransitionResult
import com.example.sos_segundoplano.domain.offline.OfflineSyncErrorCategory
import com.example.sos_segundoplano.domain.offline.OfflineSyncPayload
import com.example.sos_segundoplano.domain.offline.SyncErrorRecord
import com.example.sos_segundoplano.domain.offline.WallClock
import com.example.sos_segundoplano.domain.offline.idempotencyKey
import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.validation.MinorEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

class RoomOfflineQueueRepository(
    private val queueDao: OfflineQueueDao,
    private val errorDao: SyncErrorDao,
    private val crypto: OfflineQueueCrypto,
    private val serializer: OfflineQueueSerializer,
    private val clock: WallClock,
    private val config: OfflineQueueConfig = OfflineQueueConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scheduler: OfflineQueueWorkScheduler? = null
) : OfflineQueueRepository, OfflineQueueSyncRepository {
    override suspend fun enqueueMinorEvent(event: MinorEvent): OfflineQueueEnqueueResult = enqueue(event.toSyncPayload(clock))

    override suspend fun enqueueIncident(incident: LocalIncident): OfflineQueueEnqueueResult = enqueue(incident.toSyncPayload(clock))

    override suspend fun enqueueAlertRequest(request: AlertDispatchRequest): OfflineQueueEnqueueResult = enqueue(request.toSyncPayload(clock))

    override suspend fun enqueueIncidentBundle(incident: LocalIncident, request: AlertDispatchRequest): OfflineQueueEnqueueResult = withContext(dispatcher) {
        try {
            val now = clock.currentTimeMillis()
            val incidentPayload = incident.toSyncPayload(clock)
            val requestPayload = request.toSyncPayload(clock)
            val incidentEntity = buildEntity(incidentPayload, now) ?: return@withContext OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Encryption, "payload_encryption_failed")
            val requestEntity = buildEntity(requestPayload, now) ?: return@withContext OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Encryption, "payload_encryption_failed")
            val persisted = queueDao.insertBundle(incidentEntity, requestEntity)
            scheduleAfterPersistence(OfflineQueueEnqueueResult.PersistedAndScheduled(persisted.requestQueueItemId, requestEntity.idempotencyKey))
        } catch (_: IncompleteOfflineBundleException) {
            OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_bundle_incomplete")
        } catch (_: SQLiteException) {
            OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_queue_storage_failed")
        } catch (_: IllegalStateException) {
            OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_queue_storage_unavailable")
        }
    }

    suspend fun enqueue(payload: OfflineSyncPayload): OfflineQueueEnqueueResult = withContext(dispatcher) {
        scheduleAfterPersistence(enqueuePayloadWithoutScheduling(payload))
    }

    private suspend fun enqueuePayloadWithoutScheduling(payload: OfflineSyncPayload): OfflineQueueEnqueueResult {
        return try {
            val now = clock.currentTimeMillis()
            val entity = buildEntity(payload, now) ?: return OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Encryption, "payload_encryption_failed")
            val insertedId = queueDao.insertIgnore(entity)
            if (insertedId == -1L) OfflineQueueEnqueueResult.AlreadyExists(entity.idempotencyKey) else OfflineQueueEnqueueResult.PersistedAndScheduled(insertedId, entity.idempotencyKey)
        } catch (_: SQLiteException) {
            OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_queue_storage_failed")
        } catch (_: IllegalStateException) {
            OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_queue_storage_unavailable")
        }
    }

    private suspend fun buildEntity(payload: OfflineSyncPayload, nowMillis: Long): OfflineQueueEntity? {
        val key = idempotencyKey(payload.eventType, payload.sourceEventId, payload.schemaVersion)
        val associatedData = OfflineQueueAssociatedData(key, payload.eventType.wireName, payload.schemaVersion, config.encryptionKeyVersion)
        val serialized = try {
            serializer.serialize(payload)
        } catch (_: IllegalArgumentException) {
            recordEnqueueFailure(key, payload, OfflineSyncErrorCategory.Serialization, "payload_serialization_failed", nowMillis)
            return null
        }
        val encrypted = when (val result = crypto.encrypt(serialized, associatedData)) {
            is OfflineCryptoResult.Success -> result.value
            is OfflineCryptoResult.Failure -> {
                recordEnqueueFailure(key, payload, result.category, result.sanitizedMessage, nowMillis)
                return null
            }
        }
        return payload.toNewEntity(encrypted, key, nowMillis)
    }

    private fun scheduleAfterPersistence(result: OfflineQueueEnqueueResult): OfflineQueueEnqueueResult = when (result) {
        is OfflineQueueEnqueueResult.PersistedAndScheduled -> when (scheduleImmediateSyncSafely()) {
            ScheduleResult.Scheduled -> result
            ScheduleResult.Deferred, ScheduleResult.Unavailable -> OfflineQueueEnqueueResult.PersistedSchedulingDeferred(result.queueItemId, result.idempotencyKey, "offline_sync_schedule_deferred")
        }
        is OfflineQueueEnqueueResult.AlreadyExists -> {
            scheduleImmediateSyncSafely()
            result
        }
        else -> result
    }

    fun scheduleImmediateSyncSafely(): ScheduleResult = scheduler?.scheduleImmediateSync() ?: ScheduleResult.Unavailable

    override suspend fun claimReadyBatch(workerId: String, nowMillis: Long): List<ClaimedOfflineQueueItem> = withContext(dispatcher) {
        queueDao.claimReadyBatch(workerId, nowMillis, config.batchSize).mapNotNull { entity ->
            when (val mapped = entity.toClaimedDomainResult()) {
                is ClaimedOfflineQueueMappingResult.Success -> mapped.item
                ClaimedOfflineQueueMappingResult.InvalidClaim -> {
                    markInvalidClaimEntityPermanent(entity, nowMillis)
                    null
                }
                is ClaimedOfflineQueueMappingResult.InvalidItem -> {
                    markInvalidClaimEntityPermanent(entity, nowMillis)
                    null
                }
            }
        }
    }

    private suspend fun markInvalidClaimEntityPermanent(entity: OfflineQueueEntity, nowMillis: Long) {
        val claimedBy = entity.claimedBy ?: return
        val claimedAt = entity.claimedAtEpochMillis ?: return
        val claimToken = entity.claimToken ?: return
        if (claimedBy.isBlank() || claimToken.isBlank()) return
        val updated = queueDao.markPermanentFailure(
            entity.queueItemId,
            claimedBy,
            entity.attemptCount,
            claimedAt,
            claimToken,
            OfflineSyncErrorCategory.Serialization.name,
            "metadata_invalid",
            "offline_queue_metadata_invalid",
            nowMillis
        )
        if (updated > 0) recordError(entity, OfflineSyncErrorCategory.Serialization, "metadata_invalid", "offline_queue_metadata_invalid", nowMillis, isPermanent = true)
    }

    override suspend fun markSent(claim: OfflineQueueClaim, ackSanitized: String, nowMillis: Long): OfflineQueueTransitionResult = withContext(dispatcher) {
        if (ackSanitized.isBlank()) return@withContext OfflineQueueTransitionResult.InvalidAcknowledgement
        val item = queueDao.getById(claim.queueItemId)
        val updated = queueDao.markSent(claim.queueItemId, claim.claimedBy, claim.attemptCount, claim.claimedAtEpochMillis, claim.claimToken, sanitize(ackSanitized).orEmpty(), nowMillis)
        transitionResult(item, updated, claim)
    }

    override suspend fun markRetry(
        claim: OfflineQueueClaim,
        category: OfflineSyncErrorCategory,
        code: String?,
        sanitizedMessage: String?,
        nextAttemptAtMillis: Long,
        nowMillis: Long
    ): OfflineQueueTransitionResult = withContext(dispatcher) {
        val item = queueDao.getById(claim.queueItemId)
        val updated = queueDao.markRetry(claim.queueItemId, claim.claimedBy, claim.attemptCount, claim.claimedAtEpochMillis, claim.claimToken, category.name, sanitize(code), sanitize(sanitizedMessage), nextAttemptAtMillis, nowMillis)
        val result = transitionResult(item, updated, claim)
        if (result == OfflineQueueTransitionResult.Applied && item != null) recordError(item, category, code, sanitizedMessage, nowMillis, isPermanent = false)
        result
    }

    override suspend fun markNotConfigured(
        claim: OfflineQueueClaim,
        code: String?,
        sanitizedMessage: String?,
        nextAttemptAtMillis: Long,
        nowMillis: Long
    ): OfflineQueueTransitionResult = withContext(dispatcher) {
        val item = queueDao.getById(claim.queueItemId)
        val updated = queueDao.markNotConfigured(claim.queueItemId, claim.claimedBy, claim.attemptCount, claim.claimedAtEpochMillis, claim.claimToken, sanitize(code), sanitize(sanitizedMessage), nextAttemptAtMillis, nowMillis)
        val result = transitionResult(item, updated, claim)
        if (result == OfflineQueueTransitionResult.Applied && item != null) recordError(item, OfflineSyncErrorCategory.NotConfigured, code, sanitizedMessage, nowMillis, isPermanent = false)
        result
    }

    override suspend fun markPermanentFailure(
        claim: OfflineQueueClaim,
        category: OfflineSyncErrorCategory,
        code: String?,
        sanitizedMessage: String?,
        nowMillis: Long
    ): OfflineQueueTransitionResult = withContext(dispatcher) {
        val item = queueDao.getById(claim.queueItemId)
        val updated = queueDao.markPermanentFailure(claim.queueItemId, claim.claimedBy, claim.attemptCount, claim.claimedAtEpochMillis, claim.claimToken, category.name, sanitize(code), sanitize(sanitizedMessage), nowMillis)
        val result = transitionResult(item, updated, claim)
        if (result == OfflineQueueTransitionResult.Applied && item != null) recordError(item, category, code, sanitizedMessage, nowMillis, isPermanent = true)
        result
    }

    override suspend fun recoverAbandonedItems(nowMillis: Long): Int = withContext(dispatcher) {
        queueDao.recoverAbandoned(nowMillis - config.inFlightLeaseTimeoutMillis, nowMillis)
    }

    override fun observeSummary(): Flow<OfflineQueueSummary> = combine(
        queueDao.observeStatusCounts(),
        queueDao.observeErrorCount(),
        queueDao.observeSyncStats()
    ) { counts, errorCount, stats ->
        val byStatus = counts.associate { it.status to it.count }
        OfflineQueueSummary(
            pendingCount = byStatus[OfflineQueueStatus.Pending.name] ?: 0,
            inFlightCount = byStatus[OfflineQueueStatus.InFlight.name] ?: 0,
            retryPendingCount = byStatus[OfflineQueueStatus.RetryPending.name] ?: 0,
            notConfiguredCount = byStatus[OfflineQueueStatus.PausedNotConfigured.name] ?: 0,
            sentCount = byStatus[OfflineQueueStatus.Sent.name] ?: 0,
            permanentFailureCount = byStatus[OfflineQueueStatus.FailedPermanent.name] ?: 0,
            syncErrorCount = errorCount,
            lastSuccessfulSyncAt = stats.lastSuccessfulSyncAt,
            lastAttemptAt = stats.lastAttemptAt
        )
    }

    override suspend fun getErrors(limit: Int): List<SyncErrorRecord> = withContext(dispatcher) {
        errorDao.latest(limit.coerceIn(1, config.maxErrorRecords)).mapNotNull { it.toDomain() }
    }

    override suspend fun cleanSentBefore(cutoffEpochMillis: Long): Int = withContext(dispatcher) {
        queueDao.deleteSentBefore(cutoffEpochMillis)
    }

    override suspend fun decryptPayload(item: ClaimedOfflineQueueItem): OfflineCryptoResult<OfflineSyncPayload> = withContext(dispatcher) {
        val associatedData = OfflineQueueAssociatedData(item.item.idempotencyKey, item.item.eventType.wireName, item.item.payloadSchemaVersion, item.encryptionKeyVersion)
        when (val result = crypto.decrypt(item.encryptedPayload, item.encryptionNonce, associatedData)) {
            is OfflineCryptoResult.Failure -> result
            is OfflineCryptoResult.Success -> serializer.deserialize(result.value)?.let { OfflineCryptoResult.Success(it) }
                ?: OfflineCryptoResult.Failure(OfflineSyncErrorCategory.Serialization, "payload_schema_unsupported")
        }
    }

    override suspend fun hasUnfinishedWork(): Boolean = withContext(dispatcher) {
        queueDao.countUnfinished() > 0
    }

    override suspend fun earliestPendingAttemptAt(nowMillis: Long): Long? = withContext(dispatcher) {
        if (queueDao.countReadyPending(nowMillis) > 0) nowMillis else queueDao.earliestPendingAttemptAt()
    }

    private suspend fun recordError(
        item: OfflineQueueEntity,
        category: OfflineSyncErrorCategory,
        code: String?,
        message: String?,
        nowMillis: Long,
        isPermanent: Boolean
    ) {
        errorDao.insertAndTrim(
            SyncErrorEntity(
                queueItemId = item.queueItemId,
                idempotencyKey = item.idempotencyKey,
                eventType = item.eventType,
                category = category.name,
                code = sanitize(code),
                sanitizedMessage = sanitize(message),
                attemptNumber = item.attemptCount,
                occurredAtEpochMillis = nowMillis,
                isPermanent = isPermanent
            ),
            config.maxErrorRecords
        )
    }

    private suspend fun recordEnqueueFailure(idempotencyKey: String, payload: OfflineSyncPayload, category: OfflineSyncErrorCategory, message: String?, nowMillis: Long) {
        errorDao.insertAndTrim(
            SyncErrorEntity(
                queueItemId = 0L,
                idempotencyKey = idempotencyKey,
                eventType = payload.eventType.wireName,
                category = category.name,
                code = "enqueue_failed",
                sanitizedMessage = sanitize(message),
                attemptNumber = 0,
                occurredAtEpochMillis = nowMillis,
                isPermanent = true
            ),
            config.maxErrorRecords
        )
    }

    private fun sanitize(value: String?): String? = value
        ?.replace(Regex("[\r\n\t]"), " ")
        ?.take(160)

    private fun transitionResult(item: OfflineQueueEntity?, updated: Int, claim: OfflineQueueClaim): OfflineQueueTransitionResult = when {
        updated > 0 -> OfflineQueueTransitionResult.Applied
        item == null -> OfflineQueueTransitionResult.Missing
        item.status == OfflineQueueStatus.Sent.name -> OfflineQueueTransitionResult.AlreadySent
        item.status != OfflineQueueStatus.InFlight.name -> OfflineQueueTransitionResult.InvalidState(OfflineQueueStatus.values().firstOrNull { it.name == item.status })
        item.claimedBy != claim.claimedBy || item.attemptCount != claim.attemptCount || item.claimedAtEpochMillis != claim.claimedAtEpochMillis || item.claimToken != claim.claimToken -> OfflineQueueTransitionResult.StaleClaim
        else -> OfflineQueueTransitionResult.StaleClaim
    }
}
