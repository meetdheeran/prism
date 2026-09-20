package com.meetdheeran.prism.ai

import android.util.Base64
import com.meetdheeran.prism.core.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Groq (GroqCloud) through its OpenAI-compatible Chat Completions API.
 *
 * Why this shape:
 *  - Everything streams (`stream: true`) so the response card fills in as tokens arrive at
 *    Groq's ~1000 tok/s; the SSE reader is private because we need the HTTP status code to
 *    tell "bad key" (stop, fix the key) from "rate limited" (retry) — see [errorEvent].
 *  - `groq/compound*` is shut down (2026-09-21) and `llama-3.x` left the free tier
 *    (2026-08-16), so the catalogue is gpt-oss (text, tools, browser_search) plus
 *    `qwen/qwen3.8-27b`, the only self-serve model that can see images.
 *  - Images are only sent to a vision-capable model; otherwise they are dropped and the user
 *    is told in-line rather than the model silently pretending it looked.
 *  - Nothing is logged: not the key, not the bodies, not the images.
 */
class GroqProvider : AiProvider {

    override val provider: Provider = Provider.GROQ

    /** The model the engine uses when `Settings.groqModel` is blank. */
    val defaultModel: String get() = DEFAULT_MODEL

    // ---------------------------------------------------------------- catalogue

    override fun knownModels(): List<ModelInfo> = CATALOGUE

