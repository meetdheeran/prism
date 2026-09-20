# Third-party default digital assistant on Android 12 (API 31) + getting the current screen

Research date: 2026-09-20. Target: OnePlus 7 (GM1900), OxygenOS 12 / Android 12 (API 31), non-root, Shizuku 13.6.0 running as shell (uid 2000). Toolchain: Kotlin 2.0.20, AGP 8.5.2, Gradle 8.7, compileSdk 34, minSdk 31, Compose BOM 2024.09.03.

Verification method: developer.android.com reference pages were fetched, but the page summaries returned by the fetcher were unreliable (two fetches gave different "Added in API level" values for the same method, and one invented RoleManager constants). Every API-level claim below was therefore cross-checked against the SDK `api/android.txt` files in `prebuilts/sdk` (android12-release branch), and every behavioural claim against the android12-release source on android.googlesource.com / the aosp-mirror on GitHub. Each fact cites the exact file. Anything not opened is marked **UNVERIFIED**.

Source bundle used (all android12-release unless noted):
- `frameworks/base/core/java/android/service/voice/VoiceInteractionService.java`, `VoiceInteractionSessionService.java`, `VoiceInteractionSession.java`, `VoiceInteractionServiceInfo.java` (https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/service/voice/)
- `frameworks/base/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java`, `VoiceInteractionSessionConnection.java`
- `frameworks/base/services/core/java/com/android/server/am/AssistDataRequester.java`, `.../wm/ActivityTaskManagerService.java`, `.../wm/WindowManagerService.java`, `.../wm/DisplayContent.java`, `.../media/projection/MediaProjectionManagerService.java`
- `frameworks/base/core/java/android/app/assist/AssistStructure.java`, `frameworks/base/core/java/android/app/ActivityThread.java`, `frameworks/base/core/java/android/provider/Settings.java`, `frameworks/base/core/java/android/content/Intent.java`, `frameworks/base/core/res/res/values/attrs.xml`, `frameworks/base/core/res/AndroidManifest.xml`, `frameworks/base/packages/Shell/AndroidManifest.xml`, `frameworks/base/cmds/screencap/screencap.cpp`, `frameworks/base/media/java/android/media/projection/MediaProjection.java` + `MediaProjectionManager.java`, `frameworks/base/core/java/android/content/pm/ServiceInfo.java`, `frameworks/base/core/java/android/speech/RecognitionService.java`, `frameworks/base/core/java/com/android/internal/app/AssistUtils.java`
- `frameworks/base/tests/VoiceInteraction/` (AOSP reference assistant: `AndroidManifest.xml`, `res/xml/interaction_service.xml`, `res/xml/recognition_service.xml`, `MainRecognitionService.java`, `MainInteractionSessionService.java`, `MainInteractionSession.java`)
- `packages/modules/Permission/framework-s/java/android/app/role/RoleManager.java`, `PermissionController/res/xml/roles.xml`, `PermissionController/src/com/android/permissioncontroller/role/model/AssistantRoleBehavior.java`, `PermissionController/src/com/android/permissioncontroller/role/ui/RequestRoleActivity.java`, `PermissionController/AndroidManifest.xml`, `PermissionController/res/values/strings.xml`, `service/java/com/android/role/RoleShellCommand.java`
- `packages/apps/Settings/src/com/android/settings/applications/assist/*.java`, `res/xml/manage_assist.xml`, `res/xml/apps.xml`, `res/values/strings.xml`, `AndroidManifest.xml`
- `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp`, `BufferLayer.cpp`, `Layer.cpp`
- `prebuilts/sdk/{28,29,31}/public/api/android.txt`
- Shizuku-API master: `README.md`, `api/src/main/java/rikka/shizuku/Shizuku.java`, `demo/` (https://github.com/RikkaApps/Shizuku-API); Maven Central metadata for `dev.rikka.shizuku:api`
- androidx main: `ViewTreeLifecycleOwner.android.kt`, `ViewTreeViewModelStoreOwner.android.kt`, `ViewTreeSavedStateRegistryOwner.android.kt`, `compose/ui/ui/.../WindowRecomposer.android.kt`; Google Maven POMs `androidx.compose:compose-bom:2024.09.03`, `androidx.compose.ui:ui-android:1.7.3`
- https://developer.android.com/training/articles/assistant, https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/compose-in-views, https://developer.android.com/media/grow/media-projection, https://developer.android.com/develop/background-work/services/fg-service-types

---

## 0. Executive summary (what to build)

1. Declare a `VoiceInteractionService` (must *require* `android.permission.BIND_VOICE_INTERACTION`, intent-filter action `android.service.voice.VoiceInteractionService`, meta-data `android.voice_interaction` -> `res/xml/voice_interaction.xml` with root tag `<voice-interaction-service>`), a `VoiceInteractionSessionService`, and a `RecognitionService`. **All three are mandatory**: `VoiceInteractionServiceInfo` sets a parse error `"No sessionService specified"` / `"No recognitionService specified"` if either attribute is missing, and the assistant-role qualifier in PermissionController additionally requires `supportsAssist="true"`.
2. On Android 12 the default assistant is the holder of the **public** role `RoleManager.ROLE_ASSISTANT` (`"android.app.role.ASSISTANT"`, public since API 29 per `prebuilts/sdk/29/public/api/android.txt`). The role is declared `requestable="false"` in `roles.xml`, so `createRequestRoleIntent(ROLE_ASSISTANT)` is a no-op (RequestRoleActivity logs `"Role is not requestable"` and finishes). The user picks it in **Settings > Apps > Default apps > Digital assistant app** (PermissionController's `DefaultAppListActivity`/`DefaultAppActivity`). From the app you can only *open* that UI: `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS` (default apps list) or `Settings.ACTION_VOICE_INPUT_SETTINGS` (the "Digital assistant app" screen with the toggles).
3. **With Shizuku you can set the role directly**: shell holds `android.permission.MANAGE_ROLE_HOLDERS` and Android 12 ships `cmd role add-role-holder [--user N] android.app.role.ASSISTANT <pkg>` (`RoleShellCommand.java`, `@RequiresApi(S)`). The package must still qualify (service + XML as above).
4. "Use text from screen" / "Use screenshot" live on the `ManageAssist` screen (reached from the gear next to "Default digital assistant app", or via `Settings.ACTION_VOICE_INPUT_SETTINGS`). Keys: `Settings.Secure.ASSIST_STRUCTURE_ENABLED = "assist_structure_enabled"`, `Settings.Secure.ASSIST_SCREENSHOT_ENABLED = "assist_screenshot_enabled"`; both `@hide @Readable`; framework reads them with **default 1** (`getIntForUser(..., 1, mUser)`), so the `null` you read on the device means *enabled*.
5. When the user invokes the assistant (long-press home / gesture), the system calls `showSessionForActiveService`, which ORs in `SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT` unconditionally. Your session gets `onShow(args, flags)`, then `onHandleAssist(AssistState)` (API 29+) with `AssistStructure`/`AssistContent`, then `onHandleScreenshot(Bitmap?)`. Screenshot delivery additionally requires structure fetching to be allowed (`allowFetchScreenshot &= fetchData && isAssistDataAllowed && ...`).
6. `FLAG_SECURE`: callbacks still fire; the structure is a stub (root node only, `isAssistBlocked() == true`), `AssistContent` gets no default intent, and the assist screenshot (taken by system_server with `captureSecureLayers=false`) has the secure window **blacked out**. `screencap` run as shell **fails outright** (`PERMISSION_DENIED`, exit 1, no bytes) whenever *any* visible layer is secure because shell lacks `CAPTURE_BLACKOUT_CONTENT`. MediaProjection gets black for secure layers.
7. Hosting Compose in the session: return a container from `onCreateContentView()` and set `setViewTreeLifecycleOwner`, `setViewTreeSavedStateRegistryOwner`, `setViewTreeViewModelStoreOwner` **on that returned view** (Compose's `contentChild` lookup stops at the session's internal `android.R.id.content` FrameLayout, so the returned view is where the recomposer/lifecycle is resolved). Drive a `LifecycleRegistry` from `onCreate/onShow/onHide/onDestroy`.
8. Screen-capture fallbacks: prefer Shizuku UserService running `/system/bin/screencap -p` (shell has `READ_FRAME_BUFFER`); fall back to the assist screenshot; last resort MediaProjection (needs consent dialog each time + a running foreground service of type `mediaProjection` because targetSdk >= 29).

---

## 1. VoiceInteractionService (the always-running "interactor")

Source: `VoiceInteractionService.java` (android12-release).

- Class javadoc: "Top-level service of the current global voice interactor ... The current VoiceInteractionService that has been selected by the user is kept always running by the system ... Because this service is always running, it should be kept as lightweight as possible. Heavy-weight operations (including showing UI) should be implemented in the associated VoiceInteractionSessionService ... and that service should run in a separate process from this one."
- `SERVICE_INTERFACE = "android.service.voice.VoiceInteractionService"` — "The Intent that must be declared as handled by the service. To be supported, the service must also require the BIND_VOICE_INTERACTION permission".
- `SERVICE_META_DATA = "android.voice_interaction"` — "This meta-data should reference an XML resource containing a `<voice-interaction-service>` tag."
- `onBind(Intent)` returns the binder only when the action equals `SERVICE_INTERFACE` (WebFetch summary of the same file; consistent with the source).
- `onReady()` — system is ready; `showSession()` throws `IllegalStateException("Not available until onReady() is called")` before that.
- `showSession(Bundle args, int flags)` — "flags ... May be any combination of VoiceInteractionSession.SHOW_WITH_ASSIST and VoiceInteractionSession.SHOW_WITH_SCREENSHOT to request that the system generate and deliver assist data on the current foreground app as part of showing the session UI." You can call this from your own UI (e.g. a floating button / notification) as long as you are the current interactor — every one of these binder calls is guarded by `enforceIsCurrentVoiceInteractionService()` -> `SecurityException("Caller is not the current voice interaction service")` (`VoiceInteractionManagerService.java` line 1813).
- `static isActiveService(Context, ComponentName)` — reads `Settings.Secure.VOICE_INTERACTION_SERVICE` and compares (public method, usable to detect "are we the assistant").
- `setDisabledShowContext(int)` / `getDisabledShowContext()` / `getUserDisabledShowContext()` — flags of `SHOW_WITH_ASSIST`/`SHOW_WITH_SCREENSHOT` you (or the user, via Settings) turned off.
- `onLaunchVoiceAssistFromKeyguard()` — only invoked if XML has `supportsLaunchVoiceAssistFromKeyguard="true"`; "Implementations must start activities with `FLAG_SHOW_WHEN_LOCKED`".
- `BIND_VOICE_INTERACTION` is `protectionLevel="signature"` (`core/res/AndroidManifest.xml` line 3787) — only the system can bind; your manifest *requires* it, it does not *use* it.

### 1.1 XML attributes (`res/xml/voice_interaction.xml`)

Source: `frameworks/base/core/res/res/values/attrs.xml` lines 8704-8725, `declare-styleable name="VoiceInteractionService"`:

| attr | type | comment in attrs.xml |
|---|---|---|
| `sessionService` | string | "The service that hosts active voice interaction sessions. This is required." |
| `recognitionService` | string | "The service that provides voice recognition. This is required. When the user selects this voice interaction service, they will also be implicitly selecting the component here for their recognition service." |
| `settingsActivity` | string | activity opened from the gear in Settings (see `DefaultAssistPreferenceController.getAssistSettingsActivity`, only returned when `getSupportsAssist()`). |
| `supportsAssist` | boolean | "Flag indicating whether this voice interaction service is capable of handling the assist action." Required to qualify for the assistant role. |
| `supportsLaunchVoiceAssistFromKeyguard` | boolean | NOTE the real attribute name — it is **not** `supportsLaunchFromKeyguard` (that is only the Java getter name `getSupportsLaunchFromKeyguard()`). |
| `supportsLocalInteraction` | boolean | in-activity voice interaction (API 24+). |
| `hotwordDetectionService` | string | `@hide @SystemApi` — do not use. |

`RecognitionService` styleable (attrs.xml 8693): `settingsActivity`, `selectableAsDefault` (boolean).

Parsing rules (`VoiceInteractionServiceInfo.java`, constructor `(PackageManager, ServiceInfo)`):
```java
if (!Manifest.permission.BIND_VOICE_INTERACTION.equals(si.permission)) {
    mParseError = "Service does not require permission " + Manifest.permission.BIND_VOICE_INTERACTION; return; }
... if (parser == null) { mParseError = "No android.voice_interaction meta-data for " + si.packageName; return; }
... if (!"voice-interaction-service".equals(nodeName)) { mParseError = "Meta-data does not start with voice-interaction-service tag"; return; }
... if (mSessionService == null)     { mParseError = "No sessionService specified"; return; }
    if (mRecognitionService == null) { mParseError = "No recognitionService specified"; return; }
```
So a RecognitionService **must be declared** (attribute-level requirement; the parser does not verify that the component exists, but `RoleObserver` in `VoiceInteractionManagerService` logs "The RecognitionService must be set to avoid boot loop on earlier platform version. Also make sure that this is a valid RecognitionService when running on Android 11 or earlier." and writes an empty component if it is null).

Reference XML (AOSP `tests/VoiceInteraction/res/xml/interaction_service.xml`, verbatim):
```xml
<voice-interaction-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:sessionService="com.android.test.voiceinteraction.MainInteractionSessionService"
    android:recognitionService="com.android.test.voiceinteraction.MainRecognitionService"
    android:settingsActivity="com.android.test.voiceinteraction.SettingsActivity"
    android:supportsAssist="true"
    android:supportsLocalInteraction="true" />
```

## 2. VoiceInteractionSessionService

Source: `VoiceInteractionSessionService.java`.
- `public abstract VoiceInteractionSession onNewSession(Bundle args);`
- `onBind(Intent)` returns `mInterface.asBinder()` **without checking the action** — no intent-filter is needed on this service (the system binds it explicitly by the component named in `sessionService`).
- After `onNewSession` returns, the framework calls `mSession.doCreate(mSystemService, token)` -> your `onCreate()`.
- The AOSP sample declares it as `<service android:name="MainInteractionSessionService" android:permission="android.permission.BIND_VOICE_INTERACTION" android:process=":session">` (no intent-filter, no `exported`). System binds via `bindServiceAsUser(..., BIND_AUTO_CREATE | BIND_TREAT_LIKE_ACTIVITY | BIND_SCHEDULE_LIKE_TOP_APP | BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS, ...)` (`VoiceInteractionSessionConnection.showLocked`).

## 3. VoiceInteractionSession (the UI + data receiver)

Source: `VoiceInteractionSession.java` (android12-release), API levels from `prebuilts/sdk/*/public/api/android.txt`.

Constants (bit values from source):
```
SHOW_WITH_ASSIST                = 1<<0   // API 21
SHOW_WITH_SCREENSHOT            = 1<<1   // API 21
SHOW_SOURCE_ASSIST_GESTURE      = 1<<2
SHOW_SOURCE_APPLICATION         = 1<<3
SHOW_SOURCE_ACTIVITY            = 1<<4
SHOW_SOURCE_PUSH_TO_TALK        = 1<<5
SHOW_SOURCE_NOTIFICATION        = 1<<6   // API 26
SHOW_SOURCE_AUTOMOTIVE_SYSTEM_UI= 1<<7   // API 30
```

Lifecycle/UI (all verified in source):
- `onCreate()` — "Initialize a new session. At this point you don't know what it will be used for; you find that out in onShow()." Window is created lazily in `ensureWindowCreated()`; calling it with `mUiEnabled == false` throws `IllegalStateException("setUiEnabled is false")`.
- Window: `new SoftInputWindow(mContext, "VoiceInteractionSession", mTheme, ..., WindowManager.LayoutParams.TYPE_VOICE_INTERACTION, Gravity.BOTTOM, true)`; flags `FLAG_HARDWARE_ACCELERATED | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR`; `setFitInsetsTypes(0)`; layout `MATCH_PARENT x MATCH_PARENT`; root = `com.android.internal.R.layout.voice_interaction_session`; `mContentFrame = mRootView.findViewById(android.R.id.content)`; default theme `Theme_DeviceDefault_VoiceInteractionSession`. `setTheme(int)` "must be set before onCreate ... typically call it in your constructor".
- `onCreateContentView(): View?` — "Hook in which to create the session's UI." Called from `ensureWindowAdded()`; result is added via `setContentView(view)` -> `mContentFrame.removeAllViews(); mContentFrame.addView(view, LayoutParams(MATCH_PARENT, MATCH_PARENT)); mContentFrame.requestApplyInsets()`.
- `doShow()` order: `onPrepareShow(args, flags)` -> `ensureWindowAdded()` (creates content view) -> `onShow(args, flags)` -> `mWindow.show()` if `mUiEnabled`.
- `onShow(Bundle args, int showFlags)` — "Called when the session UI is going to be shown. This is called after onCreateContentView ... This may be called while the window is already shown, if a show request has come in while it is shown". `args` example keys from the javadoc: `"invocation_type"`, `"invocation_phone_state"`, `"invocation_time_ms"`, `Intent.EXTRA_TIME`, `Intent.EXTRA_ASSIST_INPUT_DEVICE_ID`.
- `setUiEnabled(boolean)` — "Control whether the UI layer for this session is enabled. It is enabled by default. If set to false, you will not be able to provide a UI through onCreateContentView()." Toggling while visible shows/hides the window.
- `getWindow(): Dialog` — "Retrieve the window being used to show the session's UI." (returns the `SoftInputWindow`, a `Dialog`; use `getWindow().getWindow()` for the `android.view.Window`).
- `hide()` — "Hide the session's UI, if currently shown. Call this when done with user interaction." `finish()` — "Completely destroys the session — the next time it is shown, an entirely new one will be created. Do not normally call this; instead, use hide() and allow the system to destroy if needed."
- `onLockscreenShown()` — "Called when the lockscreen was shown." Default implementation calls `hide()`.
- `onBackPressed()` default calls `hide()`; `onKeyDown` etc. available.
- `getUserDisabledShowContext()` — "which show context flags have been disabled by the user through the system settings UI ... Note that this only tells you about global user settings, not about restrictions that may be applied contextual based on the current application".
- `startAssistantActivity(Intent)` / `startVoiceActivity(Intent)` exist for launching your own activities from the session.

Assist data callbacks:
- `onHandleAssist(@NonNull AssistState state)` — **API 29** (present in `29/public/api/android.txt` line 41811, absent in 28). Default implementation: returns early if data/structure/content are all null; if `state.getIndex() == 0` calls the deprecated `onHandleAssist(Bundle, AssistStructure, AssistContent)` else `onHandleAssistSecondary(...)`. So override the `AssistState` one only.
- `AssistState` (API 29): `isFocused()`, `getIndex()` ("-1 if there was no assist data captured"), `getCount()`, `getActivityId(): ActivityId`, `getAssistData(): Bundle?` ("will be null if the original show request did not specify SHOW_WITH_ASSIST"), `getAssistStructure(): AssistStructure?` ("May be null if assist data has been disabled by the user or device policy; ... will be an empty stub if the application has disabled assist by marking its window as secure"), `getAssistContent(): AssistContent?` ("Will not be automatically filled in with data from the app if the app has marked its window as secure").
- `onHandleScreenshot(@Nullable Bitmap screenshot)` — "Called to receive a screenshot of what the user was currently viewing when an assist session is started. May be null if screenshots are disabled by the user, policy, or application. If the original show request did not specify SHOW_WITH_SCREENSHOT, this method will not be called."
- `onAssistStructureFailure(Throwable)` — called before `onHandleAssist` when the structure fetch failed.
- Multi-window: one `onHandleAssist` per visible activity (index/count); the focused one has `isFocused() == true`.

### 3.1 How the system decides what you get (gating chain)

`VoiceInteractionManagerService.showSessionForActiveService()` (line 1576): `mImpl.showSessionLocked(args, sourceFlags | SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT, ...)` — i.e. the home-long-press / gesture path always requests both.

`VoiceInteractionSessionConnection.showLocked()`:
```java
disabledContext |= getUserDisabledShowContextLocked();
boolean fetchData       = (flags & SHOW_WITH_ASSIST) != 0;
boolean fetchScreenshot = (flags & SHOW_WITH_SCREENSHOT) != 0;
mAssistDataRequester.requestAssistData(topActivitiesToken, fetchData, fetchScreenshot,
        (disabledContext & SHOW_WITH_ASSIST) == 0,
        (disabledContext & SHOW_WITH_SCREENSHOT) == 0, mCallingUid, mSessionComponentName.getPackageName());
boolean needDisclosure = pendingData > 0 || pendingScreenshot > 0;
if (needDisclosure && AssistUtils.shouldDisclose(mContext, mSessionComponentName)) post(mShowAssistDisclosureRunnable);
```
`getUserDisabledShowContextLocked()`:
```java
if (Settings.Secure.getIntForUser(cr, Settings.Secure.ASSIST_STRUCTURE_ENABLED, 1, mUser) == 0) flags |= SHOW_WITH_ASSIST;
if (Settings.Secure.getIntForUser(cr, Settings.Secure.ASSIST_SCREENSHOT_ENABLED, 1, mUser) == 0) flags |= SHOW_WITH_SCREENSHOT;
```
`AssistDataRequester.requestAssistData()` (line 227-235):
```java
isAssistDataAllowed = mActivityTaskManager.isAssistDataAllowedOnCurrentActivity();
allowFetchData &= isAssistDataAllowed;
allowFetchScreenshot &= fetchData && isAssistDataAllowed && (mRequestScreenshotAppOps != OP_NONE);
```
plus app-op checks `OP_ASSIST_STRUCTURE` / `OP_ASSIST_SCREENSHOT` for your uid (default MODE_ALLOWED). `ActivityTaskManagerService.isAssistDataAllowedOnCurrentActivity()` returns false if the focused task is an assistant-type task or there is no top activity; otherwise `DevicePolicyCache.isScreenCaptureAllowed(userId, false)` (device-admin restriction only — **not** FLAG_SECURE). Screenshot is captured by `WindowManagerService.requestAssistScreenshot()` (requires `READ_FRAME_BUFFER`, i.e. system) -> `DisplayContent.screenshotDisplayLocked()` -> `SurfaceControl.captureDisplay(DisplayCaptureArgs.Builder(displayToken).setUseIdentityTransform(inRotation).build())` (no `captureSecureLayers`), returns null if the screen is off. The bitmap is delivered via `IVoiceInteractionSession.handleScreenshot`.

Disclosure: `Settings.Secure.ASSIST_DISCLOSURE_ENABLED` javadoc — "the disclosure will be forced for third-party assistants" (the screen-edge flash when data is sent). Expect it; you cannot turn it off for a sideloaded app.

## 4. AssistStructure / AssistContent

`AssistStructure` (android12-release source, all public; class since API 23):
- `getActivityComponent(): ComponentName`, `isHomeActivity(): Boolean` (API 28), `getWindowNodeCount()`, `getWindowNodeAt(i): WindowNode`, `getAcquisitionStartTime()/EndTime()`.
- `WindowNode`: `getLeft/getTop/getWidth/getHeight`, `getTitle(): CharSequence`, `getDisplayId()`, `getRootViewNode(): ViewNode`.
- `ViewNode` (source lines 1143-1756): `getIdPackage/getIdType/getIdEntry`, `getAutofillId/getAutofillType/getAutofillHints/getAutofillValue/getAutofillOptions`, `getInputType`, `getLeft/getTop/getScrollX/getScrollY/getWidth/getHeight`, `getTransformation(): Matrix`, `getElevation`, `getAlpha`, `getVisibility`, `isAssistBlocked`, `isEnabled/isClickable/isFocusable/isFocused/isAccessibilityFocused/isCheckable/isChecked/isSelected/isActivated/isOpaque/isLongClickable/isContextClickable`, `getClassName(): String`, `getContentDescription(): CharSequence`, `getWebDomain(): String?`, `getHtmlInfo(): HtmlInfo?`, `getLocaleList()`, `getReceiveContentMimeTypes()`, `getText(): CharSequence`, `getTextSelectionStart/End`, `getTextColor/getTextSize/getTextStyle/getTextLineCharOffsets/getTextLineBaselines`, `getHint(): String`, `getExtras(): Bundle`, `getChildCount()`, `getChildAt(i)`, `getMinTextEms/getMaxTextEms/getMaxTextLength`, `getImportantForAutofill`.

FLAG_SECURE handling in `AssistStructure.WindowNode` constructor (line 513): if `(root.getWindowFlags() & FLAG_SECURE) != 0` and not for autofill: `view.onProvideStructure(builder); builder.setAssistBlocked(true); return;` — comment: "This is a secure window, so it doesn't want a screenshot, and that means we should also not copy out its view hierarchy for Assist". So you get the root node with `isAssistBlocked() == true` and no children. Apps can also block subtrees with `View.setAssistBlocked(true)`.

`AssistContent` (API 23): `getIntent()`, `getWebUri()`, `getStructuredData()` (JSON-LD string), `getExtras()`, `getClipData()`, `isAppProvidedIntent()`, `isAppProvidedWebUri()`. `ActivityThread.handleRequestAssistContextExtras` (line 3862-3900): calls `Application.dispatchOnProvideAssistData`, `Activity.onProvideAssistData(data)`, builds `AssistStructure`, sets the default intent **only when the window is not FLAG_SECURE**, then `Activity.onProvideAssistContent(content)`.

Walking text (Kotlin):
```kotlin
fun AssistStructure.collectText(): List<String> {
    val out = ArrayList<String>()
    fun walk(n: AssistStructure.ViewNode) {
        if (n.visibility == View.VISIBLE) {
            n.text?.takeIf { it.isNotBlank() }?.let { out += it.toString() }
            n.contentDescription?.takeIf { it.isNotBlank() }?.let { out += it.toString() }
            n.hint?.takeIf { it.isNotBlank() }?.let { out += "hint:$it" }
        }
        for (i in 0 until n.childCount) walk(n.getChildAt(i))
    }
    for (w in 0 until windowNodeCount) walk(getWindowNodeAt(w).rootViewNode)
    return out
}
```
Note: `AssistStructure` is lazily parcelled; walk it on the thread you received it (or call `ensureData()` is not public — just walk it before returning from `onHandleAssist` or copy what you need).

From https://developer.android.com/training/articles/assistant (fetched): users invoke with "a long press on the Home button or by saying a keyphrase"; assistant receives `AssistStructure`, `AssistContent` (via `onProvideAssistContent`) and the screenshot "through onHandleScreenshot()"; "FLAG_SECURE does not cause the Assist API callbacks to stop firing. The activity that uses FLAG_SECURE can still explicitly provide information to an assistant app using the callbacks"; "You must set FLAG_SECURE explicitly for every window created by the activity, including dialogs"; an implementer needs `VoiceInteractionSessionService`, `VoiceInteractionSession`, and the `BIND_VOICE_INTERACTION` permission; reference implementation `frameworks/base/tests/VoiceInteraction`. The article still describes the pre-role Settings path ("Settings > Apps > Default Apps > Assist & voice input") — on Android 12 the label is "Digital assistant app" (see section 6).

## 5. RecognitionService (mandatory stub)

Source: `RecognitionService.java` (android12-release).
- `SERVICE_INTERFACE = "android.speech.RecognitionService"`; `SERVICE_META_DATA = "android.speech"` — "should reference an XML resource containing a `<recognition-service>` or `<on-device-recognition-service>` tag."
- Abstract: `protected abstract void onStartListening(Intent recognizerIntent, Callback listener)`, `onCancel(Callback)`, `onStopListening(Callback)`. `Callback.error(@SpeechRecognizer.RecognitionError int)` — "The service should call this method when a network or recognition error occurred." `SpeechRecognizer.ERROR_RECOGNIZER_BUSY = 8` (reference page; also `ERROR_INSUFFICIENT_PERMISSIONS = 9` used by the framework itself).
- No `BIND_*` permission exists for it; the framework checks the **caller's** `RECORD_AUDIO` before dispatching (`dispatchStartListening`: `checkPermissionForPreflight(attributionSource)`, on failure `listener.onError(ERROR_INSUFFICIENT_PERMISSIONS)`). Your stub needs no `RECORD_AUDIO`.
- `onBind` is `final` in the base class.
- AOSP sample stub (`tests/VoiceInteraction/.../MainRecognitionService.java`) just logs in all three callbacks, with `res/xml/recognition_service.xml`: `<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" android:settingsActivity="com.android.test.voiceinteraction.SettingsActivity" />`.

Consequence: selecting your assistant "implicitly select[s] the component here for their recognition service" (attrs.xml), so other apps calling `SpeechRecognizer.createSpeechRecognizer(context)` will hit your stub. Either report busy immediately or delegate to Google's recognizer with `SpeechRecognizer.createSpeechRecognizer(context, ComponentName)` (public since API 8). Minimal stub:
```kotlin
class PrismRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        try { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) } catch (_: RemoteException) {}
    }
    override fun onCancel(listener: Callback) {}
    override fun onStopListening(listener: Callback) {}
}
```
(Delegation variant: keep a `SpeechRecognizer` created with `createSpeechRecognizer(this, ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"))` and forward `RecognitionListener` events to `Callback`. The Google component name is **UNVERIFIED** for OOS12 — query `PackageManager.queryIntentServices(Intent("android.speech.RecognitionService"))` at runtime instead of hardcoding.)

## 6. Default-assistant selection on Android 12

### 6.1 Role model (verified)
- `RoleManager.ROLE_ASSISTANT = "android.app.role.ASSISTANT"` is **public** (`prebuilts/sdk/29/public/api/android.txt` line 7461 and `31/...` line 8597; javadoc "The name of the assistant app role. @see VoiceInteractionService"). Also public: `createRequestRoleIntent(String)`, `isRoleAvailable`, `isRoleHeld`. (The task premise "Android 12 has no ROLE_ASSISTANT for third parties" is half right: the constant exists, but the role cannot be *requested*.)
- `PermissionController/res/xml/roles.xml` line 85-126: `<role name="android.app.role.ASSISTANT" behavior="AssistantRoleBehavior" defaultHolders="config_defaultAssistant" exclusive="true" fallBackToDefaultHolder="true" showNone="true" requestable="false" label="@string/role_assistant_label" shortLabel="@string/role_assistant_short_label" ...>`, and a comment describing qualification: a `<service permission="android.permission.BIND_VOICE_INTERACTION" supportsAssist="true">` with action `android.service.voice.VoiceInteractionService` and meta-data `android.voice_interaction` with "required tag in metadata xml: sessionService / recognitionService / supportsAssist = true", **or** an activity handling `android.intent.action.ASSIST`. Granted permissions: permission-set `sms`, `READ_CALL_LOG`, `ACCESS_BLOBS_ACROSS_USERS` (minSdk 31).
- `RequestRoleActivity.java` line 120: `if (!role.isRequestable()) { Log.e(LOG_TAG, "Role is not requestable: " + mRoleName); ... finish(); }` -> `createRequestRoleIntent(ROLE_ASSISTANT)` yields `RESULT_CANCELED` with no UI.
- `AssistantRoleBehavior.java`: `getQualifyingPackagesAsUser` = packages with a qualifying `VoiceInteractionService` (`isAssistantVoiceInteractionService`: `BIND_VOICE_INTERACTION` permission, meta-data present, `sessionService != null && recognitionService != null && supportsAssist`) plus packages with an `ACTION_ASSIST` activity; services are skipped on low-RAM devices (`isLowRamDevice()`). `isPackageQualified` returns true on a qualifying service, else falls back to the ASSIST activity (so declare a tiny `ACTION_ASSIST` activity too, as the AOSP sample does — it also makes the app appear if the service check ever fails). Confirmation string `assistant_confirmation_message` = "The assistant will be able to read information about apps in use on your system, including information visible on your screen or accessible within the apps." `getManageIntentAsUser` = `Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)` (that is the gear icon next to the role in PermissionController).
- Role -> settings sync: `VoiceInteractionManagerService.RoleObserver.onRoleHoldersChanged` writes `Settings.Secure.ASSISTANT` and `Settings.Secure.VOICE_INTERACTION_SERVICE` to your `VoiceInteractionService` component (only if `getSupportsAssist()`), or to the ASSIST activity with an empty `VOICE_INTERACTION_SERVICE` if no service qualifies; empty strings when the holder list is empty ("None"). `findAvailInteractor()` comment: "We never want to allow third party services to be automatically selected, because those require approval of the user." (only `FLAG_SYSTEM` apps are auto-picked at boot).

### 6.2 Settings UI path (Android 12 AOSP; OxygenOS wording **UNVERIFIED** but OOS12 uses the AOSP PermissionController role UI)
`Settings > Apps` (`apps_dashboard_title` = "Apps") `> Default apps` (`apps.xml` preference `default_apps`, title `app_default_dashboard_title` = "Default apps", `<intent android:action="android.settings.MANAGE_DEFAULT_APPS_SETTINGS"/>`) -> PermissionController `DefaultAppListActivity` (handles `android.settings.MANAGE_DEFAULT_APPS_SETTINGS`, priority 2, **no permission**) -> row "Digital assistant app" (`role_assistant_short_label`) -> `DefaultAppActivity` (radio list of qualifying apps + "None" (`default_app_none`), confirmation dialog above) -> gear icon -> `Settings.ACTION_VOICE_INPUT_SETTINGS` -> Settings `Settings$ManageAssistActivity` (exported, fragment `com.android.settings.applications.assist.ManageAssist`, label `assist_and_voice_input_title` = "Assist & voice input", screen title `default_assist_title` = "Digital assistant app").

`res/xml/manage_assist.xml` (verbatim keys/titles):
- `default_assist` — "Default digital assistant app" (`GearPreference`; click -> `Intent(Intent.ACTION_MANAGE_DEFAULT_APP).setPackage(permissionControllerPkg).putExtra(Intent.EXTRA_ROLE_NAME, RoleManager.ROLE_ASSISTANT)`; `ACTION_MANAGE_DEFAULT_APP` is `@SystemApi @RequiresPermission(MANAGE_ROLE_HOLDERS)` and `DefaultAppActivity` is protected by `android:permission="android.permission.MANAGE_ROLE_HOLDERS"` — **not launchable by your app**)
- `gesture_assist_application` — "Assist gesture" (hidden on non-Pixel)
- `context` — **"Use text from screen"** / "Allow the assist app to access the screen contents as text" -> `AssistContextPreferenceController`: `Settings.Secure.ASSIST_STRUCTURE_ENABLED`, read with default `1`, written as `putInt(..., checked ? 1 : 0)`; `isAvailable()` = `mAssistUtils.getAssistComponentForUser(myUserId) != null` (toggle hidden until an assistant is set)
- `screenshot` — **"Use screenshot"** / "Allow the assist app to access an image of the screen" -> `AssistScreenshotPreferenceController`: `Settings.Secure.ASSIST_SCREENSHOT_ENABLED`, default `1`; additionally disabled (greyed) while `ASSIST_STRUCTURE_ENABLED == 0`
- `flash` — "Flash screen" (`ASSIST_DISCLOSURE_ENABLED`)
- `voice_input_settings` — "Voice input" -> `DefaultVoiceInputPicker` (writes `Settings.Secure.VOICE_RECOGNITION_SERVICE`)
- footer `assist_footer`.

`AssistUtils.getAssistComponentForUser(userId)` = `ComponentName.unflattenFromString(Settings.Secure.getStringForUser(cr, Settings.Secure.ASSISTANT, userId))` or null.

Settings.Secure keys (`Settings.java`, all `@hide`):
| constant | key | notes |
|---|---|---|
| `ASSISTANT` | `"assistant"` | `@UnsupportedAppUsage @Readable`; "It could be a voice interaction service, or an activity that handles ACTION_ASSIST, or empty ... should be set indirectly by setting the ... ROLE_ASSISTANT" |
| `VOICE_INTERACTION_SERVICE` | `"voice_interaction_service"` | `@TestApi @Readable` |
| `VOICE_RECOGNITION_SERVICE` | `"voice_recognition_service"` | `@UnsupportedAppUsage(maxTargetSdk=R) @Readable` |
| `ASSIST_STRUCTURE_ENABLED` | `"assist_structure_enabled"` | `@Readable`; framework default 1 |
| `ASSIST_SCREENSHOT_ENABLED` | `"assist_screenshot_enabled"` | `@Readable`; framework default 1 |
| `ASSIST_DISCLOSURE_ENABLED` | `"assist_disclosure_enabled"` | `@Readable`; forced on for third-party assistants |

`@Readable` means a normal app may read them by string key: `Settings.Secure.getInt(cr, "assist_structure_enabled", 1)`. `null` on the device == default == enabled. Writing needs `WRITE_SECURE_SETTINGS` (shell has it: `packages/Shell/AndroidManifest.xml` line 140), so via Shizuku: `settings put secure assist_structure_enabled 1` / `settings put secure assist_screenshot_enabled 1`.

### 6.3 Opening the picker programmatically (public API)
```kotlin
// 1) Default apps list (PermissionController) — user taps "Digital assistant app"
context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)        // "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", public since API 24
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
// 2) The "Digital assistant app" screen with the two toggles (Settings app, ManageAssist)
context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)                // "android.settings.VOICE_INPUT_SETTINGS", API 21; javadoc text is stale ("configure input methods") but it maps to ManageAssist
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
// Detection
val rm = context.getSystemService(RoleManager::class.java)
val isAssistant = rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
// or: VoiceInteractionService.isActiveService(context, ComponentName(context, PrismInteractionService::class.java))
```
Do NOT use `RoleManager.createRequestRoleIntent(ROLE_ASSISTANT)` (silently cancelled) or `Intent.ACTION_MANAGE_DEFAULT_APP` (SecurityException — needs `MANAGE_ROLE_HOLDERS`).

### 6.4 Setting the role through Shizuku (shell uid)
`packages/Shell/AndroidManifest.xml` (android12) grants shell `MANAGE_ROLE_HOLDERS` (line 252), `WRITE_SECURE_SETTINGS` (140), `ACCESS_SURFACE_FLINGER` (177), `READ_FRAME_BUFFER` (178). `RoleShellCommand.java` (`@RequiresApi(S)`): commands `add-role-holder`, `remove-role-holder`, `clear-role-holders`; syntax `[--user <id>] <roleName> <packageName> [flags]`; waits up to 5 s and prints "Error: see logcat for details." on failure.
```
cmd role add-role-holder --user 0 android.app.role.ASSISTANT com.example.prism
cmd role remove-role-holder --user 0 android.app.role.ASSISTANT com.example.prism
```
Qualification (`AssistantRoleBehavior.isPackageQualified`) is still enforced by `RoleManagerService`, and the `RoleObserver` then writes the Secure settings for you. This bypasses the confirmation dialog. (Behaviour of `cmd role` on OOS12 is **UNVERIFIED** on-device; the class is in the mainline Permission module which OnePlus ships unmodified in the usual case.)

## 7. Manifest + resources (complete)

`AndroidManifest.xml` (targetSdk 31 requires explicit `android:exported` on components with intent filters):
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- optional, only for the MediaProjection fallback -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <!-- NOT needed on API 31: android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION (API 34) -->

    <application ...>
        <!-- 1. always-running interactor: lightweight -->
        <service
            android:name=".assist.PrismInteractionService"
            android:permission="android.permission.BIND_VOICE_INTERACTION"
            android:exported="true"
            android:label="@string/app_name">
            <meta-data android:name="android.voice_interaction"
                       android:resource="@xml/voice_interaction" />
            <intent-filter>
                <action android:name="android.service.voice.VoiceInteractionService" />
            </intent-filter>
        </service>

        <!-- 2. session host: heavy UI; separate process recommended by framework javadoc -->
        <service
            android:name=".assist.PrismSessionService"
            android:permission="android.permission.BIND_VOICE_INTERACTION"
            android:exported="true"
            android:process=":session" />

        <!-- 3. mandatory recognition stub -->
        <service
            android:name=".assist.PrismRecognitionService"
            android:exported="true"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.speech.RecognitionService" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <meta-data android:name="android.speech"
                       android:resource="@xml/recognition_service" />
        </service>

        <!-- 4. ACTION_ASSIST activity: second qualification path + what runs if the service is unavailable -->
        <activity
            android:name=".assist.AssistProxyActivity"
            android:exported="true"
            android:excludeFromRecents="true"
            android:noHistory="true"
            android:taskAffinity=""
            android:theme="@android:style/Theme.NoDisplay">
            <intent-filter>
                <action android:name="android.intent.action.ASSIST" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

        <!-- 5. settings activity referenced from voice_interaction.xml (gear icon in Settings) -->
        <activity android:name=".assist.AssistSettingsActivity" android:exported="true" />

        <!-- 6. MediaProjection fallback FGS (API 31: type attribute is optional but harmless) -->
        <service
            android:name=".capture.ProjectionService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />

        <!-- 7. Shizuku (see docs/research/shizuku-client.md) -->
        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            android:authorities="${applicationId}.shizuku"
            android:multiprocess="false"
            android:enabled="true"
            android:exported="true"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
    </application>
</manifest>
```
Notes: the AOSP sample runs the interactor in `:interactor` and the session in `:session`; `Process` separation is optional. `android:exported="true"` on the session service is not required by the framework (system binds explicitly) but is harmless with the signature permission; the AOSP sample omits it.

`res/xml/voice_interaction.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<voice-interaction-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:sessionService="com.example.prism.assist.PrismSessionService"
    android:recognitionService="com.example.prism.assist.PrismRecognitionService"
    android:settingsActivity="com.example.prism.assist.AssistSettingsActivity"
    android:supportsAssist="true"
    android:supportsLaunchVoiceAssistFromKeyguard="false"
    android:supportsLocalInteraction="false" />
```
`res/xml/recognition_service.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:settingsActivity="com.example.prism.assist.AssistSettingsActivity" />
```

## 8. Kotlin skeleton: session hosting a ComposeView

Dependencies (coordinates verified against Google Maven POMs): `androidx.compose:compose-bom:2024.09.03` maps `androidx.compose.ui:ui` -> **1.7.3**, `foundation` 1.7.3, `runtime` 1.7.3, `material3` 1.3.0 (`compose-bom-2024.09.03.pom`). `ui-android:1.7.3` transitively depends on `androidx.lifecycle:lifecycle-viewmodel:2.6.1`, `androidx.lifecycle:lifecycle-runtime-compose-android:2.8.3`, `androidx.savedstate:savedstate-ktx:1.2.1`, `androidx.activity:activity-ktx:1.7.0`. Declare explicitly (versions aligned to what Compose already pulls):
```kotlin
implementation(platform("androidx.compose:compose-bom:2024.09.03"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")   // setViewTreeLifecycleOwner (androidx.lifecycle package)
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3") // setViewTreeViewModelStoreOwner
implementation("androidx.savedstate:savedstate-ktx:1.2.1")         // setViewTreeSavedStateRegistryOwner
```
Extension names (androidx source, `@JvmName("set")`/`("get")` so the Java statics `ViewTreeLifecycleOwner.set/get` still exist): `View.setViewTreeLifecycleOwner(LifecycleOwner?)` / `findViewTreeLifecycleOwner()` in `androidx.lifecycle` (lifecycle-runtime); `View.setViewTreeViewModelStoreOwner(ViewModelStoreOwner?)` / `findViewTreeViewModelStoreOwner()` in `androidx.lifecycle` (lifecycle-viewmodel); `View.setViewTreeSavedStateRegistryOwner(SavedStateRegistryOwner?)` / `findViewTreeSavedStateRegistryOwner()` in `androidx.savedstate`.

Why the owners go on the view you return: Compose's `WindowRecomposer.android.kt` resolves the recomposer for `view.contentChild`, which walks up parents and "if (parent.id == android.R.id.content) return self". Inside the session window the first such ancestor is the session's own `mContentFrame` (`android.R.id.content`), so `contentChild` == the view you returned from `onCreateContentView()`. `createLifecycleAwareWindowRecomposer` then does `checkPreconditionNotNull(lifecycle ?: findViewTreeLifecycleOwner()?.lifecycle) { "ViewTreeLifecycleOwner not found from $this" }`. Setting the owners on your returned root (a `FrameLayout` or the `ComposeView` itself) satisfies the lookup because `findViewTree*` walks up from the ComposeView.

```kotlin
package com.example.prism.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Always-running, keep tiny. */
class PrismInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // showSession() is legal from here on, e.g. from a bound UI:
        // showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
    companion object {
        fun isCurrent(ctx: Context) =
            isActiveService(ctx, android.content.ComponentName(ctx, PrismInteractionService::class.java))
    }
}

class PrismSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = PrismSession(this)
}

class PrismSession(ctx: Context) : VoiceInteractionSession(ctx),
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    // ---- owners Compose needs ----
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val vmStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = vmStore

    // ---- UI state fed from assist callbacks ----
    var screenText by mutableStateOf<List<String>>(emptyList()); private set
    var screenshot by mutableStateOf<Bitmap?>(null); private set
    var topApp by mutableStateOf<String?>(null); private set

    init { /* setTheme(R.style.Theme_Prism_Session) must be called before onCreate, i.e. here */ }

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)          // must precede ON_CREATE
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onCreateContentView(): View {
        val root = FrameLayout(context)                    // context == VoiceInteractionSession.getContext()
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        root.setViewTreeViewModelStoreOwner(this)
        root.addView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { PrismOverlay(session = this@PrismSession, onDismiss = ::hide) }
        })
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // showFlags is the *requested* set; what you actually get is gated (Settings toggles, setDisabledShowContext, policy).
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        screenText = emptyList(); screenshot = null
        val userDisabled = userDisabledShowContext               // SHOW_WITH_ASSIST / SHOW_WITH_SCREENSHOT bits the user turned off
        val wantStructure = showFlags and SHOW_WITH_ASSIST != 0 && userDisabled and SHOW_WITH_ASSIST == 0
        val wantShot = showFlags and SHOW_WITH_SCREENSHOT != 0 && userDisabled and SHOW_WITH_SCREENSHOT == 0
        // if (!wantShot) -> trigger Shizuku/MediaProjection fallback capture here
    }

    override fun onHandleAssist(state: AssistState) {              // API 29+; do NOT call super (super routes to deprecated overloads)
        val structure: AssistStructure? = state.assistStructure
        val content: AssistContent? = state.assistContent
        if (state.isFocused || state.index == 0) {
            topApp = structure?.activityComponent?.packageName
            screenText = structure?.collectText().orEmpty()
            // content?.webUri, content?.structuredData, content?.intent available here
        }
    }

    override fun onAssistStructureFailure(failure: Throwable) { /* log */ }

    override fun onHandleScreenshot(screenshot: Bitmap?) {         // null when disabled / secure / screen off; not called at all without SHOW_WITH_SCREENSHOT
        this.screenshot = screenshot
    }

    override fun onHide() {
        super.onHide()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED   // window hidden, composition kept (DisposeOnViewTreeLifecycleDestroyed)
    }

    override fun onLockscreenShown() { hide() }                    // default already hides; override only to add behaviour

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED // disposes the composition
        vmStore.clear()
        super.onDestroy()
    }
}
```
Notes:
- `VoiceInteractionSession(Context)` runs on the main thread of the `:session` process; all callbacks arrive on that thread.
- `getContext()` is a plain `Context` (not an Activity). `LocalContext` in Compose will be this context; `LocalActivity` is absent. Anything needing an Activity (`registerForActivityResult`, permission requests, MediaProjection consent) must be started with `startAssistantActivity(Intent)` or from a normal activity.
- The window is `TYPE_VOICE_INTERACTION`, full-screen, `Gravity.BOTTOM`, not `FLAG_NOT_FOCUSABLE` — it takes focus; back key -> `onBackPressed()` -> `hide()`.
- `ViewCompositionStrategy` options (Compose interop doc): `DisposeOnDetachedFromWindowOrReleasedFromPool` (default), `DisposeOnDetachedFromWindow`, `DisposeOnLifecycleDestroyed(owner)`, `DisposeOnViewTreeLifecycleDestroyed` ("ComposeView in a View wherein the Lifecycle is not known yet"). Because the session window is hidden/shown repeatedly (detached on hide), use `DisposeOnViewTreeLifecycleDestroyed` so the composition survives `hide()`.

## 9. Fallback screen capture

Ordering the app will use: **(a) Shizuku `screencap -p`**, (b) the assist screenshot from `onHandleScreenshot`, (c) MediaProjection.

### 9.1 Shizuku shell `screencap` (preferred)
Facts:
- Binary source `frameworks/base/cmds/screencap/screencap.cpp` (android12-release; it is under frameworks/base, not frameworks/native). Usage text: `usage: screencap [-hp] [-d display-id] [FILENAME]`; `-p: save the file as a png.`; "If FILENAME ends with .png it will be saved as a png."; "If FILENAME is not given, the results will be printed to stdout." Output with `-p` is PNG (`AndroidBitmap_compress(..., ANDROID_BITMAP_COMPRESS_FORMAT_PNG, 100, ...)`, written to fd); without `-p` it is raw: four little-endian uint32 (`w`, `h`, `f` = pixel format, `c` = dataspace) then `h` rows of `w * Bpp` bytes (RGBA_8888 -> 4 Bpp). Capture call: `ScreenshotClient::captureDisplay(displayId->value, captureListener)` (the overload with `captureSecureLayers = false`, uid unset); on any error it `close(fd); return 1;` with no bytes written.
- Permission: `SurfaceFlinger::validateScreenshotPermissions` allows `uid == AID_GRAPHICS || checkPermission(READ_FRAME_BUFFER)`; shell holds `READ_FRAME_BUFFER` -> allowed.
- Secure content: `renderScreenImplLocked` computes `capturedSecureLayers = any visible layer with isSecure()` **regardless** of `captureSecureLayers`, then `if (captureResults.capturedSecureLayers && !canCaptureBlackoutContent) { ALOGW("FB is protected: PERMISSION_DENIED"); return PERMISSION_DENIED; }`. `hasCaptureBlackoutContentPermission()` = `uid == AID_GRAPHICS || uid == AID_SYSTEM || checkPermission("android.permission.CAPTURE_BLACKOUT_CONTENT")` — a signature permission shell does not hold. **Result: with a FLAG_SECURE window (or DRM/protected surface) on screen, `screencap` exits 1 and produces nothing.** Detect exit code != 0 / empty stdout and fall back to (b). (Derived from source; not exercised on the OnePlus.) `Layer::isSecure()` is true when the layer or any parent has `eLayerSecure` (set by WindowManager for FLAG_SECURE windows).
- Speed: single SurfaceFlinger capture + PNG encode; expect 100-300 ms for 1080x2340 (**UNVERIFIED** timing).

How to run it (Shizuku API 13.1.5 — latest on Maven Central per `dev/rikka/shizuku/api/maven-metadata.xml`, released 2023-09-21; coordinates `dev.rikka.shizuku:api:13.1.5` and `dev.rikka.shizuku:provider:13.1.5`):
- `Shizuku.newProcess(String[] cmd, String[] env, String dir)` is **`private static`**, `@deprecated`, "planned to be removed from Shizuku API 14" (`Shizuku.java` line 486-491). README 13.1.1 changelog: "Prepare to remove Shizuku#newProcess, developers should have to use UserService instead". Do not rely on it (reflection works today but is unsupported).
- Use a **UserService** (README "UserService": runs "in a different process and as the identity (Linux UID) of root (UID 0) or shell (UID 2000 ...)"; "the service class must implement IBinder ... `public class YourService extends IYouAidlInterface.Stub`"; constructor may take `Context` from v13 (annotate `@Keep`); `Shizuku.bindUserService(UserServiceArgs, ServiceConnection)`; `UserServiceArgs(ComponentName).daemon(false).processNameSuffix("shot").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE).tag("prism-shot")`; implement `destroy()` with transaction code `16777115` (`= 16777114` in AIDL) that calls `System.exit(0)`; `Shizuku.unbindUserService(args, conn, true)`.

AIDL (`src/main/aidl/com/example/prism/IShellShot.aidl`):
```aidl
package com.example.prism;
interface IShellShot {
    void destroy() = 16777114;                 // reserved by Shizuku
    ParcelFileDescriptor screencapPng() = 1;   // read side of a pipe carrying PNG bytes; null on failure
}
```
UserService (runs as uid 2000, not an app process — `Context#getContentResolver` etc. do not work there per README):
```kotlin
class ShellShotService() : IShellShot.Stub() {
    @Keep constructor(context: Context) : this()
    override fun destroy() { System.exit(0) }
    override fun screencapPng(): ParcelFileDescriptor? {
        val p = ProcessBuilder("/system/bin/screencap", "-p").redirectErrorStream(false).start()
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out -> p.inputStream.copyTo(out) }
            if (p.waitFor() != 0) Log.w("ShellShot", "screencap failed (secure window?)")
        }.start()
        return readEnd   // caller: if the stream is empty -> treat as failure (secure content)
    }
}
```
Binder payload limit (~1 MB) is why a pipe (or `SharedMemory`) is used instead of `byte[]`.

