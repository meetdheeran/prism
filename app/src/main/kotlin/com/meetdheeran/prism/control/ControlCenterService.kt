package com.meetdheeran.prism.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnLayout
import com.meetdheeran.prism.MainActivity
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.core.GestureEdge
import com.meetdheeran.prism.core.Settings
import com.meetdheeran.prism.overlay.BackgroundNotice
import com.meetdheeran.prism.overlay.OverlayHost
import com.meetdheeran.prism.shizuku.ShizukuBridge
import com.meetdheeran.prism.ui.motion.LocalTilt
import com.meetdheeran.prism.ui.motion.rememberDeviceTilt
import com.meetdheeran.prism.ui.theme.PrismTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Owns two overlays: a thin swipe handle on the chosen edge, and the full-screen glass panel.
 * Opening takes a Shizuku screenshot first so the panel can be real frosted glass over the app
 * underneath (Android 12 gives overlays no live backdrop on this device).
 */
class ControlCenterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var handle: OverlayHost? = null
    private var panel: OverlayHost? = null
    private var control: TileControl? = null
    private var settings = Settings()
    private val panelVisible = mutableStateOf(false)
    private var closing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BackgroundNotice.start(this)
        if (!OverlayHost.canDrawOverlays(this)) { stopSelf(); return }
        handle = OverlayHost(this)
        panel = OverlayHost(this)
        control = TileControl(this, scope).also { it.start() }
        scope.launch {
            AppGraph.get(this@ControlCenterService).prefs.settings.collect { s ->
                val edgeChanged = s.gestureEdge != settings.gestureEdge || s.gestureHandleFraction != settings.gestureHandleFraction
                settings = s
                if (edgeChanged || handle?.isShowing != true) showHandle()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == "open") openPanel()
        return START_STICKY
    }

    private fun handleParams(): WindowManager.LayoutParams {
        val d = resources.displayMetrics
        val thick = (10 * d.density).roundToInt()
        val long = (150 * d.density).roundToInt()
        return when (settings.gestureEdge) {
            GestureEdge.BOTTOM -> OverlayHost.params(width = long, height = thick, gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, y = (18 * d.density).roundToInt())
            GestureEdge.LEFT -> OverlayHost.params(width = thick, height = long, gravity = Gravity.TOP or Gravity.START, y = ((d.heightPixels - long) * settings.gestureHandleFraction).roundToInt())
            GestureEdge.RIGHT -> OverlayHost.params(width = thick, height = long, gravity = Gravity.TOP or Gravity.END, y = ((d.heightPixels - long) * settings.gestureHandleFraction).roundToInt())
        }
    }

    private fun showHandle() {
        val h = handle ?: return
        h.hide()
        val edge = settings.gestureEdge
        h.show(handleParams()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(edge) {
                        var travel = 0f
                        detectDragGestures(
                            onDragStart = { travel = 0f },
                            onDrag = { change, drag ->
                                change.consume()
                                travel += when (edge) { GestureEdge.RIGHT -> -drag.x; GestureEdge.LEFT -> drag.x; GestureEdge.BOTTOM -> -drag.y }
                                if (travel > 28.dp.toPx()) { travel = -9999f; openPanel() }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                // A faint pill so the user can find it; nearly invisible over content.
                val vertical = edge != GestureEdge.BOTTOM
                Box(
                    Modifier
                        .width(if (vertical) 3.dp else 90.dp)
                        .height(if (vertical) 90.dp else 3.dp)
                        .background(Color.White.copy(alpha = 0.28f), CircleShape),
                )
            }
        }
        // The screen edge is the system back-gesture zone; carve our strip out of it (<= 200 dp is honoured).
        h.view?.doOnLayout { v -> v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, v.width, v.height)) }
    }

    fun openPanel() {
        val p = panel ?: return
        if (p.isShowing || closing) return
        val ctrl = control ?: return
        ctrl.refresh()
        scope.launch {
            handle?.hide()
            delay(20)
            val shot: Bitmap? = if (ShizukuBridge.isReady()) {
                withTimeoutOrNull(450) { ShizukuBridge.screenshotPng().getOrNull() }?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
                }
            } else null
            panelVisible.value = false
            val params = OverlayHost.params(
                width = WindowManager.LayoutParams.MATCH_PARENT, height = WindowManager.LayoutParams.MATCH_PARENT,
                gravity = Gravity.TOP, focusable = true,
            )
            val tiles = Tiles.parse(settings.tilesOrder).filter { it.second }.map { it.first }
            p.show(params) {
                val tilt by rememberDeviceTilt()
                val visible by panelVisible
                PrismTheme(accent = Color(settings.accentArgb)) {
                    CompositionLocalProvider(LocalTilt provides tilt) {
                        ControlPanel(control = ctrl, tiles = tiles, screenshot = shot, visible = visible, onClose = { closePanel() })
                    }
                }
            }
            p.view?.apply {
                isFocusableInTouchMode = true
                requestFocus()
                setOnKeyListener { _, code, ev -> if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) { closePanel(); true } else false }
            }
            delay(16)
            panelVisible.value = true
        }
    }

    fun closePanel() {
        if (closing) return
        closing = true
        panelVisible.value = false
        scope.launch {
            delay(260)
            panel?.hide()
            closing = false
            showHandle()
        }
    }

    override fun onDestroy() {
        control?.stop()
        panel?.destroy()
        handle?.destroy()
        scope.cancel()
        BackgroundNotice.stop(this)
        super.onDestroy()
    }

}

@Suppress("unused")
private fun Float.near(other: Float) = abs(this - other) < 0.001f
