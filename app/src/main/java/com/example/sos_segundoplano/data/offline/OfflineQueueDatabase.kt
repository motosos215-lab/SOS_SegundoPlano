package com.example.sos_segundoplano.data.offline

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [OfflineQueueEntity::class, SyncErrorEntity::class],
    version = 4,
    exportSchema = false
)
abstract class OfflineQueueDatabase : RoomDatabase() {
    abstract fun offlineQueueDao(): OfflineQueueDao
    abstract fun syncErrorDao(): SyncErrorDao

    companion object {
        const val DATABASE_NAME = "offline_queue.db"

        fun create(context: Context): OfflineQueueDatabase = Room.databaseBuilder(
            context.applicationContext,
            OfflineQueueDatabase::class.java,
            DATABASE_NAME
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE offline_queue_items ADD COLUMN ownerUserId TEXT")
                database.execSQL("ALTER TABLE offline_queue_items ADD COLUMN bundleKey TEXT")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_offline_queue_items_ownerUserId_bundleKey " +
                        "ON offline_queue_items(ownerUserId, bundleKey)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE offline_queue_items ADD COLUMN remoteIncidentId TEXT")
                database.execSQL("ALTER TABLE offline_queue_items ADD COLUMN remoteAlertDispatchId TEXT")
                database.execSQL("ALTER TABLE offline_queue_items ADD COLUMN remoteSuccessAtEpochMillis INTEGER")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE offline_queue_items ADD COLUMN tripFinalizationState TEXT")
                database.execSQL("ALTER TABLE offline_queue_items ADD COLUMN remoteTripId TEXT")
                database.execSQL("ALTER TABLE offline_queue_items ADD COLUMN tripFinalizationAttemptCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE offline_queue_items ADD COLUMN tripFinalizationNextAttemptAtEpochMillis INTEGER")
                database.execSQL("ALTER TABLE offline_queue_items ADD COLUMN tripFinalizationClaimToken TEXT")
                database.execSQL("ALTER TABLE offline_queue_items ADD COLUMN tripFinalizationClaimedBy TEXT")
                database.execSQL("ALTER TABLE offline_queue_items ADD COLUMN tripFinalizationLeaseUntilEpochMillis INTEGER")
                database.execSQL("ALTER TABLE offline_queue_items ADD COLUMN tripFinalizedAtEpochMillis INTEGER")
                database.execSQL("ALTER TABLE offline_queue_items ADD COLUMN lastTripFinalizationErrorType TEXT")
            }
        }
    }
}
