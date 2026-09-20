package com.meetdheeran.prism.actions

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import com.meetdheeran.prism.ai.Schema
import com.meetdheeran.prism.ai.ToolHandler
import com.meetdheeran.prism.ai.ToolResult
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.island.MediaWatcher
import com.meetdheeran.prism.shizuku.ShellCommands
import com.meetdheeran.prism.shizuku.ShellResult
import com.meetdheeran.prism.shizuku.ShizukuBridge
import com.meetdheeran.prism.shizuku.ShizukuState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Device controls. Each one prefers a public API; when Android reserves the switch for the
 * system (Wi-Fi, data, airplane, night mode, location, NFC), it goes through Shizuku and,
 * failing that, opens the exact Settings panel so the user is one tap away.
 */
object DeviceTools {
    private val torch = Torch()

    fun all(graph: AppGraph): List<ToolHandler> = listOf(
        tool(
            "flashlight",
            "Turn the flashlight (torch) on or off.",
            Schema.obj(listOf("on")) { bool("on", "true = on") },
        ) { a, ctx ->
            val on = a.argBool("on") ?: true
            torch.set(ctx.app, on)?.let { failResult(it) } ?: okResult(if (on) "Flashlight on" else "Flashlight off")
        },
        tool(
            "media_control",
            "Control whatever is playing: play, pause, toggle, next, previous.",
            Schema.obj(listOf("action")) { string("action", "Action", listOf("play", "pause", "toggle", "next", "previous")) },
        ) { a, ctx ->
            val action = a.argStr("action") ?: "toggle"
            val np = MediaWatcher.now.value
            if (np != null) {
                when (action) { "play" -> MediaWatcher.play(); "pause" -> MediaWatcher.pause(); "next" -> MediaWatcher.next(); "previous" -> MediaWatcher.previous(); else -> MediaWatcher.toggle() }
                okResult("${action.replaceFirstChar { it.uppercase() }}: ${np.title} — ${np.artist}")
            } else {
                val key = when (action) {
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                val am = ctx.app.getSystemService(AudioManager::class.java)
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                okResult("Sent $action to the active player")
            }
        },
        tool("now_playing", "What is playing right now (title, artist, app, position).") { _, _ ->
            val np = MediaWatcher.now.value ?: return@tool failResult(
                "Nothing is playing, or Prism lacks notification access (needed to see playback).",
                needs = "notification_access",
            )
            ToolResult.data(
                buildJsonObject {
                    put("ok", true); put("title", np.title); put("artist", np.artist); put("album", np.album)
                    put("app", np.packageName); put("playing", np.isPlaying)
                    put("positionSec", np.livePosition() / 1000); put("durationSec", np.durationMs / 1000)
                },
                "${np.title} — ${np.artist}",
            )
        },
        tool(
            "set_volume",
            "Set a volume level as a percentage (0-100), or adjust it by a delta.",
            Schema.obj {
                string("stream", "Which volume", listOf("media", "ring", "alarm", "notification", "call"))
                int("level", "Absolute level 0-100")
                int("delta", "Relative change, e.g. -20 or 10")
            },
        ) { a, ctx ->
            val am = ctx.app.getSystemService(AudioManager::class.java)
            val stream = when (a.argStr("stream")) {
                "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM
                "notification" -> AudioManager.STREAM_NOTIFICATION; "call" -> AudioManager.STREAM_VOICE_CALL
                else -> AudioManager.STREAM_MUSIC
            }
            val max = am.getStreamMaxVolume(stream)
            val cur = am.getStreamVolume(stream)
            val target = a.argInt("level")?.let { (it.coerceIn(0, 100) * max / 100f).toInt() }
                ?: a.argInt("delta")?.let { (cur + it * max / 100f).toInt().coerceIn(0, max) }
                ?: return@tool failResult("Give level or delta")
            try {
                am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
                okResult("${a.argStr("stream") ?: "media"} volume ${target * 100 / max}%")
            } catch (e: SecurityException) {
                failResult("Changing that volume needs Do Not Disturb access for Prism.", needs = "policy_access")
            }
        },
        tool(
            "set_brightness",
            "Set screen brightness 0-100 (turns auto-brightness off).",
            Schema.obj(listOf("level")) { int("level", "0-100") },
        ) { a, ctx ->
            val level = a.argInt("level")?.coerceIn(0, 100) ?: return@tool failResult("Missing level")
            val raw = (level * 255 / 100f).toInt()
            if (Settings.System.canWrite(ctx.app)) {
                Settings.System.putInt(ctx.app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(ctx.app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
                okResult("Brightness $level%")
            } else if (ShizukuBridge.isReady()) {
                ShellCommands.explain(ShellCommands.brightness(raw), "Brightness")?.let { failResult(it) } ?: okResult("Brightness $level%")
            } else {
                failResult("Prism needs 'Modify system settings' (or Shizuku) to change brightness.", needs = "write_settings")
            }
        },
        tool(
            "auto_rotate",
            "Turn auto-rotate on or off.",
            Schema.obj(listOf("on")) { bool("on", "true = auto-rotate on") },
        ) { a, ctx ->
            val on = a.argBool("on") ?: true
            if (!Settings.System.canWrite(ctx.app)) return@tool failResult("Prism needs 'Modify system settings' to change rotation.", needs = "write_settings")
            Settings.System.putInt(ctx.app.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
            okResult(if (on) "Auto-rotate on" else "Rotation locked")
        },
        tool(
            "do_not_disturb",
            "Turn Do Not Disturb on or off.",
            Schema.obj(listOf("on")) { bool("on", "true = DND on") },
        ) { a, ctx ->
            val on = a.argBool("on") ?: true
            val nm = ctx.app.getSystemService(NotificationManager::class.java)
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                okResult(if (on) "Do Not Disturb on" else "Do Not Disturb off")
            } else if (ShizukuBridge.isReady()) {
                ShellCommands.explain(ShellCommands.dnd(if (on) "priority" else "off"), "DND")?.let { failResult(it) } ?: okResult(if (on) "Do Not Disturb on" else "Do Not Disturb off")
            } else {
                failResult("Prism needs Do Not Disturb access (Prism > Permissions).", needs = "policy_access")
            }
        },
        toggle("wifi", "Wi-Fi", graph, { ShellCommands.wifi(it) }, Settings.Panel.ACTION_WIFI),
        toggle("mobile_data", "Mobile data", graph, { ShellCommands.mobileData(it) }, Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
        toggle("bluetooth", "Bluetooth", graph, { ShellCommands.bluetooth(it) }, Settings.ACTION_BLUETOOTH_SETTINGS, publicApi = { ctx, on -> bluetoothPublic(ctx, on) }),
        toggle("airplane_mode", "Airplane mode", graph, { ShellCommands.airplane(it) }, Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        toggle("dark_mode", "Dark theme", graph, { ShellCommands.nightMode(it) }, Settings.ACTION_DISPLAY_SETTINGS),
        toggle("location", "Location", graph, { ShellCommands.location(it) }, Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        toggle("nfc", "NFC", graph, { ShellCommands.nfc(it) }, Settings.Panel.ACTION_NFC),
        toggle("battery_saver", "Battery saver", graph, { ShellCommands.batterySaver(it) }, Settings.ACTION_BATTERY_SAVER_SETTINGS),
        tool("lock_screen", "Turn the screen off / lock the phone (needs Shizuku).") { _, _ ->
            if (!ShizukuBridge.isReady()) return@tool failResult(ShizukuBridge.state.value.label, needs = "shizuku")
            ShellCommands.explain(ShellCommands.sleep(), "Lock")?.let { failResult(it) } ?: okResult("Screen off")
        },
        tool("device_status", "Battery, charging, Wi-Fi, Bluetooth, DND, volume and Shizuku status in one call.") { _, ctx ->
            ToolResult.data(status(ctx.app), "Status read")
        },
    )

    /** A Shizuku-backed switch with an honest fallback: open the matching Settings panel. */
    private fun toggle(
        name: String,
        label: String,
        graph: AppGraph,
        shell: suspend (Boolean) -> Result<ShellResult>,
        settingsAction: String,
        publicApi: ((Context, Boolean) -> String?)? = null,
    ): ToolHandler = tool(
        name,
        "Turn $label on or off. Works directly when Shizuku is ready; otherwise opens the $label settings panel for the user.",
        Schema.obj(listOf("on")) { bool("on", "true = on") },
    ) { a, ctx ->
        val on = a.argBool("on") ?: true
        publicApi?.invoke(ctx.app, on)?.let { if (it.isEmpty()) return@tool okResult("$label ${if (on) "on" else "off"}") }
        if (ShizukuBridge.isReady()) {
            ShellCommands.explain(shell(on), label)?.let { failResult(it, needs = "shizuku") } ?: okResult("$label ${if (on) "on" else "off"}")
        } else {
            val why = if (ShizukuBridge.state.value == ShizukuState.NOT_INSTALLED) "" else " (${ShizukuBridge.state.value.label})"
            ctx.app.launch(Intent(settingsAction))?.let { failResult(it) }
                ?: failResult("Android only lets the system flip $label; opened the $label panel so the user can tap it$why.", needs = "shizuku", userVisible = "Opened $label settings")
        }
    }

    /** Returns "" on success, null to fall through to Shizuku/panel, or a message on error. */
    @Suppress("DEPRECATION")
    private fun bluetoothPublic(ctx: Context, on: Boolean): String? {
        if (!ctx.granted(android.Manifest.permission.BLUETOOTH_CONNECT)) return null
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "No Bluetooth on this device"
        val ok = runCatching { if (on) adapter.enable() else adapter.disable() }.getOrDefault(false)
        return if (ok) "" else null
    }

    fun status(app: Context) = buildJsonObject {
        put("ok", true)
        val bm = app.getSystemService(BatteryManager::class.java)
        put("batteryPercent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        put("charging", bm.isCharging)
        put("wifiEnabled", runCatching { app.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrDefault(false))
        put("bluetoothEnabled", runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false }.getOrDefault(false))
        put("airplane", Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        val nm = app.getSystemService(NotificationManager::class.java)
        put("dnd", nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
        put("batterySaver", app.getSystemService(PowerManager::class.java).isPowerSaveMode)
        val am = app.getSystemService(AudioManager::class.java)
        put("mediaVolumePercent", am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        put("brightnessPercent", Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255)
        put("autoRotate", Settings.System.getInt(app.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1)
        put("shizuku", ShizukuBridge.refresh().name)
        put("flashlight", torch.isOn)
    }

    /** Torch state is remembered from the system callback so tiles show the truth. */
    class Torch {
        @Volatile var isOn: Boolean = false
            private set
        private var registered = false

        private fun ensure(app: Context) {
            if (registered) return
            registered = true
            val cm = app.getSystemService(CameraManager::class.java)
            runCatching {
                cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { isOn = enabled }
                }, null)
            }
        }

        fun set(app: Context, on: Boolean): String? {
            ensure(app)
            val cm = app.getSystemService(CameraManager::class.java)
            val id = runCatching {
                cm.cameraIdList.firstOrNull { cid ->
                    val ch = cm.getCameraCharacteristics(cid)
                    ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }
            }.getOrNull() ?: return "No flash on this device"
            return runCatching { cm.setTorchMode(id, on); isOn = on; null }.getOrElse { "Flashlight unavailable (camera in use?)" }
        }
    }
}
