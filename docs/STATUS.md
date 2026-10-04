# STATUS

Date: 2026-10-03

This is the closeout status for the Phase-2/3 feature pass. The project is wired end-to-end in source and has
been statically checked plus selected pure-Kotlin/C++ host smoke tests. A full Android APK build was **not** possible
in this sandbox because Android SDK/NDK and a resolvable Gradle distribution are unavailable.

## Current handoff note (2026-10-03)

This document now reflects the 0.28.0 engine upgrade rather than the previous 0.24.0 core baseline. Historical notes
below are kept because they describe earlier owner rounds, but the current engine state is the section at the end of this file.

## 1. Implemented, in the requested handoff order

1. **Chat input** — Attach menu (File / Image / Video), pending attachment chips, real document pickers, Vision-gated
   media actions, and Tools menu (Files / Code). Web and Thinking remain as clear icon toggles.
2. **Chat screen** — Confirmation dialog with scrollable unified diff, attachment/artifact chips, chat Files sheet,
   file navigation, and attachment-only sending.
3. **Code blocks** — Streaming-safe `MarkdownLite`, language detection, syntax tokenization/highlighting, copy-code-only,
   and Open as artifact for larger blocks.
4. **Workspace/file viewer** — Browse folders/files, open/edit/save/copy/share/download, HTML/SVG/Mermaid preview,
   binary handling, delete confirmation, and Settings → Workspace.
5. **Model mmproj flow** — Projector discovery/suggestion, load-dialog selection and persistence, backend plumbing,
   Vision status, and warning that image turns cannot use Guess-ahead/speculation.
6. **Settings** — File/Code tool switches, Pictures & video controls, workspace entry point, and capability text.
7. **Tests** — Added/updated JVM coverage for workspace, diffs, file context, HTML extraction, search, code analysis,
   attachment context, file tools, tool approvals, JavaScript result formatting, Markdown, syntax highlighting,
   model/projector matching, agent tool enablement, plus Room attachment/artifact persistence instrumentation tests.
8. **Docs/patches** — Generated `patches/0003-multimodal.patch`; updated README, handoff, multimodal notes, status,
   and tool-extension documentation.
9. **Final audit** — Re-ran available checks, fixed findings, verified a clean Git tree before packaging, and prepared
   one consolidated device checklist below.

## 2. What was actually tested here

| Check | Command / method | Result |
|---|---|---|
| Kotlin source sanity | `python3 scripts/kt_lint.py app/src` | `checked 103 files, 0 problem(s)` |
| Kotlin Markdown + syntax runtime smoke | `kotlinc MarkdownLite.kt SyntaxHighlighter.kt` + `java -jar` | `markdown/syntax smoke passed` |
| Workspace/FileContext runtime smoke | `kotlinc` pure workspace sources + `java -jar` | `workspace/context smoke passed` |
| Model/projector runtime smoke | `kotlinc` with small Android/coroutines stubs + `java -jar` | `model-manager smoke passed` |
| C++ session syntax, mtmd ON | `g++ -fsyntax-only ... -DBMOE_HAVE_MTMD=1` | pass |
| C++ session syntax, mtmd OFF | `g++ -fsyntax-only ...` | pass |
| JNI bridge syntax, mtmd ON | `g++ -fsyntax-only` with minimal `jni.h` / `android/log.h` stubs | pass |
| Other core C++ syntax | `config.cpp`, `arch_registry.cpp`, `ngram_draft.cpp` | pass |
| Patch / whitespace audit | `git diff --check` | pass |
| Python algorithm cross-check | pure Python ports of FileContext/BM25, chunk budget, LCS diff, and parser patterns | `python algorithm smoke passed` |
| Multimodal patch applicability | `git archive d4d32ac` + `git apply --check patches/0003-multimodal.patch` | `patch 0003 apply-check passed` |
| Gradle availability | `./gradlew --version` | wrapper tried to resolve Gradle 8.9 but failed with `UnknownHostException: services.gradle.org` |

During the host compiler pass, real issues were found and fixed: `CodeAnalysis` used an incompatible deque insertion;
projector matching had invalid Kotlin regex escapes; tight file-context budgets could hide relevant chunks; and file
viewer code had no token coloring. Attachment preparation now aborts generation instead of silently continuing after a
read/decode failure. The available compiler is Kotlin 1.9 while the project metadata targets Kotlin 2.0.21, so host
compilation is a supplemental check, not an Android build.

