package com.meetdheeran.prism.control

import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.meetdheeran.prism.MainActivity
import com.meetdheeran.prism.assistant.AssistantLauncher
import com.meetdheeran.prism.actions.DeviceTools
import com.meetdheeran.prism.ai.ToolContext
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.shizuku.ShellCommands
import com.meetdheeran.prism.shizuku.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Reads real device state for every tile and performs the toggles. Toggles are routed through
 * the same ToolHandlers the assistant uses, so the panel and the voice assistant never disagree
 * about what works and what needs Shizuku.
 */
class TileControl(private val ctx: Context, private val scope: CoroutineScope) {
    private val graph = AppGraph.get(ctx)
    val on = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val brightness = MutableStateFlow(0.5f)
    val volume = MutableStateFlow(0.5f)
    val message = MutableStateFlow<String?>(null)
    private var brightnessJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refresh() }
    }

    fun start() {
        val f = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    fun stop() { runCatching { ctx.unregisterReceiver(receiver) } }

    fun refresh() {
        scope.launch(Dispatchers.Default) {
            val cr = ctx.contentResolver
            val map = HashMap<String, Boolean>()
            map["wifi"] = runCatching { ctx.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrDefault(false)
            map["data"] = runCatching { Settings.Global.getInt(cr, "mobile_data", 1) == 1 }.getOrDefault(false)
            map["bluetooth"] = runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false }.getOrDefault(false)
            map["airplane"] = Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            map["flashlight"] = DeviceTools.torch.isOn
            map["rotation"] = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
            map["dnd"] = ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
            map["battery_saver"] = ctx.getSystemService(PowerManager::class.java).isPowerSaveMode
            map["dark_mode"] = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES ||
                ctx.getSystemService(UiModeManager::class.java).nightMode == UiModeManager.MODE_NIGHT_YES
            map["location"] = runCatching { ctx.getSystemService(LocationManager::class.java).isLocationEnabled }.getOrDefault(false)
            map["nfc"] = runCatching { NfcAdapter.getDefaultAdapter(ctx)?.isEnabled ?: false }.getOrDefault(false)
            on.value = map
            brightness.value = (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f).coerceIn(0f, 1f)
            val am = ctx.getSystemService(AudioManager::class.java)
            volume.value = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }
    }

    private val toolNames = mapOf(
        "wifi" to "wifi", "data" to "mobile_data", "bluetooth" to "bluetooth", "airplane" to "airplane_mode", "flashlight" to "flashlight",
        "rotation" to "auto_rotate", "dnd" to "do_not_disturb", "battery_saver" to "battery_saver", "dark_mode" to "dark_mode",
        "location" to "location", "nfc" to "nfc",
    )

    fun toggle(id: String) {
        val current = on.value[id] ?: false
        val tool = toolNames[id]?.let { graph.tools[it] } ?: return
        // Optimistic flip so the tile answers the finger immediately; refresh() corrects it.
        on.value = on.value + (id to !current)
        scope.launch {
            val r = withContext(Dispatchers.IO) { tool.execute(buildJsonObject { put("on", !current) }, ToolContext(ctx) {}) }
            val ok = r.json["ok"]?.toString() == "true"
            if (!ok) message.value = r.userVisible ?: "Couldn't change ${id.replace('_', ' ')}"
            delay(350)
            refresh()
        }
    }

    fun setBrightness(f: Float) {
        val v = f.coerceIn(0f, 1f)
        brightness.value = v
        val raw = (v * 255).toInt()
        if (Settings.System.canWrite(ctx)) {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
        } else if (ShizukuBridge.isReady()) {
            brightnessJob?.cancel()
            brightnessJob = scope.launch { delay(80); ShellCommands.brightness(raw) }
        } else {
            message.value = "Brightness needs 'Modify system settings' (Prism > Permissions)"
        }
    }

    fun setVolume(f: Float) {
        val v = f.coerceIn(0f, 1f)
        volume.value = v
        val am = ctx.getSystemService(AudioManager::class.java)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, (v * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), 0) }
    }

    /** One-shot tiles. Returns immediately; feedback goes through [message]. */
    fun action(id: String, closePanel: () -> Unit) {
        when (id) {
            "screenshot" -> scope.launch {
                closePanel()
                delay(280)
                ShizukuBridge.screenshotPng().onSuccess { png ->
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "Prism_${System.currentTimeMillis()}.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Prism")
                    }
                    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(png) }
                    message.value = "Screenshot saved to Pictures/Prism"
                }.onFailure { message.value = "Screenshot needs Shizuku: ${it.message}" }
            }
            "lock" -> scope.launch { closePanel(); delay(200); ShellCommands.sleep().onFailure { message.value = "Lock needs Shizuku" } }
            "assistant" -> scope.launch { closePanel(); delay(280); AssistantLauncher.open(ctx) }
            "camera" -> { closePanel(); launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
            "calculator" -> { closePanel(); if (!launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR))) ctx.packageManager.getLaunchIntentForPackage("com.oneplus.calculator")?.let { launch(it) } }
            "settings" -> { closePanel(); launch(Intent(Settings.ACTION_SETTINGS)) }
            "hotspot" -> { closePanel(); if (!launch(Intent("android.settings.TETHER_SETTINGS"))) launch(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }
    }

    private fun launch(i: Intent): Boolean = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
}
