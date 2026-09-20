# Shizuku client integration (server v13.6.0, API lib 13.1.5)

Research date: 2026-09-20. Target: sideloaded Kotlin + Jetpack Compose app, OnePlus 7 (GM1900), Android 12 / OxygenOS 12 (API 31), non-root, Shizuku 13.6.0 started over wireless debugging (runs as shell, uid 2000).
Toolchain: Kotlin 2.0.20, AGP 8.5.2, Gradle 8.7, compileSdk 34, minSdk 31, Compose BOM 2024.09.03.

Every fact below was read from the current source on 2026-09-20 unless it is marked **UNVERIFIED**. Where the task brief's assumptions were wrong, the correction is called out explicitly under "Corrections to the brief".

---

## 0. TL;DR / corrections to the brief

| Brief assumed | Verified reality | Source |
|---|---|---|
| "the app must declare the UserService in manifest, with `android:exported=false`" | **No.** The Shizuku server never consults the manifest for a UserService. It calls `createPackageContextAsUser(pkg, CONTEXT_INCLUDE_CODE \| CONTEXT_IGNORE_SECURITY, user)` and `classLoader.loadClass(cls)` on your APK, then spawns the class via `/system/bin/app_process`, not via ActivityManager. The official demo manifest declares **no** `<service>` at all. A `<service>` entry is harmless but useless; **never** add `android:process` to one. | https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/server-shared/src/main/java/rikka/shizuku/server/UserService.java ; https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/demo/src/main/AndroidManifest.xml |
| `Shizuku.UserServiceArgs(...).processNameSuffix("shell")` is optional flavour | **`processNameSuffix` is mandatory.** `UserServiceArgs.forAdd()` does `Objects.requireNonNull(processName, "process name suffix must not be null")`, so `bindUserService` throws NPE without it. | https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/api/src/main/java/rikka/shizuku/Shizuku.java |
| `daemon(false)` | Correct choice for us, but note the **default is `daemon = true`** ("for compatibility"); a daemon service outlives your app until you call `unbindUserService(..., remove = true)`. | same file |
| `Shizuku.newProcess` | Confirmed **`private static`**, `@Deprecated`, "Planned removal from API 14". Not callable from app code without reflection. Use a UserService. | same file |
| UserService `(Context)` constructor receives a Context | On the shipped v13.6.0 server it receives an **`android.app.Application`** created by `LoadedApk.makeApplication(true /*forceDefaultAppClass*/, null /*instrumentation*/)`: it is always the plain `android.app.Application` class (your custom `Application` subclass is **not** instantiated and `onCreate()` is **not** called). Do not rely on Hilt/Koin/app singletons inside the service process. | https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/server-shared/src/main/java/rikka/shizuku/server/UserService.java (commit 284db86, 2024-03-12); AOSP `LoadedApk.makeApplication` android-12.0.0_r1 |
| Latest API lib | `13.1.5` is the newest artifact on Maven Central (published 2023-09-21). The GitHub master has later *server-side* commits (patch bumped to 6 on 2025-05-29) but no newer client artifact. | https://repo1.maven.org/maven2/dev/rikka/shizuku/api/maven-metadata.xml |

---

## 1. Versions and dependency coordinates (verified on Maven Central)

| Artifact | Latest | Notes | Source |
|---|---|---|---|
| `dev.rikka.shizuku:api` | **13.1.5** | POM deps: `dev.rikka.shizuku:aidl:13.1.5` (compile), `dev.rikka.shizuku:shared:13.1.5` (compile), `androidx.annotation:annotation:1.3.0` (runtime). Packaging `aar`. | https://repo1.maven.org/maven2/dev/rikka/shizuku/api/maven-metadata.xml ; https://repo1.maven.org/maven2/dev/rikka/shizuku/api/13.1.5/api-13.1.5.pom |
| `dev.rikka.shizuku:provider` | **13.1.5** | POM deps: `dev.rikka.shizuku:api:13.1.5` (runtime), `androidx.annotation:annotation:1.3.0`. Its AAR manifest merges `<uses-permission android:name="moe.shizuku.manager.permission.API_V23"/>` and `<meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true"/>` into your app automatically. | https://repo1.maven.org/maven2/dev/rikka/shizuku/provider/13.1.5/provider-13.1.5.pom ; https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/provider/src/main/AndroidManifest.xml |
| `org.lsposed.hiddenapibypass:hiddenapibypass` | **6.1** (2025-02-11) | Only needed for the "call system services directly" path (section 7). | https://repo1.maven.org/maven2/org/lsposed/hiddenapibypass/hiddenapibypass/maven-metadata.xml |
| `dev.rikka.hidden:stub` / `dev.rikka.hidden:compat` | 4.4.0 (2025-05-23) | Optional hidden-API stubs used by Rikka's own apps; contents for `IStatusBarService` **UNVERIFIED** (README changelog lists IWindowManager, IBatteryPropertiesRegistrar, ActivityManager, DisplayManager, AppOps... not statusbar). Section 7 shows a stub-free alternative. | https://repo1.maven.org/maven2/dev/rikka/hidden/stub/maven-metadata.xml ; https://raw.githubusercontent.com/RikkaW/HiddenApi/master/README.md |
| `dev.rikka.tools.refine:runtime` | 4.4.0 | Only if you adopt the stub approach. | https://repo1.maven.org/maven2/dev/rikka/tools/refine/runtime/maven-metadata.xml |
| Shizuku app (server) | **v13.6.0**, published 2025-05-25T13:19:47Z | Changelog: "Support Android16 QPR1. Update the start command... Support auto start without root on Android13+ when connected to a trusted WLAN." The auto-start feature does **not** apply to our Android 12 device. Its `api` submodule at that tag = Shizuku-API commit `510fc988` (2025-05-22), i.e. it includes the `makeApplication` change. | https://api.github.com/repos/RikkaApps/Shizuku/releases/latest ; https://api.github.com/repos/RikkaApps/Shizuku/contents/?ref=v13.6.0 |
| Server protocol constants (lib 13.1.5 / master) | `SERVER_VERSION = 13`, `SERVER_PATCH_VERSION = 6` (master), `USER_SERVICE_TRANSACTION_destroy = 16777115`, binder descriptor `moe.shizuku.server.IShizukuService` | https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/shared/src/main/java/rikka/shizuku/ShizukuApiConstants.java |

