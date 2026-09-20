package com.meetdheeran.prism.actions

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import com.meetdheeran.prism.ai.Schema
import com.meetdheeran.prism.ai.ToolContext
import com.meetdheeran.prism.ai.ToolHandler
import com.meetdheeran.prism.ai.ToolRegistry
import com.meetdheeran.prism.ai.ToolResult
import com.meetdheeran.prism.ai.ToolSpec
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.reminders.ReminderTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Calendar

/** One tool = spec + lambda. Keeps each handler a few lines and impossible to half-implement. */
internal class Tool(
    override val spec: ToolSpec,
    override val needsConfirmation: Boolean = false,
    private val run: suspend (JsonObject, ToolContext) -> ToolResult,
) : ToolHandler {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = run(args, ctx)
}

internal fun tool(
    name: String,
    description: String,
    params: JsonObject = Schema.obj { },
    confirm: Boolean = false,
    run: suspend (JsonObject, ToolContext) -> ToolResult,
) = Tool(ToolSpec(name, description, params), confirm, run)

/**
 * Every phone action the model may call. All go through public Android intents/APIs; the
 * Shizuku-backed toggles live in DeviceTools. Nothing here sends, calls or deletes on its own:
 * messages and calls open the system app pre-filled for the user to confirm.
 */
object PhoneTools {
    fun registerAll(registry: ToolRegistry, graph: AppGraph) {
        registry.register(*apps(graph).toTypedArray())
        registry.register(*time().toTypedArray())
        registry.register(*comms().toTypedArray())
        registry.register(*navigation().toTypedArray())
        registry.register(*DeviceTools.all(graph).toTypedArray())
        registry.register(*ContextTools.all(graph).toTypedArray())
        registry.register(*ReminderTools.all(graph).toTypedArray())
    }

    private fun apps(graph: AppGraph): List<ToolHandler> = listOf(
        tool(
            "open_app",
            "Open an installed app by name (e.g. 'Spotify', 'camera', 'settings'). Use list_apps first only if the name is ambiguous.",
            Schema.obj(listOf("name")) { string("name", "App name as the user said it") },
        ) { a, ctx ->
            val name = a.argStr("name") ?: return@tool failResult("Which app?")
            val matches = graph.appIndex.find(name)
            val best = matches.firstOrNull() ?: return@tool failResult("No installed app matches \"$name\"")
            graph.appIndex.launch(best)?.let { failResult(it) }
                ?: okResult("Opened ${best.label}") {
                    put("package", best.packageName)
                    if (matches.size > 1) put("otherMatches", matches.drop(1).joinToString { it.label })
                }
        },
        tool(
            "list_apps",
            "Search installed apps by partial name. Returns labels and package names.",
            Schema.obj { string("query", "Partial app name; empty for all") },
        ) { a, _ ->
            val q = a.argStr("query")
            val list = if (q.isNullOrBlank()) graph.appIndex.all().take(60) else graph.appIndex.find(q, 12)
            ToolResult.data(
                buildJsonObject {
                    put("ok", true)
                    put("apps", buildJsonArray { list.forEach { add(buildJsonObject { put("label", it.label); put("package", it.packageName) }) } })
                },
                "${list.size} apps",
            )
        },
        tool(
            "open_settings",
            "Open a system Settings screen.",
            Schema.obj(listOf("screen")) {
                string(
                    "screen", "Which screen",
                    listOf("main", "wifi", "bluetooth", "display", "sound", "battery", "apps", "notifications", "location", "security", "date_time", "language", "accessibility", "storage", "about", "developer", "default_apps", "airplane", "nfc", "hotspot", "dnd", "app_info"),
                )
                string("package", "For app_info: the package name")
            },
        ) { a, ctx ->
            val screen = a.argStr("screen") ?: "main"
            val action = when (screen) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound" -> Settings.ACTION_SOUND_SETTINGS
                "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
                "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                "notifications" -> "android.settings.NOTIFICATION_SETTINGS"
                "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "security" -> Settings.ACTION_SECURITY_SETTINGS
                "date_time" -> Settings.ACTION_DATE_SETTINGS
                "language" -> Settings.ACTION_LOCALE_SETTINGS
                "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                "about" -> Settings.ACTION_DEVICE_INFO_SETTINGS
                "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                "default_apps" -> Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
                "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                "nfc" -> Settings.ACTION_NFC_SETTINGS
                "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
                "dnd" -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS
                "app_info" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            val intent = Intent(action)
            if (screen == "app_info") intent.data = Uri.parse("package:" + (a.argStr("package") ?: ctx.app.packageName))
            ctx.app.launch(intent)?.let { failResult(it) } ?: okResult("Opened $screen settings")
        },
        tool(
            "open_url",
            "Open a web page or deep link in the default app/browser.",
            Schema.obj(listOf("url")) { string("url", "Full URL, e.g. https://example.com") },
        ) { a, ctx ->
            val url = a.argStr("url") ?: return@tool failResult("Missing url")
            val fixed = if (url.contains("://")) url else "https://$url"
            ctx.app.launch(Intent(Intent.ACTION_VIEW, Uri.parse(fixed)))?.let { failResult(it) } ?: okResult("Opened $fixed")
        },
    )

