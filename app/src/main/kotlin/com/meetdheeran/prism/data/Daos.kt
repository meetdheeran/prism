package com.meetdheeran.prism.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun all(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: Long): ConversationEntity?

    @Insert
    suspend fun insert(c: ConversationEntity): Long

    @Update
    suspend fun update(c: ConversationEntity)

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun forConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun listForConversation(conversationId: Long): List<MessageEntity>

    @Insert
    suspend fun insert(m: MessageEntity): Long

    @Update
    suspend fun update(m: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun all(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE enabled = 1 ORDER BY updatedAt DESC")
    suspend fun enabled(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun byId(id: Long): MemoryEntity?

    @Insert
    suspend fun insert(m: MemoryEntity): Long

    @Update
    suspend fun update(m: MemoryEntity)

    @Delete
    suspend fun delete(m: MemoryEntity)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}
