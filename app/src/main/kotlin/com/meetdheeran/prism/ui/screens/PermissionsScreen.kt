package com.meetdheeran.prism.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.meetdheeran.prism.shizuku.ShizukuBridge
import com.meetdheeran.prism.shizuku.ShizukuState
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography

private class Perm(val title: String, val why: String, val granted: (Context) -> Boolean, val fix: (Context, (String) -> Unit) -> Unit)

/** Every optional permission, why it exists, its live status, and a one-tap fix. */
@Composable
fun PermissionsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    val shizuku by ShizukuBridge.state.collectAsState()
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { tick++; ShizukuBridge.refresh() } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    fun open(action: String, withPackage: Boolean = false) {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (withPackage) i.data = Uri.parse("package:${ctx.packageName}")
        runCatching { ctx.startActivity(i) }
    }
    val perms = remember {
        listOf(
            Perm("Display over other apps", "Lets the control center and the island draw above other apps.", { Settings.canDrawOverlays(it) }) { _, _ -> open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) },
            Perm("Notification access", "Only used to read what's playing (album art, title, controls) and ongoing call/timer activities for the island. No summaries, no replies.", { NotificationManagerCompat.getEnabledListenerPackages(it).contains(it.packageName) }) { _, _ -> open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            Perm("Microphone", "Voice input for the assistant.", { ContextCompat.checkSelfPermission(it, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }) { _, req -> req(Manifest.permission.RECORD_AUDIO) },
            Perm("Modify system settings", "Brightness and auto-rotate tiles.", { Settings.System.canWrite(it) }) { _, _ -> open(Settings.ACTION_MANAGE_WRITE_SETTINGS, true) },
            Perm("Do Not Disturb access", "The DND tile and ring/notification volume.", { it.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }) { _, _ -> open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
            Perm("Bluetooth", "Toggle Bluetooth directly instead of via Shizuku.", { ContextCompat.checkSelfPermission(it, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED }) { _, req -> req(Manifest.permission.BLUETOOTH_CONNECT) },
            Perm("Calendar", "Read upcoming events; new events open the calendar app for you to save.", { ContextCompat.checkSelfPermission(it, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED }) { _, req -> req(Manifest.permission.READ_CALENDAR) },
            Perm("Contacts", "Look up numbers when you say a name.", { ContextCompat.checkSelfPermission(it, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED }) { _, req -> req(Manifest.permission.READ_CONTACTS) },
            Perm("Ignore battery optimisation", "Keeps the island and control center alive in the background on OxygenOS.", { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) }) { _, _ -> open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true) },
            Perm("Default digital assistant", "Makes the assist gesture (swipe from a bottom corner / long-press home) open Prism with screen context.", { Settings.Secure.getString(it.contentResolver, "assistant")?.startsWith(it.packageName) == true }) { c, _ -> openDefaultAssistantPicker(c) },
        )
    }

    ScreenScaffold("Permissions", onBack = { nav.up() }) { backdrop ->
        GlassGroup(backdrop, "Optional — each unlocks one feature") {
            perms.forEachIndexed { i, p ->
                if (i > 0) GlassDivider()
                val ok = remember(tick) { p.granted(ctx) }
                SettingRow(p.title, subtitle = p.why, value = if (ok) "On" else "Off", chevron = !ok, onClick = if (ok) null else ({ p.fix(ctx) { perm -> runtime.launch(perm) } }))
            }
        }
        GlassGroup(backdrop, "Shizuku", footer = "Non-root Shizuku must be started again after every reboot (Shizuku app → Start via wireless debugging, or the PC script tools/shizuku-start.ps1). It unlocks Wi-Fi/data/airplane/night-mode toggles, screenshots and screen reading.") {
            SettingRow(
                "Status", subtitle = shizuku.label,
                value = when (shizuku) { ShizukuState.READY -> "Ready"; ShizukuState.NO_PERMISSION -> "Allow"; ShizukuState.NOT_RUNNING -> "Start"; ShizukuState.NOT_INSTALLED -> "Install" },
                chevron = shizuku != ShizukuState.READY,
                onClick = if (shizuku == ShizukuState.READY) null else ({ if (shizuku == ShizukuState.NO_PERMISSION) ShizukuBridge.requestPermission() else ShizukuBridge.openShizukuApp(ctx) }),
            )
        }
        Text("Nothing here is required. Features without their permission simply show what's missing.", style = PrismTypography.bodySmall, color = PrismColors.TextTertiary, modifier = Modifier.padding(horizontal = 16.dp))
    }
}