    private fun time(): List<ToolHandler> = listOf(
        tool(
            "set_alarm",
            "Set an alarm in the Clock app. 24-hour time. Optional label and repeat days.",
            Schema.obj(listOf("hour", "minute")) {
                int("hour", "0-23"); int("minute", "0-59"); string("label", "Alarm label")
                stringArray("days", "Repeat days: mon,tue,wed,thu,fri,sat,sun (empty = one-time)")
            },
        ) { a, ctx ->
            val hour = a.argInt("hour") ?: return@tool failResult("Missing hour")
            val minute = a.argInt("minute") ?: 0
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            a.argStr("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            val days = a.argList("days").mapNotNull { DAY_MAP[it.lowercase().take(3)] }
            if (days.isNotEmpty()) intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
            ctx.app.launch(intent)?.let { failResult(it) }
                ?: okResult("Alarm set for %02d:%02d".format(hour, minute))
        },
        tool(
            "set_timer",
            "Start a countdown timer in the Clock app.",
            Schema.obj(listOf("seconds")) { int("seconds", "Duration in seconds"); string("label", "Timer label") },
        ) { a, ctx ->
            val secs = a.argInt("seconds")?.takeIf { it > 0 } ?: return@tool failResult("Missing duration")
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            a.argStr("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            ctx.app.launch(intent)?.let { failResult(it) } ?: okResult("Timer started: ${human(secs)}")
        },
        tool("show_alarms", "Open the alarms list in the Clock app.") { _, ctx ->
            ctx.app.launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))?.let { failResult(it) } ?: okResult("Opened alarms")
        },
        tool("show_timers", "Open the timers screen in the Clock app.") { _, ctx ->
            ctx.app.launch(Intent(AlarmClock.ACTION_SHOW_TIMERS))?.let { failResult(it) } ?: okResult("Opened timers")
        },
    )

