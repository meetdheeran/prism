package com.meetdheeran.prism.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.meetdheeran.prism.BuildConfig
import com.meetdheeran.prism.ai.Providers
import com.meetdheeran.prism.assistant.SpeechOutput
import com.meetdheeran.prism.control.ControlCenterService
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.core.AssistantStyle
import com.meetdheeran.prism.core.GestureEdge
import com.meetdheeran.prism.core.Provider
import com.meetdheeran.prism.core.SecureStore
import com.meetdheeran.prism.core.Settings
import com.meetdheeran.prism.core.VoiceInputMode
import com.meetdheeran.prism.core.WebSearchMode
import com.meetdheeran.prism.island.IslandService
import com.meetdheeran.prism.overlay.BackgroundNotice
import com.meetdheeran.prism.overlay.OverlayHost
import com.meetdheeran.prism.shizuku.ShizukuBridge
import com.meetdheeran.prism.shizuku.ShizukuState
import com.meetdheeran.prism.ui.motion.pressable
import com.meetdheeran.prism.ui.theme.LocalAccent
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography
import kotlinx.coroutines.launch

/** Start/stop the two overlay services. Both refuse politely without the overlay permission. */
object ServiceToggles {
    fun controlCenter(ctx: Context, on: Boolean) = toggle(ctx, ControlCenterService::class.java, on)
    fun island(ctx: Context, on: Boolean) = toggle(ctx, IslandService::class.java, on)
    private fun toggle(ctx: Context, cls: Class<*>, on: Boolean) {
        val i = Intent(ctx, cls).setAction(if (on) "start" else "stop")
        if (on) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
    }
}

fun openDefaultAssistantPicker(ctx: Context) {
    val tries = listOf(Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS), Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(AndroidSettings.ACTION_SETTINGS))
    for (i in tries) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(i); true }.getOrDefault(false)) return
    }
}

private val ACCENTS = listOf(0xFF0A84FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF375F, 0xFFFF9F0A, 0xFF30D158, 0xFF64D2FF, 0xFFFFFFFF).map { it.toInt() }

