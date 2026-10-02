package com.meetdheeran.prism.island

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * The island's touch area in the notch, as its own invisible window. The island draws in a screen-wide window that
 * never moves or resizes and lets every touch through — Android re-centres a window that changes size a frame apart
 * from its picture, so a window that wrapped the island made it jump sideways as it opened and closed. This one sits
 * exactly over the island and hands it its touches; it can move and resize freely, since nobody sees it.
 */
internal class IslandPad(context: Context, private val island: () -> View?, private val onOutside: () -> Unit) {
    // An accessibility overlay has to be added through that service's own context (its window token).
    private val wm = context.getSystemService(WindowManager::class.java)
    private val view = object : View(context) {
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { onOutside(); return false }
            forward(ev)
            return true
        }
    }
    private var params: WindowManager.LayoutParams? = null

    /** Puts the pad over [bounds] (screen px); with [outside], taps anywhere else are reported too (a card is open). */
    fun place(bounds: Rect, outside: Boolean) {
        if (bounds.isEmpty) { hide(); return }
        val flags = FLAGS or (if (outside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0)
        val p = params
        if (p != null && p.x == bounds.left && p.y == bounds.top && p.width == bounds.width() && p.height == bounds.height() && p.flags == flags) return
        val next = WindowManager.LayoutParams(
            bounds.width(), bounds.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = bounds.left
            y = bounds.top
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching { if (p == null) wm.addView(view, next) else wm.updateViewLayout(view, next) }.onSuccess { params = next }
    }

    fun hide() {
        if (params == null) return
        runCatching { wm.removeViewImmediate(view) }
        params = null
    }

    /** Hands a touch to the island's window, moved into that window's own coordinates. */
    private fun forward(ev: MotionEvent) {
        val target = island()?.takeIf { it.isAttachedToWindow } ?: return
        val at = IntArray(2)
        target.getLocationOnScreen(at)
        val e = MotionEvent.obtain(ev)
        // getRaw is where the finger is on the screen, getX where it is in this pad.
        e.offsetLocation(ev.rawX - ev.x - at[0], ev.rawY - ev.y - at[1])
        target.dispatchTouchEvent(e)
        e.recycle()
    }

    private companion object {
        const val FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
}
