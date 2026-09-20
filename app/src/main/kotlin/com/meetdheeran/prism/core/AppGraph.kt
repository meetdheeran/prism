package com.meetdheeran.prism.core

import android.content.Context
import com.meetdheeran.prism.actions.AppIndex
import com.meetdheeran.prism.actions.PhoneTools
import com.meetdheeran.prism.ai.Assistant
import com.meetdheeran.prism.ai.AssistantEngine
import com.meetdheeran.prism.ai.ToolRegistry
import com.meetdheeran.prism.assistant.SpeechOutput
import com.meetdheeran.prism.data.HistoryRepository
import com.meetdheeran.prism.data.MemoryRepository
import com.meetdheeran.prism.data.PrismDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency graph. One instance per process, reachable from
 * activities, services and the VoiceInteractionSession alike.
 */
class AppGraph private constructor(val app: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs by lazy { Prefs(app) }
    val db by lazy { PrismDatabase.get(app) }
    val history by lazy { HistoryRepository(db.conversations(), db.messages()) }
    val memory by lazy { MemoryRepository(db.memories()) }
    val appIndex by lazy { AppIndex(app) }
    val tools: ToolRegistry by lazy { ToolRegistry().also { PhoneTools.registerAll(it, this) } }
    val assistant: Assistant by lazy { AssistantEngine(this) }
    val engine: AssistantEngine get() = assistant as AssistantEngine
    val speechOutput by lazy { SpeechOutput(app) }

    companion object {
        @Volatile private var instance: AppGraph? = null
        fun get(ctx: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(ctx.applicationContext).also { instance = it }
        }
    }
}