@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val graph = remember { AppGraph.get(ctx) }
    val scope = rememberCoroutineScope()
    val settings by graph.prefs.settings.collectAsState(initial = Settings())
    val shizuku by ShizukuBridge.state.collectAsState()
    val accent = LocalAccent.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    fun update(block: (Settings) -> Settings) = scope.launch { graph.prefs.update(block) }

    ScreenScaffold("Settings", onBack = { nav.up() }) { backdrop ->
        GlassGroup(backdrop, "Assistant") {
            SettingRow("Name", subtitle = "How the assistant refers to itself", trailing = {
                var name by remember(settings.assistantName) { mutableStateOf(settings.assistantName) }
                BasicTextField(
                    name, { name = it.take(20); if (it.isNotBlank()) update { s -> s.copy(assistantName = it.trim().take(20)) } },
                    textStyle = PrismTypography.bodyLarge.copy(color = accent), singleLine = true, cursorBrush = SolidColor(accent),
                    modifier = Modifier.width(120.dp),
                )
            })
            GlassDivider()
            Box(Modifier.padding(16.dp)) {
                Segmented(Provider.entries.map { it.label }, Provider.entries.indexOf(settings.provider)) { i -> update { it.copy(provider = Provider.entries[i]) } }
            }
            GlassDivider()
            val provider = Providers.forSettings(settings)
            val model = Providers.modelFor(settings, provider)
            SettingRow("Model", subtitle = Providers.modelInfo(provider, model)?.note?.take(60), value = model.substringAfterLast('/'), chevron = true) { dialog = "model" }
            GlassDivider()
            SettingRow("API keys", icon = Icons.Rounded.Key, value = listOfNotNull(if (SecureStore.has(ctx, SecureStore.KEY_GEMINI)) "Gemini" else null, if (SecureStore.has(ctx, SecureStore.KEY_GROQ)) "Groq" else null).joinToString().ifEmpty { "None yet" }, chevron = true) { nav.navigate(Routes.KEYS) }
            GlassDivider()
            SettingRow("Web search", subtitle = when (settings.webSearch) { WebSearchMode.OFF -> "Never sends queries anywhere; opens the browser instead"; WebSearchMode.PROVIDER -> "Uses ${settings.provider.label}'s search (Gemini: Google Search grounding, paid tier for 3.x models; Groq: browser_search on gpt-oss). Queries go to that provider's search partner." }, value = if (settings.webSearch == WebSearchMode.OFF) "Off" else "On", chevron = true) { dialog = "search" }
            GlassDivider()
            SettingRow("Assistant look", subtitle = settings.assistantStyle.label, chevron = true) { dialog = "style" }
        }

        GlassGroup(backdrop, "Voice") {
            SwitchRow("Speak replies", "Read answers aloud with the phone's own voice", settings.speakReplies) { on -> update { it.copy(speakReplies = on) } }
            GlassDivider()
            SwitchRow("Conversation mode", "After answering, keep listening until you say stop, thanks or that's all", settings.conversationMode) { on -> update { it.copy(conversationMode = on) } }
            GlassDivider()
            SettingRow("Voice input", subtitle = settings.voiceInput.label, chevron = true) { dialog = "voice" }
            GlassDivider()
            SettingRow("Voice & speed", subtitle = "Pick a different voice in Android's text-to-speech settings", chevron = true) { SpeechOutput.openTtsSettings(ctx) }
        }

        GlassGroup(backdrop, "Control center", footer = "A glass panel that slides in from the edge over any app. Needs 'Display over other apps'.") {
            SwitchRow("Enable control center", checked = settings.controlCenterEnabled) { on ->
                if (on && !OverlayHost.canDrawOverlays(ctx)) { nav.navigate(Routes.PERMISSIONS); return@SwitchRow }
                update { it.copy(controlCenterEnabled = on) }
                ServiceToggles.controlCenter(ctx, on)
            }
            GlassDivider()
            Box(Modifier.padding(16.dp)) {
                Segmented(GestureEdge.entries.map { it.label.removeSuffix(" edge") }, GestureEdge.entries.indexOf(settings.gestureEdge)) { i ->
                    update { it.copy(gestureEdge = GestureEdge.entries[i]) }
                    if (settings.controlCenterEnabled) ServiceToggles.controlCenter(ctx, true)
                }
            }
            GlassDivider()
            SettingRow("Handle position", subtitle = "Where along the edge the swipe handle sits", trailing = {
                Slider(
                    settings.gestureHandleFraction, { v -> update { it.copy(gestureHandleFraction = v) } },
                    onValueChangeFinished = { if (settings.controlCenterEnabled) ServiceToggles.controlCenter(ctx, true) },
                    modifier = Modifier.width(150.dp),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                )
            })
            GlassDivider()
            SettingRow("Edit tiles", subtitle = "Choose and reorder controls", chevron = true) { nav.navigate(Routes.TILES) }
        }

        GlassGroup(backdrop, "Dynamic Island", footer = "A pill around the camera notch for music and live activities. Needs 'Display over other apps' and, for music, notification access.") {
            SwitchRow("Enable island", checked = settings.islandEnabled) { on ->
                if (on && !OverlayHost.canDrawOverlays(ctx)) { nav.navigate(Routes.PERMISSIONS); return@SwitchRow }
                update { it.copy(islandEnabled = on) }
                ServiceToggles.island(ctx, on)
            }
            GlassDivider()
            SwitchRow("Music", checked = settings.islandShowMedia, enabled = settings.islandEnabled) { on -> update { it.copy(islandShowMedia = on) } }
            GlassDivider()
            SwitchRow("Charging", checked = settings.islandShowCharging, enabled = settings.islandEnabled) { on -> update { it.copy(islandShowCharging = on) } }
            GlassDivider()
            SwitchRow("Calls", checked = settings.islandShowCalls, enabled = settings.islandEnabled) { on -> update { it.copy(islandShowCalls = on) } }
            GlassDivider()
            SwitchRow("Timers & navigation", checked = settings.islandShowTimers, enabled = settings.islandEnabled) { on -> update { it.copy(islandShowTimers = on) } }
            GlassDivider()
            TuneRow("Move down", settings.islandOffsetDp, -12, 24) { v -> update { it.copy(islandOffsetDp = v) } }
            GlassDivider()
            TuneRow("Wider", settings.islandExtraWidthDp, -20, 60) { v -> update { it.copy(islandExtraWidthDp = v) } }
            GlassDivider()
            TuneRow("Taller", settings.islandExtraHeightDp, -10, 24) { v -> update { it.copy(islandExtraHeightDp = v) } }
        }

        GlassGroup(backdrop, "Background notification", footer = "Android 12 requires one notification while the island or control center runs. Turn its channel off and it disappears; Prism keeps working.") {
            SettingRow("Hide the 'Prism is running' notification", chevron = true) { BackgroundNotice.openChannelSettings(ctx) }
        }

        GlassGroup(backdrop, "Phone") {
            SettingRow("Permissions", icon = Icons.Rounded.Security, subtitle = "Notification access, overlay, microphone, Shizuku…", chevron = true) { nav.navigate(Routes.PERMISSIONS) }
            GlassDivider()
            SettingRow("Memory", icon = Icons.Rounded.Memory, subtitle = "What the assistant remembers about you", chevron = true) { nav.navigate(Routes.MEMORY) }
            GlassDivider()
            SettingRow("Reminders", icon = Icons.Rounded.Alarm, subtitle = "Reminders Prism fires as notifications", chevron = true) { nav.navigate(Routes.REMINDERS) }
            GlassDivider()
            SettingRow("Default assistant", subtitle = "Set Prism as the digital assistant app so the assist gesture opens it", chevron = true) { openDefaultAssistantPicker(ctx) }
            GlassDivider()
            SettingRow(
                "Shizuku", subtitle = shizuku.label,
                value = when (shizuku) { ShizukuState.READY -> "Ready"; ShizukuState.NO_PERMISSION -> "Allow"; ShizukuState.NOT_RUNNING -> "Start"; ShizukuState.NOT_INSTALLED -> "Missing" },
                chevron = true,
            ) { when (shizuku) { ShizukuState.NO_PERMISSION -> ShizukuBridge.requestPermission(); else -> ShizukuBridge.openShizukuApp(ctx) } }
        }

        GlassGroup(backdrop, "Appearance") {
            Row(Modifier.padding(16.dp)) {
                ACCENTS.forEach { c ->
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                            .then(if (c == settings.accentArgb) Modifier.padding(0.dp).background(Color.White.copy(alpha = 0.35f), CircleShape) else Modifier)
                            .pressable { update { it.copy(accentArgb = c) } },
                    )
                    Spacer(Modifier.width(10.dp))
                }
            }
        }

        GlassGroup(backdrop, "Glass", footer = "OpenGL refraction bends the real screen behind the control center tiles. Turn it off if tiles look wrong on this phone.") {
            SwitchRow("Refraction glass (OpenGL)", checked = settings.glRefraction) { on -> update { it.copy(glRefraction = on) } }
        }

        GlassGroup(backdrop, "Danger zone") {
            SettingRow("Delete all conversations", danger = true) { confirm = "conversations" }
            GlassDivider()
            SettingRow("Delete all memory", danger = true) { confirm = "memory" }
            GlassDivider()
            SettingRow("Forget API keys", danger = true) { confirm = "keys" }
        }
        Text("Prism ${BuildConfig.VERSION_NAME} · keys and history never leave this phone except to the provider you chose.", style = PrismTypography.bodySmall, color = PrismColors.TextTertiary, modifier = Modifier.padding(horizontal = 16.dp))
    }

    when (dialog) {
        "style" -> ChoiceDialog("Assistant look", AssistantStyle.entries.map { it.label to it.note }, AssistantStyle.entries.indexOf(settings.assistantStyle), onPick = { i -> update { it.copy(assistantStyle = AssistantStyle.entries[i]) }; dialog = null }) { dialog = null }
        "model" -> {
            val provider = Providers.forSettings(settings)
            val models = provider.knownModels()
            val current = Providers.modelFor(settings, provider)
            ChoiceDialog("${settings.provider.label} model", models.map { it.label to it.note }, models.indexOfFirst { it.id == current }, onPick = { i ->
                val id = models[i].id
                update { if (it.provider == Provider.GEMINI) it.copy(geminiModel = id) else it.copy(groqModel = id) }
                dialog = null
            }) { dialog = null }
        }
        "voice" -> ChoiceDialog(
            "Voice input",
            VoiceInputMode.entries.map { it.label to when (it) { VoiceInputMode.SYSTEM -> "Free, on-device (Google speech services)"; VoiceInputMode.GROQ_WHISPER -> "Audio is sent to Groq; whisper-large-v3-turbo"; VoiceInputMode.GEMINI_AUDIO -> "Audio is sent to Gemini" } },
            VoiceInputMode.entries.indexOf(settings.voiceInput), onPick = { i -> update { it.copy(voiceInput = VoiceInputMode.entries[i]) }; dialog = null },
        ) { dialog = null }
        "search" -> ChoiceDialog(
            "Web search",
            listOf("Off" to "Search requests open your browser instead", "Provider search" to "Gemini: Google Search grounding (free only on 2.5 models; paid on 3.x). Groq: browser_search on gpt-oss models. Your query is sent to that provider's search partner."),
            WebSearchMode.entries.indexOf(settings.webSearch), onPick = { i -> update { it.copy(webSearch = WebSearchMode.entries[i]) }; dialog = null },
        ) { dialog = null }
    }
    when (confirm) {
        "conversations" -> ConfirmDialog("Delete all conversations?", "Every conversation on this phone will be removed.", onConfirm = { scope.launch { graph.history.deleteAll() } }) { confirm = null }
        "memory" -> ConfirmDialog("Delete all memory?", "The assistant will forget everything it has saved about you.", onConfirm = { scope.launch { graph.memory.deleteAll() } }) { confirm = null }
        "keys" -> ConfirmDialog("Forget API keys?", "Both keys will be erased from the secure store.", "Forget", onConfirm = { SecureStore.clearAll(ctx) }) { confirm = null }
    }
}


/** A labelled integer slider (dp) for the island fine-tune. */
@Composable
private fun TuneRow(title: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val accent = LocalAccent.current
    SettingRow(title, value = "$value dp", trailing = {
        Slider(
            value.toFloat(), { onChange(it.toInt()) }, valueRange = min.toFloat()..max.toFloat(),
            modifier = Modifier.width(150.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
        )
    })
}
