<p align="center">
  <img src="docs/assets/logo.png" alt="BigMoeOnEdge+" width="240">
</p>

<h1 align="center">BigMoeOnEdge+</h1>

<p align="center">
  A feature-rich Android client for running local LLMs — especially large Mixture-of-Experts models — on-device.
</p>

<p align="center">
  <a href="https://github.com/hamodexd217/BigMoeOnEdge-plus/releases/latest">Download</a>
  ·
  <a href="https://github.com/hamodexd217/BigMoeOnEdge-plus/issues">Issues</a>
  ·
  <a href="docs/STATUS.md">Project status</a>
</p>

<p align="center">
  <a href="https://github.com/hamodexd217/BigMoeOnEdge-plus/releases"><img src="https://img.shields.io/github/v/release/hamodexd217/BigMoeOnEdge-plus?display_name=tag" alt="Latest release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/hamodexd217/BigMoeOnEdge-plus" alt="License"></a>
  <a href="https://github.com/hamodexd217/BigMoeOnEdge-plus/stargazers"><img src="https://img.shields.io/github/stars/hamodexd217/BigMoeOnEdge-plus" alt="GitHub stars"></a>
</p>

---

## What is BigMoeOnEdge+?

**BigMoeOnEdge+** is an Android chat application built around the **BigMoeOnEdge** native inference engine.

The goal is to make running local models on Android feel more like a complete AI application instead of a minimal model runner.

It combines local inference with persistent conversations, reasoning, web search, tools, attachments, multimodal input, artifacts, workspace features, and generation telemetry.

The project is based on **BigMoeOnEdge 0.28.0**, with additional Android-side features and project-specific engine patches.

## Demo

A quick look at the artifact workflow:

![Artifacts demo](docs/assets/demo-artifacts.gif)

Additional screenshots and demo media are available in [`docs/assets`](docs/assets).

## Screenshots

<p align="center">
  <img src="docs/assets/models.png" alt="BigMoeOnEdge+ model manager" width="900">
</p>

The model manager supports GGUF models and `mmproj` files, with automatic detection for Mixture-of-Experts and regular dense models.

## Highlights

| Area | BigMoeOnEdge+ |
| --- | --- |
| Local inference | GGUF models through the native BigMoeOnEdge engine |
| Model support | Mixture-of-Experts and dense models with automatic detection |
| Conversations | Persistent Room-backed chats |
| Generation | Live token streaming and stop control |
| Reasoning | Streaming "Thinking" / reasoning display |
| Web | Web search integrated into the chat workflow |
| Tools | File, code, web and utility tools with approval flow |
| Attachments | Text, code, archives, documents, images and video |
| Vision | `mmproj` support for multimodal models |
| Artifacts | HTML, SVG, Mermaid, Markdown and highlighted source-code artifacts |
| Workspace | Browse, open, edit, save, copy, share and download generated files |
| Chat controls | Edit/copy messages and copy raw code blocks |
| Telemetry | Generation statistics such as tokens/s, prefill and cache information |
| Settings | Context, sampling, expert-cache, media and tool controls |
| Wide screens | Persistent chat-history panel |
| Optional acceleration | Separate Hexagon/NPU build path for supported Snapdragon environments |

## Model support

BigMoeOnEdge+ loads `.gguf` models through the Models screen.

Models used during development include:

- Qwen3.5 4B Q4
- Qwen3.5 9B Q5
- Qwen3.6 35B-A3B Q4 MTP

The application is not limited to these models. MoE models are detected automatically, while regular dense GGUF models can use the normal loading path.

For multimodal models, the required `mmproj` projector can be imported separately.

### Performance notes

There is no single "BigMoeOnEdge+ speed" number.

Generation speed depends on the model, quantization, context size, storage speed, cache settings, device hardware, and thermal conditions. Tools and web search can add additional latency.

**Some feature tests intentionally use a smaller model to make the test finish faster.** Those tests demonstrate that the feature works and are **not intended to represent the performance of a large model**.

Web search, tool use, artifact generation, and complex reasoning can also take significantly longer than a normal short text generation — especially with large local models.

## Download

The latest release provides a ready-to-install Android APK:

