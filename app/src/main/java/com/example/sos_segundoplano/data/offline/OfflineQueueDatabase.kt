package com.example.sos_segundoplano.data.offline

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [OfflineQueueEntity::class, SyncErrorEntity::class],
    version = 1,
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
        ).build()
    }
}