### `app/build.gradle.kts`

```kotlin
android {
    buildFeatures {
        aidl = true          // AGP 8.0+ ships with android.defaults.buildfeatures.aidl=false
        buildConfig = true   // needed for BuildConfig.DEBUG / VERSION_CODE / APPLICATION_ID
    }
}

dependencies {
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")

    // Only for section 7 (direct system-service calls from the app process):
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
```

AGP 8.0 release notes: "`android.defaults.buildfeatures.aidl` new default `false` (was `true`)... You need to specify this option using the DSL in the projects where you need it." https://developer.android.com/build/releases/past-releases/agp-8-0-0-release-notes

Desugaring: the 13.1.0 changelog says "desugaring is required if min API of your app is 23"; with `minSdk 31` this does **not** apply. (The 13.1.1/13.1.2 `CopyOnWriteArrayList#removeIf` crash was Android < 8.0 only.) https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/README.md

---

## 2. Manifest

Exactly as in the README, the demo, and the `ShizukuProvider` javadoc:

```xml
<application ...>

    <!-- Required by Shizuku. Do NOT put android:process on it. -->
    <provider
        android:name="rikka.shizuku.ShizukuProvider"
        android:authorities="${applicationId}.shizuku"
        android:multiprocess="false"
        android:enabled="true"
        android:exported="true"
        android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />

</application>
```

Why each attribute (from the `ShizukuProvider` javadoc):
- `android:permission` "should be a permission that granted to Shell (com.android.shell) but not normal apps (e.g., android.permission.INTERACT_ACROSS_USERS_FULL), so that it can only be used by the app itself and Shizuku server."
- `android:exported` "must be `true` so that the provider can be accessed from Shizuku server runs under adb."
- `android:multiprocess` "must be `false` since Shizuku server only gets uid when app starts."

Enforced at runtime: `ShizukuProvider.attachInfo()` throws `IllegalStateException("android:multiprocess must be false")` / `("android:exported must be true")`. `onCreate()` auto-calls `Sui.init(packageName)` (opt-out via `ShizukuProvider.disableAutomaticSuiInitialization()` before `onCreate`); harmless on a non-root device.
Source: https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/provider/src/main/java/rikka/shizuku/ShizukuProvider.java

**No `<service>` for the UserService is required** (see section 0). No extra `<uses-permission>` is required in your own manifest; the provider AAR merges `moe.shizuku.manager.permission.API_V23`.

Single-process app: nothing else. Multi-process app only: call `ShizukuProvider.enableMultiProcessSupport(isProviderProcess)` in a static block of `Application` and `ShizukuProvider.requestBinderForNonProviderProcess(context)` in non-provider processes (README + `DemoApplication.java`).

---

## 3. Binder lifecycle (rikka.shizuku.Shizuku, verified signatures)

Source: https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/api/src/main/java/rikka/shizuku/Shizuku.java

| Method | Behaviour |
|---|---|
| `static boolean pingBinder()` | `return binder != null && binder.pingBinder();` Never throws. Javadoc: "Normal apps should use listeners rather calling this method everytime." |
| `static void addBinderReceivedListener(OnBinderReceivedListener)` / `(listener, Handler)` | Called on main thread (or the given Handler). "The listener could be called multiply times. For example, user restarts Shizuku when app is running." |
| `static void addBinderReceivedListenerSticky(OnBinderReceivedListener)` / `(listener, Handler)` | "Same to addBinderReceivedListener but only call the listener immediately if the binder is already received." |
| `static boolean removeBinderReceivedListener(listener)` | |
| `static void addBinderDeadListener(OnBinderDeadListener)` / `(listener, Handler)` ; `removeBinderDeadListener` | Fired from the library's `DeathRecipient` when the Shizuku server process dies (`onBinderReceived(null, null)` resets `binder`, `service`, `serverUid = -1`, `serverApiVersion = -1`). Main thread by default. |
| `static void addRequestPermissionResultListener(OnRequestPermissionResultListener)` / `(listener, Handler)` ; `remove...` | `void onRequestPermissionResult(int requestCode, int grantResult)`; `grantResult` is `PackageManager.PERMISSION_GRANTED` / `PERMISSION_DENIED`. Main thread by default. |
| `static int checkSelfPermission()` | Cached `permissionGranted`; else IPC. Throws `IllegalStateException("binder haven't been received")` if no binder. Returns `PERMISSION_GRANTED`/`PERMISSION_DENIED`. v11+. |
| `static boolean shouldShowRequestPermissionRationale()` | `true` means the user chose "Deny and don't ask again"; returns `false` if already granted. Throws `IllegalStateException` without binder. |
| `static void requestPermission(int requestCode)` | Async; result arrives only through the listener. Throws `IllegalStateException` without binder. |
| `static int getVersion()` | Cached server API version (13 for v13.x). `-1` on `SecurityException`. Throws `IllegalStateException` without binder. |
| `static int getServerPatchVersion()` | e.g. 6 on v13.6.0 (**UNVERIFIED** exact runtime value; constant on master is 6). |
| `static int getUid()` | `0` = root, `2000` = adb shell. Throws `IllegalStateException` without binder. |
| `static boolean isPreV11()` | `true` only if both v13 and v11 `attachApplication` transactions fail. Show "unsupported". |
| `static String getSELinuxContext()` | `u:r:shell:s0` under adb. |
| `static void transactRemote(Parcel data, Parcel reply, int flags)` | Low-level; use `ShizukuBinderWrapper`. |
| `private static ShizukuRemoteProcess newProcess(String[] cmd, String[] env, String dir)` | **private**, deprecated, "Planned removal from API 14". |

