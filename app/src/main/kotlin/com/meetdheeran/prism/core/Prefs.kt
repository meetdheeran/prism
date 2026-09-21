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
        }
    }
}
