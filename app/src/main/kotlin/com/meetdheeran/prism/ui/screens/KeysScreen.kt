package com.meetdheeran.prism.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.meetdheeran.prism.ai.Providers
import com.meetdheeran.prism.core.Provider
import com.meetdheeran.prism.core.SecureStore
import com.meetdheeran.prism.ui.glass.BackdropState
import com.meetdheeran.prism.ui.theme.LocalAccent
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography
import kotlinx.coroutines.launch

/**
 * Private key entry. Keys are typed or pasted here only, masked by default, encrypted with the
 * Android Keystore, and never shown again in full. "Test" makes one cheap list-models call.
 */
@Composable
fun KeysScreen(nav: NavController) {
    ScreenScaffold("API keys", onBack = { nav.up() }) { backdrop ->
        KeyCard(backdrop, Provider.GEMINI, "Gemini", "Create one at aistudio.google.com/apikey. Free tier covers the Flash models.", "AIza")
        KeyCard(backdrop, Provider.GROQ, "Groq", "Create one at console.groq.com/keys. Free tier covers gpt-oss and Whisper.", "gsk_")
        Text(
            "Keys are encrypted with a hardware-backed key in the Android Keystore, excluded from backups, never logged, and only ever sent to that provider's own API.",
            style = PrismTypography.bodySmall, color = PrismColors.TextTertiary, modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun KeyCard(backdrop: BackdropState, provider: Provider, title: String, hint: String, prefix: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    val keyName = Providers.keyName(provider)
    var saved by remember { mutableStateOf(SecureStore.has(ctx, keyName)) }
    var savedMask by remember { mutableStateOf(SecureStore.mask(SecureStore.get(ctx, keyName))) }
    var text by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    GlassGroup(backdrop, title, footer = hint) {
        Column(Modifier.padding(16.dp)) {
            Text(if (saved) "Saved: $savedMask" else "Not set", style = PrismTypography.bodyMedium, color = if (saved) PrismColors.Good else PrismColors.TextSecondary)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it.trim(); status = null },
                placeholder = { Text(if (saved) "Paste a new key to replace" else "Paste your $title key", color = PrismColors.TextTertiary) },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show", tint = PrismColors.TextSecondary) } },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PrismColors.TextPrimary, unfocusedTextColor = PrismColors.TextPrimary,
                    focusedBorderColor = accent, unfocusedBorderColor = Color.White.copy(alpha = 0.25f), cursorColor = accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row {
                GlassButton("Paste", filled = false) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
                }
                Spacer(Modifier.width(8.dp))
                GlassButton("Save", enabled = text.isNotBlank()) {
                    if (!text.startsWith(prefix)) { status = "That doesn't look like a $title key (expected to start with \"$prefix\")."; return@GlassButton }
                    SecureStore.put(ctx, keyName, text)
                    saved = true; savedMask = SecureStore.mask(text); text = ""; status = "Saved."
                }
                Spacer(Modifier.width(8.dp))
                GlassButton(if (testing) "Testing…" else "Test", filled = false, enabled = !testing && (saved || text.isNotBlank())) {
                    val key = text.ifBlank { SecureStore.get(ctx, keyName) ?: "" }
                    testing = true
                    scope.launch {
                        val r = Providers.forProvider(provider).listModels(key)
                        status = r.fold({ "Key works — ${it.size} models available." }, { "Failed: ${it.message}" })
                        testing = false
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (saved) GlassButton("Remove", danger = true) { SecureStore.remove(ctx, keyName); saved = false; savedMask = ""; status = "Removed." }
            }
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = PrismTypography.bodySmall, color = if (it.startsWith("Failed") || it.startsWith("That")) PrismColors.Bad else PrismColors.Good)
            }
        }
    }
}
