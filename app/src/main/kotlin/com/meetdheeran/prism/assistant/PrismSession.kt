package com.meetdheeran.prism.assistant

import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
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
import com.meetdheeran.prism.MainActivity
import com.meetdheeran.prism.R
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.core.Images
import com.meetdheeran.prism.ui.theme.PrismTheme

/** Registered as the phone's digital assistant; the system starts sessions through us. */
class PrismVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() { super.onReady(); AssistantLauncher.service = this }
    override fun onShutdown() { if (AssistantLauncher.service === this) AssistantLauncher.service = null; super.onShutdown() }
    override fun onDestroy() { if (AssistantLauncher.service === this) AssistantLauncher.service = null; super.onDestroy() }
}

class PrismSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = PrismSession(this)
}

/**
 * The framework insists an assistant declares a RecognitionService. Prism does its own
 * listening (SpeechInput), so this one politely refuses instead of pretending.
 */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) { runCatching { listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) } }
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}

/**
 * One assist invocation: the glow blooms, we listen, the answer card grows from the pill.
 * Screen context arrives from the system (structure text + screenshot) when Android grants
 * it; otherwise the session can still capture through Shizuku.
 */
class PrismSession(ctx: Context) : VoiceInteractionSession(ctx), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private val graph = AppGraph.get(ctx)
    private val model = SessionModel(ctx.applicationContext, graph)

    init {
        setTheme(R.style.Theme_Prism_Transparent)
        model.onEnd = { hide() }
        savedStateController.performRestore(null)
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        window?.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@PrismSession)
        setViewTreeSavedStateRegistryOwner(this@PrismSession)
        setViewTreeViewModelStoreOwner(this@PrismSession)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            val settings by model.settings
            PrismTheme(accent = Color(settings.accentArgb)) {
                SessionUi(model, onClose = { hide() }, onOpenApp = {
                    hide()
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                        model.conversationId?.let { putExtra(MainActivity.EXTRA_CONVERSATION, it) }
                    })
                })
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        model.begin(autoListen = true)
    }

    override fun onHide() {
        super.onHide()
        model.end()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        state.assistStructure?.let { model.structureText = extractText(it) }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        screenshot?.let {
            model.screenshot = Images.bitmapToJpeg(it)
            model.screenBitmap = runCatching { Bitmap.createScaledBitmap(it, it.width / 2, it.height / 2, true) }.getOrNull()
        }
    }

    override fun onBackPressed() { hide() }

    override fun onDestroy() {
        model.end()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    private fun extractText(s: AssistStructure): String {
        val sb = StringBuilder()
        fun walk(n: AssistStructure.ViewNode) {
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append('\n') }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length < 120 }?.let { sb.append('[').append(it).append("]\n") }
            for (i in 0 until n.childCount) walk(n.getChildAt(i))
        }
        for (i in 0 until s.windowNodeCount) walk(s.getWindowNodeAt(i).rootViewNode)
        return sb.toString().take(4000)
    }
}
