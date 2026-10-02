package com.meetdheeran.prism.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meetdheeran.prism.core.AppGraph
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDate

/**
 * Morse's "Make your own" widgets: a description comes in ([ACTION], with "id", "prompt" and "today"), the configured
 * AI turns it into a small JSON spec (one of a few building blocks), and the spec goes back to Morse ([MADE]). Only
 * the words of the description are sent to the AI.
 */
class WidgetMakerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getStringExtra("id") ?: return
        val prompt = intent.getStringExtra("prompt").orEmpty().take(200)
        val today = intent.getStringExtra("today") ?: LocalDate.now().toString()
        val pending = goAsync()
        val graph = AppGraph.get(ctx)
        graph.scope.launch {
            val reply = Intent(MADE).setPackage(MORSE).putExtra("id", id)
            runCatching {
                val text = StringBuilder()
                withTimeout(25_000) { graph.engine.quick(prompt, system = Prompts.widget(today)).collect { text.append(it) } }
                val start = text.indexOf("{")
                val end = text.lastIndexOf("}")
                require(start >= 0 && end > start) { "No widget in the answer" }
                reply.putExtra("spec", text.substring(start, end + 1))
            }.onFailure { reply.putExtra("error", (it.message ?: "Couldn't make it").take(80)) }
            runCatching { ctx.sendBroadcast(reply) }
            pending.finish()
        }
    }

    companion object {
        const val ACTION = "com.meetdheeran.prism.MAKE_WIDGET"
        const val MADE = "com.meetdheeran.morse.WIDGET_MADE"
        private const val MORSE = "com.meetdheeran.morse"
    }
}
