# Prism — architecture and implementation contract

Working title **Prism** (`com.meetdheeran.prism`). Target: OnePlus 7 (GM1900), Android 12 /
OxygenOS 12 (API 31), 1080x2340 @ 60 Hz, waterdrop cutout (top inset 80 px, x 450–630).
Non-root. Shizuku 13.6.0 runs as uid shell (2000). No system cross-window blur on this build
(`ro.surface_flinger.supports_background_blur` is empty) — all glass is drawn by us.

Read `docs/research/*.md` before touching a provider, Shizuku, or the assistant service.

## Hard rules

1. **Never present mock behaviour as real.** If a capability is unavailable (no key, no
   permission, Shizuku not running), the UI says so and offers the fix. No fake replies.
2. **Secrets** only through `core.SecureStore`. Never log keys, request bodies, or screenshots.
3. **Permissions are asked in context**, one at a time, with a one-line reason. Notification
   access, overlay, Shizuku, WRITE_SETTINGS, RECORD_AUDIO, calendar, contacts are all optional;
   every feature degrades gracefully without them.
4. **Nothing is sent to a provider the user did not pick.** Web search only when
   `Settings.webSearch == PROVIDER`. Screenshots are only captured on an explicit tap or
   assistant invocation, sent once, never stored.
5. Not in scope (user did not select): launcher/home screen, notification summaries,
   auto-replies. Don't add them.
6. Pinned toolchain — do not bump Kotlin/AGP/Compose/library versions (Kotlin 2.0.20 cannot
   read Kotlin 2.2 metadata; OkHttp 5 / serialization 1.11 / Room 2.8 will break the build).

## Package map (single module `app`)

| Package | Owns | Key types |
|---|---|---|
| `core` | DI, prefs, secrets | `AppGraph`, `Prefs`/`Settings`, `SecureStore`, enums `Provider`, `GestureEdge`, `VoiceInputMode`, `WebSearchMode` |
| `data` | Room | `ConversationEntity`, `MessageEntity`, `MemoryEntity`, DAOs, `HistoryRepository`, `MemoryRepository` |
| `ai` | provider-neutral contracts + providers + engine | `AiProvider`, `ChatRequest/ChatMessage/AiEvent`, `ToolSpec/ToolHandler/ToolRegistry/Schema`, `GeminiProvider`, `GroqProvider`, `AssistantEngine`, `Prompts` |
| `actions` | phone actions as tools | `AppIndex`, `PhoneTools` (open_app, set_alarm, set_timer, create_calendar_event, list_calendar_events, dial, send_sms_draft, navigate, web_search_open, play_music, media_control, set_volume, flashlight, dnd, brightness, open_settings, remember, forget, search_web (provider), read_screen) |
| `shizuku` | privileged shell via Shizuku | `ShizukuBridge` (state flow, permission, `exec`), `ShellService` (UserService, AIDL `IShellService`), `ShellCommands` (typed helpers) |
| `control` | control center overlay | `ControlCenterService` (foreground), `EdgeHandle`, `ControlPanel` (Compose), `Tiles` (state readers + togglers), `TileSpec` |
| `island` | Dynamic Island | `MediaWatcher`, `PrismNotificationListener`, `LiveActivity`, `IslandService` (foreground), `IslandUi` |
| `assistant` | default-assistant + voice | `PrismVoiceInteractionService`, `PrismSessionService`, `PrismSession` (VoiceInteractionSession hosting Compose), `StubRecognitionService`, `ScreenReader` (assist structure → text; Shizuku screencap; MediaProjection fallback), `SpeechInput` (SpeechRecognizer + AudioRecord→WAV), `SpeechOutput` (TTS) |
| `writing` | text-selection + share entry points | `ProcessTextActivity` (ACTION_PROCESS_TEXT: rewrite/summarize/translate/explain), `ShareActivity` (ACTION_SEND images/PDF/text) |
| `ui.theme` / `ui.motion` / `ui.glass` | design system | `PrismTheme`, `PrismColors`, `Motion`, `pressable`, `LocalTilt`, `BackdropState`, `backdropSource`, `liquidGlass`, `LiquidGlass`, `GlassStyle`, `GlassBackground` |
| `ui.screens` | app screens | `HomeScreen` (chat), `HistoryScreen`, `MemoryScreen`, `SettingsScreen`, `KeysScreen`, `PermissionsScreen`, `ControlCenterEditorScreen`, `OnboardingScreen` |
| `ui.siri` | assistant visuals | `EdgeGlow` (full-screen animated gradient border, amplitude-driven), `Orb`, `ListeningPill`, `ResponseCard` |

