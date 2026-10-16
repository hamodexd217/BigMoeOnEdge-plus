# Phase 0 audit (nothing fixed in this commit)

Environment reality: no network, no Android SDK/NDK, no Gradle, no CMake, no kotlinc.
Only g++ (host), python3, git, JDK 21 runtime. "VERIFIED" = observed by running a tool here.
"READ" = found by reading code, not by compiling.

## Build / Gradle
- READ: no `gradlew`, `gradlew.bat`, `gradle-wrapper.jar` (jar cannot be produced offline).
- READ: no launcher icons, but manifest references `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`.
- READ: `app/build.gradle.kts` hardcodes every dependency string; `libs.versions.toml` only has plugins.
- READ: Compose BOM only in app/build.gradle.kts (2024.06.00); catalog has none.
- READ: manifest asks for CAMERA / READ_MEDIA_* although the app is text-only.
- READ: `abiFilters` arm64-v8a + x86_64 doubles native build time; NDK/CMake versions not pinned.
- READ: Coil 3 dependency exists only for image attachments that cannot work.

## Native
- VERIFIED (g++ -fsyntax-only against vendored llama.cpp): `core/src/engine/session.cpp` does NOT compile:
  `llama_model_params::load_mode`, `LLAMA_LOAD_MODE_MMAP`, `llama_model_params::load_mtp` do not exist in the
  vendored llama.cpp snapshot (it has `use_mmap`/`use_direct_io`). Every other core/*.cpp passes syntax check.
- READ: `native-bridge.cpp` includes `vision_projector.h` and `llama.h`; vision code calls llama API that is gone
  (`llama_n_embd(model)`, `llama_token_nl(model)`), and uses dead globals `g_llama_ctx`/`g_n_past`.
- READ: CMake: `onedge-engine` gets llama include dirs by hand, not `core/include`; bmoe_core links llama-common.
  `LLAMA_BUILD_COMMON` forced ON is right. No Android-specific ggml flags (GGML_NATIVE, OpenMP).
- READ: `NewStringUTF` on raw token text: partial UTF-8 (Arabic/emoji split across tokens) and 4-byte emoji are not
  valid *modified* UTF-8 -> CheckJNI abort / mangled text. Same for `GetStringUTFChars` on prompts (emoji -> CESU-8).
- READ: `nativeFreeModel` resets `g_session` while another thread may be inside `generate()` (use-after-free).
- READ: temperature/topP/topK are set at open() (correct) but Kotlin/Settings still expose dead knobs
  (repetitionPenalty, streamingChunkSize, expertCacheSizeMb) and cache/dense/io threads are hardcoded in C++.
- READ (session.cpp): `n_predict` has no "unlimited"; `n_prompt+n_predict+8 > n_ctx` -> error; on that error path
  the pushed user message stays in `chat_history` (no rollback) -> duplicated message on retry.
- READ (session.cpp): cancel => whole turn rolled back (KV + user message removed). If `seq_rm` fails (recurrent /
  SWA models) `rollback_turn` clears the KV but leaves `kv_tokens` claiming n_common tokens -> corrupted next turn.
- READ (session.cpp): Session has NO system prompt and NO way to inject prior turns; chat_history only holds
  user/assistant turns created by generate(). So switching chats / restart cannot restore context without a patch.
- READ (session.cpp): `on_token` is invoked synchronously from generate() on the calling thread (verified in the
  decode loop). JNIEnv capture is valid. `cancel()` is an atomic flag polled by the ggml abort callback.

## Kotlin logic
- READ: nothing ever calls `EngineController.loadModel()` -> always "Engine is not ready". No model picker.
- READ: no code creates a chat session; `sendMessage` returns silently when `currentSession == null`.
- READ: `loadSession` starts new collectors on every call without cancelling old ones.
- READ: AgentController builds "system\nUser: x\nAssistant:" and sends it as the user turn of a chatml session
  (double wrapped). Tool results are sent as a raw fragment; original question is lost because cancel rolls back.
- READ: `EngineController.generateResponse` blocks inside `callbackFlow` before `awaitClose`, so cancelling the
  collector cannot stop native generation until the next token; media loop calls dead JNI functions.
- READ: `StreamOutputParser`: tool-call tag split across tokens leaks as text; artifact detection depends on how the
  tokenizer splits "```"; artifact code is swallowed or duplicated; unterminated blocks handled inconsistently.
- READ: ChatViewModel telemetry is fake (`split(whitespace)` tokens/sec) and TTFT is wall-clock.
- READ: MoeStreamConfig.enabled is set true in the bridge (OK); DenseWeightsMode is an enum (OK).

## Kotlin compile errors (READ, certain)
- ChatMessageBubble.kt: `import androidx.compose.ui.font.FontStyle` (wrong package; is `ui.text.font`).
- SettingsScreen.kt: uses `Modifier.width` without importing `androidx.compose.foundation.layout.width`.
- ChatScreen.kt bottom bar: no imePadding/navigationBarsPadding with enableEdgeToEdge().

## UI / polish
- ChatInputBar: attach/mic/lightbulb placeholders; web-search toggle wired to an empty lambda; no stop button.
- `BigMoeTheme` ignores `activeThemeStyle`; `FloatingNavBar` and `ChatHeader` are unused.
- ArtifactSheet: JS only for Mermaid, so HTML artifacts cannot be interactive; mermaid content injected unescaped
  into HTML (injection); no CSP. Network is blocked via shouldInterceptRequest + blockNetworkLoads (good).

## Structural assumptions that cannot be verified here
- ASSUMPTION: vendored `third_party/llama.cpp` (master snapshot 2026-07-08) is compatible with upstream's pinned
  submodule in behavior (only API shape of 3 fields differs, per compile check). Not the Helldez fork, so expert
  overlap is unavailable (serial path).
- ASSUMPTION: no network => cannot add upstream as a git submodule; `core/` stays vendored (real 0.24.0 copy).
