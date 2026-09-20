# Shell controls on Android 12 (shell uid via Shizuku) and public-API alternatives

Research date: 2026-09-20. Target: sideloaded Kotlin + Jetpack Compose app, OnePlus 7 (GM1900), Android 12 / OxygenOS 12 (API 31), non-root, Shizuku 13.6.0 running as shell (uid 2000, package identity `com.android.shell`).
Toolchain: Kotlin 2.0.20, AGP 8.5.2, Gradle 8.7, compileSdk 34, minSdk 31, Compose BOM 2024.09.03, OkHttp 4.12, kotlinx-serialization 1.7.3.

Every command below was verified by downloading the **`android12-release`** branch source from android.googlesource.com (raw `?format=TEXT`, base64-decoded, grepped) on 2026-09-20. Public-API facts were read from the same branch's framework sources plus developer.android.com where noted. Anything I could not open is marked **UNVERIFIED**. OxygenOS-specific behaviour is never verifiable from AOSP and is called out as such.

How the commands are executed from the app (UserService as uid 2000, `Runtime.exec`) is documented in `docs/research/shizuku-client.md`; this document only covers *what* to run and *what to do when you cannot run anything*.

---

## 0. TL;DR and corrections to the brief

| Brief assumed | Verified reality on `android12-release` | Source |
|---|---|---|
| `svc wifi enable/disable` is a Java svc subcommand | `svc` is a **shell script** on 12. `svc wifi enable` is rewritten to `exec cmd wifi set-wifi-enabled enabled`; `svc data enable` to `exec cmd phone data enable`. The Java `WifiCommand.java`/`DataCommand.java` no longer exist (HTTP 404 on the branch). | [cmds/svc/svc](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/cmds/svc/svc) |
| `svc` Java command list | `Svc.java` registers only `help`, `power`, `usb`, `nfc`, `bluetooth`, `system_server`. | [Svc.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/cmds/svc/src/com/android/commands/svc/Svc.java) |
| `cmd bluetooth_manager enable` exists on 12 | **No.** `BluetoothManagerService.java` on 12 has no `onShellCommand`/ShellCommand class at all. The `BluetoothShellCommand` (`enable`/`disable`, prints "Enabling Bluetooth") lives in `packages/modules/Bluetooth` on **android13-release**. On 12 use `svc bluetooth enable|disable` (calls `BluetoothAdapter.getDefaultAdapter().enable()/disable()`). | [BluetoothManagerService.java (12)](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/BluetoothManagerService.java) ; [BluetoothShellCommand.java (13)](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android13-release/service/java/com/android/server/bluetooth/BluetoothShellCommand.java) |
| `cmd connectivity airplane-mode enable|disable` exists on 12 | **Yes.** Inner class `ConnectivityService.ShellCmd`; with no argument it prints `enabled`/`disabled`. Calls `setAirplaneMode()`, which writes `Settings.Global.AIRPLANE_MODE_ON` **and** broadcasts `ACTION_AIRPLANE_MODE_CHANGED` with `state` extra to all users. | [ConnectivityService.java L8923-8970, L5529](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android12-release/service/src/com/android/server/ConnectivityService.java) |
| `settings put global airplane_mode_on 1` alone works | It only flips the key. Wi‑Fi (`ActiveModeWarden`) and Bluetooth react to the **broadcast** `Intent.ACTION_AIRPLANE_MODE_CHANGED`, and `am broadcast -a android.intent.action.AIRPLANE_MODE` from uid 2000 throws `SecurityException: Permission Denial: not allowed to send broadcast` because shell is not in AMS's `isCallerSystem` list for protected broadcasts. Always use `cmd connectivity airplane-mode`. | [ActiveModeWarden.java L511-518](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android12-release/service/java/com/android/server/wifi/ActiveModeWarden.java) ; [ActivityManagerService.java L12880-12906](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/am/ActivityManagerService.java) |
| `cmd notification set_dnd` exists on 12 | **Yes.** Accepts `on|none|priority|alarms|all|off`; hard-gated to uid 0/2000 (`checkShellCommandPermission`, returns 255 otherwise). | [NotificationShellCmd.java L125-184](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/notification/NotificationShellCmd.java) |
| `cmd location set-location-enabled true|false` | Correct syntax on 12 is `set-location-enabled true|false [--user <USER_ID>]` (boolean first, then option). Read with `is-location-enabled [--user N]`. | [LocationShellCommand.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/location/LocationShellCommand.java) |
| `cmd power set-mode` vs `settings put global low_power 1` | `cmd power set-mode 1|0` calls `PowerManagerService.setPowerSaveModeEnabled()` (needs `POWER_SAVER` or `DEVICE_POWER`; shell has both). Prefer it over the raw `low_power` key. | [PowerManagerShellCommand.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/power/PowerManagerShellCommand.java) ; [PowerManagerService.java L5391](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/power/PowerManagerService.java) |
| `input` is a Java binary | On 12 `/system/bin/input` is a 2-line script: `cmd input "$@"`. Handler is `InputShellCommand` in system_server. `settings` is likewise `cmd settings "$@"`. | [cmds/input/input](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/cmds/input/input) ; [cmds/settings/settings](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/cmds/settings/settings) |
| Night mode is togglable by a normal app via `UiModeManager.setNightMode` | **No on AOSP 12**: `config_lockDayNightMode` defaults to `true`, so `setNightMode` silently returns unless caller holds `MODIFY_DAY_NIGHT_MODE` (signature). Shell has it; use `cmd uimode night yes|no`. `setApplicationNightMode` (API 31) only affects your own app. | [config.xml L745-748](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/res/res/values/config.xml) ; [UiModeManagerService.java L704-710](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/UiModeManagerService.java) |
| Brightness via `settings put system screen_brightness N` | Still works on 12: `BrightnessSynchronizer` observes the `screen_brightness` URI and pushes `DisplayManager.setBrightness(DEFAULT_DISPLAY, float)`. Manual mode must be on (`screen_brightness_mode 0`) for the value to be visible. | [BrightnessSynchronizer.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/com/android/internal/display/BrightnessSynchronizer.java) |

---

## 1. What uid 2000 is allowed to do (Shell package manifest, android12-release)

Every `cmd …` handler enforces a framework permission on the *caller's uid*. Shizuku runs your UserService as uid 2000 whose package is `com.android.shell`. The permissions that matter, all declared in [packages/Shell/AndroidManifest.xml](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/packages/Shell/AndroidManifest.xml):

