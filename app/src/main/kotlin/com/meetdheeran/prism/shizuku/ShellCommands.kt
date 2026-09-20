package com.meetdheeran.prism.shizuku

/**
 * Typed wrappers over the Android 12 shell commands a uid-2000 process may run
 * (verified against android12-release sources in docs/research/shell-controls.md).
 * Every call returns the raw [ShellResult] so callers can show stderr when something fails.
 */
object ShellCommands {
    private fun onOff(on: Boolean) = if (on) "enable" else "disable"

    suspend fun wifi(on: Boolean) = ShizukuBridge.exec("svc wifi ${onOff(on)}")
    suspend fun mobileData(on: Boolean) = ShizukuBridge.exec("svc data ${onOff(on)}")
    suspend fun bluetooth(on: Boolean) = ShizukuBridge.exec("svc bluetooth ${onOff(on)}")
    suspend fun nfc(on: Boolean) = ShizukuBridge.exec("svc nfc ${onOff(on)}")

    /** The only way that also fires the protected AIRPLANE_MODE_CHANGED broadcast on 12. */
    suspend fun airplane(on: Boolean) = ShizukuBridge.exec("cmd connectivity airplane-mode ${onOff(on)}")

    suspend fun nightMode(on: Boolean) = ShizukuBridge.exec("cmd uimode night ${if (on) "yes" else "no"}")
    suspend fun location(on: Boolean) = ShizukuBridge.exec("cmd location set-location-enabled $on")
    suspend fun batterySaver(on: Boolean) = ShizukuBridge.exec("cmd power set-mode ${if (on) 1 else 0}")

    /** mode: on | off | none | priority | alarms | all */
    suspend fun dnd(mode: String) = ShizukuBridge.exec("cmd notification set_dnd $mode")

    suspend fun sleep() = ShizukuBridge.exec("input keyevent KEYCODE_SLEEP")
    suspend fun wake() = ShizukuBridge.exec("input keyevent KEYCODE_WAKEUP")
    suspend fun expandNotifications() = ShizukuBridge.exec("cmd statusbar expand-notifications")
    suspend fun expandQuickSettings() = ShizukuBridge.exec("cmd statusbar expand-settings")
    suspend fun collapseStatusBar() = ShizukuBridge.exec("cmd statusbar collapse")

    /** 0..255; switches auto-brightness off first so the value sticks. */
    suspend fun brightness(value: Int) = ShizukuBridge.exec(
        "settings put system screen_brightness_mode 0 && settings put system screen_brightness ${value.coerceIn(0, 255)}",
    )

    suspend fun mediaKey(key: String) = ShizukuBridge.exec("cmd media_session dispatch $key")

    /** "Wifi is enabled" / "Wifi is disabled" (+ SSID line) on Android 12. */
    suspend fun wifiStatus(): Result<String> = ShizukuBridge.exec("cmd wifi status").map { it.stdout.trim() }

    /** One-time grants that outlive Shizuku: let Prism write settings / DND without Shizuku afterwards. */
    suspend fun grantSelfWriteSettings(pkg: String) = ShizukuBridge.exec("appops set $pkg WRITE_SETTINGS allow")
    suspend fun grantSelfSecureSettings(pkg: String) = ShizukuBridge.exec("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
    suspend fun grantSelfDnd(pkg: String) = ShizukuBridge.exec("cmd notification allow_dnd $pkg")

    /** Human-readable failure line for a toggle attempt. */
    fun explain(r: Result<ShellResult>, what: String): String? {
        val res = r.getOrElse { return "$what: ${it.message ?: "Shizuku error"}" }
        if (res.ok) return null
        val detail = (res.stderr.ifBlank { res.stdout }).lines().firstOrNull { it.isNotBlank() }?.take(140)
        return "$what failed (exit ${res.exitCode})" + (detail?.let { ": $it" } ?: "")
    }
}
