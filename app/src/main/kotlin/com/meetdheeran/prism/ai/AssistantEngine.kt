package com.meetdheeran.prism.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.BatteryManager
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.core.Settings
import com.meetdheeran.prism.core.WebSearchMode
import com.meetdheeran.prism.data.MessageEntity
import com.meetdheeran.prism.island.MediaWatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * Thrown by [AssistantEngine.quick] when the one-shot cannot run (no key, provider error).
 * The message is user-facing and may be shown as-is; nothing here ever contains the key.
 */
class AssistantException(message: String) : Exception(message)

/**
 * The assistant loop behind [Assistant]: provider + key resolution, history, the tool round-trip
 * (with on-screen confirmation for risky tools), and persistence through HistoryRepository.
 *
 * WHY it is shaped this way:
 *  - Each turn runs in [AppGraph.scope] on IO and pushes events into a channelFlow, so the UI
 *    can collect on the main thread and leave at any time; leaving (or [cancel]) cancels the
 *    network call, and whatever text had streamed is still persisted so history never lies.
 *  - One turn at a time: a second [send] cancels the first (see [Assistant]).
 *  - Nothing is ever mocked. Missing key -> [EngineEvent.Error] with the fix; a tool that
 *    fails reports its own error text back to the model and to the user chip.
 *  - Screenshots ([Attachment.name] == "screenshot") are sent once and never written to disk.
 */
class AssistantEngine(private val graph: AppGraph) : Assistant {

    /**
     * What the user was looking at when they invoked the assistant (e.g. from the session's
     * assist structure: "Chrome - BBC News"). Consumed by the next [send]; set to null after.
     */
    @Volatile var foregroundHint: String? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val activeJob = AtomicReference<Job?>(null)
    @Volatile private var pendingConfirmation: CompletableDeferred<Boolean>? = null

    // ---------------------------------------------------------------- Assistant contract

    override fun send(conversationId: Long?, text: String, attachments: List<Attachment>): Flow<EngineEvent> = channelFlow {
        val turn = Turn(conversationId, text, attachments)
        activeJob.get()?.cancel()
        val job = graph.scope.launch(Dispatchers.IO) {
            try {
                runTurn(turn) { send(it) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val persisted = persistPartial(turn)
                    val convId = turn.conversationId
                    if (persisted != null && convId != null) trySend(EngineEvent.Done(convId, persisted, turn.shown.toString()))
                }
            } catch (e: Exception) {
                withContext(NonCancellable) { persistPartial(turn) }
                trySend(EngineEvent.Error(friendly(e), turn.conversationId))
            } finally {
                close()
            }
        }
        activeJob.set(job)
        job.invokeOnCompletion { activeJob.compareAndSet(job, null) }
        awaitClose { job.cancel() }
    }

    override fun confirm(accept: Boolean) {
        pendingConfirmation?.complete(accept)
    }

    override fun cancel() {
        pendingConfirmation?.complete(false)
        activeJob.getAndSet(null)?.cancel()
    }

