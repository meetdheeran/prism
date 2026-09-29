package com.meetdheeran.prism.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import java.io.File
import androidx.navigation.NavController
import com.meetdheeran.prism.assistant.SpeechState
import com.meetdheeran.prism.core.Provider
import com.meetdheeran.prism.ai.Providers
import com.meetdheeran.prism.data.MessageEntity
import com.meetdheeran.prism.shizuku.ShizukuBridge
import com.meetdheeran.prism.ui.glass.GlassBackground
import com.meetdheeran.prism.ui.glass.GlassStyle
import com.meetdheeran.prism.ui.glass.LiquidGlass
import com.meetdheeran.prism.ui.glass.rememberBackdropState
import com.meetdheeran.prism.ui.motion.Motion
import com.meetdheeran.prism.ui.motion.pressable
import com.meetdheeran.prism.ui.siri.Chip
import com.meetdheeran.prism.ui.siri.EdgeGlow
import com.meetdheeran.prism.ui.siri.ListeningPill
import com.meetdheeran.prism.ui.siri.Orb
import com.meetdheeran.prism.ui.siri.Phase
import com.meetdheeran.prism.ui.theme.LocalAccent
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(nav: NavController, launch: LaunchRequest?, onLaunchConsumed: () -> Unit, vm: ChatViewModel = viewModel()) {
    val ctx = LocalContext.current
    val backdrop = rememberBackdropState()
    val accent = LocalAccent.current
    val settings by vm.settings.collectAsState()
    val messages by vm.messages.collectAsState()
    val conversations by vm.conversations.collectAsState()
    val conversationId by vm.conversationId.collectAsState()
    val speech by vm.speech.state.collectAsState()
    val speaking by vm.speaking.collectAsState()
    var drawer by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::addUri) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::addUri) }
    val cameraUri = remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri.value?.let(vm::addUri) }

    LaunchedEffect(launch?.stamp) {
        val l = launch ?: return@LaunchedEffect
        l.conversationId?.let(vm::open)
        vm.attachments += l.attachments
        if (l.prompt != null) vm.send(l.prompt)
        if (l.openAssistant) vm.startVoice()
        onLaunchConsumed()
    }
    LaunchedEffect(speech) { if (speech is SpeechState.Error) vm.toast = (speech as SpeechState.Error).message }
    LaunchedEffect(vm.toast) { if (vm.toast != null) { delay(2600); vm.toast = null } }
    LaunchedEffect(messages.size, vm.draft?.text?.length) {
        val n = messages.size + (if (vm.draft != null) 1 else 0)
        if (n > 0) listState.animateScrollToItem(n)
    }

    val listening = speech is SpeechState.Listening
    val level = (speech as? SpeechState.Listening)?.level ?: 0f
    val phase = when {
        listening -> Phase.Listening
        vm.busy && vm.draft?.text.isNullOrEmpty() -> Phase.Thinking
        speaking -> Phase.Speaking
        else -> Phase.Idle
    }
    val pillText = if (listening) (speech as SpeechState.Listening).partial.ifEmpty { vm.input } else vm.input

    Box(Modifier.fillMaxSize()) {
        GlassBackground(backdrop, accent = accent, animated = true)
        EdgeGlow(active = listening || phase == Phase.Thinking, level = level, phase = phase)
        androidx.compose.runtime.SideEffect { com.meetdheeran.prism.assistant.AssistantPulse.publish(phase, level) }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.meetdheeran.prism.assistant.AssistantPulse.clear() } }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(backdrop, Icons.Rounded.History, "History") { drawer = true }
                Spacer(Modifier.width(10.dp))
                LiquidGlass(backdrop, Modifier.weight(1f).pressable(scaleDown = 0.98f) { vm.newChat() }, RoundedCornerShape(22.dp), GlassStyle.Tile) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(settings.assistantName, style = PrismTypography.titleMedium, modifier = Modifier.weight(1f))
                        val model = Providers.modelFor(settings).substringAfterLast('/').ifBlank { "default" }
                        Chip("${settings.provider.label} · ${model.take(18)}")
                    }
                }
                Spacer(Modifier.width(10.dp))
                if (speaking) {
                    GlassIconButton(backdrop, Icons.Rounded.VolumeOff, "Stop speaking") { vm.stopSpeaking() }
                    Spacer(Modifier.width(10.dp))
                }
                GlassIconButton(backdrop, Icons.Rounded.Settings, "Settings") { nav.navigate(Routes.SETTINGS) }
            }

            // Conversation
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty() && vm.draft == null) {
                    EmptyState(level, phase, settings.assistantName) { vm.send(it) }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(messages.filter { it.role != "tool" && it.role != "system" }, key = { it.id }) { m: MessageEntity ->
                            if (m.role == "user") UserBubble(backdrop, m) else AssistantBubble(backdrop, m) { openUrl(ctx, it) }
                        }
                        vm.draft?.let { d -> item("draft") { DraftBubble(backdrop, d) { openUrl(ctx, it) } } }
                        item { Spacer(Modifier.height(4.dp)) }
                    }
                }
            }

            // Pending attachments
            if (vm.attachments.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                    vm.attachments.forEachIndexed { i, a ->
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(PrismColors.TextPrimary.copy(alpha = 0.1f))) {
                            val bmp = remember(a) { if (a.isImage) runCatching { BitmapFactory.decodeByteArray(a.bytes, 0, a.bytes.size) }.getOrNull() else null }
                            if (bmp != null) Image(bmp.asImageBitmap(), a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Text(a.mime.substringAfter('/').uppercase().take(5), style = PrismTypography.labelSmall, modifier = Modifier.align(Alignment.Center))
                            Icon(
                                Icons.Rounded.Close, "Remove", tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).size(18.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape).pressable { vm.attachments.removeAt(i) },
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }

            if (vm.conversation) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
                    Chip("Conversation on \u00B7 tap to end", accent = true) { vm.endConversation() }
                }
            }
            // Input
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                ListeningPill(
                    backdrop = backdrop,
                    text = pillText,
                    onTextChange = { vm.input = it },
                    onSend = { vm.send() },
                    onMic = { vm.startVoice() },
                    onStop = { vm.stopVoice() },
                    listening = listening,
                    level = level,
                    processing = speech is SpeechState.Processing,
                    placeholder = "Ask ${settings.assistantName}…",
                    leading = {
                        Box {
                            Icon(
                                Icons.Rounded.Add, "Attach", tint = PrismColors.TextSecondary,
                                modifier = Modifier.size(40.dp).clip(CircleShape).pressable { attachMenu = true }.padding(8.dp),
                            )
                            DropdownMenu(attachMenu, { attachMenu = false }, modifier = Modifier.background(PrismColors.Slate)) {
                                DropdownMenuItem({ Text("Photo", color = PrismColors.TextPrimary) }, { attachMenu = false; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                                DropdownMenuItem({ Text("PDF or text file", color = PrismColors.TextPrimary) }, { attachMenu = false; pickFile.launch(arrayOf("application/pdf", "text/plain", "text/markdown")) })
                                DropdownMenuItem({ Text("Take a photo and ask", color = PrismColors.TextPrimary) }, {
                                    attachMenu = false
                                    val f = File(ctx.cacheDir, "camera/shot.jpg").apply { parentFile?.mkdirs() }
                                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                                    cameraUri.value = uri
                                    runCatching { takePhoto.launch(uri) }.onFailure { vm.toast = "No camera app available" }
                                })
                                DropdownMenuItem(
                                    { Text(if (ShizukuBridge.isReady()) "Capture screen" else "Capture screen (needs Shizuku)", color = PrismColors.TextPrimary) },
                                    { attachMenu = false; vm.captureScreen() },
                                )
                            }
                        }
                    },
                )
            }
        }

        // Toast
        AnimatedVisibility(vm.toast != null, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp), enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 }) {
            LiquidGlass(backdrop, shape = RoundedCornerShape(16.dp), style = GlassStyle.Dark) {
                Text(vm.toast ?: "", style = PrismTypography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }

        HistoryDrawer(
            visible = drawer, backdrop = backdrop, conversations = conversations, currentId = conversationId,
            onDismiss = { drawer = false }, onNew = vm::newChat, onOpen = vm::open, onRename = vm::rename, onDelete = vm::delete, onClearAll = vm::clearAll,
        )

        vm.pendingConfirmation?.let { p ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(remember { MutableInteractionSource() }, null) { vm.confirm(false) })
            AnimatedVisibility(true, Modifier.align(Alignment.BottomCenter), enter = slideInVertically(Motion.panel()) { it }) {
                LiquidGlass(backdrop, Modifier.padding(16.dp).navigationBarsPadding(), RoundedCornerShape(26.dp), GlassStyle.Dark) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Allow this?", style = PrismTypography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(p.description, style = PrismTypography.bodyMedium, color = PrismColors.TextSecondary)
                        Spacer(Modifier.height(16.dp))
                        Row {
                            GlassButton("Don't", Modifier.weight(1f), filled = false) { vm.confirm(false) }
                            Spacer(Modifier.width(10.dp))
                            GlassButton("Allow", Modifier.weight(1f)) { vm.confirm(true) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(level: Float, phase: Phase, name: String, onSuggestion: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Orb(level = level, phase = phase, size = 132.dp)
        Spacer(Modifier.height(22.dp))
        Text("Hi, I'm $name.", style = PrismTypography.headlineLarge)
        Text("Ask anything, or tell me what to do.", style = PrismTypography.bodyLarge, color = PrismColors.TextSecondary)
        Spacer(Modifier.height(26.dp))
        listOf(
            "What's on my screen?",
            "Set a timer for 10 minutes",
            "Play some focus music",
            "Summarize my day from the calendar",
        ).forEach { s ->
            Box(Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(PrismColors.TextPrimary.copy(alpha = 0.10f)).pressable { onSuggestion(s) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(s, style = PrismTypography.bodyMedium)
            }
        }
    }
}

internal fun openUrl(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
