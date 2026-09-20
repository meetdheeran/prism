package com.meetdheeran.prism.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, MemoryEntity::class, ReminderEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class PrismDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun memories(): MemoryDao
    abstract fun reminders(): ReminderDao

    companion object {
        @Volatile private var instance: PrismDatabase? = null
        fun get(ctx: Context): PrismDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, PrismDatabase::class.java, "prism.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
