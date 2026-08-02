package com.example.sos_segundoplano.data.offline

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SyncErrorDao {
    @Insert
    suspend fun insert(entity: SyncErrorEntity): Long

    @Query("SELECT * FROM offline_sync_errors ORDER BY occurredAtEpochMillis DESC, errorId DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<SyncErrorEntity>

    @Query(
        "DELETE FROM offline_sync_errors WHERE errorId NOT IN " +
            "(SELECT errorId FROM offline_sync_errors ORDER BY occurredAtEpochMillis DESC, errorId DESC LIMIT :maxRecords)"
    )
    suspend fun trimToLatest(maxRecords: Int): Int

    @Transaction
    suspend fun insertAndTrim(entity: SyncErrorEntity, maxRecords: Int): Long {
        val id = insert(entity)
        trimToLatest(maxRecords)
        return id
    }
}
