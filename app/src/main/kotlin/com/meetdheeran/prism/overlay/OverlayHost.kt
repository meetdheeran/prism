package com.meetdheeran.prism.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.meetdheeran.prism.R

/**
 * Hosts Jetpack Compose inside a WindowManager overlay window (TYPE_APPLICATION_OVERLAY).
 * Compose needs the three ViewTree owners that an Activity normally provides; a Service has
 * none, so this object is all three. One host per overlay window.
 */
class OverlayHost(context: Context) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val ctx = ContextThemeWrapper(context.applicationContext, R.style.Theme_Prism)
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    var view: ComposeView? = null
        private set
    var params: WindowManager.LayoutParams? = null
        private set

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    val isShowing: Boolean get() = view != null

    fun show(params: WindowManager.LayoutParams, content: @Composable () -> Unit) {
        if (view != null) { update(params); return }
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(this@OverlayHost)
            setViewTreeSavedStateRegistryOwner(this@OverlayHost)
            setViewTreeViewModelStoreOwner(this@OverlayHost)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(content)
        }
        runCatching { wm.addView(v, params) }.onFailure { return }
        view = v
        this.params = params
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun update(params: WindowManager.LayoutParams) {
        val v = view ?: return
        this.params = params
        runCatching { wm.updateViewLayout(v, params) }
    }

    fun hide() {
        val v = view ?: return
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        runCatching { wm.removeViewImmediate(v) }
        view = null
        params = null
    }

    fun destroy() {
        hide()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    companion object {
        fun canDrawOverlays(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

        /**
         * Base params for a non-focusable overlay. FLAG_NOT_TOUCH_MODAL lets touches outside our
         * view pass through to the app underneath; FLAG_LAYOUT_IN_SCREEN/NO_LIMITS let the island
         * sit inside the cutout area.
         */
        fun params(
            width: Int = WindowManager.LayoutParams.WRAP_CONTENT,
            height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
            gravity: Int = Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            focusable: Boolean = false,
            touchable: Boolean = true,
            x: Int = 0,
            y: Int = 0,
        ): WindowManager.LayoutParams {
            var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            return WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                this.gravity = gravity
                this.x = x
                this.y = y
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                // Android 12 blocks touches through overlays that are too opaque; ours are glass, but keep this low.
                alpha = 1f
            }
        }
    }
}
