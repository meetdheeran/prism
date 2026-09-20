# Google Gemini Developer API — research for Prism (Android, direct REST, user-supplied key)

Researched 2026-09-20 against live docs at ai.google.dev. Every key fact carries the URL it was read from. Anything I could not open or that the docs no longer publish is marked **UNVERIFIED**.

Target: sideloaded Kotlin/Compose app, OnePlus 7 (Android 12, API 31), no root, no Firebase, user pastes their own Gemini API key. Toolchain: Kotlin 2.0.20, AGP 8.5.2, compileSdk 34, minSdk 31, OkHttp 4.12, kotlinx-serialization 1.7.3.

---

## 0. TL;DR / decisions

1. **No official Android/Kotlin SDK for the Developer API exists any more.** The old `com.google.ai.client.generativeai:generativeai` SDK is deprecated (legacy libraries deprecated 30 Nov 2025; repo archived 16 Dec 2025) and Google's only mobile path is **Firebase AI Logic** (`com.google.firebase:firebase-ai`), which requires a Firebase project. → **Use plain REST over OkHttp.** (§2)
2. **Two REST surfaces now coexist.** The docs front a new **Interactions API** (`POST /v1beta/interactions`, GA June 2026, "recommended for all new projects"). The classic **`generateContent` / `streamGenerateContent`** endpoints are labelled *legacy* but "remain fully supported" with no shutdown date. → **Recommendation for Prism v1: use `generateContent` (v1beta).** It is the surface that has `safetySettings`, keeps all conversation state on the phone (Interactions defaults to `store: true` server-side storage), has a stable schema (Interactions had a breaking schema change in May/June 2026), and matches every JSON shape this brief asked for. Keep the Interactions mapping in §11 so the switch is cheap later. (§3)
3. **Model picks (exact IDs, all GA unless noted):**
   - Fast chat (free-tier eligible): `gemini-3.5-flash-lite` (or `gemini-3.1-flash-lite`, `gemini-2.5-flash-lite` as fallbacks)
   - Best quality on free tier: `gemini-3.8-flash` (1M in / 65k out, thinking, tools; GA 2 Sep 2026)
   - Best quality overall: `gemini-3.1-pro-preview` (**paid only**, preview) — otherwise `gemini-3.8-flash`
   - Vision / audio-in / PDF-in: any of the above (all accept text, image, video, audio, PDF)
   - Speech-to-text specialist: `gemini-3.5-transcribe`
   - TTS: `gemini-3.1-flash-tts-preview` (preview; 24 kHz 16-bit mono PCM out; free tier "Free of charge")
   - Live (voice-to-voice): `gemini-3.8-live` — free of charge on free tier, but see §14 for why it is a poor fit for this app
4. **Free tier exists and is generous on model coverage** (every Flash/Flash-Lite/2.5 Pro/TTS/Live model is "Free of charge"), **but the official docs no longer publish per-model RPM/TPM/RPD numbers** — they are only visible in AI Studio (`https://aistudio.google.com/rate-limit`). Secondary sources disagree; see §6.
5. **Google Search grounding on Gemini 3.x is NOT available on the free tier** ("Not available"). It **is** free on `gemini-2.5-flash` / `gemini-2.5-flash-lite` up to 500 RPD (shared). Paid tier: 5,000 searches/month free across Gemini 3.x then $14/1,000. (§9)
6. **Auth header is `x-goog-api-key: <key>`**; `?key=` query param is still shown in the models.list reference. Google warns against hardcoding keys in mobile apps; Prism stores a *user-supplied* key which is the acceptable pattern here, but store it encrypted (EncryptedSharedPreferences / Keystore). Unrestricted keys "will be rejected starting September 2026" per the API-key page — tell users to create the key in AI Studio (default keys there are restricted to the Gemini API).
7. **Gemini 3 function calling requires echoing back `thoughtSignature` and the `functionCall.id`** in the follow-up turn. Simplest rule: append the model's whole `candidates[0].content` object verbatim to history, then add a `user` turn with `functionResponse` parts. (§8.3)
8. **Sampling params `temperature`/`top_p`/`top_k` were deprecated in the changelog on 21 Jul 2026** and the legacy text page "cautions against modifying these for Gemini 3.x models". Do not send them for 3.x; use `thinkingConfig.thinkingLevel` instead.
9. **Gemini 2.0 models are shut down (1 Jun 2026)**; `gemini-3-pro-preview` shut down 9 Mar 2026. Do not offer them.

---

## 1. Base URL, auth, versions

| Item | Value | Source |
|---|---|---|
| Base URL | `https://generativelanguage.googleapis.com` | https://ai.google.dev/gemini-api/docs/quickstart |
| API version to use | `v1beta` (everything — tools, thinking, TTS, Live, ephemeral tokens — is documented on v1beta) | https://ai.google.dev/api/generate-content |
| Auth header (recommended) | `x-goog-api-key: $GEMINI_API_KEY` | https://ai.google.dev/gemini-api/docs/api-key |
| Auth query param (still documented for models.list) | `?key=$GEMINI_API_KEY` | https://ai.google.dev/api/models |
| Create key | `https://aistudio.google.com/apikey` | https://ai.google.dev/gemini-api/docs/api-key |
| Client-side warning | "Do not hardcode API keys directly in web or mobile apps. Keys compiled in client-side code can be extracted by users." | https://ai.google.dev/gemini-api/docs/api-key |
| Key restrictions | "Unrestricted standard keys will be rejected starting September 2026" | https://ai.google.dev/gemini-api/docs/api-key |
| Leaked-key error | `"Your API key was reported as leaked. Please use another API key."` | https://ai.google.dev/gemini-api/docs/troubleshooting |

Prism note: the key is user-supplied at runtime, never compiled in. Store it via `androidx.security:security-crypto` `EncryptedSharedPreferences` (or Keystore-wrapped). Never put it in a URL query string (it would land in logs) — use the header. Manifest needs only:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" /> <!-- only if audio-in / Live -->
```

---

## 2. SDK status — why REST via OkHttp

| Library | Status | Source |
|---|---|---|
| `com.google.ai.client.generativeai:generativeai` (Google AI client SDK for Android) | **Deprecated.** README: "This SDK is now deprecated, use the unified Firebase SDK." Repo `google-gemini/deprecated-generative-ai-android` archived 16 Dec 2025. | https://github.com/google-gemini/deprecated-generative-ai-android |
| Legacy libraries generally | "legacy libraries are deprecated as of November 30th, 2025"; Android/Swift devs told to "Use Firebase AI Logic" | https://ai.google.dev/gemini-api/docs/libraries |
| Firebase AI Logic (`com.google.firebase:firebase-ai`, BoM `com.google.firebase:firebase-bom:34.19.0`) | Current mobile SDK. **Requires a Firebase project** (create/connect project, enable APIs, register app). The Gemini key is Firebase-managed, not user-supplied. | https://firebase.google.com/docs/ai-logic/migrate-from-google-ai-client-sdks |
| Official GA SDKs | Python `google-genai`, JS `@google/genai`, Go `google.golang.org/genai`, Java `com.google.genai`, C# `Google.GenAI`. **No Kotlin/Android SDK listed.** | https://ai.google.dev/gemini-api/docs/libraries |
| `https://ai.google.dev/gemini-api/docs/android` | 404 — **UNVERIFIED** what the "Build Android apps" nav item points to now. | — |

