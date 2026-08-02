package com.example.sos_segundoplano.data.offline

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "offline_queue_items",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["status", "nextAttemptAtEpochMillis", "priority", "occurredAtEpochMillis"]),
        Index(value = ["claimedAtEpochMillis"])
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