Optional `settings`/`cmd` side-effects via the same UserService: `settings put secure assist_screenshot_enabled 1`, `cmd role add-role-holder ...` (section 6.4).

### 9.2 Assist screenshot (system path)
Delivered to `onHandleScreenshot` only for a session show with `SHOW_WITH_SCREENSHOT`, only if `assist_screenshot_enabled != 0`, `assist_structure_enabled != 0`, structure allowed, screen on. Taken by system_server (uid 1000 -> `canCaptureBlackoutContent` true) with `captureSecureLayers = false`, so `BufferLayer::prepareClientComposition` blacks out secure layers (`blackOutLayer = ... || ((isSecure() || isProtected()) && !targetSettings.isSecure)`): you get a bitmap with the secure window as black instead of a failure. Bitmap is `asShared()` (ashmem, immutable). Free.

### 9.3 MediaProjection on API 31
- Consent: `MediaProjectionManager.createScreenCaptureIntent()` — "Returns an Intent that must be passed to startActivityForResult() ... The activity will prompt the user whether to allow screen capture" (component from `config_mediaProjectionPermissionDialogComponent`; on Android 12 it is the SystemUI dialog). Result -> `getMediaProjection(resultCode, resultData)`; javadoc (android12): "Apps targeting SDK version Q or later should specify the foreground service type using the attribute foregroundServiceType in the service element of the app's manifest file. The FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION attribute should be specified." Also: "Starting from Android R, if your application requests the SYSTEM_ALERT_WINDOW permission, and the user has not explicitly denied it, the permission will be automatically granted until the projection is stopped."
- Enforcement (`MediaProjectionManagerService.MediaProjection.start()`): `requiresForegroundService() = mTargetSdkVersion >= Q && !mIsPrivileged`; then `if (!mActivityManagerInternal.hasRunningForegroundService(uid, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)) throw new SecurityException("Media projections require a foreground service of type ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION")`. The check happens when `createVirtualDisplay()`/`registerCallback` starts the projection, so the FGS must already be running **before** you use the `MediaProjection`. `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION = 1 << 5` (=32, API 29). Restarting an already-started projection is ignored with a warning ("attempted to start already started MediaProjection").
- API 31 needs only `<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>` (normal, API 28+); `FOREGROUND_SERVICE_MEDIA_PROJECTION` and the mandatory manifest type are Android 14 rules (fg-service-types page: "Apps targeting API 31 ... Do NOT need to declare FOREGROUND_SERVICE_MEDIA_PROJECTION"). `MediaProjection.registerCallback` exists on 12 but is not mandatory (the "throws IllegalStateException if no callback" rule is API 34). Token reuse: on Android 12 the one-shot Intent/one-`createVirtualDisplay` rules from the media-projection guide are Android 14 behaviour; still treat each consent as single-use.
- Capture loop:
```kotlin
// in ProjectionService.onStartCommand (after consent):
startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
val mp = mpm.getMediaProjection(resultCode, data)!!
val metrics = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds   // API 30+
val (w, h) = metrics.width() to metrics.height()
val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
val vd = mp.createVirtualDisplay("prism", w, h, resources.configuration.densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
reader.setOnImageAvailableListener({ r ->
    r.acquireLatestImage()?.use { img ->
        val plane = img.planes[0]; val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
        val padded = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val bmp = Bitmap.createBitmap(padded, 0, 0, w, h)
        vd.release(); reader.close(); mp.stop(); stopSelf()
        deliver(bmp)
    }
}, Handler(Looper.getMainLooper()))
```
- FLAG_SECURE: the virtual display is non-secure (secure virtual displays need `CAPTURE_SECURE_VIDEO_OUTPUT`), so secure layers are blacked out (same `blackOutLayer` path). No failure, just black.
- Cost: consent dialog every time (no "don't ask again" on Android 12), a persistent notification while the FGS runs, and `SYSTEM_ALERT_WINDOW` auto-grant side effects. Use last.