## Glass system (already implemented — use it, don't reinvent)

```kotlin
val backdrop = rememberBackdropState()
Box {
    GlassBackground(backdrop)                       // or any composable with Modifier.backdropSource(backdrop)
    LiquidGlass(backdrop, Modifier.size(...), shape, GlassStyle.Regular) { ...content... }
    Box(Modifier.liquidGlass(backdrop, shape, GlassStyle.Tile, LocalTilt.current)) { ... }
}
```
- Glass must be a later sibling of the source, never its descendant.
- `GlassStyle.Tile` for many small tiles (no refraction ring), `Regular` for panels/cards,
  `Clear` over photos/album art, `Dark` for text-heavy sheets, `Island` for the pill.
- Provide `LocalTilt` at the top of each window: `CompositionLocalProvider(LocalTilt provides rememberDeviceTilt().value)`.
- Overlays over OTHER apps have no live backdrop. The control center therefore captures a
  screenshot when it opens (Shizuku `screencap` if available, else nothing) and uses that
  bitmap as its `backdropSource` (frozen glass — this is what every Android "iOS control
  center" app does, and it is honest: the panel is a full-screen window).
- Press feedback: `Modifier.pressable { }` (scale 0.96 + haptic). Springs from `Motion`.

## AI engine

`AssistantEngine.send(conversationId, userText, attachments, ctx)` → `Flow<EngineEvent>`:
`Thinking`, `Text(delta)`, `ToolRunning(name, label)`, `ToolDone(name, userVisible)`,
`Citations`, `Done(messageId)`, `Error(message)`.
Loop: build system prompt (`Prompts.system(settings, memoryBlock, deviceContext)`) → provider
`chat()` → on `ToolCallRequested` execute via `ToolRegistry` (confirm first if
`needsConfirmation`) → append TOOL message → call again (max 6 rounds) → persist every message
through `HistoryRepository`. Title the conversation from the first user message (first 40 chars).
Provider chosen from `Settings.provider`; model from `Settings.geminiModel/groqModel` or the
provider's default.

Providers (see `docs/research/gemini.md`, `docs/research/groq.md` for exact IDs/JSON):
- `GeminiProvider`: REST `generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse`,
  header `x-goog-api-key`. `system_instruction`, `contents[].parts[]` with `inline_data`
  (image/jpeg, application/pdf, audio/wav), `tools[]` with `function_declarations` and,
  when webSearch, `google_search: {}`. Map `functionCall` → `ToolCallRequested`;
  `functionResponse` parts for TOOL messages; `groundingMetadata.groundingChunks[].web` → Citations.
- `GroqProvider`: OpenAI-compatible `api.groq.com/openai/v1/chat/completions` with
  `stream: true`, `tools[]` (`type: function`), `image_url` data URLs for the vision model
  only; STT via `audio/transcriptions` (`whisper-large-v3-turbo`). Web search on Groq uses the
  `browser_search` built-in tool on `openai/gpt-oss-*` (compound is shut down 2026-09-21).
  Groq's TTS (`canopylabs/orpheus-v1-english`) is optional and OFF by default (cost).
- `listModels()` = "Test connection". Keys are validated by prefix (`AIza`, `gsk_`) before calling.

## Tools (function calling) — one schema for both providers

Names are snake_case, arguments flat. Each `ToolHandler` maps to a public Android API (see
`docs/research/shell-controls.md` for Shizuku fallbacks). `needsConfirmation = true` for
`dial`, `send_sms_draft` (opens the SMS app pre-filled, never sends), `create_calendar_event`,
`forget`. The engine shows a glass confirmation sheet before running those.

## Control center

- `ControlCenterService`: foreground service (notification "Prism controls are on") that
  adds a thin `EdgeHandle` overlay (touchable strip, 6 dp wide × 140 dp, at
  `Settings.gestureEdge`/`gestureHandleFraction`). A swipe inward opens the full-screen
  `ControlPanel` overlay (focusable so back/outside-tap closes it).
- Opening sequence: capture Shizuku screenshot (≤ 120 ms) → show panel with the screenshot
  as backdrop → tiles spring in staggered (Motion.panel, 18 ms apart). Without Shizuku the
  backdrop is `GlassBackground` (animated) — honest and still pretty.
- Tiles (id → behaviour): `wifi`, `data`, `bluetooth`, `airplane` (Shizuku; otherwise open
  the Settings panel), `flashlight`, `rotation`, `dnd`, `battery_saver`, `dark_mode` (Shizuku),
  `location` (Shizuku), `nfc` (Shizuku), `hotspot` (Shizuku or settings), `screenshot`
  (Shizuku), `lock` (Shizuku sleep key), `brightness` slider (WRITE_SETTINGS), `volume`
  slider (media), `media` card (MediaWatcher), `assistant` (opens the session), `camera`,
  `calculator`, `settings`. Every tile shows real state; a tile that cannot act shows a
  small lock badge and explains on tap.
- Layout inspired by iOS 18: media card (2×2), connectivity cluster (2×2 of 4 small), two
  vertical sliders (brightness/volume), a grid of 1×1 tiles. Reorderable in the editor screen.

## Dynamic Island

- `IslandService` (foreground): an overlay pill at the cutout (`y = 0`, width ≈ 190 px
  collapsed around the 180 px notch, height 80 px matching the inset), black
  `GlassStyle.Island`. Collapsed: album art dot left + audio bars right when music plays.
- Tap → expands (Motion.pop) to a 340×150 dp card: art, title/artist, progress, controls.
  Auto-collapses after 4 s of no touch. Long-press → opens the assistant.
- Activities: incoming/ongoing call (from listener), timers/chronometer notifications,
  charging (BatteryManager broadcast: brief "Charging 64%" bloom then collapse).
- Untrusted-touch rule (Android 12): keep window alpha 1 and the pill opaque; never cover
  more than the pill while collapsed.

## Assistant (default digital assistant)

- `PrismVoiceInteractionService` + `PrismSessionService` + `PrismSession` per
  `docs/research/assistant-api.md`. The session's content view hosts Compose (set the three
  ViewTree owners on the content view — see `overlay.OverlayHost` for the pattern).
