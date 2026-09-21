# Next session (updated 2026-09-21 ~00:50, continue "tomorrow")

STATE: bugs 1-3 below are DONE and installed (round 2), plus new features: conversation mode,
point-and-ask camera, real reminders (Room v2), island events, iOS-27 edge-glow assistant
style, OpenGL refraction glass (control center). Latest APK: Downloads/Prism-debug.apk.

UPDATE 2026-09-21 ~01:30: v2 DONE and INSTALLED: iOS-27 glass-lens island (default style;
GL magnifying lens over a Shizuku strip snapshot, rainbow streak, black pill still selectable in
Settings > Dynamic Island > Island look), glass-sphere rainbow-rim app icon, CLAUDE as a third
provider (Messages API over REST: streaming, tools w/ eager input streaming, images, PDFs,
web_search_20260209, refusal fallback on Opus 5; key prefix sk-ant-; default claude-opus-5).
NOT yet user-tested: lens look on the phone, Claude with a real key. README + screenshots ready
for a later git push (no remote yet).

TOMORROW, in order:
(a) Collect feedback on round 2 (GL panel look, camera, reminder firing, edge style); fix.
(b) NEW LOOK REQUEST — see docs/reference/ios27-siri-1..4.png :
    iOS 27 makes Siri/the island a CLEAR GLASS LENS over the camera area: a transparent
    bubble/pill that MAGNIFIES and refracts what is behind it (date/lock-screen text shows
    enlarged through it) with a rainbow chromatic light streak; not a black pill. Also a
    glass-sphere app icon with a rainbow rim (image 4). Plan: island "Lens" style using the
    RefractionSurface GL path with a backdrop snapshot (Shizuku screencap of the top strip,
    refreshed on content change) or, without Shizuku, the wallpaper/status area colours;
    stronger magnification (1.15-1.25x), rainbow specular streak, thin bright rim; keep
    the black style as option. New adaptive icon: glass sphere + rainbow rim.
(c) Then the adb-driven TEST AGENT for the app.
(d) User still has to: allow Prism in the Shizuku app; Developer options → "Disable permission monitoring".

--- original notes ---

User feedback after first real use on the OnePlus 7 (Groq key working, island live):

1. Dynamic Island sits too high and is slightly misaligned with the waterdrop notch.
   - Measure DisplayCutout at runtime (WindowInsets.displayCutout bounding rect) instead of the
     hard-coded 72 dp / y=0 guess; add "island vertical offset" + "width" fine-tune sliders in Settings.
   - Cutout on this phone: inset 80 px, bounds x 450-630, cutoutSpec path M -90,0 L -90,80 L 90,80 L 90,0.
2. The persistent notifications ("Dynamic Island is on", "Prism controls are on") disturb him.
   - Android 12 requires a foreground-service notification, but: merge both services into ONE
     notification (shared channel/id), keep IMPORTANCE_MIN + silent, and add a "Hide this
     notification" row that opens the channel settings (disabling the channel hides it on 12 while
     the service keeps running). Verify on OxygenOS.
3. "What's on my screen" opens the Prism app, so the screenshot shows Prism itself, not the app he was in.
   - Island long-press / assistant tile must open the VoiceInteractionSession overlay (Prism is now
     the default assistant): keep a static ref in PrismVoiceInteractionService and call
     showSession(null, SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT) so the system hands us the
     underlying app's structure + screenshot.
   - In-app chat path: before a Shizuku screencap, moveTaskToBack(true), wait ~400 ms, capture, then
     bring Prism back (or just tell the user to use the assist gesture).
4. Still needed from the user: allow Prism in the Shizuku app; Developer options → "Disable
   permission monitoring" (OxygenOS limits adb/shell rights; Shizuku warned about it).
5. Plan agreed: build an automated TEST AGENT for the app in a later session (adb-driven flows,
   screenshots, logcat checks). Keep token use low: no research fan-outs, write code directly.

Polish backlog: Material slider thumb in Settings; GlassStyle.Dark ring reads dark; control-center
swipe untested by adb (works only with a finger on OxygenOS); island charging/call/timer untested.
