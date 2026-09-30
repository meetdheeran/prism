package com.meetdheeran.prism.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.meetdheeran.prism.core.Images
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The agent's hands and eyes: Android's accessibility service, the same system screen readers use.
 * It lets Prism read the buttons and text of the app in front and press / type into them.
 *
 * Idle, it listens to nothing but window changes (cheap). While a task runs, [Agent] switches on
 * content-change events so [settle] can tell when a screen has stopped moving.
 */
class AgentAccessibilityService : AccessibilityService() {

    @Volatile private var lastEventAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        setBusy(false)
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        _connected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        _connected.value = false
        super.onDestroy()
    }

    /** Only listen to every content change while a task is running. */
    fun setBusy(busy: Boolean) {
        val info = serviceInfo ?: return
        info.eventTypes = if (busy) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        } else AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.notificationTimeout = if (busy) 50 else 500
        serviceInfo = info
    }

    /** The window the user is looking at (not the keyboard, not our own overlays). */
    fun activeRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * Wait for the screen to stop changing after an action: no accessibility events for [quietMs],
     * or [maxMs] at most. Apps animate, load and re-lay-out after a tap; reading too early sees the
     * old screen.
     */
    suspend fun settle(quietMs: Long = 350, maxMs: Long = 2_500) {
        val start = SystemClock.uptimeMillis()
        delay(250)
        while (SystemClock.uptimeMillis() - start < maxMs) {
            if (SystemClock.uptimeMillis() - lastEventAt >= quietMs) return
            delay(60)
        }
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    /** A real finger tap (or long press) at screen pixels — for things that ignore ACTION_CLICK. */
    suspend fun tapAt(x: Float, y: Float, longPress: Boolean = false): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, if (longPress) 650 else 60)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 320): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build())
    }

    private suspend fun dispatch(g: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    /**
     * JPEG of the screen for when an app exposes too little to read (Android 11+). Secure screens
     * (banking, passwords) come back as a failure, which is the point.
     */
    suspend fun screenshotJpeg(maxSide: Int = 1024): ByteArray? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val jpeg = runCatching {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    soft?.let { Images.bitmapToJpeg(it, maxSide) }
                }.getOrNull()
                if (cont.isActive) cont.resume(jpeg)
            }
            override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
        })
    }

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
            private set
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected

        fun component(ctx: Context) = ComponentName(ctx, AgentAccessibilityService::class.java)

        /** Whether the user has switched the service on in Android's accessibility settings. */
        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = component(ctx)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Android's accessibility page, scrolled to Prism's entry where the OS supports that. */
        fun openSettings(ctx: Context) {
            val key = component(ctx).flattenToString()
            val intent = android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(":settings:fragment_args_key", key)
                .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", key) })
            runCatching { ctx.startActivity(intent) }
        }
    }
}
