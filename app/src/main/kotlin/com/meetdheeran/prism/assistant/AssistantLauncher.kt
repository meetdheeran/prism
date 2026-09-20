package com.meetdheeran.prism.assistant

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import com.meetdheeran.prism.MainActivity
import com.meetdheeran.prism.core.Images
import com.meetdheeran.prism.shizuku.ShizukuBridge
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference

/**
 * Opens the assistant the right way: when Prism is the default assistant, the system shows our
 * VoiceInteractionSession over the CURRENT app and hands it that app's text + screenshot.
 * Only when that is not possible does it fall back to opening the Prism app.
 */
object AssistantLauncher {
    @Volatile var service: PrismVoiceInteractionService? = null

    fun isDefaultAssistant(ctx: Context): Boolean =
        VoiceInteractionService.isActiveService(ctx, ComponentName(ctx, PrismVoiceInteractionService::class.java))

    fun open(ctx: Context): Boolean {
        val svc = service
        if (svc != null && isDefaultAssistant(ctx)) {
            val ok = runCatching {
                svc.showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
            }.isSuccess
            if (ok) return true
        }
        ctx.startActivity(Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_ASSISTANT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }
}

/** Which of our activities is on screen, so a screenshot can look past it. */
object Foreground : Application.ActivityLifecycleCallbacks {
    private var resumed: WeakReference<Activity>? = null
    val activity: Activity? get() = resumed?.get()

    override fun onActivityResumed(activity: Activity) { resumed = WeakReference(activity) }
    override fun onActivityPaused(activity: Activity) { if (resumed?.get() === activity) resumed = null }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

object ScreenCapture {
    /**
     * Shizuku screenshot of what is BEHIND Prism: if one of our activities is in front, it steps
     * aside for the capture and comes straight back. Returns JPEG bytes.
     */
    suspend fun behindPrism(ctx: Context): Result<ByteArray> {
        val ours = Foreground.activity
        if (ours != null) {
            ours.moveTaskToBack(true)
            delay(500)
        }
        val png = ShizukuBridge.screenshotPng()
        if (ours != null) {
            runCatching { ctx.startActivity(Intent(ctx, ours.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        }
        return png.mapCatching { Images.toJpeg(it) ?: throw IllegalStateException("Screenshot could not be decoded") }
    }
}
