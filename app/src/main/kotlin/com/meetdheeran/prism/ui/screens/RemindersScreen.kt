package com.meetdheeran.prism.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.reminders.ReminderScheduler
import com.meetdheeran.prism.ui.motion.pressable
import com.meetdheeran.prism.ui.theme.LocalAccent
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography
import kotlinx.coroutines.launch

/** Reminders Prism set for you. Add new ones by asking ("remind me at 6 to call Mom"). */
@Composable
fun RemindersScreen(nav: NavController) {
    val ctx = LocalContext.current
    val graph = remember { AppGraph.get(ctx) }
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    val items by graph.db.reminders().all().collectAsState(initial = emptyList())

    ScreenScaffold("Reminders", onBack = { nav.up() }) { backdrop ->
        GlassGroup(backdrop, "${items.count { !it.done }} pending", footer = "Say \"remind me in 20 minutes to check the oven\" or \"remind me tomorrow at 9 to call the bank\". Prism notifies you itself; Done and Snooze live on the notification.") {
            if (items.isEmpty()) Text("No reminders yet.", style = PrismTypography.bodyMedium, color = PrismColors.TextSecondary, modifier = Modifier.padding(16.dp))
            items.forEachIndexed { i, r ->
                if (i > 0) GlassDivider()
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        r.done, { on -> scope.launch { graph.db.reminders().update(r.copy(done = on)); if (on) ReminderScheduler.cancel(ctx, r.id) else ReminderScheduler.schedule(ctx, r) } },
                        colors = CheckboxDefaults.colors(checkedColor = accent),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(r.text, style = PrismTypography.bodyLarge, color = if (r.done) PrismColors.TextTertiary else PrismColors.TextPrimary, textDecoration = if (r.done) TextDecoration.LineThrough else null)
                        Text(ReminderScheduler.format(r.atMillis), style = PrismTypography.bodySmall, color = if (!r.done && r.atMillis < System.currentTimeMillis()) PrismColors.Warn else PrismColors.TextSecondary)
                    }
                    Icon(Icons.Rounded.Delete, "Delete", tint = PrismColors.TextTertiary, modifier = Modifier.pressable { scope.launch { graph.db.reminders().delete(r.id); ReminderScheduler.cancel(ctx, r.id) } }.padding(8.dp))
                }
            }
        }
        if (items.any { it.done }) {
            GlassButton("Clear completed", filled = false) { scope.launch { graph.db.reminders().clearDone() } }
            Spacer(Modifier.height(12.dp))
        }
    }
}
