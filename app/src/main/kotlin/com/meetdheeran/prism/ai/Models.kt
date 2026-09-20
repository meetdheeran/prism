package com.meetdheeran.prism.ai

import com.meetdheeran.prism.core.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

enum class Role(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool") }

/** Binary content attached to a message: image/jpeg, image/png, application/pdf, audio/wav, text/plain. */
class Attachment(val mime: String, val bytes: ByteArray, val name: String? = null) {
    val isImage get() = mime.startsWith("image/")
}

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

data class Citation(val title: String, val url: String)

/**
 * Provider-neutral message. For role=TOOL, toolCallId/toolName identify which call this answers
 * and text holds the JSON result. For role=ASSISTANT with toolCalls, text may be empty.
 */
data class ChatMessage(
    val role: Role,
    val text: String = "",
    val attachments: List<Attachment> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
)

/** A callable tool, described with a JSON-Schema object (type=object, properties, required). */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class ModelInfo(
    val id: String,
    val label: String,
    val supportsVision: Boolean,
    val supportsTools: Boolean,
    val supportsAudioIn: Boolean = false,
    val supportsPdf: Boolean = false,
    val supportsSearch: Boolean = false,
    val note: String = "",
)

data class ChatRequest(
    val apiKey: String,
    val model: String,
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val webSearch: Boolean = false,
    val temperature: Float = 0.7f,
    val maxOutputTokens: Int = 2048,
)

sealed interface AiEvent {
    data class TextDelta(val text: String) : AiEvent
    data class ToolCallRequested(val call: ToolCall) : AiEvent
    data class Citations(val items: List<Citation>) : AiEvent
    data class Usage(val inputTokens: Int, val outputTokens: Int) : AiEvent
    data object Done : AiEvent
    data class Error(val message: String, val retryable: Boolean = false) : AiEvent
}

/**
 * One implementation per provider. Implementations must:
 *  - stream text as it arrives (SSE),
 *  - surface tool calls as ToolCallRequested and then Done (the engine executes and re-calls),
 *  - never log the API key or full request bodies,
 *  - map HTTP 401/403 to Error(retryable=false, "Invalid API key ..."), 429 to retryable.
 */
interface AiProvider {
    val provider: Provider
    /** Static catalogue used for the picker before the key is verified. */
    fun knownModels(): List<ModelInfo>
    /** Cheap live call ("Test connection"): lists models with the key. */
    suspend fun listModels(apiKey: String): Result<List<ModelInfo>>
    fun chat(request: ChatRequest): Flow<AiEvent>
    /** Speech-to-text for a 16 kHz mono WAV. Return null if the provider has no STT. */
    suspend fun transcribe(apiKey: String, wav: ByteArray, languageHint: String? = null): Result<String>?
}
