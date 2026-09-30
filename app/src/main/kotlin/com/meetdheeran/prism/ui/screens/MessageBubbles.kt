package com.meetdheeran.prism.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetdheeran.prism.ai.Citation
import com.meetdheeran.prism.data.MessageEntity
import com.meetdheeran.prism.ui.glass.BackdropState
import com.meetdheeran.prism.ui.glass.GlassStyle
import com.meetdheeran.prism.ui.glass.LiquidGlass
import com.meetdheeran.prism.ui.siri.Chip
import com.meetdheeran.prism.ui.siri.ResponseCard
import com.meetdheeran.prism.ui.theme.LocalAccent
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class AttachmentMeta(val mime: String, val name: String, val path: String?)

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** Marks the start of screen text appended to a question (see SessionModel.send). */
const val SCREEN_MARKER = "\n\n[Text currently visible on screen]"

fun parseAttachments(raw: String?): List<AttachmentMeta> = runCatching {
    json.parseToJsonElement(raw ?: return emptyList()).jsonArray.map { e ->
        val o = e.jsonObject
        AttachmentMeta(
            o["mime"]?.jsonPrimitive?.content ?: "", o["name"]?.jsonPrimitive?.content ?: "", o["path"]?.jsonPrimitive?.content,
        )
    }
}.getOrDefault(emptyList())

fun parseCitations(raw: String?): List<Citation> = runCatching {
    json.parseToJsonElement(raw ?: return emptyList()).jsonArray.map { e ->
        val o = e.jsonObject
        Citation(o["title"]?.jsonPrimitive?.content ?: "", o["url"]?.jsonPrimitive?.content ?: "")
    }
}.getOrDefault(emptyList())

fun parseToolNames(raw: String?): List<String> = runCatching {
    json.parseToJsonElement(raw ?: return emptyList()).jsonArray.mapNotNull { e ->
        e.jsonObject["name"]?.jsonPrimitive?.content?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
    }
}.getOrDefault(emptyList())

@Composable
fun UserBubble(backdrop: BackdropState, message: MessageEntity) {
    val accent = LocalAccent.current
    val atts = remember(message.attachmentsJson) { parseAttachments(message.attachmentsJson) }
    Row(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
        LiquidGlass(
            backdrop, Modifier.widthIn(max = 320.dp), RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp),
            GlassStyle.Regular.copy(tint = accent, tintAlpha = 0.32f, refraction = 0.04f),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (atts.isNotEmpty()) {
                    Row {
                        atts.take(3).forEach { a -> AttachmentThumb(a); Spacer(Modifier.width(6.dp)) }
                    }
                    if (message.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                // The screen text sent along with a question is for the model; the bubble shows what you asked.
                val shown = message.text.substringBefore(SCREEN_MARKER).trim()
                if (shown.isNotBlank()) Text(shown, style = PrismTypography.bodyLarge.copy(lineHeight = 23.sp), color = PrismColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun AttachmentThumb(a: AttachmentMeta) {
    val bmp = remember(a.path) { a.path?.let { p -> runCatching { BitmapFactory.decodeFile(p) }.getOrNull() } }
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), a.name, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
        } else {
            Chip(if (a.name == "screenshot") "Screen" else a.mime.substringAfter('/').uppercase().take(6))
        }
    }
}

@Composable
fun AssistantBubble(backdrop: BackdropState, message: MessageEntity, onOpenUrl: (String) -> Unit) {
    val citations = remember(message.citationsJson) { parseCitations(message.citationsJson) }
    val tools = remember(message.toolCallsJson) { parseToolNames(message.toolCallsJson) }
    if (message.text.isBlank() && tools.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(end = 32.dp)) {
        if (message.text.isBlank()) {
            Row(Modifier.padding(start = 6.dp)) { tools.forEach { Chip(it); Spacer(Modifier.width(6.dp)) } }
        } else {
            ResponseCard(backdrop, message.text, streaming = false, citations = citations, chips = tools, onOpenUrl = onOpenUrl)
        }
    }
}

@Composable
fun DraftBubble(backdrop: BackdropState, draft: Draft, onOpenUrl: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 32.dp)) {
        ResponseCard(backdrop, draft.text, streaming = draft.error == null, citations = draft.citations, chips = draft.chips, error = draft.error, onOpenUrl = onOpenUrl)
    }
}
