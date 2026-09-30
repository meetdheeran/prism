package com.meetdheeran.prism.ai

import android.util.Base64
import com.meetdheeran.prism.core.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Anthropic Messages API over raw HTTP (the official SDK is a JVM/Java library, not built for
 * Android, and the other providers already stream through [Http]). Streams SSE, supports
 * client tools (with eager input streaming, validated here before the engine runs them),
 * images, PDFs and Anthropic's server-side web search. Opus 5 / Fable requests opt into the
 * server-side refusal fallback so a declined request still gets an answer from a sibling model.
 */
class ClaudeProvider : AiProvider {
    override val provider: Provider = Provider.CLAUDE

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun knownModels(): List<ModelInfo> = CATALOGUE

    override suspend fun listModels(apiKey: String): Result<List<ModelInfo>> = runCatching {
        if (!apiKey.startsWith("sk-ant-")) throw IllegalArgumentException(BAD_KEY)
        val req = Request.Builder().url("$BASE/models?limit=100")
            .header("x-api-key", apiKey).header("anthropic-version", VERSION).get().build()
        val body = try { Http.execute(req) } catch (e: HttpException) { throw IllegalStateException(friendly(e)) }
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())
        data.mapNotNull { e ->
            val o = e.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            CATALOGUE.firstOrNull { it.id == id } ?: ModelInfo(
                id = id, label = o["display_name"]?.jsonPrimitive?.contentOrNull ?: id,
                supportsVision = true, supportsTools = true, supportsPdf = true, supportsSearch = supportsSearch(id),
            )
        }
    }

    override suspend fun transcribe(apiKey: String, wav: ByteArray, languageHint: String?): Result<String>? = null

    override fun chat(request: ChatRequest): Flow<AiEvent> = flow {
        val key = request.apiKey
        if (!key.startsWith("sk-ant-")) { emit(AiEvent.Error(BAD_KEY, retryable = false)); return@flow }
        val model = request.model.ifBlank { DEFAULT_MODEL }
        val useFallback = model.startsWith("claude-opus-5") || model.startsWith("claude-fable") || model.startsWith("claude-sonnet-5-5")
        val (system, messages) = buildMessages(request)
        val body = buildJsonObject {
            put("model", model)
            // Current Claude models always think, and thinking counts toward max_tokens: a small cap
            // (the agent asks for ~700) would cut the reply or tool call off. Billing is per token used,
            // so a roomy ceiling costs nothing extra; streaming keeps it clear of HTTP timeouts.
            put("max_tokens", maxOf(request.maxOutputTokens, if (thinksAlways(model)) 16_000 else 1024))
            put("stream", true)
            if (system.isNotBlank()) put("system", system)
            put("messages", messages)
            val tools = buildJsonArray {
                request.tools.forEach { t ->
                    addJsonObject {
                        put("name", t.name); put("description", t.description); put("input_schema", t.parameters)
                        put("eager_input_streaming", true)
                    }
                }
                if (request.webSearch && supportsSearch(model)) {
                    addJsonObject { put("type", searchToolType(model)); put("name", "web_search"); put("max_uses", 5) }
                }
            }
            if (tools.isNotEmpty()) put("tools", tools)
            // "auto" is the only tool_choice current models accept; parallel calls can still be switched off.
            if (request.singleToolCall && request.tools.isNotEmpty()) putJsonObject("tool_choice") { put("type", "auto"); put("disable_parallel_tool_use", true) }
            if (supportsEffort(model)) putJsonObject("output_config") { put("effort", "medium") }
            if (useFallback) put("fallbacks", "default")
        }
        val req = Request.Builder().url("$BASE/messages")
            .header("x-api-key", key).header("anthropic-version", VERSION).header("content-type", "application/json")
            .apply { if (useFallback) header("anthropic-beta", FALLBACK_BETA) }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var blockType = ""
        var toolId = ""
        var toolName = ""
        val toolJson = StringBuilder()
        val calls = ArrayList<ToolCall>()
        val citations = ArrayList<Citation>()
        var inputTokens = 0
        var outputTokens = 0
        var stopReason = ""
        var failed = false
        try {
            Http.sse(req).collect { data ->
                val ev = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@collect
                when (ev["type"]?.jsonPrimitive?.contentOrNull) {
                    "message_start" -> inputTokens = ev["message"]?.jsonObject?.get("usage")?.jsonObject?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                    "content_block_start" -> {
                        val cb = ev["content_block"]?.jsonObject ?: return@collect
                        blockType = cb["type"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (blockType == "tool_use") {
                            toolId = cb["id"]?.jsonPrimitive?.contentOrNull ?: "call_${calls.size}"
                            toolName = cb["name"]?.jsonPrimitive?.contentOrNull ?: ""
                            toolJson.setLength(0)
                        }
                    }
                    "content_block_delta" -> {
                        val d = ev["delta"]?.jsonObject ?: return@collect
                        when (d["type"]?.jsonPrimitive?.contentOrNull) {
                            "text_delta" -> d["text"]?.jsonPrimitive?.contentOrNull?.let { emit(AiEvent.TextDelta(it)) }
                            "input_json_delta" -> d["partial_json"]?.jsonPrimitive?.contentOrNull?.let { toolJson.append(it) }
                            "citations_delta" -> d["citation"]?.jsonObject?.let { c ->
                                val url = c["url"]?.jsonPrimitive?.contentOrNull
                                if (url != null) citations += Citation(c["title"]?.jsonPrimitive?.contentOrNull ?: url, url)
                            }
                        }
                    }
                    "content_block_stop" -> {
                        if (blockType == "tool_use" && toolName.isNotEmpty()) {
                            val args = toolJson.toString().ifBlank { "{}" }
                            // Eager streaming means the API no longer validates the JSON; we do.
                            if (runCatching { json.parseToJsonElement(args).jsonObject }.isSuccess) calls += ToolCall(toolId, toolName, args)
                            else { failed = true; emit(AiEvent.Error("Claude's tool input was cut off. Please try again.", retryable = true)) }
                        }
                        blockType = ""
                    }
                    "message_delta" -> {
                        ev["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let { stopReason = it }
                        ev["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.intOrNull?.let { outputTokens = it }
                    }
                    "error" -> {
                        failed = true
                        emit(AiEvent.Error(ev["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "Claude returned an error", retryable = true))
                    }
                }
            }
        } catch (e: HttpException) {
            emit(AiEvent.Error(friendly(e), retryable = e.code == 429 || e.code >= 500)); return@flow
        } catch (e: IOException) {
            emit(AiEvent.Error("Network problem talking to Claude: ${e.message ?: "connection failed"}", retryable = true)); return@flow
        }
        if (failed) return@flow
        if (stopReason == "refusal") emit(AiEvent.TextDelta("Claude declined this request."))
        if (stopReason == "max_tokens") emit(AiEvent.TextDelta("\n\n[Reply cut short at the length limit.]"))
        if (citations.isNotEmpty()) emit(AiEvent.Citations(citations.distinctBy { it.url }))
        calls.forEach { emit(AiEvent.ToolCallRequested(it)) }
        emit(AiEvent.Usage(inputTokens, outputTokens))
        emit(AiEvent.Done)
    }

    /** System text + the alternating user/assistant array the API wants; consecutive tool results share one user turn. */
    private fun buildMessages(request: ChatRequest): Pair<String, JsonArray> {
        val system = StringBuilder(request.system)
        val out = ArrayList<Pair<String, MutableList<JsonElement>>>()
        fun append(role: String, blocks: List<JsonElement>) {
            if (blocks.isEmpty()) return
            val last = out.lastOrNull()
            if (last != null && last.first == role) last.second.addAll(blocks) else out += role to blocks.toMutableList()
        }
        for (m in request.messages) {
            when (m.role) {
                Role.SYSTEM -> if (m.text.isNotBlank()) system.append("\n\n").append(m.text)
                Role.USER -> {
                    val blocks = ArrayList<JsonElement>()
                    m.attachments.forEach { a ->
                        when {
                            a.isImage -> blocks += buildJsonObject {
                                put("type", "image")
                                putJsonObject("source") { put("type", "base64"); put("media_type", if (a.mime == "image/png") "image/png" else "image/jpeg"); put("data", b64(a.bytes)) }
                            }
                            a.mime == "application/pdf" -> blocks += buildJsonObject {
                                put("type", "document")
                                putJsonObject("source") { put("type", "base64"); put("media_type", "application/pdf"); put("data", b64(a.bytes)) }
                            }
                            a.mime.startsWith("text/") -> blocks += textBlock("[Attached file ${a.name ?: "text"}]\n" + String(a.bytes, Charsets.UTF_8).take(60_000))
                            else -> blocks += textBlock("[Attachment ${a.name ?: a.mime} could not be sent to Claude: unsupported type]")
                        }
                    }
                    val text = m.text.ifBlank { if (blocks.isEmpty()) "(empty)" else "" }
                    if (text.isNotBlank()) blocks += textBlock(text)
                    append("user", blocks)
                }
                Role.ASSISTANT -> {
                    val blocks = ArrayList<JsonElement>()
                    if (m.text.isNotBlank()) blocks += textBlock(m.text)
                    m.toolCalls.forEach { c ->
                        blocks += buildJsonObject {
                            put("type", "tool_use"); put("id", c.id); put("name", c.name)
                            put("input", runCatching { json.parseToJsonElement(c.argumentsJson).jsonObject }.getOrDefault(JsonObject(emptyMap())))
                        }
                    }
                    append("assistant", blocks)
                }
                Role.TOOL -> append("user", listOf(buildJsonObject {
                    put("type", "tool_result"); put("tool_use_id", m.toolCallId ?: ""); put("content", m.text)
                }))
            }
        }
        // The first turn must be the user's.
        while (out.isNotEmpty() && out.first().first != "user") out.removeAt(0)
        val arr = buildJsonArray { out.forEach { (role, blocks) -> addJsonObject { put("role", role); putJsonArray("content") { blocks.forEach { add(it) } } } } }
        return system.toString() to arr
    }

    private fun textBlock(t: String): JsonElement = buildJsonObject { put("type", "text"); put("text", t) }
    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun friendly(e: HttpException): String {
        val msg = runCatching { json.parseToJsonElement(e.body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()
        return when (e.code) {
            401, 403 -> "Claude rejected the API key. Check it in Settings > API keys."
            429 -> "Claude rate limit reached — try again in a moment."
            529 -> "Claude is overloaded right now — try again shortly."
            400 -> "Claude refused the request: ${msg ?: "bad request"}"
            else -> "Claude error ${e.code}: ${msg ?: e.body.take(120)}"
        }
    }

    companion object {
        private const val BASE = "https://api.anthropic.com/v1"
        private const val VERSION = "2023-06-01"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private const val BAD_KEY = "That doesn't look like a Claude key (they start with \"sk-ant-\"). Create one at console.anthropic.com."
        const val DEFAULT_MODEL = "claude-opus-5-5"

        /** First entry is the default when the user hasn't picked one. */
        private val CATALOGUE = listOf(
            ModelInfo("claude-opus-5-5", "Claude Opus 5.5", supportsVision = true, supportsTools = true, supportsPdf = true, supportsSearch = true, note = "Default · $4 in / $20 out per 1M tokens · 1M context"),
            ModelInfo("claude-sonnet-5-5", "Claude Sonnet 5.5", supportsVision = true, supportsTools = true, supportsPdf = true, supportsSearch = true, note = "Faster and cheaper · $2 / $10 per 1M"),
            ModelInfo("claude-haiku-4-5", "Claude Haiku 4.5", supportsVision = true, supportsTools = true, supportsPdf = true, supportsSearch = true, note = "Cheapest · $1 / $5 per 1M · 200K context"),
            ModelInfo("claude-fable-5-1", "Claude Fable 5.1", supportsVision = true, supportsTools = true, supportsPdf = true, supportsSearch = true, note = "Most capable · $10 / $50 per 1M"),
            ModelInfo("claude-opus-5", "Claude Opus 5", supportsVision = true, supportsTools = true, supportsPdf = true, supportsSearch = true, note = "Previous Opus · $5 / $25 per 1M"),
            ModelInfo("claude-sonnet-5", "Claude Sonnet 5", supportsVision = true, supportsTools = true, supportsPdf = true, supportsSearch = true, note = "Previous Sonnet · $2 / $10 per 1M"),
        )

        private val MODERN = Regex("^claude-(opus-5|opus-4-[678]|sonnet-5|sonnet-4-6|fable|mythos)")
        private fun supportsEffort(model: String) = MODERN.containsMatchIn(model)
        /** Models with thinking on by default (it can't be switched off on Opus 5.5 / Sonnet 5.5 / Fable). */
        private fun thinksAlways(model: String) = Regex("^claude-(opus-5|sonnet-5|fable|mythos)").containsMatchIn(model)
        private fun supportsSearch(model: String) = true
        private fun searchToolType(model: String) = if (MODERN.containsMatchIn(model)) "web_search_20260209" else "web_search_20250305"
    }
}
