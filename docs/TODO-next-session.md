# Next session (recorded 2026-09-21, user had 30% quota left; do NOT start until asked)

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
