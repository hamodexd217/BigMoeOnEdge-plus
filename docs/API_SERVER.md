# Local API server (Anthropic Messages / OpenAI chat) — work log

Goal: let other apps on the device (e.g. Mobile Harness running Claude Code) use the model that is loaded in this app.
Delivered in three steps so each can be built and tested on its own:

| step | content | user-visible? |
|---|---|---|
| **A** | engine patch 0004, JNI `nativeGenerateChat`, `EngineBackend.generateChat`, `EngineController` queue + in-app priority | no |
| **B** | the HTTP server (`/v1/messages`, `/v1/chat/completions`, `/v1/models`, ...), `ApiServerService`, server settings in the DataStore | via an `adb` start hook (no screen yet) |
| **C** | Settings screen: on/off, port, key, endpoint URL + copy button, force-server-settings, context, thinking, max tokens | yes |

## Step A — what changed

**Why the stock llama-server is not embedded.** `server_context` loads its own model and runs `llama_decode` itself; that
would bypass `bmoe::Session` (expert streaming, cache, hooks). The server is written in Kotlin on top of the same
`Session`, so every backend path (MoE streaming, dense mmap, optional NPU prefill) is the one the chat already uses.

### Engine (`patches/0004-session-tools-sampling.patch`)
`GenerateRequest` gets an optional *stateless chat* mode. It is off unless `chat_messages_json` is non-empty, and then
`generate()` is byte-for-byte the old path (the existing tests/CLI/app turn never set it).

* **Messages + tools through the model's own jinja template.** `chat_messages_json` is an OpenAI-format array
  (system/user/assistant/tool, assistant `tool_calls`, tool `tool_call_id`), `chat_tools_json` an OpenAI `tools` array.
  Parsed with llama.cpp's `common_chat_msgs_parse_oaicompat` / `common_chat_tools_parse_oaicompat` and rendered with
  `common_chat_templates_apply` (`use_jinja`, tools, tool_choice, `parallel_tool_calls`).
  Tool calls in the output are parsed by llama.cpp's chat parser and returned in `RunResult::tool_calls`.
  **They are parsed, not grammar-constrained** (the engine has no grammar sampler), so a weak model can still emit a bad call.
* **KV prefix reuse (the priority item).** The KV is *not* cleared. The rendered prompt is tokenised and its longest common
  token prefix with what the KV holds is kept; only the rest is prefilled (this is the existing continued-chat mechanism,
  now reachable without the engine-side chat history). No match, or a KV that cannot drop a tail (some SWA / recurrent
  memories refuse `seq_rm`), simply means a full re-prefill. `RunResult::n_prompt_total` / `n_reused` report what happened.
  A turn cancelled *after* its prefill keeps the prompt in the KV (retry after a client abort or an in-app preemption does
  not pay for it twice); the old behaviour for the app's own turns is unchanged.
* **Stop strings** on the answer text only (never the reasoning). Possible partial matches are held back from the stream.
* **Per-request sampling** (`sampling_override`): temperature/top_p/top_k/seed, built as a request-scoped sampler chain
  (temp <= 0 = greedy). Cheap, so it is supported. Not supported by the engine at all: min_p, repetition/frequency
  penalties, grammar.
* **`max_tokens` is clamped** to the room left in the context instead of failing (Claude Code asks for 32k+).
* `stop_reason`: `end_turn`, `max_tokens`, `stop_sequence` or `tool_use`.
* With tools in the request the "close the reasoning span in the prompt" trick for think=false is not used (a prefilled turn
  bypasses the parser that finds the tool calls). A model whose template ignores `enable_thinking` therefore still reasons
  when tools are present; its reasoning is split out of the answer either way.

### JNI / Kotlin
* `native-bridge.cpp`: new `nativeGenerateChat` (JSON in; deltas, tool calls (flat id/name/arguments triples), stop reason,
  reuse counters out). `nativeGenerate` is untouched.
