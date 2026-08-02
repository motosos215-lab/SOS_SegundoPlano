package com.example.sos_segundoplano.data.offline

import com.example.sos_segundoplano.domain.offline.AlertDispatchRequestSyncPayload
import com.example.sos_segundoplano.domain.offline.ClaimedOfflineQueueItem
import com.example.sos_segundoplano.domain.offline.LocalIncidentSyncPayload
import com.example.sos_segundoplano.domain.offline.MinorEventSyncPayload
import com.example.sos_segundoplano.domain.offline.OfflineEventType
import com.example.sos_segundoplano.domain.offline.OfflineQueueClaim
import com.example.sos_segundoplano.domain.offline.OfflineQueueItem
import com.example.sos_segundoplano.domain.offline.OfflineQueueStatus
import com.example.sos_segundoplano.domain.offline.OfflineSyncErrorCategory
import com.example.sos_segundoplano.domain.offline.OfflineSyncPayload
import com.example.sos_segundoplano.domain.offline.SyncErrorRecord
import com.example.sos_segundoplano.domain.offline.WallClock
import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.validation.MinorEvent

fun MinorEvent.toSyncPayload(clock: WallClock): OfflineSyncPayload.MinorEventPayload =
    OfflineSyncPayload.MinorEventPayload(
        MinorEventSyncPayload(
            eventId = eventId,
            sessionId = sessionId,
            assessmentId = assessmentId,
            windowId = windowId,
            type = type.name,
            score = score,
            confidence = confidence,
            policyVersion = policyVersion,
            occurredAtEpochMillis = clock.currentTimeMillis(),
            createdAtElapsedRealtimeNanos = createdAtElapsedRealtimeNanos
        )
    )

fun LocalIncident.toSyncPayload(clock: WallClock): OfflineSyncPayload.LocalIncidentPayload =
    OfflineSyncPayload.LocalIncidentPayload(
        LocalIncidentSyncPayload(
            incidentId = incidentId,
            sessionId = sessionId,
            assessmentId = assessmentId,
            windowId = windowId,
            cause = cause.name,
            score = score,
            riskLevel = riskLevel.name,
            confidence = confidence,
            ruleSetVersion = ruleSetVersion,
            validationPolicyVersion = validationPolicyVersion,
            gpsQuality = gpsQuality.name,
            occurredAtEpochMillis = clock.currentTimeMillis(),
            createdAtElapsedRealtimeNanos = createdAtElapsedRealtimeNanos
        )
    )

fun AlertDispatchRequest.toSyncPayload(clock: WallClock): OfflineSyncPayload.AlertDispatchRequestPayload =
    OfflineSyncPayload.AlertDispatchRequestPayload(
        AlertDispatchRequestSyncPayload(
            requestId = requestId,
            incidentId = incidentId,
            sessionId = sessionId,
            assessmentId = assessmentId,
            priority = priority.name,
            reason = reason.name,
            score = score,
            confidence = confidence,
            deliveryStatus = deliveryStatus.name,
            retryState = retryState.name,
            occurredAtEpochMillis = clock.currentTimeMillis(),
            createdAtElapsedRealtimeNanos = createdAtElapsedRealtimeNanos
        )
    )

fun OfflineQueueEntity.toDomainResult(): OfflineQueueEntityMappingResult {
    val mappedEventType = eventType.toEventTypeOrNull() ?: return OfflineQueueEntityMappingResult.UnknownEventType
    val mappedStatus = status.toQueueStatus() ?: return OfflineQueueEntityMappingResult.UnknownStatus
    val mappedErrorCategory = lastErrorCategory?.let { category ->
        OfflineSyncErrorCategory.values().firstOrNull { it.name == category } ?: return OfflineQueueEntityMappingResult.UnknownErrorCategory
    }
    return OfflineQueueEntityMappingResult.Success(
        OfflineQueueItem(
            queueItemId = queueItemId,
            idempotencyKey = idempotencyKey,
            eventType = mappedEventType,
            payloadSchemaVersion = payloadSchemaVersion,
            sourceSessionId = sourceSessionId,
            sourceAssessmentId = sourceAssessmentId,
            sourceEventId = sourceEventId,
            occurredAtEpochMillis = occurredAtEpochMillis,
            enqueuedAtEpochMillis = enqueuedAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
            lastAttemptAtEpochMillis = lastAttemptAtEpochMillis,
            nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
            sentAtEpochMillis = sentAtEpochMillis,
            attemptCount = attemptCount,
            status = mappedStatus,
            lastErrorCategory = mappedErrorCategory,
            lastErrorCode = lastErrorCode,
            lastErrorMessageSanitized = lastErrorMessageSanitized,
            ackSanitized = ackSanitized
        )
    )
}