## 3. What could not be tested here

There is no Android SDK/NDK, no connected device/emulator, and no working Gradle dependency resolution in this
sandbox. Therefore there is no claim of a built APK, Android link, Room instrumentation execution, Compose runtime
execution, WebView runtime execution, or actual mtmd image/video inference here.

The native mtmd sources and integration pass **host syntax checks only**. The Android CMake path that creates and links
the `mtmd` target still needs a real NDK build. Vision also needs a compatible vision model plus its `mmproj` on the
Xiaomi tablet.

## 4. Known limitations / risks

* The tool protocol is prompt-based, not grammar-constrained; smaller models can still fail to emit valid tool calls.
* Web search needs network access and a working provider; DuckDuckGo parsing is intentionally tolerant rather than an API contract.
* Mermaid preview loads its renderer from jsDelivr; ordinary artifact pages block network access through CSP/interception.
* After an image turn the engine deliberately clears the KV because image embeddings are not representable by the text-only
  `kv_tokens` mirror. A later text turn replays textual history; it does not reconstruct prior image embeddings.
* Video is implemented as sampled image frames in Kotlin (`MTMD_VIDEO=OFF` in the native build), so the device test must
  cover frame count, memory, and context pressure.
* Room v1 → v2 uses `fallbackToDestructiveMigration()`: the app shows a startup warning and old **development-build**
  chats are cleared once. Models/settings are not cleared.
* Model import remains single-file oriented; split models may need manual placement.
* Native generation remains single-flight; unload waits for an active generation to finish.

## 5. Consolidated device test checklist

Run this once on the Xiaomi tablet after installing the debug APK:

1. **Load + baseline:** open Models, load the same model/config in the original BigMoeOnEdge and BigMoeOnEdge+; record
   load time and tokens/s. Test serial mode and overlap where the device/build reports overlap capability. Confirm the new
   app does not regress ordinary text generation.
2. **Core generation:** send Arabic + emoji, check streaming, stop, resume, several multi-turn messages, and verify the
   `active` overlap status does not stay stuck. Reopen an old chat and continue it; rename and delete chats.
3. **Web search:** test Web OFF, Web AUTO (model decides), and Web ALWAYS (app searches first), including a provider failure.
4. **Thinking:** toggle Thinking on/off and verify the reasoning block, visible answer, and stop behavior.
5. **Tools:** enable Files and Code; test create file, read/search, edit with diff approval/reject, delete with confirmation,
   rename, create folder/project, `export_zip`, `analyze_code`, `search_code`, `run_javascript`, and web page read/extract.
6. **Text attachments:** attach small `.txt/.md/.json/.csv/.xml/.yaml/.log/.java/.kt/.cpp/.h/.hpp/.c/.py/.js/.ts/.html/.css`
   files; verify chips, filename-aware context, relevant extraction for a large file, and that the workspace contains the copy.
7. **Code blocks:** verify language detection, syntax highlighting, Copy copies only code, horizontal scroll, and Open as
   artifact appears for larger blocks.
8. **Artifacts:** create an artifact, open it from the chat Files sheet, view it, edit/save it in the file viewer, copy,
   share, download, reopen it later, and confirm it remains attached to the correct chat.
9. **Palettes:** cycle built-in palettes, verify dark/light contrast, edit a custom palette, relaunch, and confirm persistence.
10. **Vision:** load a compatible vision model + matching `mmproj`; verify `Vision: active`, attach an image, generate, then
    attach a second image. Verify Image/Video controls are disabled when no projector is loaded and show the reason.
11. **Video:** attach a short MP4, verify sampled frames are passed, test 1/2/4/6/8 frame settings, and watch memory/context usage.
12. **Images + thinking/speculation:** confirm the app warns/refuses the incompatible combination and that disabling Guess-ahead
    permits image generation.
13. **Persistence:** force-stop/relaunch, continue chat history, verify attachments/artifacts remain, and verify rename/delete
    operations survive relaunch.
