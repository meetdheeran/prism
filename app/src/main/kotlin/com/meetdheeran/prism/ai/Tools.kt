package com.meetdheeran.prism.ai

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What a tool hands back to the model, plus an optional line to show the user ("Opened Spotify"). */
data class ToolResult(val json: JsonObject, val userVisible: String? = null) {
    companion object {
        fun ok(message: String, extra: Map<String, String> = emptyMap()) = ToolResult(
            buildJsonObject {
                put("ok", true)
                put("message", message)
                extra.forEach { (k, v) -> put(k, v) }
            },
            message,
        )

        fun fail(message: String) = ToolResult(buildJsonObject { put("ok", false); put("error", message) }, message)
        fun data(obj: JsonObject, userVisible: String? = null) = ToolResult(obj, userVisible)
    }
}

/** Everything a tool may need at execution time. */
class ToolContext(
    val app: Context,
    /** The conversation this call belongs to, so long-running work (the phone agent) can report back into it. */
    val conversationId: Long? = null,
    /** Attach an image the user is asking about (screenshot/photo) to the conversation. */
    val attachImage: suspend (Attachment) -> Unit,
)

interface ToolHandler {
    val spec: ToolSpec
    /** Tools that change the phone in a hard-to-undo way (call, send, delete) set this so the UI confirms first. */
    val needsConfirmation: Boolean get() = false
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

/** Small JSON-Schema helpers so tool specs stay readable. */
object Schema {
    fun obj(required: List<String> = emptyList(), block: Props.() -> Unit): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { Props(this).block() }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }

    class Props(private val b: JsonObjectBuilder) {
        fun string(name: String, description: String, enum: List<String>? = null) = b.putJsonObject(name) {
            put("type", "string")
            put("description", description)
            if (enum != null) putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } }
        }
        fun int(name: String, description: String) = b.putJsonObject(name) { put("type", "integer"); put("description", description) }
        fun number(name: String, description: String) = b.putJsonObject(name) { put("type", "number"); put("description", description) }
        fun bool(name: String, description: String) = b.putJsonObject(name) { put("type", "boolean"); put("description", description) }
        fun stringArray(name: String, description: String) = b.putJsonObject(name) {
            put("type", "array")
            put("description", description)
            putJsonObject("items") { put("type", "string") }
        }
    }
}

class ToolRegistry {
    private val handlers = LinkedHashMap<String, ToolHandler>()
    fun register(vararg h: ToolHandler) = apply { h.forEach { handlers[it.spec.name] = it } }
    fun specs(): List<ToolSpec> = handlers.values.map { it.spec }
    operator fun get(name: String): ToolHandler? = handlers[name]
    fun all(): List<ToolHandler> = handlers.values.toList()
}
