package com.meetdheeran.prism.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val provider: String,
    val model: String,
)

/**
 * One turn. role is "user" | "assistant" | "tool" | "system".
 * attachmentsJson: list of {mime,name,path?} - images are stored downscaled in app storage; screenshots are NOT stored.
 * toolCallsJson / toolCallId / toolName: what the provider round-trip needs so a conversation can be resumed.
 * citationsJson: list of {title,url}.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val text: String,
    val createdAt: Long,
    val attachmentsJson: String? = null,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val citationsJson: String? = null,
    val provider: String? = null,
    val model: String? = null,
)

/**
 * A fact the assistant may use in future conversations. Fully user-editable.
 * source is "user" (typed in the memory screen) or "assistant" (saved via the remember tool; always visible to the user).
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val category: String = "general",
    val source: String = "user",
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)