* `EngineNativeBridge.kt`: `nativeGenerateChat` + `NativeChatCallback`.
* `EngineBackend.kt`: `generateChat(...)` with a default "unsupported" body, so existing implementations and fakes compile unchanged.
* `ChatApiTypes.kt` (new): `ChatGenRequest`, `SamplingOverride`, `ChatEvent`, `ChatStats`, `ChatToolCall`.
* `EngineController.kt`:
  * `acquireForApi(timeoutMs, maxQueued)` — FIFO queue, one API request at a time, bounded wait; `null` means "answer 529".
  * `generateChat(lease, request)` — the streaming flow; afterwards the in-app chat's engine state key is invalidated, so the
    next in-app turn replays its saved history (same as after Choose-from-options).
  * **In-app chat has priority.** If the app starts a generation while an API generation runs, the API generation is cancelled
    (`ChatEvent.Finished.preempted = true`) and the app proceeds once it has unwound. Queued API requests wait until the app is
    idle or their wait runs out (then 529). Everything else the app did before (refusing a second app generation or a
    decide() while busy) is unchanged.

### RAM cost of a big context (read before enabling the server, step C exposes the setting)
The KV cache is allocated when the model is loaded and grows linearly with the context. Rough size (f16 K and V):
`2 x layers x context x kv_heads x head_dim x 2 bytes`. For a model with 48 layers, 4 KV heads and head_dim 128 (a
Qwen3-30B-A3B-class shape, arithmetic only, not measured here) that is about 3 GiB at 32k tokens and 6 GiB at 64k.
It competes with the expert cache and the OS page cache on the phone. Changing the context size needs a model reload.

## Status of step A — what was and was not checked

