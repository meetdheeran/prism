package com.meetdheeran.prism.actions

import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import com.meetdheeran.prism.ai.Attachment
import com.meetdheeran.prism.ai.Schema
import com.meetdheeran.prism.ai.ToolHandler
import com.meetdheeran.prism.ai.ToolResult
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.core.Images
import com.meetdheeran.prism.assistant.ScreenCapture
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Calendar, memory and "what's on my screen" — the tools that give the model context. */
object ContextTools {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)

    fun all(graph: AppGraph): List<ToolHandler> = listOf(
        tool(
            "list_calendar_events",
            "Upcoming calendar events for the next N days (default 1). Needs calendar permission.",
            Schema.obj { int("days", "How many days ahead, 1-30") },
        ) { a, ctx ->
            if (!ctx.app.granted(android.Manifest.permission.READ_CALENDAR)) return@tool needsPermission(android.Manifest.permission.READ_CALENDAR, "Reading the calendar")
            val days = (a.argInt("days") ?: 1).coerceIn(1, 30)
            val begin = System.currentTimeMillis()
            val end = begin + days * 86_400_000L
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { ContentUris.appendId(it, begin); ContentUris.appendId(it, end); it.build() }
            val proj = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
            val events = ArrayList<Map<String, String>>()
            ctx.app.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext() && events.size < 40) {
                    events += mapOf(
                        "title" to c.getString(0).orEmpty(), "start" to iso.format(Date(c.getLong(1))), "end" to iso.format(Date(c.getLong(2))),
                        "allDay" to (c.getInt(3) == 1).toString(), "location" to c.getString(4).orEmpty(), "calendar" to c.getString(5).orEmpty(),
                    )
                }
            }
            ToolResult.data(
                buildJsonObject {
                    put("ok", true)
                    put("events", buildJsonArray { events.forEach { e -> add(buildJsonObject { e.forEach { (k, v) -> put(k, v) } }) } })
                },
                "${events.size} event(s)",
            )
        },
        tool(
            "create_calendar_event",
            "Open the calendar app's new-event editor pre-filled; the user reviews and saves it.",
            Schema.obj(listOf("title", "start")) {
                string("title", "Event title"); string("start", "Start as yyyy-MM-ddTHH:mm (local time)")
                string("end", "End as yyyy-MM-ddTHH:mm; default start + 1h"); string("location", "Location"); string("description", "Notes")
                bool("all_day", "All-day event")
            },
            confirm = true,
        ) { a, ctx ->
            val title = a.argStr("title") ?: return@tool failResult("Missing title")
            val start = a.argStr("start")?.let { runCatching { iso.parse(it)?.time }.getOrNull() } ?: return@tool failResult("Bad start time; use yyyy-MM-ddTHH:mm")
            val end = a.argStr("end")?.let { runCatching { iso.parse(it)?.time }.getOrNull() } ?: (start + 3_600_000L)
            val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, a.argBool("all_day") ?: false)
            a.argStr("location")?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            a.argStr("description")?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
            ctx.app.launch(intent)?.let { failResult(it) } ?: okResult("Calendar editor opened for \"$title\"")
        },
        tool(
            "remember",
            "Save a lasting fact about the user or their preferences to memory (they can see and edit it). Use only for things worth keeping across conversations.",
            Schema.obj(listOf("text")) { string("text", "The fact, in one sentence"); string("category", "Category", listOf("general", "preference", "person", "place", "work", "health", "routine")) },
        ) { a, _ ->
            val text = a.argStr("text") ?: return@tool failResult("Missing text")
            graph.memory.add(text, a.argStr("category") ?: "general", source = "assistant")
            okResult("Saved to memory: $text", userVisible = "Saved to memory")
        },
        tool("list_memories", "List what is currently stored in the user's memory.") { _, _ ->
            val items = graph.memory.enabled()
            ToolResult.data(
                buildJsonObject {
                    put("ok", true)
                    put("memories", buildJsonArray { items.forEach { add(buildJsonObject { put("id", it.id); put("text", it.text); put("category", it.category) }) } })
                },
                "${items.size} memories",
            )
        },
        tool(
            "forget",
            "Delete memories whose text contains the given words. Asks the user first.",
            Schema.obj(listOf("query")) { string("query", "Words to match") },
            confirm = true,
        ) { a, _ ->
            val q = a.argStr("query")?.lowercase() ?: return@tool failResult("Missing query")
            val victims = graph.memory.enabled().filter { it.text.lowercase().contains(q) }
            if (victims.isEmpty()) return@tool failResult("No memory matches \"$q\"")
            victims.forEach { graph.memory.delete(it) }
            okResult("Forgot ${victims.size} memor${if (victims.size == 1) "y" else "ies"}: " + victims.joinToString { "\"${it.text.take(40)}\"" })
        },
        tool(
            "read_screen",
            "Capture what is currently on the screen (needs Shizuku) and attach it so you can answer questions about it. Call this when the user asks about 'this', 'my screen', 'what am I looking at'.",
        ) { _, ctx ->
            val jpeg = ScreenCapture.behindPrism(ctx.app).getOrElse {
                return@tool failResult("Can't capture the screen: ${it.message}. Shizuku must be running and allowed for Prism.", needs = "shizuku")
            }
            ctx.attachImage(Attachment("image/jpeg", jpeg, name = "screenshot"))
            okResult("Screenshot attached to this conversation. Describe or answer using it. It is not stored.", userVisible = "Looked at the screen")
        },
    )
}
