package com.meetdheeran.prism.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.data.MemoryEntity
import com.meetdheeran.prism.ui.motion.pressable
import com.meetdheeran.prism.ui.siri.Chip
import com.meetdheeran.prism.ui.theme.LocalAccent
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography
import kotlinx.coroutines.launch

/** Everything the assistant remembers, in plain sight: add, edit, pause, delete. */
@Composable
fun MemoryScreen(nav: NavController) {
    val ctx = LocalContext.current
    val graph = remember { AppGraph.get(ctx) }
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    val items by graph.memory.all().collectAsState(initial = emptyList())
    var newText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    ScreenScaffold("Memory", onBack = { nav.up() }) { backdrop ->
        GlassGroup(backdrop, "Add", footer = "Facts here are added to every conversation's context. The assistant can also save things with your knowledge (they show up tagged 'assistant').") {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    newText, { newText = it }, placeholder = { Text("e.g. I'm vegetarian. My office is in Dubai Marina.", color = PrismColors.TextTertiary) },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = PrismColors.TextPrimary, unfocusedTextColor = PrismColors.TextPrimary, focusedBorderColor = accent, unfocusedBorderColor = PrismColors.TextTertiary, cursorColor = accent),
                )
                Spacer(Modifier.height(10.dp))
                GlassButton("Remember this", enabled = newText.isNotBlank()) { scope.launch { graph.memory.add(newText); newText = "" } }
            }
        }
        GlassGroup(backdrop, "${items.size} memories") {
            if (items.isEmpty()) Text("Nothing yet.", style = PrismTypography.bodyMedium, color = PrismColors.TextSecondary, modifier = Modifier.padding(16.dp))
            items.forEachIndexed { i, m ->
                if (i > 0) GlassDivider()
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).pressable(scaleDown = 0.99f) { editing = m }) {
                        Text(m.text, style = PrismTypography.bodyLarge, color = if (m.enabled) PrismColors.TextPrimary else PrismColors.TextTertiary)
                        Spacer(Modifier.height(4.dp))
                        Row { Chip(m.category); Spacer(Modifier.width(6.dp)); Chip(if (m.source == "user") "you" else "assistant", accent = m.source != "user") }
                    }
                    androidx.compose.material3.Switch(m.enabled, { on -> scope.launch { graph.memory.update(m.copy(enabled = on)) } })
                    Icon(Icons.Rounded.Delete, "Delete", tint = PrismColors.TextTertiary, modifier = Modifier.pressable { scope.launch { graph.memory.delete(m) } }.padding(8.dp))
                }
            }
        }
        if (items.isNotEmpty()) GlassButton("Delete all memory", danger = true) { confirmAll = true }
    }

    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.text) }
        AlertDialog(
            onDismissRequest = { editing = null }, containerColor = PrismColors.Slate,
            title = { Text("Edit memory", color = PrismColors.TextPrimary) },
            text = { OutlinedTextField(text, { text = it }, maxLines = 4, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = PrismColors.TextPrimary, unfocusedTextColor = PrismColors.TextPrimary)) },
            confirmButton = { TextButton({ scope.launch { graph.memory.update(m.copy(text = text.trim())) }; editing = null }) { Text("Save", color = accent) } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel", color = PrismColors.TextSecondary) } },
        )
    }
    if (confirmAll) ConfirmDialog("Delete all memory?", "The assistant will forget everything saved here.", onConfirm = { scope.launch { graph.memory.deleteAll() } }) { confirmAll = false }
}