    override suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) {
        val settings = graph.prefs.current()
        Providers.apiKey(graph.app, settings.provider) != null
    }

    /**
     * Stateless one-shot: no history, no tools, nothing persisted. Used by the writing tools
     * (rewrite / summarize / translate) and by the island/session for short answers.
     * Streams text deltas; fails with [AssistantException] carrying a user-facing message.
     */
    fun quick(prompt: String, attachments: List<Attachment> = emptyList(), system: String? = null): Flow<String> = flow {
        val settings = graph.prefs.current()
        val provider = Providers.forSettings(settings)
        val key = Providers.apiKey(graph.app, settings.provider)
            ?: throw AssistantException(Providers.missingKeyMessage(settings.provider))
        val model = Providers.modelFor(settings, provider)
        if (model.isEmpty()) throw AssistantException("No ${settings.provider.label} model available. Pick one in Settings.")
        val request = ChatRequest(
            apiKey = key,
            model = model,
            system = system ?: Prompts.oneShot(settings),
            messages = listOf(ChatMessage(Role.USER, prompt, prepareAttachments(attachments))),
            tools = emptyList(),
            webSearch = false,
            temperature = 0.4f,
        )
        provider.chat(request).collect { ev ->
            when (ev) {
                is AiEvent.TextDelta -> emit(ev.text)
                is AiEvent.Error -> throw AssistantException(ev.message)
                else -> Unit
            }
        }
    }.flowOn(Dispatchers.IO)

    // ---------------------------------------------------------------- the turn

    /** Mutable state of one user turn; lives only for the duration of [runTurn]. */
    private class Turn(var conversationId: Long?, val text: String, val attachments: List<Attachment>) {
        /** Everything the UI has been shown as Text deltas (across all tool rounds). */
        val shown = StringBuilder()
        /** Text of the current provider round only (persisted per assistant message). */
        val roundText = StringBuilder()
        var citations: List<Citation> = emptyList()
        /** Images tools attached this round (read_screen); sent once, never persisted. */
        val pendingImages = mutableListOf<Attachment>()
        var providerId: String = ""
        var model: String = ""
        var finished = false
    }

    private suspend fun runTurn(turn: Turn, emit: suspend (EngineEvent) -> Unit) {
        val settings = graph.prefs.current()
        val provider = Providers.forSettings(settings)
        val key = Providers.apiKey(graph.app, settings.provider)
        if (key == null) {
            emit(EngineEvent.Error(Providers.missingKeyMessage(settings.provider), turn.conversationId))
            return
        }
        val model = Providers.modelFor(settings, provider)
        if (model.isEmpty()) {
            emit(EngineEvent.Error("No ${settings.provider.label} model available. Pick one in Settings.", turn.conversationId))
            return
        }
        turn.providerId = settings.provider.id
        turn.model = model
        emit(EngineEvent.Thinking)

        val convId = turn.conversationId
            ?: graph.history.newConversation(settings.provider.id, model, titleFor(turn))
        turn.conversationId = convId

        val prepared = prepareAttachments(turn.attachments)
        val userMessageId = persistUserMessage(convId, turn, prepared)
        val convo = loadHistory(convId, excludeId = userMessageId).toMutableList()
        convo += ChatMessage(Role.USER, turn.text, prepared)

        val hint = foregroundHint
        foregroundHint = null
        val system = Prompts.system(settings, graph.memory.promptBlock(), deviceContext(hint))
        val info = Providers.modelInfo(provider, model)
        val tools = if (info?.supportsTools == false) emptyList() else graph.tools.specs()
        val webSearch = settings.webSearch == WebSearchMode.PROVIDER

        repeat(MAX_ROUNDS) { round ->
            if (round > 0) emit(EngineEvent.Thinking)
            turn.roundText.setLength(0)
            turn.pendingImages.clear()
            val calls = mutableListOf<ToolCall>()
            var error: AiEvent.Error? = null

            val request = ChatRequest(key, model, system, convo.toList(), tools, webSearch)
            provider.chat(request).collect { ev ->
                when (ev) {
                    is AiEvent.TextDelta -> {
                        turn.roundText.append(ev.text)
                        turn.shown.append(ev.text)
                        emit(EngineEvent.Text(ev.text))
                    }
                    is AiEvent.ToolCallRequested -> calls += ev.call
                    is AiEvent.Citations -> {
                        turn.citations = (turn.citations + ev.items).distinctBy { it.url }
                        emit(EngineEvent.Citations(turn.citations))
                    }
                    is AiEvent.Error -> error = ev
                    is AiEvent.Usage, AiEvent.Done -> Unit
                }
            }

            error?.let { err ->
                persistPartial(turn)
                emit(EngineEvent.Error(err.message, convId))
                turn.finished = true
                return
            }

            if (calls.isEmpty()) {
                val id = persistAssistant(convId, turn, turn.roundText.toString(), toolCalls = emptyList(), citations = turn.citations)
                turn.finished = true
                emit(EngineEvent.Done(convId, id, turn.shown.toString()))
                return
            }

            // Tool round: persist the assistant's request, run each call, feed results back.
            persistAssistant(convId, turn, turn.roundText.toString(), toolCalls = calls, citations = emptyList())
            convo += ChatMessage(Role.ASSISTANT, turn.roundText.toString(), toolCalls = calls)
            if (turn.shown.isNotEmpty() && !turn.shown.endsWith("\n")) {
                turn.shown.append("\n")
                emit(EngineEvent.Text("\n"))
            }
            for (call in calls) {
                val result = executeCall(turn, call, emit)
                val resultJson = result.json.toString()
                graph.history.addMessage(
                    MessageEntity(
                        conversationId = convId,
                        role = "tool",
                        text = resultJson,
                        createdAt = System.currentTimeMillis(),
                        toolCallId = call.id,
                        toolName = call.name,
                        provider = turn.providerId,
                        model = turn.model,
                    ),
                )
                convo += ChatMessage(Role.TOOL, resultJson, toolCallId = call.id, toolName = call.name)
            }
            if (turn.pendingImages.isNotEmpty()) {
                convo += ChatMessage(Role.USER, "Here is the image the tool captured.", turn.pendingImages.toList())
                turn.pendingImages.clear()
            }
        }

        // Every round ended in more tool calls; stop honestly rather than loop forever.
        turn.finished = true
        emit(EngineEvent.Error("Stopped after $MAX_ROUNDS tool steps without a final answer. Try rephrasing.", convId))
    }

    // ---------------------------------------------------------------- tools

    private suspend fun executeCall(turn: Turn, call: ToolCall, emit: suspend (EngineEvent) -> Unit): ToolResult {
        val handler = graph.tools[call.name]
            ?: return ToolResult.fail("Unknown tool '${call.name}'. Use only the tools you were given.")
        val args = parseArgs(call.argumentsJson)

        if (handler.needsConfirmation) {
            val gate = CompletableDeferred<Boolean>()
            pendingConfirmation = gate
            emit(EngineEvent.NeedsConfirmation(call, describeCall(call.name, args)))
            val accepted = try {
                gate.await()
            } finally {
                if (pendingConfirmation === gate) pendingConfirmation = null
            }
            if (!accepted) {
                emit(EngineEvent.ToolDone(call.name, "Skipped: you declined"))
                return ToolResult.fail("The user declined this action on screen. Do not retry; acknowledge briefly.")
            }
        }

        emit(EngineEvent.ToolRunning(call.name, labelFor(call.name, args)))
        val ctx = ToolContext(graph.app) { attachment -> turn.pendingImages += attachment }
        val result = try {
            withTimeoutOrNull(TOOL_TIMEOUT_MS) { handler.execute(args, ctx) }
                ?: ToolResult.fail("The '${call.name}' tool timed out.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.fail(e.message?.takeIf { it.isNotBlank() } ?: "The '${call.name}' tool failed.")
        }
        emit(EngineEvent.ToolDone(call.name, result.userVisible))

        if (call.name == "remember" && result.json["ok"]?.jsonPrimitive?.contentOrNull != "false") {
            val remembered = args["text"]?.jsonPrimitive?.contentOrNull
                ?: result.userVisible
                ?: ""
            if (remembered.isNotBlank()) emit(EngineEvent.Remembered(remembered))
        }
        return result
    }

    private fun parseArgs(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }

    /** "Opening Spotify" style label for the running chip. */
    private fun labelFor(name: String, args: JsonObject): String {
        val base = TOOL_LABELS[name] ?: "Running ${name.replace('_', ' ')}"
        if (name !in DETAIL_TOOLS) return base
        val detail = args.values
            .firstOrNull { it is JsonPrimitive && it.isString }
            ?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return base
        return if (detail.length <= 40) "$base $detail" else "$base ${detail.take(37)}..."
    }

    /** Text for the confirmation sheet: what will happen plus the exact arguments. */
    private fun describeCall(name: String, args: JsonObject): String {
        val head = labelFor(name, args)
        val lines = args.entries.mapNotNull { (k, v) ->
            val value = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            if (value.isBlank()) null else "${k.replace('_', ' ')}: $value"
        }
        return if (lines.isEmpty()) head else head + "\n" + lines.joinToString("\n")
    }

    // ---------------------------------------------------------------- persistence

    private fun titleFor(turn: Turn): String {
        val t = turn.text.trim().replace(Regex("\\s+"), " ")
        if (t.isNotEmpty()) return t.take(40)
        val first = turn.attachments.firstOrNull() ?: return "New conversation"
        return when {
            first.name == "screenshot" -> "About the screen"
            first.isImage -> "About an image"
            else -> first.name?.take(40) ?: "Attachment"
        }
    }

    /**
     * Stores the user message. Attachments become metadata only ({mime,name,size,path?});
     * non-screenshot images are also written downscaled under filesDir/attachments so the
     * conversation can be resumed later. Screenshots never touch disk.
     */
    private suspend fun persistUserMessage(convId: Long, turn: Turn, prepared: List<Attachment>): Long {
        val now = System.currentTimeMillis()
        val id = graph.history.addMessage(
            MessageEntity(
                conversationId = convId,
                role = "user",
                text = turn.text,
                createdAt = now,
                attachmentsJson = if (turn.attachments.isEmpty()) null else attachmentsMeta(turn.attachments, emptyMap()),
                provider = turn.providerId,
                model = turn.model,
            ),
        )
        if (turn.attachments.isEmpty()) return id

        val paths = HashMap<Int, String>()
        val dir = File(graph.app.filesDir, ATTACH_DIR)
        turn.attachments.forEachIndexed { n, att ->
            if (!att.isImage || att.name == "screenshot") return@forEachIndexed
            val bytes = prepared.getOrNull(n)?.bytes ?: att.bytes
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "${id}_$n.jpg")
                file.writeBytes(bytes)
                paths[n] = file.absolutePath
            }
        }
        if (paths.isNotEmpty()) {
            graph.history.updateMessage(
                MessageEntity(
                    id = id,
                    conversationId = convId,
                    role = "user",
                    text = turn.text,
                    createdAt = now,
                    attachmentsJson = attachmentsMeta(turn.attachments, paths),
                    provider = turn.providerId,
                    model = turn.model,
                ),
            )
        }
        return id
    }

    private fun attachmentsMeta(attachments: List<Attachment>, paths: Map<Int, String>): String = buildJsonArray {
        attachments.forEachIndexed { n, a ->
            add(
                buildJsonObject {
                    put("mime", a.mime)
                    put("name", a.name ?: "")
                    put("size", a.bytes.size)
                    paths[n]?.let { put("path", it) }
                },
            )
        }
    }.toString()

    private suspend fun persistAssistant(convId: Long, turn: Turn, text: String, toolCalls: List<ToolCall>, citations: List<Citation>): Long =
        graph.history.addMessage(
            MessageEntity(
                conversationId = convId,
                role = "assistant",
                text = text,
                createdAt = System.currentTimeMillis(),
                toolCallsJson = if (toolCalls.isEmpty()) null else toolCallsJson(toolCalls),
                citationsJson = if (citations.isEmpty()) null else citationsJson(citations),
                provider = turn.providerId,
                model = turn.model,
            ),
        )

    /**
     * After a cancel or failure: keep whatever streamed so the transcript matches what the
     * user saw. Returns the message id, or null when there was nothing (or no conversation).
     */
    private suspend fun persistPartial(turn: Turn): Long? {
        if (turn.finished) return null
        val convId = turn.conversationId ?: return null
        val text = turn.roundText.toString().trim()
        if (text.isEmpty()) return null
        turn.finished = true
        return runCatching { persistAssistant(convId, turn, text, emptyList(), turn.citations) }.getOrNull()
    }

    private fun toolCallsJson(calls: List<ToolCall>): String = buildJsonArray {
        calls.forEach { c ->
            add(buildJsonObject { put("id", c.id); put("name", c.name); put("arguments", c.argumentsJson) })
        }
    }.toString()

    private fun citationsJson(items: List<Citation>): String = buildJsonArray {
        items.forEach { c -> add(buildJsonObject { put("title", c.title); put("url", c.url) }) }
    }.toString()

    // ---------------------------------------------------------------- history

    /**
     * Last [HISTORY_LIMIT] messages as provider-neutral ChatMessages. Leading orphans (a TOOL
     * message whose ASSISTANT request was trimmed away) are dropped so both providers see a
     * well-formed transcript. Stored images are re-attached only for recent messages to keep
     * request size sane; older ones stay as their text.
     */
    private suspend fun loadHistory(convId: Long, excludeId: Long): List<ChatMessage> {
        val rows = graph.history.messagesNow(convId).filter { it.id != excludeId }.takeLast(HISTORY_LIMIT)
        val recentFrom = (rows.size - RECENT_IMAGE_MESSAGES).coerceAtLeast(0)
        val mapped = rows.mapIndexedNotNull { i, m ->
            when (m.role) {
                "user" -> ChatMessage(Role.USER, m.text, if (i >= recentFrom) storedAttachments(m) else emptyList())
                "assistant" -> ChatMessage(Role.ASSISTANT, m.text, toolCalls = parseToolCalls(m.toolCallsJson))
                "tool" -> ChatMessage(Role.TOOL, m.text, toolCallId = m.toolCallId, toolName = m.toolName)
                else -> null
            }
        }
        val firstUser = mapped.indexOfFirst { it.role == Role.USER }
        return if (firstUser <= 0) mapped else mapped.drop(firstUser)
    }

    private fun parseToolCalls(raw: String?): List<ToolCall> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ToolCall(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = name,
                    argumentsJson = o["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun storedAttachments(m: MessageEntity): List<Attachment> {
        val raw = m.attachmentsJson ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val path = o["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val file = File(path)
                if (!file.isFile) return@mapNotNull null
                Attachment("image/jpeg", file.readBytes(), o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() })
            }
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- device + images

    private fun deviceContext(foregroundHint: String?): DeviceContext {
        val bm = graph.app.getSystemService(BatteryManager::class.java)
        val percent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging ?: false
        val playing = MediaWatcher.now.value?.let { np ->
            val who = if (np.artist.isBlank()) np.title else "${np.title} by ${np.artist}"
            who + if (np.isPlaying) " (playing)" else " (paused)"
        }
        return DeviceContext(
            nowIso = ZonedDateTime.now().format(DateTimeFormatter.ISO_ZONED_DATE_TIME),
            batteryPercent = percent,
            isCharging = charging,
            nowPlaying = playing,
            foregroundHint = foregroundHint,
        )
    }

    /** Images are downscaled (long side <= 1280 px, JPEG) before they go to the provider or disk. */
    private fun prepareAttachments(attachments: List<Attachment>): List<Attachment> = attachments.map { a ->
        if (!a.isImage) a else downscaleJpeg(a.bytes)?.let { Attachment("image/jpeg", it, a.name) } ?: a
    }

    private fun downscaleJpeg(bytes: ByteArray): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bmp.recycle()
        out.toByteArray()
    }.getOrNull()

    private fun friendly(e: Exception): String = when (e) {
        is IOException -> "Couldn't reach the provider. Check your connection and try again."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again."
    }

    private companion object {
        const val MAX_ROUNDS = 6
        const val HISTORY_LIMIT = 30
        const val RECENT_IMAGE_MESSAGES = 6
        const val TOOL_TIMEOUT_MS = 30_000L
        const val MAX_IMAGE_SIDE = 1280
        const val JPEG_QUALITY = 82
        const val ATTACH_DIR = "attachments"

        val TOOL_LABELS = mapOf(
            "open_app" to "Opening",
            "set_alarm" to "Setting an alarm",
            "set_timer" to "Setting a timer",
            "create_calendar_event" to "Adding to calendar",
            "list_calendar_events" to "Checking your calendar",
            "dial" to "Calling",
            "send_sms_draft" to "Drafting a message to",
            "navigate" to "Getting directions to",
            "web_search_open" to "Searching for",
            "play_music" to "Playing",
            "media_control" to "Controlling playback",
            "set_volume" to "Setting volume",
            "flashlight" to "Flashlight",
            "dnd" to "Do Not Disturb",
            "brightness" to "Setting brightness",
            "open_settings" to "Opening Settings",
            "remember" to "Saving to memory:",
            "forget" to "Forgetting:",
            "search_web" to "Searching the web for",
            "read_screen" to "Reading the screen",
        )

        /** Tools whose first string argument reads naturally after the label. */
        val DETAIL_TOOLS = setOf(
            "open_app", "dial", "send_sms_draft", "navigate", "web_search_open",
            "play_music", "remember", "forget", "search_web",
        )
    }
}
