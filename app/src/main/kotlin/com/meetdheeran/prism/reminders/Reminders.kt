package com.meetdheeran.prism.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meetdheeran.prism.MainActivity
import com.meetdheeran.prism.actions.argInt
import com.meetdheeran.prism.actions.argStr
import com.meetdheeran.prism.actions.failResult
import com.meetdheeran.prism.actions.okResult
import com.meetdheeran.prism.actions.tool
import com.meetdheeran.prism.ai.Schema
import com.meetdheeran.prism.ai.ToolHandler
import com.meetdheeran.prism.ai.ToolResult
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.data.ReminderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exact alarms → [ReminderReceiver] → a high-priority notification with Done / Snooze. */
object ReminderScheduler {
    const val CHANNEL = "reminders"
    private val fmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

    fun format(at: Long): String = fmt.format(Date(at))

    private fun pending(ctx: Context, id: Long, action: String = "fire"): PendingIntent = PendingIntent.getBroadcast(
        ctx, (id % Int.MAX_VALUE).toInt(),
        Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(ctx: Context, r: ReminderEntity) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, r.id)
        val at = maxOf(r.atMillis, System.currentTimeMillis() + 1_000)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))
        ctx.getSystemService(NotificationManager::class.java).cancel(notificationId(id))
    }

    /** After boot or reinstall: re-arm everything still pending; fire anything missed. */
    suspend fun rescheduleAll(ctx: Context) {
        val graph = AppGraph.get(ctx)
        graph.db.reminders().pending().forEach { r ->
            if (r.atMillis <= System.currentTimeMillis()) notify(ctx, r) else schedule(ctx, r)
        }
    }

    fun notificationId(id: Long) = 2000 + (id % 100_000).toInt()

    fun notify(ctx: Context, r: ReminderEntity) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply { description = "Reminders you set with Prism" })
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle("Reminder")
            .setContentText(r.text)
            .setStyle(Notification.BigTextStyle().bigText(r.text))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(Notification.Action.Builder(null, "Done", pending(ctx, r.id, "done")).build())
            .addAction(Notification.Action.Builder(null, "Snooze 10 min", pending(ctx, r.id, "snooze")).build())
            .build()
        nm.notify(notificationId(r.id), n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = AppGraph.get(context)
                val dao = graph.db.reminders()
                val r = dao.byId(id) ?: return@launch
                when (intent.action) {
                    "fire" -> ReminderScheduler.notify(context, r)
                    "done" -> { dao.update(r.copy(done = true)); ReminderScheduler.cancel(context, id) }
                    "snooze" -> {
                        val next = r.copy(atMillis = System.currentTimeMillis() + 10 * 60_000L)
                        dao.update(next)
                        context.getSystemService(NotificationManager::class.java).cancel(ReminderScheduler.notificationId(id))
                        ReminderScheduler.schedule(context, next)
                    }
                }
            } finally {
                result.finish()
            }
        }
    }
}

/** Tools the model uses: set / list / complete / delete. Times are local, yyyy-MM-ddTHH:mm or minutes from now. */
object ReminderTools {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)

    fun all(graph: AppGraph): List<ToolHandler> = listOf(
        tool(
            "set_reminder",
            "Create a reminder that Prism itself will notify about at the given time. Prefer this over set_alarm for 'remind me to ...'.",
            Schema.obj(listOf("text")) {
                string("text", "What to remind, e.g. 'call Mom'")
                string("at", "Local time as yyyy-MM-ddTHH:mm (compute from the current time in the system prompt)")
                int("in_minutes", "Alternative: minutes from now")
            },
        ) { a, ctx ->
            val text = a.argStr("text") ?: return@tool failResult("Missing text")
            val at = a.argStr("at")?.let { runCatching { iso.parse(it)?.time }.getOrNull() }
                ?: a.argInt("in_minutes")?.let { System.currentTimeMillis() + it * 60_000L }
                ?: return@tool failResult("Give 'at' (yyyy-MM-ddTHH:mm) or 'in_minutes'")
            if (at < System.currentTimeMillis() - 60_000) return@tool failResult("That time is in the past")
            val id = graph.db.reminders().insert(ReminderEntity(text = text, atMillis = at, createdAt = System.currentTimeMillis()))
            ReminderScheduler.schedule(ctx.app, graph.db.reminders().byId(id)!!)
            okResult("Reminder set for ${ReminderScheduler.format(at)}: $text", userVisible = "Reminder · ${ReminderScheduler.format(at)}") { put("id", id) }
        },
        tool("list_reminders", "List pending reminders with ids and times.") { _, _ ->
            val items = graph.db.reminders().pending()
            ToolResult.data(
                buildJsonObject {
                    put("ok", true)
                    put("reminders", buildJsonArray { items.forEach { add(buildJsonObject { put("id", it.id); put("text", it.text); put("at", ReminderScheduler.format(it.atMillis)) }) } })
                },
                "${items.size} reminder(s)",
            )
        },
        tool(
            "delete_reminder",
            "Delete or complete a reminder by id (from list_reminders).",
            Schema.obj(listOf("id")) { int("id", "Reminder id") },
        ) { a, ctx ->
            val id = a.argInt("id")?.toLong() ?: return@tool failResult("Missing id")
            val r = graph.db.reminders().byId(id) ?: return@tool failResult("No reminder $id")
            graph.db.reminders().delete(id)
            ReminderScheduler.cancel(ctx.app, id)
            okResult("Deleted reminder: ${r.text}")
        },
    )
}

/** Small helper for BootReceiver (synchronous context). */
fun rescheduleRemindersBlocking(ctx: Context) = runBlocking { ReminderScheduler.rescheduleAll(ctx) }