    private fun comms(): List<ToolHandler> = listOf(
        tool(
            "find_contact",
            "Look up a contact's phone numbers by name. Use before dial/send_sms_draft when the user gives a name.",
            Schema.obj(listOf("name")) { string("name", "Contact name or part of it") },
        ) { a, ctx ->
            val name = a.argStr("name") ?: return@tool failResult("Missing name")
            if (!ctx.app.granted(android.Manifest.permission.READ_CONTACTS)) return@tool needsPermission(android.Manifest.permission.READ_CONTACTS, "Looking up contacts")
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
            val results = ArrayList<Pair<String, String>>()
            ctx.app.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext() && results.size < 8) results += c.getString(0).orEmpty() to c.getString(1).orEmpty()
            }
            if (results.isEmpty()) failResult("No contact matches \"$name\"")
            else ToolResult.data(
                buildJsonObject {
                    put("ok", true)
                    put("contacts", buildJsonArray { results.forEach { add(buildJsonObject { put("name", it.first); put("number", it.second) }) } })
                },
                "${results.size} match(es)",
            )
        },
        tool(
            "dial",
            "Open the phone dialer with a number filled in. The user taps call themselves; nothing is dialled automatically.",
            Schema.obj(listOf("number")) { string("number", "Phone number") },
        ) { a, ctx ->
            val n = a.argStr("number") ?: return@tool failResult("Missing number")
            ctx.app.launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))?.let { failResult(it) } ?: okResult("Dialer opened with $n")
        },
        tool(
            "send_sms_draft",
            "Open the messaging app with a recipient and message pre-filled. Nothing is sent until the user taps send.",
            Schema.obj(listOf("number", "message")) { string("number", "Recipient phone number"); string("message", "Message text") },
        ) { a, ctx ->
            val n = a.argStr("number") ?: return@tool failResult("Missing number")
            val msg = a.argStr("message") ?: ""
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))).putExtra("sms_body", msg)
            ctx.app.launch(intent)?.let { failResult(it) } ?: okResult("Message draft opened for $n")
        },
        tool(
            "send_email_draft",
            "Open the email app with recipient, subject and body pre-filled (not sent).",
            Schema.obj { string("to", "Recipient email"); string("subject", "Subject"); string("body", "Body") },
        ) { a, ctx ->
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(a.argStr("to") ?: "")))
                .putExtra(Intent.EXTRA_SUBJECT, a.argStr("subject") ?: "")
                .putExtra(Intent.EXTRA_TEXT, a.argStr("body") ?: "")
            ctx.app.launch(intent)?.let { failResult(it) } ?: okResult("Email draft opened")
        },
        tool(
            "share_text",
            "Open the Android share sheet with text, so the user can send it to any app.",
            Schema.obj(listOf("text")) { string("text", "Text to share") },
        ) { a, ctx ->
            val t = a.argStr("text") ?: return@tool failResult("Missing text")
            val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share")
            ctx.app.launch(chooser)?.let { failResult(it) } ?: okResult("Share sheet opened")
        },
    )

    private fun navigation(): List<ToolHandler> = listOf(
        tool(
            "navigate",
            "Start turn-by-turn navigation to a place in the maps app.",
            Schema.obj(listOf("destination")) {
                string("destination", "Address or place name")
                string("mode", "Travel mode", listOf("driving", "walking", "transit", "bicycling"))
            },
        ) { a, ctx ->
            val dest = a.argStr("destination") ?: return@tool failResult("Missing destination")
            val mode = when (a.argStr("mode")) { "walking" -> "w"; "transit" -> "r"; "bicycling" -> "b"; else -> "d" }
            val primary = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=$mode"))
            val err = ctx.app.launch(primary)
            if (err == null) okResult("Navigating to $dest")
            else ctx.app.launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}")))?.let { failResult(it) } ?: okResult("Opened map for $dest")
        },
        tool(
            "show_map",
            "Show a place or search on the map without navigating.",
            Schema.obj(listOf("query")) { string("query", "Place, address or search like 'coffee near me'") },
        ) { a, ctx ->
            val q = a.argStr("query") ?: return@tool failResult("Missing query")
            ctx.app.launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(q)}")))?.let { failResult(it) } ?: okResult("Map opened for $q")
        },
        tool(
            "open_web_search",
            "Open a web search in the browser (use when the user wants to browse results themselves, or when no in-app search is available).",
            Schema.obj(listOf("query")) { string("query", "Search query") },
        ) { a, ctx ->
            val q = a.argStr("query") ?: return@tool failResult("Missing query")
            val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
            val err = ctx.app.launch(intent)
            if (err == null) okResult("Searching the web for \"$q\"")
            else ctx.app.launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(q)}")))?.let { failResult(it) } ?: okResult("Searching the web for \"$q\"")
        },
        tool(
            "play_music",
            "Play a song, artist, album or playlist by name in the user's music app (Spotify, YouTube Music, ...).",
            Schema.obj(listOf("query")) { string("query", "What to play"); string("app", "Optional app name to use") },
        ) { a, ctx ->
            val q = a.argStr("query") ?: return@tool failResult("Missing query")
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra(SearchManager.QUERY, q)
            a.argStr("app")?.let { app -> PACKAGE_HINTS.entries.firstOrNull { app.lowercase().contains(it.key) }?.let { intent.setPackage(it.value) } }
            ctx.app.launch(intent)?.let { failResult(it) } ?: okResult("Playing \"$q\"")
        },
    )

    private val DAY_MAP = mapOf(
        "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY,
        "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY,
    )

    private val PACKAGE_HINTS = mapOf(
        "spotify" to "com.spotify.music",
        "youtube" to "com.google.android.apps.youtube.music",
        "echo" to "echo.music.iad1tya",
    )

    internal fun human(secs: Int): String {
        val h = secs / 3600; val m = (secs % 3600) / 60; val s = secs % 60
        return buildString {
            if (h > 0) append("${h}h ")
            if (m > 0) append("${m}m ")
            if (s > 0 || isEmpty()) append("${s}s")
        }.trim()
    }
}
