package com.example.sos_segundoplano.data.route

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "trip_route_points",
    indices = [
        Index(value = ["ownerUserId", "remoteTripId", "sequence"], unique = true),
        Index(value = ["ownerUserId", "remoteTripId"])
    ]
)
data class TripRoutePointEntity(
    @PrimaryKey val clientRoutePointId: String,
    val ownerUserId: String,
    val tripSessionKey: String,
    val remoteTripId: String,
    val sequence: Long,
    val recordedAtUtc: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val speedMetersPerSecond: Double?,
    val bearingDegrees: Double?,
    val createdAtEpochMillis: Long
)

@Entity(
    tableName = "trip_route_sequence_checkpoints",
    primaryKeys = ["ownerUserId", "remoteTripId"]
)
data class TripRouteSequenceCheckpointEntity(
    val ownerUserId: String,
    val remoteTripId: String,
    val lastSequence: Long
)

@Dao
interface TripRoutePointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: TripRoutePointEntity): Long

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM trip_route_points WHERE ownerUserId = :ownerUserId AND remoteTripId = :remoteTripId")
    suspend fun maxSequence(ownerUserId: String, remoteTripId: String): Long

    @Query("SELECT lastSequence FROM trip_route_sequence_checkpoints WHERE ownerUserId = :ownerUserId AND remoteTripId = :remoteTripId LIMIT 1")
    suspend fun sequenceCheckpoint(ownerUserId: String, remoteTripId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSequenceCheckpoint(checkpoint: TripRouteSequenceCheckpointEntity)

    @Query("SELECT remoteTripId FROM trip_route_points WHERE ownerUserId = :ownerUserId GROUP BY remoteTripId ORDER BY MIN(createdAtEpochMillis) ASC")
    suspend fun pendingTripIds(ownerUserId: String): List<String>

    @Query("SELECT * FROM trip_route_points WHERE ownerUserId = :ownerUserId AND remoteTripId = :remoteTripId ORDER BY sequence ASC LIMIT :limit")
    suspend fun pendingBatch(ownerUserId: String, remoteTripId: String, limit: Int): List<TripRoutePointEntity>

    @Query("DELETE FROM trip_route_points WHERE clientRoutePointId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("SELECT COUNT(*) FROM trip_route_points WHERE ownerUserId = :ownerUserId")
    suspend fun pendingCount(ownerUserId: String): Int

    @Query("SELECT COUNT(*) FROM trip_route_points WHERE ownerUserId = :ownerUserId")
    fun observePendingCount(ownerUserId: String): Flow<Int>
}

@Database(entities = [TripRoutePointEntity::class, TripRouteSequenceCheckpointEntity::class], version = 1, exportSchema = false)
abstract class TripRouteDatabase : RoomDatabase() {
    abstract fun routePointDao(): TripRoutePointDao

    companion object {
        private const val DATABASE_NAME = "trip_route_points.db"

        fun create(context: Context): TripRouteDatabase = Room.databaseBuilder(
            context.applicationContext,
            TripRouteDatabase::class.java,
            DATABASE_NAME
        ).build()
    }
}