- Show: `EdgeGlow` bloom + a bottom `ListeningPill` (mic level from `SpeechInput`), typed
  input via a glass text field. Result: `ResponseCard` morphs up from the pill with streaming
  text; speaks it if `speakReplies`.
- Screen context: `onHandleAssist` structure text + `onHandleScreenshot` bitmap when
  available; otherwise Shizuku `screencap`; otherwise ask the user to tap "Capture" which
  uses MediaProjection consent. The screenshot is sent with the first question of that
  session and discarded.
- Not selected as default assistant → the app still works via the in-app mic button, the
  island long-press, and the control-center tile; Settings screen deep-links to the picker.

## Writing tools

- `ProcessTextActivity` (transparent theme) appears in the text-selection toolbar as
  "Prism": sheet with Rewrite / Summarize / Translate / Explain, result replaces the
  selection (`EXTRA_PROCESS_TEXT_READONLY` respected) or copies.
- `ShareActivity`: images / PDF / text shared into a new conversation with a prompt field.

## Memory & history

- Home has a history drawer (conversations, rename/delete/clear all) and a Memory screen
  (list, add, edit, toggle, delete, delete all). The `remember` tool writes with
  `source = "assistant"` and the UI shows a "Saved to memory" chip on that message so nothing
  is stored silently.

## Build & install

```
.\gradlew.bat assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
adb install -r app\build\outputs\apk\debug\app-debug.apk
tools\shizuku-start.ps1               # after every phone reboot
```
