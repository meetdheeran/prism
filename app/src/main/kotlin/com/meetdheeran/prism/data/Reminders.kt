package com.meetdheeran.prism.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** A reminder Prism itself fires as a notification at [atMillis]. */
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val atMillis: Long,
    val done: Boolean = false,
    val createdAt: Long,
)

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY done ASC, atMillis ASC")
    fun all(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE done = 0 ORDER BY atMillis ASC")
    suspend fun pending(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): ReminderEntity?

    @Insert
    suspend fun insert(r: ReminderEntity): Long

    @Update
    suspend fun update(r: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reminders WHERE done = 1")
    suspend fun clearDone()
}

/** v1 → v2: adds the reminders table without touching conversations or memory. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, " +
                "`atMillis` INTEGER NOT NULL, `done` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
    }
}
