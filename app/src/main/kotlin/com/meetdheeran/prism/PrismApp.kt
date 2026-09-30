package com.meetdheeran.prism

import android.app.Application
import android.content.res.Configuration
import android.content.Intent
import androidx.core.content.ContextCompat
import com.meetdheeran.prism.control.ControlCenterService
import com.meetdheeran.prism.island.IslandService
import com.meetdheeran.prism.reminders.ReminderScheduler
import kotlinx.coroutines.launch
import com.meetdheeran.prism.assistant.Foreground
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.island.MediaWatcher
import com.meetdheeran.prism.shizuku.ShizukuBridge
import com.meetdheeran.prism.ui.theme.Appearance
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class PrismApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(Foreground)
        val graph = AppGraph.get(this)
        Appearance.dark = isNight(resources.configuration)
        graph.scope.launch {
            graph.prefs.settings.distinctUntilChanged().collectLatest { s ->
                Appearance.look = s.look
                Appearance.accent = androidx.compose.ui.graphics.Color(s.nothingAccent)
                Appearance.dotGrid = s.nothingDotGrid
                Appearance.dotTitles = s.nothingDotTitles
                Appearance.glyphStrength = s.glyphStrength
                Appearance.edgeLights = s.edgeLights
                Appearance.typewriterCps = s.typewriterCps
                Appearance.islandSpeed = s.islandSpeed
            }
        }
        ShizukuBridge.init(this)
        MediaWatcher.start(this)
        // Overlay services the user left on come back with the app (they die with the process on reinstall).
        graph.scope.launch {
            runCatching { ReminderScheduler.rescheduleAll(this@PrismApp) }
            val selected = graph.prefs.current().provider
            com.meetdheeran.prism.ai.Providers.providerWithKey(this@PrismApp, selected)?.let { p -> graph.prefs.update { it.copy(provider = p) } }
            val s = graph.prefs.current()
            runCatching {
                if (s.controlCenterEnabled) ContextCompat.startForegroundService(this@PrismApp, Intent(this@PrismApp, ControlCenterService::class.java).setAction("start"))
                if (s.islandEnabled) ContextCompat.startForegroundService(this@PrismApp, Intent(this@PrismApp, IslandService::class.java).setAction("start"))
                if (s.aodEnabled) ContextCompat.startForegroundService(this@PrismApp, Intent(this@PrismApp, com.meetdheeran.prism.aod.AodService::class.java).setAction("start"))
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Appearance.dark = isNight(newConfig)
    }

    private fun isNight(c: Configuration) =
        (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
