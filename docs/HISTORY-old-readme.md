# UPDATE — Round 2 (read this first)

## What changed this round
1. **Known issue #1 (`<think>` leak) — FIXED, differently from the plan below.** Instead of a client-side
   tag parser, `native-bridge.cpp` now sets `req.render_text = true` and forwards the engine's own
   `TokenMetrics.text` / `.reasoning` (cumulative, so C++ converts them to deltas). The JNI callback is
   now `onTokenGenerated(answerDelta, reasoningDelta)`; `EngineController` emits `EngineChunk.Answer/Reasoning`;
   `AgentController` routes reasoning straight to `ParsedChunk.Reasoning` (never through the tool-call parser);
   `ChatViewModel`/`ChatMessageBubble` show it as a collapsible "Thinking" block; `ChatMessageEntity.reasoning`
   persists it (schema still v1 — the app has never been installed anywhere).
2. **Known issue #2 (llama.cpp) — vendored, with a caveat.** `app/src/main/cpp/third_party/llama.cpp` is now
   upstream `ggml-org/llama.cpp` master (2026-07-08 snapshot), pruned of docs/tests/examples/web UI.
   It is NOT the Helldez `bmoe/expert-ready-hook` fork, so expert-stream **overlap is unavailable** (serial
   path works; the code already falls back).
   - **One real API break found and patched:** this snapshot's `common_chat_params` has a single
     `thinking_end_tag` (string), while `core/src/engine/thinking_control.cpp` iterated `thinking_end_tags`
     (vector). That file (previously "do not touch") was minimally adapted; see the COMPAT NOTE at its top.
     Every other llama.cpp symbol the engine uses was grep-checked against this snapshot and matches.
3. **Known issue #4 (Settings)** — copy now says sampling applies on next model load; Repetition Penalty
   slider is disabled and labeled unsupported.