fun OfflineQueueEntity.toClaimedDomainResult(): ClaimedOfflineQueueMappingResult {
    if (status != OfflineQueueStatus.InFlight.name || claimedBy.isNullOrBlank() || claimedAtEpochMillis == null || claimToken.isNullOrBlank()) {
        return ClaimedOfflineQueueMappingResult.InvalidClaim
    }
    val item = when (val mapped = toDomainResult()) {
        is OfflineQueueEntityMappingResult.Success -> mapped.item
        else -> return ClaimedOfflineQueueMappingResult.InvalidItem(mapped)
    }
    return ClaimedOfflineQueueMappingResult.Success(
        ClaimedOfflineQueueItem(
            item = item,
            encryptedPayload = encryptedPayload,
            encryptionNonce = encryptionNonce,
            encryptionKeyVersion = encryptionKeyVersion,
            claim = OfflineQueueClaim(
                queueItemId = queueItemId,
                claimedBy = claimedBy,
                attemptCount = attemptCount,
                claimedAtEpochMillis = claimedAtEpochMillis,
                claimToken = claimToken
            )
        )
    )
}

fun OfflineQueueEntity.toDomain(): OfflineQueueItem? = (toDomainResult() as? OfflineQueueEntityMappingResult.Success)?.item

fun SyncErrorEntity.toDomain(): SyncErrorRecord? {
    val mappedEventType = eventType.toEventTypeOrNull() ?: return null
    val mappedCategory = OfflineSyncErrorCategory.values().firstOrNull { it.name == category } ?: return null
    return SyncErrorRecord(
        errorId = errorId,
        queueItemId = queueItemId,
        idempotencyKey = idempotencyKey,
        eventType = mappedEventType,
        category = mappedCategory,
        code = code,
        sanitizedMessage = sanitizedMessage,
        attemptNumber = attemptNumber,
        occurredAtEpochMillis = occurredAtEpochMillis,
        isPermanent = isPermanent
    )
}

fun OfflineSyncPayload.toNewEntity(
    encrypted: EncryptedPayload,
    idempotencyKey: String,
    nowMillis: Long
): OfflineQueueEntity = OfflineQueueEntity(
    idempotencyKey = idempotencyKey,
    eventType = eventType.wireName,
    priority = eventType.priority,
    payloadSchemaVersion = schemaVersion,
    encryptedPayload = encrypted.ciphertext,
    encryptionNonce = encrypted.nonce,
    encryptionKeyVersion = encrypted.keyVersion,
    sourceSessionId = sourceSessionId,
    sourceAssessmentId = sourceAssessmentId,
    sourceEventId = sourceEventId,
    occurredAtEpochMillis = occurredAtEpochMillis,
    enqueuedAtEpochMillis = nowMillis,
    updatedAtEpochMillis = nowMillis,
    nextAttemptAtEpochMillis = null,
    lastAttemptAtEpochMillis = null,
    sentAtEpochMillis = null,
    attemptCount = 0,
    status = OfflineQueueStatus.Pending.name,
    claimedAtEpochMillis = null,
    claimedBy = null,
    claimToken = null,
    lastErrorCategory = null,
    lastErrorCode = null,
    lastErrorMessageSanitized = null,
    ackSanitized = null
)

fun String.toEventTypeOrNull(): OfflineEventType? = OfflineEventType.values().firstOrNull { it.wireName == this }
private fun String.toQueueStatus(): OfflineQueueStatus? = OfflineQueueStatus.values().firstOrNull { it.name == this }