The Java SDK `com.google.genai` is a server-side library (not tested on Android; pulls gRPC/Apache HTTP deps) — not worth the APK size risk when the REST surface is this simple. **Decision: OkHttp 4.12 + kotlinx-serialization 1.7.3, hand-written DTOs.**

Dependencies (already pinned in the project):

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")      // EventSource for streamGenerateContent?alt=sse
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
implementation("androidx.security:security-crypto:1.1.0-alpha06") // key storage (verify latest stable yourself)
```

---

## 3. The two REST surfaces

### 3.1 Interactions API (new, GA)
- `POST https://generativelanguage.googleapis.com/v1beta/interactions` — "Generally Available as of June 2026" and "recommended for all new projects". https://ai.google.dev/gemini-api/docs/interactions
- Reference: https://ai.google.dev/api/interactions-api
- Server-side conversation state by default (`store: true`, chain with `previous_interaction_id`). Data retention: paid 55 days, free tier 1 day. https://ai.google.dev/gemini-api/docs/interactions
- Not available on it (per overview page): Batch API, explicit caching, automatic function calling, "custom safety settings". (The reference page *does* list an optional `safety_settings: SafetySetting[]` — contradictory; **UNVERIFIED** whether it works.) https://ai.google.dev/gemini-api/docs/interactions , https://ai.google.dev/api/interactions-api
- Had a **breaking schema change**: `outputs[]` → `steps[]`, `response_mime_type`/`response_modalities` folded into `response_format`; new schema default for REST 26 May 2026, legacy removed 8 Jun 2026. https://ai.google.dev/gemini-api/docs/interactions-breaking-changes-may-2026

### 3.2 generateContent (legacy, fully supported)
- `POST /v1beta/models/{model}:generateContent` and `POST /v1beta/models/{model}:streamGenerateContent` — both still in the reference with full field docs. https://ai.google.dev/api/generate-content
- "While it is now considered legacy, the original `generateContent` API remains fully supported." https://ai.google.dev/gemini-api/docs/interactions
- "While `generateContent` remains fully supported, we recommend the Interactions API for all new development." **No shutdown date is mentioned.** https://ai.google.dev/gemini-api/docs/migrate-to-interactions
- Legacy feature guides live under `https://ai.google.dev/gemini-api/docs/generate-content/<topic>` (text-generation, function-calling, google-search, speech-generation, image-understanding, thinking…). Page carries a "Legacy" banner. https://ai.google.dev/gemini-api/docs/generate-content/text-generation

### 3.3 Recommendation
Build Prism on **`generateContent` v1beta** (all JSON below is that shape unless labelled *Interactions*). Reasons: on-device history (no server storage), `safetySettings` supported, stable schema, exact shapes requested by this brief. Abstract the transport behind an interface so the Interactions mapping (§11) can be swapped in later.

---

## 4. Current models (exact IDs)

Source for the catalogue: https://ai.google.dev/gemini-api/docs/models . Per-model cards at `https://ai.google.dev/gemini-api/docs/models/<id>`.

### 4.1 Text-out generalist models (all: input 1,048,576 tokens, output 65,536 tokens; inputs text+image+video+audio+PDF; output text only)

| Model ID | Status | Thinking default | Free tier tokens | Notes | Card |
|---|---|---|---|---|---|
| `gemini-3.8-flash` | GA (2 Sep 2026) | medium (levels low/medium/high; **minimal not supported**) | Free of charge | "most intelligent Flash model"; function calling, search grounding, url context, structured output, caching, batch, code exec. No audio/image gen, no Live. Latest update Sep 2026; cutoff not stated. | https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash |
| `gemini-3.7-flash` | GA (13 Aug 2026) | medium | Free of charge | intro pricing to 31 Dec 2026 | https://ai.google.dev/gemini-api/docs/changelog |
| `gemini-3.6-flash` | GA (21 Jul 2026) | medium | Free of charge | | changelog |
| `gemini-3.5-flash` | GA (19 May 2026) | medium | Free of charge | pricier than 3.8 ($1.50/$9.00) — no reason to pick it | pricing |
| `gemini-3.5-flash-lite` | GA (21 Jul 2026) | minimal | Free of charge | cheapest 3.x ($0.30/$2.50); computer-use preview; latest update Jul 2026 | https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite |
| `gemini-3.1-flash-lite` | GA (7 May 2026) | on | Free of charge | "low-latency, cost-effective"; latest update May 2026 | https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite |
| `gemini-3.1-pro-preview` | Preview | high | **Not available** on free tier | best raw quality; paid only | pricing |
| `gemini-3-flash-preview` | Preview | high | (not in pricing extract — UNVERIFIED) | superseded by 3.x GA Flash models | models |
| `gemini-2.5-flash` | GA (stable) | on | Free of charge | cutoff Jan 2025, updated Jun 2025; **search grounding free 500 RPD**; no shutdown date | https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash |
| `gemini-2.5-flash-lite` | GA (stable) | **off** by default | Free of charge | cutoff Jan 2025, updated Jul 2025; grounding free 500 RPD shared; no shutdown date | https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash-lite |
| `gemini-2.5-pro` | GA (stable) | on | Free of charge (pricing page) — but see §6 note that Pro may be RPD-starved on free | 200k-tier pricing; no shutdown date | pricing, deprecations |

`-latest` aliases exist ("Points to the latest release for a specific model variation… For example: `gemini-flash-latest`") but can hot-swap to previews; use pinned IDs in the app. https://ai.google.dev/gemini-api/docs/models

### 4.2 Audio / speech
| Model ID | Purpose | Limits | Free tier | Source |
|---|---|---|---|---|
| `gemini-3.1-flash-tts-preview` | TTS (preview). Text in → audio out. Streaming + multi-speaker. | in 8,192 / out 16,384 tokens; "TTS session has a context window limit of 32k tokens" | Free of charge | https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-tts-preview , https://ai.google.dev/gemini-api/docs/generate-content/speech-generation |
| `gemini-2.5-flash-preview-tts`, `gemini-2.5-pro-preview-tts` | older TTS previews | deprecations page listed shutdown "December 2025" yet they are still in the models list — **UNVERIFIED** whether still callable; avoid | Free of charge | https://ai.google.dev/gemini-api/docs/deprecations |
| `gemini-3.5-transcribe` | Speech-to-text with diarization (request-response) | — | Free of charge | models, pricing |
| `gemini-3.5-transcribe-live` | Live transcription (WebSocket) | — | Free of charge | pricing |
| `gemini-3.8-live` | Live API native audio (15 Sep 2026). In: text/image/audio/video; out: text+audio. Async function calling, search grounding, output transcription. Proactive audio permanently on; affective dialog removed. | in 131,072 / out 65,536 | Free of charge | https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live |
| `gemini-3.8-live-extended-thinking` | Live with more reasoning | — | (not extracted) | models |
| `gemini-2.5-flash-native-audio-preview-12-2025` | previous Live model | 128k context | Free of charge | live-guide, pricing |
| `gemini-3.1-flash-live-preview` | "legacy" Live | 32k | — | live-guide |

### 4.3 Image generation (not needed for Prism, listed for completeness)
`gemini-3.1-flash-image` (Nano Banana 2), `gemini-3.1-flash-lite-image`, `gemini-3-pro-image`, `gemini-2.5-flash-image`. https://ai.google.dev/gemini-api/docs/models

