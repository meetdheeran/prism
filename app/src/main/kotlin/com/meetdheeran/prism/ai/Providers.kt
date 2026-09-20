package com.meetdheeran.prism.ai

import android.content.Context
import com.meetdheeran.prism.core.Provider
import com.meetdheeran.prism.core.SecureStore
import com.meetdheeran.prism.core.Settings

/**
 * The one place that knows which concrete [AiProvider] answers for a [Provider] enum value,
 * where its key lives in [SecureStore] and which model to use when the user has not picked one.
 *
 * Instances are cached: each provider owns an OkHttp client, and building one per request
 * would leak connection pools. Nothing here reads a key unless asked, and keys are never logged.
 */
object Providers {
    private val geminiInstance: AiProvider by lazy { GeminiProvider() }
    private val groqInstance: AiProvider by lazy { GroqProvider() }

    fun gemini(): AiProvider = geminiInstance
    fun groq(): AiProvider = groqInstance

    fun forProvider(p: Provider): AiProvider = when (p) {
        Provider.GEMINI -> gemini()
        Provider.GROQ -> groq()
    }

    /** Only the provider the user selected in Settings is ever used (hard rule 4). */
    fun forSettings(s: Settings): AiProvider = forProvider(s.provider)

    /** SecureStore entry name for a provider's API key. */
    fun keyName(p: Provider): String = when (p) {
        Provider.GEMINI -> SecureStore.KEY_GEMINI
        Provider.GROQ -> SecureStore.KEY_GROQ
    }

    /** Decrypted key for the provider, or null when none has been saved (or it is blank). */
    fun apiKey(ctx: Context, p: Provider): String? =
        SecureStore.get(ctx, keyName(p))?.trim()?.takeIf { it.isNotEmpty() }

    /** Settings.geminiModel / groqModel when set, else the provider's first known model. */
    fun modelFor(s: Settings, provider: AiProvider = forSettings(s)): String {
        val chosen = when (provider.provider) {
            Provider.GEMINI -> s.geminiModel
            Provider.GROQ -> s.groqModel
        }.trim()
        if (chosen.isNotEmpty()) return chosen
        return provider.knownModels().firstOrNull()?.id ?: ""
    }

    /** Static catalogue entry for a model id, if the provider lists it (used to gate tools/vision). */
    fun modelInfo(provider: AiProvider, model: String): ModelInfo? =
        provider.knownModels().firstOrNull { it.id == model }

    /** User-facing line shown when the selected provider has no key yet. */
    fun missingKeyMessage(p: Provider): String =
        "No ${p.label} API key yet. Add one in Settings > API keys."
}
