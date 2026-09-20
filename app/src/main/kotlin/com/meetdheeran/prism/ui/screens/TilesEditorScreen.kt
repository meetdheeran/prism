package com.meetdheeran.prism.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.meetdheeran.prism.control.Tiles
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.core.Settings
import com.meetdheeran.prism.ui.motion.pressable
import com.meetdheeran.prism.ui.theme.LocalAccent
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography
import kotlinx.coroutines.launch

/** Reorder and hide control-center tiles. Up/down buttons; no drag library needed. */
@Composable
fun TilesEditorScreen(nav: NavController) {
    val ctx = LocalContext.current
    val graph = remember { AppGraph.get(ctx) }
    val scope = rememberCoroutineScope()
    val settings by graph.prefs.settings.collectAsState(initial = Settings())
    val list = Tiles.parse(settings.tilesOrder)
    fun save(next: List<Pair<com.meetdheeran.prism.control.TileSpec, Boolean>>) {
        scope.launch {
            graph.prefs.update { it.copy(tilesOrder = Tiles.serialize(next)) }
            if (settings.controlCenterEnabled) ServiceToggles.controlCenter(ctx, true)
        }
    }

    ScreenScaffold("Tiles", onBack = { nav.up() }) { backdrop ->
        GlassGroup(backdrop, "Order and visibility", footer = "Tiles marked Shizuku need Shizuku running to act; otherwise they open the matching Settings panel.") {
            list.forEachIndexed { i, (tile, on) ->
                if (i > 0) GlassDivider()
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(tile.icon ?: Icons.Rounded.Apps, null, tint = LocalAccent.current, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(tile.label + if (tile.needsShizuku) "  · Shizuku" else "", style = PrismTypography.bodyLarge, color = if (on) PrismColors.TextPrimary else PrismColors.TextTertiary, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ArrowUpward, "Up", tint = if (i > 0) PrismColors.TextSecondary else PrismColors.TextTertiary.copy(alpha = 0.3f), modifier = Modifier.pressable(enabled = i > 0) { save(list.toMutableList().apply { add(i - 1, removeAt(i)) }) }.padding(8.dp))
                    Icon(Icons.Rounded.ArrowDownward, "Down", tint = if (i < list.lastIndex) PrismColors.TextSecondary else PrismColors.TextTertiary.copy(alpha = 0.3f), modifier = Modifier.pressable(enabled = i < list.lastIndex) { save(list.toMutableList().apply { add(i + 1, removeAt(i)) }) }.padding(8.dp))
                    Switch(on, { v -> save(list.toMutableList().apply { set(i, tile to v) }) })
                }
            }
        }
        GlassButton("Reset to default", filled = false) { save(Tiles.ALL.map { it to true }) }
    }
}