    /**
     * GET /openai/v1/models — the cheapest authenticated call, so it doubles as "Test
     * connection". Speech/TTS/guard models are filtered out because the picker is for chat.
     */
    override suspend fun listModels(apiKey: String): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        keyProblem(apiKey)?.let { return@withContext Result.failure(GroqException(it)) }
        try {
            val req = Request.Builder()
                .url("$BASE_URL/models")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .get()
                .build()
            val ids = Http.client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw GroqHttpException(resp.code, resp.header("retry-after"), text)
                val root = json.parseToJsonElement(text).asObj() ?: JsonObject(emptyMap())
                root.arr("data").orEmpty().mapNotNull { it.asObj()?.str("id") }
            }
            val chatIds = ids.filter { id -> NON_CHAT_HINTS.none { id.contains(it, ignoreCase = true) } }
            val known = CATALOGUE.filter { it.id in chatIds }
            val extra = chatIds.filter { id -> CATALOGUE.none { it.id == id } }.sorted().map { inferInfo(it) }
            Result.success(known + extra)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(GroqException(errorEvent(e).message))
        }
    }

    // ---------------------------------------------------------------- chat

    override fun chat(request: ChatRequest): Flow<AiEvent> = flow {
        keyProblem(request.apiKey)?.let {
            emit(AiEvent.Error(it, retryable = false))
            return@flow
        }
        val prepared = prepare(request)
        // Honest, visible notes ("this model can't see images") come before any model text.
        prepared.notes.forEach { emit(AiEvent.TextDelta("$it\n")) }

        val http = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer ${request.apiKey.trim()}")
            .header("Accept", "text/event-stream")
            .post(prepared.body.toRequestBody(JSON_MEDIA))
            .build()

        val pending = sortedMapOf<Int, PendingCall>()
        val citations = LinkedHashMap<String, Citation>()
        val markers = MarkerFilter(enabled = prepared.searchEnabled)
        var usage: AiEvent.Usage? = null
        var streamError: AiEvent.Error? = null

        suspend fun flushCalls() {
            if (pending.isEmpty()) return
            for ((index, call) in pending) {
                val id = call.id.ifBlank { "call_${index}_${System.nanoTime()}" }
                val args = call.arguments.toString().trim().ifBlank { "{}" }
                if (call.name.isNotBlank()) emit(AiEvent.ToolCallRequested(ToolCall(id, call.name, args)))
            }
            pending.clear()
        }

        try {
            streamSse(http).collect { payload ->
                if (payload == DONE_MARKER || streamError != null) return@collect
                val root = runCatching { json.parseToJsonElement(payload).asObj() }.getOrNull() ?: return@collect

                root.obj("error")?.let { err ->
                    streamError = AiEvent.Error(
                        "Groq stopped the reply: ${err.str("message") ?: "unknown error"}",
                        retryable = false,
                    )
                    return@collect
                }

                val choice = root.arr("choices")?.firstOrNull()?.asObj()
                val delta = choice?.obj("delta")
                if (delta != null) {
                    delta.str("content")?.takeIf { it.isNotEmpty() }?.let { text ->
                        markers.push(text)?.let { emit(AiEvent.TextDelta(it)) }
                    }
                    delta.arr("tool_calls")?.forEach { el ->
                        val tc = el.asObj() ?: return@forEach
                        val index = tc.int("index") ?: pending.size
                        val acc = pending.getOrPut(index) { PendingCall() }
                        tc.str("id")?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                        tc.obj("function")?.let { fn ->
                            fn.str("name")?.takeIf { it.isNotBlank() && acc.name.isBlank() }?.let { acc.name = it }
                            fn.str("arguments")?.let { acc.arguments.append(it) }
                        }
                    }
                    delta.arr("executed_tools")?.let { collectCitations(it, citations) }
                    delta.arr("annotations")?.let { collectAnnotations(it, citations) }
                }
                // Defensive: a non-streaming shaped choice (some error paths return one).
                choice?.obj("message")?.let { msg ->
                    msg.arr("executed_tools")?.let { collectCitations(it, citations) }
                    msg.arr("annotations")?.let { collectAnnotations(it, citations) }
                }

                if (choice?.str("finish_reason") != null) flushCalls()

                (root.obj("usage") ?: root.obj("x_groq")?.obj("usage"))?.let { u ->
                    val inTok = u.int("prompt_tokens")
                    val outTok = u.int("completion_tokens")
                    if (inTok != null || outTok != null) usage = AiEvent.Usage(inTok ?: 0, outTok ?: 0)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(errorEvent(e))
            return@flow
        }

        markers.flush()?.let { emit(AiEvent.TextDelta(it)) }
        flushCalls()
        streamError?.let {
            emit(it)
            return@flow
        }
        usage?.let { emit(it) }
        if (citations.isNotEmpty()) emit(AiEvent.Citations(citations.values.toList()))
        emit(AiEvent.Done)
    }

    // ---------------------------------------------------------------- speech-to-text

    /**
     * POST /openai/v1/audio/transcriptions with whisper-large-v3-turbo. Groq resamples to
     * 16 kHz mono itself; we send the WAV as-is. Free tier caps uploads at 25 MB.
     */
    override suspend fun transcribe(apiKey: String, wav: ByteArray, languageHint: String?): Result<String>? =
        withContext(Dispatchers.IO) {
            keyProblem(apiKey)?.let { return@withContext Result.failure(GroqException(it)) }
            if (wav.isEmpty()) return@withContext Result.failure(GroqException("Nothing was recorded."))
            if (wav.size > MAX_AUDIO_BYTES) {
                return@withContext Result.failure(GroqException("That recording is over 25 MB, Groq's free-tier limit. Try a shorter clip."))
            }
            try {
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "speech.wav", wav.toRequestBody(WAV_MEDIA))
                    .addFormDataPart("model", STT_MODEL)
                    .addFormDataPart("response_format", "json")
                    .addFormDataPart("temperature", "0")
                languageHint?.substringBefore('-')?.substringBefore('_')?.trim()?.lowercase()
                    ?.takeIf { it.length == 2 }
                    ?.let { form.addFormDataPart("language", it) }
                val req = Request.Builder()
                    .url("$BASE_URL/audio/transcriptions")
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .post(form.build())
                    .build()
                val text = Http.client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw GroqHttpException(resp.code, resp.header("retry-after"), body)
                    json.parseToJsonElement(body).asObj()?.str("text").orEmpty().trim()
                }
                Result.success(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(GroqException(errorEvent(e).message))
            }
        }

    // ---------------------------------------------------------------- request building

    private class Prepared(val body: String, val notes: List<String>, val searchEnabled: Boolean)

    /**
     * Turns the provider-neutral request into Groq's JSON. Images go only to a vision model
     * (max three per request, newest kept); PDFs/audio are not accepted by Groq chat at all,
     * so they are dropped with a note instead of being faked.
     */
    private fun prepare(req: ChatRequest): Prepared {
        val vision = supportsVision(req.model)
        val search = req.webSearch && supportsSearch(req.model)
        val notes = mutableListOf<String>()

        val totalImages = req.messages.sumOf { m -> m.attachments.count { it.isImage } }
        var imagesToSkip = if (vision) maxOf(0, totalImages - MAX_IMAGES) else 0
        if (!vision && totalImages > 0) {
            notes += "This Groq model can't see images; switch to the vision model ($VISION_MODEL) in Settings."
        }
        if (imagesToSkip > 0) notes += "Groq accepts up to $MAX_IMAGES images per request, so only the latest $MAX_IMAGES were sent."
        if (req.webSearch && !search) {
            notes += "Web search isn't available on this Groq model; switch to $DEFAULT_MODEL for search."
        }
        var droppedDocuments = false

        val messages = buildJsonArray {
            if (req.system.isNotBlank()) addJsonObject { put("role", "system"); put("content", req.system) }
            for (m in req.messages) when (m.role) {
                Role.SYSTEM -> addJsonObject { put("role", "system"); put("content", m.text) }

                Role.USER -> {
                    val images = m.attachments.filter { it.isImage }
                    val texts = m.attachments.filter { it.mime.startsWith("text/") }
                    if (m.attachments.any { !it.isImage && !it.mime.startsWith("text/") }) droppedDocuments = true
                    val kept = if (vision) {
                        val drop = minOf(imagesToSkip, images.size)
                        imagesToSkip -= drop
                        images.drop(drop)
                    } else emptyList()
                    val textBody = buildString {
                        append(m.text)
                        texts.forEach { t ->
                            append("\n\n[Attached ${t.name ?: "text"}]\n")
                            append(String(t.bytes, Charsets.UTF_8))
                        }
                    }.trim()
                    addJsonObject {
                        put("role", "user")
                        if (kept.isEmpty()) {
                            val fallback = if (images.isNotEmpty()) "[The user attached an image this model cannot see.]"
                            else "[The user attached a file this model cannot read.]"
                            put("content", textBody.ifBlank { fallback })
                        } else {
                            putJsonArray("content") {
                                if (textBody.isNotBlank()) addJsonObject { put("type", "text"); put("text", textBody) }
                                kept.forEach { img ->
                                    addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            put("url", dataUrl(img))
                                            put("detail", "auto")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Role.ASSISTANT -> addJsonObject {
                    put("role", "assistant")
                    if (m.text.isNotEmpty() || m.toolCalls.isEmpty()) put("content", m.text) else put("content", JsonNull)
                    if (m.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                        m.toolCalls.forEach { c ->
                            addJsonObject {
                                put("id", c.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", c.name)
                                    put("arguments", c.argumentsJson.ifBlank { "{}" })
                                }
                            }
                        }
                    }
                }

                // Groq returns 400 for `messages[].name`, so the tool name is deliberately omitted.
                Role.TOOL -> addJsonObject {
                    put("role", "tool")
                    put("tool_call_id", m.toolCallId.orEmpty())
                    put("content", m.text.ifBlank { "{}" })
                }
            }
        }
        if (droppedDocuments) notes += "Groq chat can't read PDFs or audio attachments; use Gemini for those."

        val tools = buildJsonArray {
            req.tools.forEach { t ->
                addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parameters)
                    }
                }
            }
            if (search) addJsonObject { put("type", "browser_search") }
        }

        val body = buildJsonObject {
            put("model", req.model)
            put("messages", messages)
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
            put("temperature", req.temperature.coerceIn(0f, 2f))
            put("max_completion_tokens", req.maxOutputTokens.coerceAtLeast(1))
            if (tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
            reasoningEffort(req.model, search)?.let { put("reasoning_effort", it) }
        }
        return Prepared(body.toString(), notes, search)
    }

    private fun dataUrl(img: Attachment): String =
        "data:${img.mime};base64," + Base64.encodeToString(img.bytes, Base64.NO_WRAP)

    /**
     * gpt-oss reasons by default; "low" keeps a phone assistant snappy (and is what Groq
     * recommends alongside browser_search, whose browsing trace burns reasoning tokens).
     * Qwen gets no override: its values differ and the default is fine.
     */
    private fun reasoningEffort(model: String, search: Boolean): String? = when {
        !model.startsWith("openai/gpt-oss") -> null
        search -> "low"
        model.contains("120b") -> "medium"
        else -> "low"
    }

    private fun supportsVision(model: String): Boolean =
        CATALOGUE.firstOrNull { it.id == model }?.supportsVision ?: model.startsWith("qwen/")

    private fun supportsSearch(model: String): Boolean =
        CATALOGUE.firstOrNull { it.id == model }?.supportsSearch ?: model.startsWith("openai/gpt-oss")

    private fun inferInfo(id: String) = ModelInfo(
        id = id,
        label = id.substringAfterLast('/'),
        supportsVision = id.startsWith("qwen/"),
        supportsTools = true,
        supportsSearch = id.startsWith("openai/gpt-oss"),
        note = "Listed by your account; capabilities inferred from the id.",
    )

    // ---------------------------------------------------------------- SSE + errors

    private class PendingCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    /** Streaming needs a generous read timeout; reasoning pauses can exceed a default 10 s. */
    private val sseClient: OkHttpClient by lazy {
        Http.client.newBuilder()
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Reads `data:` payloads from a text/event-stream body. Private (rather than a shared
     * helper) so a non-2xx status becomes [GroqHttpException] with the code and body, which
     * [errorEvent] needs to decide retryable vs. "fix your key". Cancelling the collector
     * cancels the OkHttp call, which unblocks the read.
     */
    private fun streamSse(request: Request): Flow<String> = flow {
        val call = sseClient.newCall(request)
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw GroqHttpException(resp.code, resp.header("retry-after"), resp.body?.string().orEmpty())
                }
                val source = resp.body?.source() ?: return@use
                val data = StringBuilder()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> if (data.isNotEmpty()) { emit(data.toString()); data.setLength(0) }
                        line.startsWith(":") -> Unit
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").trimStart())
                        }
                        else -> Unit // event:/id:/retry: fields are not used by Groq
                    }
                }
                if (data.isNotEmpty()) emit(data.toString())
            }
        } finally {
            handle?.dispose()
        }
    }.flowOn(Dispatchers.IO)

    /** A non-2xx response. Carries the parsed `error.message` when Groq sent one; never the key. */
    private class GroqHttpException(val code: Int, val retryAfter: String?, body: String) : Exception("HTTP $code") {
        val apiMessage: String? = runCatching {
            json.parseToJsonElement(body).asObj()?.obj("error")?.str("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** User-facing error text. Retryable = the same request may succeed a moment later. */
    private fun errorEvent(t: Throwable): AiEvent.Error = when (t) {
        is GroqHttpException -> {
            val detail = t.apiMessage?.let { " ($it)" }.orEmpty()
            when (t.code) {
                401, 403 -> AiEvent.Error("Groq rejected the API key. Check it in Settings › Keys — Groq keys start with gsk_.", false)
                413 -> AiEvent.Error("That request is too large for Groq. Send fewer or smaller images, or start a new conversation.", false)
                429 -> AiEvent.Error(
                    "Groq rate limit reached" + (t.retryAfter?.let { " — try again in ${it}s" } ?: " — try again shortly") + ".",
                    true,
                )
                498 -> AiEvent.Error("Groq has no spare capacity right now. Try again in a moment.", true)
                404 -> AiEvent.Error("Groq doesn't know that model. Pick another one in Settings.", false)
                400, 422 -> if (looksLikeRetiredModel(t.apiMessage)) {
                    AiEvent.Error("That Groq model was retired or isn't available to your account. Pick another in Settings.", false)
                } else {
                    AiEvent.Error("Groq rejected the request$detail.", false)
                }
                in 500..599 -> AiEvent.Error("Groq is having trouble (HTTP ${t.code}). Try again shortly.", true)
                else -> AiEvent.Error("Groq error (HTTP ${t.code})$detail.", false)
            }
        }
        is UnknownHostException -> AiEvent.Error("No internet connection — Groq is unreachable.", true)
        is SocketTimeoutException -> AiEvent.Error("Groq took too long to respond. Try again.", true)
        is IOException -> AiEvent.Error("Network error talking to Groq: ${t.message ?: "connection failed"}.", true)
        else -> AiEvent.Error("Unexpected Groq error: ${t.message ?: t.javaClass.simpleName}.", false)
    }

    private fun looksLikeRetiredModel(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return "model" in m && listOf("decommission", "deprecat", "not exist", "not found", "does not", "no longer", "unavailable").any { it in m }
    }

    private fun keyProblem(key: String): String? = when {
        key.isBlank() -> "No Groq API key yet — add one in Settings › Keys (console.groq.com/keys)."
        !key.trim().startsWith("gsk_") -> "That doesn't look like a Groq key — Groq keys start with gsk_."
        else -> null
    }

    // ---------------------------------------------------------------- citations

    private fun collectCitations(executedTools: JsonArray, into: MutableMap<String, Citation>) {
        executedTools.forEach { el ->
            val results = el.asObj()?.obj("search_results")?.arr("results") ?: return@forEach
            results.forEach { r ->
                val ro = r.asObj() ?: return@forEach
                val url = ro.str("url")?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                val title = ro.str("title")?.trim()?.takeIf { it.isNotBlank() } ?: hostLabel(url)
                if (url !in into) into[url] = Citation(title, url)
            }
        }
    }

    /** OpenAI-style `annotations[].url_citation` — not documented for Groq today, parsed defensively. */
    private fun collectAnnotations(annotations: JsonArray, into: MutableMap<String, Citation>) {
        annotations.forEach { el ->
            val cite = el.asObj()?.obj("url_citation") ?: return@forEach
            val url = cite.str("url")?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            val title = cite.str("title")?.trim()?.takeIf { it.isNotBlank() } ?: hostLabel(url)
            if (url !in into) into[url] = Citation(title, url)
        }
    }

    private fun hostLabel(url: String): String =
        url.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/').ifBlank { url }

    /**
     * gpt-oss answers from browser_search carry inline `【2†L6-L10】` markers. Strip them from
     * the stream; an opener without its closer is held back until the closer (or the end)
     * arrives so a marker split across two deltas never leaks half-way.
     */
    private class MarkerFilter(private val enabled: Boolean) {
        private val held = StringBuilder()

        fun push(delta: String): String? {
            if (!enabled) return delta
            held.append(delta)
            val text = held.toString()
            val open = text.lastIndexOf(OPEN)
            val close = text.lastIndexOf(CLOSE)
            val safeEnd = if (open > close) open else text.length
            val out = MARKER.replace(text.substring(0, safeEnd), "")
            held.setLength(0)
            held.append(text, safeEnd, text.length)
            return out.ifEmpty { null }
        }

        fun flush(): String? {
            val out = MARKER.replace(held.toString(), "")
            held.setLength(0)
            return out.ifEmpty { null }
        }

        private companion object {
            const val OPEN = '【' // 【
            const val CLOSE = '】' // 】
            val MARKER = Regex("【[^】]*】")
        }
    }

    companion object {
        const val BASE_URL = "https://api.groq.com/openai/v1"
        const val DEFAULT_MODEL = "openai/gpt-oss-20b"
        const val QUALITY_MODEL = "openai/gpt-oss-120b"
        const val VISION_MODEL = "qwen/qwen3.8-27b"
        const val STT_MODEL = "whisper-large-v3-turbo"

        private const val MAX_IMAGES = 3
        private const val MAX_AUDIO_BYTES = 25 * 1024 * 1024
        private const val DONE_MARKER = "[DONE]"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val WAV_MEDIA = "audio/wav".toMediaType()
        private val NON_CHAT_HINTS = listOf("whisper", "orpheus", "tts", "playai", "prompt-guard", "guard-2", "compound")

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Curated from docs/research/groq.md (2026-09-20). Compound and llama-3.x are absent on
         * purpose: compound is shut down and llama-3.x is Enterprise-only now.
         */
        private val CATALOGUE = listOf(
            ModelInfo(
                id = DEFAULT_MODEL,
                label = "GPT-OSS 20B · fast",
                supportsVision = false,
                supportsTools = true,
                supportsSearch = true,
                note = "Default. ~1000 tok/s, cheapest. Tools + web search. Free tier: 30 req/min, 8K tokens/min, 1K req/day. Text only.",
            ),
            ModelInfo(
                id = QUALITY_MODEL,
                label = "GPT-OSS 120B · smarter",
                supportsVision = false,
                supportsTools = true,
                supportsSearch = true,
                note = "Stronger reasoning, ~500 tok/s. Tools + web search. Same free-tier limits (30 RPM, 8K TPM). Text only.",
            ),
            ModelInfo(
                id = VISION_MODEL,
                label = "Qwen 3.8 27B · vision",
                supportsVision = true,
                supportsTools = true,
                supportsSearch = false,
                note = "The only Groq model that can see images (up to 3 per request, ~2K tokens each). Parallel tool calls. Preview: may be retired at short notice. No web search.",
            ),
        )

        private fun JsonElement.asObj(): JsonObject? = this as? JsonObject
        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
        private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.int(key: String): Int? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()?.toInt()
    }
}

/** Failure for the suspend APIs ([GroqProvider.listModels], [GroqProvider.transcribe]); message is user-facing. */
class GroqException(message: String) : Exception(message)
