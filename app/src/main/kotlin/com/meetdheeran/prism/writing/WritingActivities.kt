package com.meetdheeran.prism.writing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meetdheeran.prism.MainActivity
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.core.Images
import com.meetdheeran.prism.ui.glass.GlassBackground
import com.meetdheeran.prism.ui.glass.GlassStyle
import com.meetdheeran.prism.ui.glass.LiquidGlass
import com.meetdheeran.prism.ui.glass.rememberBackdropState
import com.meetdheeran.prism.ui.screens.GlassButton
import com.meetdheeran.prism.ui.siri.Chip
import com.meetdheeran.prism.ui.siri.ThinkingDots
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTheme
import com.meetdheeran.prism.ui.theme.PrismTypography
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** "Prism" in any app's text-selection toolbar: rewrite, summarize, translate, explain, fix. */
class ProcessTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        if (text.isBlank()) { finish(); return }
        setContent {
            PrismTheme {
                WritingSheet(
                    source = text, readOnly = readOnly,
                    onReplace = { result -> setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result)); finish() },
                    onClose = { finish() },
                )
            }
        }
    }
}

private val ACTIONS = listOf(
    "Rewrite" to "Rewrite the following text so it reads naturally and clearly. Keep the meaning, language and approximate length. Return only the rewritten text.",
    "Friendly" to "Rewrite the following text in a warm, friendly tone. Return only the rewritten text.",
    "Professional" to "Rewrite the following text in a concise, professional tone. Return only the rewritten text.",
    "Shorter" to "Shorten the following text to its essentials. Return only the shortened text.",
    "Summarize" to "Summarize the following text in a few short bullet points or sentences. Return only the summary.",
    "Fix grammar" to "Correct spelling, grammar and punctuation in the following text without changing its meaning or tone. Return only the corrected text.",
    "Explain" to "Explain the following text simply, as if to a smart friend. Be brief.",
    "Translate → English" to "Translate the following text to English. Return only the translation.",
    "Translate → Hindi" to "Translate the following text to Hindi. Return only the translation.",
    "Translate → Arabic" to "Translate the following text to Arabic. Return only the translation.",
)

@Composable
fun WritingSheet(source: String, readOnly: Boolean, onReplace: (String) -> Unit, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val graph = remember { AppGraph.get(ctx) }
    val backdrop = rememberBackdropState()
    var result by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(-1) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { if (!graph.assistant.isConfigured()) error = "No API key yet. Add one in Prism > Settings > API keys." }

    fun run(i: Int) {
        selected = i
        job?.cancel()
        result = ""; error = null; running = true
        job = scope.launch {
            runCatching {
                graph.engine.quick("${ACTIONS[i].second}\n\n---\n$source").collect { result += it }
            }.onFailure { error = it.message ?: "Something went wrong" }
            running = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
        Box(Modifier.fillMaxSize()) { GlassBackground(backdrop, animated = false, intensity = 0.5f, opaque = false) }
        LiquidGlass(backdrop, Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp), RoundedCornerShape(28.dp), GlassStyle.Dark) {
            Column(Modifier.padding(18.dp)) {
                Text("Prism writing tools", style = PrismTypography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(source.take(220) + if (source.length > 220) "…" else "", style = PrismTypography.bodySmall, color = PrismColors.TextSecondary, maxLines = 3)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ACTIONS.forEachIndexed { i, (label, _) -> Chip(label, accent = i == selected) { run(i) }; Spacer(Modifier.width(6.dp)) }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 260.dp).verticalScroll(rememberScrollState())) {
                    when {
                        error != null -> Text(error!!, color = PrismColors.Bad, style = PrismTypography.bodyMedium)
                        running && result.isEmpty() -> ThinkingDots()
                        result.isEmpty() -> Text("Pick an action.", color = PrismColors.TextTertiary, style = PrismTypography.bodyMedium)
                        else -> Text(result, style = PrismTypography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    GlassButton("Copy", filled = false, enabled = result.isNotBlank() && !running) {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Prism", result))
                    }
                    Spacer(Modifier.width(8.dp))
                    if (!readOnly) GlassButton("Replace", enabled = result.isNotBlank() && !running) { onReplace(result.trim()) }
                    else GlassButton("Open in Prism", enabled = result.isNotBlank() && !running) {
                        ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PROMPT, "${ACTIONS[selected].first}:\n$source").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); onClose()
                    }
                }
            }
        }
    }
}

/** Share target: images, PDFs and text land in a new conversation. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(@Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        lifecycleScope.launch {
            val dir = File(cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val paths = ArrayList<String>()
            val mimes = ArrayList<String>()
            withContext(Dispatchers.IO) {
                uris.take(3).forEachIndexed { i, uri ->
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    val bytes = if (mime.startsWith("image/")) Images.fromUri(this@ShareActivity, uri) else runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                    if (bytes != null && bytes.size <= 18 * 1024 * 1024) {
                        val f = File(dir, "share_$i")
                        f.writeBytes(bytes)
                        paths += f.absolutePath
                        mimes += if (mime.startsWith("image/")) "image/jpeg" else mime
                    }
                }
            }
            val prompt = buildString {
                if (!subject.isNullOrBlank()) append(subject).append("\n")
                if (!text.isNullOrBlank()) append(text)
            }.ifBlank { if (paths.isNotEmpty()) "What can you tell me about this?" else "" }
            startActivity(
                Intent(this@ShareActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_PROMPT, prompt)
                    .putExtra(MainActivity.EXTRA_ATTACH_PATHS, paths.toTypedArray())
                    .putExtra(MainActivity.EXTRA_ATTACH_MIMES, mimes.toTypedArray()),
            )
            finish()
        }
    }
}
