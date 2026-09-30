package com.meetdheeran.prism.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.meetdheeran.prism.control.ControlCenterService
import com.meetdheeran.prism.core.AppGraph
import kotlinx.coroutines.runBlocking
import com.meetdheeran.prism.reminders.rescheduleRemindersBlocking

/** Re-arms the overlay services after a reboot or an update, only if the user left them on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val s = runBlocking { AppGraph.get(context).prefs.current() }
        runCatching { rescheduleRemindersBlocking(context) }
        if (s.islandEnabled) ContextCompat.startForegroundService(context, Intent(context, IslandService::class.java).setAction("start"))
        if (s.controlCenterEnabled) ContextCompat.startForegroundService(context, Intent(context, ControlCenterService::class.java).setAction("start"))
        if (s.aodEnabled) ContextCompat.startForegroundService(context, Intent(context, com.meetdheeran.prism.aod.AodService::class.java).setAction("start"))
    }
}
