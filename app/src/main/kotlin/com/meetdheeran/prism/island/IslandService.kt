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
import com.meetdheeran.prism.overlay.BackgroundNotice
import com.meetdheeran.prism.overlay.OverlayHost
import com.meetdheeran.prism.assistant.AssistantLauncher
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

/** A 3-second pop: low battery, Wi-Fi or Bluetooth connected. */
data class IslandEvent(val kind: Kind, val text: String, val at: Long) {
    enum class Kind { BATTERY_LOW, WIFI, BLUETOOTH }
}

/** What the island is showing. One "focus" at a time, priority: call > charging bloom > timer/nav > media. */
data class IslandState(
    val media: NowPlaying? = null,
    val activity: LiveActivity? = null,
    val battery: BatteryInfo = BatteryInfo(),
    /** elapsedRealtime when the cable went in; the bloom shows for 3 s after. */
    val chargedAt: Long = 0L,
    val expanded: Boolean = false,
    /** Collapsed pill size in dp, derived from the real cutout + user fine-tune. */
    val pillWidthDp: Float = 88f,
    val pillHeightDp: Float = 38f,
    val event: IslandEvent? = null,
) {
    val showChargeBloom: Boolean get() = chargedAt > 0 && SystemClock.elapsedRealtime() - chargedAt < 3_000
    val showEvent: Boolean get() = event != null && SystemClock.elapsedRealtime() - event.at < 3_500
    val hasContent: Boolean get() = media != null || activity != null || showChargeBloom || showEvent
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
    private val events = MutableStateFlow<IslandEvent?>(null)
    private var wifiCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var btReceiver: BroadcastReceiver? = null
    private var collapseJob: Job? = null
    private var showing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BackgroundNotice.start(this)
        if (!OverlayHost.canDrawOverlays(this)) { stopSelf(); return }
        host = OverlayHost(this)
        charging = ChargingWatcher(this).also { it.start() }
        MediaWatcher.start(this)
        val graph = AppGraph.get(this)
        val ch = charging!!
        // Low battery: one pop when crossing 15% while unplugged.
        scope.launch {
            var wasLow = false
            ch.info.collect { b ->
                val low = b.percent in 1..15 && !b.charging
                if (low && !wasLow) events.value = IslandEvent(IslandEvent.Kind.BATTERY_LOW, "Battery low \u00B7 ${b.percent}%", SystemClock.elapsedRealtime())
                wasLow = low
            }
        }
        // Wi-Fi joined (ignore the callback that fires for an already-connected network at registration).
        val startedAt = SystemClock.elapsedRealtime()
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        wifiCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                if (SystemClock.elapsedRealtime() - startedAt > 3_000) events.value = IslandEvent(IslandEvent.Kind.WIFI, "Wi-Fi connected", SystemClock.elapsedRealtime())
            }
        }
        runCatching { cm.registerNetworkCallback(android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback!!) }
        // Bluetooth device connected.
        btReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION") val dev = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                val name = runCatching { dev?.name }.getOrNull().orEmpty()
                events.value = IslandEvent(IslandEvent.Kind.BLUETOOTH, if (name.isBlank()) "Bluetooth connected" else "Connected \u00B7 $name", SystemClock.elapsedRealtime())
            }
        }
        ContextCompat.registerReceiver(this, btReceiver!!, IntentFilter(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED), ContextCompat.RECEIVER_EXPORTED)
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
                    pillWidthDp = cutoutWidthDp() + s.islandExtraWidthDp,
                    pillHeightDp = cutoutHeightDp() + s.islandExtraHeightDp,
                ) to s.islandOffsetDp
            }.combine(events) { (st, off), ev -> st.copy(event = ev) to off }.collect { (next, offsetDp) ->
                val newOffset = (offsetDp * resources.displayMetrics.density).toInt()
                val offsetChanged = newOffset != offsetPx
                offsetPx = newOffset
                state.value = next.copy(expanded = state.value.expanded && next.media != null)
                if (offsetChanged && showing) host?.update(params(state.value.expanded))
                sync()
                if (next.showChargeBloom || next.showEvent) { delay(3_600); sync() }
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

    private var offsetPx = 0

    /** The waterdrop cutout as Android reports it (falls back to the OnePlus 7 numbers). */
    private fun cutout(): android.graphics.Rect? = runCatching {
        getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets.displayCutout?.boundingRectTop
    }.getOrNull()?.takeIf { !it.isEmpty }
    private fun cutoutWidthDp(): Float = (cutout()?.width() ?: 180) / resources.displayMetrics.density
    private fun cutoutHeightDp(): Float = (cutout()?.height() ?: 80) / resources.displayMetrics.density

    private fun params(expanded: Boolean): WindowManager.LayoutParams = OverlayHost.params(
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL, focusable = false, y = offsetPx,
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
        AssistantLauncher.open(this)
    }

    override fun onDestroy() {
        hide()
        host?.destroy()
        charging?.stop()
        wifiCallback?.let { runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        btReceiver?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel()
        BackgroundNotice.stop(this)
        super.onDestroy()
    }

}
