package com.meetdheeran.prism.ai

import kotlinx.coroutines.flow.Flow

/** What the UI sees while the assistant works on one user turn. */
sealed interface EngineEvent {
    /** A request is in flight; show the thinking animation. */
    data object Thinking : EngineEvent
    /** Streamed assistant text. Append. */
    data class Text(val delta: String) : EngineEvent
    /** A tool started ("Opening Spotify"). */
    data class ToolRunning(val name: String, val label: String) : EngineEvent
    /** A tool finished; userVisible is a one-liner for a chip under the message. */
    data class ToolDone(val name: String, val userVisible: String?) : EngineEvent
    /** A tool needs the user's OK; the UI shows a confirmation sheet and calls Assistant.confirm(). */
    data class NeedsConfirmation(val call: ToolCall, val description: String) : EngineEvent
    /** Web citations attached to the current assistant message. */
    data class Citations(val items: List<Citation>) : EngineEvent
    /** The assistant saved something to memory (always surfaced, never silent). */
    data class Remembered(val text: String) : EngineEvent
    /** Turn complete; ids of the conversation and the assistant message that was persisted. */
    data class Done(val conversationId: Long, val messageId: Long, val fullText: String) : EngineEvent
    /** Something failed. Message is user-facing ("No Gemini key yet — add one in Settings"). */
    data class Error(val message: String, val conversationId: Long?) : EngineEvent
}

/**
 * The assistant as the UI uses it. Implemented by AssistantEngine, exposed as AppGraph.assistant.
 * One turn at a time; a second send() while busy cancels the first.
 */
interface Assistant {
    /**
     * Send a user turn. conversationId null = start a new conversation (Done carries the id).
     * Attachments are images/PDF/audio the user is asking about (screenshots are never persisted).
     */
    fun send(conversationId: Long?, text: String, attachments: List<Attachment> = emptyList()): Flow<EngineEvent>

    /** Answer a NeedsConfirmation event. */
    fun confirm(accept: Boolean)

    fun cancel()

    /** Whether a usable key exists for the currently selected provider. */
    suspend fun isConfigured(): Boolean
}
