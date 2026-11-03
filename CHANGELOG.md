# BigMoeOnEdge+ Changelog

## 0.6.5 — 2026-11-02 (source update; covers everything since 0.6.0)

Version 0.6.5, `versionCode` 19. Everything below is new since 0.6.0.

### Chat and interface
- **Native UI cards are chosen by the model.** The system-prompt hint no longer says "only when explicitly requested": it lists when a card fits (comparisons, pros/cons, tables, rankings, timelines, steps, key numbers, slides) and includes a one-row example, so "compare two things" works without saying "use the tool you have". It adds about 450 tokens to the prompt, which is why the setting stays off by default. The parser is forgiving (missing `type`/`title`, rows on the root, `name`/`a`/`b` key aliases, trailing commas, truncated JSON, prose around the object, misspelled or unclosed fences) instead of showing "could not be rendered"; a plain `json` block renders only if it declares a known `type`.
- **Wrench menu** (under the chat input) now also switches **Native UI** and **External** tools on and off, next to Files / Code / Utilities.
- **In-app browser.** Image-search previews, source links and links in answers (every http/https `openUri`) open in a full-screen browser inside the app with close, back, reload and "open in your browser app" buttons; other link types still go to the system.
- **"Liquid glass" is now called "Glass"** in Settings and the palette preview (the stored setting is unchanged).
- **Chat-switch glitch fixed.** While one chat streamed, opening another showed an empty spinner/live bubble there and the list jumped. The shared "a generation is running" flag (which still locks the composer) no longer decides whether the open chat shows a live bubble (`ChatUiState.isGeneratingHere` does), and the previous chat's live text is cleared on switch.
- **Live status line** under the answer: what the model is doing and for how long (`Reading prompt 12.3 s`, `Thinking 8.1 s`, `Using web_search 2.4 s`, `Answering`), `Thought for 18 s`, `Tools took 3.2 s`, prompt-reading speed (`Prefill 42 tok/s (420 tok, 9.8 s)`) and live generation speed. The Thinking header counts up while the model thinks.
- **Timings are saved with each message** (new columns `prefill_tokens`, `thinking_ms`, `tool_ms`; database version 5, `MIGRATION_4_5`, same shape as 3→4): the stats line reads `prefill 42 tok/s (420 tok, 9.8 s)` and `thought 18.0 s`, the Thinking block says `Thought for 18 s`, tool-result cards say `Tool result · 3.2 s`. Older messages keep their old wording. Limit: the engine only reports the prompt size when a round ends, so the prefill *speed* appears after each round.
- Carried over from the earlier 0.6.1 source update: expand/hide of the chat-history pane on wide screens (the menu button in the chat toolbar), Custom Settings, Solid / Gradient / Glass surface styles, a centered max-width composer, removal of the old live-statistics strip, data-only opt-in Native UI components.

### Sampling
- New controls under Top K in Settings: **Min P, Repetition penalty, Repeat window, Frequency penalty, Presence penalty**. All default to off. They apply per request without reloading the model, work at temperature 0 too, and are switched off while Guess ahead (MTP / n-gram) is on, because that mode verifies against plain greedy decoding. Native side: one shared `build_sampler_chain` in `session.cpp` (penalties → top-k → top-p → min-p → temperature → dist); the JNI signatures and load-config arrays gained the new fields (`INT_COUNT` 45, `FLOAT_COUNT` 6). The HTTP API still ignores client-supplied `repeat_penalty` / `min_p` / frequency / presence fields and uses the app's values.

