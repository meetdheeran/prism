package com.meetdheeran.prism.ai

import android.util.Base64
import com.meetdheeran.prism.core.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google Gemini over plain REST (`generateContent` v1beta, SSE streaming). No SDK: the old
 * Android client is deprecated and the replacement needs a Firebase project, while Prism
 * uses a key the user pastes at runtime (see docs/research/gemini.md).
 *
 * The provider is stateless across turns with one exception: Gemini 3 requires the
 * `functionCall.id` and `thoughtSignature` of a tool-call turn to be echoed back, and the
 * provider-neutral [ChatMessage] has no field for them. They are therefore memoised
 * in-process, keyed by the `call_<tag>_<n>` ids this class generates, so the engine's
 * ASSISTANT/TOOL round trip reproduces them exactly. After a process restart older turns
 * are sent without signatures, which Gemini tolerates for previous turns.
 */
class GeminiProvider : AiProvider {
    override val provider: Provider = Provider.GEMINI

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val counter = AtomicInteger()
    private val sessionTag = java.lang.Long.toHexString(System.currentTimeMillis() and 0xFFFFFF)

    /** What a streamed functionCall carried that [ToolCall] cannot hold. */
    private class CallMemo(val geminiId: String?, val thoughtSignature: String?)

    private val memo = object : LinkedHashMap<String, CallMemo>(32, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CallMemo>?): Boolean = size > 128
    }

    private fun remember(id: String, m: CallMemo) = synchronized(memo) { memo[id] = m }
    private fun recall(id: String?): CallMemo? = if (id == null) null else synchronized(memo) { memo[id] }

    // ---------------------------------------------------------------- catalogue

    override fun knownModels(): List<ModelInfo> = KNOWN

    override suspend fun listModels(apiKey: String): Result<List<ModelInfo>> {
        val key = apiKey.trim()
        if (!looksLikeKey(key)) return Result.failure(IllegalArgumentException(BAD_KEY_FORMAT))
        return runCatching {
            val found = LinkedHashMap<String, ModelInfo>()
            var pageToken: String? = null
            var pages = 0
            do {
                val url = buildString {
                    append(BASE).append("/models?pageSize=100")
                    if (pageToken != null) append("&pageToken=").append(pageToken)
                }
                val body = Http.execute(
                    Request.Builder().url(url).header("x-goog-api-key", key).get().build(),
                )
                val root = json.parseToJsonElement(body) as? JsonObject ?: JsonObject(emptyMap())
                root.arr("models").forEach { el ->
                    val m = el as? JsonObject ?: return@forEach
                    val name = m.str("name") ?: return@forEach
                    val id = name.removePrefix("models/")
                    val methods = m.arr("supportedGenerationMethods").mapNotNull { (it as? JsonPrimitive)?.content }
                    if ("generateContent" !in methods) return@forEach
                    if (!isChatModel(id)) return@forEach
                    found[id] = toModelInfo(id, m)
                }
                pageToken = root.str("nextPageToken")
                pages++
            } while (pageToken != null && pages < 5)
            if (found.isEmpty()) throw IOException("The key works but Google returned no chat-capable models.")
            // Curated models first (in our order), then everything else alphabetically.
            val curated = KNOWN.mapNotNull { known -> found.remove(known.id)?.let { live -> known.copy(label = live.label.ifBlank { known.label }) } }
            curated + found.values.sortedBy { it.id }
        }.recoverCatching { e ->
            if (e is CancellationException) throw e
            throw IOException(friendlyMessage(e), e)
        }
    }

    private fun toModelInfo(id: String, m: JsonObject): ModelInfo {
        KNOWN.firstOrNull { it.id == id }?.let { return it.copy(label = m.str("displayName") ?: it.label) }
        val limit = m.int("inputTokenLimit") ?: 0
        val ctx = when {
            limit >= 1_000_000 -> "${limit / 1_000_000}M context"
            limit >= 1_000 -> "${limit / 1_000}k context"
            else -> ""
        }
        val thinking = m["thinking"]?.let { (it as? JsonPrimitive)?.content == "true" } ?: false
        val note = listOf(ctx, if (thinking) "thinking" else "").filter { it.isNotEmpty() }.joinToString(" · ")
        return ModelInfo(
            id = id,
            label = m.str("displayName") ?: id,
            supportsVision = true,
            supportsTools = true,
            supportsAudioIn = true,
            supportsPdf = true,
            supportsSearch = true,
            note = note,
        )
    }

    // ---------------------------------------------------------------- chat

    override fun chat(request: ChatRequest): Flow<AiEvent> = flow {
        val key = request.apiKey.trim()
        if (!looksLikeKey(key)) {
            emit(AiEvent.Error(BAD_KEY_FORMAT, retryable = false))
            return@flow
        }
        val model = request.model.ifBlank { DEFAULT_MODEL }
        val body = buildChatBody(request, model)
        val http = Request.Builder()
            .url("$BASE/models/$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", key)
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val citations = LinkedHashMap<String, Citation>()
        var usage: AiEvent.Usage? = null
        var emittedText = false
        var emittedCall = false
        var terminalError: AiEvent.Error? = null
        // A signature that arrived on a text/empty part before the first functionCall of this
        // turn belongs to the whole tool-call step; carry it onto that call.
        var pendingSignature: String? = null

        try {
            Http.sse(http).collect { payload ->
                if (terminalError != null) return@collect
                val chunk = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return@collect

                chunk.obj("error")?.let { err ->
                    terminalError = mapApiError(err.int("code") ?: 0, err.str("message"), null)
                    return@collect
                }
                chunk.obj("promptFeedback")?.str("blockReason")?.let { reason ->
                    terminalError = AiEvent.Error("Gemini declined that request (prompt blocked: ${reason.lowercase()}).", retryable = false)
                    return@collect
                }
                chunk.obj("usageMetadata")?.let { u ->
                    val input = u.int("promptTokenCount") ?: 0
                    val output = (u.int("candidatesTokenCount") ?: 0) + (u.int("thoughtsTokenCount") ?: 0)
                    usage = AiEvent.Usage(input, output)
                }

                val candidate = chunk.arr("candidates").firstOrNull() as? JsonObject
                if (candidate != null) {
                    candidate.obj("content")?.arr("parts")?.forEach { p ->
                        val part = p as? JsonObject ?: return@forEach
                        val signature = part.str("thoughtSignature")
                        val call = part.obj("functionCall")
                        if (call != null) {
                            val name = call.str("name") ?: return@forEach
                            val args = call["args"] as? JsonObject ?: JsonObject(emptyMap())
                            val id = "call_${sessionTag}_${counter.incrementAndGet()}"
                            remember(id, CallMemo(call.str("id"), signature ?: pendingSignature))
                            pendingSignature = null
                            emittedCall = true
                            emit(AiEvent.ToolCallRequested(ToolCall(id, name, args.toString())))
                        } else {
                            if (signature != null && pendingSignature == null) pendingSignature = signature
                            val isThought = part["thought"]?.let { (it as? JsonPrimitive)?.content == "true" } ?: false
                            val text = part.str("text")
                            if (!isThought && !text.isNullOrEmpty()) {
                                emittedText = true
                                emit(AiEvent.TextDelta(text))
                            }
                        }
                    }
                    candidate.obj("groundingMetadata")?.arr("groundingChunks")?.forEach { g ->
                        val web = (g as? JsonObject)?.obj("web") ?: return@forEach
                        val uri = web.str("uri") ?: return@forEach
                        citations.putIfAbsent(uri, Citation(web.str("title") ?: uri, uri))
                    }
                    when (val finish = candidate.str("finishReason")) {
                        null, "STOP", "MAX_TOKENS" -> Unit
                        "MALFORMED_FUNCTION_CALL" ->
                            if (!emittedCall) terminalError = AiEvent.Error("Gemini produced a malformed tool call. Try asking again.", retryable = true)
                        "SAFETY", "PROHIBITED_CONTENT", "SPII", "BLOCKLIST", "RECITATION", "LANGUAGE", "OTHER" ->
                            if (!emittedText && !emittedCall) {
                                val why = candidate.str("finishMessage")?.takeIf { it.isNotBlank() } ?: finish.lowercase().replace('_', ' ')
                                terminalError = AiEvent.Error("Gemini stopped without answering ($why).", retryable = false)
                            }
                        else -> Unit
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(mapThrowable(e))
            return@flow
        }

        terminalError?.let { emit(it); return@flow }
        if (citations.isNotEmpty()) emit(AiEvent.Citations(citations.values.toList()))
        usage?.let { emit(it) }
        emit(AiEvent.Done)
    }

    /** Builds the streamGenerateContent body. Field names follow the REST reference (camelCase and snake_case are both accepted). */
    private fun buildChatBody(request: ChatRequest, model: String): JsonObject {
        val systemText = buildString {
            append(request.system.trim())
            request.messages.filter { it.role == Role.SYSTEM && it.text.isNotBlank() }.forEach {
                if (isNotEmpty()) append("\n\n")
                append(it.text.trim())
            }
        }
        val contents = buildContents(request.messages)
        val isGemini3 = model.startsWith("gemini-3")
        val wantSearch = request.webSearch && searchCapable(model)
        // Built-in google_search combined with function declarations is documented for
        // Gemini 3 only; on older models the user's explicit search request wins for this turn.
        val declareTools = request.tools.isNotEmpty() && (isGemini3 || !wantSearch)

        return buildJsonObject {
            if (systemText.isNotEmpty()) {
                putJsonObject("system_instruction") {
                    putJsonArray("parts") { add(buildJsonObject { put("text", systemText) }) }
                }
            }
            put("contents", JsonArray(contents))
            if (declareTools || wantSearch) {
                putJsonArray("tools") {
                    if (wantSearch) add(buildJsonObject { putJsonObject("google_search") {} })
                    if (declareTools) {
                        add(
                            buildJsonObject {
                                putJsonArray("function_declarations") {
                                    request.tools.forEach { t ->
                                        add(
                                            buildJsonObject {
                                                put("name", t.name)
                                                put("description", t.description)
                                                put("parameters", t.parameters)
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
            putJsonArray("safetySettings") {
                SAFETY_CATEGORIES.forEach { c ->
                    add(buildJsonObject { put("category", c); put("threshold", "BLOCK_ONLY_HIGH") })
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", request.maxOutputTokens.coerceIn(64, 65_536))
                // Sampling params are deprecated for Gemini 3.x (changelog 2026-07-21) and the
                // docs caution against setting them; they are still honoured on 2.5.
                if (!isGemini3) put("temperature", request.temperature.coerceIn(0f, 2f))
            }
        }
    }

    /**
     * Provider-neutral history → Gemini `contents`. Consecutive same-role turns are merged
     * (parallel tool results become one user turn with several functionResponse parts).
     */
    private fun buildContents(messages: List<ChatMessage>): List<JsonElement> {
        val turns = ArrayList<Pair<String, MutableList<JsonElement>>>()
        fun addParts(role: String, parts: List<JsonElement>) {
            if (parts.isEmpty()) return
            val last = turns.lastOrNull()
            if (last != null && last.first == role) last.second.addAll(parts) else turns.add(role to parts.toMutableList())
        }
        messages.forEach { m ->
            when (m.role) {
                Role.SYSTEM -> Unit // folded into system_instruction
                Role.USER -> {
                    val parts = ArrayList<JsonElement>()
                    if (m.text.isNotBlank()) parts.add(buildJsonObject { put("text", m.text) })
                    m.attachments.forEach { a -> parts.add(inlineData(a.mime, a.bytes)) }
                    addParts("user", parts)
                }
                Role.ASSISTANT -> {
                    val parts = ArrayList<JsonElement>()
                    if (m.text.isNotBlank()) parts.add(buildJsonObject { put("text", m.text) })
                    m.toolCalls.forEach { c ->
                        val mem = recall(c.id)
                        parts.add(
                            buildJsonObject {
                                putJsonObject("functionCall") {
                                    mem?.geminiId?.let { put("id", it) }
                                    put("name", c.name)
                                    put("args", parseObject(c.argumentsJson))
                                }
                                mem?.thoughtSignature?.let { put("thoughtSignature", it) }
                            },
                        )
                    }
                    addParts("model", parts)
                }
                Role.TOOL -> {
                    val name = m.toolName ?: "tool"
                    val mem = recall(m.toolCallId)
                    val response = parseObjectOrWrap(m.text)
                    addParts(
                        "user",
                        listOf(
                            buildJsonObject {
                                putJsonObject("functionResponse") {
                                    mem?.geminiId?.let { put("id", it) }
                                    put("name", name)
                                    put("response", response)
                                }
                            },
                        ),
                    )
                }
            }
        }
        return turns.map { (role, parts) -> buildJsonObject { put("role", role); put("parts", JsonArray(parts)) } }
    }

    private fun inlineData(mime: String, bytes: ByteArray): JsonObject = buildJsonObject {
        putJsonObject("inline_data") {
            put("mime_type", mime)
            put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    // ---------------------------------------------------------------- transcription

    /**
     * Speech-to-text via audio understanding on the fast default model: the dedicated
     * transcribe model's request shape is not documented for generateContent yet.
     */
    override suspend fun transcribe(apiKey: String, wav: ByteArray, languageHint: String?): Result<String>? {
        val key = apiKey.trim()
        if (!looksLikeKey(key)) return Result.failure(IllegalArgumentException(BAD_KEY_FORMAT))
        if (wav.isEmpty()) return Result.failure(IllegalArgumentException("Nothing was recorded."))
        val prompt = buildString {
            append("Transcribe exactly what is said in this audio, word for word. ")
            append("Output only the transcript: no quotes, labels, timestamps or commentary. ")
            append("If the audio is silent or unintelligible, output nothing.")
            if (!languageHint.isNullOrBlank()) append(" The speech is in ").append(languageHint.trim()).append('.')
        }
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", prompt) })
                            add(inlineData("audio/wav", wav))
                        }
                    },
                )
            }
            putJsonObject("generationConfig") { put("maxOutputTokens", 2048) }
        }
        val http = Request.Builder()
            .url("$BASE/models/$DEFAULT_MODEL:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            val root = json.parseToJsonElement(Http.execute(http)) as? JsonObject ?: JsonObject(emptyMap())
            root.obj("promptFeedback")?.str("blockReason")?.let { throw IOException("Gemini declined to transcribe that audio ($it).") }
            val candidate = root.arr("candidates").firstOrNull() as? JsonObject
            val text = candidate?.obj("content")?.arr("parts").orEmpty()
                .mapNotNull { (it as? JsonObject) }
                .filter { it["thought"]?.let { t -> (t as? JsonPrimitive)?.content == "true" } != true }
                .mapNotNull { it.str("text") }
                .joinToString("")
                .trim()
            text
        }.recoverCatching { e ->
            if (e is CancellationException) throw e
            throw IOException(friendlyMessage(e), e)
        }
    }

    // ---------------------------------------------------------------- errors

    private fun mapThrowable(e: Throwable): AiEvent.Error = when (e) {
        is HttpException -> {
            val parsed = runCatching { json.parseToJsonElement(e.body) as? JsonObject }.getOrNull()?.obj("error")
            mapApiError(e.code, parsed?.str("message"), retryDelay(parsed))
        }
        is IOException -> AiEvent.Error("Couldn't reach Gemini. Check your connection and try again.", retryable = true)
        else -> AiEvent.Error("Something went wrong talking to Gemini: ${e.message ?: e.javaClass.simpleName}", retryable = true)
    }

    private fun mapApiError(code: Int, message: String?, retryDelay: String?): AiEvent.Error {
        val msg = message?.trim().orEmpty()
        val keyProblem = msg.contains("API key", ignoreCase = true) || msg.contains("API_KEY", ignoreCase = true)
        return when {
            code == 400 && keyProblem || code == 401 ->
                AiEvent.Error("Gemini rejected the API key. Check it in Settings › Keys (create one at aistudio.google.com/apikey).", retryable = false)
            code == 403 ->
                AiEvent.Error(
                    if (msg.contains("leaked", ignoreCase = true)) "Google reports this Gemini key as leaked. Create a new one in AI Studio and replace it in Settings › Keys."
                    else "This Gemini key isn't allowed to do that (restricted, wrong project, or unsupported region). Check it in Settings › Keys.",
                    retryable = false,
                )
            code == 404 ->
                AiEvent.Error("That Gemini model isn't available for this key. Pick another model in Settings.", retryable = false)
            code == 429 -> {
                val wait = retryDelay?.let { " Try again in $it." } ?: ""
                val daily = msg.contains("per day", ignoreCase = true) || msg.contains("daily", ignoreCase = true) || msg.contains("PerDay", ignoreCase = true)
                AiEvent.Error(
                    if (daily) "Gemini's daily free quota for this model is used up. Switch to a lighter model in Settings or wait until it resets.$wait"
                    else "Gemini is rate-limiting this key right now.$wait".trimEnd(),
                    retryable = true,
                )
            }
            code == 400 ->
                AiEvent.Error("Gemini rejected the request${if (msg.isNotEmpty()) ": ${msg.take(200)}" else "."}", retryable = false)
            code == 402 ->
                AiEvent.Error("This Gemini model needs billing enabled on your Google project.", retryable = false)
            code in 500..599 ->
                AiEvent.Error("Gemini is having trouble right now (HTTP $code). Try again in a moment.", retryable = true)
            code == 408 ->
                AiEvent.Error("Gemini took too long to answer. Try again.", retryable = true)
            else ->
                AiEvent.Error("Gemini error${if (code > 0) " (HTTP $code)" else ""}${if (msg.isNotEmpty()) ": ${msg.take(200)}" else "."}", retryable = code == 0)
        }
    }

    /** Human-readable message for Result failures (listModels / transcribe). */
    private fun friendlyMessage(e: Throwable): String = mapThrowable(e).message

    private fun retryDelay(error: JsonObject?): String? {
        error?.arr("details")?.forEach { d ->
            (d as? JsonObject)?.str("retryDelay")?.let { return it }
        }
        return null
    }

    // ---------------------------------------------------------------- helpers

    private fun looksLikeKey(key: String): Boolean = key.startsWith("AIza") && key.length >= 30 && key.none { it.isWhitespace() }

    private fun parseObject(text: String): JsonObject =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    /** functionResponse.response must be an object; scalars/arrays/plain text are wrapped as {"result": …}. */
    private fun parseObjectOrWrap(text: String): JsonObject {
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
        return when (parsed) {
            is JsonObject -> parsed
            null, JsonNull -> buildJsonObject { put("result", text) }
            else -> buildJsonObject { put("result", parsed) }
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray)?.toList() ?: emptyList()
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val BAD_KEY_FORMAT = "That doesn't look like a Gemini key (they start with \"AIza\"). Create one at aistudio.google.com/apikey."

        /** Fast, free-tier model used when Settings has no Gemini model yet and for transcription. */
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

        private val SAFETY_CATEGORIES = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
        )

        private val NOT_CHAT = listOf("tts", "image", "imagen", "veo", "embedding", "live", "transcribe", "computer-use", "aqa", "learnlm", "robotics")

        /** Chat-capable generalist Gemini models (text out) — excludes TTS, image, Live, embedding and STT specialists. */
        private fun isChatModel(id: String): Boolean = id.startsWith("gemini-") && NOT_CHAT.none { id.contains(it) }

        /** Google Search grounding is available on every current 2.5 / 3.x generalist model. */
        private fun searchCapable(model: String): Boolean = isChatModel(model) && !model.startsWith("gemini-1")

        /**
         * Curated catalogue (docs/research/gemini.md, 2026-09-20). Gemini 2.0 and 3-pro-preview
         * are shut down and deliberately absent. Every entry accepts text, image, audio and PDF.
         */
        private val KNOWN = listOf(
            ModelInfo(
                id = DEFAULT_MODEL, label = "Gemini 3.5 Flash-Lite",
                supportsVision = true, supportsTools = true, supportsAudioIn = true, supportsPdf = true, supportsSearch = true,
                note = "Fast default · free tier · web search needs a paid key",
            ),
            ModelInfo(
                id = "gemini-3.8-flash", label = "Gemini 3.8 Flash",
                supportsVision = true, supportsTools = true, supportsAudioIn = true, supportsPdf = true, supportsSearch = true,
                note = "Best quality on the free tier · thinking · web search needs a paid key",
            ),
            ModelInfo(
                id = "gemini-3.1-flash-lite", label = "Gemini 3.1 Flash-Lite",
                supportsVision = true, supportsTools = true, supportsAudioIn = true, supportsPdf = true, supportsSearch = true,
                note = "Low latency fallback · free tier",
            ),
            ModelInfo(
                id = "gemini-2.5-flash", label = "Gemini 2.5 Flash",
                supportsVision = true, supportsTools = true, supportsAudioIn = true, supportsPdf = true, supportsSearch = true,
                note = "Stable · web search free up to 500/day · knowledge to Jan 2025",
            ),
            ModelInfo(
                id = "gemini-2.5-flash-lite", label = "Gemini 2.5 Flash-Lite",
                supportsVision = true, supportsTools = true, supportsAudioIn = true, supportsPdf = true, supportsSearch = true,
                note = "Cheapest · web search free up to 500/day (shared)",
            ),
            ModelInfo(
                id = "gemini-3.1-pro-preview", label = "Gemini 3.1 Pro (preview)",
                supportsVision = true, supportsTools = true, supportsAudioIn = true, supportsPdf = true, supportsSearch = true,
                note = "Highest quality · paid keys only · slower",
            ),
        )
    }
}