| Needed by | Permission (line in manifest) |
|---|---|
| `cmd wifi set-wifi-enabled`, `cmd connectivity airplane-mode` | `NETWORK_SETTINGS` (L247), `CHANGE_WIFI_STATE` (L248), `ACCESS_WIFI_STATE` (L119) |
| `svc bluetooth` | `BLUETOOTH_CONNECT` (L123), `BLUETOOTH_ADMIN` (L121), `BLUETOOTH_PRIVILEGED`-adjacent `BLUETOOTH_STACK` (L212) |
| `svc nfc`, `cmd location set-location-enabled`, `settings put secure/global` | `WRITE_SECURE_SETTINGS` (L140) |
| `settings put system …`, `svc power stayon` | `WRITE_SETTINGS` (L139) |
| `cmd statusbar expand-*` / `collapse` | `EXPAND_STATUS_BAR` (L126), `STATUS_BAR` (L256), `STATUS_BAR_SERVICE` (L255) |
| `cmd uimode night` | `MODIFY_DAY_NIGHT_MODE` (L351) |
| `cmd power set-mode` | `POWER_SAVER` (L181), `DEVICE_POWER` (L180) |
| `cmd notification set_dnd` | `MANAGE_NOTIFICATIONS` (L382), `ACCESS_NOTIFICATION_POLICY` (L110) |
| `input keyevent` | `INJECT_EVENTS` (L158) |
| `screencap` | `READ_FRAME_BUFFER` (L178) |
| `cmd media_session dispatch` | `MEDIA_CONTENT_CONTROL` (L556) |
| `pm grant` | `GRANT_RUNTIME_PERMISSIONS` (L188), `REVOKE_RUNTIME_PERMISSIONS` (L189) |
| `appops set` | `MANAGE_APP_OPS_MODES` (L230) |
| `cmd phone data` | `MODIFY_PHONE_STATE` (L223) |
| `dumpsys battery` | `DUMP` (L149) |
| `cmd wifi status` SSID | `ACCESS_FINE_LOCATION` (L42) |

The `cmd` transport itself has no uid gate; the receiving service decides. `SettingsService.resolveCallingPackage()` maps uid 2000 to `"com.android.shell"` and root to `"root"` ([SettingsService.java L443-455](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/packages/SettingsProvider/src/com/android/providers/settings/SettingsService.java)).

---

## 2. Shell commands, per tile (Android 12, uid 2000)

Conventions: exit code 0 = handled; ShellCommand handlers print help and return -1 on bad args. Most set-commands print **nothing** on success. Where a command prints something, it is quoted from source.

### 2.1 Wi‑Fi

```
cmd wifi set-wifi-enabled enabled|disabled     # preferred; svc wifi enable|disable is the same thing
cmd wifi status
```

* Handler: `WifiShellCommand` (packages/modules/Wifi). `set-wifi-enabled` is in `NON_PRIVILEGED_COMMANDS` (allowed for non-root); commands *not* in that list throw `SecurityException("Uid 2000 does not have access to <cmd> wifi command (or such command doesn't exist)")`. Non-privileged list on 12: `add-suggestion, add-network, connect-network, forget-network, get-country-code, help, -h, is-verbose-logging, list-scan-results, list-networks, list-suggestions, remove-suggestion, remove-all-suggestions, reset-connected-score, set-connected-score, set-scan-always-available, set-verbose-logging, set-wifi-enabled, start-scan, start-softap, status, stop-softap`. Source: [WifiShellCommand.java L115-138, L225-238, L525-528](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android12-release/service/java/com/android/server/wifi/WifiShellCommand.java).
* Argument parser is `getNextArgRequiredTrueOrFalse("enabled", "disabled")` — the words are literally `enabled`/`disabled`, not `true`/`false`/`on`/`off`.
* It calls `WifiServiceImpl.setWifiEnabled("com.android.shell", enabled)`. Because uid 2000 holds `NETWORK_SETTINGS`, `isPrivileged` is true, so the two blockers for normal apps ("only Settings can toggle wifi" in airplane mode, and while SoftAP is up) do not apply ([WifiServiceImpl.java L885-935](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android12-release/service/java/com/android/server/wifi/WifiServiceImpl.java)).
* Output of `cmd wifi status` (verbatim format, [L1445-1458, L1427-1441](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android12-release/service/java/com/android/server/wifi/WifiShellCommand.java)):
  ```
  Wifi is enabled|disabled
  Wifi scanning is always available|only available when wifi is enabled
  ==== Primary ClientModeManager instance ====
  Wifi is not connected                       <- or:
  Wifi is connected to "MySSID"
  WifiInfo: SSID: ..., BSSID: ..., ...
  successfulTxPackets: N
  ...
  ```
  Parse the first line with regex `^Wifi is (enabled|disabled)`; the SSID line is `^Wifi is connected to (.+)$` (SSID is quoted).
* Hotspot: `cmd wifi start-softap <ssid> (open|wpa2|wpa3|wpa3_transition) <passphrase> [-b 2|5|6|any]` and `cmd wifi stop-softap`. Help text warns: "the shell command doesn't activate internet tethering" — it starts a bare AP, not the Settings hotspot with tethering ([L1602-1616](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android12-release/service/java/com/android/server/wifi/WifiShellCommand.java)). For a real hotspot, open `Settings.ACTION_WIRELESS_SETTINGS` / the tethering screen instead.
* Pitfall: `svc wifi` on 12 is just the script above, so "svc wifi is a no-op on some OEMs" collapses to "OEM's WifiServiceImpl may refuse"; on OxygenOS 12 the Wi‑Fi mainline module is the AOSP one (**UNVERIFIED** for OnePlus specifically).

### 2.2 Mobile data

```
cmd phone data enable
cmd phone data disable          # svc data enable|disable execs exactly these
```

