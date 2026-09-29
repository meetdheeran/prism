# Prism v3 — build plan (awaiting Dheer's approval)

Decisions: see `v3-decisions.md`. Nothing research brief summarised below.

## Nothing-inspired design tokens (no Nothing fonts/logos shipped — theirs are proprietary)
- Canvas `#000000` (dark) / `#FFFFFF` (light), cards `#111111` / `#F2F2F2`, hairlines `#2A2A2A`,
  secondary text `#8A8A8A`, ONE accent: signal red `#D71921`. No gradients, blur or shadows.
- Fonts (Google Fonts, OFL, bundled): **Doto** = dot-matrix display (clock, big numbers, titles only),
  **Space Mono** = UPPERCASE 11sp labels, **Space Grotesk** = body text.
- 8dp rhythm, 16dp gutters, widgets 24dp radius, toggles as pills, 1.5dp line icons, dot-grid texture.
- Signatures: red dot as status, dot-drawn widgets, segmented dotted progress bars, 25x25 "glyph
  matrix" dot canvas, dot ripple on confirm, lowercase names with brackets, monochrome icons.
- Motion: dot-by-dot reveals (~25ms per column), springs damping 0.8, CLOCK_TICK haptics.

## Phases
1. **Look switch foundation** — `Look { GLASS, NOTHING }` in Prefs, one CompositionLocal every
   screen, the island, AOD and the assistant read. Glass code stays as-is.
2. **Nothing screens** — home, chat, settings, keys, memory, reminders, control center tiles.
3. **AI** — Gemini default. Guided free-key setup: open AI Studio → user taps Copy → Prism detects
   a Gemini-shaped key on the clipboard → test call → saved encrypted. Paid keys unchanged.
   Nothing mode: short & blunt system prompt + dot-matrix typewriter replies + edge glyph pulses
   while listening/thinking.
4. **Island** — music (song + mini waveform), AI listening/thinking, timers/calls/charging,
   notification peek. Tap/hold opens the assistant. Long-press power (Prism as default assistant)
   opens it through the island too. Both looks.
5. **AOD (new)** — on screen-off, a black full-screen lock-screen activity: clock + date, notif
   icons, music, battery/charging. Pixel shift every minute (burn-in), very low brightness.
   Off when: proximity covered / face down, night hours (user picks), battery < 15%.
6. **Ship** — build, install on the OnePlus 7, test each piece, v3.0.0, push + GitHub release.
