# Prism v3 — decisions (Dheer, 2026-09-29)

Asked, not assumed. Nothing built yet — plan needs approval first.

## AI
- **Default AI: Gemini.** Groq is "absolutely bad" at quality — keep it out of the default path.
- Problem: Dheer has a paid key, most people don't have money / won't spend it.
- **Guided free key:** setup screen walks a new user through getting a free Gemini key in
  Google AI Studio (opens the page, they tap Copy, Prism picks it up from the clipboard).
  Dheer's own paid key keeps working for him. No shared server, no shared key.
- **Triggers:** tap/hold the island, and long-press power (Prism as default assistant).
  Not edge swipe, not wake word.

## Looks — a switch between two complete looks
- **Current glass ↔ Nothing-inspired.** The switch changes EVERYTHING, not just colours:
  how the AI behaves, the island, the AOD, the control center.
- Nothing mode AI: **short & blunt** answers (no fluff, mono text) **and glyph-style replies**
  (dot-matrix readout that types out + light-pulse glyph animations at the screen edge).
- Dheer wants the Nothing look designed from a study of every Nothing product and app.
- Name stays **Prism** for now.

## Dynamic Island shows
- Music now playing, AI listening/thinking, timers/calls/charging, notification peek.

## AOD (new)
- **Always, when the screen locks** (Samsung-style), not only while charging.
- Shows: clock + date, notification icons, music, battery/charging.
- Turns itself off: phone in pocket/face down (proximity), night hours, low battery.

## Plan approval (2026-09-29)
- **Build it all**, phase by phase per `v3-plan.md`, installing on the phone after big steps.
- Groq + Claude: **hidden under Advanced** — Gemini is all a new user sees; paid keys still usable.
- Nothing mode **follows the phone's dark mode** (black at night, white by day).

## 3.0 fix round (2026-09-29) — not a new version
- More customisation: island (split on/off, animation speed), AOD (clock style auto/dot/thin/bold,
  size, brightness, show date/battery/notifications/music), Nothing look (signal colour, dot grid,
  dot-matrix titles, glyph strength, edge lights), assistant (answer length, typing speed).
- iPhone 18 Pro-style split island: two things at once → pill + detached bubble joined by a liquid
  neck (blur + alpha-threshold metaball; Android 12 has no AGSL). Default: split when two run.
- Shipped as 3.0: commit + push, and put the APK on the v3.0 release.
