package com.example.sos_segundoplano.data.offline

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "offline_sync_errors",
    indices = [Index(value = ["queueItemId"]), Index(value = ["occurredAtEpochMillis"])]
)
data class SyncErrorEntity(
    @PrimaryKey(autoGenerate = true) val errorId: Long = 0L,
    val queueItemId: Long,
    val idempotencyKey: String,
    val eventType: String,
    val category: String,
    val code: String?,
    val sanitizedMessage: String?,
    val attemptNumber: Int,
    val occurredAtEpochMillis: Long,
    val isPermanent: Boolean
)

data class QueueStatusCount(
    val status: String,
    val count: Int
)

data class QueueSyncStats(
    val lastSuccessfulSyncAt: Long?,
    val lastAttemptAt: Long?
)
