package com.meetdheeran.prism.actions

import com.meetdheeran.prism.agent.Agent
import com.meetdheeran.prism.ai.Schema
import com.meetdheeran.prism.ai.ToolHandler

/**
 * Hands a multi-step, inside-another-app task to the phone agent. It returns as soon as the agent
 * has started; the agent reports its result itself (island, voice, and a message in this
 * conversation), and asks the user on the island before anything is sent.
 */
object AgentTools {
    fun all(): List<ToolHandler> = listOf(
        tool(
            "operate_phone",
            "Work inside other apps on the phone, step by step, like a person would: send a message in Zoom/WhatsApp/Instagram/Teams, " +
                "actually send an email in Gmail, find something inside an app. Use it when the user wants something DONE in an app, " +
                "not just opened or drafted. Don't use it for things other tools do directly (alarms, timers, calendar, volume, opening an app). " +
                "Resolve people first when you can (find_contact for email addresses or numbers). The agent asks the user before sending anything.",
            Schema.obj(listOf("goal")) {
                string("goal", "The complete task in one sentence, with names and exact text, e.g. 'Open Zoom and send \"hi\" to Anushka Thakur in Team Chat'")
                string("context", "Information the agent needs, e.g. the text that was on the user's screen to send, or an email address")
            },
        ) { a, ctx ->
            val goal = a.argStr("goal") ?: return@tool failResult("What should I do on the phone?")
            val err = Agent.start(ctx.app, goal, a.argStr("context"), ctx.conversationId)
            if (err != null) failResult(err.message, needs = err.needs)
            else okResult(
                "The phone agent has started and is working on it now. Reply to the user in a few words that you're on it " +
                    "(don't list steps, don't claim it's done). The agent reports the result itself and asks the user before sending.",
                userVisible = "Agent started",
            )
        },
    )
}