4. **Known issue #5 (ChatInputBar)** — attach / prompt-ideas / mic buttons are now genuinely disabled.
5. **Corrections to Round 1's claims:** the root Gradle files were NOT actually in the zip, and the Compose
   compiler plugin was not applied. Added `settings.gradle.kts`, root `build.gradle.kts`,
   `gradle/libs.versions.toml` (AGP 8.7.3 / Kotlin 2.0.21 / KSP 2.0.21-1.0.28 + `kotlin.compose`),
   `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.9), and replaced the obsolete
   `composeOptions` block with the Compose compiler plugin.
6. `app/src/main/cpp/CMakeLists.txt`: project version 0.24.0, and llama.cpp options forced off that would
   break/need network on Android (OpenSSL, tools, app, embedded web UI).

## Still NOT done / not verified
- **Nothing has been compiled or run.** No Android SDK/NDK/CMake/network was available. Expect a first-build
  round of compile errors, especially in C++ (`native-bridge.cpp` against the real headers) and Kotlin.
- `gradlew`, `gradlew.bat` and `gradle-wrapper.jar` are missing. Open the folder in Android Studio (it will
  sync with its own Gradle) or run `gradle wrapper --gradle-version 8.9` once.
- NDK version is not pinned in `app/build.gradle.kts`; install NDK + CMake 3.22.1 via SDK Manager.
- Multimodal (images/video), GBNF tool-call enforcement, and expert-stream overlap remain deferred as before.

---

# BigMoeOnEdge - Extended App (Full Status Snapshot)

This packages everything produced across a very long chat session extending
[BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge) into a full Android
chat app (nicer UI, image/video support, persistent conversations, tool use,
web search, Artifacts). This snapshot is taken at the point the person
switched to a new AI conversation, so it documents *everything currently
known* - done, pending, and broken - in one place.

**Nothing has been built or run.** Every review in this project was a text
review of AI-generated code and diffs, never a real compile. Treat the first
`./gradlew build` as the true test - it is very likely to surface additional
issues no text review could catch (NDK/CMake version mismatches, missing
llama.cpp symbols, etc).

## How to use this package

1. Read `docs/bmoe_real_api_notes.md` FIRST - it's the corrected, verified
   reference for BigMoeOnEdge's actual C++ API. Read the STATUS UPDATE note
   at its top - `core/include/bmoe/session.h` is now vendored for real in
   this package (see below), not just inferred.
2. Read this file's "Known issues" section below before doing anything else.
3. Use the "Suggested opening message for a new AI conversation" section at
   the bottom to get an assistant up to speed quickly.

## What's done (reviewed and approved across the chat session)

- **UI (Compose)**: theme system (Paper/Light/Dark/AMOLED), floating nav bar,
  chat screen (wired to ChatViewModel: live streaming bubble, message list,
  artifact sheet, **new: context-reset divider** - see below), header,
  message bubbles, empty-state suggestions, History screen (list/create/
  delete/clear sessions), Settings screen (system prompt, context/token
  limits, web search config incl. Brave API key + SearXNG URL, sampling
  sliders - **now partly stale, see Known issue #4**), tied together by
  AppNavigation.
- **Persistence**: Room entities/DAOs/database for sessions and messages,
  repository layer with clean domain models, DataStore for engine settings.
- **Tools/Agent (Kotlin side)**: Tool-calling framework, web search tool
  (Brave API + SearXNG fallback, user-configurable), streaming parser for
  tool calls + code artifacts (thinking-tag handling **still missing** - see
  Known issue #1), Artifact bottom sheet with a network-sandboxed WebView
  (JS only for Mermaid).
- **DI & entry point**: AppContainer, AppViewModelFactory, OnEdgeApplication,
  MainActivity.
- **Build files**: root settings.gradle.kts / build.gradle.kts /
  gradle/libs.versions.toml (AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28),
  app/build.gradle.kts (Kotlin 2.0 Compose plugin applied correctly),
  AndroidManifest.xml, plus minimal strings.xml/themes.xml/
  data_extraction_rules.xml/backup_rules.xml/proguard-rules.pro stubs (added
  during packaging, not by any AI - flagged in each file's own comment).
- **`app/src/main/cpp/CMakeLists.txt`** (rewritten this round): forces
  `LLAMA_BUILD_COMMON=ON` (+ tests/examples/server/curl OFF) via
  `CACHE BOOL ... FORCE` before `add_subdirectory(third_party/llama.cpp
  EXCLUDE_FROM_ALL)`; sets `BMOE_HAVE_LLAMA ON` in the same scope (this was a
  critical bug caught and fixed mid-session - without it, `core/CMakeLists.txt`
  silently skips compiling `session.cpp` into `bmoe_core`, and the project
  fails at the *link* step with confusing undefined-reference errors instead
  of a clear missing-header error); both `third_party/llama.cpp` and `core`
  are wrapped in `EXISTS` guards so the project still configures if either is
  absent; links `onedge-engine` against `bmoe_core` when present (falls back
  to linking `llama` directly, though note: `native-bridge.cpp` has an
  unconditional `#include "bmoe/session.h"`, so that fallback only helps if
  `core/` is present but `third_party/llama.cpp` isn't - it does **not** make
  the project buildable with `core/` missing entirely).
- **`app/src/main/cpp/core/`** - **vendored for real this round**, copied
  directly from the real `BigMoeOnEdge-0.24.0` release zip (not a guess, not
  a reference-only copy - the actual source tree CMake will compile:
  `core/CMakeLists.txt`, `core/include/bmoe/*.h`, `core/src/**/*.cpp`).
