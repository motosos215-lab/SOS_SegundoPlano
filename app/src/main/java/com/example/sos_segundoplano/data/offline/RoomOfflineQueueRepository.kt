package com.example.sos_segundoplano.data.offline

import android.database.sqlite.SQLiteException
import android.util.Log
import com.example.sos_segundoplano.BuildConfig
import com.example.sos_segundoplano.domain.offline.ClaimedOfflineQueueItem
import com.example.sos_segundoplano.domain.offline.ConnectivitySyncSnapshot
import com.example.sos_segundoplano.domain.offline.OfflineQueueClaim
import com.example.sos_segundoplano.domain.offline.AutomaticSosBundleClaimResult
import com.example.sos_segundoplano.domain.offline.AutomaticSosRemoteReceipt
import com.example.sos_segundoplano.domain.offline.ClaimedAutomaticSosBundle
import com.example.sos_segundoplano.domain.offline.OfflineQueueConfig
import com.example.sos_segundoplano.domain.offline.OfflineQueuePolicy
import com.example.sos_segundoplano.domain.offline.OfflineQueueEnqueueResult
import com.example.sos_segundoplano.domain.offline.OfflineQueueRepository
import com.example.sos_segundoplano.domain.offline.OfflineQueueStatus
import com.example.sos_segundoplano.domain.offline.OfflineQueueSummary
import com.example.sos_segundoplano.domain.offline.OfflineQueueTransitionResult
import com.example.sos_segundoplano.domain.offline.RecoverableOfflineIncidentBundle
import com.example.sos_segundoplano.domain.offline.OfflineSyncErrorCategory
import com.example.sos_segundoplano.domain.offline.OfflineSyncPayload
import com.example.sos_segundoplano.domain.offline.SyncErrorRecord
import com.example.sos_segundoplano.domain.offline.WallClock
import com.example.sos_segundoplano.domain.offline.idempotencyKey
import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.validation.MinorEvent
import com.example.sos_segundoplano.domain.validation.IncidentCause
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.util.UUID

