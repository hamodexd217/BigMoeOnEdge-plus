# BigMoeOnEdge+ (BigMoeOnEdgeExtended)

Android chat app (Kotlin, Jetpack Compose, Room, DataStore) that runs large Mixture-of-Experts LLMs on the
phone through [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge) (Apache-2.0), which streams experts from
flash. The app talks to the engine through JNI (`EngineNativeBridge.kt` ↔ `app/src/main/cpp/native-bridge.cpp`)
using the engine's public `bmoe::Session` API.

**Read `docs/STATUS.md` first**: it lists what was actually verified in the sandbox and what still needs a real Android device.
The sandbox has no Android SDK/NDK and cannot resolve Gradle dependencies, but pure Kotlin host smoke tests and native C++
syntax checks were run.

## Features
Saved conversations (Room) · live token streaming · stop button · model picker/import/load with pre-flight checks ·
reasoning ("thinking") block · web search · file/code/web tools with approval · workspace and persistent artifacts ·
attachments (text/code/image/video) · multimodal mmproj support · Markdown/code blocks with syntax highlighting ·
artifact preview (HTML/SVG/Mermaid, sandboxed WebView) · settings for context, sampling, expert cache, dense-weights mode,
threads, tool switches, and media sizing · per-message engine telemetry (tokens/s, prefill, cache hit).
Known limitations: voice input and grammar-constrained tool calls are not part of this project; image/video runtime still needs
device validation (see STATUS.md). The 0.28 pass also adds Choose-from-options (`--decide`), optional Hexagon/NPU prefill
and loader controls, automatic MoE / regular-model detection, utility tools, persistent per-chat drafts, and a permanent 344dp chat-history
panel on wide screens. Photo/video picking uses the system gallery picker with an `ACTION_GET_CONTENT` fallback and copies
selected media into app-private storage immediately, so no `READ_MEDIA_*` permission is needed.

Code artifacts are not HTML-only: the artifact pipeline preserves common languages such as Kotlin, Java, Python, C/C++,
JavaScript/TypeScript, JSON, YAML, shell, Dockerfile, SQL and others when saving generated code.


## Build
Requirements: Android Studio (Koala+) with SDK 35, **NDK** and **CMake 3.22.1** (SDK Manager → SDK Tools), JDK 17,
internet access for the first Gradle sync.

```bash
./gradlew assembleDebug              # arm64-v8a only (phones)
./gradlew assembleDebug -PbmoeAbis=arm64-v8a,x86_64   # also an x86_64 emulator image
./gradlew testDebugUnitTest          # JVM unit tests
./gradlew connectedDebugAndroidTest  # Room tests, needs a device/emulator
```
The first native build compiles all of llama.cpp + the engine and takes a while. The native build is forced to
Release even for the debug APK (an unoptimised engine is unusably slow).

## Layout
* `app/src/main/java/com/bigmoe/onedge` — `core/` (engine controller, agent loop, context planner, model manager),
  `parser/`, `tools/`, `data/`, `ui/`.
* `app/src/main/cpp` — `native-bridge.cpp` (JNI), `core/` (**vendored BigMoeOnEdge 0.28.0 + local patches**),
  `third_party/llama.cpp` (**vendored upstream snapshot**, see below).
* `patches/` — changes made to the vendored engine, as re-appliable patches.
* `docs/` — `STATUS.md`, `prompt-and-kv-design.md`, `tool-calling.md`, `model-loading.md`,
  `multimodal-feasibility.md`, `bmoe_real_api_notes.md` (verified upstream API), `AUDIT.md` (phase-0 audit),
  `HISTORY-old-readme.md` (the previous README, kept for reference).
* `scripts/kt_lint.py` — offline sanity checker for the Kotlin sources (not a compiler).
* `scripts/build-hexagon-android.sh` — optional upstream Hexagon/NPU toolchain recipe; normal Gradle builds keep it OFF.

## Native dependencies (important)
No network was available while this was assembled, so upstream is **vendored, not a git submodule**:
* `app/src/main/cpp/core` = BigMoeOnEdge v0.28.0 `core/`, then local patches `0001` (saved-chat context / unlimited
  generation / rollback fixes) and `0003` (mtmd multimodal image path). Upstream 0.28 `thinking_control.cpp` and the
  upstream `pos0` fix are retained; no old `thinking_end_tag` compatibility shim is carried forward.
* `app/src/main/cpp/third_party/llama.cpp` = upstream ggml-org/llama.cpp (965f897, 2026-09-26). Upstream
  BigMoeOnEdge pins the **Helldez fork** (`bmoe/expert-ready-hook`); with stock llama.cpp the engine works but
  I/O–compute *overlap* is unavailable (the bridge turns it off automatically).
The vendored llama.cpp tree is intentionally left untouched during the 0.28 engine port. The app keeps its existing
965f897-era tree and only the engine above it is upgraded. No new CMake compatibility shim is needed for the 0.28 core
against this llama.cpp snapshot.

## Models
Put a MoE `.gguf` on the device via the app (Models screen → Choose file) or
`adb push model.gguf /data/data/<pkg>/files/models/` (debug builds: `run-as`). Files must live on a real
filesystem path because the engine reads experts with `O_DIRECT`.

## Optional Hexagon / NPU APK

The normal `assembleDebug` path stays CPU-only and requires no Qualcomm/Hexagon SDK. The NPU path is deliberately separate so a machine without the Snapdragon toolchain cannot accidentally ship an APK whose UI advertises an unavailable backend.

The real NPU APK is built in two steps by `.github/workflows/android-npu.yml`:

1. `scripts/build-hexagon-onedge-engine.sh` runs inside upstream's pinned Snapdragon toolchain image and builds the actual `libonedge-engine.so` with `GGML_HEXAGON=ON`, plus HTP DSP skels v73/v75/v79/v81.
2. Gradle is run with `-PbmoePrebuiltNative=true`. That mode verifies the required JNI payload before the build and packages the prebuilt native engine unchanged.

The app's NPU switch is enabled only when the loaded `libonedge-engine.so` reports `BMOE_HAVE_HEXAGON`. On a normal CPU-only APK the switch remains unavailable rather than pretending the device is using the NPU.