## 10. Behaviour matrix for FLAG_SECURE

| source | callbacks fire? | text | screenshot |
|---|---|---|---|
| Assist structure | yes | root node only, `isAssistBlocked()==true`; `AssistContent` has no default intent (app may still fill it) | — |
| Assist screenshot (`onHandleScreenshot`) | yes | — | bitmap with secure window black |
| Shizuku `screencap` | n/a | — | **fails** (`PERMISSION_DENIED`, exit 1) if any secure layer is visible |
| MediaProjection | n/a | — | secure layers black |

Device-policy `setScreenCaptureDisabled` (work profile) kills both assist structure and screenshot (`isAssistDataAllowedOnCurrentActivity`).

## 11. Verification status / caveats

- VERIFIED from source/api files: everything in sections 1-9 unless marked. API levels: `onHandleAssist(AssistState)` and `AssistState` = 29; `ROLE_ASSISTANT`, `createRequestRoleIntent`, `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` = 29; `ACTION_VOICE_INPUT_SETTINGS`, `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`, `getUserDisabledShowContext`, `onHandleScreenshot` present in API 28 file (so <= 28); `isHomeActivity` <= 28.
- The R.styleable reference page fetch listed the attribute as `supportsLaunchFromKeyguard`; attrs.xml says `supportsLaunchVoiceAssistFromKeyguard`. Trust attrs.xml (it is what the parser reads via `R.styleable.VoiceInteractionService_supportsLaunchVoiceAssistFromKeyguard`).
- Reference-page fetches for `RoleManager`, `VoiceInteractionSessionService`, `ViewTreeViewModelStoreOwner`, `Settings#ACTION_VOICE_INPUT_SETTINGS`, `MediaProjectionManager`, `WindowManager.LayoutParams#FLAG_SECURE` returned navigation shells or invented content; those facts were taken from source instead. `FLAG_SECURE` doc text captured: "Window flag: treat the content of the window as secure, preventing it from appearing in screenshots or from being viewed on non-secure displays."
- UNVERIFIED: OxygenOS 12 Settings labels/ordering (AOSP strings assumed); `cmd role` on OOS12; Google recognizer component name; `screencap` timing; whether OnePlus overrides `config_showDefaultAssistant` (if false the row is hidden in Default apps, but `cmd role add-role-holder` and `ACTION_VOICE_INPUT_SETTINGS` still work).
- The AOSP article https://developer.android.com/training/articles/assistant still names the pre-Android-10 path "Settings > Apps > Default Apps > Assist & voice input"; on Android 12 the row is "Digital assistant app" and the toggles are behind its gear icon.