class RoomOfflineQueueRepository(
    private val queueDao: OfflineQueueDao,
    private val errorDao: SyncErrorDao,
    private val crypto: OfflineQueueCrypto,
    private val serializer: OfflineQueueSerializer,
    private val clock: WallClock,
    private val config: OfflineQueueConfig = OfflineQueueConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scheduler: OfflineQueueWorkScheduler? = null,
    private val currentRiderOwnerId: () -> String? = { null },
    private val currentRemoteTripId: () -> String? = { null },
    private val connectivitySnapshotProvider: () -> ConnectivitySyncSnapshot? = { null }
) : OfflineQueueRepository, OfflineQueueSyncRepository, AutomaticTripFinalizationRepository {
    override suspend fun enqueueMinorEvent(event: MinorEvent): OfflineQueueEnqueueResult = enqueue(event.toSyncPayload(clock))

    override suspend fun enqueueIncident(incident: LocalIncident): OfflineQueueEnqueueResult = enqueue(incident.toSyncPayload(clock))

    override suspend fun enqueueAlertRequest(request: AlertDispatchRequest): OfflineQueueEnqueueResult = enqueue(request.toSyncPayload(clock))

    override suspend fun enqueueIncidentBundle(incident: LocalIncident, request: AlertDispatchRequest): OfflineQueueEnqueueResult = withContext(dispatcher) {
        try {
            val now = clock.currentTimeMillis()
            val metadata = if (incident.isAutomaticSos()) {
                automaticBundleMetadata(incident) ?: return@withContext OfflineQueueEnqueueResult.PersistenceFailed(
                    OfflineSyncErrorCategory.Serialization,
                    "offline_bundle_owner_or_identity_missing"
                )
            } else null
            val incidentPayload = incident.toSyncPayload(clock)
            val requestPayload = request.toSyncPayload(clock)
            val incidentEntity = buildEntity(incidentPayload, now, metadata) ?: return@withContext OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Encryption, "payload_encryption_failed")
            val requestEntity = buildEntity(requestPayload, now, metadata) ?: return@withContext OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Encryption, "payload_encryption_failed")
            val persisted = queueDao.insertBundle(incidentEntity, requestEntity)
            val result = OfflineQueueEnqueueResult.PersistedAndScheduled(persisted.requestQueueItemId, requestEntity.idempotencyKey)
            if (metadata != null) scheduleAutomaticAfterPersistence(result) else scheduleAfterPersistence(result)
        } catch (_: IncompleteOfflineBundleException) {
            OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_bundle_incomplete")
        } catch (_: SQLiteException) {
            OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_queue_storage_failed")
        } catch (failure: IllegalStateException) {
            logStorageUnavailable("enqueue_incident_bundle", failure)
            OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_queue_storage_unavailable")
        }
    }

    override suspend fun updateIncidentBundle(incident: LocalIncident, request: AlertDispatchRequest): OfflineQueueEnqueueResult = withContext(dispatcher) {
        try {
            val now = clock.currentTimeMillis()
            val metadata = if (incident.isAutomaticSos()) {
                automaticBundleMetadata(incident) ?: return@withContext OfflineQueueEnqueueResult.PersistenceFailed(
                    OfflineSyncErrorCategory.Serialization,
                    "offline_bundle_owner_or_identity_missing"
                )
            } else null
            val incidentEntity = buildEntity(incident.toSyncPayload(clock), now, metadata)
                ?: return@withContext OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Encryption, "payload_encryption_failed")
            val requestEntity = buildEntity(request.toSyncPayload(clock), now, metadata)
                ?: return@withContext OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Encryption, "payload_encryption_failed")
            queueDao.updateBundlePayload(incidentEntity, requestEntity, now)
            val result = OfflineQueueEnqueueResult.PersistedAndScheduled(0L, requestEntity.idempotencyKey)
            if (metadata != null) scheduleAutomaticAfterPersistence(result) else result
        } catch (_: IncompleteOfflineBundleException) {
            OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_bundle_incomplete")
        } catch (_: SQLiteException) {
            OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_queue_storage_failed")
        } catch (failure: IllegalStateException) {
            logStorageUnavailable("update_incident_bundle", failure)
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
        } catch (failure: IllegalStateException) {
            logStorageUnavailable("enqueue_payload", failure)
            OfflineQueueEnqueueResult.PersistenceFailed(OfflineSyncErrorCategory.Serialization, "offline_queue_storage_unavailable")
        }
    }

    private suspend fun buildEntity(
        payload: OfflineSyncPayload,
        nowMillis: Long,
        bundleMetadata: OfflineBundleMetadata? = null
    ): OfflineQueueEntity? {
        val enrichedPayload = payload.withConnectivity(connectivitySnapshotProvider())
        val key = idempotencyKey(enrichedPayload.eventType, enrichedPayload.sourceEventId, enrichedPayload.schemaVersion)
        val associatedData = OfflineQueueAssociatedData(key, enrichedPayload.eventType.wireName, enrichedPayload.schemaVersion, config.encryptionKeyVersion)
        val serialized = try {
            serializer.serialize(enrichedPayload)
        } catch (_: IllegalArgumentException) {
            recordEnqueueFailure(key, enrichedPayload, OfflineSyncErrorCategory.Serialization, "payload_serialization_failed", nowMillis)
            return null
        }
        val encrypted = when (val result = crypto.encrypt(serialized, associatedData)) {
            is OfflineCryptoResult.Success -> result.value
            is OfflineCryptoResult.Failure -> {
                recordEnqueueFailure(key, enrichedPayload, result.category, result.sanitizedMessage, nowMillis)
                return null
            }
        }
        return enrichedPayload.toNewEntity(
            encrypted = encrypted,
            idempotencyKey = key,
            nowMillis = nowMillis,
            bundleMetadata = bundleMetadata,
            currentRemoteTripId = currentRemoteTripId(),
            currentOwnerUserId = currentRiderOwnerId()
        )
    }

    private fun OfflineSyncPayload.withConnectivity(snapshot: ConnectivitySyncSnapshot?): OfflineSyncPayload {
        if (snapshot == null || connectivity != null) return this
        return when (this) {
            is OfflineSyncPayload.MinorEventPayload -> copy(payload = payload.copy(connectivity = snapshot))
            is OfflineSyncPayload.LocalIncidentPayload -> copy(payload = payload.copy(connectivity = snapshot))
            is OfflineSyncPayload.AlertDispatchRequestPayload -> copy(payload = payload.copy(connectivity = snapshot))
        }
    }

    override suspend fun recoverableBundleKeysForCurrentRider(): List<String> = withContext(dispatcher) {
        val ownerUserId = currentRiderOwnerId()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext emptyList()
        queueDao.recoverableBundleKeysForOwner(ownerUserId)
    }

    /** F.5C3 can reconcile trip finalization from these already acknowledged automatic bundles. */
    suspend fun remotelyConfirmedAutomaticBundleKeysForCurrentRider(): List<String> = withContext(dispatcher) {
        val ownerUserId = currentRiderOwnerId()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext emptyList()
        queueDao.confirmedAutomaticBundleKeysForOwner(ownerUserId)
    }

    suspend fun earliestAutomaticSosRetryForCurrentRider(): Long? = withContext(dispatcher) {
        val ownerUserId = currentRiderOwnerId()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        queueDao.earliestAutomaticBundleRetryForOwner(ownerUserId)
    }

    suspend fun earliestAutomaticTripFinalizationRetryForCurrentRider(): Long? = withContext(dispatcher) {
        val owner = currentRiderOwnerId()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        queueDao.earliestTripFinalizationRetryForOwner(owner)
    }

    override suspend fun claimNextAutomaticTripFinalization(workerId: String, now: Long): com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult = withContext(dispatcher) {
        val owner = currentRiderOwnerId()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@withContext com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult.BusyOrUnavailable
        queueDao.recoverAbandonedTripFinalizations(now)
        val key = queueDao.nextTripFinalizationBundleKey(owner, now)
            ?: return@withContext com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult.BusyOrUnavailable
        val token = "$workerId-finalize-$now-${UUID.randomUUID()}"
        val lease = now + config.inFlightLeaseTimeoutMillis
        if (queueDao.claimTripFinalizationRows(owner, key, workerId, token, lease, now) != 2) return@withContext com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult.BusyOrUnavailable
        val rows = queueDao.claimedTripFinalizationRows(owner, key, token)
        val trip = rows.mapNotNull { it.remoteTripId?.trim()?.takeIf(String::isNotEmpty) }.distinct().singleOrNull()
            ?: return@withContext com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult.NotRecoverable
        com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult.Acquired(com.example.sos_segundoplano.domain.offline.ClaimedAutomaticTripFinalization(owner, key, trip, token))
    }

    override suspend fun claimAutomaticTripFinalization(bundleKey: String, workerId: String, now: Long): com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult = withContext(dispatcher) {
        val owner = currentRiderOwnerId()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult.BusyOrUnavailable
        val key = bundleKey.trim().takeIf { it.isNotEmpty() } ?: return@withContext com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult.NotRecoverable
        val token = "$workerId-finalize-$now-${UUID.randomUUID()}"
        if (queueDao.claimTripFinalizationRows(owner, key, workerId, token, now + config.inFlightLeaseTimeoutMillis, now) != 2) return@withContext com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult.BusyOrUnavailable
        val trip = queueDao.claimedTripFinalizationRows(owner, key, token).mapNotNull { it.remoteTripId }.distinct().singleOrNull()
            ?: return@withContext com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult.NotRecoverable
        com.example.sos_segundoplano.domain.offline.AutomaticTripFinalizationClaimResult.Acquired(com.example.sos_segundoplano.domain.offline.ClaimedAutomaticTripFinalization(owner,key,trip,token))
    }

    override suspend fun completeAutomaticTripFinalization(claim: com.example.sos_segundoplano.domain.offline.ClaimedAutomaticTripFinalization, now: Long): Boolean = withContext(dispatcher) {
        queueDao.completeTripFinalization(claim.ownerUserId, claim.bundleKey, claim.claimToken, now) == 2
    }

    override suspend fun releaseAutomaticTripFinalization(claim: com.example.sos_segundoplano.domain.offline.ClaimedAutomaticTripFinalization, permanent: Boolean, now: Long): Boolean = withContext(dispatcher) {
        val next = if (permanent) null else OfflineQueuePolicy(config).nextRetryAt(now, 1, null)
        val released = queueDao.releaseTripFinalization(claim.ownerUserId, claim.bundleKey, claim.claimToken, if (permanent) "FailedPermanent" else "RetryPending", next, if (permanent) "permanent" else "retry", now) == 2
        if (released && next != null) scheduler?.scheduleDeferredAutomaticSos((next - now).coerceAtLeast(0L))
        released
    }

    override suspend fun recoverableIncidentBundleForCurrentRider(bundleKey: String): RecoverableOfflineIncidentBundle? = withContext(dispatcher) {
        val ownerUserId = currentRiderOwnerId()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        recoverableIncidentBundle(ownerUserId, bundleKey)
    }

    override suspend fun claimAutomaticSosBundle(bundleKey: String, workerId: String, nowMillis: Long): AutomaticSosBundleClaimResult = withContext(dispatcher) {
        val owner = currentRiderOwnerId()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext AutomaticSosBundleClaimResult.BusyOrUnavailable
        claimedAutomaticBundle(owner, bundleKey, workerId, nowMillis)
    }

    suspend fun claimNextAutomaticSosBundle(workerId: String, nowMillis: Long): AutomaticSosBundleClaimResult = withContext(dispatcher) {
        val owner = currentRiderOwnerId()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext AutomaticSosBundleClaimResult.BusyOrUnavailable
        val rows = queueDao.claimNextAutomaticBundle(owner, workerId, nowMillis)
        mapClaimedAutomaticBundle(owner, rows)
    }

    suspend fun expediteAutomaticSosRetriesForCurrentRider(nowMillis: Long = clock.currentTimeMillis()): Int = withContext(dispatcher) {
        val owner = currentRiderOwnerId()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext 0
        queueDao.expediteAutomaticBundleRetries(owner, nowMillis)
    }

    private suspend fun claimedAutomaticBundle(owner: String, bundleKey: String, workerId: String, nowMillis: Long): AutomaticSosBundleClaimResult {
        val key = bundleKey.trim().takeIf { it.isNotEmpty() } ?: return AutomaticSosBundleClaimResult.NotRecoverable
        return mapClaimedAutomaticBundle(owner, queueDao.claimAutomaticBundle(owner, key, workerId, nowMillis))
    }

    private fun mapClaimedAutomaticBundle(owner: String, rows: List<OfflineQueueEntity>): AutomaticSosBundleClaimResult {
        if (rows.isEmpty()) return AutomaticSosBundleClaimResult.BusyOrUnavailable
        if (rows.size != 2 || rows.any { it.ownerUserId != owner || it.bundleKey.isNullOrBlank() }) return AutomaticSosBundleClaimResult.NotRecoverable
        val claimed = rows.map { (it.toClaimedDomainResult() as? ClaimedOfflineQueueMappingResult.Success)?.item }
        val incident = claimed.filterNotNull().singleOrNull { it.item.eventType == com.example.sos_segundoplano.domain.offline.OfflineEventType.LocalIncident }
        val request = claimed.filterNotNull().singleOrNull { it.item.eventType == com.example.sos_segundoplano.domain.offline.OfflineEventType.AlertDispatchRequest }
        return if (incident == null || request == null) AutomaticSosBundleClaimResult.NotRecoverable else AutomaticSosBundleClaimResult.Acquired(
            ClaimedAutomaticSosBundle(owner, rows.first().bundleKey.orEmpty(), incident, request)
        )
    }

    override suspend fun acknowledgeAutomaticSosBundle(bundle: ClaimedAutomaticSosBundle, receipt: AutomaticSosRemoteReceipt, nowMillis: Long): OfflineQueueTransitionResult = withContext(dispatcher) {
        if (receipt.remoteIncidentId.isBlank() || receipt.remoteAlertDispatchId.isBlank()) return@withContext OfflineQueueTransitionResult.InvalidAcknowledgement
        val token = bundle.incident.claim.claimToken
        if (token != bundle.request.claim.claimToken || bundle.incident.claim.claimedBy != bundle.request.claim.claimedBy) return@withContext OfflineQueueTransitionResult.StaleClaim
        if (queueDao.acknowledgeAutomaticBundleAtomically(bundle.ownerUserId, bundle.bundleKey, bundle.incident.claim.claimedBy, token, receipt.remoteIncidentId, receipt.remoteAlertDispatchId, nowMillis)) {
            OfflineQueueTransitionResult.Applied
        } else OfflineQueueTransitionResult.StaleClaim
    }

    override suspend fun releaseAutomaticSosBundle(
        bundle: ClaimedAutomaticSosBundle,
        permanent: Boolean,
        code: String,
        nowMillis: Long
    ): OfflineQueueTransitionResult = withContext(dispatcher) {
        val status = if (permanent) OfflineQueueStatus.FailedPermanent.name else OfflineQueueStatus.RetryPending.name
        val nextAttempt = if (permanent) null else OfflineQueuePolicy(config).nextRetryAt(nowMillis, bundle.incident.item.attemptCount, null)
        val updated = queueDao.releaseAutomaticBundle(
            bundle.ownerUserId, bundle.bundleKey, bundle.incident.claim.claimedBy, bundle.incident.claim.claimToken,
            status, if (permanent) OfflineSyncErrorCategory.Serialization.name else OfflineSyncErrorCategory.Transport.name,
            sanitize(code).orEmpty(), "automatic_sos_bundle_$code", nextAttempt, nowMillis
        )
        if (updated == 2) {
            if (!permanent && nextAttempt != null) {
                scheduler?.scheduleDeferredAutomaticSos((nextAttempt - nowMillis).coerceAtLeast(0L))
            }
            OfflineQueueTransitionResult.Applied
        } else OfflineQueueTransitionResult.StaleClaim
    }

    private suspend fun recoverableIncidentBundle(
        ownerUserId: String,
        bundleKey: String
    ): RecoverableOfflineIncidentBundle? {
        val normalizedKey = bundleKey.trim().takeIf { it.isNotEmpty() } ?: return null
        val items = queueDao.bundleForOwner(ownerUserId, normalizedKey)
        if (items.size != 2 || items.any { it.ownerUserId != ownerUserId || it.bundleKey != normalizedKey }) return null
        val mapped = items.map { it.toDomain() ?: return null }
        val incident = mapped.singleOrNull { it.eventType == com.example.sos_segundoplano.domain.offline.OfflineEventType.LocalIncident } ?: return null
        val request = mapped.singleOrNull { it.eventType == com.example.sos_segundoplano.domain.offline.OfflineEventType.AlertDispatchRequest } ?: return null
        return RecoverableOfflineIncidentBundle(ownerUserId, normalizedKey, incident, request)
    }

    private fun automaticBundleMetadata(incident: LocalIncident): OfflineBundleMetadata? {
        val ownerUserId = currentRiderOwnerId()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bundleKey = incident.clientIncidentId?.trim()?.takeIf { candidate ->
            runCatching { UUID.fromString(candidate) }.isSuccess
        } ?: return null
        val remoteTripId = incident.remoteTripId?.trim()?.takeIf { it.isNotEmpty() }
        return OfflineBundleMetadata(ownerUserId = ownerUserId, bundleKey = bundleKey, remoteTripId = remoteTripId)
    }

    private fun LocalIncident.isAutomaticSos(): Boolean = cause in setOf(
        IncidentCause.Timeout,
        IncidentCause.UserRequestedHelp,
        IncidentCause.CriticalPhysicalEvent
    )


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

    private fun scheduleAutomaticAfterPersistence(result: OfflineQueueEnqueueResult): OfflineQueueEnqueueResult = when (result) {
        // The foreground coordinator owns the first send. A delayed WorkManager job is only a
        // safety net for process death / transient failures, avoiding a race where the worker
        // claims the bundle before the coordinator finishes writing remote trip + GPS context.
        is OfflineQueueEnqueueResult.PersistedAndScheduled -> when (scheduleAutomaticSosSafetyRecoverySafely()) {
            ScheduleResult.Scheduled -> result
            ScheduleResult.Deferred, ScheduleResult.Unavailable -> OfflineQueueEnqueueResult.PersistedSchedulingDeferred(result.queueItemId, result.idempotencyKey, "automatic_sos_schedule_deferred")
        }
        is OfflineQueueEnqueueResult.AlreadyExists -> {
            scheduleAutomaticSosSafetyRecoverySafely()
            result
        }
        else -> result
    }

    fun scheduleImmediateSyncSafely(): ScheduleResult = scheduler?.scheduleImmediateSync() ?: ScheduleResult.Unavailable

    suspend fun expediteGenericRetries(): Int = withContext(dispatcher) {
        queueDao.expediteGenericRetries(clock.currentTimeMillis())
    }

    suspend fun restoreUnsupportedGenericTransportFailures(): Int = withContext(dispatcher) {
        val ids = queueDao.failedUnsupportedGenericTransportItemIds()
        if (ids.isEmpty()) return@withContext 0
        val restored = queueDao.restoreUnsupportedGenericTransportFailures(clock.currentTimeMillis())
        if (restored > 0) errorDao.deleteForQueueItems(ids)
        restored
    }

    /**
     * One-time compatibility repair for automatic SOS rows produced by older builds that could
     * classify an offline remote submission as terminal. Only the current Rider's complete SOS
     * rows with the legacy remote_submission_failed marker are requeued.
     */
    suspend fun restoreLegacyAutomaticSosFailuresForCurrentRider(): Int = withContext(dispatcher) {
        val owner = currentRiderOwnerId()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext 0
        queueDao.restoreLegacyAutomaticSosFailures(owner, clock.currentTimeMillis())
    }

    fun scheduleAutomaticSosSafetyRecoverySafely(): ScheduleResult =
        scheduler?.scheduleDeferredAutomaticSos(AUTOMATIC_SOS_SAFETY_RECOVERY_DELAY_MILLIS) ?: ScheduleResult.Unavailable

    fun scheduleImmediateAutomaticSosSafely(): ScheduleResult = scheduler?.scheduleImmediateAutomaticSos() ?: ScheduleResult.Unavailable


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
        nextAttemptAtMillis: Long?,
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

    override fun observeSummary(): Flow<OfflineQueueSummary> {
        // Rider UI is created after authentication, so scope emergency counts to that Rider.
        // This prevents a failed SOS from a previous account on the same phone appearing here.
        val owner = currentRiderOwnerId()?.trim()?.takeIf { it.isNotEmpty() }
        val automaticCountsFlow = if (owner != null) {
            queueDao.observeAutomaticSosStatusCountsForOwner(owner)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
        return combine(
            queueDao.observeStatusCounts(),
            queueDao.observeErrorCount(),
            queueDao.observeSyncStats(),
            automaticCountsFlow
        ) { counts, errorCount, stats, automaticCounts ->
        val byStatus = counts.associate { it.status to it.count }
        val automaticByStatus = automaticCounts.associate { it.status to it.count }
        OfflineQueueSummary(
            pendingCount = byStatus[OfflineQueueStatus.Pending.name] ?: 0,
            inFlightCount = byStatus[OfflineQueueStatus.InFlight.name] ?: 0,
            retryPendingCount = byStatus[OfflineQueueStatus.RetryPending.name] ?: 0,
            notConfiguredCount = byStatus[OfflineQueueStatus.PausedNotConfigured.name] ?: 0,
            sentCount = byStatus[OfflineQueueStatus.Sent.name] ?: 0,
            permanentFailureCount = byStatus[OfflineQueueStatus.FailedPermanent.name] ?: 0,
            syncErrorCount = errorCount,
            lastSuccessfulSyncAt = stats.lastSuccessfulSyncAt,
            lastAttemptAt = stats.lastAttemptAt,
            automaticSosPendingCount = automaticByStatus[OfflineQueueStatus.Pending.name] ?: 0,
            automaticSosInFlightCount = automaticByStatus[OfflineQueueStatus.InFlight.name] ?: 0,
            automaticSosRetryPendingCount = automaticByStatus[OfflineQueueStatus.RetryPending.name] ?: 0,
            automaticSosFailedCount = automaticByStatus[OfflineQueueStatus.FailedPermanent.name] ?: 0
        )
        }
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

    suspend fun updateClaimedAutomaticSosContext(
        bundle: ClaimedAutomaticSosBundle,
        incidentPayload: OfflineSyncPayload.LocalIncidentPayload,
        requestPayload: OfflineSyncPayload.AlertDispatchRequestPayload,
        remoteTripId: String,
        latitude: Double,
        longitude: Double
    ): Boolean = withContext(dispatcher) {
        val normalizedTripId = remoteTripId.trim().takeIf { it.isNotEmpty() } ?: return@withContext false
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || (latitude == 0.0 && longitude == 0.0)) {
            return@withContext false
        }
        if (bundle.incident.claim.claimToken != bundle.request.claim.claimToken ||
            bundle.incident.claim.claimedBy != bundle.request.claim.claimedBy
        ) return@withContext false

        return@withContext try {
            val now = clock.currentTimeMillis()
            val metadata = OfflineBundleMetadata(bundle.ownerUserId, bundle.bundleKey, normalizedTripId)
            val updatedIncident = incidentPayload.copy(
                payload = incidentPayload.payload.copy(
                    remoteTripId = normalizedTripId,
                    latitude = latitude,
                    longitude = longitude
                )
            )
            val incidentEntity = buildEntity(updatedIncident, now, metadata) ?: return@withContext false
            val requestEntity = buildEntity(requestPayload, now, metadata) ?: return@withContext false
            queueDao.updateBundlePayload(incidentEntity, requestEntity, now)
            true
        } catch (_: SQLiteException) {
            false
        } catch (_: IncompleteOfflineBundleException) {
            false
        } catch (failure: IllegalStateException) {
            logStorageUnavailable("update_claimed_automatic_sos_context", failure)
            false
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
    private fun logStorageUnavailable(stage: String, failure: IllegalStateException) {
        if (!BuildConfig.DEBUG) return
        Log.w(TAG, "event=offline_queue_storage_unavailable stage=$stage exception=${failure::class.java.simpleName}")
    }
    private companion object {
        const val TAG = "MotoSOS.OfflineQueue"
        const val AUTOMATIC_SOS_SAFETY_RECOVERY_DELAY_MILLIS = 75_000L
    }


}
