package com.example.sos_segundoplano.domain.offline

enum class OfflineEventType(val wireName: String, val priority: Int) {
    MinorEvent("minor-event", 30),
    LocalIncident("local-incident", 20),
    AlertDispatchRequest("alert-dispatch-request", 10)
}

enum class OfflineQueueStatus {
    Pending,
    InFlight,
    RetryPending,
    PausedNotConfigured,
    Sent,
    FailedPermanent
}

enum class OfflineSyncErrorCategory {
    Encryption,
    Serialization,
    Transport,
    NotConfigured,
    Unknown
}

data class OfflineQueueConfig(
    val payloadSchemaVersion: Int = 1,
    val encryptionKeyVersion: Int = 1,
    val batchSize: Int = 10,
    val maxAttempts: Int = 8,
    val initialBackoffMillis: Long = 30_000L,
    val maxBackoffMillis: Long = 30 * 60_000L,
    val inFlightLeaseTimeoutMillis: Long = 5 * 60_000L,
    val maxErrorRecords: Int = 1_000,
    val sentRetentionMillis: Long = 7 * 24 * 60 * 60_000L
) {
    init {
        require(payloadSchemaVersion > 0)
        require(encryptionKeyVersion > 0)
        require(batchSize in 1..100)
        require(maxAttempts > 0)
        require(initialBackoffMillis > 0L)
        require(maxBackoffMillis >= initialBackoffMillis)
        require(inFlightLeaseTimeoutMillis > 0L)
        require(maxErrorRecords > 0)
        require(sentRetentionMillis > 0L)
    }
}

data class OfflineQueueItem(
    val queueItemId: Long,
    val idempotencyKey: String,
    val eventType: OfflineEventType,
    val payloadSchemaVersion: Int,
    val sourceSessionId: Long?,
    val sourceAssessmentId: Long?,
    val sourceEventId: String,
    val occurredAtEpochMillis: Long,
    val enqueuedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val nextAttemptAtEpochMillis: Long?,
    val lastAttemptAtEpochMillis: Long?,
    val sentAtEpochMillis: Long?,
    val attemptCount: Int,
    val status: OfflineQueueStatus,
    val lastErrorCategory: OfflineSyncErrorCategory?,
    val lastErrorCode: String?,
    val lastErrorMessageSanitized: String?,
    val ackSanitized: String?
)

data class ClaimedOfflineQueueItem(
    val item: OfflineQueueItem,
    val encryptedPayload: ByteArray,
    val encryptionNonce: ByteArray,
    val encryptionKeyVersion: Int,
    val claim: OfflineQueueClaim
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClaimedOfflineQueueItem) return false
        return item == other.item &&
            encryptedPayload.contentEquals(other.encryptedPayload) &&
            encryptionNonce.contentEquals(other.encryptionNonce) &&
            encryptionKeyVersion == other.encryptionKeyVersion &&
            claim == other.claim
    }

    override fun hashCode(): Int {
        var result = item.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        result = 31 * result + encryptionNonce.contentHashCode()
        result = 31 * result + encryptionKeyVersion
        result = 31 * result + claim.hashCode()
        return result
    }
}

data class OfflineQueueClaim(
    val queueItemId: Long,
    val claimedBy: String,
    val attemptCount: Int,
    val claimedAtEpochMillis: Long,
    val claimToken: String
)

sealed interface OfflineQueueTransitionResult {
    data object Applied : OfflineQueueTransitionResult
    data object AlreadySent : OfflineQueueTransitionResult
    data object StaleClaim : OfflineQueueTransitionResult
    data object Missing : OfflineQueueTransitionResult
    data object InvalidAcknowledgement : OfflineQueueTransitionResult
    data class InvalidState(val currentStatus: OfflineQueueStatus?) : OfflineQueueTransitionResult
}

sealed interface OfflineQueueSyncResult {
    data object Completed : OfflineQueueSyncResult
    data object NothingToDo : OfflineQueueSyncResult
    data object RetryRequired : OfflineQueueSyncResult
    data class DeferredUntil(val epochMillis: Long) : OfflineQueueSyncResult
    data class InitializationFailure(val sanitizedMessage: String) : OfflineQueueSyncResult
}

data class SyncErrorRecord(
    val errorId: Long,
    val queueItemId: Long,
    val idempotencyKey: String,
    val eventType: OfflineEventType,
    val category: OfflineSyncErrorCategory,
    val code: String?,
    val sanitizedMessage: String?,
    val attemptNumber: Int,
    val occurredAtEpochMillis: Long,
    val isPermanent: Boolean
)

data class OfflineQueueSummary(
    val pendingCount: Int = 0,
    val inFlightCount: Int = 0,
    val retryPendingCount: Int = 0,
    val notConfiguredCount: Int = 0,
    val sentCount: Int = 0,
    val permanentFailureCount: Int = 0,
    val syncErrorCount: Int = 0,
    val lastSuccessfulSyncAt: Long? = null,
    val lastAttemptAt: Long? = null
) {
    val unsentCount: Int get() = pendingCount + inFlightCount + retryPendingCount + notConfiguredCount
}

sealed interface OfflineQueueEnqueueResult {
    data class PersistedAndScheduled(val queueItemId: Long, val idempotencyKey: String) : OfflineQueueEnqueueResult
    data class PersistedSchedulingDeferred(val queueItemId: Long, val idempotencyKey: String, val sanitizedMessage: String?) : OfflineQueueEnqueueResult
    data class AlreadyExists(val idempotencyKey: String) : OfflineQueueEnqueueResult
    data class PersistenceFailed(val category: OfflineSyncErrorCategory, val sanitizedMessage: String?) : OfflineQueueEnqueueResult
}

val OfflineQueueEnqueueResult.isPersisted: Boolean
    get() = this is OfflineQueueEnqueueResult.PersistedAndScheduled ||
        this is OfflineQueueEnqueueResult.PersistedSchedulingDeferred ||
        this is OfflineQueueEnqueueResult.AlreadyExists

fun idempotencyKey(eventType: OfflineEventType, sourceEventId: String, schemaVersion: Int): String =
    "${eventType.wireName}:$sourceEventId:v$schemaVersion"
