package com.meetdheeran.prism.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.meetdheeran.prism.control.ControlCenterService
import com.meetdheeran.prism.core.AppGraph
import kotlinx.coroutines.runBlocking

/** Re-arms the overlay services after a reboot, only if the user left them on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val s = runBlocking { AppGraph.get(context).prefs.current() }
        if (s.islandEnabled) ContextCompat.startForegroundService(context, Intent(context, IslandService::class.java).setAction("start"))
        if (s.controlCenterEnabled) ContextCompat.startForegroundService(context, Intent(context, ControlCenterService::class.java).setAction("start"))
    }
}