### 4.4 Shut down / do not use
`gemini-2.0-flash`, `gemini-2.0-flash-001`, `gemini-2.0-flash-lite`, `gemini-2.0-flash-lite-001` (shut down 1 Jun 2026); `gemini-3-pro-preview` (shut down 9 Mar 2026); `gemini-2.5-flash-preview-09-2025`, `gemini-2.5-flash-lite-preview-09-2025` (shut down); Imagen 4 (shut down). https://ai.google.dev/gemini-api/docs/changelog , https://ai.google.dev/gemini-api/docs/deprecations , https://ai.google.dev/gemini-api/docs/models

Deprecation policy: preview models "will be deprecated with at least 2 weeks notice"; stable "usually don't change"; shutdown dates in the table are "earliest possible dates". https://ai.google.dev/gemini-api/docs/models , https://ai.google.dev/gemini-api/docs/deprecations

---

## 5. Pricing (paid tier, per 1M tokens, standard)

Source: https://ai.google.dev/gemini-api/docs/pricing

| Model | Input | Output | Notes |
|---|---|---|---|
| `gemini-3.8-flash` | $0.75 (→ $1.50 after 31 Dec 2026) | $3.75 (→ $7.50) | Batch/Flex 50% off |
| `gemini-3.7-flash` | $0.75 / $1.50 | $3.75 / $7.50 | same intro schedule |
| `gemini-3.5-flash` | $1.50 | $9.00 | |
| `gemini-3.5-flash-lite` | $0.30 | $2.50 | |
| `gemini-2.5-flash` | $0.30 text/image/video; $1.00 audio | $2.50 | |
| `gemini-2.5-pro` | $1.25 (≤200k) / $2.50 (>200k) | $10.00 / $15.00 | |
| `gemini-3.1-flash-tts-preview` | $1.00 | $20.00 (audio) | |
| `gemini-3.8-live` | text $0.75; audio $3.00 (~$0.005/min) | text $4.50; audio $12.00 (~$0.018/min) | |
| `gemini-3.5-transcribe-live` | audio $3.50 (~$0.005/min) | text $21.00 | |
| Context caching | $0.075–$0.15 read + storage $0.50–$1.00 /1M tokens/hour | | |
| URL context | "Charged as input tokens per model pricing" (free-tier column: "Free of charge") | | |
| Google Search grounding | see §9 | | |

Free tier: "Free input & output tokens", "Limited access to certain models", **"Content used to improve our products"**. Paid: "Content **not** used to improve our products". https://ai.google.dev/gemini-api/docs/pricing
Terms (Unpaid Services): "Do not submit sensitive, confidential, or personal information to the Unpaid Services." https://ai.google.dev/gemini-api/terms — surface this in Prism's key-entry screen.

---

## 6. Rate limits

Official page: https://ai.google.dev/gemini-api/docs/rate-limits

- "Rate limits depend on a variety of factors (such as your usage tier) and can be viewed in Google AI Studio." → https://aistudio.google.com/rate-limit?timeRange=last-28-days (login required; not fetchable).
- Dimensions: RPM, TPM (input), RPD. Exceeding returns **`429 RESOURCE_EXHAUSTED`**.
- Usage tiers (verbatim table): Free — "Active project or free trial", cap N/A; Tier 1 — "Set up and link an active billing account", $250 billing cap, $10 spend per rolling 10 min; Tier 2 — "Paid $100 + 3 days", $2,000 cap, $50/10 min; Tier 3 — "Paid $1,000 + 30 days", $20,000–$100,000+, $200/10 min. "Tier upgrades from the Free to Tier 1 will typically take effect instantly."
- "Specified rate limits are not guaranteed and actual capacity may vary."
- **The official docs no longer publish per-model free-tier RPM/TPM/RPD tables** (confirmed on both the HTML and `.md.txt` versions).

**UNVERIFIED secondary-source figures** (conflicting; use only as rough expectations):
- pecollective (dated Sep 2026, no source given): Gemini 3 Flash 10 RPM / 250k TPM / 1,500 RPD; 3.1 Flash-Lite 15 / 250k / 1,000; 2.5 Flash 10 / 250k / 1,500; 2.5 Pro 5 / 150k / 50. https://pecollective.com/tools/gemini-free-tier-guide/
- aifreeapi (Jan 2026): 2.5 Pro 5 / 250k / 100; 2.5 Flash 10 / 250k / 250; 2.5 Flash-Lite 15 / 250k / 1,000; "Limits apply per project (not per API key)", "daily quotas reset at midnight Pacific Time". https://www.aifreeapi.com/en/posts/gemini-api-free-tier-rate-limits
- tinkerllm (May 2026): "roughly 15 RPM and 1,500 RPD" for Flash; "Pro models removed from the free tier in April 2026" (contradicts pricing page which still says 2.5 Pro "Free of charge" — treat Pro-on-free as unreliable). https://tinkerllm.com/blog/gemini-api-free-tier-limits-rate-quotas/
- Web search snippet claimed "Gemini 3.8 Flash currently provides around 20 free requests per day" — **UNVERIFIED**, single unsourced claim.

**Implementation consequence:** Prism must treat limits as unknown: parse 429 bodies, honour any `RetryInfo.retryDelay` / `Retry-After`, back off with jitter, and let the user pick a fallback model (e.g. 3.8-flash → 3.5-flash-lite → 2.5-flash-lite) when a 429 says daily quota. Show a link to AI Studio's rate-limit page in Settings.

---

## 7. Core generateContent request/response shapes

Reference: https://ai.google.dev/api/generate-content . Legacy guide: https://ai.google.dev/gemini-api/docs/generate-content/text-generation

Endpoints:
```
POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse
Headers: x-goog-api-key: <key>   Content-Type: application/json
```

Request body fields (top level): `contents[]` (required), `tools[]`, `toolConfig`, `safetySettings[]`, `systemInstruction` (text only), `generationConfig`, `cachedContent`, `serviceTier`, `store`. JSON accepts both camelCase and snake_case (`system_instruction`, `inline_data`, `mime_type` all appear in official curl samples); responses are camelCase.

`Content` = `{ "role": "user" | "model", "parts": [Part] }`. `Part` = one of `text`, `inlineData{mimeType,data}`, `fileData`, `functionCall{id,name,args}`, `functionResponse{id,name,response}`, `thought` (bool), `thoughtSignature` (string, sits beside another field on the same part).

`generationConfig` fields: `stopSequences[]`, `responseMimeType`, `responseSchema`, `responseJsonSchema`, `responseModalities[]` (TEXT|IMAGE|AUDIO), `candidateCount`, `maxOutputTokens`, `temperature`, `topP`, `topK`, `seed`, `presencePenalty`, `frequencyPenalty`, `thinkingConfig{includeThoughts, thinkingBudget, thinkingLevel}`, `speechConfig{voiceConfig{prebuiltVoiceConfig{voiceName}}, multiSpeakerVoiceConfig, languageCode}`, `mediaResolution`. https://ai.google.dev/api/generate-content

Thinking: `thinkingLevel` values `"minimal" | "low" | "medium" | "high"` (3.x; 3.8-flash has no minimal). `thinkingBudget` is the 2.5 mechanism: 2.5 Pro 128–32768, 2.5 Flash 0–24576 (0 disables), 2.5 Flash-Lite 512–24576. `includeThoughts: true` returns thought summaries as parts with `"thought": true`. https://ai.google.dev/gemini-api/docs/generate-content/thinking