`requireService()` is the helper that throws: `if (service == null) throw new IllegalStateException("binder haven't been received");` — wrap every `Shizuku.*` call except `pingBinder()`/listener registration in `runCatching`.

Demo pattern (`DemoActivity.java`): register `addBinderReceivedListenerSticky`, `addBinderDeadListener`, `addRequestPermissionResultListener` in `onCreate`, remove all three in `onDestroy`; permission check:

```java
private boolean checkPermission(int code) {
    if (Shizuku.isPreV11()) return false;
    try {
        if (Shizuku.checkSelfPermission() == PERMISSION_GRANTED) return true;
        else if (Shizuku.shouldShowRequestPermissionRationale()) { /* denied permanently */ return false; }
        else { Shizuku.requestPermission(code); return false; }
    } catch (Throwable e) { /* binder not ready */ }
    return false;
}
```
Source: https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/demo/src/main/java/rikka/shizuku/demo/DemoActivity.java

When is the binder delivered? The server pushes it into your `ShizukuProvider` (`METHOD_SEND_BINDER`) when your app process starts and again when Shizuku (re)starts while you are running (hence "could be called multiply times"). You never request it. If Shizuku is not running, no listener fires; your UI must show "Shizuku not running" from the absence of a binder (use a state default, not a call).

---

## 4. UserService (privileged process, shell uid)

### 4.1 Contract (README "User Service" + server source)

- Runs "in a different process and as the identity (Linux UID) of root (UID 0) or shell (UID 2000, if the backend is Shizuku and user starts Shizuku with adb)".
- "There are no restrictions on non-SDK APIs in the user service process. However, the User Service process is not a valid Android application process... many APIs, such as `Context#registerReceiver` and `Context#getContentResolver` will not work."
- "the service class must implement `IBinder` interface. The usual usage is `public class YourService extends IYouAidlInterface.Stub`."
- Constructors: server does `serviceClass.getConstructor(Context.class)` first; if absent, `serviceClass.newInstance()` (public no-arg). Both must be `public`. The argument passed is the `Application` described in section 0.
- "Shizuku uses `tag` from `UserServiceArgs` to determine if the User Service is same. If `tag` is not set, class name will be uses, but class name is unstable after ProGuard/R8. If `version` from `UserServiceArgs` mismatches, a new User Service will be start and 'destroy' method (see below) will be called for the old."
- Stop: "the user service process will **NOT** be killed automatically. You need to implement a 'destroy' method in your service. The transaction code for that method is `16777115` (use `16777114` in aidl). In this method, you can do some cleanup jobs and call `System.exit()` in the end."
- "to let the service to use the latest code, 'Run/Debug configurations' - 'Always install with package manager' in Android Studio should be checked."
Source: https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/README.md

### 4.2 How the server actually starts it (verified, Shizuku-API `server-shared` + Shizuku `starter`)

1. `UserServiceManager.addUserService(conn, options, callingApiVersion)`:
   - Validates the calling package owns the component's package (`ensureCallingPackageForUserService`, `SecurityException` otherwise).
   - Key = `packageName + ":" + (tag ?: className)`.
   - Existing record with different `versionCode` or dead binder is removed (`destroy` transaction sent) and a new one created.
   - Sets a **30 s start timeout** (`setStartingTimeout(DateUtils.SECOND_IN_MILLIS * 30)`); if the binder does not arrive, the record is silently removed and **your `ServiceConnection` never fires** (no `onServiceDisconnected`).
   - Spawns via `Runtime.exec("sh")` writing the command from `ServiceStarter.commandForUserService(...)`:
     `(CLASSPATH='<shizuku manager apk>' /system/bin/app_process[<debug jdwp args>] /system/bin --nice-name='<pkg>:<processNameSuffix>' moe.shizuku.starter.ServiceStarter --token='..' --package='<pkg>' --class='<fqcn>' --uid=<callingUid>)&`
