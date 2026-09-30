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
import com.meetdheeran.prism.core.IslandStyle
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
import com.meetdheeran.prism.aod.AodService
import com.meetdheeran.prism.agent.Agent
import com.meetdheeran.prism.agent.AgentAccessibilityService
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import com.meetdheeran.prism.ui.theme.Appearance
import com.meetdheeran.prism.ui.theme.Look
import com.meetdheeran.prism.ui.theme.NOTHING_ACCENTS
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.foundation.border

/** Start/stop the two overlay services. Both refuse politely without the overlay permission. */
object ServiceToggles {
    fun controlCenter(ctx: Context, on: Boolean) = toggle(ctx, ControlCenterService::class.java, on)
    fun island(ctx: Context, on: Boolean) = toggle(ctx, IslandService::class.java, on)
    fun aod(ctx: Context, on: Boolean) = toggle(ctx, AodService::class.java, on)
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

    var advanced by remember { mutableStateOf(false) }
    val agentConnected by AgentAccessibilityService.connected.collectAsState()
    val agentState by Agent.state.collectAsState()
    var agentTick by remember { mutableStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) agentTick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val agentOn = remember(agentConnected, agentTick) { AgentAccessibilityService.isEnabled(ctx) }
    ScreenScaffold("Settings", onBack = { nav.up() }) { backdrop ->
        GlassGroup(backdrop, "Look", footer = settings.look.note + ". Changes every screen, the assistant, the island and the always-on display.") {
            Box(Modifier.padding(16.dp)) {
                Segmented(Look.entries.map { it.label }, Look.entries.indexOf(settings.look)) { i -> update { it.copy(look = Look.entries[i]) } }
            }
            if (Appearance.nothing) {
                GlassDivider()
                SettingRow("Signal colour", subtitle = "The one colour the Nothing look uses")
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                    NOTHING_ACCENTS.forEach { c ->
                        val on = c == settings.nothingAccent
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                                .border(if (on) 3.dp else 1.dp, if (on) PrismColors.TextPrimary else PrismColors.TextTertiary, CircleShape)
                                .pressable { update { it.copy(nothingAccent = c) } },
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                }
                GlassDivider()
                SwitchRow("Dot grid background", checked = settings.nothingDotGrid) { on -> update { it.copy(nothingDotGrid = on) } }
                GlassDivider()
                SwitchRow("Dot-matrix titles", "Off uses the plain font for big titles too", settings.nothingDotTitles) { on -> update { it.copy(nothingDotTitles = on) } }
            }
            GlassDivider()
            SwitchRow("Edge lights", if (Appearance.nothing) "Glyph strips round the screen while the assistant listens" else "The coloured glow round the screen while the assistant listens", settings.edgeLights) { on -> update { it.copy(edgeLights = on) } }
            if (Appearance.nothing && settings.edgeLights) {
                GlassDivider()
                SliderRow("Glyph strength", "${(settings.glyphStrength * 100).toInt()}%", settings.glyphStrength, 0.3f..1.5f) { v -> update { it.copy(glyphStrength = v) } }
            }
        }

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
            // Gemini is the only AI a new user sees (free key). Groq and Claude live under Advanced.
            if (advanced || settings.provider != Provider.GEMINI) {
                Box(Modifier.padding(16.dp)) {
                    Segmented(Provider.entries.map { it.label }, Provider.entries.indexOf(settings.provider)) { i -> update { it.copy(provider = Provider.entries[i]) } }
                }
            } else {
                SettingRow("AI", subtitle = "Gemini — free with your own Google key", value = "Gemini")
            }
            GlassDivider()
            val provider = Providers.forSettings(settings)
            val model = Providers.modelFor(settings, provider)
            SettingRow("Model", subtitle = Providers.modelInfo(provider, model)?.note?.take(60), value = model.substringAfterLast('/'), chevron = true) { dialog = "model" }
            GlassDivider()
            SettingRow("API keys", icon = Icons.Rounded.Key, value = listOfNotNull(if (SecureStore.has(ctx, SecureStore.KEY_GEMINI)) "Gemini" else null, if (SecureStore.has(ctx, SecureStore.KEY_GROQ)) "Groq" else null, if (SecureStore.has(ctx, SecureStore.KEY_CLAUDE)) "Claude" else null).joinToString().ifEmpty { "None yet" }, chevron = true) { nav.navigate(Routes.KEYS) }
            GlassDivider()
            SettingRow("Web search", subtitle = when (settings.webSearch) { WebSearchMode.OFF -> "Never sends queries anywhere; opens the browser instead"; WebSearchMode.PROVIDER -> "Uses ${settings.provider.label}'s search (Gemini: Google Search grounding, paid tier for 3.x models; Groq: browser_search on gpt-oss; Claude: built-in web search, about $10 per 1,000 searches). Queries go to that provider's search partner." + if (settings.provider == Provider.GROQ && !Providers.modelFor(settings).startsWith("openai/gpt-oss")) " \u26A0 The selected Groq model cannot search; pick an openai/gpt-oss model." else "" }, value = if (settings.webSearch == WebSearchMode.OFF) "Off" else "On", chevron = true) { dialog = "search" }
            if (!Appearance.nothing) {
                GlassDivider()
                SettingRow("Assistant look", subtitle = settings.assistantStyle.label, chevron = true) { dialog = "style" }
            }
            GlassDivider()
            SettingRow("Answer length", subtitle = when (settings.answerLength) { "short" -> "Blunt: a few words"; "normal" -> "Short and friendly"; "detailed" -> "Fuller answers with detail"; else -> "Follows the look (Nothing = blunt)" })
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                val opts = listOf("auto", "short", "normal", "detailed")
                Segmented(listOf("Auto", "Short", "Normal", "Detailed"), opts.indexOf(settings.answerLength).coerceAtLeast(0)) { i -> update { it.copy(answerLength = opts[i]) } }
            }
            if (Appearance.nothing) {
                GlassDivider()
                SliderRow("Typing speed", "${settings.typewriterCps} chars/s", settings.typewriterCps.toFloat(), 30f..240f) { v -> update { it.copy(typewriterCps = v.toInt()) } }
            }
            GlassDivider()
            SettingRow("Advanced", subtitle = if (advanced) "Other AI providers shown above" else "Use Groq or Claude with your own paid key", value = if (advanced) "On" else "Off") { advanced = !advanced }
        }

        GlassGroup(
            backdrop, "Phone agent",
            footer = "Ask things like \"message Anushka on Zoom and say hi\" or \"email this to Sam\". The agent works the apps step by step and asks on the island before anything is sent. Tap the island to stop it.",
        ) {
            val ready = settings.agentConsent && agentOn
            SwitchRow(
                "Let Prism operate apps",
                when {
                    ready -> "On"
                    settings.agentConsent -> "One more step: switch on \"Prism phone agent\" in Accessibility"
                    else -> "Off"
                },
                ready,
            ) { on ->
                if (on) { if (!settings.agentConsent) dialog = "agent" else AgentAccessibilityService.openSettings(ctx) }
                else {
                    update { it.copy(agentConsent = false) }
                    // Apps can't switch an accessibility service off themselves; send the user to the switch.
                    if (agentOn) AgentAccessibilityService.openSettings(ctx)
                }
            }
            GlassDivider()
            SettingRow("Accessibility switch", subtitle = "Android's own on/off for the agent", value = if (agentOn) "On" else "Off", chevron = true) { AgentAccessibilityService.openSettings(ctx) }
            GlassDivider()
            SettingRow("Never touched", subtitle = "Banking, payment, wallet and password-manager apps, password fields and secure screens")
            if (agentState.log.isNotEmpty() || agentState.result != null) {
                GlassDivider()
                SettingRow("Last task", subtitle = agentState.goal.take(80), value = if (agentState.running) "Running" else if (agentState.success) "Done" else "Stopped", chevron = true) { dialog = "agentlog" }
            }
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
            if (!Appearance.nothing) {
                SettingRow("Island look", subtitle = settings.islandStyle.label, chevron = true) { dialog = "island" }
                GlassDivider()
            }
            SwitchRow("Assistant", "Tap or hold the island to talk; it shows listening and thinking", settings.islandShowAssistant, enabled = settings.islandEnabled) { on -> update { it.copy(islandShowAssistant = on) } }
            GlassDivider()
            SwitchRow("Split into two", "When two things run at once, one pops off into its own bubble (like iPhone)", settings.islandSplit, enabled = settings.islandEnabled) { on -> update { it.copy(islandSplit = on) } }
            GlassDivider()
            SliderRow("Animation speed", "%.1f×".format(settings.islandSpeed), settings.islandSpeed, 0.5f..2f) { v -> update { it.copy(islandSpeed = v) } }
            GlassDivider()
            SwitchRow("Notification peek", "New notifications slide out of the island for a moment", settings.islandShowNotifications, enabled = settings.islandEnabled) { on -> update { it.copy(islandShowNotifications = on) } }
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

        GlassGroup(backdrop, "Always-on display", footer = "Shows whenever the screen turns off: clock, date, notifications, music and battery. Android can't draw on a truly off screen, so this keeps the screen on at very low brightness and shifts pixels every minute to protect the OLED.") {
            SwitchRow("Enable always-on display", checked = settings.aodEnabled) { on ->
                // Android 12 only lets an app open a screen from the background with "Display over other apps".
                if (on && !OverlayHost.canDrawOverlays(ctx)) { nav.navigate(Routes.PERMISSIONS); return@SwitchRow }
                update { it.copy(aodEnabled = on) }
                ServiceToggles.aod(ctx, on)
            }
            GlassDivider()
            SettingRow("Clock style")
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                val opts = listOf("auto", "dot", "thin", "bold")
                Segmented(listOf("Auto", "Dot", "Thin", "Bold"), opts.indexOf(settings.aodClockStyle).coerceAtLeast(0)) { i -> update { it.copy(aodClockStyle = opts[i]) } }
            }
            GlassDivider()
            SliderRow("Clock size", "${(settings.aodClockScale * 100).toInt()}%", settings.aodClockScale, 0.6f..1.4f) { v -> update { it.copy(aodClockScale = v) } }
            GlassDivider()
            SliderRow("Brightness", "${settings.aodBrightness}%", settings.aodBrightness.toFloat(), 1f..30f) { v -> update { it.copy(aodBrightness = v.toInt()) } }
            GlassDivider()
            SwitchRow("Date", checked = settings.aodShowDate) { on -> update { it.copy(aodShowDate = on) } }
            GlassDivider()
            SwitchRow("Battery", checked = settings.aodShowBattery) { on -> update { it.copy(aodShowBattery = on) } }
            GlassDivider()
            SwitchRow("Notification icons", checked = settings.aodShowNotifications) { on -> update { it.copy(aodShowNotifications = on) } }
            GlassDivider()
            SwitchRow("Music", checked = settings.aodShowMusic) { on -> update { it.copy(aodShowMusic = on) } }
            GlassDivider()
            SwitchRow("Off in pocket or face down", checked = settings.aodPocketOff, enabled = settings.aodEnabled) { on -> update { it.copy(aodPocketOff = on) } }
            GlassDivider()
            SwitchRow("Off at night", "Between the hours below", settings.aodNightOff, enabled = settings.aodEnabled) { on -> update { it.copy(aodNightOff = on) } }
            if (settings.aodNightOff) {
                GlassDivider()
                HourRow("Night starts", settings.aodNightStart) { v -> update { it.copy(aodNightStart = v) } }
                GlassDivider()
                HourRow("Night ends", settings.aodNightEnd) { v -> update { it.copy(aodNightEnd = v) } }
            }
            GlassDivider()
            SwitchRow("Off on low battery", "Below ${settings.aodLowBatteryPercent}%", settings.aodLowBatteryOff, enabled = settings.aodEnabled) { on -> update { it.copy(aodLowBatteryOff = on) } }
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

        if (!Appearance.nothing) GlassGroup(backdrop, "Appearance") {
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

        if (!Appearance.nothing) GlassGroup(backdrop, "Glass", footer = "OpenGL refraction bends the real screen behind the control center tiles. Turn it off if tiles look wrong on this phone.") {
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
        "agent" -> AgentConsentDialog(
            onAccept = {
                update { it.copy(agentConsent = true) }
                dialog = null
                if (!agentOn) AgentAccessibilityService.openSettings(ctx)
            },
            onDismiss = { dialog = null },
        )
        "agentlog" -> ChoiceDialog(
            "Last task",
            (listOf(agentState.goal to "Goal") + agentState.log.mapIndexed { i, step -> "${i + 1}. $step" to null } +
                listOfNotNull(agentState.result?.let { it to (if (agentState.success) "Result" else "Ended") })),
            -1, onPick = { },
        ) { dialog = null }
        "island" -> ChoiceDialog("Island look", IslandStyle.entries.map { it.label to it.note }, IslandStyle.entries.indexOf(settings.islandStyle), onPick = { i -> update { it.copy(islandStyle = IslandStyle.entries[i]) }; dialog = null }) { dialog = null }
        "style" -> ChoiceDialog("Assistant look", AssistantStyle.entries.map { it.label to it.note }, AssistantStyle.entries.indexOf(settings.assistantStyle), onPick = { i -> update { it.copy(assistantStyle = AssistantStyle.entries[i]) }; dialog = null }) { dialog = null }
        "model" -> {
            val provider = Providers.forSettings(settings)
            val models = provider.knownModels()
            val current = Providers.modelFor(settings, provider)
            ChoiceDialog("${settings.provider.label} model", models.map { it.label to it.note }, models.indexOfFirst { it.id == current }, onPick = { i ->
                val id = models[i].id
                update { when (it.provider) { Provider.GEMINI -> it.copy(geminiModel = id); Provider.GROQ -> it.copy(groqModel = id); Provider.CLAUDE -> it.copy(claudeModel = id) } }
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

/** Picks a whole hour of the day, stored as minutes after midnight. */
@Composable
private fun HourRow(title: String, minutes: Int, onChange: (Int) -> Unit) {
    val accent = LocalAccent.current
    SettingRow(title, value = "%02d:00".format(minutes / 60), trailing = {
        Slider(
            (minutes / 60).toFloat(), { onChange(it.toInt() * 60) }, valueRange = 0f..23f, steps = 22,
            modifier = Modifier.width(150.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = PrismColors.TextTertiary.copy(alpha = 0.4f)),
        )
    })
}

/** A labelled slider with its current value shown on the right. */
@Composable
private fun SliderRow(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val accent = LocalAccent.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = PrismTypography.bodyLarge, color = PrismColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(value, style = PrismTypography.bodyMedium, color = PrismColors.TextSecondary)
        }
        Slider(
            current.coerceIn(range), onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = if (Appearance.nothing) PrismColors.TextPrimary else Color.White, activeTrackColor = accent, inactiveTrackColor = PrismColors.TextTertiary.copy(alpha = 0.4f)),
        )
    }
}


/**
 * Shown once before the agent can be switched on. Says plainly what it can do, what it will never do,
 * and where the screen content goes — including what free Gemini keys mean for privacy.
 */
@Composable
private fun AgentConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PrismColors.Slate,
        titleContentColor = PrismColors.TextPrimary,
        textContentColor = PrismColors.TextSecondary,
        title = { Text("Let Prism operate your apps?", style = PrismTypography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("When you ask, the agent reads the screen of the app in front and taps or types for you, one step at a time.", style = PrismTypography.bodyMedium, color = PrismColors.TextPrimary)
                Spacer(Modifier.height(10.dp))
                Text("• Before anything is sent, posted, deleted or paid, it asks you on the island.", style = PrismTypography.bodyMedium)
                Text("• It never opens banking, payment or password apps, and never reads password fields.", style = PrismTypography.bodyMedium)
                Text("• Text on the screen is never treated as an instruction — only what you asked.", style = PrismTypography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Text("Privacy", style = PrismTypography.titleSmall, color = PrismColors.TextPrimary)
                Text(
                    "To decide each step, what's on the screen (messages and emails included) is sent to the AI provider you use in Prism — Gemini by default. " +
                        "With a free Gemini key, Google's terms let it use that content to improve its products, and people may review it. Paid keys aren't used that way.",
                    style = PrismTypography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text("Next, Android opens Accessibility: switch on \"Prism phone agent\".", style = PrismTypography.bodySmall, color = PrismColors.TextTertiary)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onAccept) { Text("Turn on", color = LocalAccent.current) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Not now", color = PrismColors.TextSecondary) } },
    )
}