### 7.1 Streaming chat request: system_instruction + inline image + tools (function + google_search)

```json
POST /v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse
{
  "system_instruction": {
    "parts": [{ "text": "You are Prism, a concise assistant running on the user's phone." }]
  },
  "contents": [
    {
      "role": "user",
      "parts": [
        { "text": "What is in this photo, and what's the weather there right now?" },
        { "inline_data": { "mime_type": "image/jpeg", "data": "<base64>" } }
      ]
    }
  ],
  "tools": [
    { "google_search": {} },
    {
      "functionDeclarations": [
        {
          "name": "get_weather",
          "description": "Current weather for a city.",
          "parameters": {
            "type": "object",
            "properties": { "city": { "type": "string" } },
            "required": ["city"]
          }
        }
      ]
    }
  ],
  "toolConfig": { "functionCallingConfig": { "mode": "AUTO" } },
  "safetySettings": [
    { "category": "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold": "BLOCK_ONLY_HIGH" }
  ],
  "generationConfig": {
    "maxOutputTokens": 2048,
    "thinkingConfig": { "thinkingLevel": "low" }
  }
}
```
- Image part shape verbatim from https://ai.google.dev/gemini-api/docs/generate-content/image-understanding ; `google_search: {}` verbatim from https://ai.google.dev/gemini-api/docs/generate-content/google-search ; `functionDeclarations` and `toolConfig` from https://ai.google.dev/gemini-api/docs/generate-content/function-calling .
- Combining built-in tools (`google_search`) with `functionDeclarations` in one request is documented for **Gemini 3 models** only; on 2.5 keep them in separate requests. https://ai.google.dev/gemini-api/docs/generate-content/google-search
- Mode `VALIDATED` is the default when tools are combined; `AUTO` is default for function declarations alone. Modes: `AUTO`, `ANY`, `NONE`, `VALIDATED`; `allowedFunctionNames[]`. https://ai.google.dev/gemini-api/docs/generate-content/function-calling
- Do not send `temperature`/`topP`/`topK` on 3.x (deprecated 21 Jul 2026; legacy page "cautions against modifying these for Gemini 3.x"). https://ai.google.dev/gemini-api/docs/changelog

