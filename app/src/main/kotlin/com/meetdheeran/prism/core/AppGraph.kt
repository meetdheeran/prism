package com.meetdheeran.prism.core

import android.content.Context
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

    companion object {
        @Volatile private var instance: AppGraph? = null
        fun get(ctx: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(ctx.applicationContext).also { instance = it }
        }
    }
}