14. **Room upgrade:** install a development v1 build with a test chat, install v2, confirm the startup wipe notice appears,
    old chat history is cleared as documented, and models/settings remain.
15. **Polish:** verify app name is **BigMoeOnEdge+**, launcher icon, attachment/tool menus, overflow/secondary controls,
    errors, and no UI freezes during file import or media preparation.

## 6. Git / deliverables

The multimodal change is preserved as `patches/0003-multimodal.patch`. This upgrade is kept on the dedicated
`chore/upstream-0.28-merge` branch and no commit was made to the main branch. The final ZIP includes `.git` and `.gitignore`
and is created without excluding `.git*`; generated build/cache trees are not added.

## Owner-requested changes (2026-10-03)
* Home (Chats) screen: the picture is gone (HomeHero.kt and home_hero_default.xml removed); only the chat list remains.
* Settings: removed the Thinking switch, the Tools section (File tools / Code tools) and the Web search switch. These
  are controlled from the chat input bar (globe, lightbulb, tools menu). The "When to search" mode choice stays.
* Settings: "Context (tokens)" is a typed number (256..131072, `ui/settings/ContextInput.kt`, unit-tested); invalid
  text is flagged and the last valid value stays in use.
* Settings: palettes are one dropdown button (System, built-in, custom, "New custom palette", "Edit this palette").
* Models: card is titled "Add a model / mmproj"; the picker accepts a model or an mmproj .gguf.
* Navigation: all NavHost transitions are `None` (instant tab switching).
* Thinking button/icon is a lightbulb (was a head).
* Artifact / HTML preview: the WebView now always fills its area and loads the page only once it has a real size
  (before, `100vh` / centring was computed against height 0, so content sat clipped at the top). HTML documents
  without a viewport meta get one. NOT verified on a device.
* Build errors from the owner's GitHub log were fixed in the owner's tree (opt-in flags, generic `wsCall`,
  smart casts, `updateCacheFloorMb`, `PaletteSpec` continue); this pass kept those fixes. No compiler was available
  here, so the whole tree is still unverified by a Kotlin compiler.

## Second owner round (2026-10-03)
* Artifact / HTML preview: the previous fix (load only when sized) did not cure the clipped, top-aligned content on the
  owner's tablet. The page is now told the real visible height in pixels: `ArtifactHtml.fitViewport` turns every
  `vh`/`dvh`/`svh`/`lvh` length into px and sets `html{height:Npx}`; the WebView also fires a `resize` event after load
  and reloads if its height changes. Unit-tested as a string transform; the on-device effect is NOT verified. If it is
  still wrong, the HTML source of the failing artifact is needed (open it, tap the pencil, copy the text).
* Context (tokens): no upper limit any more (only > 0; `ContextInput`, DataStore `coerceAtLeast(1)`). A value the device
  cannot hold shows up as a normal load error.
* One file per distinct artifact: "Open as artifact" and the artifact chips share `ChatViewModel.saveOrReuseArtifact`
  (same text in the same chat => same file, found by remembered path or by comparing text); no more
  `inline.html` / `snippet.html` / `snippet (1).html` for one piece of code. Old duplicates stay until deleted.
