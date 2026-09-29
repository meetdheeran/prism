package com.meetdheeran.prism.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.meetdheeran.prism.ai.Providers
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.core.Provider
import com.meetdheeran.prism.core.SecureStore
import com.meetdheeran.prism.ui.glass.BackdropState
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val AI_STUDIO_URL = "https://aistudio.google.com/apikey"

/** Gemini keys are "AIza" + 35 URL-safe characters. */
private val GEMINI_KEY = Regex("AIza[0-9A-Za-z_\\-]{35}")

/**
 * The free path for people without a paid key. Google gives every Google account a free Gemini
 * key in AI Studio; this walks them there and back:
 *  1. "Get my free key" opens AI Studio in the browser (they sign in, tap Create API key, tap Copy).
 *  2. When they come back, Prism looks at the clipboard once. A Gemini-shaped key is tested with
 *     one cheap list-models call, saved encrypted, and wiped from the clipboard.
 * Nothing is shared: each person uses their own key, so it costs the app's author nothing.
 */
@Composable
fun FreeKeySetup(backdrop: BackdropState, onReady: () -> Unit = {}) {
    val ctx = LocalContext.current
    val graph = remember { AppGraph.get(ctx) }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(SecureStore.has(ctx, SecureStore.KEY_GEMINI)) }
    var waitingForReturn by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }

    fun tryKey(raw: String?, fromClipboard: Boolean) {
        val key = raw?.let { GEMINI_KEY.find(it)?.value }
        if (key == null) {
            if (!fromClipboard) { status = "No Gemini key on the clipboard. In AI Studio, tap Copy next to your key, then come back."; ok = false }
            return
        }
        status = "Found a key. Checking it with Google…"; ok = true
        scope.launch {
            val r = Providers.forProvider(Provider.GEMINI).listModels(key)
            r.onSuccess {
                SecureStore.put(ctx, SecureStore.KEY_GEMINI, key)
                if (fromClipboard) clearClipboard(ctx)
                graph.prefs.update { it.copy(provider = Provider.GEMINI) }
                saved = true; waitingForReturn = false
                status = "Ready. Your free Gemini key is saved on this phone only."; ok = true
                onReady()
            }.onFailure {
                status = "Google rejected that key: ${it.message}"; ok = false
            }
        }
    }

    // Coming back from the browser: read the clipboard once. Android only lets the focused app read
    // it, and focus arrives a moment after ON_RESUME, hence the short wait.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && waitingForReturn) scope.launch { delay(400); tryKey(readClipboard(ctx), fromClipboard = true) }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    GlassGroup(
        backdrop, "Free AI — Gemini",
        footer = "Free for anyone with a Google account. The key stays encrypted on this phone and is only sent to Google.",
    ) {
        Column(Modifier.padding(16.dp)) {
            if (saved) {
                Text("Gemini is set up", style = PrismTypography.titleMedium, color = PrismColors.Good)
                Text("Saved: ${SecureStore.mask(SecureStore.get(ctx, SecureStore.KEY_GEMINI))}", style = PrismTypography.bodySmall, color = PrismColors.TextSecondary)
            } else {
                Step("1", "Tap Get my free key and sign in with Google.")
                Step("2", "Tap Create API key, then Copy.")
                Step("3", "Come back here — Prism picks it up by itself.")
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassButton(if (saved) "Get a new key" else "Get my free key", filled = !saved) {
                    waitingForReturn = true
                    status = null
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { status = "No browser found. Open $AI_STUDIO_URL yourself."; ok = false }
                }
                Spacer(Modifier.width(8.dp))
                GlassButton("Paste", filled = false) { tryKey(readClipboard(ctx), fromClipboard = false) }
            }
            status?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = PrismTypography.bodySmall, color = if (ok) PrismColors.Good else PrismColors.Bad)
            }
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(n, style = PrismTypography.labelLarge, color = PrismColors.TextTertiary, modifier = Modifier.width(22.dp))
        Text(text, style = PrismTypography.bodyMedium, color = PrismColors.TextPrimary)
    }
}

private fun readClipboard(ctx: Context): String? = runCatching {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
}.getOrNull()

/** A key left on the clipboard can be pasted anywhere later; remove it once it's safely stored. */
private fun clearClipboard(ctx: Context) {
    runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip() }
}
