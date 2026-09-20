package com.meetdheeran.prism.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meetdheeran.prism.data.ConversationEntity
import com.meetdheeran.prism.ui.glass.BackdropState
import com.meetdheeran.prism.ui.glass.GlassStyle
import com.meetdheeran.prism.ui.glass.LiquidGlass
import com.meetdheeran.prism.ui.motion.Motion
import com.meetdheeran.prism.ui.motion.pressable
import com.meetdheeran.prism.ui.theme.LocalAccent
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography
import java.text.DateFormat
import java.util.Date

/** Glass side sheet listing past conversations. Rename/delete inline; clear-all confirmed. */
@Composable
fun HistoryDrawer(
    visible: Boolean,
    backdrop: BackdropState,
    conversations: List<ConversationEntity>,
    currentId: Long?,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onOpen: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onClearAll: () -> Unit,
) {
    var renaming by remember { mutableStateOf<ConversationEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    AnimatedVisibility(visible, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible,
        enter = slideInHorizontally(Motion.panel()) { -it },
        exit = slideOutHorizontally(Motion.panel()) { -it },
    ) {
        LiquidGlass(
            backdrop,
            Modifier.fillMaxHeight().width(300.dp).statusBarsPadding().navigationBarsPadding().padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
            RoundedCornerShape(26.dp), GlassStyle.Dark,
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Text("History", style = PrismTypography.headlineMedium)
                Spacer(Modifier.height(10.dp))
                GlassButton("New chat", Modifier.fillMaxWidth()) { onNew(); onDismiss() }
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(conversations, key = { it.id }) { c ->
                        val on = c.id == currentId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressable(scaleDown = 0.98f) { onOpen(c.id); onDismiss() }
                                .background(if (on) Color.White.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = PrismTypography.bodyLarge, maxLines = 1)
                                Text(
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.updatedAt)) + "  ·  " + c.provider,
                                    style = PrismTypography.labelSmall, color = PrismColors.TextTertiary,
                                )
                            }
                            Icon(Icons.Rounded.Edit, "Rename", tint = PrismColors.TextTertiary, modifier = Modifier.pressable { renaming = c }.padding(6.dp))
                            Icon(Icons.Rounded.Delete, "Delete", tint = PrismColors.TextTertiary, modifier = Modifier.pressable { onDelete(c.id) }.padding(6.dp))
                        }
                    }
                }
                if (conversations.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) { Text("Clear all", color = PrismColors.Bad) }
                }
            }
        }
    }
    renaming?.let { c ->
        var title by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = PrismColors.Slate,
            title = { Text("Rename", color = PrismColors.TextPrimary) },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(c.id, title.trim().ifEmpty { c.title }); renaming = null }) { Text("Save", color = LocalAccent.current) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel", color = PrismColors.TextSecondary) } },
        )
    }
    if (confirmClear) ConfirmDialog("Clear all conversations?", "This deletes every conversation on this phone. Memory is kept.", "Clear", onConfirm = onClearAll) { confirmClear = false }
}
