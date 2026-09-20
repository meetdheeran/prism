# Research: groq (2026-09-20)

# Groq API (GroqCloud) for a direct-from-Android BYOK client — findings as of 2026-09-20

All facts below were fetched today from the live pages cited. Where the fetched page did not state something, it is flagged. IMPORTANT headline changes vs. older (2025) knowledge:

- **`groq/compound` and `groq/compound-mini` are shut down on 2026-09-21 (tomorrow)** — do not build on them. Built-in web search now lives on the `openai/gpt-oss-*` models via the `browser_search` tool. (https://console.groq.com/docs/deprecations)
- **`llama-3.3-70b-versatile` and `llama-3.1-8b-instant` were removed from Free/Developer tiers on 2026-08-16** (now "Enterprise / Contact Sales"). Replacements: `openai/gpt-oss-120b` / `openai/gpt-oss-20b`. (https://console.groq.com/docs/deprecations, https://console.groq.com/docs/models)
- **`playai-tts` / `playai-tts-arabic` were shut down 2025-12-31**; TTS is now `canopylabs/orpheus-v1-english` / `canopylabs/orpheus-arabic-saudi` (preview). (https://console.groq.com/docs/deprecations)
- **The only vision-capable self-serve model is `qwen/qwen3.8-27b` (Preview)**. `gpt-oss-*` are text-only. (https://console.groq.com/docs/vision, https://console.groq.com/docs/models)
- **groq.com/pricing no longer renders a price table** (it now shows the marketing homepage); per-model prices are on the model cards in the docs. Built-in-tool prices could NOT be confirmed from a Groq page. (https://groq.com/pricing fetched twice, incl. via a render proxy)

---

## 1. Models, recommendations, context windows, prices, free-tier limits

Source: https://console.groq.com/docs/models (no date stamp on page) plus the per-model cards.

### Production models (self-serve)
| Model ID | Ctx window | Max completion | Speed | Price (per 1M tokens) | Notes |
|---|---|---|---|---|---|
| `openai/gpt-oss-120b` | 131,072 | 65,536 | ~500 tps | $0.15 in / $0.075 cached in / $0.60 out | Tool use, browser search, code execution, JSON object + JSON schema, reasoning (`low/medium/high`). https://console.groq.com/docs/model/openai/gpt-oss-120b |
| `openai/gpt-oss-20b` | 131,072 | 65,536 | ~1000 tps | $0.075 in / $0.037 cached in / $0.30 out | Same capability set as 120B. https://console.groq.com/docs/model/openai/gpt-oss-20b |
| `whisper-large-v3` | – | – | 189x realtime | $0.111 / hour | STT, 100 MB file (dev tier). https://console.groq.com/docs/model/whisper-large-v3 |
| `whisper-large-v3-turbo` | – | – | 216x realtime | $0.04 / hour | STT, 100 MB file (dev tier). https://console.groq.com/docs/model/whisper-large-v3-turbo |
| `llama-3.3-70b-versatile` | 131,072 | 32,768 | ~280 tps | Contact Sales | **Enterprise only** since 2026-08-16 |
| `llama-3.1-8b-instant` | 131,072 | 131,072 | ~560 tps | Contact Sales | **Enterprise only** since 2026-08-16 |

### Preview models (may be discontinued at short notice — docs say "should not be used in production")
| Model ID | Ctx | Max out | Speed | Price | Notes |
|---|---|---|---|---|---|
| `qwen/qwen3.8-27b` | 131,042 | 16,384 | ~450 tps | $0.80 in / $4.00 out per 1M (as shown on the card; third-party trackers show the same) | **Vision** (max 3 images, 2,048 tokens each, 20 MB), tool use (parallel), JSON object + JSON schema (strict), reasoning (`none/default/low/medium/high`, thinking/instruct switching). https://console.groq.com/docs/model/qwen/qwen3.8-27b |
| `openai/gpt-oss-safeguard-20b` | 131,072 | 65,536 | ~1000 tps | $0.075 / $0.30 | Safety/moderation variant; also supports browser search. https://console.groq.com/docs/model/openai/gpt-oss-safeguard-20b |
| `canopylabs/orpheus-v1-english` | 4,000 | 50,000 | – | $22 / 1M characters | TTS |
| `canopylabs/orpheus-arabic-saudi` | 4,000 | 50,000 | – | $40 / 1M characters | TTS |
| `minimaxai/minimax-m2.7` | 196,608 | 131,072 | ~260 tps | Enterprise only | Not usable with a self-serve key |
| `meta-llama/llama-prompt-guard-2-22m` / `-86m` | 512 | 512 | – | – | Prompt-injection classifier |

### Recommendation for the app
- **Fast chat (default):** `openai/gpt-oss-20b` with `reasoning_effort: "low"` (1000 tps, cheapest).
- **Quality reasoning:** `openai/gpt-oss-120b` with `reasoning_effort: "medium"` or `"high"`.
- **Vision / screen & document understanding:** `qwen/qwen3.8-27b` (only option; Preview — wrap it behind a config flag so the model ID can be changed remotely/in settings).
- **Tool use (phone actions):** all three above support function calling. Per https://console.groq.com/docs/tool-use, *parallel* tool calls are listed for `qwen/qwen3.8-27b`, `minimaxai/minimax-m2.7`, `llama-3.3-70b-versatile`, `llama-3.1-8b-instant` — `gpt-oss-*` are not in that list, so assume gpt-oss issues one tool call per turn (flagged: not explicitly stated as unsupported).
- **Web search:** `openai/gpt-oss-20b`/`120b` + `{"type":"browser_search"}` (Section 5).
- **Prompt caching** (automatic prefix caching, 50% off cached input, 2-hour TTL, min 128–1024 tokens, only gpt-oss family; cached tokens don't count toward TPM) — put the static system prompt + memory block first in every request. Cached usage shows as `usage.prompt_tokens_details.cached_tokens`. https://console.groq.com/docs/prompt-caching

### Free-tier rate limits
Source: https://console.groq.com/docs/rate-limits (page has a "Free Plan Limits / Developer Plan Limits" toggle; the table the fetcher rendered is the *default* tab and matches Groq's known free-tier numbers; one fetch attributed it to Developer — **verify in Console > Settings > Limits**).

| Model | RPM | RPD | TPM | TPD | ASH | ASD |
|---|---|---|---|---|---|---|
| `openai/gpt-oss-120b` | 30 | 1,000 | 8,000 | 200,000 | – | – |
| `openai/gpt-oss-20b` | 30 | 1,000 | 8,000 | 200,000 | – | – |
| `openai/gpt-oss-safeguard-20b` | 30 | 1,000 | 8,000 | 200,000 | – | – |
| `qwen/qwen3.8-27b` | 30 | 1,000 | 8,000 | 200,000 | – | – |
| `whisper-large-v3` / `-turbo` | 20 | 2,000 | – | – | 7,200 audio-sec/hour | 28,800 audio-sec/day |
| `canopylabs/orpheus-*` | 10 | 100 | 1,200 | 3,600 | – | – |
| `llama-prompt-guard-2-*` | 30 | 14,400 | 15,000 | 500,000 | – | – |

Implications: 8K TPM means a vision call with 3 images (3 × 2,048 = 6,144 tokens) nearly exhausts a minute; keep screenshots to 1 image per request. Limits are **per organization, not per key**. Headers: `x-ratelimit-limit-requests`, `x-ratelimit-limit-tokens`, `x-ratelimit-remaining-requests`, `x-ratelimit-remaining-tokens`, `x-ratelimit-reset-requests`, `x-ratelimit-reset-tokens`, `retry-after` (429 only). Some accounts have separate ITPM/OTPM. Developer plan unlocks higher limits, Batch (50% off) and Flex processing (paid only, 10x limits, 498 `capacity_exceeded` on overflow). https://console.groq.com/docs/flex-processing, https://console.groq.com/docs/batch

---

## 2. OpenAI-compatible Chat Completions

**Base URL:** `https://api.groq.com/openai/v1` (https://console.groq.com/docs/openai). Endpoint: `POST https://api.groq.com/openai/v1/chat/completions`.

**Headers**
```
Authorization: Bearer gsk_...      # user-supplied key
Content-Type: application/json
```

**Request (text + image + tools)** — https://console.groq.com/docs/api-reference, https://console.groq.com/docs/vision
```json
{
  "model": "qwen/qwen3.8-27b",
  "messages": [
    {"role": "system", "content": "You are Perch's assistant..."},
    {"role": "user", "content": [
      {"type": "text", "text": "What is on this screen?"},
      {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,/9j/4AAQ...", "detail": "auto"}}
    ]}
  ],
  "tools": [{
    "type": "function",
    "function": {
      "name": "set_alarm",
      "description": "Set an alarm on the phone",
      "parameters": {"type": "object", "properties": {"time": {"type": "string"}}, "required": ["time"]}
    }
  }],
  "tool_choice": "auto",
  "parallel_tool_calls": true,
  "temperature": 0.7,
  "max_completion_tokens": 1024,
  "stream": true,
  "stream_options": {"include_usage": true}
}
```
Key parameter facts (https://console.groq.com/docs/api-reference):
- `messages[].content` is a string or an array of parts `{"type":"text"}` / `{"type":"image_url","image_url":{"url","detail":"auto|low|high"}}`. `url` is "Either a URL of the image or the base64 encoded image data" (groq-python `chat_completion_content_part_image_param.py`).
- **Image limits** (https://console.groq.com/docs/vision): max 3 images per request; each image = 2,048 input tokens; "Maximum allowed size for a request containing an image URL as input is 20MB". **A separate base64 size limit is no longer stated on the page** (older docs said 4 MB) — downscale screenshots to ≤ ~1280 px longest side JPEG q≈80 (≈200–400 KB) to be safe and to keep request bodies small. Formats not enumerated; JPEG/PNG are what the examples use. Tool use and JSON mode both work with images on `qwen/qwen3.8-27b`; multi-turn about an image is supported.
- `tools`: up to 128 function tools (API ref); tool page recommends 3–5, max ~10–15 practical. `tool_choice`: `"none" | "auto" | "required" | {"type":"function","function":{"name":"..."}}`. `"required"` returns HTTP 400 if the model does not call a tool. `parallel_tool_calls` default `true`. `disable_tool_validation` (bool, default false) skips validation. Bad tool generations return 400 with a `failed_generation` field. (https://console.groq.com/docs/tool-use/local-tool-calling)
- `max_completion_tokens` (use this; `max_tokens` deprecated). `temperature` 0–2 (0 is mapped to 1e-8). `top_p`, `stop` (≤4), `seed`, `user`, `service_tier` (`auto|on_demand|flex|performance`).
- **Not supported (400 if sent):** `logprobs`, `logit_bias`, `top_logprobs`, `messages[].name`, `n` ≠ 1. `frequency_penalty`/`presence_penalty`/`store`/`metadata` accepted but "not currently supported". (https://console.groq.com/docs/openai)
- Reasoning: `reasoning_effort` (gpt-oss: `low|medium|high`; qwen3.8: `none|default|low|medium|high`), `reasoning_format` (`hidden|raw|parsed`, **cannot be combined with JSON mode or tool use**), `include_reasoning` (bool; gpt-oss puts reasoning in `message.reasoning` by default). (https://console.groq.com/docs/reasoning)

**Tool-call response and round trip**
```json
{"choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
  "tool_calls":[{"id":"call_abc","type":"function","function":{"name":"set_alarm","arguments":"{\"time\":\"07:30\"}"}}]}}]}
```
Then append the assistant message (with `tool_calls`) and one `{"role":"tool","tool_call_id":"call_abc","name":"set_alarm","content":"{\"ok\":true}"}` per call, and re-POST. Loop until `finish_reason: "stop"`.

**Full response object** (https://console.groq.com/docs/api-reference + groq-python `chat_completion_message.py`): `id, object:"chat.completion", created, model, choices[{index, finish_reason: stop|length|tool_calls|function_call, message{role, content, reasoning, tool_calls, executed_tools, annotations, refusal}}], usage{prompt_tokens, completion_tokens, total_tokens, queue_time, prompt_time, completion_time, total_time, prompt_tokens_details.cached_tokens}, usage_breakdown, system_fingerprint, service_tier, x_groq{id, usage, seed}`.

**Streaming (SSE)** — `stream: true` returns `text/event-stream` lines `data: {chunk}` ending with `data: [DONE]`. Chunk (groq-python `chat_completion_chunk.py`): `object:"chat.completion.chunk"`, `choices[].delta{content?, reasoning?, role?, tool_calls?[{index, id?, type?, function{name?, arguments?}}], executed_tools?}`, `choices[].finish_reason`, and on the last chunk `x_groq.usage` / `usage` (with `stream_options.include_usage`). Accumulate `delta.tool_calls` by `index`, concatenating `function.arguments` strings. On Android use OkHttp's `okhttp-sse` `EventSources` or read the response body line by line.

**JSON mode / structured outputs** (https://console.groq.com/docs/structured-outputs):
- `"response_format": {"type": "json_object"}` — all models; put "respond in JSON" in the prompt.
- `"response_format": {"type":"json_schema","json_schema":{"name":"x","strict":true,"schema":{...}}}` — strict constrained decoding on `gpt-oss-20b`, `gpt-oss-120b`, `qwen/qwen3.8-27b` (all properties `required`, `additionalProperties:false`). **Structured outputs do not work with streaming or with tools.**

**Errors** (https://console.groq.com/docs/errors): body `{"error":{"message":"...","type":"invalid_request_error"}}`; 401 bad key, 413 body too large, 422, 429 rate limit (honour `retry-after`), 498 flex capacity, 499 cancelled, 500/502/503. Paid orgs hitting a spend cap get 400 `blocked_api_access` (https://console.groq.com/docs/spend-limits).

**Responses API** (`POST /openai/v1/responses`, https://console.groq.com/docs/responses-api) also exists (input items incl. `{"type":"input_image","image_url":"...","detail":"auto"}`, tools `browser_search`, `code_interpreter`, `mcp`), but is stateless (`previous_response_id`, `store`, etc. unsupported). Chat Completions is sufficient and simpler for this app.

---

## 3. Speech-to-text
Source: https://console.groq.com/docs/speech-to-text, https://console.groq.com/docs/api-reference
- `POST https://api.groq.com/openai/v1/audio/transcriptions` (multipart/form-data) and `POST .../audio/translations` (to English).
- Models: `whisper-large-v3-turbo` ($0.04/hr, WER 12%, 216x) — recommend; `whisper-large-v3` ($0.111/hr, WER 10.3%). `distil-whisper-large-v3-en` was shut down 2025-08-23.
- Fields: `file` (or `url`), `model`, `language` (ISO-639-1, improves accuracy/latency), `prompt` (≤224 tokens, same language as audio), `response_format` `json|text|verbose_json` (**`vtt`/`srt` not supported**), `temperature` 0–1 (default 0), `timestamp_granularities` `["word"]`/`["segment"]` (verbose_json only).
- Accepted: `flac, mp3, mp4, mpeg, mpga, m4a, ogg, wav, webm`. Server downsamples to 16 kHz mono; docs recommend pre-converting: `ffmpeg -i in -ar 16000 -ac 1 -map 0:a -c:a flac out.flac` (FLAC for lossless small size).
- **Max upload: 25 MB on free tier, 100 MB on dev tier**; longer audio → chunk client-side. Minimum billed length 10 s per request. 99+ languages.
- Free-tier: 20 RPM, 2,000 RPD, 7,200 audio-sec/hour, 28,800 audio-sec/day.
- Response: `{"text":"...","x_groq":{"id":"..."}}`; verbose_json adds segments with `avg_logprob`, `compression_ratio`, `no_speech_prob`.
- Android: `AudioRecord` at 16 kHz mono PCM → write a WAV header (simplest, no codec) or `MediaRecorder` with `OutputFormat.MPEG_4`/`AudioEncoder.AAC` → `.m4a`. Needs `android.permission.RECORD_AUDIO` (runtime). Post with OkHttp `MultipartBody` part name `file`.

---

## 4. Text-to-speech
Source: https://console.groq.com/docs/text-to-speech, https://console.groq.com/docs/text-to-speech/orpheus, model cards, https://console.groq.com/docs/deprecations
- `playai-tts` / `playai-tts-arabic`: **deprecated 2025-12-23, shut down 2025-12-31**.
- Current: `canopylabs/orpheus-v1-english` ($22/1M chars) and `canopylabs/orpheus-arabic-saudi` ($40/1M chars), both **Preview**. Endpoint `POST https://api.groq.com/openai/v1/audio/speech`.
- Request: `{"model":"canopylabs/orpheus-v1-english","input":"[cheerful] Good morning!","voice":"austin","response_format":"wav"}` → binary audio body. English voices: `autumn, diana, hannah` (F), `austin, daniel, troy` (M). Arabic: `abdullah, fahad, sultan, lulwa, noura, aisha` (no vocal directions). English supports bracketed directions like `[cheerful]`, `[whisper]`.
- **Input limited to 200 characters per request** — you must sentence-chunk assistant replies and queue playback. Free tier: 10 RPM, 100 RPD, 1.2K TPM. Batch API not supported for Orpheus.
- Format: Orpheus page says WAV (default). The generic API reference lists `response_format` `flac|mp3|mulaw|ogg|wav` and `sample_rate` `8000…48000` (default 48000) — those were written for PlayAI; **only `wav` is confirmed for Orpheus** (flagged). No terms-acceptance step is mentioned for Orpheus (PlayAI used to require one).
- Practical recommendation: use Android's on-device `android.speech.tts.TextToSpeech` for the assistant's voice by default (free, no rate limit, no 200-char cap) and offer Orpheus as an optional "premium voice".

---

## 5. Built-in web search / agentic tooling
Sources: https://console.groq.com/docs/deprecations, https://console.groq.com/docs/tool-use/built-in-tools, https://console.groq.com/docs/tool-use/built-in-tools/browser-search, https://console.groq.com/docs/tool-use/built-in-tools/code-execution, https://console.groq.com/llms.txt, groq-python types.
- **Compound is gone:** "Both systems will be decommissioned on September 21, 2026. Beginning on that date, requests to these model IDs will return errors." No replacement model ID is listed. The `search_settings` (`include_domains`, `exclude_domains`, `country`, `include_images`) and `compound_custom` request params still exist in the API reference/SDK but were Compound features — **not confirmed to have any effect with `browser_search`** (flagged). Visit Website, Wolfram Alpha ("Cannot use Visit Website or Wolfram Alpha" with gpt-oss built-ins) and Browser Automation (retired) are not available to you.
- **What to use:** `browser_search` built-in tool on `openai/gpt-oss-20b`, `openai/gpt-oss-120b` (and `openai/gpt-oss-safeguard-20b`). Also `code_interpreter` (Python, sandboxed by E2B, no network) on gpt-oss-20b/120b.

Request (docs example):
```json
{
  "model": "openai/gpt-oss-20b",
  "messages": [{"role":"user","content":"What changed in Android 17 this week?"}],
  "tools": [{"type": "browser_search"}],
  "tool_choice": "required",
  "reasoning_effort": "low"
}
```
Docs always show `tool_choice:"required"` (forces a search); `"auto"` should let the model decide but is not shown in the docs (flagged). Docs recommend `reasoning_effort: "low"` because browsing sessions can burn many reasoning tokens.

Response: `choices[0].message.content` contains the answer with inline citation markers such as `【2†L6-L10】`; `message.reasoning` holds the browsing trace; `message.executed_tools[]` items have `{index, type, arguments (JSON string), output, search_results: {results: [{title, url, content, score, images}], images}, code_results: [{text, png, chart, charts}], browser_results}` (groq-python `chat_completion_message.py` / `chat_completion_chunk.py`). Streaming deltas carry `delta.executed_tools` with the same shape. `message.annotations[]` (types `document_citation` / `function_citation`) exist for the `documents` + `citation_options` feature; URL citations for browser search come through `executed_tools[].search_results` and the `【】` markers rather than annotations (flagged: no full response sample on the page — build defensively).
- Limits: not compatible with structured outputs; not HIPAA-covered; not on regional endpoints. Whether custom function tools can be sent in the same request as `browser_search` is not documented — plan to run search and phone-action tools in separate calls.
- **Third-party provider:** "Browser search functionality is powered by Exa, a search engine designed for AI applications" — the user's query text (as the model formulates it) is sent to Exa. Code execution: "powered by Foundry Labs (E2B)". Disclose this in the app's privacy note. Groq's own default is no retention of inference data (up to 30 days only for abuse/troubleshooting; ZDR available) — https://console.groq.com/docs/your-data.
- **Cost:** not on any Groq page today (`groq.com/pricing` renders the homepage; docs link to it). Third-party trackers (https://www.usagepricing.com/blueprint/groq, "last confirmed 2026-07-21") list Basic Search $5/1k requests, Advanced Search $8/1k, Visit Website $1/1k, Code Execution $0.18/hour, on top of model tokens — **unverified**. Assume roughly $0.005 per search plus tokens; on the free tier it is rate-limited by the model's 30 RPM / 8K TPM (search snippets count as input tokens).

---

## 6. Groq-side restrictions on API keys in a mobile client
- Security guidance (https://console.groq.com/docs/production-readiness/security-onboarding): "Never embed keys in frontend code or expose them in browser bundles"; route client-side usage "through a trusted backend proxy"; rotate keys; use per-environment keys. This targets *the developer's* key. Your BYOK design (each user pastes their own `gsk_` key on the phone) is consistent with it as long as the APK ships no key.
- Groq Services Agreement, last updated 2026-06-22 (https://console.groq.com/docs/legal/services-agreement): the customer "is responsible for … the security of its passwords for the Account (including any keys for Groq's APIs)" (3.2); may integrate the APIs into a "Customer Application" and make services available to End Users (3.1); "may not resell or lease access to its Account"; must be 18+. No clause forbids end users using their own keys inside a client app. Groq keys are project-scoped (https://console.groq.com/docs/projects) — advise users to create a dedicated project/key for the app.
- Implementation: store the key with `androidx.security:security-crypto` EncryptedSharedPreferences (Keystore-backed), never log it, add `android:allowBackup="false"` or exclude the prefs file from backups, and use the `user` field is unnecessary. Manifest needs only `<uses-permission android:name="android.permission.INTERNET"/>` (plus RECORD_AUDIO for STT).

---

## 7. Deprecation status of everything recommended
| ID | Status today | Notes |
|---|---|---|
| `openai/gpt-oss-120b`, `openai/gpt-oss-20b` | Production, no deprecation listed | Named as the replacement for llama-3.x |
| `whisper-large-v3-turbo`, `whisper-large-v3` | Production, none listed | |
| `qwen/qwen3.8-27b` | **Preview** — "may be discontinued at short notice"; predecessor `qwen3.6-27b` was shut down 2026-09-14 in favour of it | Keep model ID configurable |
| `canopylabs/orpheus-*` | Preview | |
| `groq/compound*` | shutdown 2026-09-21 | do not use |
| `llama-3.3-70b-versatile`, `llama-3.1-8b-instant` | free/dev shutdown 2026-08-16 | Enterprise only |
Deprecation process: email + docs announcement → transition → optional auto-upgrade → EOL. Applies to free and developer tiers only.

---

## Client library / build notes
- Groq publishes official SDKs only for Python and JS; community ones for C#, Dart, PHP, Ruby — **no Kotlin/Java SDK** (https://console.groq.com/docs/libraries).
- Recommended: plain OkHttp + `okhttp-sse` + `kotlinx-serialization-json` (or Moshi) — no new AGP/Gradle/Kotlin requirements beyond your Kotlin 2.0.20 / AGP 8.5.2 / Gradle 8.7 stack. Model Groq-specific fields (`reasoning`, `executed_tools`, `x_groq`) yourself.
- Alternatives that can point at `https://api.groq.com/openai/v1`: OpenAI's official Java SDK `com.openai:openai-java:4.65.0` (Java 8+, ships R8/ProGuard keep rules, `OpenAIOkHttpClient.builder().baseUrl(...)`, streaming accumulators) — https://github.com/openai/openai-java; or `com.aallam.openai:openai-client:4.1.0` + `io.ktor:ktor-client-okhttp:3.x` (Kotlin 2.0, released 2026-02-07 per Maven metadata) — https://github.com/aallam/openai-kotlin. Both will drop unknown Groq fields (`executed_tools`, `reasoning`), so plain OkHttp is preferable for search/reasoning features.


## Key facts
- [confirmed] groq/compound and groq/compound-mini are decommissioned on 2026-09-21; requests will return errors and no replacement model ID is listed — https://console.groq.com/docs/deprecations
- [confirmed] llama-3.3-70b-versatile and llama-3.1-8b-instant were shut down for free/developer tiers on 2026-08-16 (Enterprise/Contact Sales only); recommended replacements openai/gpt-oss-120b and openai/gpt-oss-20b — https://console.groq.com/docs/deprecations
- [confirmed] openai/gpt-oss-120b: 131,072 context, 65,536 max output, ~500 tps, $0.15 input / $0.075 cached / $0.60 output per 1M tokens; supports tool use, browser search, code execution, JSON object/schema, reasoning — https://console.groq.com/docs/model/openai/gpt-oss-120b
- [confirmed] openai/gpt-oss-20b: 131,072 context, 65,536 max output, ~1000 tps, $0.075 input / $0.037 cached / $0.30 output per 1M tokens — https://console.groq.com/docs/model/openai/gpt-oss-20b
- [confirmed] qwen/qwen3.8-27b (Preview) is the only self-serve vision model: max 3 images/request, 2,048 tokens per image, 20 MB request limit for URL images, 131,042 context, 16,384 max output, ~450 tps, $0.80 in / $4.00 out per 1M — https://console.groq.com/docs/model/qwen/qwen3.8-27b
- [confirmed] Vision docs: 'Maximum allowed size for a request containing an image URL as input is 20MB', max 3 images, each image counts as 2048 input tokens; base64 data URL form 'data:image/jpeg;base64,...'; tool use and JSON mode work with images; no separate base64 size limit is stated — https://console.groq.com/docs/vision
- [likely] Free-plan rate limits: gpt-oss-120b/20b/safeguard and qwen3.8-27b = 30 RPM, 1K RPD, 8K TPM, 200K TPD; whisper = 20 RPM, 2K RPD, 7.2K audio-sec/hour, 28.8K audio-sec/day; orpheus = 10 RPM, 100 RPD, 1.2K TPM, 3.6K TPD (page has Free/Developer toggle; rendered table is the default tab) — https://console.groq.com/docs/rate-limits
- [confirmed] Rate limits are enforced per organization (not per key); headers x-ratelimit-limit-requests/tokens, x-ratelimit-remaining-requests/tokens, x-ratelimit-reset-requests/tokens, retry-after on 429; cached tokens do not count toward rate limits — https://console.groq.com/docs/rate-limits
- [confirmed] Chat completions endpoint POST https://api.groq.com/openai/v1/chat/completions; params include messages, model, tools (up to 128), tool_choice, parallel_tool_calls (default true), response_format (json_object / json_schema), stream, stream_options, reasoning_effort (none|default|minimal|low|medium|high|xhigh|max), reasoning_format (hidden|raw|parsed), include_reasoning, max_completion_tokens, service_tier, search_settings, compound_custom, citation_options, documents — https://console.groq.com/docs/api-reference
- [confirmed] Unsupported OpenAI params (400): logprobs, logit_bias, top_logprobs, messages[].name, n != 1; temperature 0 is converted to 1e-8; audio response formats vtt/srt not supported — https://console.groq.com/docs/openai
- [confirmed] tool_choice accepts none | auto | required | {type:function,function:{name}}; 'required' returns 400 if no tool is called; failed tool generations return 400 with failed_generation; streaming tool calls arrive in delta.tool_calls to be accumulated by index; tool results are sent as role 'tool' with tool_call_id — https://console.groq.com/docs/tool-use/local-tool-calling
- [confirmed] All Groq-hosted models support tool use; parallel tool use is listed for qwen/qwen3.8-27b, minimaxai/minimax-m2.7, llama-3.3-70b-versatile, llama-3.1-8b-instant; built-in tools only on openai/gpt-oss-20b and openai/gpt-oss-120b — https://console.groq.com/docs/tool-use
- [confirmed] Structured outputs (json_schema strict:true) supported on GPT-OSS 20B, GPT-OSS 120B, Qwen 3.8 27B; 'Streaming and tool use are not currently supported with Structured Outputs'; JSON object mode supported on all models — https://console.groq.com/docs/structured-outputs
- [confirmed] reasoning_effort values: gpt-oss = low|medium|high; qwen3.8-27b = none|default|low|medium|high; reasoning_format (hidden|raw|parsed) cannot be used with JSON mode or tool use; gpt-oss returns reasoning in message.reasoning controlled by include_reasoning — https://console.groq.com/docs/reasoning
- [confirmed] Streaming chunk delta fields: content, reasoning, role, tool_calls[{index,id,type,function{name,arguments}}], executed_tools; finish_reason stop|length|tool_calls|function_call; final chunk carries usage and x_groq{id,usage} — https://raw.githubusercontent.com/groq/groq-python/main/src/groq/types/chat/chat_completion_chunk.py
- [confirmed] Response message fields: role, content, reasoning, tool_calls, executed_tools[{index,type,arguments,output,search_results{results[{title,url,content,score,images}],images},code_results[{text,png,chart,charts}],browser_results}], annotations[{type: document_citation|function_citation}], refusal — https://raw.githubusercontent.com/groq/groq-python/main/src/groq/types/chat/chat_completion_message.py
- [confirmed] Browser search: tools [{type:'browser_search'}] with tool_choice 'required' on openai/gpt-oss-20b, gpt-oss-120b, gpt-oss-safeguard-20b; 'powered by Exa'; not compatible with structured outputs; recommend reasoning_effort low; pricing deferred to the pricing page — https://console.groq.com/docs/tool-use/built-in-tools/browser-search
- [confirmed] Code execution: tools [{type:'code_interpreter'}] on gpt-oss-20b/120b, Python only, sandboxed by Foundry Labs (E2B), no external network; executed_tools item example {name:'python',index:0,type:'function',arguments:'...',search_results:{results:null},code_results:[{text:'111.108...'}]} — https://console.groq.com/docs/tool-use/built-in-tools/code-execution
- [confirmed] GPT-OSS built-in tools cannot use Visit Website or Wolfram Alpha; built-in tools are not HIPAA covered and not available on regional/sovereign endpoints — https://console.groq.com/docs/tool-use/built-in-tools
- [unverified] Built-in tool pricing (Basic Search $5/1k, Advanced Search $8/1k, Visit Website $1/1k, Code Execution $0.18/hour) is only available from a third-party tracker 'last confirmed 2026-07-21'; groq.com/pricing now redirects to the homepage — https://www.usagepricing.com/blueprint/groq
- [confirmed] Speech-to-text: POST /openai/v1/audio/transcriptions and /audio/translations; whisper-large-v3-turbo $0.04/hr (WER 12%), whisper-large-v3 $0.111/hr (WER 10.3%); formats flac, mp3, mp4, mpeg, mpga, m4a, ogg, wav, webm; max 25 MB free tier / 100 MB dev tier; audio downsampled to 16 kHz mono; minimum billed 10 seconds; prompt ≤224 tokens; response_format json|text|verbose_json — https://console.groq.com/docs/speech-to-text
- [confirmed] playai-tts and playai-tts-arabic were deprecated 2025-12-23 and shut down 2025-12-31; replacements canopylabs/orpheus-v1-english and canopylabs/orpheus-arabic-saudi — https://console.groq.com/docs/deprecations
- [confirmed] Orpheus TTS: endpoint POST /openai/v1/audio/speech; English voices autumn, diana, hannah, austin, daniel, troy; Arabic voices abdullah, fahad, sultan, lulwa, noura, aisha; input limited to 200 characters; WAV output; $22/1M chars English, $40/1M chars Arabic; vocal direction tags like [cheerful] English only; batch API not supported — https://console.groq.com/docs/text-to-speech/orpheus
- [confirmed] Prompt caching: automatic prefix caching on GPT-OSS 20B/120B/Safeguard, 50% discount on cached input, minimum 128–1024 tokens, 2-hour expiry, reported in usage.prompt_tokens_details.cached_tokens — https://console.groq.com/docs/prompt-caching
- [confirmed] Groq security guidance: 'Never embed keys in frontend code or expose them in browser bundles'; client-side usage should go through a trusted backend proxy; rotate keys; per-environment keys — https://console.groq.com/docs/production-readiness/security-onboarding
- [confirmed] Groq Services Agreement (last updated 2026-06-22): customer responsible for security of API keys; may integrate APIs into a Customer Application and make services available to End Users; may not resell or lease access to its Account; users must be 18+; no clause prohibits end users supplying their own keys in a client app — https://console.groq.com/docs/legal/services-agreement
- [confirmed] API keys are project-scoped; per-project request limits cannot exceed org limits; token limits enforced at org level — https://console.groq.com/docs/projects
- [confirmed] By default Groq does not retain customer data for inference requests; may log up to 30 days for troubleshooting/abuse; Zero Data Retention setting available — https://console.groq.com/docs/your-data
- [confirmed] Error body shape {error:{message,type}}; codes 401, 413, 422, 429, 498 (flex capacity), 499, 500, 502, 503 — https://console.groq.com/docs/errors
- [confirmed] Flex processing is paid-tier only, same price as on-demand, ~10x rate limits, fails fast with 498 capacity_exceeded — https://console.groq.com/docs/flex-processing
- [confirmed] Responses API at POST /openai/v1/responses supports browser_search, code_interpreter, mcp tools and input_image items but is stateless (previous_response_id, store, truncation, include unsupported) — https://console.groq.com/docs/responses-api
- [confirmed] Groq official SDKs exist only for Python and JavaScript; no Kotlin/Java SDK; community libraries are unverified by Groq — https://console.groq.com/docs/libraries
- [confirmed] openai-java 4.65.0 (com.openai:openai-java) requires Java 8+, ships R8/ProGuard keep rules, supports custom baseUrl and streaming accumulators — https://github.com/openai/openai-java
- [confirmed] openai-kotlin latest is 4.1.0 (com.aallam.openai:openai-client:4.1.0, Maven Central 2026-02-07), Kotlin 2.0 / Ktor 3.0, Android engine io.ktor:ktor-client-okhttp — https://repo1.maven.org/maven2/com/aallam/openai/openai-client/maven-metadata.xml

## Open questions
- Rate-limits page has a Free/Developer toggle; the fetched table (30 RPM / 8K TPM for gpt-oss) matches Groq's known free-tier values but one fetch labelled it Developer — confirm in Console > Settings > Limits after creating a key.
- Built-in tool (browser_search / code_interpreter) pricing is not published on any Groq page today; the $5–$8 per 1k searches / $0.18 per hour numbers come from a third-party tracker (last confirmed 2026-07-21) and are unverified.
- Whether custom function tools can be included in the same request as {type:'browser_search'} is not documented; whether tool_choice:'auto' (instead of the documented 'required') triggers browser search when the model deems it useful is not documented.
- Whether search_settings (include_domains/exclude_domains/country/include_images) has any effect on browser_search after Compound's shutdown — the parameter remains in the API reference but was a Compound feature.
- No explicit base64 image size limit appears on the current vision page (older docs said 4 MB); only the 20 MB request limit for URL images is stated. Test empirically with ~2–4 MB payloads.
- Orpheus response_format: the Orpheus page only mentions WAV, while the generic /audio/speech reference lists flac/mp3/mulaw/ogg/wav plus sample_rate (PlayAI-era) — test whether mp3/ogg are accepted for canopylabs/orpheus-v1-english.
- Whether gpt-oss models emit parallel tool calls (they are absent from the parallel-tool-use model list on the tool-use page, but not explicitly excluded).
- Whether the streaming SSE final chunk includes usage without stream_options.include_usage (Groq historically sends x_groq.usage regardless) — verify at runtime.
- Whether the free tier still requires no credit card and whether the free tier can create multiple Projects (projects docs do not mention tier restrictions).
- Exact retention/handling of query text by Exa (third-party) for browser_search is not described in Groq's Your Data page or service terms.

## Recommendations
- Do not implement groq/compound or groq/compound-mini (shut down 2026-09-21) or llama-3.x models (Enterprise-only since 2026-08-16). Default chat model: openai/gpt-oss-20b with reasoning_effort 'low'; 'quality' toggle: openai/gpt-oss-120b with reasoning_effort 'medium'/'high'.
- Vision (screen/image/document understanding): qwen/qwen3.8-27b only. It is Preview, so keep every model ID in a remote/user-editable config and surface Groq 4xx 'model not found/deprecated' errors with a one-tap model switch. Send one downscaled JPEG (≤1280 px, ~300 KB) per request as a data:image/jpeg;base64 URL; budget 2,048 tokens per image against the 8K TPM free-tier limit.
- Web search: send tools [{type:'browser_search'}], tool_choice 'required', reasoning_effort 'low' on gpt-oss-20b; render citations from message.executed_tools[].search_results.results (title/url/content/score) and strip the 【n†Lx-Ly】 markers from content. Run it as a separate call from phone-action function tools, and never combine with response_format json_schema.
- Phone actions: OpenAI-style function tools; parse tool_calls[].function.arguments as JSON string; reply with role 'tool' + tool_call_id; loop until finish_reason 'stop'. Prefer qwen/qwen3.8-27b when you need multiple tool calls in one turn, gpt-oss otherwise. Use response_format {type:'json_object'} (not json_schema) whenever streaming or tools are involved.
- Streaming: stream:true with stream_options {include_usage:true}; consume SSE via OkHttp okhttp-sse; accumulate delta.content, delta.reasoning (show as collapsible 'thinking'), delta.tool_calls by index, and delta.executed_tools.
- STT: record 16 kHz mono PCM via AudioRecord and wrap as WAV (or MediaRecorder AAC .m4a), POST multipart to /openai/v1/audio/transcriptions with model whisper-large-v3-turbo, language hint, response_format 'json'; keep clips under 25 MB (free tier) and note the 10-second minimum billing.
- TTS: use Android's on-device TextToSpeech as the default assistant voice; optionally offer Orpheus (canopylabs/orpheus-v1-english, voice e.g. 'austin', response_format 'wav'), chunking text to ≤200 characters per request and respecting 10 RPM / 100 RPD on free tier.
- Key handling: ship no key in the APK; store the user's gsk_ key in EncryptedSharedPreferences (Keystore-backed), exclude it from backups, mask it in UI/logs, and tell users to create a dedicated Project key at console.groq.com/keys. Show a first-run notice that web search queries go to Groq and Exa, and code execution to E2B.
- Rate-limit handling: read x-ratelimit-remaining-* headers, back off on 429 using retry-after, and show remaining TPM in the settings screen; place static system prompt + memory at the start of messages to benefit from automatic prompt caching (gpt-oss only).
- Networking stack: plain OkHttp + okhttp-sse + kotlinx-serialization (no new Gradle/AGP/Kotlin requirements); avoid third-party OpenAI SDKs which drop Groq-specific fields (reasoning, executed_tools, x_groq). If an SDK is still desired, com.openai:openai-java:4.65.0 with baseUrl https://api.groq.com/openai/v1 is the most maintained option.
