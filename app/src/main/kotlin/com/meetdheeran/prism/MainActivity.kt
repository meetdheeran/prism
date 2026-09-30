package com.meetdheeran.prism

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import com.meetdheeran.prism.ui.theme.Appearance
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import com.meetdheeran.prism.ai.Attachment
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.core.Settings
import com.meetdheeran.prism.ui.motion.LocalTilt
import com.meetdheeran.prism.ui.motion.rememberDeviceTilt
import com.meetdheeran.prism.ui.screens.LaunchRequest
import com.meetdheeran.prism.ui.screens.PrismNav
import com.meetdheeran.prism.ui.screens.Routes
import com.meetdheeran.prism.ui.theme.PrismTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val graph by lazy { AppGraph.get(this) }
    private var launch by mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            val settings by graph.prefs.settings.collectAsState(initial = Settings())
            val tilt by rememberDeviceTilt()
            // Glass is always dark; the Nothing look follows the phone, so the status-bar icons must too.
            val lightBars = Appearance.nothing && !Appearance.dark
            LaunchedEffect(lightBars) {
                val t = android.graphics.Color.TRANSPARENT
                val style = if (lightBars) SystemBarStyle.light(t, t) else SystemBarStyle.dark(t)
                enableEdgeToEdge(style, style)
            }
            PrismTheme(accent = Color(settings.accentArgb)) {
                // The island pill sits over the camera; when it's always shown, push screen content
                // below it so it never covers a top bar. Pill = cutout height + user tweak, at the offset.
                val density = androidx.compose.ui.platform.LocalDensity.current
                val cutoutPx = WindowInsets.displayCutout.getTop(density)
                val statusPx = WindowInsets.statusBars.getTop(density)
                val islandInset = if (settings.islandEnabled && settings.islandShowAssistant) {
                    with(density) {
                        val bottom = cutoutPx + (settings.islandExtraHeightDp + settings.islandOffsetDp).dp.roundToPx()
                        (bottom - statusPx).coerceAtLeast(0).toDp() + 6.dp
                    }
                } else 0.dp
                CompositionLocalProvider(LocalTilt provides tilt, com.meetdheeran.prism.ui.screens.LocalIslandInset provides islandInset) {
                    PrismNav(
                        startRoute = if (settings.onboardingDone) Routes.HOME else Routes.ONBOARDING,
                        launch = launch,
                        onLaunchConsumed = { launch = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** Deep links from the island, share sheet, text-selection tools and the assistant tile. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val conv = intent.getLongExtra(EXTRA_CONVERSATION, -1L).takeIf { it > 0 }
        val prompt = intent.getStringExtra(EXTRA_PROMPT)
        val paths = intent.getStringArrayExtra(EXTRA_ATTACH_PATHS).orEmpty()
        val mimes = intent.getStringArrayExtra(EXTRA_ATTACH_MIMES).orEmpty()
        val attachments = paths.mapIndexedNotNull { i, p ->
            val f = File(p)
            if (!f.exists()) null else Attachment(mimes.getOrNull(i) ?: "application/octet-stream", f.readBytes(), f.name).also { f.delete() }
        }
        val open = intent.action == ACTION_OPEN_ASSISTANT
        if (conv == null && prompt == null && attachments.isEmpty() && !open) return
        launch = LaunchRequest(conv, prompt, attachments, open, System.currentTimeMillis())
    }

    companion object {
        const val ACTION_OPEN_ASSISTANT = "com.meetdheeran.prism.OPEN_ASSISTANT"
        const val EXTRA_CONVERSATION = "conversationId"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_ATTACH_PATHS = "attachPaths"
        const val EXTRA_ATTACH_MIMES = "attachMimes"
    }
}