**[Download the latest APK](https://github.com/hamodexd217/BigMoeOnEdge-plus/releases/latest)**

Current release:

**[BigMoeOnEdge+ v0.2.0](https://github.com/hamodexd217/BigMoeOnEdge-plus/releases/tag/0.2.0)**

## Requirements

### Running the app

- Android 10 or newer
- Enough RAM and storage for the selected model
- A compatible model and `mmproj` for vision/video use
- Network access for web search

### Building from source

- Android Studio (Koala or newer)
- Android SDK 35
- JDK 17
- Android NDK
- CMake 3.22.1
- Internet access for the initial Gradle dependency sync

The native engine is built in **Release mode even for debug APKs** because an unoptimized native build is impractical for real inference.

## Build

Clone the repository:

```bash
git clone https://github.com/hamodexd217/BigMoeOnEdge-plus.git
cd BigMoeOnEdge-plus
```

Build the normal ARM64 APK:

```bash
./gradlew assembleDebug
```

For ARM64 + x86_64:

```bash
./gradlew assembleDebug -PbmoeAbis=arm64-v8a,x86_64
```

Run JVM tests:

```bash
./gradlew testDebugUnitTest
```

Run Android instrumentation tests with a connected device or emulator:

```bash
./gradlew connectedDebugAndroidTest
```

The project is also built through **GitHub Actions**, and the application has been tested on real Android hardware during development.

## Loading models

1. Open **Models**.
2. Tap **Choose `.gguf` (model or mmproj)**.
3. Select the model or projector.
4. Load it.

Models are copied into app-private storage because the native engine requires a real filesystem path.

For debug builds, models can also be pushed with `adb` into the app's model directory and then refreshed from the Models screen.

## Multimodal models

Vision-capable models use an `mmproj` projector.

The app supports importing the model and projector separately, discovering projector candidates, and selecting the correct projector during loading.

Image and video support depends on the exact model, projector, and device. Video input is handled as sampled visual frames rather than continuous native video understanding.

## Web search and tools

Web search and tools are integrated into the normal chat workflow.

Keep in mind:

- Web features require network access.
- Search and tool calls can take noticeable time.
- Tool execution can require additional model reasoning.
- Large local models can make these operations considerably slower.
- Some development tests use a smaller model simply to shorten the test cycle.

This is intentional: the project aims to expose useful local-AI capabilities without pretending that large on-device inference is instant.

## Artifacts and workspace

Generated files and code can be handled as artifacts instead of being left as plain chat text.

Supported artifact behavior includes:

- HTML / SVG / Mermaid previews
- Rendered Markdown
- Highlighted source-code artifacts
- Copyable code
- Offline JavaScript execution when explicitly requested
- Workspace browsing and file management
- Saving, editing, sharing and downloading files

Common source formats such as Kotlin, Java, Python, C/C++, JavaScript/TypeScript, JSON, YAML, shell, Dockerfile and SQL are preserved as code artifacts.

Languages that require an external interpreter, such as Python, are not executed by the app.

## Chat and attachments

BigMoeOnEdge+ supports persistent conversations through Room, along with:

- Message editing
- Message copying
- Raw code-block copying
- Persistent per-chat drafts
- Text and code attachments
- Images and video
- ZIP/JAR/TAR/GZ archives
- DOCX/XLSX/PPTX and other supported document formats
- File navigation and workspace operations

Attachment processing uses safety and size limits so very large files do not silently consume excessive memory.

## Native engine

The repository vendors its native dependencies rather than relying on a git submodule.

At a high level:

- `app/src/main/cpp/core` contains the vendored BigMoeOnEdge 0.28.0 engine plus local patches.
- `app/src/main/cpp/third_party/llama.cpp` contains the vendored llama.cpp snapshot used by the project.
- `EngineNativeBridge.kt` communicates with the native engine through JNI.
- `patches/` contains project-specific native changes in re-applicable patch form.

The optional Hexagon/NPU path is separate from the normal CPU build and is intended for environments where the required Snapdragon toolchain is available.

## Project structure

```text
app/
  src/main/java/com/bigmoe/onedge/
    core/       engine, generation, model management, context
    parser/     markdown, code fences, artifact languages
    tools/      web, file and utility tools
    data/       Room persistence and settings
    ui/         Compose UI
  src/main/cpp/
    core/       BigMoeOnEdge engine + local patches
    third_party/llama.cpp
docs/
  STATUS.md
  model-loading.md
  tool-calling.md
  multimodal-feasibility.md
  ...
patches/
scripts/
.github/workflows/
```

## Testing and status

The project has been built through GitHub Actions and tested on real Android hardware.

Development testing covers areas including:

- Model loading and generation
- Persistent Room conversations
- Web search
- Thinking/reasoning
- Tools
- Text and code attachments
- Artifacts
- Code blocks
- Vision and video flows
- Model/projector loading
- Chat persistence
- Workspace operations

The complete implementation notes, test checklist, and remaining known limitations are maintained in [`docs/STATUS.md`](docs/STATUS.md).

## Known limitations

BigMoeOnEdge+ is an actively developed project, so some behavior still depends on the model and device.

- Inference performance varies heavily between devices and models.
- Web search requires a network connection.
- Web search and tools may take a long time with large local models.
- Multimodal behavior depends on the exact model + `mmproj` combination.
- Voice input is not part of this project.
- Grammar-constrained tool calls are not part of this project.
- Some engine/device combinations may need additional real-device validation.
- Video input uses sampled frames rather than continuous video understanding.

## Credits

BigMoeOnEdge+ builds on work from:

- [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge)
- [llama.cpp](https://github.com/ggml-org/llama.cpp)
- Jetpack Compose, Room, DataStore and the Android ecosystem

The native engine and vendored components retain their respective licenses and notices.

## License

BigMoeOnEdge+ is licensed under the **Apache License 2.0**.

See [`LICENSE`](LICENSE).

---

<p align="center">
  Built for local AI on Android.
</p>
