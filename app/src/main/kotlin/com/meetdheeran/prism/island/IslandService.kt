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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Rect
import com.meetdheeran.prism.core.IslandStyle
import com.meetdheeran.prism.shizuku.ShizukuBridge
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
import com.meetdheeran.prism.ai.Prompts
import com.meetdheeran.prism.ai.Providers
import com.meetdheeran.prism.core.VoiceInputMode
import com.meetdheeran.prism.assistant.Haptics
import com.meetdheeran.prism.assistant.SpeechOutput
import com.meetdheeran.prism.assistant.SpeechState
import com.meetdheeran.prism.assistant.SpeechInput
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
import com.meetdheeran.prism.assistant.AssistantPulse
import com.meetdheeran.prism.agent.Agent
import com.meetdheeran.prism.agent.AgentAccessibilityService
import com.meetdheeran.prism.ui.siri.Phase
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class BatteryInfo(val percent: Int = -1, val charging: Boolean = false)

/**
 * A face check running in another app (Morse's face unlock), shown on the island without words: scanning, then
 * it's you (ok) or it isn't (fail). Sent as a broadcast: [ACTION_FACE] with extra "state" = scan / ok / fail / off.
 */
enum class FaceScan { OFF, SCANNING, OK, FAIL }

const val ACTION_FACE = "com.meetdheeran.prism.ISLAND_FACE"

/** Where the island is (left, right, bottom in px; all 0 when it isn't over the status bar), sent to Morse. */
const val ACTION_ISLAND_BOUNDS = "com.meetdheeran.prism.ISLAND_BOUNDS"
const val ACTION_ISLAND_BOUNDS_ASK = "com.meetdheeran.prism.ISLAND_BOUNDS_ASK"

private const val MORSE = "com.meetdheeran.morse"
private const val MORSE_KNOCK_TAP = "com.meetdheeran.morse.KNOCK_TAP"

/** A question asked by holding the island, answered right there: listening, thinking, then the answer. */
data class Ask(
    val question: String = "",
    val answer: String = "",
    val phase: Phase = Phase.Listening,
    val level: Float = 0f,
    val error: Boolean = false,
    /** elapsedRealtime when the answer finished (0 while it's still coming). */
    val doneAt: Long = 0L,
)

/** A 3-second pop: low battery, Wi-Fi or Bluetooth connected. */
data class IslandEvent(val kind: Kind, val text: String, val at: Long) {
    enum class Kind { BATTERY_LOW, WIFI, BLUETOOTH }
}