* Handler `TelephonyShellCommand` (`packages/services/Telephony`), subcommand constant `DATA_TEST_MODE = "data"`, help text "data enable: enable mobile data connectivity". Calls `ITelephony.enableDataConnectivity()/disableDataConnectivity()`; on `RemoteException` prints `Exception: <msg>` to stderr and returns -1 ([TelephonyShellCommand.java L93-96, L516-521, L690-723](https://android.googlesource.com/platform/packages/services/Telephony/+/refs/heads/android12-release/src/com/android/phone/TelephonyShellCommand.java)). Uid 2000 has `MODIFY_PHONE_STATE`.
* Read state: `settings get global mobile_data` (key `Settings.Global.MOBILE_DATA = "mobile_data"`, [Settings.java L11070](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/provider/Settings.java)) — prints `1`/`0`/`null`. Dual-SIM devices may use `mobile_data<subId>`; the public API `TelephonyManager.isDataEnabled()` (needs `ACCESS_NETWORK_STATE` or `MODIFY_PHONE_STATE`/`READ_PHONE_STATE`) is the safer reader from the app.
* Public write alternative: none (`setDataEnabled` is carrier/system only). Fallback intent: `Settings.ACTION_DATA_ROAMING_SETTINGS = "android.settings.DATA_ROAMING_SETTINGS"` or `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`.

### 2.3 Bluetooth

```
svc bluetooth enable
svc bluetooth disable
```

* `BluetoothCommand.run()` calls `BluetoothAdapter.getDefaultAdapter()` then `adapter.enable()` / `adapter.disable()`; prints `Got a null BluetoothAdapter, is the system running?` if the service is missing, otherwise prints nothing ([BluetoothCommand.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/cmds/svc/src/com/android/commands/svc/BluetoothCommand.java)).
* With no `Context`, `BluetoothManager.resolveAttributionSource(null)` builds `AttributionSource(2000, "com.android.shell")` from `getPackagesForUid` ([BluetoothManager.java L77-93](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/bluetooth/BluetoothManager.java)). `BluetoothManagerService.enable()` then requires: not disallowed by user restriction, `checkPackage(uid, pkg)`, caller in foreground user (uid 2000 is user 0), and `BLUETOOTH_CONNECT` for `com.android.shell` ([BluetoothManagerService.java L939-963, L1084-1115](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/BluetoothManagerService.java)). `config_wirelessConsentRequired` is `false` on AOSP, so no consent dialog ([config.xml L3704](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/res/res/values/config.xml)).
* **UNVERIFIED**: whether OxygenOS keeps `BLUETOOTH_CONNECT` (a runtime permission) granted to `com.android.shell`. On AOSP `adb shell svc bluetooth enable` works on 12; treat a silent no-op as this check failing and fall back to `BluetoothAdapter.ACTION_REQUEST_ENABLE` from the app.
* No `cmd bluetooth_manager` on 12 (see section 0). Do not ship that string for API 31.
* Read: `settings get global bluetooth_on` (`0`/`1`/`2` where 2 = `BLUETOOTH_ON_AIRPLANE`), or better the public `BluetoothAdapter.isEnabled()`.

### 2.4 NFC

```
svc nfc enable
svc nfc disable
```

* `NfcCommand.run()` gets `INfcAdapter` via `ServiceManager.getService("nfc")` and calls `enable()` / `disable(true)`; errors: `Got a null NfcAdapter, is the system running?`, `NFC operation failed: <e>` ([NfcCommand.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/cmds/svc/src/com/android/commands/svc/NfcCommand.java)).
* `NfcService.NfcAdapterService.enable()` enforces `NfcPermissions.enforceAdminPermissions` = `WRITE_SECURE_SETTINGS` ([NfcService.java L1097-1116](https://android.googlesource.com/platform/packages/apps/Nfc/+/refs/heads/android12-release/src/com/android/nfc/NfcService.java) ; [NfcPermissions.java L12, L43-45](https://android.googlesource.com/platform/packages/apps/Nfc/+/refs/heads/android12-release/src/com/android/nfc/NfcPermissions.java)). Shell has it.
* There is no `cmd nfc` on 12 (NFC is `packages/apps/Nfc`, not a ShellCommand service). Public API `NfcAdapter.enable()` is `@SystemApi @hide` ([NfcAdapter.java L945-949](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/nfc/NfcAdapter.java)).

### 2.5 Airplane mode

```
cmd connectivity airplane-mode enable
cmd connectivity airplane-mode disable
cmd connectivity airplane-mode              # prints "enabled" or "disabled"
```

* Verbatim help: `Connectivity service commands: / help / airplane-mode [enable|disable] — Turn airplane mode on or off. / airplane-mode — Get airplane mode.` ([ConnectivityService.java L8923-8970](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android12-release/service/src/com/android/server/ConnectivityService.java)).
* `setAirplaneMode(boolean)` = `enforceAirplaneModePermission()` (any of `NETWORK_AIRPLANE_MODE`, `NETWORK_SETTINGS`, `NETWORK_SETUP_WIZARD`, `NETWORK_STACK`, mainline network stack) + `Settings.Global.putInt(AIRPLANE_MODE_ON)` + `sendBroadcastAsUser(ACTION_AIRPLANE_MODE_CHANGED with "state", UserHandle.ALL)` ([L2674-2681, L5529-5541](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android12-release/service/src/com/android/server/ConnectivityService.java)).
* Do **not** substitute `settings put global airplane_mode_on 1` + `am broadcast`: the setting alone changes nothing (Wi‑Fi/BT listen for the broadcast), and shell cannot send that protected broadcast (section 0).
* Read: `settings get global airplane_mode_on` or public `Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0)`.

### 2.6 Location (master switch)

```
cmd location set-location-enabled true|false [--user <USER_ID>]
cmd location is-location-enabled [--user <USER_ID>]
```

* Parser: first positional is `Boolean.parseBoolean(...)` (anything other than `true` is false), then optional `--user`; default user `USER_CURRENT_OR_SELF`. Calls `LocationManagerService.setLocationEnabledForUser`, which enforces `WRITE_SECURE_SETTINGS` ([LocationShellCommand.java L126-144](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/location/LocationShellCommand.java) ; [LocationManagerService.java L1217-1225](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/location/LocationManagerService.java)).
* `is-location-enabled` prints `true`/`false`.

### 2.7 Brightness, auto-brightness, rotation lock, screen timeout

```
settings put system screen_brightness_mode 0        # 0 = manual, 1 = automatic
settings put system screen_brightness 128           # 0..255
settings get system screen_brightness
settings put system accelerometer_rotation 0|1       # 0 = rotation locked
settings put system user_rotation 0|1|2|3            # Surface.ROTATION_* used when locked
settings put system screen_off_timeout 30000         # ms
```

* Help verbatim: `put [--user <USER_ID> | current] NAMESPACE KEY VALUE [TAG] [default]`, `get [--user <USER_ID> | current] NAMESPACE KEY`, NAMESPACE one of `system, secure, global` (case-insensitive) ([SettingsService.java L472-488](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/packages/SettingsProvider/src/com/android/providers/settings/SettingsService.java)). `get` prints the value or `null`.
* Keys: `SCREEN_BRIGHTNESS = "screen_brightness"`, `SCREEN_BRIGHTNESS_MODE = "screen_brightness_mode"` (MANUAL=0, AUTOMATIC=1), hidden `SCREEN_BRIGHTNESS_FLOAT = "screen_brightness_float"`, `ACCELEROMETER_ROTATION = "accelerometer_rotation"`, `USER_ROTATION = "user_rotation"`, `SCREEN_OFF_TIMEOUT = "screen_off_timeout"` ([Settings.java L4274-4327, L4819-4830](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/provider/Settings.java)).
* On 12 the display actually consumes a float; `BrightnessSynchronizer` registers a content observer on the `screen_brightness` URI and calls `DisplayManager.setBrightness(Display.DEFAULT_DISPLAY, float)`, and writes the int back when the float changes ([BrightnessSynchronizer.java L46-47, L162, L229-232, L275-290](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/com/android/internal/display/BrightnessSynchronizer.java)). So writing the int key is still the correct shell path. Writing while `screen_brightness_mode=1` is accepted but auto-brightness overrides it visually.
* Pitfall: `settings put system` from uid 2000 is a *system* write via `com.android.shell` which holds `WRITE_SETTINGS`; nothing else needed. Multi-user: add `--user current`.

### 2.8 Battery saver

```
cmd power set-mode 1        # low power on
cmd power set-mode 0        # off
```

* Help verbatim: `set-mode MODE — sets the power mode of the device to MODE. 1 turns low power mode on and 0 turns low power mode off.` Parsed with `Integer.parseInt`, anything non-numeric prints `Error: …` and returns -1 ([PowerManagerShellCommand.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/power/PowerManagerShellCommand.java)).
* Also available: `set-adaptive-power-saver-enabled true|false`.
* `settings put global low_power 1` writes `Settings.Global.LOW_POWER_MODE = "low_power"` ([Settings.java L13811](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/provider/Settings.java)); `BatterySaverStateMachine` observes it, but `cmd power set-mode` goes through the same `setLowPowerModeInternal` path and is the documented interface. Use `cmd power`.
* Read: public `PowerManager.isPowerSaveMode()`.

### 2.9 Dark theme

```
cmd uimode night yes|no|auto|custom
cmd uimode night            # read
```

* Prints `Night mode: yes|no|auto|custom|unknown` after setting or when reading ([UiModeManagerService.java L1843-1944](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/UiModeManagerService.java)). Bad arg: stderr `Error: mode must be 'yes', 'no', or 'auto', or 'custom'`, return -1.
* Persisted key: `Settings.Secure.UI_NIGHT_MODE = "ui_night_mode"` (1=no, 2=yes, 0=auto, 3=custom) ([Settings.java L8679](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/provider/Settings.java)). Prefer the `cmd` so the configuration change is dispatched.

### 2.10 Do Not Disturb

```
cmd notification set_dnd on|none|priority|alarms|all|off
```

* Mapping in source: `none`/`on` → `INTERRUPTION_FILTER_NONE` (3); `priority` → 2; `alarms` → 4; `all`/`off` → `INTERRUPTION_FILTER_ALL` (1). Unknown word → `INTERRUPTION_FILTER_UNKNOWN` → `setInterruptionFilter` throws `IllegalArgumentException("Invalid filter")`. Calls `INotificationManager.setInterruptionFilter("com.android.shell", filter)`; `enforcePolicyAccess` passes immediately because the caller holds `MANAGE_NOTIFICATIONS` ([NotificationShellCmd.java L164-184](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/notification/NotificationShellCmd.java) ; [NotificationManagerService.java L4876-4970](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/notification/NotificationManagerService.java)).
* Note the command accepts underscores or dashes (`cmd.replace('-', '_')`), so `set-dnd` also works.
* Read: `settings get global zen_mode` (`ZEN_MODE = "zen_mode"`, [Settings.java L14277](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/provider/Settings.java); 0 off, 1 important, 2 none, 3 alarms) or public `NotificationManager.getCurrentInterruptionFilter()`.

### 2.11 Notification shade / quick settings

```
cmd statusbar expand-notifications
cmd statusbar expand-settings
cmd statusbar collapse
cmd statusbar click-tile <COMPONENT>      # only for TileService tiles
```

* Help verbatim: `expand-notifications — Open the notifications panel.`, `expand-settings — Open the notifications panel and expand quick settings if present.`, `collapse — Collapse the notifications and settings panel.` ([StatusBarShellCommand.java L113-126 + onHelp](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/statusbar/StatusBarShellCommand.java)).
* `expandNotificationsPanel`/`expandSettingsPanel` enforce `EXPAND_STATUS_BAR`; `collapsePanels` goes through `checkCanCollapseStatusBar` which, for targetSdk ≥ S callers, enforces `STATUS_BAR` — shell holds both ([StatusBarManagerService.java L657-713, L1175-1212](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/statusbar/StatusBarManagerService.java)).
* Nothing is printed. **UNVERIFIED** on OxygenOS: OnePlus ships its own SystemUI; the IStatusBar callbacks should still exist but behaviour when the keyguard is showing is OEM-dependent.

### 2.12 Screen off / wake / power key / media keys

```
input keyevent KEYCODE_SLEEP          # 223 — screen off without power-menu semantics
input keyevent KEYCODE_WAKEUP         # 224
input keyevent KEYCODE_POWER          # 26 — toggles like the hardware key
input keyevent --longpress KEYCODE_POWER
input keyevent KEYCODE_MEDIA_PLAY_PAUSE   # 85 ; MEDIA_NEXT 87 ; MEDIA_PREVIOUS 88
input keyevent 223                    # numeric form accepted
```

* Help verbatim: `keyevent [--longpress|--doubletap] <key code number or name> ... (Default: keyboard)`. Multiple key codes on one line are sent in sequence. Names are resolved by `KeyEvent.keyCodeFromString`, which accepts a number or a name with or without the `KEYCODE_` prefix ([InputShellCommand.java L215-243, L285-318](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/input/InputShellCommand.java) ; [KeyEvent.java L894, L3064-3076](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/view/KeyEvent.java)).
* Injection: `InputManager.injectInputEvent(event, INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH)` — requires `INJECT_EVENTS` (shell has it). Prints nothing.
* Pitfall: `KEYCODE_POWER` from a script is racy (screen may be off/on in either order); use `KEYCODE_SLEEP` to turn off and `KEYCODE_WAKEUP` to turn on. There is no public "turn screen off" API without a `DevicePolicyManager.lockNow()` device admin.

### 2.13 Media playback control

```
cmd media_session dispatch play|pause|play-pause|next|previous|stop|mute|headsethook|rewind|record|fast-forward
cmd media_session list-sessions
```

* Help verbatim (note the typo): `media_session dispatch: dispatch a media key to the system. KEY may be: play, pause, play-pause, mute, headsethook, stop, next, previous, rewind, record, fast-forword.` The parser actually matches `fast-forward` ([MediaShellCommand.java L103-119, L159-193](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/media/MediaShellCommand.java)). Sends ACTION_DOWN + ACTION_UP via `ISessionManager.dispatchMediaKeyEvent("", false, event, false)`. Unknown key → `Error: unknown dispatch code '<x>'`.
* Service name `media_session` = `Context.MEDIA_SESSION_SERVICE`; `MediaSessionService.SessionManagerImpl.onShellCommand` routes to `MediaShellCommand` ([MediaSessionService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/media/MediaSessionService.java)).
* Public alternative that needs **no** Shizuku: `AudioManager.dispatchMediaKeyEvent(KeyEvent)` (API 19, no permission) — send DOWN then UP for `KEYCODE_MEDIA_PLAY_PAUSE` etc. ([AudioManager.java L841](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/media/java/android/media/AudioManager.java)). Use this first; keep `cmd media_session` as fallback.

### 2.14 Screenshot

```
screencap -p /sdcard/Pictures/prism.png
screencap -p            # PNG to stdout (read the process InputStream as bytes)
```

* Usage verbatim: `usage: screencap [-hp] [-d display-id] [FILENAME] / -p: save the file as a png. / -d: specify the physical display ID … / If FILENAME ends with .png it will be saved as a png. / If FILENAME is not given, the results will be printed to stdout.` ([screencap.cpp L48-59](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/cmds/screencap/screencap.cpp)). Shell may write under `/sdcard` (it is `media_rw` capable) and to `/data/local/tmp`; it cannot write into your app's private dir. Simplest: capture to stdout and copy bytes into your app's `cacheDir`.
* Public alternative: none without `MediaProjection` (user consent dialog each session).

### 2.15 Torch

No shell command exists in AOSP for the torch. Use the public API (section 3.1); from a UserService you have no `Context` for `CameraManager` anyway.

### 2.16 App launching, permission grants, app ops, battery dump

```
am start -n <pkg>/<activity>                     # explicit
am start -a android.settings.SETTINGS            # by action
am start -W -n <pkg>/<activity>                  # -W waits for launch, prints Status/Activity/TotalTime lines
monkey -p <pkg> -c android.intent.category.LAUNCHER 1     # prints "Events injected: 1" and "// Monkey finished"
pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS   # shell holds GRANT_RUNTIME_PERMISSIONS
pm grant --user 0 <pkg> <perm>
appops set <pkg> <OP> allow|ignore|deny|default|foreground
appops set --uid <pkg> <OP> <MODE>
dumpsys battery
```

* `am start` is an alias of `start-activity [-D] [-N] [-W] … [--user <USER_ID> | current] <INTENT>` ([ActivityManagerShellCommand.java L191, L3222-3245](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/am/ActivityManagerShellCommand.java)). From your own app you should just call `startActivity`; only use `am` when launching something the app itself cannot (e.g. across users).
* `monkey -p pkg 1` with no `-c` adds `CATEGORY_LAUNCHER` by default and launches the main activity ([Monkey.java L608, L804, L1183](https://android.googlesource.com/platform/development/+/refs/heads/android12-release/cmds/monkey/src/com/android/commands/monkey/Monkey.java)).
* `pm grant`: help verbatim "The permissions must be declared as used in the app's manifest, be runtime permissions (protection level dangerous)…" — `WRITE_SECURE_SETTINGS` is a **development** permission (`signature|privileged|development`) and is grantable by `pm grant` because it is not dangerous but is in the development tier; this is the classic trick to let *your app* write `Settings.Secure`/`Global` and call `setLocationEnabledForUser` etc. without Shizuku afterwards ([PackageManagerShellCommand.java L2361-2386, L3793-3797](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java)). Once granted, `Settings.Global.putInt(...)` works from the app; the grant survives reboots but not reinstall.
* `appops set [--user <USER_ID>] <[--uid] PACKAGE | UID> <OP> <MODE>`; MODE names are `allow, ignore, deny, default, foreground` or a number ([AppOpsService.java L5278-5290, L5437-5438](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/appop/AppOpsService.java) ; [AppOpsManager.java L449-455](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/app/AppOpsManager.java)). Useful: `appops set <pkg> WRITE_SETTINGS allow` grants the "Modify system settings" toggle so `Settings.System.canWrite()` becomes true without the user visiting the screen.
* `dumpsys battery` prints exactly ([BatteryService.java L1066-1087](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/BatteryService.java)):
  ```
  Current Battery Service state:
    AC powered: false
    USB powered: true
    Wireless powered: false
    Max charging current: 500000
    Max charging voltage: 5000000
    Charge counter: 2300000
    status: 2
    health: 2
    present: true
    level: 87
    scale: 100
    voltage: 4123
    temperature: 312
    technology: Li-ion
  ```
  `status` uses `BatteryManager.BATTERY_STATUS_*` (2 = charging). From the app, `BatteryManager` is strictly better (section 3.8).

### 2.17 Keep screen on while charging

```
svc power stayon true|false|usb|ac|wireless
```

* Calls `IPowerManager.wakeUp(...)` (when not false) then `setStayOnSetting(int)`; `setStayOnSetting` calls `Settings.checkAndNoteWriteSettingsOperation` for uid 2000 → `WRITE_SETTINGS` (shell has it) then writes `Settings.Global.STAY_ON_WHILE_PLUGGED_IN` ([PowerCommand.java L43-91](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/cmds/svc/src/com/android/commands/svc/PowerCommand.java) ; [PowerManagerService.java L5735-5745](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/power/PowerManagerService.java)). Prints nothing on success, `Faild to set setting: …` (sic) on RemoteException.

---

## 3. Public-API alternatives for a normal app (no Shizuku), with permission names

All constants below were read from the `android12-release` framework sources; deprecation levels from developer.android.com.

### 3.1 Torch — `CameraManager.setTorchMode(cameraId, enabled)` (API 23)

* No permission required (the javadoc lists none; `CAMERA` is **not** needed). Throws `CameraAccessException` (`CAMERA_IN_USE`, `MAX_CAMERAS_IN_USE`, `CAMERA_DISCONNECTED`) or `IllegalArgumentException` if the id has no flash. Any app may turn the torch off; the torch turns off when the owning app exits. Observe state with `registerTorchCallback(Executor, TorchCallback)` → `onTorchModeChanged(cameraId, enabled)` ([CameraManager.java L891-929](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/hardware/camera2/CameraManager.java)). `turnOnTorchWithStrengthLevel` is API 33 — not on this device.

```kotlin
val cm = context.getSystemService(CameraManager::class.java)
val torchId = cm.cameraIdList.firstOrNull { id ->
    cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
    cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
}
torchId?.let { cm.setTorchMode(it, true) }
```

### 3.2 Brightness / auto-brightness / rotation lock / timeout — `Settings.System` + `WRITE_SETTINGS`

* Manifest: `<uses-permission android:name="android.permission.WRITE_SETTINGS" />` (special app-op permission; not a runtime dialog).
* Gate: `Settings.System.canWrite(context)`; if false, `startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))` — action string `android.settings.action.MANAGE_WRITE_SETTINGS` ([Settings.java L1176, L5586-5599](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/provider/Settings.java)). With Shizuku available once: `appops set <pkg> WRITE_SETTINGS allow` flips the same switch.
* Writes: `Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 0..255)`, `putInt(cr, SCREEN_BRIGHTNESS_MODE, SCREEN_BRIGHTNESS_MODE_MANUAL /*0*/ or _AUTOMATIC /*1*/)`, `putInt(cr, ACCELEROMETER_ROTATION, 0|1)`, `putInt(cr, USER_ROTATION, Surface.ROTATION_0..270)`, `putInt(cr, SCREEN_OFF_TIMEOUT, ms)`. Throws `SecurityException` without the grant.
* Reads need no permission: `Settings.System.getInt(cr, key, default)`.

### 3.3 Volume — `AudioManager` (no permission for normal streams)

* `setStreamVolume(streamType, index, flags)`, `adjustStreamVolume(streamType, ADJUST_RAISE|ADJUST_LOWER|ADJUST_SAME|ADJUST_MUTE|ADJUST_UNMUTE|ADJUST_TOGGLE_MUTE, flags)`, `getStreamVolume`, `getStreamMaxVolume`, `getStreamMinVolume` (API 28), `isStreamMute`, `isMusicActive`. Streams: `STREAM_VOICE_CALL`, `STREAM_SYSTEM`, `STREAM_RING`, `STREAM_MUSIC`, `STREAM_ALARM`, `STREAM_NOTIFICATION`, `STREAM_ACCESSIBILITY`. `FLAG_SHOW_UI = 1`. Ringer: `setRingerMode(RINGER_MODE_SILENT=0 | RINGER_MODE_VIBRATE=1 | RINGER_MODE_NORMAL=2)`.
* Javadoc verbatim: "From N onward, volume adjustments that would toggle Do Not Disturb are not allowed unless the app has been granted Do Not Disturb Access. See NotificationManager#isNotificationPolicyAccessGranted()" and "@throws SecurityException if the volume change triggers a Do Not Disturb change and the caller is not granted notification policy access." Same note on `setRingerMode` ([AudioManager.java L1201-1243](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/media/java/android/media/AudioManager.java)). So: muting `STREAM_RING`/`STREAM_NOTIFICATION` to 0 or `RINGER_MODE_SILENT` needs DND access; `STREAM_MUSIC` never does.
* Broadcasts: `android.media.VOLUME_CHANGED_ACTION` (extras `android.media.EXTRA_VOLUME_STREAM_TYPE`, `…_VALUE`) and `android.media.RINGER_MODE_CHANGED` ([L129, L178, L245](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/media/java/android/media/AudioManager.java)).
* System volume panel: `startActivity(Intent(Settings.Panel.ACTION_VOLUME))`.

### 3.4 Do Not Disturb — `NotificationManager` + policy access

* Manifest: `<uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />` (normal). Runtime gate: `isNotificationPolicyAccessGranted()`; if false, `startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))` (user toggles your app in the list). Then `setInterruptionFilter(INTERRUPTION_FILTER_ALL=1 | PRIORITY=2 | NONE=3 | ALARMS=4)`; without access it throws `SecurityException("Notification policy access denied")` ([NotificationManager.java L383-412, L1451, L2538](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/app/NotificationManager.java) ; [NotificationManagerService.java L4960-4970](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/notification/NotificationManagerService.java)).
* Read: `getCurrentInterruptionFilter()`; broadcast `NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED`.
* Shizuku shortcut to skip the settings screen: `cmd notification allow_dnd <pkg>` grants policy access to your package ([NotificationShellCmd.java L186-194](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/notification/NotificationShellCmd.java)).

### 3.5 Bluetooth — `BluetoothAdapter.enable()/disable()` on API 31

* Deprecated in **API 33** (developer.android.com: "Use startActivityForResult(Intent) with ACTION_REQUEST_ENABLE instead"); on API 31 it is a live call annotated `@RequiresPermission(BLUETOOTH_CONNECT)` ([BluetoothAdapter.java L1196](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/bluetooth/BluetoothAdapter.java) ; https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#enable()).
* Manifest for minSdk 31: `<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />` (runtime, "Nearby devices" group — request with `ActivityResultContracts.RequestPermission`). Also add `<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />` only if you ever lower minSdk.
* Service-side conditions (same as shell): caller must be in the foreground user, `BLUETOOTH_CONNECT` granted, no `DISALLOW_BLUETOOTH` restriction ([BluetoothManagerService.java L939-963](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/BluetoothManagerService.java)). Returns `false` (no exception) when refused. Fallback: `startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))` / `Settings.ACTION_BLUETOOTH_SETTINGS = "android.settings.BLUETOOTH_SETTINGS"`.
* Read: `BluetoothAdapter.isEnabled()` / `getState()` (needs `BLUETOOTH_CONNECT` on 31 for `getState`? — `isEnabled` is documented as needing only legacy `BLUETOOTH`; on 31 both are gated by `BLUETOOTH_CONNECT` in `BluetoothManagerService` for non-system callers: **PLAUSIBLE, treat as requiring BLUETOOTH_CONNECT**). Broadcast `BluetoothAdapter.ACTION_STATE_CHANGED` with `EXTRA_STATE` (`STATE_ON=12`, `STATE_OFF=10`).

### 3.6 Wi‑Fi — read-only for normal apps

* `WifiManager.setWifiEnabled` is `@Deprecated` since Q: "For applications targeting Q or above, this API will always fail and return false. Deprecation Exemptions: Device Owner (DO), Profile Owner (PO) and system apps." ([WifiManager.java L3447-3469](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android12-release/framework/java/android/net/wifi/WifiManager.java)). Fallback UI: `Settings.Panel.ACTION_WIFI` or `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` (section 3.11).
* Read: `WifiManager.isWifiEnabled()` / `getWifiState()` (`WIFI_STATE_DISABLED=1`, `WIFI_STATE_ENABLED=3`), broadcast `android.net.wifi.WIFI_STATE_CHANGED` with `EXTRA_WIFI_STATE = "wifi_state"` ([L557-594, L3648-3661](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android12-release/framework/java/android/net/wifi/WifiManager.java)). `ACCESS_WIFI_STATE` (normal) in manifest.
* SSID on API 31: `getConnectionInfo()` is deprecated in 31; register `ConnectivityManager.NetworkCallback(NetworkCallback.FLAG_INCLUDE_LOCATION_INFO /* = 1 */)` and cast `networkCapabilities.transportInfo as? WifiInfo` in `onCapabilitiesChanged`. Requires `ACCESS_FINE_LOCATION` granted **and** device location on; without the flag "any NetworkCapabilities provided via the callback does not include location sensitive info" (SSID is `<unknown ssid>`) ([ConnectivityManager.java L3462-3501](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android12-release/framework/src/android/net/ConnectivityManager.java) ; [WifiManager.java L3195-3219](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android12-release/framework/java/android/net/wifi/WifiManager.java)).

```kotlin
val cm = context.getSystemService(ConnectivityManager::class.java)
val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        val info = caps.transportInfo as? WifiInfo ?: return
        _ssid.value = info.ssid.removeSurrounding("\"")   // "<unknown ssid>" when location denied
    }
    override fun onLost(network: Network) { _ssid.value = null }
}
cm.registerNetworkCallback(
    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
```

### 3.7 Airplane mode — read-only for normal apps

* `Settings.Global.AIRPLANE_MODE_ON = "airplane_mode_on"`; `Settings.Global.getInt(cr, key, 0) == 1`. Writes need `WRITE_SECURE_SETTINGS` (not grantable through UI; grantable via `pm grant` because it is a development permission) and even then the broadcast cannot be sent by a normal app, so the write is useless — use the shell path or `Settings.ACTION_AIRPLANE_MODE_SETTINGS = "android.settings.AIRPLANE_MODE_SETTINGS"` ([Settings.java L251, L10261](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/provider/Settings.java)). Broadcast to observe: `Intent.ACTION_AIRPLANE_MODE_CHANGED` (`android.intent.action.AIRPLANE_MODE`), extra `state` (boolean).

### 3.8 Battery — `BatteryManager` (no permission)

* `getIntProperty(BATTERY_PROPERTY_CAPACITY /*4*/)` → percent; `getIntProperty(BATTERY_PROPERTY_STATUS /*6*/, API 26)`; `isCharging()` (API 23). Sticky `Intent.ACTION_BATTERY_CHANGED` via `registerReceiver(null, IntentFilter(ACTION_BATTERY_CHANGED))` with extras `level`, `scale`, `plugged` (`BATTERY_PLUGGED_AC=1`, `USB=2`, `WIRELESS=4`), `status`. Broadcasts `android.os.action.CHARGING` / `android.os.action.DISCHARGING` ([BatteryManager.java L41-89, L168-213, L242-252, L284, L328](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/os/BatteryManager.java)).

### 3.9 Battery saver — read-only

* `PowerManager.isPowerSaveMode()`; broadcast `PowerManager.ACTION_POWER_SAVE_MODE_CHANGED = "android.os.action.POWER_SAVE_MODE_CHANGED"`. `setPowerSaveModeEnabled` is `@SystemApi` ([PowerManager.java L1720-1737](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/os/PowerManager.java)). UI fallback `Settings.ACTION_BATTERY_SAVER_SETTINGS` (API 22). `isInteractive()` tells you whether the screen is on.

### 3.10 Dark theme — read-only system-wide

* `UiModeManager.getNightMode()` returns `MODE_NIGHT_AUTO=0`, `MODE_NIGHT_NO=1`, `MODE_NIGHT_YES=2`, `MODE_NIGHT_CUSTOM=3` ([UiModeManager.java L226-244](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/app/UiModeManager.java)). Cheaper live signal: `resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES` (updates on configuration change).
* `setNightMode(int)` is blocked for third-party apps when `config_lockDayNightMode = true` (AOSP default; OxygenOS **UNVERIFIED** but assume locked). `setApplicationNightMode(int)` (API 31) only affects your own app. UI fallback: `Settings.ACTION_DARK_THEME_SETTINGS = "android.settings.DARK_THEME_SETTINGS"`.

### 3.11 Settings panels and screens (all `startActivity`, no permission)

| Constant | String | Notes |
|---|---|---|
| `Settings.Panel.ACTION_WIFI` | `android.settings.panel.action.WIFI` | API 29, bottom-sheet with Wi‑Fi toggle |
| `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` | `android.settings.panel.action.INTERNET_CONNECTIVITY` | API 29, Wi‑Fi + mobile data + airplane |
| `Settings.Panel.ACTION_NFC` | `android.settings.panel.action.NFC` | API 29 |
| `Settings.Panel.ACTION_VOLUME` | `android.settings.panel.action.VOLUME` | API 29 |
| `Settings.ACTION_WIFI_SETTINGS` | `android.settings.WIFI_SETTINGS` | |
| `Settings.ACTION_BLUETOOTH_SETTINGS` | `android.settings.BLUETOOTH_SETTINGS` | |
| `Settings.ACTION_NFC_SETTINGS` | `android.settings.NFC_SETTINGS` | |
| `Settings.ACTION_AIRPLANE_MODE_SETTINGS` | `android.settings.AIRPLANE_MODE_SETTINGS` | |
| `Settings.ACTION_LOCATION_SOURCE_SETTINGS` | `android.settings.LOCATION_SOURCE_SETTINGS` | |
| `Settings.ACTION_DISPLAY_SETTINGS` | `android.settings.DISPLAY_SETTINGS` | |
| `Settings.ACTION_SOUND_SETTINGS` | `android.settings.SOUND_SETTINGS` | |
| `Settings.ACTION_DARK_THEME_SETTINGS` | `android.settings.DARK_THEME_SETTINGS` | |
| `Settings.ACTION_DATA_ROAMING_SETTINGS` | `android.settings.DATA_ROAMING_SETTINGS` | mobile network screen |
| `Settings.ACTION_MANAGE_WRITE_SETTINGS` | `android.settings.action.MANAGE_WRITE_SETTINGS` | pass `Uri.parse("package:…")` |
| `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` | `android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS` | DND access list |

Sources: [Settings.java Panel class + L152-1496](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/provider/Settings.java). Panels are Activities; wrap in `try/catch(ActivityNotFoundException)` and fall back to the full-screen action (OEM SystemUI may not ship a panel — **UNVERIFIED** on OxygenOS).

### 3.12 Location and NFC — read-only

* `LocationManager.isLocationEnabled()` (API 28, no permission); broadcast `android.location.MODE_CHANGED` with `EXTRA_LOCATION_ENABLED` (API 30) ([LocationManager.java L307-315, L600](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/location/java/android/location/LocationManager.java)).
* `NfcAdapter.getDefaultAdapter(context)?.isEnabled()` (manifest `android.permission.NFC`, normal); broadcast `android.nfc.action.ADAPTER_STATE_CHANGED`, extra `android.nfc.extra.ADAPTER_STATE` with `STATE_OFF=1`, `STATE_ON=3` ([NfcAdapter.java L232-276, L700, L879](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/nfc/NfcAdapter.java)). `getDefaultAdapter` returns `null` if the device has no NFC.

---

## 4. Decision table (what the tile should actually call)

| Tile | Read state (app, no Shizuku) | Write — Shizuku (uid 2000) | Write — no Shizuku |
|---|---|---|---|
| Wi‑Fi | `WifiManager.isWifiEnabled()` + `WIFI_STATE_CHANGED` | `cmd wifi set-wifi-enabled enabled\|disabled` | `Settings.Panel.ACTION_WIFI` |
| Mobile data | `TelephonyManager.isDataEnabled()` | `cmd phone data enable\|disable` | `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` |
| Bluetooth | `BluetoothAdapter.isEnabled()` + `ACTION_STATE_CHANGED` | `svc bluetooth enable\|disable` | `BluetoothAdapter.enable()` with `BLUETOOTH_CONNECT` (deprecated 33, live on 31); else `ACTION_REQUEST_ENABLE` |
| NFC | `NfcAdapter.isEnabled()` + `ADAPTER_STATE_CHANGED` | `svc nfc enable\|disable` | `Settings.Panel.ACTION_NFC` |
| Airplane | `Settings.Global.AIRPLANE_MODE_ON` + `ACTION_AIRPLANE_MODE_CHANGED` | `cmd connectivity airplane-mode enable\|disable` | `Settings.ACTION_AIRPLANE_MODE_SETTINGS` |
| Location | `LocationManager.isLocationEnabled()` + `MODE_CHANGED` | `cmd location set-location-enabled true\|false` | `Settings.ACTION_LOCATION_SOURCE_SETTINGS` |
| Torch | `CameraManager.registerTorchCallback` | (none needed) | `CameraManager.setTorchMode` |
| Brightness | `Settings.System.getInt(SCREEN_BRIGHTNESS)` + ContentObserver | `settings put system screen_brightness N` | `Settings.System.putInt` after `canWrite()` |
| Auto-brightness | `SCREEN_BRIGHTNESS_MODE` | `settings put system screen_brightness_mode 0\|1` | same via `WRITE_SETTINGS` |
| Rotation lock | `ACCELEROMETER_ROTATION` | `settings put system accelerometer_rotation 0\|1` | same via `WRITE_SETTINGS` |
| Battery saver | `PowerManager.isPowerSaveMode()` + broadcast | `cmd power set-mode 1\|0` | `Settings.ACTION_BATTERY_SAVER_SETTINGS` |
| Dark theme | `UiModeManager.getNightMode()` / `Configuration.uiMode` | `cmd uimode night yes\|no` | `Settings.ACTION_DARK_THEME_SETTINGS` |
| DND | `NotificationManager.getCurrentInterruptionFilter()` + broadcast | `cmd notification set_dnd priority\|off` (or grant self via `allow_dnd <pkg>` once, then use API) | `setInterruptionFilter` after policy access |
| Volume | `AudioManager.getStreamVolume` + `VOLUME_CHANGED_ACTION` | (not needed) | `AudioManager.setStreamVolume` (DND access only for ring/notification mute) |
| Media | `MediaSessionManager` needs notification-listener; skip | `cmd media_session dispatch play-pause` | `AudioManager.dispatchMediaKeyEvent` (works without any permission — prefer) |
| Screen off | `PowerManager.isInteractive()` | `input keyevent KEYCODE_SLEEP` | none (device admin only) |
| Shade / QS | — | `cmd statusbar expand-notifications\|expand-settings\|collapse` | none |
| Screenshot | — | `screencap -p` (stdout) | `MediaProjection` |
| Battery info | `BatteryManager` | `dumpsys battery` (not needed) | — |

---

## 5. Implementation notes for the Prism `ShellService`

* Run each command as `arrayOf("sh", "-c", cmd)` or directly `arrayOf("cmd", "wifi", "set-wifi-enabled", "enabled")`; the `cmd`, `svc`, `settings`, `input`, `screencap`, `am`, `pm`, `appops`, `dumpsys`, `monkey` binaries are all on `/system/bin` and in the UserService's PATH.
* Treat exit code ≠ 0 **or** any stderr text as failure; most successful writes print nothing to stdout. `cmd` returns the handler's `onCommand` result (-1 on bad args) and prints `Exception occurred while executing 'x':` + stack trace to stderr on a thrown `SecurityException`.
* After a write, re-read state through the public API rather than trusting the command (Bluetooth/Wi‑Fi toggles are async; listen for the state broadcast).
* Commands that need a *foreground user* (`svc bluetooth`) work because uid 2000 belongs to user 0; if the phone ever uses a secondary user, pass `--user current` to `settings`/`cmd location`.
* For `screencap -p` with no filename, read stdout as raw bytes (do not go through a `BufferedReader`).
* One-time privilege escalations worth doing when Shizuku is present, so the tiles keep working after Shizuku dies: `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` (then `Settings.Secure/Global` and `LocationManager` writes work from the app), `appops set <pkg> WRITE_SETTINGS allow`, `cmd notification allow_dnd <pkg>`. These do **not** cover Wi‑Fi/BT/NFC/airplane toggles, which stay Shizuku-only.

---

## 6. UNVERIFIED / OEM caveats

* OxygenOS 12 SystemUI reaction to `cmd statusbar expand-*` and the availability of `Settings.Panel` activities.
* Whether OxygenOS keeps the runtime `BLUETOOTH_CONNECT` grant on `com.android.shell` (affects `svc bluetooth`). AOSP: works.
* Whether OnePlus sets `config_lockDayNightMode` differently (AOSP: `true`).
* `BluetoothAdapter.isEnabled()` permission on 31 for third-party callers (marked PLAUSIBLE above; request `BLUETOOTH_CONNECT` regardless).
* `android13-release` `BluetoothShellCommand` was opened and confirmed (`enable`/`disable`, "Enabling Bluetooth"), but the exact service name it registers under (`bluetooth_manager`) was not read from that branch's `BluetoothManagerService`; irrelevant for API 31.
* All developer.android.com pages except `BluetoothAdapter` returned truncated navigation content to the fetcher; every API fact was therefore taken from the `android12-release` framework source instead.

## 7. Source index (all opened 2026-09-20)

frameworks/base android12-release: `cmds/svc/svc`, `cmds/svc/src/com/android/commands/svc/{Svc,PowerCommand,BluetoothCommand,NfcCommand}.java`, `cmds/input/input`, `cmds/settings/settings`, `cmds/screencap/screencap.cpp`, `packages/Shell/AndroidManifest.xml`, `packages/SettingsProvider/src/com/android/providers/settings/SettingsService.java`, `services/core/java/com/android/server/{BluetoothManagerService,BatteryService,UiModeManagerService}.java`, `services/core/java/com/android/server/statusbar/{StatusBarShellCommand,StatusBarManagerService}.java`, `services/core/java/com/android/server/location/{LocationShellCommand,LocationManagerService}.java`, `services/core/java/com/android/server/power/{PowerManagerShellCommand,PowerManagerService}.java`, `services/core/java/com/android/server/notification/{NotificationShellCmd,NotificationManagerService}.java`, `services/core/java/com/android/server/media/{MediaShellCommand,MediaSessionService}.java`, `services/core/java/com/android/server/input/InputShellCommand.java`, `services/core/java/com/android/server/appop/AppOpsService.java`, `services/core/java/com/android/server/pm/PackageManagerShellCommand.java`, `services/core/java/com/android/server/pm/permission/PermissionManagerService.java`, `services/core/java/com/android/server/am/{ActivityManagerShellCommand,ActivityManagerService}.java`, `core/java/android/provider/Settings.java`, `core/java/android/view/KeyEvent.java`, `core/java/android/app/{AppOpsManager,UiModeManager,NotificationManager}.java`, `core/java/android/bluetooth/{BluetoothAdapter,BluetoothManager}.java`, `core/java/android/nfc/NfcAdapter.java`, `core/java/android/os/{PowerManager,BatteryManager}.java`, `core/java/android/hardware/camera2/CameraManager.java`, `core/java/com/android/internal/display/BrightnessSynchronizer.java`, `core/res/res/values/config.xml`, `location/java/android/location/LocationManager.java`, `media/java/android/media/AudioManager.java`.
packages/modules/Connectivity android12-release: `service/src/com/android/server/ConnectivityService.java`, `framework/src/android/net/ConnectivityManager.java`.
packages/modules/Wifi android12-release: `service/java/com/android/server/wifi/{WifiShellCommand,WifiServiceImpl,WifiSettingsStore,ActiveModeWarden}.java`, `framework/java/android/net/wifi/WifiManager.java`.
packages/services/Telephony android12-release: `src/com/android/phone/TelephonyShellCommand.java`.
packages/apps/Nfc android12-release: `src/com/android/nfc/{NfcService,NfcPermissions}.java`.
development android12-release: `cmds/monkey/src/com/android/commands/monkey/Monkey.java`.
packages/modules/Bluetooth android13-release: `service/java/com/android/server/bluetooth/BluetoothShellCommand.java`.
developer.android.com: `reference/android/bluetooth/BluetoothAdapter` (deprecation level 33 for `enable()`/`disable()`).
Maven Central: `dev/rikka/shizuku/api/maven-metadata.xml` (latest 13.1.5).