### 7.2 SSE stream framing
Each SSE event is `data: {GenerateContentResponse}` — same object as non-streaming, with partial `candidates[0].content.parts`. Text arrives as successive `parts[].text` deltas to concatenate; `usageMetadata` is complete on the final chunk; a `functionCall` part may arrive in a later chunk than the text. Parse with OkHttp `EventSource` (`okhttp-sse`) or a line reader on `data:` prefixes; ignore blank keep-alive lines. (Framing is the standard SSE described on https://ai.google.dev/gemini-api/docs/generate-content/text-generation ; exact chunk boundaries are implementation detail.)

### 7.3 Response paths (non-streaming and each streamed chunk)

```
candidates[0].content.role                         -> "model"
candidates[0].content.parts[i].text                -> assistant text (skip parts where .thought == true unless showing summaries)
candidates[0].content.parts[i].thoughtSignature    -> opaque string; MUST be echoed back (see §8.3)
candidates[0].content.parts[i].functionCall        -> { "id": "...", "name": "...", "args": {...} }
candidates[0].content.parts[i].inlineData          -> { "mimeType": "...", "data": "<base64>" }  (TTS/image output)
candidates[0].finishReason                         -> STOP | MAX_TOKENS | SAFETY | RECITATION | LANGUAGE | OTHER | BLOCKLIST | PROHIBITED_CONTENT | SPII | MALFORMED_FUNCTION_CALL
candidates[0].finishMessage                        -> explanatory text
candidates[0].safetyRatings[]                      -> { category, probability: NEGLIGIBLE|LOW|MEDIUM|HIGH }
candidates[0].groundingMetadata                    -> see §9
candidates[0].urlContextMetadata.urlMetadata[]     -> { retrievedUrl, urlRetrievalStatus }
promptFeedback.blockReason                         -> set when the *prompt* was blocked (no candidates)
usageMetadata.promptTokenCount
usageMetadata.candidatesTokenCount
usageMetadata.thoughtsTokenCount                   -> thinking models
usageMetadata.cachedContentTokenCount
usageMetadata.totalTokenCount
usageMetadata.promptTokensDetails[] / candidatesTokensDetails[] / cacheTokensDetails[]  -> per-modality breakdown
modelVersion, responseId
```
Source: https://ai.google.dev/api/generate-content

---

## 8. Function calling (generateContent)

Guide: https://ai.google.dev/gemini-api/docs/generate-content/function-calling ; reference: https://ai.google.dev/api/generate-content

### 8.1 Declaration
`FunctionDeclaration { name, description, parameters (OpenAPI-style Schema), parametersJsonSchema (alt: raw JSON Schema), response, behavior }`. Use `parameters` with `type: "object"`, `properties`, `required` as in §7.1.

### 8.2 Model turn containing a call (what comes back)
```json
{
  "candidates": [{
    "content": {
      "role": "model",
      "parts": [
        {
          "functionCall": { "id": "8f2b1a3c", "name": "get_weather", "args": { "city": "Bengaluru" } },
          "thoughtSignature": "CoMDAXLI2n..."
        }
      ]
    },
    "finishReason": "STOP"
  }],
  "usageMetadata": { "...": "..." }
}
```
"Gemini 3 now always returns a unique `id` with every `functionCall`." Parallel calls = several `functionCall` parts in one `parts[]`; results may be returned in any order because "the Gemini API maps each result back using the `id`".

### 8.3 Function-response turn (the follow-up request)
Append the model's `content` object **verbatim** (keeps `thoughtSignature` and `id`), then a `user` turn with one `functionResponse` part per call:

```json
{
  "contents": [
    { "role": "user", "parts": [{ "text": "What's the weather in Bengaluru?" }] },
    {
      "role": "model",
      "parts": [
        {
          "functionCall": { "id": "8f2b1a3c", "name": "get_weather", "args": { "city": "Bengaluru" } },
          "thoughtSignature": "CoMDAXLI2n..."
        }
      ]
    },
    {
      "role": "user",
      "parts": [
        {
          "functionResponse": {
            "id": "8f2b1a3c",
            "name": "get_weather",
            "response": { "result": { "tempC": 27, "sky": "cloudy" } }
          }
        }
      ]
    }
  ],
  "tools": [{ "functionDeclarations": [ "...same declarations again..." ] }]
}
```
- Role for function results is `"user"` (verbatim from the legacy guide: `{"role":"user","parts":[{"functionResponse":{"name":..., "response":{"result":...}, "id":...}}]}`).
- `response` must be a JSON object (wrap scalars, e.g. `{"result": ...}`).
- Thought signatures: "Gemini 3 models may return thought signatures for all types of parts"; "passing back thought signatures is mandatory for function calling" when managing history manually; "Return the entire response with all parts back to the model in subsequent turns"; don't merge parts. https://ai.google.dev/gemini-api/docs/generate-content/thinking , https://ai.google.dev/gemini-api/docs/generate-content/function-calling
- **UNVERIFIED** (page no longer states): the exact 400 message on a missing signature and the old dummy value `"skip_thought_signature_validator"`. Design Prism to never need it: persist `thoughtSignature` in the local message model.
- Streaming: a `thoughtSignature` may land in a chunk with an empty/absent text; keep part-level fidelity when reassembling the model turn for history (don't collapse all chunks into one text part if any chunk carried a signature or functionCall).

---

## 9. Grounding with Google Search

Guide (legacy shapes): https://ai.google.dev/gemini-api/docs/generate-content/google-search ; Interactions shapes: https://ai.google.dev/gemini-api/docs/google-search ; pricing: https://ai.google.dev/gemini-api/docs/pricing ; terms: https://ai.google.dev/gemini-api/terms

### 9.1 Request (verbatim)
```json
POST /v1beta/models/gemini-3.8-flash:generateContent
{
  "contents": [{ "parts": [{ "text": "Who won the euro 2024?" }] }],
  "tools": [{ "google_search": {} }]
}
```
Supported on all current models (3.8/3.7/3.6/3.5/3.1 Flash family, 2.5 family). Older (1.5) models used `google_search_retrieval` — irrelevant now.

### 9.2 Response (verbatim example structure)
```json
{
  "candidates": [{
    "content": { "role": "model", "parts": [{ "text": "Spain won Euro 2024, defeating England 2-1 in the final. This victory marks Spain's record fourth European Championship title." }] },
    "groundingMetadata": {
      "webSearchQueries": ["UEFA Euro 2024 winner", "who won euro 2024"],
      "searchEntryPoint": { "renderedContent": "<!-- HTML and CSS for the search widget -->" },
      "groundingChunks": [
        { "web": { "uri": "https://vertexaisearch.cloud.google.com.....", "title": "aljazeera.com" } },
        { "web": { "uri": "https://vertexaisearch.cloud.google.com.....", "title": "uefa.com" } }
      ],
      "groundingSupports": [
        { "segment": { "startIndex": 0,  "endIndex": 85,  "text": "Spain won Euro 2024, defeatin..." }, "groundingChunkIndices": [0] },
        { "segment": { "startIndex": 86, "endIndex": 210, "text": "This victory marks Spain's..." },   "groundingChunkIndices": [0, 1] }
      ]
    }
  }]
}
```
Reference also lists `groundingSupports[].confidenceScores[]`, `segment.partIndex`, `retrievalMetadata`, `searchEntryPoint.sdkBlob`. `groundingChunks[].web.uri` are Google redirect URLs (`vertexaisearch.cloud.google.com/...`) — open them as-is.

### 9.3 Display / attribution obligations (Terms, verbatim)
- "You will only display the Grounded Results with the associated Search Suggestion(s) to the end user who submitted the prompt."
- "You will not modify, or intersperse any other content with, the Grounded Results or Search Suggestions."
- "You will not place any interstitial content between any Link or Search Suggestions and the associated destination page, redirect end users away from the destination pages, or minimize, remove, or otherwise inhibit the full and complete display of any destination page."
- Google stores prompts/context/output "for thirty (30) days for the purposes of creating Grounded Results and Search Suggestions."
- `searchEntryPoint.renderedContent` "contains the HTML and CSS to render the **required** Search Suggestions".

**Prism implementation:** when `groundingMetadata.searchEntryPoint.renderedContent` is present, render it in an `AndroidView { WebView }` beneath the message (`loadDataWithBaseURL(null, html, "text/html", "utf-8", null)`, JS off, `setSupportZoom(false)`), and open clicks in the system browser via `Intent.ACTION_VIEW`. Render `groundingSupports` as tappable citation chips mapping to `groundingChunks[idx].web.uri/title`. Do not strip or restyle the widget.

### 9.4 Cost / free tier (pricing page, verbatim columns)
| Model | Free tier | Paid tier |
|---|---|---|
| `gemini-3.8-flash`, `gemini-3.5-flash-lite` (all Gemini 3.x) | **"Not available"** | "5,000 free search requests per month (shared across all Gemini 3.x models), then $14 per 1,000 requests." |
| `gemini-2.5-flash` | "Free of charge, up to 500 RPD (limit shared with Flash-Lite RPD)" | "1,500 RPD (free, limit shared with Flash-Lite RPD), then $35 / 1,000 grounded prompts" |
| `gemini-2.5-flash-lite` | "Free of charge, up to 500 RPD (limit shared with Flash RPD)" | same as above |

Billing unit on Gemini 3: **per search query the model executes** (one prompt can trigger several; empty queries ignored). On 2.5 and older: per grounded prompt. https://ai.google.dev/gemini-api/docs/generate-content/google-search

**Consequence:** for free-tier users, route "web search on" requests to `gemini-2.5-flash` (or `-lite`); for paid users use `gemini-3.8-flash`. Make the search toggle per-message and show the model swap.

---

## 10. url_context tool

Guide: https://ai.google.dev/gemini-api/docs/url-context (Interactions examples); reference field for generateContent: `tools[].urlContext` https://ai.google.dev/api/generate-content

```json
"tools": [{ "url_context": {} }]          // generateContent (snake_case accepted; camelCase key is urlContext)
"tools": [{ "type": "url_context" }]      // Interactions
```
- Put the URLs in the prompt text; max **20 URLs per request**; up to **34 MB per URL**; public URLs only (no localhost/private/tunnels).
- Unsupported: paywalled content, YouTube, Google Workspace files, video/audio files.
- Response: `candidates[0].urlContextMetadata.urlMetadata[] { retrievedUrl, urlRetrievalStatus }` (generateContent); `url_context_result.result[] { url, status: success|error|paywall|unsafe }` (Interactions).
- Cost: retrieved content billed as input tokens; pricing table free-tier column says "Free of charge".
- Supported on all 3.x and 2.5 models. Can be combined with `google_search`.

---

## 11. Interactions API cheat-sheet (for the future swap)

Reference: https://ai.google.dev/api/interactions-api ; streaming: https://ai.google.dev/gemini-api/docs/streaming ; migration: https://ai.google.dev/gemini-api/docs/migrate-to-interactions

```
POST https://generativelanguage.googleapis.com/v1beta/interactions          (add ?alt=sse or "stream": true for SSE)
GET/DELETE  /v1beta/interactions/{id}     POST /v1beta/interactions/{id}/cancel
```
Request: `model`, `input` (string | parts[] | turns[] `{role:"user"|"model", content:[...]}` or `{type:"user_input", content:[...]}`), `system_instruction` (string), `tools[]` (`{type:"function",name,description,parameters}`, `{type:"google_search"}`, `{type:"url_context"}`, `{type:"code_execution"}`, `{type:"file_search"}`, `{type:"mcp_server",...}`, `{type:"computer_use"}`, `{type:"google_maps"}`), `generation_config` (`max_output_tokens`, `temperature`, `top_p`, `top_k`, `seed`, `stop_sequences`, `thinking_level` minimal|low|medium|high, `thinking_summaries` auto|none, `tool_choice` auto|any|none|validated or `{allowed_tools:{mode,tools[]}}`, `speech_config`, `transcription_config`), `response_format` (`{type:"text", mime_type?, schema?}` | `{type:"audio"}` | `{type:"image"}`), `stream`, `store` (default true), `previous_interaction_id`, `background`, `service_tier`.
Parts: `{type:"text",text}`, `{type:"image",data,mime_type}` or `{type:"image",uri,mime_type}`, `{type:"audio",data,mime_type}`, `{type:"document",data,mime_type:"application/pdf"}`, `{type:"function_call",id,name,arguments}`, `{type:"function_result",call_id,name,result}` (result = string | object | parts[]), `{type:"thought",signature}`.
Response: `{id, object:"interaction", created, updated, model, status: in_progress|requires_action|completed|failed|cancelled|incomplete|queued, steps:[{type: user_input|model_output|function_call|function_result|thought|google_search_call|google_search_result|url_context_call|url_context_result|..., content:[...], name, arguments, id, signature, summary}], usage:{total_input_tokens,total_output_tokens,total_tokens,total_thought_tokens,total_cached_tokens,input_tokens_by_modality[],output_tokens_by_modality[]}, errors:[{code,message}]}`.
Grounded text carries `annotations:[{type:"url_citation",url,title,start_index,end_index}]`; `google_search_result.result[].search_suggestions` is the HTML widget.
SSE events: `interaction.created`, `interaction.status_update`, `step.start`, `step.delta` (`{"index":0,"delta":{"type":"text","text":"..."},"event_type":"step.delta"}`; delta types `text`, `thought_summary`, `arguments_delta` (JSON string to accumulate), `image`, `audio`, `text_annotation_delta`, tool call/result types), `step.stop`, `interaction.completed` (carries `usage`), `error`, `done` (`data: [DONE]`).
Stateless mode: `store:false` and resend the full `steps` including every `thought` step "exactly as they were received"; stateful: `store:true` + `previous_interaction_id` and the server keeps signatures. https://ai.google.dev/gemini-api/docs/thinking
Errors: `{"error":{"code":"invalid_request","message":"..."}}` with string codes `invalid_request` 400, `failed_precondition` 400, `authentication` 401, `payment_required` 402, `permission_denied` 403, `not_found` 404, `rate_limit_exceeded` 429, `quota_exceeded` 429, `service_unavailable` 503, `deadline_exceeded` 504. https://ai.google.dev/gemini-api/docs/api-errors

---

## 12. Multimodal input details

### 12.1 Images — https://ai.google.dev/gemini-api/docs/generate-content/image-understanding
- Part: `{"inline_data": {"mime_type": "image/jpeg", "data": "<base64>"}}` (verbatim curl). Put the text prompt before the image when there is one image (Interactions page best practice).
- MIME: `image/png`, `image/jpeg`, `image/webp`, `image/heic`, `image/heif`.
- "Inline image data limits your total request size (text prompts, system instructions, and inline bytes) to 20MB." Above that, use the Files API (not needed for a phone: downscale instead).
- Tokens: "258 tokens if both dimensions <= 384 pixels. Larger images are tiled into 768x768 pixel tiles, each costing 258 tokens." Up to 3,600 images/request.
- Prism: downscale camera shots to ≤1536 px long edge, JPEG q≈80, before base64 (Base64.NO_WRAP). 20 MB is the *request* cap, base64 inflates 4/3.

### 12.2 Audio in — https://ai.google.dev/gemini-api/docs/audio
- Part: same `inline_data`. Supported MIME: `audio/wav`, `audio/mp3`, `audio/mpeg`, `audio/aiff`, `audio/aac`, `audio/ogg`, `audio/flac`, `audio/m4a`, `audio/l16`, `audio/opus`, `audio/alaw`, `audio/mulaw`, `audio/webm`. (`audio/mp4`, `audio/3gpp` not listed — record with `MediaRecorder` as AAC in `.m4a`/`audio/m4a`, or write raw 16-bit PCM WAV via `AudioRecord` for zero-dependency reliability.)
- 32 tokens per second (1 min = 1,920 tokens); max 9.5 h per prompt; request ≤ 20 MB total.
- Ask for timestamps with "MM:SS" references. Dedicated STT: `gemini-3.5-transcribe` (models page) — **UNVERIFIED** request shape (Interactions `transcription_config` exists in the reference: `language_codes`, `mode: smart|verbatim`, `custom_vocabulary`, `diarization_mode`, `timestamp_granularities`).

### 12.3 PDF / documents — https://ai.google.dev/gemini-api/docs/document-processing
- Part: `{"inline_data": {"mime_type": "application/pdf", "data": "<base64>"}}`; Interactions: `{type:"document", data, mime_type:"application/pdf"}`.
- Up to 1,000 pages; ~258 tokens per page; with Gemini 3 "native text extracted from PDFs is not charged". File size limit stated 50 MB, but keep under the 20 MB inline request cap on a phone.
- Only PDFs get visual understanding; TXT/MD/HTML/XML are treated as plain text. DOCX not natively supported — convert or extract text first.

---

## 13. Safety settings

https://ai.google.dev/gemini-api/docs/safety-settings ; enum names https://ai.google.dev/api/generate-content

```json
"safetySettings": [
  { "category": "HARM_CATEGORY_HARASSMENT",        "threshold": "BLOCK_ONLY_HIGH" },
  { "category": "HARM_CATEGORY_HATE_SPEECH",       "threshold": "BLOCK_ONLY_HIGH" },
  { "category": "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold": "BLOCK_MEDIUM_AND_ABOVE" },
  { "category": "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold": "BLOCK_ONLY_HIGH" }
]
```
- Thresholds: `OFF`, `BLOCK_NONE`, `BLOCK_ONLY_HIGH`, `BLOCK_MEDIUM_AND_ABOVE`, `BLOCK_LOW_AND_ABOVE`.
- "The default block threshold is **Off** for Gemini 2.5 and 3 models." So omit the field unless the user wants stricter filtering. (`HARM_CATEGORY_CIVIC_INTEGRITY` no longer appears on the page — **UNVERIFIED** whether still accepted; don't send it.)
- Blocked prompt → `promptFeedback.blockReason`, no candidates. Blocked output → `candidates[0].finishReason == "SAFETY"` with `safetyRatings[] { category, probability: NEGLIGIBLE|LOW|MEDIUM|HIGH }`.
- Interactions API: overview says custom safety settings unsupported; reference lists `safety_settings` — **UNVERIFIED**.

---

## 14. Speech generation (TTS)

Legacy guide (generateContent shapes): https://ai.google.dev/gemini-api/docs/generate-content/speech-generation ; model card https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-tts-preview ; voices/languages https://ai.google.dev/gemini-api/docs/speech-generation

Request (verbatim):
```json
POST /v1beta/models/gemini-3.1-flash-tts-preview:generateContent
{
  "contents": [{ "parts": [{ "text": "Say cheerfully: Have a wonderful day!" }] }],
  "generationConfig": {
    "responseModalities": ["AUDIO"],
    "speechConfig": {
      "voiceConfig": { "prebuiltVoiceConfig": { "voiceName": "Kore" } }
    }
  }
}
```
Multi-speaker: `speechConfig.multiSpeakerVoiceConfig.speakerVoiceConfigs[] { speaker, voiceConfig{prebuiltVoiceConfig{voiceName}} }`.
Response: `candidates[0].content.parts[0].inlineData.data` = base64 **raw PCM, signed 16-bit little-endian, 24,000 Hz, mono** (doc's own pipeline: `ffmpeg -f s16le -ar 24000 -ac 1`). `inlineData.mimeType` string — **UNVERIFIED** exact value (historically `audio/L16;codec=pcm;rate=24000`); don't branch on it, assume s16le/24k/mono.
Voices (30): Zephyr, Puck, Charon, Kore, Fenrir, Leda, Orus, Aoede, Callirrhoe, Autonoe, Enceladus, Iapetus, Umbriel, Algieba, Despina, Erinome, Algenib, Rasalgethi, Laomedeia, Achernar, Alnilam, Schedar, Gacrux, Pulcherrima, Achird, Zubenelgenubi, Vindemiatrix, Sadachbia, Sadaltager, Sulafat. 80+ languages, auto-detected.
Limits: 32k-token session context; input 8,192 / output 16,384 tokens. Style is steered by natural-language prompt ("Say cheerfully: …") and expressive audio tags.
Cost: paid $1.00 in / $20.00 audio out per 1M tokens; free tier "Free of charge" (RPM/RPD unknown, see §6).
Android playback: `AudioTrack(AudioAttributes, AudioFormat(ENCODING_PCM_16BIT, 24000, CHANNEL_OUT_MONO), bufferSize, MODE_STREAM)` and `write()` the decoded bytes; to save, prepend a 44-byte WAV header.
Streaming TTS via `streamGenerateContent?alt=sse` returns successive `inlineData` chunks — the 3.1 TTS card lists streaming support; chunk boundaries are safe to concatenate as PCM.

---

## 15. Live API — and whether to use it on the phone

Docs: https://ai.google.dev/gemini-api/docs/live , https://ai.google.dev/gemini-api/docs/live-guide , reference https://ai.google.dev/api/live , tokens https://ai.google.dev/gemini-api/docs/ephemeral-tokens

- Endpoint: `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent` (append `?key=<API_KEY>` for a standard key — **UNVERIFIED** exact query name for standard keys on the current page; ephemeral tokens documented as `?access_token=<token>` or header `Authorization: Token <token>`).
- Models: `gemini-3.8-live` (default), `gemini-3.8-live-extended-thinking`, `gemini-2.5-flash-native-audio-preview-12-2025`, `gemini-3.1-flash-live-preview` (legacy).
- First message: `{"setup": {"model": "models/gemini-3.8-live", "generationConfig": {"responseModalities": ["AUDIO"], "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": "Kore"}}}}, "systemInstruction": {...}, "tools": [...], "inputAudioTranscription": {}, "outputAudioTranscription": {}, "realtimeInputConfig": {"automaticActivityDetection": {"silenceDurationMs": 500}}, "sessionResumption": {}, "contextWindowCompression": {"slidingWindow": {}}}}` (field names from the reference; wrapper `setup` and `models/` prefix from the reference's "models/{model}" requirement).
- Mic chunks: `{"realtimeInput": {"audio": {"data": "<base64 PCM>", "mimeType": "audio/pcm;rate=16000"}}}` — "raw 16-bit PCM audio, 16kHz, little-endian". Text turns: `{"clientContent": {"turns": [...], "turnComplete": true}}`. `{"realtimeInput": {"audioStreamEnd": true}}` to force end of speech.
- Server: `serverContent.modelTurn.parts[].inlineData` (24 kHz 16-bit PCM), `serverContent.turnComplete`, `.interrupted`, `.inputTranscription`, `.outputTranscription`, `usageMetadata.totalTokenCount`, `sessionResumptionUpdate.newHandle`, `toolCall` for async function calls.
- Limits: "Audio-only sessions are limited to 15 minutes" (2 min with video) unless compression/resumption used; context 128k (native audio) / 32k (others); 3.8-live card says 131,072 in.
- Cost: `gemini-3.8-live` audio in ≈ $0.005/min, audio out ≈ $0.018/min (paid). Free tier "Free of charge".

**Verdict for Prism:** technically feasible with OkHttp `WebSocket` + `AudioRecord`(16 kHz mono) + `AudioTrack`(24 kHz), but: the key travels in the WS URL (ephemeral tokens need a *server* holding the real key — we have none), free-tier concurrency/RPD for Live is unknown, and sessions cap at ~15 min. Ship it as an optional "Voice mode (beta)" after the request/response path works, or skip. A cheaper "push-to-talk" flow — record → `inline_data` audio to `gemini-3.8-flash` → stream text → TTS — covers most value with no WebSocket.

---

## 16. "Test connection" — list models

Reference: https://ai.google.dev/api/models

```
GET https://generativelanguage.googleapis.com/v1beta/models?pageSize=100
x-goog-api-key: <key>
```
Response:
```json
{
  "models": [
    {
      "name": "models/gemini-3.8-flash",
      "baseModelId": "...", "version": "...",
      "displayName": "Gemini 3.8 Flash",
      "description": "...",
      "inputTokenLimit": 1048576,
      "outputTokenLimit": 65536,
      "supportedGenerationMethods": ["generateContent", "streamGenerateContent", "..."],
      "thinking": true,
      "temperature": 1.0, "maxTemperature": 2.0, "topP": 0.95, "topK": 64
    }
  ],
  "nextPageToken": "..."
}
```
- 200 → key valid (also gives a live model list to populate the model picker: filter `supportedGenerationMethods` contains `generateContent`; strip `models/` prefix for use in URLs).
- 400 with "API key not valid" → bad key (**UNVERIFIED** exact string on current docs; match on HTTP code + `status`).
- 403 → key restricted / project lacks permission / leaked key.
- `GET /v1beta/models/{model}` for one model.

---

## 17. Error JSON shapes

### 17.1 generateContent / models.* (Google standard `google.rpc.Status`)
Standard shape: https://google.aip.dev/193 (`"code"` = HTTP status, `"status"` = `google.rpc.Code` name):
```json
{
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. ...",
    "status": "RESOURCE_EXHAUSTED",
    "details": [
      { "@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "...", "domain": "googleapis.com", "metadata": { "...": "..." } },
      { "@type": "type.googleapis.com/google.rpc.QuotaFailure", "violations": [ { "quotaMetric": "...", "quotaId": "...", "quotaDimensions": { "model": "...", "location": "..." } } ] },
      { "@type": "type.googleapis.com/google.rpc.Help", "links": [ { "description": "...", "url": "..." } ] },
      { "@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "34s" }
    ]
  }
}
```
Mapping: 400 `INVALID_ARGUMENT` (malformed body, bad key) / `FAILED_PRECONDITION` (e.g. billing/region); 401 `UNAUTHENTICATED`; 403 `PERMISSION_DENIED` (key lacks permission, leaked key, unsupported location); 404 `NOT_FOUND` (bad model id); 429 `RESOURCE_EXHAUSTED`; 500 `INTERNAL`; 503 `UNAVAILABLE`; 504 `DEADLINE_EXCEEDED`. The exact `message` strings and which `details` entries Gemini includes are **UNVERIFIED** (the Gemini api-errors page now documents only the Interactions format) — parse defensively: `error.code` (int), `error.status` (string), `error.message`, optional `details[]` scanning for `RetryInfo.retryDelay` (string like `"34s"`).

Retry guidance (https://ai.google.dev/gemini-api/docs/troubleshooting): retry `429`, `408`, `5xx` with exponential backoff + jitter and a max retry count; "Do not retry on client errors (like `400`, `402`, or `403`)".

### 17.2 Interactions API
`{"error": {"code": "invalid_request", "message": "The value 'invalid_tool_type_xyz' is not supported for 'type' at 'tools[0]'."}}` — string codes listed in §11. Generation-blocked codes: `safety`, `recitation`, `language`, `prohibited_content`, `spii`, `blocklist`, `image_safety`; generation errors: `malformed_function_call`, `malformed_tool_call`, `unexpected_tool_call`, `no_image`, `too_many_tool_calls`. https://ai.google.dev/gemini-api/docs/api-errors

---

## 18. Kotlin implementation notes (OkHttp 4.12 + kotlinx-serialization 1.7.3)

```kotlin
@Serializable data class Part(
    val text: String? = null,
    val inlineData: Blob? = null,
    val functionCall: FunctionCall? = null,
    val functionResponse: FunctionResponse? = null,
    val thought: Boolean? = null,
    val thoughtSignature: String? = null,
)
@Serializable data class Blob(val mimeType: String, val data: String)
@Serializable data class FunctionCall(val id: String? = null, val name: String, val args: JsonObject = JsonObject(emptyMap()))
@Serializable data class FunctionResponse(val id: String? = null, val name: String, val response: JsonObject)
@Serializable data class Content(val role: String? = null, val parts: List<Part>)
@Serializable data class Tool(
    val functionDeclarations: List<FunctionDeclaration>? = null,
    val googleSearch: JsonObject? = null,   // JsonObject(emptyMap()) to enable
    val urlContext: JsonObject? = null,
)
@Serializable data class GenerateContentRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null,
    val tools: List<Tool>? = null,
    val toolConfig: ToolConfig? = null,
    val safetySettings: List<SafetySetting>? = null,
    val generationConfig: GenerationConfig? = null,
)
@Serializable data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList(),
    val promptFeedback: PromptFeedback? = null,
    val usageMetadata: UsageMetadata? = null,
    val modelVersion: String? = null,
)
val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
```
- camelCase serialisation is accepted server-side (the reference is camelCase; curl samples mix snake_case). Keep `ignoreUnknownKeys = true` — new fields appear constantly.
- Streaming: `EventSources.createFactory(okHttpClient).newEventSource(request, listener)`; in `onEvent(data)` decode `GenerateContentResponse`; accumulate text; keep the raw parts list for history; treat `finishReason`/`usageMetadata` on the last chunk as terminal. Set `readTimeout` ≥ 60 s (thinking models can pause before the first token); disable OkHttp retries on `429`.
- Store per-message: role, parts (including `thoughtSignature`, `functionCall.id`), `groundingMetadata`, `usageMetadata`. Rebuild `contents` from that for every request (stateless).
- History trimming: drop oldest *pairs* but never split a model turn with `functionCall` from its `functionResponse` turn, and never drop parts that carry `thoughtSignature` from the most recent model turn.
- Token budgeting: use `usageMetadata.totalTokenCount` from the last response; there is also `POST /v1beta/models/{model}:countTokens` (not fetched this session — **UNVERIFIED** current shape).
- Base64 on Android: `android.util.Base64.encodeToString(bytes, Base64.NO_WRAP)`.
- Network on API 31: cleartext not needed; all HTTPS. No special manifest flags.

---

## 19. Open items / UNVERIFIED list

1. Per-model free-tier RPM/TPM/RPD — not published; only in AI Studio (login). Secondary figures conflict (§6).
2. Whether `gemini-2.5-pro` is really usable on the free tier (pricing says free; a blog says removed Apr 2026).
3. Exact `message` text and `details[]` contents of Gemini's 400/403/429 `google.rpc`-style bodies (api-errors page now shows only Interactions format).
4. Exact `inlineData.mimeType` string returned by TTS (format itself is confirmed: s16le 24 kHz mono).
5. `HARM_CATEGORY_CIVIC_INTEGRITY` acceptance; `safety_settings` on Interactions.
6. Whether `gemini-2.5-flash-preview-tts` / `-pro-preview-tts` still respond (deprecations page said Dec 2025 shutdown; models page still lists them).
7. Standard-key query parameter name on the Live WebSocket URL on the current page (older docs: `?key=`).
8. `https://ai.google.dev/gemini-api/docs/android` returned 404; the nav's "Build Android apps" destination unknown.
9. `countTokens` current request/response shape (not fetched).
10. The old dummy thought-signature skip value — page no longer documents it; do not rely on it.

## 20. Sources (all opened 2026-09-20 unless marked)
- https://ai.google.dev/gemini-api/docs/models
- https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash
- https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite
- https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite
- https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash
- https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash-lite
- https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live
- https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-tts-preview
- https://ai.google.dev/gemini-api/docs/pricing (+ .md.txt)
- https://ai.google.dev/gemini-api/docs/rate-limits (+ .md.txt)
- https://ai.google.dev/gemini-api/docs/quickstart
- https://ai.google.dev/gemini-api/docs/api-key
- https://ai.google.dev/gemini-api/docs/libraries
- https://ai.google.dev/gemini-api/docs/interactions
- https://ai.google.dev/api/interactions-api
- https://ai.google.dev/gemini-api/docs/streaming
- https://ai.google.dev/gemini-api/docs/migrate-to-interactions
- https://ai.google.dev/gemini-api/docs/interactions-breaking-changes-may-2026.md.txt
- https://ai.google.dev/gemini-api/docs/text-generation (+ .md.txt)
- https://ai.google.dev/gemini-api/docs/generate-content/text-generation
- https://ai.google.dev/api/generate-content
- https://ai.google.dev/api/models
- https://ai.google.dev/gemini-api/docs/function-calling (+ .md.txt)
- https://ai.google.dev/gemini-api/docs/generate-content/function-calling (+ .md.txt)
- https://ai.google.dev/gemini-api/docs/thinking
- https://ai.google.dev/gemini-api/docs/generate-content/thinking
- https://ai.google.dev/gemini-api/docs/google-search (+ .md.txt)
- https://ai.google.dev/gemini-api/docs/generate-content/google-search (+ .md.txt)
- https://ai.google.dev/gemini-api/terms
- https://ai.google.dev/gemini-api/docs/url-context
- https://ai.google.dev/gemini-api/docs/image-understanding
- https://ai.google.dev/gemini-api/docs/generate-content/image-understanding
- https://ai.google.dev/gemini-api/docs/audio
- https://ai.google.dev/gemini-api/docs/document-processing
- https://ai.google.dev/gemini-api/docs/safety-settings
- https://ai.google.dev/gemini-api/docs/speech-generation
- https://ai.google.dev/gemini-api/docs/generate-content/speech-generation (+ .md.txt)
- https://ai.google.dev/gemini-api/docs/live , /live-guide (+ .md.txt), https://ai.google.dev/api/live
- https://ai.google.dev/gemini-api/docs/ephemeral-tokens
- https://ai.google.dev/gemini-api/docs/troubleshooting
- https://ai.google.dev/gemini-api/docs/api-errors
- https://ai.google.dev/gemini-api/docs/deprecations
- https://ai.google.dev/gemini-api/docs/changelog
- https://github.com/google-gemini/deprecated-generative-ai-android
- https://firebase.google.com/docs/ai-logic/migrate-from-google-ai-client-sdks
- https://google.aip.dev/193
- Secondary (UNVERIFIED): https://pecollective.com/tools/gemini-free-tier-guide/ , https://www.aifreeapi.com/en/posts/gemini-api-free-tier-rate-limits , https://tinkerllm.com/blog/gemini-api-free-tier-limits-rate-quotas/