* Chats screen and the navigation root now paint the palette background (the window's default white showed through).
* Chat list: following the newest text stops when the person drags up to read; a down-arrow button appears and jumps
  back to the bottom (also re-enabled on send or when the bottom is reached again). Uses a nested-scroll connection:
  programmatic scrolling never turns following off.

## Load failure on a different model (2026-10-03)
Owner report: a Qwen "Flash Next" Q4 model says "failed to load" here but loads in the plain BigMoeOnEdge app.
Exact engine error text was not available. Two real causes were found by reading the code and fixed:
* `ModelManager.suggestProjector` matched a name-less projector (`mmproj-F16.gguf`) to EVERY model, so a projector
  belonging to another model was attached to the new one and the engine rejected the load. Name-less projectors are
  no longer picked by name, and the last-used projector is only re-selected for the same model.
* If a load with a projector fails, the app now retries text-only (what the plain engine does) and says so.
* Guess ahead (MTP / n-gram) together with temperature > 0 makes the engine refuse to open the model
  ("speculative decoding requires greedy decoding"). `toLoadConfig()` now uses temperature 0 while it is on, and the
  load dialog warns about it.
NOT verified on a device. If it still fails, the exact message shown on the Models screen is needed.

## Qwen3.8-Flash-Next does not load (root cause, 2026-10-03)
The owner clarified: Qwen3.8 Flash Next fails to load even WITHOUT an mmproj, while Qwen3.6 35B loads. The previous
mmproj / greedy fixes were real bugs but NOT the cause of this.
* The model's architecture is `qwen4exp`. The engine core already knows it (`core/src/moe/arch_registry.cpp`), but the
  vendored `app/src/main/cpp/third_party/llama.cpp` does NOT: `src/llama-arch.cpp` has no `qwen4exp` (nor
  `bailingmoe3`), and `src/models/` has no qwen4exp graph. `llama_model_load_from_file` therefore throws
  "unknown model architecture: 'qwen4exp'" and the app only sees "failed to load model".
* The original BigMoeOnEdge app ships a newer llama.cpp snapshot, which is why it works there.
* Fix = replace `third_party/llama.cpp` with the snapshot from the original app (then re-check the COMPAT block in
  `core/src/engine/session.cpp` for `load_mode` / `use_mmap` and re-apply patch 0002/0003 if they touch llama.cpp).
  The qwen4exp graph (hyper-connections, sparse attention with indexer, gated-delta blocks, per-layer n-gram
  embedding) is deliberately NOT re-implemented from memory.
* Done meanwhile: the native load error now names the architecture the GGUF declares and says that an unknown
  architecture means the built-in llama.cpp is older than the model.
* After the swap, per docs/model-loading.md this model needs Dense weights = Pinned and a small expert cache
  (1000-1500 MiB on a 12 GB device).

## Round: BigMoeOnEdge engine upgraded to 0.28.0

* `app/src/main/cpp/core` now starts from the upstream 0.28.0 `core/`. The 0.25→0.28 engine changes are therefore
  present, including Nemotron H MoE, decide/prefix-cache, prefill-device/path, loader threads, routed prefill and the
  new decision-probe/device-arena machinery.
* Local patch `0001` was regenerated against pristine 0.28.0 for saved-chat context (`system_prompt`/`history`),
  `n_predict <= 0`, and rollback fixes. Local patch `0003` was regenerated on top for mtmd/mmproj image turns.
* Upstream `thinking_control.cpp` and the upstream MTP position fix (`dp.pos0`) are kept. The old 0.24-compatible
  `thinking_end_tag` adaptation is not carried into the 0.28 core.
* `core/CMakeLists.txt` now includes the 0.28 decision/logits/prefill sources. The app-level Hexagon path is compile-guarded
  and OFF by default; the upstream `scripts/build-hexagon-android.sh` is included for the optional toolchain build.
* App-side 0.27/0.28 features are wired through the existing architecture: Choose-from-options uses the real `Session::decide`
  path, and NPU prefill/loader controls are only shown when the native capability reports Hexagon support.
* The Models page no longer lists a catalog: it says any MoE model works and that regular (dense) models can work too.
  `ModelManager` imports local GGUFs; there is no downloader stack.
* The "mmap baseline" switch is gone. `GgufInspector` reads only the GGUF metadata (`<arch>.expert_count`) when a model
  is opened: MoE (>= 2 experts) streams its experts, a regular model is loaded the ordinary way (`mmapBaseline=true` in
  the effective `LoadConfig`), and an unreadable header keeps the streaming default. The decision is made in
  `EngineController.loadModel`, and `loadConfigDiffers` ignores it, so it never raises a "reload needed" notice.
  Needs a device check with one dense and one MoE model.
* The chat's folder button (Chat files sheet) now shows a tree: files the user sent, then the folders and files the AI
  made in this chat. `create_folder` registers the folder as an artifact row with `language = "folder"` (no schema
  change). Files made inside a folder show up under it even if the folder itself was never registered.
* New tool group `UTILITIES` (get_datetime, calculate, convert_units, random_number, device_info), switchable under
  Settings -> Tool access and in the chat's wrench menu. On by default (read-only, offline).
* Image/video selection now uses the Android system photo picker (`PickMultipleVisualMedia`) with `ACTION_GET_CONTENT` as
  the fallback. Imported media is copied into app-private workspace storage immediately, with no `READ_MEDIA_*` permission.
* Wide screens (>=840dp) keep a permanent 344dp chat-history panel on the left while the active chat stays on the right.
  Narrow phones retain the existing separate-page navigation.
* Composer drafts and pending attachments are stored per chat in a dedicated DataStore. Switching chats serializes the save/load
  boundary, new-chat drafts use a dedicated key, and sends clear only the chat that was actually sent.
* Code artifacts preserve non-HTML languages; the parser now carries a language field and maps common Kotlin/Java/Python/C/C++/
  JavaScript/TypeScript/JSON/YAML/shell/etc. blocks to suitable file extensions.

### Upstream change plan (0.25 → 0.28)

| Upstream change | Port decision | App adaptation |
|---|---|---|
| 0.25 `nemotron_h_moe` / gate-less Nemotron | Port | Engine-only; catalog entry refreshed |
| 0.25 Ornith 1.5 | Port | Engine capability kept; obsolete special catalog shortcut not retained |
| 0.26 NPU prefill / `--prefill-device` | Port | Optional Hexagon build + capability-gated settings |
| 0.26 `--prefill-loaders` | Port | Loader count control appears only when NPU prefill exists |
| 0.26 llama.cpp 965f897 baseline / MTP `pos0` | Keep existing vendored tree | 0.28 core uses upstream `pos0`; llama.cpp itself is not replaced in this pass |
| 0.26 Hexagon Android release path | Port | Script copied; default CMake path remains OFF |
| 0.27 `--decide`, choice scorer and prefix cache | Port | JNI `nativeDecide`, Kotlin result types, chat switch/options UI |
| 0.27 catalog refresh | Port | Informational catalog matching upstream 0.28 entries |
| 0.28 routed prefill defaults / decide probe | Port | Engine-side; NPU path stays optional and disabled by default |

## Verification for this pass

The Android SDK/NDK is not installed in this sandbox, so the following remain explicitly unverified: full CMake/NDK
configure+build, APK packaging, Compose runtime behavior, Room instrumentation tests, and device execution. The current
verification pass runs host C++ syntax checks over every `core/src/**/*.cpp`, the offline Kotlin lint script, and JVM unit
tests only when Gradle/dependencies are available. Exact command results are recorded in the final handoff report.


## Final-8 follow-up UI fixes

- Added an always-visible Home button to the chat top app bar; it returns to the main Chats/Home page without changing the active chat data.
- Made the NPU prefill control visible in Settings > Streaming even when the installed APK lacks Hexagon support. Unsupported builds show the reason and keep the switch disabled; Hexagon-enabled builds expose the real toggle and loader-thread setting.

### NPU build hardening (final-10)
The optional Hexagon path now has a real app-native build recipe. `scripts/build-hexagon-onedge-engine.sh` builds the app's `libonedge-engine.so` with `GGML_HEXAGON=ON` inside the pinned Snapdragon toolchain and stages all supported HTP skels. GitHub workflow `android-npu.yml` builds the APK from that prebuilt payload. Gradle's `-PbmoePrebuiltNative=true` mode verifies the JNI payload before packaging, so an NPU APK cannot silently fall back to the CPU-only native library.

## Final-10 build-log follow-up

The GitHub `assembleDebug` log from 2026-10-03 failed during Kotlin DSL script compilation before any Android/CMake compilation.
The root cause was an accidental template token in `app/build.gradle.kts`: line 134 contained `$needle` where the `dependencies {` block opener belongs.
That token has been replaced with the actual `dependencies {` declaration. No NPU/native code was implicated in that failure.
The local Kotlin lint script passes after the fix; a full Gradle build is still not runnable in this sandbox because the Gradle 8.9 distribution is not cached and outbound network access is unavailable.


## CI CMake regeneration guard (2026-10-04)

- `app/build.gradle.kts` now passes `-DCMAKE_SUPPRESS_REGENERATION=ON` only under CI/GitHub Actions.
- Local Android Studio/Gradle builds retain normal CMake regeneration behavior.
- This targets the Ninja failure where `build.ninja` remained dirty after repeated CMake regeneration attempts.
