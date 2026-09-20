package com.meetdheeran.prism.data

import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val conversations: ConversationDao, private val messages: MessageDao) {
    fun conversations(): Flow<List<ConversationEntity>> = conversations.all()
    fun messages(conversationId: Long): Flow<List<MessageEntity>> = messages.forConversation(conversationId)
    suspend fun messagesNow(conversationId: Long): List<MessageEntity> = messages.listForConversation(conversationId)
    suspend fun conversation(id: Long): ConversationEntity? = conversations.byId(id)

    suspend fun newConversation(provider: String, model: String, title: String = "New conversation"): Long {
        val now = System.currentTimeMillis()
        return conversations.insert(ConversationEntity(title = title, createdAt = now, updatedAt = now, provider = provider, model = model))
    }

    suspend fun addMessage(m: MessageEntity): Long {
        val id = messages.insert(m)
        conversations.touch(m.conversationId, m.createdAt)
        return id
    }

    suspend fun updateMessage(m: MessageEntity) = messages.update(m)
    suspend fun deleteMessage(id: Long) = messages.delete(id)
    suspend fun rename(id: Long, title: String) = conversations.rename(id, title)
    suspend fun delete(id: Long) = conversations.delete(id)
    suspend fun deleteAll() = conversations.deleteAll()
}

class MemoryRepository(private val dao: MemoryDao) {
    fun all(): Flow<List<MemoryEntity>> = dao.all()
    suspend fun enabled(): List<MemoryEntity> = dao.enabled()
    suspend fun byId(id: Long): MemoryEntity? = dao.byId(id)

    suspend fun add(text: String, category: String = "general", source: String = "user"): Long {
        val now = System.currentTimeMillis()
        return dao.insert(MemoryEntity(text = text.trim(), category = category, source = source, createdAt = now, updatedAt = now))
    }

    suspend fun update(m: MemoryEntity) = dao.update(m.copy(updatedAt = System.currentTimeMillis()))
    suspend fun delete(m: MemoryEntity) = dao.delete(m)
    suspend fun deleteAll() = dao.deleteAll()

    /** Compact block injected into the system prompt. */
    suspend fun promptBlock(): String {
        val items = enabled()
        if (items.isEmpty()) return ""
        return items.joinToString("\n") { "- [${it.category}] ${it.text}" }
    }
}
