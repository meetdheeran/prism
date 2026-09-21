package com.meetdheeran.prism.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets (API keys) are encrypted with an AES-256-GCM key that lives in the Android
 * Keystore (hardware-backed TEE on the OnePlus 7) and never leaves it. Only the
 * ciphertext + IV are written to a private SharedPreferences file. Backups are
 * disabled app-wide (allowBackup=false), so the ciphertext never leaves the phone
 * either. Nothing here is ever logged.
 */
object SecureStore {
    private const val ALIAS = "prism_master_key"
    private const val PREFS = "prism_secrets"
    private const val GCM_TAG_BITS = 128

    const val KEY_GEMINI = "gemini_api_key"
    const val KEY_GROQ = "groq_api_key"
    const val KEY_CLAUDE = "claude_api_key"
    const val KEY_SEARCH = "search_api_key"

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun put(ctx: Context, name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs(ctx).edit().putString(name, blob).apply()
    }

    fun get(ctx: Context, name: String): String? {
        val blob = prefs(ctx).getString(name, null) ?: return null
        return runCatching {
            val parts = blob.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            // Key invalidated or corrupt blob: treat as absent rather than crash; the user re-enters the key.
            prefs(ctx).edit().remove(name).apply()
            null
        }
    }

    fun has(ctx: Context, name: String): Boolean = prefs(ctx).contains(name)

    fun remove(ctx: Context, name: String) { prefs(ctx).edit().remove(name).apply() }

    fun clearAll(ctx: Context) { prefs(ctx).edit().clear().apply() }

    /** Shown in the UI instead of the key, e.g. "AIza........3kQ". */
    fun mask(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        if (value.length <= 8) return "••••••••"
        return value.take(4) + "••••••••" + value.takeLast(3)
    }
}
