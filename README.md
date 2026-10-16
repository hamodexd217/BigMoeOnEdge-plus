# BigMoeOnEdge+ — v0.4.0

**A local-first Android chat app for running GGUF language models on-device, including Mixture-of-Experts (MoE) models with expert streaming.** Built with Kotlin, Jetpack Compose, Room, DataStore and a native C++ engine connected through JNI.

> **Release status:** this repository contains the v0.4.0 source changes and release notes. A successful Android APK build and full device QA must still be completed in Android Studio or GitHub Actions before publishing a release. See [`docs/STATUS.md`](docs/STATUS.md) and [`docs/RELEASE_NOTES_0.4.0.md`](docs/RELEASE_NOTES_0.4.0.md).

- **Version name:** `0.4.0`
- **Android version code in this source:** `4` — if any APK has already been uploaded to Google Play or another store, increase this to a value higher than the highest version code already published.
- **Application ID:** `com.bigmoe.onedge`
- **Minimum Android version:** Android 10 (API 29)

## Contents

- [Highlights](#highlights)
- [Quick start](#quick-start)
- [Adding models](#adding-models)
- [MTP / speculative decoding](#mtp--speculative-decoding)
- [Chat, tools and attachments](#chat-tools-and-attachments)
- [Themes and settings](#themes-and-settings)
- [Local API server](#local-api-server)
- [Build from source](#build-from-source)
- [Project structure and native dependencies](#project-structure-and-native-dependencies)
- [Privacy, permissions and security](#privacy-permissions-and-security)
- [Limitations and validation status](#limitations-and-validation-status)
- [Documentation](#documentation)

## Highlights

### On-device inference and model management

- Run compatible GGUF models locally through the BigMoeOnEdge native engine; internet is not needed for ordinary local text generation after the model is on the device.
- Stream output token by token, stop a generation, keep persistent chat history, and continue existing conversations.
- Inspect per-message generation telemetry such as tokens/second, prompt prefill time, token counts and cache-related information.
- Detect/load supported MoE and ordinary dense GGUF models with pre-flight checks and configurable context, sampler, compute-thread, I/O-lane, expert-cache, dense-weight, prefetch/routing and expert-streaming controls.
- **Add GGUF from storage (no copy)** is the primary model picker flow. It opens Android's document picker and registers a real shared-storage path when the provider and Android permissions allow it. No second model copy is made.
- **Import GGUF file(s) (copy)** remains available when a real path cannot be resolved, or when app-private storage is preferred. Import supports selecting a complete numbered split-GGUF set together, validates shard numbering and metadata, and presents the set as one model.
- A manual absolute-path registration option remains under Advanced for troubleshooting.
- Model and matching `mmproj` projector paths are managed separately for multimodal models.

### Chat and rendering

- Saved conversations, per-chat draft and pending-attachment persistence, message copy/edit actions, and a stop-generation control.
- Assistant responses use the available conversation width; user messages remain compact and right-aligned. On portrait screens, a hamburger menu opens chat history. On wide screens, the history panel remains visible beside the conversation.
- Markdown headings, paragraphs, bold/italic/strike, inline and fenced code, block quotes, numbered and nested bullet lists, horizontal rules, links, and pipe tables.
- Theme-aware list markers and numbered badges; wide tables can scroll horizontally.
- Readable best-effort math conversion for common LaTeX syntax without bundling a TeX renderer. This is an approximation, not full mathematical typesetting; unknown commands are preserved rather than silently removed.
- Syntax-highlighted code blocks, copy-code actions, and a code/artifact viewer. Generated HTML/SVG/Mermaid can be previewed; Markdown can be rendered; common programming-language artifacts remain copyable/read-only unless a supported preview applies.
- Streaming UI updates and auto-scroll are coalesced to reduce avoidable interface work during long generations. This is a UI-efficiency change, not a promise of a specific tokens/second increase.

### Optional tools and multimodal input

Tools are opt-in and start **off** on a fresh install:

- **Files / workspace:** list, read, search, create, edit, rename, delete, create folders/projects, and export ZIPs. Destructive actions request approval. File operations are restricted to the app's private workspace.
- **Code:** search/analyse code and run JavaScript in a sandboxed WebView with no network/file access and a time limit. JavaScript runs only after the user presses Run.
- **Utilities:** offline date/time, calculator, unit conversion, random numbers and device information.
- **Web search:** can use Brave (optional API key), SearXNG (optional HTTPS endpoint), DuckDuckGo HTML, then Wikipedia as a fallback. The globe button controls web access; when enabled, the search mode determines whether the app searches every message or allows the model to request a search.
- **Image search:** optional `image_search` queries Wikimedia Commons. Results appear as a two-column thumbnail-card grid with a source-page link and available creator/licence details. It is controlled by the same globe/Web Search toggle and is off on a fresh install.
- **Web-page reading/extraction:** available through the opt-in web tools.
- **Choose from options (`--decide`):** optionally offer a bounded set of choices in the chat composer and let the model select one.
- **Thinking/reasoning display:** an optional thinking toggle. It is off by default.
- **Image attachments and video attachments:** selectable through the system picker. A compatible vision model plus matching `mmproj` is required for image understanding. Video is handled as evenly sampled image frames; it is not a native video encoder path.
- **Archive/document attachments:** ZIP/JAR, TAR, GZ/TAR.GZ, DOCX, XLSX, PPTX, ODT, ODS, ODP and EPUB can be inspected as bounded text context. PDF, 7z and RAR are not bundled as supported archive formats.

### Modern appearance

- Twelve featured presets in a shorter picker: **Paper, Sand, Sage, Catppuccin Latte, Pearl, Ocean, ChatGPT Dark, Claude Warm, Gemini Night, Midnight, AMOLED Black and Coffee**.
- Other legacy presets remain in the palette registry so previously saved theme IDs keep working; the shorter picker is not destructive deletion.
- Create and edit custom palettes with nine editable colours. Contrast checks are included in the palette logic and tests.
- Settings are organised into clearer sections, with the engine/performance group collapsible. Controls were rearranged, not removed.

## Quick start

1. Install a build compatible with your device and open **Models**.
2. Choose **Add GGUF from storage (no copy)**, browse to **Internal storage**, and select a GGUF model. The app may ask for special all-files access on Android 11+ before it can register the shared-storage path.
3. If the selected provider cannot supply a real file path, choose **Import GGUF file(s) (copy)** instead. This consumes app-private storage approximately equal to the copied model size.
4. Tap **Load**, inspect the pre-flight warnings, choose any needed load-time settings, then load the model.
5. Return to Chats and send a prompt. Only enable the globe, tools, thinking, MTP, or other optional capabilities when you need them.

### Fresh-install defaults

- System prompt: **blank**.
- Tools: **Files, Code, Utilities, Web Search and Image Search are off** until enabled. Image Search follows the globe/Web Search toggle.
- Thinking: **off**.
- Guess-ahead/speculative decoding: **off** until explicitly selected.
- Tokens to generate: **unlimited by an explicit output-token cap**, stopping when the model reaches the available context or another stopping condition. This does **not** mean infinite context or unlimited RAM.
- Automatic loading of the last model: off.
- Local API server: off; listens on loopback by default when enabled.

These are fresh-install/default values only. Existing saved preferences are preserved and are not silently overwritten by an update.

## Adding models

### No-copy picker (recommended when the file is already on the device)

**Models → Add GGUF from storage (no copy)** opens the system document picker. For common Android shared-storage locations, the app resolves the selected document to a real filesystem path and registers it without copying the model. This is most useful when a multi-gigabyte GGUF already lives in internal shared storage.

- On Android 11+, the app may route you to the system's **Allow access to manage all files** screen and retry when you return.
- The app's native engine requires a real filesystem path. A `content://` URI from a cloud or virtual provider may not provide one; use the copy/import picker for those sources.
- Shared/emulated storage can use buffered I/O and may be slower than app-private storage on some devices. No-copy saves storage; it does not guarantee faster inference.
- Do not rename or move a registered model after adding it unless you register the new path.

### Copy import and split GGUF

Use **Import GGUF file(s) (copy)** for provider locations that cannot be registered directly or if you want the app to own a private copy. Allow enough free app storage for the entire model.

For a split model, select the complete numbered set in one multi-selection (`-00001-of-000NN.gguf` through the last shard). The importer validates the set and lists it as one target model; rename and delete operations apply to the in-app shard set. Do not import only a later shard as if it were a standalone model.

### Separate MTP draft sidecar

A compatible MTP-only GGUF is an optional draft sidecar, not an ordinary target model. Import/attach it from **Models → MTP draft sidecars**, then choose **Guess ahead → MTP** and enable **Use a separate MTP GGUF**. See [`docs/QWEN4EXP_MTP_SEPARATE_DRAFT.md`](docs/QWEN4EXP_MTP_SEPARATE_DRAFT.md) before preparing a sidecar.

**Current boundary:** the separate sidecar path is supported for the `qwen4exp` architecture. The main target MoE model keeps Expert Streaming enabled, but the sidecar's own weights use ordinary mmap and are **not** routed through the target model's expert-stream source. This can increase memory/page-cache use. If sidecar loading fails or is incompatible, the app falls back to normal generation with speculation disabled and shows a warning; speed-up is not guaranteed.

## MTP / speculative decoding

The Guess-ahead section offers:

- **Off** — ordinary decoding.
- **Model's own head (MTP)** — use an MTP head supported by the loaded model, or optionally a separate compatible MTP sidecar.
- **Repeated text (n-gram)** — predict repeated token sequences where the prompt/output contains useful repetition.

The app verifies guessed tokens against the target model, but real-world speed-up depends on how many guesses are accepted, the model, sampling mode, RAM pressure, storage and phone hardware. Some speculative modes require greedy decoding. Benchmark the same model, prompt, context and settings before comparing speeds. For sidecar conversion instructions and limits, see [`docs/QWEN4EXP_MTP_SEPARATE_DRAFT.md`](docs/QWEN4EXP_MTP_SEPARATE_DRAFT.md).

## Chat, tools and attachments

### Attachments and multimodal models

- Text/code attachments add bounded, filename-aware context. Attachments up to 256 MB can be selected; files over 8 MB show a slowdown warning. For plain text above 8 MB, the full file can be saved but only the first 8 MB is read into model context. Archive extraction is bounded to 1 MB per entry, 24 MB total and 5,000 entries; unsafe archive paths are skipped. Media selected in the gallery is copied into app-private storage during preparation, so broad `READ_MEDIA_*` permission is not required.
- Image and video controls are gated by a loaded compatible vision model and projector (`mmproj`). Settings expose maximum image dimension and number of video frames sampled.
- A model without a compatible projector cannot perform the app's image-understanding path. Image/video runtime should be validated with the target model/device; video uses sampled frames and can be memory/context intensive.
- After an image turn, the engine may clear text KV state because image embeddings are not represented in the app's text-token mirror; subsequent turns replay textual history but do not reconstruct prior image embeddings.

### Workspace and artifacts

The workspace contains chat-linked files and artifacts. The file viewer supports browsing, opening, editing, saving, copying, sharing, downloading and deleting with confirmation. HTML/SVG/Mermaid previews are sandboxed; JavaScript can run only on explicit user action in an isolated, offline WebView. Other code artifacts are displayed with syntax highlighting and can be copied but are not executed by the app.

### Web and image search

The globe icon enables/disables network tools. With it disabled, the app does not invoke web or image search. When enabled:

- **Every message** searches the user's message before generation; this is generally more reliable for smaller models.
- **Only when the model asks** exposes web tools in the tool protocol; smaller models may never request them.
- Image search uses Wikimedia Commons and shows the source URL plus available attribution/licensing metadata. External network access is required to search and fetch thumbnails.

Tool calling is prompt-based rather than grammar-constrained, so smaller models may produce invalid tool-call syntax. The app has parser recovery, limits tool rounds/calls, reports failures to the model, and uses timeouts/result-size caps.

## Local API server

An optional local HTTP server exposes OpenAI Chat Completions-compatible (`/v1/chat/completions`) and Anthropic Messages-compatible (`/v1/messages`) endpoints over the same loaded model session. It supports streaming, API-key authentication, a request queue and settings for port/context/thinking/output limits. It is off by default, binds to `127.0.0.1` by default and requires authentication when configured to listen on the network. The stateless API path currently replaces image inputs with a placeholder; do not assume API image/vision support. MTP/n-gram, tool parsing, cancellation and prefix reuse should be device-tested before production use. See [`docs/API_SERVER.md`](docs/API_SERVER.md).

## Build from source

### Requirements

- Android Studio (Koala or newer recommended)
- JDK 17
- Android SDK Platform 35 and Build Tools 35.0.0
- Android NDK `27.0.12077973`
- CMake `3.22.1`
- Internet access for the first Gradle sync/dependency resolution

The native build compiles llama.cpp and the vendored engine and may take a while. CMake builds the native engine in Release mode even when producing a debug APK to avoid the substantial performance penalty of an unoptimised native library.

```bash
# Phone build (arm64-v8a)
./gradlew assembleDebug --no-daemon

# Optional emulator ABI too
./gradlew assembleDebug -PbmoeAbis=arm64-v8a,x86_64 --no-daemon

# JVM unit tests
./gradlew testDebugUnitTest --no-daemon

# Instrumentation tests (device/emulator required)
./gradlew connectedDebugAndroidTest --no-daemon
```

To make the debug variant debuggable for a debugger, use `-PbmoeDebuggable=true`. It is not debuggable by default because CheckJNI/ART debug behavior slows token callbacks and UI work.

### Optional Qualcomm Hexagon / NPU build

The normal build is CPU-only and does not need a Qualcomm Hexagon SDK. An optional NPU APK is produced by `.github/workflows/android-npu.yml` through a separate Snapdragon toolchain build, then packaged with `-PbmoePrebuiltNative=true`. The NPU controls are available only when the packaged native engine reports `BMOE_HAVE_HEXAGON`; they remain unavailable in a CPU-only build. NPU prefill is off by default, and hardware/backend compatibility must be verified on a supported device.

## Project structure and native dependencies

- `app/src/main/java/com/bigmoe/onedge/` — Kotlin app: chat UI, settings, model management, engine controller, parser, tools, workspace and API server.
- `app/src/main/cpp/native-bridge.cpp` — JNI bridge.
- `app/src/main/cpp/core/` — vendored BigMoeOnEdge **0.28.0** core plus local patches for saved chat context/unlimited generation/rollback, multimodal image support, the local API/stateless chat path, context/token telemetry and speculative-tail handling.
- `app/src/main/cpp/third_party/llama.cpp/` — vendored llama.cpp snapshot. It is not a git submodule; the app contains local integration changes and the upstream fork used for I/O/compute overlap differs from the stock snapshot.
- `patches/` — re-applicable native engine patches (`0001` saved-chat context/unlimited generation, `0003` multimodal image path, `0004` stateless API/tool/sampling path and `0005` context/KV/speculative-tail reporting). `0002` documents the expert-ready hook already carried in the vendored llama.cpp tree and is not reapplied as part of the engine-core port.
- `scripts/kt_lint.py` — offline Kotlin sanity checker; it is not a compiler.
- `scripts/build-hexagon-android.sh` and related workflow scripts — optional NPU build path.

The repository vendors native dependencies instead of fetching them as Git submodules during build. Review the licenses and upstream notices in the source trees before redistributing binaries.

## Privacy, permissions and security

- Ordinary text generation is local. Web search, image search and webpage tools make network requests only when their feature is enabled.
- The no-copy shared-storage flow uses Android document-provider/path resolution. Absolute-path registration may request `MANAGE_EXTERNAL_STORAGE` (“Allow access to manage all files”) on Android 11+; that is a broad, restricted permission and may not be allowed for every Google Play distribution use case. Review current Play policy before publishing there. The copy-import path is the alternative where direct access is unavailable.
- File tools are confined to the private workspace and ask for approval before destructive operations. JavaScript artifact execution is isolated, offline and user-triggered; it has a runtime limit.
- The local API server is disabled by default. Keep it on loopback unless you intentionally need LAN access; when exposed to the network, use authentication and understand that other devices may reach the endpoint.
- User model files can be many gigabytes; check free storage/RAM and the pre-flight warnings before loading. A larger context increases memory use, especially with vision, a large KV cache or API requests.

## Limitations and validation status

- **Release gate:** this source tree has received static checks and selected host-side smoke/syntax tests, but the v0.4.0 APK still needs a successful Android SDK/NDK build, JVM/unit tests, and real-device tests. See `docs/STATUS.md` and the release checklist in `docs/RELEASE_NOTES_0.4.0.md`.
- Voice input is not implemented. Tool-call JSON is not grammar-constrained; recovery helps but cannot guarantee every model follows the protocol.
- Separate MTP sidecars currently support `qwen4exp` and their own weights use mmap instead of the target's expert-stream path. Do not assume sidecar expert streaming or a speed-up.
- No-copy registration works only when Android's provider exposes a compatible real filesystem path and required access is granted; cloud/virtual providers may require copy import. Shared storage can be slower than app-private storage.
- Image/video inference, mmproj integration, server compatibility, speculation acceptance/speed-up and the new UI behavior require runtime/device testing. Video is sampled frames, not native video-token inference.
- The math renderer is best-effort Unicode conversion, not full TeX rendering. Markdown links are displayed but are not guaranteed to be tappable.
- The local API does not currently perform vision inference on image payloads; image content is represented by a placeholder on the stateless API path.
- Mermaid preview may load its renderer from jsDelivr; normal artifact pages use network-blocking sandbox rules.

## Documentation

- [`CHANGELOG.md`](CHANGELOG.md) — version history.
- [`docs/RELEASE_NOTES_0.4.0.md`](docs/RELEASE_NOTES_0.4.0.md) — complete v0.4.0 release notes and pre-publish checklist.
- [`docs/STATUS.md`](docs/STATUS.md) — validation status and remaining device checks.
- [`docs/model-loading.md`](docs/model-loading.md) — model files, no-copy picker, storage permissions and loading settings.
- [`docs/QWEN4EXP_MTP_SEPARATE_DRAFT.md`](docs/QWEN4EXP_MTP_SEPARATE_DRAFT.md) — MTP sidecar conversion/usage and limitations.
- [`docs/tool-calling.md`](docs/tool-calling.md) and [`docs/tools.md`](docs/tools.md) — web search, tools, protocol and extension guide.
- [`docs/CHAT_RENDERING.md`](docs/CHAT_RENDERING.md) — Markdown, math and artifact rendering details.
- [`docs/API_SERVER.md`](docs/API_SERVER.md) — local API endpoints, authentication, queue and compatibility.
- [`docs/multimodal-feasibility.md`](docs/multimodal-feasibility.md) — image/video model requirements and limitations.
- [`docs/npu-android-build.md`](docs/npu-android-build.md) — optional Hexagon/NPU build path.
- [`docs/CI_BUILD_FIX_2026-10-15.md`](docs/CI_BUILD_FIX_2026-10-15.md) — workflow build-path fix.
