package com.example.sos_segundoplano.data.offline

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [OfflineQueueEntity::class, SyncErrorEntity::class],
    version = 2,
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
        ).addMigrations(MIGRATION_1_2).build()

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
    }
}
