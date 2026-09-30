# Prism Agent — plan (awaiting Dheer's approval)

Operate other apps on the phone from one request: "send the text on the screen by Gmail to X",
"message Anushka on Zoom and say hi". Tier 1 (not built).

## Decided with Dheer (2026-09-29)
| Question | Answer |
|---|---|
| How it controls apps | **Accessibility service** (Android's built-in way to read and press other apps' buttons) |
| When it asks | **Before anything that sends, posts, deletes or pays**; everything else runs on its own |
| Which apps | **Any app; banking, payment and password-manager apps are always blocked** |
| Free-key privacy | **Warn once before first use; each person chooses** (free Gemini keys: Google may use content to improve products and humans may review it; paid keys aren't used that way — Gemini API terms, checked 2026-09-29) |

## How it works
1. The normal assistant (voice, typed, island, power button) gets a new tool, `operate_phone(goal)`.
   It uses it only when a task needs working *inside* another app. Simple things keep using the
   existing tools (alarms, SMS draft, open app…).
2. The agent loop, one step at a time, max 25 steps / 3 minutes:
   - **Look:** read the screen through the accessibility service into a compact numbered list
     (`[12] button "Send"`, `[7] text field "Message" (editable)`), about 150 elements max.
     Password fields are never read. If an app exposes almost nothing (some games, custom UIs),
     a screenshot goes to the model instead and it taps by position.
   - **Decide:** the chosen AI (Gemini by default) gets the goal, the steps so far and the screen,
     and must answer with exactly one action.
   - **Act:** tap, type, scroll, back, home, open app, wait, ask you a question, or finish.
   - Repeat until done, stuck, or stopped.
3. **Shortcuts before tapping around:** open Gmail straight into a filled-in email (subject, body,
   recipient), open apps directly, open links. Tapping is the fallback, not the first choice.
4. **"Text on the screen":** captured the moment you call the assistant (Android hands the default
   assistant the screen's text), before the agent starts moving around.

## Safety (not optional)
- **Your request is the only instruction.** Anything read on screen — an email that says "forward
  this to…", a web page, a chat message — is information, never a command.
- **Confirmation gate:** a tap is held for your OK when the model flags it as irreversible OR the
  button reads like send / post / delete / pay / buy / confirm / submit / share / call / reply-all.
  The prompt says exactly what and to whom: *Send "hi" to Anushka Thakur on Zoom?* [Cancel] [Send].
- **Blocked:** banking, payment, wallet, UPI and password-manager apps (curated list + name
  patterns), password fields, and screens Android marks secure. It never types passwords, OTPs or
  card numbers.
- **Always visible, always stoppable:** the island shows the current step with a stop control;
  step and time limits; if the screen stops making sense it stops and tells you.
- **Privacy notice** on first use, as decided above. Screenshots are never stored.

## Build steps
1. `agent/AgentAccessibilityService` + `res/xml` config (read windows, perform gestures, take
   screenshots) + manifest entry + Settings/Permissions rows to turn it on.
2. `agent/ScreenReader` — accessibility tree → compact element list; secure/password handling.
3. `agent/AgentRunner` — the loop, model calls with its own action set, limits, state flow
   (current step, pending confirmation, result), cancel.
4. `agent/AgentPolicy` — blocked apps, irreversible-action detection, password rules.
5. `operate_phone` tool + prompt changes so the assistant knows when to hand off.
6. UI: island "agent" mode (step label + stop), confirmation card, first-use privacy sheet,
   Settings → Agent (on/off, blocked apps, step log). Both looks.
7. Build, install, test on the OnePlus 7 with harmless tasks (open Settings → battery, draft an
   email without sending). You turn the accessibility service on yourself — I won't change system
   security settings. Any real send only with your explicit OK.

## Honest limits
- It will sometimes get stuck on unusual screens; it should stop and say so rather than guess.
- About 2–4 s per step: Gmail ≈ 10–15 s, a Zoom message ≈ 20–40 s.
- Each task is 5–15 AI calls. Free Gemini keys have per-account daily limits (shown in AI Studio),
  so free users get fewer tasks per day.
- Android 13+ phones that installed Prism outside the Play Store must tap "Allow restricted
  settings" once before accessibility can be turned on (not needed on the OnePlus 7, Android 12).
- Google Play restricts accessibility use; sideloading from GitHub is unaffected.

## Approved (2026-09-29)
- Build it as planned.
- Progress and the OK prompt live in the **island** (step label; expands with Cancel / Send; tap to stop).
- Confirmation is **tap only** — no voice yes/no.
