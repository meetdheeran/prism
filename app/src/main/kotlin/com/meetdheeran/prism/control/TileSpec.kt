package com.meetdheeran.prism.control

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.Assistant
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.ui.graphics.vector.ImageVector

/** One control-center tile. `span` is grid columns of 4 (2 = a wide tile). */
data class TileSpec(val id: String, val label: String, val icon: ImageVector?, val kind: Kind, val needsShizuku: Boolean = false) {
    enum class Kind { TOGGLE, ACTION, MEDIA, SLIDER_BRIGHTNESS, SLIDER_VOLUME }
}

object Tiles {
    val ALL: List<TileSpec> = listOf(
        TileSpec("media", "Now playing", null, TileSpec.Kind.MEDIA),
        TileSpec("wifi", "Wi-Fi", Icons.Rounded.Wifi, TileSpec.Kind.TOGGLE, needsShizuku = true),
        TileSpec("data", "Mobile data", Icons.Rounded.NetworkCell, TileSpec.Kind.TOGGLE, needsShizuku = true),
        TileSpec("bluetooth", "Bluetooth", Icons.Rounded.Bluetooth, TileSpec.Kind.TOGGLE),
        TileSpec("airplane", "Airplane", Icons.Rounded.AirplanemodeActive, TileSpec.Kind.TOGGLE, needsShizuku = true),
        TileSpec("brightness", "Brightness", null, TileSpec.Kind.SLIDER_BRIGHTNESS),
        TileSpec("volume", "Volume", null, TileSpec.Kind.SLIDER_VOLUME),
        TileSpec("flashlight", "Flashlight", Icons.Rounded.FlashlightOn, TileSpec.Kind.TOGGLE),
        TileSpec("rotation", "Auto-rotate", Icons.Rounded.ScreenRotation, TileSpec.Kind.TOGGLE),
        TileSpec("dnd", "Do Not Disturb", Icons.Rounded.DoNotDisturbOn, TileSpec.Kind.TOGGLE),
        TileSpec("battery_saver", "Battery saver", Icons.Rounded.BatterySaver, TileSpec.Kind.TOGGLE, needsShizuku = true),
        TileSpec("dark_mode", "Dark theme", Icons.Rounded.DarkMode, TileSpec.Kind.TOGGLE, needsShizuku = true),
        TileSpec("location", "Location", Icons.Rounded.LocationOn, TileSpec.Kind.TOGGLE, needsShizuku = true),
        TileSpec("nfc", "NFC", Icons.Rounded.Nfc, TileSpec.Kind.TOGGLE, needsShizuku = true),
        TileSpec("hotspot", "Hotspot", Icons.Rounded.WifiTethering, TileSpec.Kind.ACTION),
        TileSpec("screenshot", "Screenshot", Icons.Rounded.Screenshot, TileSpec.Kind.ACTION, needsShizuku = true),
        TileSpec("lock", "Lock", Icons.Rounded.Lock, TileSpec.Kind.ACTION, needsShizuku = true),
        TileSpec("assistant", "Assistant", Icons.Rounded.Assistant, TileSpec.Kind.ACTION),
        TileSpec("camera", "Camera", Icons.Rounded.CameraAlt, TileSpec.Kind.ACTION),
        TileSpec("calculator", "Calculator", Icons.Rounded.Calculate, TileSpec.Kind.ACTION),
        TileSpec("settings", "Settings", Icons.Rounded.Settings, TileSpec.Kind.ACTION),
    )

    val DEFAULT_ORDER: List<String> = ALL.map { it.id }

    fun byId(id: String): TileSpec? = ALL.firstOrNull { it.id == id }

    /** Parses Settings.tilesOrder ("wifi,data,-nfc" — a leading '-' means hidden). */
    fun parse(order: String): List<Pair<TileSpec, Boolean>> {
        if (order.isBlank()) return ALL.map { it to true }
        val seen = LinkedHashMap<String, Boolean>()
        order.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            val hidden = token.startsWith("-")
            val id = token.removePrefix("-")
            if (byId(id) != null) seen[id] = !hidden
        }
        ALL.forEach { if (it.id !in seen) seen[it.id] = true }
        return seen.map { (id, on) -> byId(id)!! to on }
    }

    fun serialize(list: List<Pair<TileSpec, Boolean>>): String = list.joinToString(",") { (t, on) -> if (on) t.id else "-" + t.id }
}
