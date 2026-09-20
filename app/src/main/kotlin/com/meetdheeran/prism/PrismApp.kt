package com.meetdheeran.prism

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.meetdheeran.prism.control.ControlCenterService
import com.meetdheeran.prism.island.IslandService
import kotlinx.coroutines.launch
import com.meetdheeran.prism.assistant.Foreground
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.island.MediaWatcher
import com.meetdheeran.prism.shizuku.ShizukuBridge

class PrismApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(Foreground)
        val graph = AppGraph.get(this)
        ShizukuBridge.init(this)
        MediaWatcher.start(this)
        // Overlay services the user left on come back with the app (they die with the process on reinstall).
        graph.scope.launch {
            val s = graph.prefs.current()
            runCatching {
                if (s.controlCenterEnabled) ContextCompat.startForegroundService(this@PrismApp, Intent(this@PrismApp, ControlCenterService::class.java).setAction("start"))
                if (s.islandEnabled) ContextCompat.startForegroundService(this@PrismApp, Intent(this@PrismApp, IslandService::class.java).setAction("start"))
            }
        }
    }
}
