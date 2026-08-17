package com.example.sos_segundoplano.data.offline

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "offline_queue_items",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["status", "nextAttemptAtEpochMillis", "priority", "occurredAtEpochMillis"]),
        Index(value = ["claimedAtEpochMillis"]),
        Index(
            name = "index_offline_queue_items_ownerUserId_bundleKey",
            value = ["ownerUserId", "bundleKey"]
        )
    ]
)
data class OfflineQueueEntity(
    @PrimaryKey(autoGenerate = true) val queueItemId: Long = 0L,
    val idempotencyKey: String,
    val eventType: String,
    val priority: Int,
    val payloadSchemaVersion: Int,
    val encryptedPayload: ByteArray,
    val encryptionNonce: ByteArray,
    val encryptionKeyVersion: Int,
    /** Backend Rider identifier used exclusively to prevent cross-account recovery. */
    val ownerUserId: String? = null,
    /** Stable identity shared by the two rows of a recoverable automatic SOS bundle. */
    val bundleKey: String? = null,
    val sourceSessionId: Long?,
    val sourceAssessmentId: Long?,
    val sourceEventId: String,
    val occurredAtEpochMillis: Long,
    val enqueuedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastAttemptAtEpochMillis: Long?,
    val nextAttemptAtEpochMillis: Long?,
    val sentAtEpochMillis: Long?,
    val attemptCount: Int,
    val status: String,
    val claimedAtEpochMillis: Long?,
    val claimedBy: String?,
    val claimToken: String?,
    val lastErrorCategory: String?,
    val lastErrorCode: String?,
    val lastErrorMessageSanitized: String?,
    val ackSanitized: String?
)