- **Native/C++ (`app/src/main/cpp/`)**: `native-bridge.cpp` uses the real,
  verified `bmoe::Session` API throughout. This round's changes:
  - `nativeLoadModel()` now takes `temperature`/`topP`/`topK` and sets
    `cfg.sampling.*` at `Session::open()` time (the only place the real API
    allows sampling to be configured - it's fixed for the Session's
    lifetime, not per-generate-call).
  - `cfg.moe.overlap` is now guarded by `#ifdef BMOE_HAVE_EXPERT_READY_HOOK`
    (a macro `bmoe_core` only defines when the vendored `third_party/llama.cpp`
    is detected to be the `Helldez/llama.cpp` fork branch with the
    expert-ready hook, not vanilla upstream) - falls back to `overlap =
    false` otherwise instead of the previous unconditional `true`, which
    would make `Session::open()` fail outright against vanilla llama.cpp.
  - `nativeGenerate()` dropped `temperature/topP/topK/repeatPenalty/
    threadCount/gbnfGrammar` entirely (all were dead - ignored or unused)
    and gained `isNewConversation: Boolean`, mapped directly to
    `req.clear_kv`.
  - `vision_projector.h/.cpp` unchanged - still Phase 3 design, still
    non-functional (see Known issue #2).
- **Kotlin native-binding chain** (`EngineNativeBridge.kt`,
  `EngineController.kt`, `AgentController.kt`): JNI signatures updated to
  match the C++ changes above end-to-end (no dead parameters left partway
  through the chain). GBNF grammar removed completely, including the
  `toolCallGbnf` string constant in `AgentController` (tool-call JSON is
  enforced by prompting + `StreamOutputParser`'s best-effort parsing only -
  see Known issue #3). `AgentController.runAgentLoop()`'s internal
  do-while loop (used for tool-call round trips) was fixed to only pass
  `isNewConversation=true`/evaluate media on the loop's *first* iteration -
  without this fix, a tool-call response would have incorrectly reset the
  model's own KV cache mid-exchange, erasing its memory of the tool call it
  had just made.
- **Conversation-switch KV handling** (`ChatViewModel.kt`, `ChatScreen.kt`):
  `lastGeneratedSessionId` tracks which session the native engine's KV cache
  actually belongs to; `sendMessage()` compares it against the active session
  to compute `isNewConversation`, race-free (`contextResetMessageId` is
  cleared synchronously at the top of `loadSession()`, not conditionally
  inside a Flow collector). When resuming/switching into a session that
  already had messages, a "Context reset - active memory starts here"
  divider (`ContextResetDivider` composable, Material3 `HorizontalDivider`)
  renders above the first new message sent in that session - see "Decisions
  made this round" below for why this is a divider and not real context
  replay.

## Decisions made this round (previously open, now resolved)

These were previously flagged as needing a decision before more code could
be written. They're now decided and (mostly) implemented - see Known issues
below for what's still actually unimplemented despite the decision.

1. **Multimodal (images/video)**: **deferred**. Not touched. Text-only for
   now; `vision_projector`'s functions keep failing closed.
2. **GBNF / tool-call JSON reliability**: **prompting + client-side repair,
   no engine patch**. Fully implemented - the grammar plumbing is removed
   end-to-end, not just ignored.
3. **Conversation resume (old chats / app restart)**: **stateless reload +
   UI divider** (cheapest option; no KV replay, no prompt-flattening).
   Implemented as described above.
4. **Dead sampling parameters**: **moved `temperature`/`topP`/`topK` to
   load-time, dropped `repetitionPenalty` entirely** (no backing field
   exists anywhere in the real API). Implemented in the native/Kotlin chain.
   **The Settings screen itself was never updated to match** - see Known
   issue #4.

## Known issues - read before opening in Android Studio

### Critical / blocking

1. **Thinking-tag leak into the UI is still an open bug - the fix was
   decided but never implemented.** `native-bridge.cpp` sets `req.render_text
   = false` and forwards raw `TokenMetrics.piece` deltas over JNI. This means
   `Session::generate()` never strips `<think>...</think>` (or whatever the
   loaded model's chat template uses) from the stream - that only happens
   when `render_text = true`. The agreed fix was a buffering state machine in
   `StreamOutputParser.kt` (same pattern it already uses for `<tool_call>`/
   artifact detection: watch for open/close tags across token boundaries,
   route reasoning content to a separate channel, everything else to the
   answer channel) - **this was never written**. `StreamOutputParser.kt` is
   unchanged from the start of the project and has zero handling for
   thinking/reasoning tags right now. **This is the single most important
   next step** - any reasoning-capable model loaded into this app will show
   raw `<think>` markup inline in the chat bubble until this is done.
2. **`third_party/llama.cpp` is still not vendored.** `core/` now is (see
   above), but the llama.cpp submodule itself was never available to fetch
   in the environment that assembled this package (no network access) - it
   needs to be cloned in separately. Two things to get right when doing so:
   - It must build a `common`/`llama-common` CMake target (`LLAMA_BUILD_COMMON`
     is already forced ON in `CMakeLists.txt`) - `bmoe_core` links against it
     for chat-template support.
   - For `cfg.moe.overlap` (expert-stream prefetch overlap) to actually
     activate, this needs to be the **`Helldez/llama.cpp` fork's
     `bmoe/expert-ready-hook` branch**, not vanilla upstream llama.cpp -
     `core/CMakeLists.txt` detects this automatically (greps
     `ggml-cpu.h` for `ggml_cpu_set_expert_ready_hook` and defines
     `BMOE_HAVE_EXPERT_READY_HOOK` if found) and `native-bridge.cpp` now
     guards on that macro, so vanilla llama.cpp will still build and run
     correctly, just without expert-stream overlap.
3. **GBNF-based tool-call enforcement doesn't exist** (by decision, see
   above) - tool-call JSON reliability depends entirely on prompting +
   `StreamOutputParser`'s best-effort parsing. Not a bug, just a real
   capability gap worth knowing about if tool calls start coming back
   malformed in testing.

### Known but non-blocking

4. **Settings screen is stale relative to the native/Kotlin changes above.**
   Never actually opened or edited this round. Two things it should have but
   doesn't yet:
   - Copy near the temperature/top-p/top-k sliders should say changes apply
     on the *next model load*, not the current response (matches the real
     API - sampling is fixed at `Session::open()` time).
   - The repetition-penalty slider (if the Settings screen has one bound to
     `EngineSettings.repetitionPenalty`) is now fully disconnected from the
     engine - nothing in the native/Kotlin call chain reads that field
     anymore. Either hide the slider or label it unsupported. Whether
     `EngineSettings.repetitionPenalty` still exists as a field at all
     wasn't checked this round - the data class itself (in
     `EngineSettingsDataStore.kt`, never opened in this session) needs a
     look.
5. **`ChatInputBar.kt`**: was cut off mid-response early in the original
   session and never fully resent. The attach/mic buttons are a placeholder
   added during packaging so the file compiles - not AI-reviewed.
6. **No real image/video picker**: the attach button has no CameraX/Media3
   logic behind it at all (moot until multimodal is un-deferred anyway).
7. **`process_image_buffer()`** in `vision_projector.cpp` is an explicit stub
   (moot until multimodal is un-deferred).
8. **Never built or run** - see the top of this file.

## Suggested opening message for a new AI conversation

```
I'm continuing work on an Android app (BigMoeOnEdgeExtended) extending
BigMoeOnEdge (github.com/Helldez/BigMoeOnEdge). The attached project is
a snapshot from a long prior session - nothing in it has been built or
compiled yet, only reviewed as text/diffs.

Before touching any code:
1. Read README.md in full, especially "Known issues" - issue #1 (thinking-
   tag leak in StreamOutputParser.kt) is the top priority: unimplemented,
   the fix approach is already decided, just needs writing.
2. Read docs/bmoe_real_api_notes.md, including the STATUS UPDATE at the top.
   core/include/bmoe/session.h is genuinely vendored in this package now
   (app/src/main/cpp/core/) - use that file directly, don't reconstruct it.
3. Do not regenerate/rewrite any file from memory. If you need to change a
   file, ask me to paste its current exact content first, then edit that -
   this project had real regressions earlier from an assistant recreating a
   header file from memory and silently fabricating wrong values.
4. third_party/llama.cpp still needs to be vendored in (see Known issue #2
   for the specific fork/branch requirement around expert-stream overlap) -
   this blocks an actual build attempt.

Give me a numbered, file-by-file plan starting with StreamOutputParser.kt.
```