| check | result |
|---|---|
| `g++ -fsyntax-only` on `core/src/engine/session.cpp` against the vendored llama.cpp headers, `BMOE_HAVE_MTMD` on and off | passed |
| `g++ -fsyntax-only` on `native-bridge.cpp` against a minimal `jni.h` / `android/log.h` stand-in, mtmd on and off | passed (the stand-in is hand-written, so this checks the bridge's own syntax, not JNI signature correctness) |
| `scan_stops` (stop-string logic, extracted verbatim from `session.cpp`) compiled and **run** on the host | 12 checks passed |
| `git apply --check patches/0004-...` on the pristine uploaded tree | passed |
| `scripts/kt_lint.py` | same 19 findings as the uploaded tree had before any change; none new |
| Kotlin compile | **not done** (no kotlinc / Android SDK / Gradle here) |
| JVM unit tests (`EngineControllerApiTest`, new) | written, **never run** |
| Native build (CMake/NDK), APK, on-device behaviour, prefix-reuse hit rates, tool-call parsing on a real model | **untested** |

Things I could not verify and would check first on a device: that `common_chat_msgs_parse_oaicompat` accepts the exact
JSON step B produces for your model's template, that prefix reuse actually hits for Claude Code's request shape (step B
normalises the per-request billing header to keep the prefix stable), and the cancel/preempt timing during a long prefill.


---

# Step B — the server

## Files
| file | what |
|---|---|
| `server/Http.kt` | `MiniHttpServer`: a small HTTP/1.1 server on `java.net.ServerSocket` (keep-alive, Content-Length and chunked request bodies, `Expect: 100-continue`, chunked/SSE responses). No new dependency |
| `server/ApiServer.kt` | routing, auth, queue/lease handling, non-streaming and streaming chat, error mapping, `X-BigMoe-Ignored` header |
| `server/AnthropicApi.kt` | Anthropic request -> OpenAI-format engine request (system blocks, tool_use/tool_result, thinking, images -> placeholder), response builder, `AnthropicStreamEncoder` (SSE event sequence) |
| `server/OpenAiApi.kt` | the same for `/v1/chat/completions`, `OpenAiStreamEncoder` |
| `server/ApiRuntime.kt` | per-request settings snapshot and `RequestResolver` ("Force server settings", output cap, thinking default) |
| `server/ApiKey.kt` | key generation, constant-time compare, `x-api-key` / `Authorization: Bearer` |
| `server/ApiServerService.kt` | foreground service (type specialUse, own notification with a Stop action, wake lock only while a request runs, follows the settings) |
| `server/ApiServerController.kt` | on/off + key helpers; `ApiServerIntentHook` (adb test hook) |
| `EngineSettingsDataStore.kt` | server settings, `effectiveContextLength` (see below) |
| `AppContainer.kt`, `OnEdgeApplication.kt`, `MainActivity.kt`, `AndroidManifest.xml` | wiring: controller, autostart when left enabled, the adb hook, the service entry |

## Behaviour
* **Binds 127.0.0.1 only** by default; port from settings (default 8080). `serverBindAll` (0.0.0.0) exists in the data layer, and then the key is always required.
* **Auth**: key generated on first start and required by default. `Authorization: Bearer <key>` and `x-api-key: <key>` both work. Auth can be turned off only while bound to loopback (`serverRequireKey=false`); `GET /health` never needs a key.
* **Model**: whatever is loaded in the app. The `model` field of a request is accepted and echoed, any name works; `/v1/models` returns the loaded model. No model loaded: HTTP 503.
* **Settings come from the app.** The server has no copies of the app's own settings: context size, Max tokens, Thinking, temperature / top_p / top_k and the MTP / guess-ahead options are the ones from Settings. The only server-specific settings are on/off, port, key, authentication, listen-on-network, "Force server settings" and the queue wait.
* **MTP / n-gram guess-ahead apply to the server.** The server runs on the same engine session the chat uses, and speculation is decided per session (`spec_on` in `Session::generate`, independent of the request kind), so a model loaded with MTP or n-gram speculation drafts for API requests too. The app already loads such a model with temperature 0 (greedy), so API sampling is greedy as well and client temperature / top_p / top_k are ignored (reported in `X-BigMoe-Ignored`). Not yet measured on a device.
* **Queue / priority**: one request at a time, FIFO, up to `serverQueueTimeoutSec` (default 60 s) of waiting, then HTTP 529 `overloaded_error`. If the in-app chat starts, a *running* API request is cancelled (non-streaming: 529; streaming: an `error` event with `overloaded_error`) and queued ones keep waiting or time out with 529.
* **Streaming**: `stream:true` gives Anthropic SSE (`message_start`, `content_block_*`, `message_delta`, `message_stop`). `event: ping` is sent whenever nothing was written for 3 s, so a long prefill does not hit the client's read timeout. (A non-streaming request cannot be kept alive this way.)
* **Thinking**: with thinking on, reasoning arrives as `thinking` content blocks (`thinking_delta`, empty `signature`), never in the text block; with it off, reasoning is dropped. `thinking.budget_tokens` is not enforced.
* **tool_use**: a tool call is delivered once the engine has parsed it complete: one `content_block_start`, one `input_json_delta` carrying the whole input, one stop. Not token-by-token.
* **Honoured request fields**: `max_tokens` (capped by the app's Max tokens, 0 = no cap; the app default is unlimited (0 = no cap beyond the available context)), `stop_sequences`, `stream`, `system`, `messages`, `tools`, `tool_choice` (`any`/`tool` -> "some tool", a specific tool cannot be forced), `thinking`, and unless forced `temperature` / `top_p` / `top_k` (OpenAI: `seed` too).
* **Force server settings ON**: request sampling, request `thinking` and request `max_tokens` are ignored; the app's own sampler settings, its Thinking switch and its Max tokens apply. `stop_sequences` and `stream` are always honoured.
* **Ignored, and said so**: the response carries `X-BigMoe-Ignored: ...` listing what was not applied (forced values, `budget_tokens`, images, `min_p`, penalties, `n`>1, `response_format`, ...). Sampling params are also ignored while "guess ahead" (speculation) is on, because that mode only verifies exactly under greedy decoding.
* **Images** in requests are replaced by a text placeholder (the stateless path has no vision).
* **Usage**: reused KV prefix is reported as `cache_read_input_tokens`, so `input_tokens + cache_read_input_tokens` is the whole prompt.
* **count_tokens** is an estimate (about 3 bytes per token), marked with `X-BigMoe-Estimate: true`.
* **Context**: the app's "Context size" (default 4096). Claude Code's first request is far bigger, so set it to 32768 or more and reload the model; the settings section warns while the loaded context is smaller. See the RAM note in step A. A prompt that does not fit is answered with HTTP 400 telling you to raise the context.
* The per-request Claude Code billing stamp (`cch=`) is blanked so the system prompt stays identical across requests and the KV prefix is reused.

## Starting it without the settings screen (this step has none)
```
adb shell am start -n com.bigmoe.onedge/.MainActivity --es api_server start --ei api_port 8080 --es api_key test-key
adb shell am start -n com.bigmoe.onedge/.MainActivity --es api_server stop
adb logcat -s BmoeApi          # one line per request: stop reason, prompt / reused / generated tokens, tok/s
adb forward tcp:8080 tcp:8080  # to curl from the computer
```
(Replace the package name if you changed `applicationId`.) Load a model in the app first. `--ez api_no_auth true` turns authentication off (loopback only). The extras write the same persisted settings the screen will.

## curl
```
URL=http://127.0.0.1:8080 ; KEY=test-key
curl -s $URL/health
curl -s $URL/v1/models -H "x-api-key: $KEY"
curl -s $URL/v1/messages/count_tokens -H "x-api-key: $KEY" -H "content-type: application/json" \
  -d '{"messages":[{"role":"user","content":"hello"}]}'

# Anthropic, plain
curl -s $URL/v1/messages -H "x-api-key: $KEY" -H "anthropic-version: 2023-06-01" -H "content-type: application/json" \
  -d '{"model":"claude-sonnet-4","max_tokens":64,"messages":[{"role":"user","content":"Say hi in five words."}]}'

# Anthropic, streaming (-N: no buffering)
curl -sN $URL/v1/messages -H "x-api-key: $KEY" -H "content-type: application/json" \
  -d '{"model":"x","max_tokens":128,"stream":true,"messages":[{"role":"user","content":"Count to five."}]}'

# thinking blocks
curl -s $URL/v1/messages -H "x-api-key: $KEY" -H "content-type: application/json" \
  -d '{"model":"x","max_tokens":512,"thinking":{"type":"enabled","budget_tokens":1024},"messages":[{"role":"user","content":"What is 17*23?"}]}'

# tool_use
curl -s $URL/v1/messages -H "x-api-key: $KEY" -H "content-type: application/json" -d '{
  "model":"x","max_tokens":256,
  "tools":[{"name":"get_weather","description":"Weather for a city","input_schema":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}],
  "messages":[{"role":"user","content":"What is the weather in Basra?"}]}'

# send the tool result back
curl -s $URL/v1/messages -H "x-api-key: $KEY" -H "content-type: application/json" -d '{
  "model":"x","max_tokens":256,
  "tools":[{"name":"get_weather","description":"Weather for a city","input_schema":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}],
  "messages":[{"role":"user","content":"What is the weather in Basra?"},
   {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Basra"}}]},
   {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"41C, sunny"}]}]}'

# stop sequence
curl -s $URL/v1/messages -H "x-api-key: $KEY" -H "content-type: application/json" \
  -d '{"model":"x","max_tokens":64,"stop_sequences":["three"],"messages":[{"role":"user","content":"Count: one, two, three, four"}]}'

# OpenAI-compatible, Bearer auth, streaming
curl -sN $URL/v1/chat/completions -H "Authorization: Bearer $KEY" -H "content-type: application/json" \
  -d '{"model":"x","stream":true,"messages":[{"role":"user","content":"Hello"}]}'

# auth failure (expect 401) and what was ignored (see the X-BigMoe-Ignored header)
curl -si $URL/v1/models | head -1
curl -si $URL/v1/messages -H "x-api-key: $KEY" -H "content-type: application/json" \
  -d '{"model":"x","max_tokens":8,"temperature":0.2,"messages":[{"role":"user","content":"hi"}]}' | grep -i x-bigmoe
```
**Checking prefix reuse:** send the same long request twice (or the same conversation plus one more turn) and watch `adb logcat -s BmoeApi`: the second line should show `reused=` close to `prompt=`.

## Claude Code / Mobile Harness
Point it at the server with Claude Code's usual environment variables (from memory of its documentation, verify against your version):
`ANTHROPIC_BASE_URL=http://127.0.0.1:8080`, `ANTHROPIC_AUTH_TOKEN=<key>` (or `ANTHROPIC_API_KEY`), and the model variables set to any name. Claude Code also sends background requests to a small "fast" model; with one engine they all queue, so set that model variable to the same name and expect them to wait their turn. A 4 tok/s model with Claude Code's prompt size means the first request pays a long prefill and later ones should reuse the prefix.

## Status of step B — what was and was not checked
| check | result |
|---|---|
| Native code | unchanged since step A (same syntax checks) |
| `scripts/kt_lint.py` | no findings in any new or changed file. Ten extra findings appear in *untouched* files (e.g. `GenerationForegroundService.kt`) only because the linter is heuristic: those files use wildcard imports and my service imports the same classes explicitly |
| Kotlin compile | **not done** (no kotlinc / Gradle / SDK here). New Kotlin is untested by a compiler: expect to fix small compile errors on the first build |
| JVM unit tests: `AnthropicApiTest`, `AnthropicStreamEncoderTest`, `OpenAiApiTest`, `RequestResolverTest`, `HttpParserTest`, `ApiServerTest` (real loopback sockets + fake engine), `EngineControllerApiTest` | written, **never run** |
| Android service start, foreground notification, wake lock, adb hook, port binding on a device | **untested** |
| Claude Code / Mobile Harness against it, real tool-call parsing, thinking output on a real model, prefix-reuse hit rate, long-prefill ping behaviour | **untested** |


---

# Step C — the settings screen

Settings > **Local API server**: the **last** section, collapsed until you tap its header (small arrow, down/up). The header shows "on" while the server runs. It only holds the server-specific controls; context size, Max tokens, Thinking, sampling and MTP are the app's own settings. Files: `ui/settings/ApiServerSettings.kt` (new), `SettingsScreen.kt` (one call, and five helper composables `private` -> `internal` so the new file can reuse them), `SettingsViewModel.kt` (+ the controller and server setters), `di/ViewModelFactory.kt` (passes the controller).

| control | does |
|---|---|
| Server switch | starts/stops `ApiServerService`; generates the key the first time |
| Port | 1024-65535, debounced, restarts the server |
| Endpoint + **Copy** | shows `http://127.0.0.1:<port>` and copies it; also lists the three endpoint URLs |
| API key | masked, Show / Copy key / New key |
| Require API key | on by default; turning it off shows a red warning; locked on while "listen on the network" is on |
| Listen on the network | off by default (127.0.0.1 only) |
| **Force server settings** | request sampling, thinking and max_tokens are ignored in favour of the app's sampler / the switches below |
| Queue wait | 5-300 s before HTTP 529 |

## Status of step C
| check | result |
|---|---|
| `scripts/kt_lint.py` | no findings in the new/changed UI files (the 10 heuristic false positives in untouched wildcard-import files remain, see step B) |
| Compose compile, layout, clipboard copy, on-device behaviour | **not done / untested** (no Kotlin compiler or device here) |
| Everything listed as untested in steps A and B | still untested |

## Known limits worth knowing
* Tool calls are parsed from the model's text, not grammar-constrained; a small model can emit a malformed call (it becomes `{}` input).
* Cancelling a request *during prefill* drops only that request's own prefill progress; once prefill is done a cancel keeps the prompt in the KV.
* A non-streaming request cannot be kept alive during a long prefill and a disconnect is only noticed on streaming requests (pings make the write fail).
* Models whose memory cannot drop a KV tail (some SWA / recurrent ones) get a full re-prefill every request; the log line shows `reused=0`.
* One engine: Claude Code's background "fast model" calls queue behind the main one.


## Revision after first test
* The API section moved to the bottom of Settings and is collapsible (arrow).
* Removed the duplicate server settings (context size, thinking, max output tokens); the server follows the app's own. Stored values of the removed keys are simply ignored.
* Chat: the status shown before the first token (while the prompt is being read) now says "Reading…" instead of "Thinking…". The real "Thinking" block for reasoning is unchanged.
* `session.cpp`: a turn cancelled after prefill keeps the prompt in the KV only when there is no MTP draft context (otherwise it rolls back as before). Patch 0004 regenerated; `git apply --check` on the pristine tree passed again, `g++ -fsyntax-only` on `session.cpp` and the bridge passed again. Kotlin: still not compiled, tests still never run.


## Audit round (full scan)
Scope: every file I wrote or touched, every engine `.cpp` (28 files, `g++ -Wall -Wextra`), and a pattern scan of all Kotlin (swallowed cancellation, divisions, global scopes) plus a read of the risky numeric code (GGUF parser, ContextPlanner, calculator, unit converter, workspace path check).

Fixed:
* **Security:** the adb hook (`api_server`, `api_key` extras) lived in the exported `MainActivity`, so any installed app could start the server, choose its key or switch authentication off. It now works in debuggable builds only; `api_no_auth` is removed.
* **Security / DoS:** the HTTP layer read the whole body (up to 64 MB) before checking the key. Auth is now checked on the request head, before the body (`MiniHttpServer.guard`). `/health` no longer reveals the model name.
* Stop button in the notification could leave "server enabled" on (the write ran in the service's own scope, cancelled with the service): now persisted in the app scope.
* Open client connections survived `stop()` (a blocking read cannot be cancelled): all sockets are closed on stop.
* An in-app generation that asked for the engine in the instant between an API lease being granted and its native call started was missed (the native side clears its cancel flag on start): `generateChat` now checks the lease and the token callback keeps checking it.
* `max_tokens` <= 0 was passed on to the engine, where it means "fill the whole context": now a 400, like Anthropic.
* Claude Code's billing-header system block (changes every request, useless to a local model) is dropped instead of kept with a blanked stamp; remaining system blocks are joined with a blank line instead of glued together.
* **Calculator:** `log2(2^k)` was off in the last digit for 9 of the 62 powers of two (e.g. `log2(2^29) = 29.000000000000004`), and `round(0.49999999999999994)` returned 1. Both fixed and checked with real Java runs of the same algorithms; tests added.
* Chat: "Thinking..." before the first token is now "Reading..." (earlier change).
* Context footer "0 / n_ctx" and the KV wipe after an accepted-EOG draft: see `docs/CTX_AND_KV_REUSE.md` (patch 0005).

Looked at and found fine: engine `.cpp` warnings (one unused parameter in `router_hook.cpp`, pre-existing), `Workspace.resolve` (canonical path + root prefix check), `GgufInspector` bounds, `ContextPlanner` budget arithmetic, division sites with possible zero denominators, catch-all handlers around suspend calls (only three in `FileViewModel`, inside `viewModelScope`, harmless).
Known and left alone: the token estimate is `bytes / 3` (conservative; it overestimates English by roughly a quarter); Qwen-style templates drop old reasoning when a new user turn starts, so part of the KV prefix cannot be reused on those models.