2. `ServiceStarter.main` (in the new process) -> `rikka.shizuku.server.UserService.create(args)`: `ActivityThread.systemMain()`, `createPackageContextAsUser(pkg, CONTEXT_INCLUDE_CODE | CONTEXT_IGNORE_SECURITY, userHandle)`, `LoadedApk.makeApplication(true, null)`, `application.getClassLoader().loadClass(cls)`, constructor as described, then sends the `IBinder` back to the server and runs `Looper.loop()`. It registers a death listener on the server binder and exits when the server dies (**UNVERIFIED** detail: summary of `ServiceStarter.java`, full text not opened).
3. Server `UserServiceRecord.setBinder()` -> `linkToDeath` -> broadcasts `connected(binder)` to all registered `IShizukuServiceConnection`s.
4. Client `ShizukuServiceConnection.connected()` posts `conn.onServiceConnected(componentName, binder)` to the **main looper** and `linkToDeath`s the service binder; on death it posts `onServiceDisconnected` once, clears connections and removes itself from the cache.
5. Non-daemon cleanup: `ConnectionList.onCallbackDied()` -> if `!daemon && getRegisteredCallbackCount() == 0` -> `removeSelf()` -> `record.destroy()` which sends transaction `16777115` **oneway** with `data.writeInterfaceToken(service.getInterfaceDescriptor())`. That is the only thing that ends your process; your `destroy()` must `exit`.
6. `ApkChangedObservers` watches your APK path: **reinstalling the app (every sideload / `adb install`) kills the running user service** (record removed, package removed -> removed).

Sources: https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/server-shared/src/main/java/rikka/shizuku/server/UserServiceManager.java ; .../UserServiceRecord.java ; .../UserService.java ; https://raw.githubusercontent.com/RikkaApps/Shizuku/master/server/src/main/java/rikka/shizuku/server/ShizukuUserServiceManager.java ; https://raw.githubusercontent.com/RikkaApps/Shizuku/master/starter/src/main/java/moe/shizuku/starter/ServiceStarter.java ; https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/api/src/main/java/rikka/shizuku/ShizukuServiceConnection.java

### 4.3 Client API (verified)

```java
public static class UserServiceArgs {
    final ComponentName componentName;
    int versionCode = 1; String processName; String tag;
    boolean debuggable = false; boolean daemon = true; boolean use32BitAppProcess = false;
    public UserServiceArgs(@NonNull ComponentName componentName)
    public UserServiceArgs daemon(boolean daemon)                 // default TRUE
    public UserServiceArgs tag(@NonNull String tag)
    public UserServiceArgs version(int versionCode)
    public UserServiceArgs debuggable(boolean debuggable)
    public UserServiceArgs processNameSuffix(String suffix)       // REQUIRED (requireNonNull in forAdd)
}
public static void bindUserService(@NonNull UserServiceArgs args, @NonNull ServiceConnection conn)   // v10+
public static int  peekUserService(@NonNull UserServiceArgs args, @NonNull ServiceConnection conn)   // v12+; returns service version or -1 if not running (v13+)
public static void unbindUserService(@NonNull UserServiceArgs args, @Nullable ServiceConnection conn, boolean remove)
```
`unbindUserService(args, conn, remove = true)` -> server `removeUserService` -> `record.destroy()` (destroy transaction) ; `remove = false` only unregisters the callback (and, on server >= 13.4, tells the server too). All three rethrow `RemoteException` as `RuntimeException` and throw `IllegalStateException` if the binder is absent.

Demo args:
```java
new Shizuku.UserServiceArgs(new ComponentName(BuildConfig.APPLICATION_ID, UserService.class.getName()))
        .daemon(false).processNameSuffix("service").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE);
```
Demo guards: `Shizuku.getVersion() < 10` -> "requires Shizuku API 10" before bind/unbind, `< 12` before peek.

### 4.4 Demo AIDL and service (verbatim)

`demo/src/main/aidl/rikka/shizuku/demo/IUserService.aidl`:
```aidl
package rikka.shizuku.demo;

interface IUserService {
    void destroy() = 16777114; // Destroy method defined by Shizuku server
    void exit() = 1;           // Exit method defined by user
    String doSomething() = 2;
}
```
Note the AIDL ids are `transactionCode - FIRST_CALL_TRANSACTION(1)`; when you give one method an explicit id, give all of them one (the demo does).

`demo/src/main/java/rikka/shizuku/demo/service/UserService.java`:
```java
public class UserService extends IUserService.Stub {
    /** Constructor is required. */
    public UserService() { Log.i("UserService", "constructor"); }

    /** Constructor with Context. This is only available from Shizuku API v13.
     *  This method need to be annotated with {@link Keep} to prevent ProGuard from removing it. */
    @Keep
    public UserService(Context context) { Log.i("UserService", "constructor with Context: context=" + context.toString()); }

    /** Reserved destroy method */
    @Override public void destroy() { Log.i("UserService", "destroy"); System.exit(0); }
    @Override public void exit() { destroy(); }
    @Override public String doSomething() throws RemoteException {
        return "pid=" + Os.getpid() + ", uid=" + Os.getuid() + ", " + stringFromJNI();
    }
    static { System.loadLibrary("hello-jni"); }
    public static native String stringFromJNI();
}
```
Sources: https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/demo/src/main/aidl/rikka/shizuku/demo/IUserService.aidl ; https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/demo/src/main/java/rikka/shizuku/demo/service/UserService.java

### 4.5 ProGuard / R8

Demo `proguard-rules.pro` (entire file):
```
-keepclassmembers class rikka.shizuku.demo.service.UserService {
    public <init>(...);
}
```
https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/demo/proguard-rules.pro