/** What the island is showing. One "focus" at a time, priority: call > charging bloom > timer/nav > media. */
data class IslandState(
    val media: NowPlaying? = null,
    /** The most important live thing ([activities] first). */
    val activity: LiveActivity? = null,
    /** Everything live right now (calls, navigation, timers, downloads), most important first. */
    val activities: List<LiveActivity> = emptyList(),
    /** Which live thing the person swiped to ("media", "charge" or an activity's key); null: the most important. */
    val focusKey: String? = null,
    /** Plugged in and not yet full: charging is one of the live things, as a dot bar. */
    val chargingLive: Boolean = false,
    /** A question asked by holding the island (see [Ask]). */
    val ask: Ask? = null,
    val battery: BatteryInfo = BatteryInfo(),
    /** elapsedRealtime when the cable went in; the bloom shows for 3 s after. */
    val chargedAt: Long = 0L,
    val expanded: Boolean = false,
    /** Collapsed pill size in dp, derived from the real cutout + user fine-tune. */
    val pillWidthDp: Float = 88f,
    val pillHeightDp: Float = 38f,
    val event: IslandEvent? = null,
    val style: IslandStyle = IslandStyle.PILL,
    /** Snapshot of the screen strip behind the notch (lens style), and the screen rect it covers. */
    val lensBitmap: Bitmap? = null,
    val lensRect: Rect? = null,
    /** A notification sliding out of the island, and when it arrived (elapsedRealtime). */
    val peek: ShadeItem? = null,
    val peekAt: Long = 0L,
    /** The assistant's live phase, from the chat screen or the system session. */
    val ai: Phase = Phase.Idle,
    val aiLevel: Float = 0f,
    /** Keep the pill up even with nothing to show, so it can be tapped or held to talk. */
    val alwaysOn: Boolean = false,
    /** Two things at once → a pill plus a detached bubble, like the iPhone island. */
    val split: Boolean = true,
    /** The phone agent: its current step, a pending OK, or the result it just finished with. */
    val agent: Agent.State = Agent.State(),
    /** The agent's step panel is open (tap on the island while it works). */
    val agentPanel: Boolean = false,
    /** elapsedRealtime of the last unlock; the island plays the Face-ID-style unlock for a moment. */
    val unlockAt: Long = 0L,
    /** Another app's face check (Morse), and when its state last changed. */
    val face: FaceScan = FaceScan.OFF,
    val faceAt: Long = 0L,
    /**
     * In the notch: the island lives in Prism's accessibility service, above the status bar, as a black tab growing
     * out of the top edge around the camera — always there (so it can always be tapped), and taps never reach the
     * status bar. Off (no accessibility switch): the old floating pill under the status bar.
     */
    val notch: Boolean = false,
    /** The real cutout, for the tab (the pill adds the person's fine-tune instead). */
    val cutWidthDp: Float = 64f,
    val cutHeightDp: Float = 28f,
    val landscape: Boolean = false,
    /** Bumped when a timed pop (bloom, event, peek) expires, so the UI re-reads the clock. */
    val tick: Long = 0L,
) {
    val showChargeBloom: Boolean get() = chargedAt > 0 && SystemClock.elapsedRealtime() - chargedAt < 3_000
    val showEvent: Boolean get() = event != null && SystemClock.elapsedRealtime() - event.at < 3_500
    val showPeek: Boolean get() = peek != null && SystemClock.elapsedRealtime() - peekAt < 3_500
    val showAi: Boolean get() = ai != Phase.Idle
    /** The agent's answer stays up for 15 s (long enough to read) unless tapped away. */
    val showAgentResult: Boolean get() = !agent.running && agent.result != null && !agent.resultDismissed && SystemClock.elapsedRealtime() - agent.finishedAt < 15_000
    val showUnlock: Boolean get() = unlockAt > 0 && SystemClock.elapsedRealtime() - unlockAt < 1_700
    /** Scanning shows until the app says otherwise (12 s at most, in case it never does); ok / fail for a moment. */
    val showFace: Boolean get() = face != FaceScan.OFF &&
        SystemClock.elapsedRealtime() - faceAt < (if (face == FaceScan.SCANNING) 12_000 else 1_400)
    /** Cards that close when you tap anywhere else on the screen. */
    val wantsOutsideTaps: Boolean get() = expanded || showAgentResult || (agentPanel && agent.running) || ask != null
    val hasContent: Boolean get() = (notch && !landscape) || ask != null || activities.isNotEmpty() || chargingLive || showFace || showUnlock || agent.running || showAgentResult || alwaysOn || media != null || activity != null || showChargeBloom || showEvent || showPeek || showAi
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
    private val peeks = MutableStateFlow<Pair<ShadeItem?, Long>>(null to 0L)
    private var wifiCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var faceReceiver: BroadcastReceiver? = null
    private var boundsAsk: BroadcastReceiver? = null
    private var panelJob: Job? = null
    /** Whether the window currently asks for taps outside it (only while a closable card is up). */
    private var watchingOutside = false
    private var btReceiver: BroadcastReceiver? = null
    private var collapseJob: Job? = null
    private var showing = false
    /** The island is in Prism's accessibility service's window, in the notch (see [IslandState.notch]). */
    private var inNotch = false

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
        scope.launch { PrismNotificationListener.peek.collect { peeks.value = it to SystemClock.elapsedRealtime() } }
        state.value = state.value.copy(landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        // With Prism's accessibility switch on, the island moves into the notch (above the status bar).
        scope.launch { AgentAccessibilityService.connected.collect { on -> rehost(on && AgentAccessibilityService.instance != null) } }
        // Unlocked (face, fingerprint or PIN): play the Face-ID-style unlock on the island. Apps can't
        // draw over the lock screen itself, so this is the first moment the island is visible again.
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                state.value = state.value.copy(unlockAt = SystemClock.elapsedRealtime())
                sync()
                scope.launch { delay(1_800); state.value = state.value.copy(tick = SystemClock.elapsedRealtime()); sync() }
            }
        }
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        // Morse's face unlock: its camera check shows here, small, instead of on its own screen. It's only a
        // picture (the unlocking itself happens in Morse), so any app may send it.
        faceReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val face = when (i.getStringExtra("state")) {
                    "scan" -> FaceScan.SCANNING
                    "ok" -> FaceScan.OK
                    "fail" -> FaceScan.FAIL
                    else -> FaceScan.OFF
                }
                val at = SystemClock.elapsedRealtime()
                state.value = state.value.copy(face = face, faceAt = at)
                sync()
                scope.launch {
                    delay(if (face == FaceScan.SCANNING) 12_100 else 1_500)
                    if (state.value.faceAt == at) {
                        state.value = state.value.copy(face = FaceScan.OFF, tick = SystemClock.elapsedRealtime())
                        sync()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(this, faceReceiver!!, IntentFilter(ACTION_FACE), ContextCompat.RECEIVER_EXPORTED)
        // Morse asks where the island is when it starts (it may have missed the last report).
        boundsAsk = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                sentBounds = IntArray(3)
                reportBounds(if (showing) host?.view else null)
            }
        }
        ContextCompat.registerReceiver(this, boundsAsk!!, IntentFilter(ACTION_ISLAND_BOUNDS_ASK), ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, btReceiver!!, IntentFilter(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED), ContextCompat.RECEIVER_EXPORTED)
        scope.launch {
            combine(graph.prefs.settings, MediaWatcher.now, PrismNotificationListener.activities, ch.info, ch.connectedAt) { s: Settings, np, acts, bat, at ->
                IslandState(
                    media = if (s.islandShowMedia) np else null,
                    activity = liveOnes(acts, s).firstOrNull(),
                    activities = liveOnes(acts, s),
                    chargingLive = s.islandShowCharging && bat.charging && bat.percent in 0..99,
                    battery = bat,
                    chargedAt = if (s.islandShowCharging) at else 0L,
                    pillWidthDp = cutoutWidthDp() + s.islandExtraWidthDp,
                    pillHeightDp = cutoutHeightDp() + s.islandExtraHeightDp,
                    cutWidthDp = cutoutWidthDp(),
                    cutHeightDp = cutoutHeightDp(),
                    style = s.islandStyle,
                    alwaysOn = s.islandShowAssistant,
                    split = s.islandSplit,
                ) to s
            }.combine(events) { (st, s), ev -> st.copy(event = ev, lensBitmap = state.value.lensBitmap, lensRect = state.value.lensRect) to s }
            .combine(peeks) { (st, s), (item, at) -> (if (s.islandShowNotifications) st.copy(peek = item, peekAt = at) else st) to s }
            .combine(AssistantPulse.state) { (st, s), ai ->
                // The system session draws its own island panel; don't stack a second pill under it.
                (if (s.islandShowAssistant && !ai.sessionOpen) st.copy(ai = ai.phase, aiLevel = ai.level) else st) to s.islandOffsetDp
            }.combine(Agent.state) { (st, off), agent -> st.copy(agent = agent) to off }
            .collect { (next, offsetDp) ->
                val newOffset = (offsetDp * resources.displayMetrics.density).toInt()
                val offsetChanged = newOffset != offsetPx
                offsetPx = newOffset
                state.value = next.copy(
                    expanded = state.value.expanded && next.media != null,
                    agentPanel = state.value.agentPanel && next.agent.running,
                    unlockAt = state.value.unlockAt,
                    face = state.value.face,
                    faceAt = state.value.faceAt,
                    notch = inNotch,
                    landscape = state.value.landscape,
                    focusKey = state.value.focusKey,
                    ask = state.value.ask,
                )
                if (offsetChanged && showing) { host?.update(params(state.value.wantsOutsideTaps)); watchingOutside = state.value.wantsOutsideTaps }
                sync()
                refreshOutsideWatch()
                if (next.showChargeBloom || next.showEvent || next.showPeek || next.showAgentResult) {
                    delay(if (next.showAgentResult) 15_300 else 3_600)
                    state.value = state.value.copy(tick = SystemClock.elapsedRealtime())
                    sync()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Turned sideways the notch is on the side: the idle tab steps away (things with content stay).
        state.value = state.value.copy(landscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        sync()
    }

    /** Moves the island between the notch (accessibility overlay, above the status bar) and the old floating pill. */
    private fun rehost(notch: Boolean) {
        if (notch == inNotch && host != null) return
        if (showing) hide()
        host?.destroy()
        val svc = if (notch) AgentAccessibilityService.instance else null
        inNotch = svc != null
        host = if (svc != null) OverlayHost(svc, accessibility = true) else OverlayHost(this)
        if (!inNotch) reportBounds(null)
        state.value = state.value.copy(notch = inNotch)
        sync()
    }

    /**
     * Each tap on the island goes to Morse, which counts them with taps along the rest of the top edge: four quick
     * ones knock on its secret door (hidden apps, face first).
     */
    private fun tapped() {
        runCatching { sendBroadcast(Intent(MORSE_KNOCK_TAP).setPackage(MORSE)) }
    }

    /**
     * Where the island's window is, sent to Morse so its Nothing bar strips (also over the status bar) leave room
     * around it — at most every 120 ms while the island animates, and once more when it settles.
     */
    private var boundsJob: Job? = null
    private var sentBounds = IntArray(3)

    private fun reportBounds(view: android.view.View?) {
        boundsJob?.cancel()
        boundsJob = scope.launch {
            delay(120)
            val b = IntArray(3)
            if (view != null && view.isAttachedToWindow && inNotch) {
                val at = IntArray(2)
                view.getLocationOnScreen(at)
                b[0] = at[0]; b[1] = at[0] + view.width; b[2] = at[1] + view.height
            }
            if (b.contentEquals(sentBounds)) return@launch
            sentBounds = b
            runCatching {
                sendBroadcast(Intent(ACTION_ISLAND_BOUNDS).setPackage(MORSE).putExtra("left", b[0]).putExtra("right", b[1]).putExtra("bottom", b[2]))
            }
        }
    }

    /** The live things the island may show, by the person's settings, most important first. */
    private fun liveOnes(acts: List<LiveActivity>, s: Settings): List<LiveActivity> = acts.filter { a ->
        when (a.kind) {
            LiveActivity.Kind.CALL -> s.islandShowCalls
            LiveActivity.Kind.TIMER, LiveActivity.Kind.NAVIGATION, LiveActivity.Kind.PROGRESS -> s.islandShowTimers
        }
    }.sortedBy {
        when (it.kind) {
            LiveActivity.Kind.CALL -> 0
            LiveActivity.Kind.NAVIGATION -> 1
            LiveActivity.Kind.TIMER -> 2
            LiveActivity.Kind.PROGRESS -> 3
        }
    }

    /** Pull the island down: open what it's showing — the music app, the call, the download, the battery page. */
    private fun openLive(key: String) {
        val s = state.value
        runCatching {
            when (key) {
                "media" -> s.media?.packageName?.let { pkg ->
                    packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { startActivity(it) }
                }
                "charge" -> startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else -> s.activities.firstOrNull { it.key == key }?.contentIntent?.send()
            }
        }
        if (s.expanded) setExpanded(false)
    }

    private fun sync() {
        val s = state.value
        if (s.hasContent && !showing) { show(); refreshLens() } else if (!s.hasContent && showing) hide()
    }

    private var lensJob: Job? = null

    /**
     * Lens style: photograph the strip of screen behind the notch (Shizuku), hiding the island
     * for two frames so it is not in its own picture. Without Shizuku the lens falls back to
     * album art / a tint (drawn by IslandUi).
     */
    private fun refreshLens() {
        if (state.value.style != IslandStyle.LENS) return
        lensJob?.cancel()
        lensJob = scope.launch {
            if (!ShizukuBridge.isReady()) { state.value = state.value.copy(lensBitmap = null, lensRect = null); return@launch }
            val v = host?.view
            v?.alpha = 0f
            delay(60)
            val png = ShizukuBridge.screenshotPng().getOrNull()
            v?.alpha = 1f
            png ?: return@launch
            val full = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return@launch
            val d = resources.displayMetrics
            val stripPx = (240 * d.density).toInt()
            val crop = Bitmap.createBitmap(full, 0, 0, full.width, minOf(full.height, stripPx / 2))
            state.value = state.value.copy(lensBitmap = crop, lensRect = Rect(0f, 0f, d.widthPixels.toFloat(), stripPx.toFloat()))
        }
    }

    private var offsetPx = 0

    /** The waterdrop cutout as Android reports it (falls back to the OnePlus 7 numbers). */
    private fun cutout(): android.graphics.Rect? = runCatching {
        getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets.displayCutout?.boundingRectTop
    }.getOrNull()?.takeIf { !it.isEmpty }
    private fun cutoutWidthDp(): Float = (cutout()?.width() ?: 180) / resources.displayMetrics.density
    private fun cutoutHeightDp(): Float = (cutout()?.height() ?: 80) / resources.displayMetrics.density

    private fun params(expanded: Boolean): WindowManager.LayoutParams = OverlayHost.params(
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL, focusable = false,
        // In the notch the tab hangs from the very top edge; the pill keeps the person's "move down".
        y = if (inNotch) 0 else offsetPx,
        type = if (inNotch) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    ).apply {
        if (inNotch) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
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
                        onTap = { tapped() },
                        onFocus = { key -> state.value = state.value.copy(focusKey = key) },
                        onOpen = { key -> openLive(key) },
                        onAskClose = { endAsk() },
                        onAskMore = { askMore() },
                        onToggleExpand = { setExpanded(!st.expanded) },
                        onAssistant = { holdIsland() },
                        onActivityTap = { a -> runCatching { a.contentIntent?.send() } },
                        onPeekTap = { n -> runCatching { n.contentIntent?.send() } },
                        onAgentStop = { setAgentPanel(false); Agent.stop() },
                        onAgentAnswer = { allow -> Agent.answer(allow) },
                        onAgentTap = { setAgentPanel(!state.value.agentPanel) },
                        onAgentResultTap = { openAgentConversation() },
                    )
                }
            }
        }
        h.view?.let { v ->
            v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> reportBounds(view) }
            reportBounds(v)
        }
        h.view?.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                val s = state.value
                when {
                    s.ask != null -> endAsk()
                    s.showAgentResult -> Agent.dismissResult()
                    s.agentPanel -> setAgentPanel(false)
                    s.expanded -> setExpanded(false)
                }
            }
            false
        }
    }

    /** Ask for outside taps only while a closable card is showing; otherwise the window stays out of the way. */
    private fun refreshOutsideWatch() {
        val want = state.value.wantsOutsideTaps
        if (showing && want != watchingOutside) {
            host?.update(params(want))
            watchingOutside = want
        }
    }

    private fun setAgentPanel(open: Boolean) {
        state.value = state.value.copy(agentPanel = open)
        refreshOutsideWatch()
        panelJob?.cancel()
        if (open) panelJob = scope.launch { delay(6_000); setAgentPanel(false) }
    }

    private fun openAgentConversation() {
        val conv = Agent.state.value.conversationId
        Agent.dismissResult()
        runCatching {
            startActivity(Intent(this, com.meetdheeran.prism.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                if (conv != null) putExtra(com.meetdheeran.prism.MainActivity.EXTRA_CONVERSATION, conv)
            })
        }
    }

    private fun setExpanded(expanded: Boolean) {
        state.value = state.value.copy(expanded = expanded)
        host?.update(params(state.value.wantsOutsideTaps)); watchingOutside = state.value.wantsOutsideTaps
        collapseJob?.cancel()
        if (expanded) collapseJob = scope.launch { delay(5_000); setExpanded(false) }
        scope.launch { delay(350); refreshLens() }
    }

    private fun hide() {
        showing = false
        host?.hide()
        reportBounds(null)
    }

    private fun openAssistant() {
        setExpanded(false)
        AssistantLauncher.open(this)
    }

    // ------------------------------------------------------------ ask in the island

    private var speech: SpeechInput? = null
    private var askJob: Job? = null
    private var askWatch: Job? = null

    /** Hold the island: ask right there (listen, think, answer in the island) — or, if that's off, the assistant. */
    private fun holdIsland() {
        scope.launch {
            val st = AppGraph.get(this@IslandService).prefs.current()
            val mic = (speech ?: SpeechInput(this@IslandService).also { speech = it })
            if (!st.islandAskInPlace || !mic.hasMicPermission()) openAssistant() else ask(st, mic)
        }
    }

    private fun ask(st: Settings, mic: SpeechInput) {
        askJob?.cancel()
        AppGraph.get(this).speechOutput.stop()
        setExpanded(false)
        setAsk(Ask())
        Haptics.tick(this)
        askWatch?.cancel()
        askWatch = scope.launch {
            mic.state.collect { sp ->
                val a = state.value.ask ?: return@collect
                when (sp) {
                    is SpeechState.Listening -> if (a.phase == Phase.Listening) setAsk(a.copy(level = sp.level, question = sp.partial))
                    is SpeechState.Processing -> if (a.phase == Phase.Listening) setAsk(a.copy(phase = Phase.Thinking))
                    is SpeechState.Error -> finishAsk(a.copy(answer = sp.message, error = true, phase = Phase.Idle))
                    else -> Unit
                }
            }
        }
        val transcriber: (suspend (ByteArray) -> Result<String>?)? = if (st.voiceInput == VoiceInputMode.SYSTEM) null else { wav ->
            val p = if (st.voiceInput == VoiceInputMode.GROQ_WHISPER) Providers.groq() else Providers.gemini()
            val key = Providers.apiKey(this, p.provider)
            if (key == null) Result.failure(IllegalStateException(Providers.missingKeyMessage(p.provider))) else p.transcribe(key, wav)
        }
        mic.start(st.voiceInput, onFinal = { text -> if (text.isBlank()) endAsk() else answer(text, st) }, transcribe = transcriber)
    }

    /** The short answer, streamed into the island as it comes, then read out if spoken replies are on. */
    private fun answer(question: String, st: Settings) {
        askWatch?.cancel()
        setAsk(Ask(question = question, phase = Phase.Thinking))
        askJob = scope.launch {
            val graph = AppGraph.get(this@IslandService)
            val text = StringBuilder()
            val failed = runCatching {
                graph.engine.quick(question, system = Prompts.island(st)).collect { d ->
                    text.append(d)
                    setAsk(Ask(question = question, answer = text.toString(), phase = Phase.Speaking))
                }
            }.exceptionOrNull()
            val reply = if (failed != null) failed.message ?: "Couldn't answer that" else text.toString().trim()
            finishAsk(Ask(question = question, answer = reply, phase = Phase.Idle, error = failed != null))
            if (failed == null && st.speakReplies && reply.isNotBlank()) graph.speechOutput.speak(SpeechOutput.stripMarkdown(reply))
        }
    }

    /** The answer stays up 15 s (long enough to read), unless tapped away or swiped down. */
    private fun finishAsk(a: Ask) {
        val done = a.copy(doneAt = SystemClock.elapsedRealtime())
        setAsk(done)
        scope.launch {
            delay(15_000)
            if (state.value.ask?.doneAt == done.doneAt) endAsk()
        }
    }

    private fun endAsk() {
        askJob?.cancel()
        askWatch?.cancel()
        speech?.cancel()
        AppGraph.get(this).speechOutput.stop()
        setAsk(null)
    }

    /** Swipe the answer down: carry on in Prism's chat, with the same question. */
    private fun askMore() {
        val q = state.value.ask?.question.orEmpty()
        endAsk()
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_ASSISTANT)
                    .apply { if (q.isNotBlank()) putExtra(MainActivity.EXTRA_PROMPT, q) }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun setAsk(a: Ask?) {
        state.value = state.value.copy(ask = a)
        sync()
        refreshOutsideWatch()
    }

    override fun onDestroy() {
        askJob?.cancel()
        askWatch?.cancel()
        speech?.release()
        hide()
        host?.destroy()
        charging?.stop()
        wifiCallback?.let { runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        btReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        faceReceiver?.let { runCatching { unregisterReceiver(it) } }
        boundsAsk?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel()
        BackgroundNotice.stop(this)
        super.onDestroy()
    }

}