### Tools
- **No tool-use limit.** The old cap of 6 tool rounds ("Tool call limit reached") is gone, and a round may contain up to 16 calls (was 4). Remaining guards: the Stop button, the context size, and one loop guard: the exact same call (same tool, same arguments) 9 times in a row stops the turn with a note.
- **External tools** (Settings → Tools → External tools): HTTP tools (address with `{argument}` placeholders, GET/POST/PUT/DELETE, headers, arguments) and MCP servers (Streamable HTTP; the server's tool list is fetched once and cached; you choose which tools the model may use). Every call asks for approval by default (switchable per entry); results are marked untrusted in the prompt; names are made unique and cannot shadow built-in tools. Not supported: the legacy HTTP+SSE transport, stdio servers, OAuth, MCP resources/prompts. See `docs/tool-calling.md`.
- **Editor-style file tools:** `edit_lines` (replace / insert / delete by line number, several edits per call numbered against the original file, keeps final newline and CRLF), `replace_in_files` (project-wide find & replace, plain or regex with `$1`, one combined diff to approve, max 50 files), `undo_edit` (restores the file to before the last AI edit; up to 10 steps per file, kept in memory only). `rename_file` gained `copy=true`; `search_files` gained `context` (0–5 lines). The tool prompt gained one sentence on which edit tool to use.
- `ToolManager` now keeps its tool list copy-on-write and gained `syncExternalTools` / `externalToolNames`.

### Models and engine
- **LFM3 (text) and LFM3-VL (vision) ported** from upstream llama.cpp PR #31252 (release b11960 / v0.8.0) onto the vendored engine: `lfm3` architecture and graph (`lfm3.cpp`, carrying its own copy of the short-convolution block so the LFM2 / LFM2-MoE code is untouched), quantizer rule, and the `lfm3vl` projector in `tools/mtmd` (graph, token counting, 2D RoPE positions, `smart_resize` preprocessor, `<|vision_start|>`/`<|vision_end|>`). Known differences/limits: image resizing uses plain bilinear/bicubic instead of torchvision-style resizing, so image embeddings differ slightly from the reference; LFM3 audio / omni / decision models and video are not ported; vision needs the matching `mmproj` file. See `docs/LFM3_SUPPORT_PORT.md`.
- **Flint** (`flint`: MoE with leading dense blocks and a shared expert, expert streaming registered; CPU path, NPU unverified) and LFM2 / LFM2.5 (`lfm2`, `lfm2moe`) are supported by the vendored engine; `ModelFamilies` now names them and turns the raw "unknown model architecture" load error into a readable message.
- **NPU prefill no longer kills the app when the device cannot map memory.** Seen on a Xiaomi (Hexagon HTP0) loading Qwen3.5-4B Q4_K_M: `ggml-hex: fastrpc_mmap failed` is thrown as a C++ exception from inside `llama_decode` during `PrefillPath::reserve_on_device`; nothing caught it and the process aborted. It is now caught in `PrefillPath::decode`, around `PrefillPath::open` in `Session::open`, and around `Session::open` in the JNI bridge, and shown as an ordinary load error with a hint (turn off NPU prefill, lower the context, or use a smaller quantisation). The root cause is **not** fixed: with NPU prefill on, a dense model's layer weights and its context state are copied into the NPU session, which has a size ceiling of its own; MoE models stream through two small slots.

### Housekeeping
- Version name 0.6.5. `versionCode` is 19 so a build of this release installs over any of the intermediate test builds.
- `scripts/verify-requested-features.sh`: updated to 0.6.5 and three stale source patterns corrected (Glass surfaces, history-pane button); it now passes all checks.
- New tests: parser repair, load-config wire format, sampling extras, chat-switch state, external tools (fake HTTP and MCP servers), editor tools, timing formatting, agent timing events and the no-cap tool loop.

### Source checks made while writing it
- `g++ -fsyntax-only` on `lfm3.cpp`, `lfm3vl.cpp`, `clip.cpp`, `mtmd.cpp`, `mtmd-image.cpp`, `llama-arch.cpp`, `llama-model.cpp`, `llama-quant.cpp`, `session.cpp`, `config.cpp`, `prefill_path.cpp`; the LFM3 chat template rendered correctly with the vendored Jinja engine; bracket-balance checks on the edited Kotlin; `scripts/verify-requested-features.sh` (27/27). Compiling and running the app and its tests is done by building the project.

## Unreleased — upstream 0.35.0 migration tranche

- Fixed Settings → Appearance to start collapsed, consistent with the other settings groups.
- Added conservative session-level Cache Brake for streamed MoE expert-cache pressure; host policy tests pass, but Android memory-pressure behavior remains unverified.
- Added OpenAI Chat Completions `response_format: {type: "json_object"}` and `json_schema` plumbing through Kotlin/JNI/native generation, with grammar-constrained sampling and final JSON validation. Unsupported/malformed formats fail explicitly. Host native core builds; real-model/template behavior and Android tests remain unverified.
- Added upstream logging and expert-layout/tensor-name helpers. Expert-layout tests pass; automatic fallback for architectures without a registered recipe is not yet connected.
- See `docs/UPSTREAM_0.35_MIGRATION_PROGRESS.md` for exact scope and test boundaries. This is a source migration tranche, not a ready-to-install APK.

## Unreleased - NPU prefill + MTP fix

- NPU prefill now works together with Guess ahead (MTP and n-gram). Before, three independent gates refused the combination: `config.cpp` ("prefill.device does not combine with speculative decoding"), `native-bridge.cpp` (rejected any `I_SPEC_SOURCE`) and the Settings switch (`npuCombinationAllowed`). Removing the gates alone would have been unsafe, so the engine was also fixed: the MTP draft pass during prefill ran while the target's weights were rebound to the device (and with the arena hooked in), and the MTP head's layer, experts and KV were pulled into the device rebind. See docs/NPU_MTP_FIX.md.
- The NPU "minimum prompt tokens" threshold must now exceed the speculative verify width (draft_max + 1); the app raises it automatically and the Settings list only offers valid values.
- A separate MTP sidecar GGUF is loaded CPU-only while NPU prefill is on, so it does not open a second HTP session.
- MTP / n-gram Guess ahead can now be loaded together with an mmproj. Before, `session.cpp` refused both at load and on any image turn. Image turns decode plainly (the engine already disabled drafting for them), and both contexts' KV is cleared around them, so text turns afterwards re-prefill target and draft consistently.
- Not compiled with the NDK/Hexagon toolchain and not run on a device in this environment.

## Signing fix

- Added a stable release signing key mechanism (`app/build.gradle.kts`, env/secrets driven) and updated the NPU CI workflows to require it, so future APKs share one certificate and install as updates. See docs/SIGNING.md.
- One-time limitation: v0.5.5 and earlier were signed with a throwaway CI debug key that no longer exists, so the first build signed with the new key cannot update them; uninstall is required once.

## 0.6.0 — 2026-10-23

- **Auto settings** (Settings → top card): scans RAM, CPU cores/fast cores, SoC, a quick memory-speed test and whether the NPU runtime is available, then builds settings for the chosen model in three tiers: **Fastest**, **Balanced** and **Most accurate (slowest)**. MoE and dense models get different plans (expert cache, dropped/substituted experts, prefetch, guess-ahead, context, threads).
- Each save shows an **estimated tokens/second**; **Measure** loads the model with that save, generates 64 tokens and records the real speed, then restores the model that was loaded before. A "this may take a while" prompt shows a time estimate first, and measuring can be cancelled.
- **Saves**: named, switchable (Use), renamable, deletable, and downloadable/importable as small JSON files. Switching a save only changes the engine settings Auto settings owns; sampling, tools and theme are untouched.
- Measuring is crash-guarded: plans are kept inside a cautious RAM budget, a save that wouldn't fit is skipped instead of loaded, only one big load exists at a time, the screen stays on while measuring, and a crash marker lowers a save's memory use automatically if the app is killed mid-measurement. The Fastest tier no longer uses expert substitution, two-wave reads or a wide prediction window (the settings its first version crashed a 35B-A3B model with), and its expert-count cut is milder (8 → 6); older saves are cleaned automatically. NPU prefill is opt-in (off by default). Each save has a "Lower memory" button.
- Bumped to 0.6.0 (versionCode 11).

## 0.5.9 — 2026-10-22

- Fixed NPU unavailable at runtime ("HTP0 was not registered (devices: CPU)"): the manifest now declares `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`, which Android 12+ requires before an app can load Qualcomm's FastRPC library that ggml-hexagon dlopen()s.
- Fixed the NPU payload verifier false-failing under `set -o pipefail` (`strings | grep -q` SIGPIPE); it now greps the binary directly.
- Bumped versionCode to 10 so the NPU build installs as an update instead of being mistaken for the CPU-only 0.5.8 APK.

## 0.5.8 — 2026-10-22

- Fixed the Android NPU workflow to run Gradle from the project root and use the correct root-relative APK path.
- Hardened the Hexagon CMake path with explicit SDK/toolchain validation, the existing `PREBUILT_LIB_DIR=android_aarch64` selector, a fresh build directory, and stale-output invalidation. The reported SDK `string(FIND...)` failure is not reproduced here and remains pending a real toolchain CI run.
- Updated the native-build container reference to the documented Snapdragon toolchain `v0.8` image and added ELF/backend-marker/payload validation before packaging and after extracting files back out of the APK.
- Made the Hexagon build script remove only its own staged libraries instead of deleting the entire `jniLibs/arm64-v8a` directory.
- Added a source-level NPU validator and made settings/upstream wiring validators follow the current app version instead of hard-coding 0.5.7.
- Clarified that a successful CI package is not proof of on-device HTP execution; runtime verification on a supported Snapdragon device is still required.

This is an NPU build-pipeline hardening change. It does not claim an APK has been built or benchmarked in this environment.

## 0.5.7 — 2026-10-22

- Reorganized Engine Performance into four grouped cards: Hexagon prefill, MoE streaming/I/O, speed/quality, and CPU compute/memory.
- Added native-wired controls for batch/prefill threads, ubatch width, sampling seed, two-wave I/O, row-stream window, NPU routing thresholds, N-gram matching, drop normalization/prefill, and expert-stream diagnostics.
- Added the upstream decision-prefix-cache policy to the scored-choice API settings.
- Added an explicit force switch for expert-cache sizes below 1500 MiB while keeping the previous BigMoeOnEdge+ default behavior.
- Enabled I/O/compute overlap in fresh-install defaults. The native build still reports overlap inactive when the expert-ready hook is absent or streaming/cache is unavailable.
- Made Hexagon/NPU controls visible in CPU-only builds, disabled with an explanation instead of hiding the settings.
- Added a source-level parity inventory separating supported options from unsupported desktop/GPU/planner/debug-only options.
- Preserved BigMoeOnEdge+ identity, API, database, MTP sidecar flow, and custom UI.

This is a settings/UI/native-wiring update on the existing local engine baseline, not a wholesale merge of upstream 0.33.x.

## 0.5.6 — 2026-10-21

- Fixed host-side handling of routing index tensors whose buffers are owned by a non-host backend.
- Avoided streaming/rebinding device-owned expert weight tensors and restricted route-mutating diagnostics to host-readable tensors.
- Preserved BigMoeOnEdge+ UI, API, multimodal, persistence, split-GGUF, and custom MTP paths.
- Documented the current upstream boundary and verification limits in `docs/UPSTREAM_UPGRADE_0.5.6.md`.

This is a compatibility fix on the existing 0.28.0 engine baseline, revisioned locally as 0.28.1. It is not a wholesale merge of upstream 0.33.x.