For a Kotlin app with `minifyEnabled true`, use the stronger form and always set `tag()` so the server key does not change when R8 renames the class between builds:
```
-keep class app.prism.shizuku.ShellService { public <init>(...); public <methods>; }
-keep class app.prism.shizuku.IShellService** { *; }
```
The `api`/`provider` AARs ship no consumer ProGuard rules of their own (api/build.gradle has none); `rikka.shizuku.*` is referenced directly so R8 keeps what is used.

---

## 5. Pitfalls specific to this device / Android 12

1. **Non-root = restart after every reboot.** Shizuku docs: "Due to system limitations, the startup steps need to be performed again after each reboot." (wireless debugging, Android 11+). "If it does not start, try disabling and enabling wireless debugging." https://shizuku.rikka.app/guide/setup/ The v13.6.0 trusted-WLAN auto-start is Android 13+ only, so not for the OnePlus 7 on A12.
2. **Binder death = everything gone.** When the server stops (reboot, user stops Shizuku, wireless debugging toggled off) the library resets state and fires `OnBinderDeadListener`; the user-service process exits with the server; all `Shizuku.*` calls throw `IllegalStateException` until a new binder arrives. Design the bridge as a state machine, never cache `uid`/`version` outside the `Ready` state.
3. **Silent bind failure.** If the class cannot be instantiated (`unable to start service ...` in the server log; e.g. `NullPointerException` in `LoadedApk.makeApplicationInner` seen on some OEM ROMs with v13.6.0: Shizuku issues #1119 and #2048, Transsion/Android 15; not reported for OxygenOS 12), the server drops the record after 30 s and **never calls your `ServiceConnection`**. Put a timeout on bind (the bridge below uses 35 s) and fall back to `peekUserService`.
4. **Every reinstall kills the user service** (`ApkChangedObservers`). During development expect `onServiceDisconnected` after each deploy; rebind lazily.
5. **`processNameSuffix` NPE** (section 0). A third-party project hit exactly this: https://github.com/DSH-APP/DSHA/issues/50 (search result; summary only).
6. **Do not add `android:process` or rely on `Application.onCreate`** in the service (section 0).
7. **Permission dialog** is shown by the Shizuku app; the result comes only via `OnRequestPermissionResultListener`, on the main thread; `shouldShowRequestPermissionRationale() == true` means the user ticked "don't ask again", so send them to the Shizuku app instead of re-requesting.
8. **OxygenOS-specific**: a search result (XDA "Shizuku on OOS", page returned HTTP 403, **UNVERIFIED**) reports that disabling "system optimization" in Developer Options stops OxygenOS from killing/stalling Shizuku; also exclude the Shizuku app and your app from battery optimisation.
9. Shell uid limits: README: "what ADB can do is significantly different from ROOT". Shell respects Android permissions granted to `com.android.shell`; no Linux-level root file access. `getUid() == 2000` is what you will see.
10. The whole `Shizuku` API is static and process-global; register listeners once (e.g. in `Application.onCreate` via the bridge), not per-Activity, in a Compose single-Activity app.

---

## 6. Implementation: `ShizukuBridge` (Kotlin) + `ShellService`

Package placeholder `app.prism.shizuku`; replace with your `applicationId`-relative package.

### 6.1 `src/main/aidl/app/prism/shizuku/IShellService.aidl`

```aidl
package app.prism.shizuku;

interface IShellService {
    // Reserved by the Shizuku server: transaction 16777115 == 16777114 + FIRST_CALL_TRANSACTION
    void destroy() = 16777114;

    void exit() = 1;

    // Runs `sh -c command` inside the shell-uid process. Returns a Bundle with
    // "code" (int), "out" (String), "err" (String).
    Bundle exec(String command, int timeoutMs) = 2;
}
```

### 6.2 `ShellService.kt` (runs in the `app.prism:shell` process as uid 2000)

```kotlin
package app.prism.shizuku

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Instantiated by the Shizuku server via reflection (see UserService.create):
 * it tries the (Context) constructor first, then the no-arg one. Both must be public.
 * The Context is a bare android.app.Application; no custom Application code runs here.
 */
@Keep
class ShellService : IShellService.Stub {

    constructor() : super()

    @Keep
    @Suppress("unused")
    constructor(context: Context) : super()

    override fun exec(command: String, timeoutMs: Int): Bundle {
        val proc = ProcessBuilder("sh", "-c", command)
            .directory(File("/"))
            .redirectErrorStream(false)
            .start()

        // Drain both streams concurrently to avoid pipe-buffer deadlock.
        val outT = Thread { proc.inputStream.bufferedReader().readText().let { out = it } }
        val errT = Thread { proc.errorStream.bufferedReader().readText().let { err = it } }
        outT.start(); errT.start()

        val finished = proc.waitFor(timeoutMs.toLong().coerceAtLeast(1), TimeUnit.MILLISECONDS)
        if (!finished) proc.destroyForcibly()
        outT.join(); errT.join()

        return Bundle().apply {
            putInt("code", if (finished) proc.exitValue() else -1)
            putString("out", out)
            putString("err", if (finished) err else (err + "\n[timeout after ${timeoutMs}ms]"))
        }
    }

    @Volatile private var out = ""
    @Volatile private var err = ""

    override fun exit() = destroy()

    /** Called by the Shizuku server with transaction 16777115 (oneway). Must terminate the process. */
    override fun destroy() {
        exitProcess(0)
    }
}
```

`Bundle` crosses the process boundary as a framework Parcelable, so no custom `Parcelable` AIDL declarations are needed. Note: `out`/`err` fields make this service single-flight; the bridge serialises calls with a `Mutex`.

### 6.3 `ShizukuBridge.kt` (app process)

```kotlin
package app.prism.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import app.prism.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

object ShizukuBridge {

    sealed interface State {
        /** No binder: Shizuku app not installed, not started, or server died. */
        data object NotRunning : State
        /** Server v10 or lower; UserService unsupported. */
        data class Unsupported(val version: Int) : State
        /** Binder alive, permission not granted. [permanentlyDenied] == shouldShowRequestPermissionRationale(). */
        data class NeedsPermission(val permanentlyDenied: Boolean) : State
        /** Binder alive and permission granted. uid 0 = root, 2000 = adb shell. */
        data class Ready(val uid: Int, val version: Int, val patch: Int, val serviceBound: Boolean) : State
    }

    private const val REQUEST_CODE = 0x5A1C
    private const val BIND_TIMEOUT_MS = 35_000L   // server drops the record after 30 s

    private val _state = MutableStateFlow<State>(State.NotRunning)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var service: IShellService? = null
    private var pendingBind: CompletableDeferred<IShellService>? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private val execMutex = Mutex()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name)
    )
        .daemon(false)                       // die with the app; default is true
        .processNameSuffix("shell")          // REQUIRED; process shows as <applicationId>:shell
        .debuggable(BuildConfig.DEBUG)
        .tag("prism-shell")                  // stable across R8 renames
        .version(BuildConfig.VERSION_CODE)   // bump => server restarts the service

    private val connection = object : ServiceConnection {
        // Both callbacks are posted to the main looper by ShizukuServiceConnection.
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                pendingBind?.completeExceptionally(IllegalStateException("invalid binder for $name"))
                pendingBind = null
                return
            }
            val svc = IShellService.Stub.asInterface(binder)
            service = svc
            pendingBind?.complete(svc)
            pendingBind = null
            _state.update { s -> if (s is State.Ready) s.copy(serviceBound = true) else s }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            _state.update { s -> if (s is State.Ready) s.copy(serviceBound = false) else s }
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshState() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        pendingBind?.completeExceptionally(IllegalStateException("Shizuku binder died"))
        pendingBind = null
        pendingPermission?.complete(false)
        pendingPermission = null
        _state.value = State.NotRunning
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        pendingPermission?.complete(granted)
        pendingPermission = null
        refreshState()
    }

    /** Call once, e.g. from Application.onCreate(). Idempotent enough for a single process. */
    fun install() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        if (!Shizuku.pingBinder()) _state.value = State.NotRunning
    }

    fun refreshState() {
        _state.value = computeState()
    }

    private fun computeState(): State = runCatching {
        if (!Shizuku.pingBinder()) return State.NotRunning
        if (Shizuku.isPreV11()) return State.Unsupported(-1)
        val version = Shizuku.getVersion()
        if (version < 11) return State.Unsupported(version)
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return State.NeedsPermission(permanentlyDenied = Shizuku.shouldShowRequestPermissionRationale())
        }
        State.Ready(
            uid = Shizuku.getUid(),
            version = version,
            patch = Shizuku.getServerPatchVersion(),
            serviceBound = service?.asBinder()?.pingBinder() == true,
        )
    }.getOrElse { State.NotRunning }   // IllegalStateException("binder haven't been received") etc.

    /**
     * Shows the Shizuku permission dialog. Returns true when granted.
     * Returns false immediately if the binder is absent or the user chose "don't ask again".
     */
    suspend fun requestPermission(): Boolean {
        when (val s = computeState()) {
            is State.Ready -> return true
            is State.NeedsPermission -> if (s.permanentlyDenied) return false
            else -> return false
        }
        pendingPermission?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        return runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
            deferred.await()
        }.getOrElse { pendingPermission = null; false }
    }

    private suspend fun bindService(): IShellService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        pendingBind?.let { return it.await() }

        val st = computeState()
        check(st is State.Ready) { "Shizuku not ready: $st" }
        check(st.version >= 10) { "UserService requires Shizuku API 10, got ${st.version}" }

        val deferred = CompletableDeferred<IShellService>()
        pendingBind = deferred
        return try {
            withContext(Dispatchers.Main) { Shizuku.bindUserService(userServiceArgs, connection) }
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        } catch (t: Throwable) {
            pendingBind = null
            throw t
        }
    }

    /** Runs `sh -c cmd` as uid 2000 (or 0 under root). Non-zero exit code is returned as failure. */
    suspend fun exec(cmd: String, timeoutMs: Int = 15_000): Result<String> = runCatching {
        val svc = bindService()
        val bundle = execMutex.withLock {
            withContext(Dispatchers.IO) { svc.exec(cmd, timeoutMs) }
        }
        val code = bundle.getInt("code", -1)
        val out = bundle.getString("out").orEmpty()
        val err = bundle.getString("err").orEmpty()
        if (code != 0) throw ShellException(code, out, err)
        out
    }

    /** Stops and kills the shell process (server sends transaction 16777115 -> ShellService.destroy()). */
    fun unbindService() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        service = null
        _state.update { s -> if (s is State.Ready) s.copy(serviceBound = false) else s }
    }

    class ShellException(val exitCode: Int, val stdout: String, val stderr: String) :
        RuntimeException("exit $exitCode: ${stderr.ifBlank { stdout }.trim()}")
}
```

### 6.4 Wiring

```kotlin
// Application
class PrismApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuBridge.install()
    }
}

// Compose
@Composable
fun ShizukuStatus() {
    val state by ShizukuBridge.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    when (val s = state) {
        ShizukuBridge.State.NotRunning -> Text("Start Shizuku (wireless debugging) and reopen the app")
        is ShizukuBridge.State.Unsupported -> Text("Shizuku v${s.version} too old")
        is ShizukuBridge.State.NeedsPermission ->
            if (s.permanentlyDenied) Text("Allow this app inside the Shizuku app")
            else Button({ scope.launch { ShizukuBridge.requestPermission() } }) { Text("Grant Shizuku access") }
        is ShizukuBridge.State.Ready -> Button({
            scope.launch {
                ShizukuBridge.exec("cmd statusbar expand-notifications")
                    .onSuccess { /* ok */ }.onFailure { /* show it.message */ }
            }
        }) { Text("Expand shade (uid ${s.uid})") }
    }
}
```

Manifest additions for all of the above: **only** the `<provider>` from section 2.

---

## 7. Calling system services directly from the app process (`ShizukuBinderWrapper`)

### 7.1 Mechanism (verified)

`ShizukuBinderWrapper implements IBinder`; its `transact()` wraps the original binder, code, (v13+) flags and the data parcel into a parcel with descriptor `moe.shizuku.server.IShizukuService` and calls `Shizuku.transactRemote(...)`, so the server (uid 2000) performs the transaction on your behalf. Javadoc example:

```java
IPackageManager pm = IPackageManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package")));
pm.getInstalledPackages(0, 0);
```
`SystemServiceHelper.getSystemService(String name)` reflects `android.os.ServiceManager.getService(name)` (annotated `@UnsupportedAppUsage` in android-12.0.0_r1, i.e. greylist: reflection works on API 31 without a bypass) and caches the `IBinder`. `SystemServiceHelper.getTransactionCode`/`obtainParcel` are deprecated; `obtainParcel` now throws `UnsupportedOperationException("...please use ShizukuBinderWrapper")`.

Demo (`ShizukuSystemServerApi.java`):
```java
IUserManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.USER_SERVICE)));
IPackageInstaller.Stub.asInterface(new ShizukuBinderWrapper(packageInstaller.asBinder())); // wrap nested binders too
```
Demo `DemoApplication.attachBaseContext`: `if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("L");` (exempts everything) so that hidden `Stub` classes and hidden members resolve at runtime.

Sources: https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/api/src/main/java/rikka/shizuku/ShizukuBinderWrapper.java ; .../SystemServiceHelper.java ; https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/demo/src/main/java/rikka/shizuku/demo/util/ShizukuSystemServerApi.java ; https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/demo/src/main/java/rikka/shizuku/demo/DemoApplication.java ; https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/core/java/android/os/ServiceManager.java ; https://raw.githubusercontent.com/LSPosed/AndroidHiddenApiBypass/master/README.md

### 7.2 The catch: you need the `Stub` class at compile time

`IStatusBarService.Stub`, `IPackageManager.Stub` etc. are hidden; the demo compiles against a private `demo-hidden-api-stub` module (`compileOnly project(':demo-hidden-api-stub')`) that is **not published**. Options:

A. **Copy the AIDL into your app** (no hidden-API access at all; recommended for one or two methods). Transaction codes are `FIRST_CALL_TRANSACTION + ordinal` in the platform `.aidl`, so keep the ordinals with explicit ids and the same package so the generated `DESCRIPTOR` matches `com.android.internal.statusbar.IStatusBarService`. In android-12.0.0_r1 the first methods are, in order: `expandNotificationsPanel()` (0), `collapsePanels()` (1), `togglePanel()` (2), `disable(...)` (3) ... (`expandSettingsPanel(String)` ordinal reported as 13 by the fetch summary: **UNVERIFIED**, count it yourself from the tag's file before using).

`src/main/aidl/com/android/internal/statusbar/IStatusBarService.aidl`:
```aidl
package com.android.internal.statusbar;

/** Trimmed copy of AOSP android-12.0.0_r1 IStatusBarService; ids = ordinal in the platform file. */
interface IStatusBarService {
    void expandNotificationsPanel() = 0;
    void collapsePanels() = 1;
    void togglePanel() = 2;
}
```
```kotlin
import com.android.internal.statusbar.IStatusBarService
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

val statusBar: IStatusBarService by lazy {
    IStatusBarService.Stub.asInterface(
        ShizukuBinderWrapper(SystemServiceHelper.getSystemService("statusbar"))
    )
}
// requires State.Ready; throws RuntimeException/SecurityException otherwise
statusBar.expandNotificationsPanel()
```
Whether shell (uid 2000) passes the `STATUS_BAR` permission check inside `StatusBarManagerService.expandNotificationsPanel()` is **UNVERIFIED** here; the verified, permission-safe route on Android 12 is the shell command below.

B. **Shell command via the UserService** (verified to exist in android-12.0.0_r1 `StatusBarShellCommand`): `cmd statusbar expand-notifications | expand-settings | collapse | add-tile <component> | remove-tile <component> | click-tile <component> | check-support | get-status-icons | send-disable-flag <flags...> | disable-for-setup <bool> | tracing start|stop`. https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/statusbar/StatusBarShellCommand.java
   `ShizukuBridge.exec("cmd statusbar expand-notifications")` is the simplest implementation and needs no hidden API.

C. Rikka's stubs: `compileOnly("dev.rikka.hidden:stub:4.4.0")` + `implementation("dev.rikka.hidden:compat:4.4.0")` + Gradle plugin `dev.rikka.tools.refine` 4.4.0 (`implementation("dev.rikka.tools.refine:runtime:4.4.0")`). Coverage of `IStatusBarService` is **UNVERIFIED**; use for `IPackageManager`/`IActivityManager`-style work if you go that way. https://github.com/RikkaApps/HiddenApiRefinePlugin ; https://raw.githubusercontent.com/RikkaW/HiddenApi/master/README.md

For any option that touches hidden **members** by reflection (not merely loading a class) add in `Application.attachBaseContext`:
```kotlin
if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("L")
```
and, per the HiddenApiBypass README, optionally `android { dependenciesInfo { includeInApk = false; includeInBundle = false } }`.

---

## 8. Quick verification checklist on the OnePlus 7

1. Shizuku app 13.6.0 installed, started via Wireless debugging (Developer options -> Wireless debugging -> pair with code from the Shizuku notification). Shizuku home screen must say "running, version 13.6.0, adb".
2. `adb shell dumpsys package <applicationId> | grep -A3 shizuku` shows the provider exported with permission `INTERACT_ACROSS_USERS_FULL`.
3. Launch app -> `State.NeedsPermission(false)` -> tap Grant -> Shizuku dialog -> `State.Ready(uid=2000, version=13, patch=6, serviceBound=false)`.
4. `exec("id")` -> `uid=2000(shell) gid=2000(shell) ... context=u:r:shell:s0`; `adb shell ps -A | grep <applicationId>:shell` shows the service process.
5. Stop Shizuku from its app -> `State.NotRunning`, service process gone, next `exec` fails with `IllegalStateException("Shizuku not ready")`.
6. Reinstall the APK while bound -> `onServiceDisconnected` -> `serviceBound=false` -> next `exec` rebinds automatically.

---

## 9. Source index (all opened 2026-09-20)

- README: https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/README.md
- API: https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/api/src/main/java/rikka/shizuku/Shizuku.java ; ShizukuBinderWrapper.java ; SystemServiceHelper.java ; ShizukuRemoteProcess.java ; ShizukuServiceConnection.java ; ShizukuServiceConnections.java ; rikka/sui/Sui.java
- Constants: https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/shared/src/main/java/rikka/shizuku/ShizukuApiConstants.java
- Provider: https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/provider/src/main/java/rikka/shizuku/ShizukuProvider.java ; provider/src/main/AndroidManifest.xml
- Demo: demo/build.gradle ; demo/proguard-rules.pro ; demo/src/main/AndroidManifest.xml ; demo/src/main/aidl/rikka/shizuku/demo/IUserService.aidl ; demo/src/main/java/rikka/shizuku/demo/{DemoActivity,DemoApplication}.java ; demo/src/main/java/rikka/shizuku/demo/service/UserService.java ; demo/src/main/java/rikka/shizuku/demo/util/ShizukuSystemServerApi.java (all under https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/)
- Server side: https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/server-shared/src/main/java/rikka/shizuku/server/{UserService,UserServiceManager,UserServiceRecord}.java ; https://raw.githubusercontent.com/RikkaApps/Shizuku/master/server/src/main/java/rikka/shizuku/server/ShizukuUserServiceManager.java ; https://raw.githubusercontent.com/RikkaApps/Shizuku/master/starter/src/main/java/moe/shizuku/starter/ServiceStarter.java
- Versions: https://repo1.maven.org/maven2/dev/rikka/shizuku/api/maven-metadata.xml ; .../api/13.1.5/api-13.1.5.pom ; .../provider/13.1.5/provider-13.1.5.pom ; https://api.github.com/repos/RikkaApps/Shizuku/releases/latest ; https://api.github.com/repos/RikkaApps/Shizuku/contents/?ref=v13.6.0 ; https://api.github.com/repos/RikkaApps/Shizuku-API/commits?per_page=5 ; https://github.com/RikkaApps/Shizuku-API/commit/284db86e8cae49030546a5fed7b5bd1a211a5278
- AGP: https://developer.android.com/build/releases/past-releases/agp-8-0-0-release-notes
- AOSP android-12.0.0_r1: core/java/android/app/LoadedApk.java ; core/java/android/os/ServiceManager.java ; core/java/com/android/internal/statusbar/IStatusBarService.aidl ; services/core/java/com/android/server/statusbar/StatusBarShellCommand.java
- Setup guide: https://shizuku.rikka.app/guide/setup/
- Issues: https://github.com/RikkaApps/Shizuku/issues/2048 ; https://github.com/RikkaApps/Shizuku/issues/1119 ; https://github.com/RikkaApps/Shizuku-API/issues/193
- UNVERIFIED (could not open or only summarised): XDA "Shizuku on OOS" thread (HTTP 403); `ServiceStarter.java` full text (summary only); `dev.rikka.hidden:stub` class list; `expandSettingsPanel` ordinal; Shizuku-API GitHub Releases page lists only up to v12.1.0 (13.x were never tagged as GitHub releases, only on Maven).
