package com.meetdheeran.prism.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.meetdheeran.prism.ui.theme.Look

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "prism_prefs")

/** Which cloud provider answers. Both are configured; the user picks one at a time. */
enum class Provider(val id: String, val label: String) {
    GEMINI("gemini", "Gemini"),
    GROQ("groq", "Groq"),
    CLAUDE("claude", "Claude");

    companion object {
        fun from(id: String?): Provider = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

/** Where the control-center swipe handle lives. Not chosen by the user yet; default right edge. */
enum class GestureEdge(val id: String, val label: String) {
    RIGHT("right", "Right edge"),
    LEFT("left", "Left edge"),
    BOTTOM("bottom", "Bottom edge");

    companion object {
        fun from(id: String?): GestureEdge = entries.firstOrNull { it.id == id } ?: RIGHT
    }
}

/** How spoken input is transcribed. */
enum class VoiceInputMode(val id: String, val label: String) {
    SYSTEM("system", "On-device (Android speech)"),
    GROQ_WHISPER("groq_whisper", "Groq Whisper (cloud)"),
    GEMINI_AUDIO("gemini_audio", "Gemini audio (cloud)");

    companion object {
        fun from(id: String?): VoiceInputMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** Web search policy. OFF never sends a query anywhere except the chosen chat provider. */
enum class WebSearchMode(val id: String, val label: String) {
    OFF("off", "Off (open in browser instead)"),
    PROVIDER("provider", "Use the current provider's built-in search");

    companion object {
        fun from(id: String?): WebSearchMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** How the assistant overlay looks. */
enum class AssistantStyle(val id: String, val label: String, val note: String) {
    GLASS("glass", "Glass sheet", "Dimmed screen, glass pill and answer card, edge glow"),
    EDGE("edge", "Edge glow only", "Just the coloured ring hugging the screen; your content stays visible"),
    LENS("lens", "Lens + glow (iOS 27)", "A glass bubble over the camera magnifies your screen with a rainbow streak, plus the edge glow");

    companion object {
        fun from(id: String?): AssistantStyle = entries.firstOrNull { it.id == id } ?: GLASS
    }
}

/** How the island is drawn. */
enum class IslandStyle(val id: String, val label: String, val note: String) {
    PILL("pill", "Black pill", "Merges with the notch, like a Dynamic Island"),
    LENS("lens", "Glass lens (iOS 27)", "A clear bubble that magnifies what is behind it with a rainbow streak; Shizuku gives it the real backdrop");

    companion object {
        fun from(id: String?): IslandStyle = entries.firstOrNull { it.id == id } ?: PILL
    }
}

data class Settings(
    val provider: Provider = Provider.GEMINI,
    val geminiModel: String = "",
    val groqModel: String = "",
    val claudeModel: String = "",
    val gestureEdge: GestureEdge = GestureEdge.RIGHT,
    /** Where along the edge the handle sits (0 = top, 1 = bottom). */
    val gestureHandleFraction: Float = 0.55f,
    val accentArgb: Int = 0xFF0A84FF.toInt(),
    val voiceInput: VoiceInputMode = VoiceInputMode.SYSTEM,
    val speakReplies: Boolean = true,
    /** Keep listening after each answer until the user says stop. */
    val conversationMode: Boolean = true,
    val assistantStyle: AssistantStyle = AssistantStyle.GLASS,
    /** OpenGL lens refraction in the control center. */
    val glRefraction: Boolean = true,
    val webSearch: WebSearchMode = WebSearchMode.OFF,
    val memoryEnabled: Boolean = true,
    val controlCenterEnabled: Boolean = false,
    val islandEnabled: Boolean = false,
    val islandShowMedia: Boolean = true,
    val islandShowCharging: Boolean = true,
    val islandShowCalls: Boolean = true,
    val islandShowTimers: Boolean = true,
    /** Fine-tune in dp so the pill hugs this phone's notch exactly. */
    val islandStyle: IslandStyle = IslandStyle.PILL,
    val islandOffsetDp: Int = 2,
    val islandExtraWidthDp: Int = 24,
    val islandExtraHeightDp: Int = 10,
    val onboardingDone: Boolean = false,
    val assistantName: String = "Prism",
    val userName: String = "",
    /** Comma-separated tile ids; empty = default order. */
    val tilesOrder: String = "",
    /** Glass or Nothing. Changes every surface, and how the assistant talks. */
    val look: Look = Look.GLASS,
    val islandShowAssistant: Boolean = true,
    /** Hold the island and ask: the answer shows (and is spoken) right there; off, holding opens the assistant. */
    val islandAskInPlace: Boolean = true,
    val islandShowNotifications: Boolean = true,
    /** Always-on display shown whenever the screen turns off. */
    val aodEnabled: Boolean = false,
    val aodPocketOff: Boolean = true,
    val aodNightOff: Boolean = true,
    /** Minutes after midnight. */
    val aodNightStart: Int = 0,
    val aodNightEnd: Int = 7 * 60,
    val aodLowBatteryOff: Boolean = true,
    val aodLowBatteryPercent: Int = 15,
    // --- Customisation (3.0 fix) ---
    val islandSplit: Boolean = true,
    val islandSpeed: Float = 1f,
    val aodClockStyle: String = "auto",
    val aodClockScale: Float = 1f,
    val aodBrightness: Int = 2,
    val aodShowDate: Boolean = true,
    val aodShowBattery: Boolean = true,
    val aodShowNotifications: Boolean = true,
    val aodShowMusic: Boolean = true,
    val nothingAccent: Int = 0xFFD71921.toInt(),
    val nothingDotGrid: Boolean = true,
    val nothingDotTitles: Boolean = true,
    val glyphStrength: Float = 1f,
    val answerLength: String = "auto",
    val typewriterCps: Int = 90,
    val edgeLights: Boolean = true,
    /** The user has read the phone agent's privacy notice and switched it on. */
    val agentConsent: Boolean = false,
)

class Prefs(private val ctx: Context) {
    private object K {
        val provider = stringPreferencesKey("provider")
        val geminiModel = stringPreferencesKey("gemini_model")
        val groqModel = stringPreferencesKey("groq_model")
        val claudeModel = stringPreferencesKey("claude_model")
        val gestureEdge = stringPreferencesKey("gesture_edge")
        val gestureHandleFraction = floatPreferencesKey("gesture_handle_fraction")
        val accent = intPreferencesKey("accent_argb")
        val voiceInput = stringPreferencesKey("voice_input")
        val speakReplies = booleanPreferencesKey("speak_replies")
        val conversationMode = booleanPreferencesKey("conversation_mode")
        val assistantStyle = stringPreferencesKey("assistant_style")
        val glRefraction = booleanPreferencesKey("gl_refraction")
        val webSearch = stringPreferencesKey("web_search")
        val memoryEnabled = booleanPreferencesKey("memory_enabled")
        val controlCenterEnabled = booleanPreferencesKey("control_center_enabled")
        val islandEnabled = booleanPreferencesKey("island_enabled")
        val islandShowMedia = booleanPreferencesKey("island_media")
        val islandShowCharging = booleanPreferencesKey("island_charging")
        val islandShowCalls = booleanPreferencesKey("island_calls")
        val islandShowTimers = booleanPreferencesKey("island_timers")
        val islandStyle = stringPreferencesKey("island_style")
        val islandOffset = intPreferencesKey("island_offset_dp")
        val islandExtraWidth = intPreferencesKey("island_extra_w_dp")
        val islandExtraHeight = intPreferencesKey("island_extra_h_dp")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val assistantName = stringPreferencesKey("assistant_name")
        val userName = stringPreferencesKey("user_name")
        val tilesOrder = stringPreferencesKey("tiles_order")
        val look = stringPreferencesKey("look")
        val islandShowAssistant = booleanPreferencesKey("island_assistant")
        val islandAskInPlace = booleanPreferencesKey("island_ask_in_place")
        val islandShowNotifications = booleanPreferencesKey("island_notifications")
        val aodEnabled = booleanPreferencesKey("aod_enabled")
        val aodPocketOff = booleanPreferencesKey("aod_pocket_off")
        val aodNightOff = booleanPreferencesKey("aod_night_off")
        val aodNightStart = intPreferencesKey("aod_night_start")
        val aodNightEnd = intPreferencesKey("aod_night_end")
        val aodLowBatteryOff = booleanPreferencesKey("aod_low_battery_off")
        val aodLowBatteryPercent = intPreferencesKey("aod_low_battery_percent")
        val islandSplit = booleanPreferencesKey("island_split")
        val islandSpeed = floatPreferencesKey("island_speed")
        val aodClockStyle = stringPreferencesKey("aod_clock_style")
        val aodClockScale = floatPreferencesKey("aod_clock_scale")
        val aodBrightness = intPreferencesKey("aod_brightness")
        val aodShowDate = booleanPreferencesKey("aod_show_date")
        val aodShowBattery = booleanPreferencesKey("aod_show_battery")
        val aodShowNotifications = booleanPreferencesKey("aod_show_notifications")
        val aodShowMusic = booleanPreferencesKey("aod_show_music")
        val nothingAccent = intPreferencesKey("nothing_accent")
        val nothingDotGrid = booleanPreferencesKey("nothing_dot_grid")
        val nothingDotTitles = booleanPreferencesKey("nothing_dot_titles")
        val glyphStrength = floatPreferencesKey("glyph_strength")
        val answerLength = stringPreferencesKey("answer_length")
        val typewriterCps = intPreferencesKey("typewriter_cps")
        val edgeLights = booleanPreferencesKey("edge_lights")
        val agentConsent = booleanPreferencesKey("agent_consent")
    }

    private fun Preferences.toSettings() = Settings(
        provider = Provider.from(this[K.provider]),
        geminiModel = this[K.geminiModel] ?: "",
        groqModel = this[K.groqModel] ?: "",
        claudeModel = this[K.claudeModel] ?: "",
        gestureEdge = GestureEdge.from(this[K.gestureEdge]),
        gestureHandleFraction = this[K.gestureHandleFraction] ?: 0.55f,
        accentArgb = this[K.accent] ?: 0xFF0A84FF.toInt(),
        voiceInput = VoiceInputMode.from(this[K.voiceInput]),
        speakReplies = this[K.speakReplies] ?: true,
        conversationMode = this[K.conversationMode] ?: true,
        assistantStyle = AssistantStyle.from(this[K.assistantStyle]),
        glRefraction = this[K.glRefraction] ?: true,
        webSearch = WebSearchMode.from(this[K.webSearch]),
        memoryEnabled = this[K.memoryEnabled] ?: true,
        controlCenterEnabled = this[K.controlCenterEnabled] ?: false,
        islandEnabled = this[K.islandEnabled] ?: false,
        islandShowMedia = this[K.islandShowMedia] ?: true,
        islandShowCharging = this[K.islandShowCharging] ?: true,
        islandShowCalls = this[K.islandShowCalls] ?: true,
        islandShowTimers = this[K.islandShowTimers] ?: true,
        islandStyle = IslandStyle.from(this[K.islandStyle]),
        islandOffsetDp = this[K.islandOffset] ?: 2,
        islandExtraWidthDp = this[K.islandExtraWidth] ?: 24,
        islandExtraHeightDp = this[K.islandExtraHeight] ?: 10,
        onboardingDone = this[K.onboardingDone] ?: false,
        assistantName = this[K.assistantName] ?: "Prism",
        userName = this[K.userName] ?: "",
        tilesOrder = this[K.tilesOrder] ?: "",
        look = Look.from(this[K.look]),
        islandShowAssistant = this[K.islandShowAssistant] ?: true,
        islandAskInPlace = this[K.islandAskInPlace] ?: true,
        islandShowNotifications = this[K.islandShowNotifications] ?: true,
        aodEnabled = this[K.aodEnabled] ?: false,
        aodPocketOff = this[K.aodPocketOff] ?: true,
        aodNightOff = this[K.aodNightOff] ?: true,
        aodNightStart = this[K.aodNightStart] ?: 0,
        aodNightEnd = this[K.aodNightEnd] ?: (7 * 60),
        aodLowBatteryOff = this[K.aodLowBatteryOff] ?: true,
        aodLowBatteryPercent = this[K.aodLowBatteryPercent] ?: 15,
        islandSplit = this[K.islandSplit] ?: true,
        islandSpeed = this[K.islandSpeed] ?: 1f,
        aodClockStyle = this[K.aodClockStyle] ?: "auto",
        aodClockScale = this[K.aodClockScale] ?: 1f,
        aodBrightness = this[K.aodBrightness] ?: 2,
        aodShowDate = this[K.aodShowDate] ?: true,
        aodShowBattery = this[K.aodShowBattery] ?: true,
        aodShowNotifications = this[K.aodShowNotifications] ?: true,
        aodShowMusic = this[K.aodShowMusic] ?: true,
        nothingAccent = this[K.nothingAccent] ?: 0xFFD71921.toInt(),
        nothingDotGrid = this[K.nothingDotGrid] ?: true,
        nothingDotTitles = this[K.nothingDotTitles] ?: true,
        glyphStrength = this[K.glyphStrength] ?: 1f,
        answerLength = this[K.answerLength] ?: "auto",
        typewriterCps = this[K.typewriterCps] ?: 90,
        edgeLights = this[K.edgeLights] ?: true,
        agentConsent = this[K.agentConsent] ?: false,
    )

    val settings: Flow<Settings> = ctx.dataStore.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    suspend fun update(block: (Settings) -> Settings) {
        ctx.dataStore.edit { p ->
            val s = block(p.toSettings())
            p[K.provider] = s.provider.id
            p[K.geminiModel] = s.geminiModel
            p[K.groqModel] = s.groqModel
            p[K.claudeModel] = s.claudeModel
            p[K.gestureEdge] = s.gestureEdge.id
            p[K.gestureHandleFraction] = s.gestureHandleFraction
            p[K.accent] = s.accentArgb
            p[K.voiceInput] = s.voiceInput.id
            p[K.speakReplies] = s.speakReplies
            p[K.conversationMode] = s.conversationMode
            p[K.assistantStyle] = s.assistantStyle.id
            p[K.glRefraction] = s.glRefraction
            p[K.webSearch] = s.webSearch.id
            p[K.memoryEnabled] = s.memoryEnabled
            p[K.controlCenterEnabled] = s.controlCenterEnabled
            p[K.islandEnabled] = s.islandEnabled
            p[K.islandShowMedia] = s.islandShowMedia
            p[K.islandShowCharging] = s.islandShowCharging
            p[K.islandShowCalls] = s.islandShowCalls
            p[K.islandShowTimers] = s.islandShowTimers
            p[K.islandStyle] = s.islandStyle.id
            p[K.islandOffset] = s.islandOffsetDp
            p[K.islandExtraWidth] = s.islandExtraWidthDp
            p[K.islandExtraHeight] = s.islandExtraHeightDp
            p[K.onboardingDone] = s.onboardingDone
            p[K.assistantName] = s.assistantName
            p[K.userName] = s.userName
            p[K.tilesOrder] = s.tilesOrder
            p[K.look] = s.look.id
            p[K.islandShowAssistant] = s.islandShowAssistant
            p[K.islandAskInPlace] = s.islandAskInPlace
            p[K.islandShowNotifications] = s.islandShowNotifications
            p[K.aodEnabled] = s.aodEnabled
            p[K.aodPocketOff] = s.aodPocketOff
            p[K.aodNightOff] = s.aodNightOff
            p[K.aodNightStart] = s.aodNightStart
            p[K.aodNightEnd] = s.aodNightEnd
            p[K.aodLowBatteryOff] = s.aodLowBatteryOff
            p[K.aodLowBatteryPercent] = s.aodLowBatteryPercent
            p[K.islandSplit] = s.islandSplit
            p[K.islandSpeed] = s.islandSpeed
            p[K.aodClockStyle] = s.aodClockStyle
            p[K.aodClockScale] = s.aodClockScale
            p[K.aodBrightness] = s.aodBrightness
            p[K.aodShowDate] = s.aodShowDate
            p[K.aodShowBattery] = s.aodShowBattery
            p[K.aodShowNotifications] = s.aodShowNotifications
            p[K.aodShowMusic] = s.aodShowMusic
            p[K.nothingAccent] = s.nothingAccent
            p[K.nothingDotGrid] = s.nothingDotGrid
            p[K.nothingDotTitles] = s.nothingDotTitles
            p[K.glyphStrength] = s.glyphStrength
            p[K.answerLength] = s.answerLength
            p[K.typewriterCps] = s.typewriterCps
            p[K.edgeLights] = s.edgeLights
            p[K.agentConsent] = s.agentConsent
        }
    }
}
