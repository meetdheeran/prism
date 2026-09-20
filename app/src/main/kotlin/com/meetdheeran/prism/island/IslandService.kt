package com.meetdheeran.prism.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.meetdheeran.prism.MainActivity
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.core.Settings
import com.meetdheeran.prism.overlay.OverlayHost
import com.meetdheeran.prism.ui.motion.LocalTilt
import com.meetdheeran.prism.ui.motion.rememberDeviceTilt
import com.meetdheeran.prism.ui.theme.PrismTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class BatteryInfo(val percent: Int = -1, val charging: Boolean = false)

/** What the island is showing. One "focus" at a time, priority: call > charging bloom > timer/nav > media. */
data class IslandState(
    val media: NowPlaying? = null,
    val activity: LiveActivity? = null,
    val battery: BatteryInfo = BatteryInfo(),
    /** elapsedRealtime when the cable went in; the bloom shows for 3 s after. */
    val chargedAt: Long = 0L,
    val expanded: Boolean = false,
) {
    val showChargeBloom: Boolean get() = chargedAt > 0 && SystemClock.elapsedRealtime() - chargedAt < 3_000
    val hasContent: Boolean get() = media != null || activity != null || showChargeBloom
}

/** Battery broadcasts as flows. */
class ChargingWatcher(private val ctx: Context) {
    val info = MutableStateFlow(BatteryInfo())
    val connectedAt = MutableStateFlow(0L)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    info.value = BatteryInfo(if (level >= 0) level * 100 / scale else -1, charging)
                }
                Intent.ACTION_POWER_CONNECTED -> connectedAt.value = SystemClock.elapsedRealtime()
                Intent.ACTION_POWER_DISCONNECTED -> connectedAt.value = 0L
            }
        }
    }

    fun start() {
        val f = IntentFilter().apply { addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }
        ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() { runCatching { ctx.unregisterReceiver(receiver) } }
}

/**
 * Foreground service that owns the island overlay. It only adds the window while there is
 * something to show, so an idle phone keeps a plain notch.
 */
class IslandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: OverlayHost? = null
    private var charging: ChargingWatcher? = null
    private val state = MutableStateFlow(IslandState())
    private var collapseJob: Job? = null
    private var showing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, notification())
        if (!OverlayHost.canDrawOverlays(this)) { stopSelf(); return }
        host = OverlayHost(this)
        charging = ChargingWatcher(this).also { it.start() }
        MediaWatcher.start(this)
        val graph = AppGraph.get(this)
        val ch = charging!!
        scope.launch {
            combine(graph.prefs.settings, MediaWatcher.now, PrismNotificationListener.activities, ch.info, ch.connectedAt) { s: Settings, np, acts, bat, at ->
                IslandState(
                    media = if (s.islandShowMedia) np else null,
                    activity = acts.firstOrNull { a ->
                        when (a.kind) {
                            LiveActivity.Kind.CALL -> s.islandShowCalls
                            LiveActivity.Kind.TIMER, LiveActivity.Kind.NAVIGATION -> s.islandShowTimers
                        }
                    },
                    battery = bat,
                    chargedAt = if (s.islandShowCharging) at else 0L,
                )
            }.collect { next ->
                state.value = next.copy(expanded = state.value.expanded && next.media != null)
                sync()
                if (next.showChargeBloom) { delay(3_100); sync() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    private fun sync() {
        val s = state.value
        if (s.hasContent && !showing) show() else if (!s.hasContent && showing) hide()
    }

    private fun params(expanded: Boolean): WindowManager.LayoutParams = OverlayHost.params(
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL, focusable = false,
    ).apply {
        if (expanded) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    }

    private fun show() {
        val h = host ?: return
        showing = true
        h.show(params(false)) {
            val tilt by rememberDeviceTilt()
            val st by state.collectAsState()
            PrismTheme(accent = Color(0xFF0A84FF)) {
                CompositionLocalProvider(LocalTilt provides tilt) {
                    IslandUi(
                        state = st,
                        onToggleExpand = { setExpanded(!st.expanded) },
                        onAssistant = { openAssistant() },
                        onActivityTap = { a -> runCatching { a.contentIntent?.send() } },
                    )
                }
            }
        }
        h.view?.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE && state.value.expanded) setExpanded(false)
            false
        }
    }

    private fun setExpanded(expanded: Boolean) {
        state.value = state.value.copy(expanded = expanded)
        host?.update(params(expanded))
        collapseJob?.cancel()
        if (expanded) collapseJob = scope.launch { delay(5_000); setExpanded(false) }
    }

    private fun hide() {
        showing = false
        host?.hide()
    }

    private fun openAssistant() {
        setExpanded(false)
        startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_ASSISTANT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Dynamic Island", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Dynamic Island is on")
            .setContentText("Music and live activities around the notch")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        hide()
        host?.destroy()
        charging?.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "island"
        const val NOTIF_ID = 1101
    }
}
