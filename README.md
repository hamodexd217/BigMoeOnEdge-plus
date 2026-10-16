<p align="center">
  <img src="docs/assets/logo.png" alt="BigMoeOnEdge+" width="240">
</p>

<h1 align="center">BigMoeOnEdge+</h1>

<p align="center">
  An Android chat app for running local GGUF models on-device, especially large Mixture-of-Experts models.
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
# BigMoeOnEdge+ (BigMoeOnEdge Plus / BigMoeOnEdge-plus)

> BigMoeOnEdge+ is a modified/enhanced fork of [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge).

> **Disclosure:** this fork was VERY HIGHLY developed with the help of AI tools.

> **Project update:** v0.4.0. Check the repository's Releases page for the current published APK and release status.
> 
## What is BigMoeOnEdge+?

BigMoeOnEdge+ is an Android chat application built on the **BigMoeOnEdge** native inference engine (version 0.28.0).

The engine is designed for Mixture-of-Experts models whose weights are read from flash storage during generation, which makes models larger than the device's RAM practical to run. The app wraps that engine in a complete chat experience: persistent conversations, reasoning display, web search, tools, attachments, multimodal input, artifacts, a workspace, and generation telemetry.

Dense GGUF models are supported through the normal loading path.

## Demo

A quick look at the artifact workflow:



![Artifacts demo](docs/assets/demo-artifacts.gif)



More screenshots and demo media are in [`docs/assets`](docs/assets).

## Screenshots

<p align="center">
  <img src="docs/assets/models.jpg" alt="BigMoeOnEdge+ model manager" width="900">
</p>

The model manager loads GGUF models and `mmproj` projector files, and detects whether a model is Mixture-of-Experts or dense.

## Highlights

| Area | Details |
| --- | --- |
| Local inference | GGUF models through the native BigMoeOnEdge engine |
| Model support | Mixture-of-Experts and dense models, with automatic detection |
| Conversations | Persistent Room-backed chats, with per-chat drafts |
| Generation | Live token streaming and a stop control |
| Reasoning | Streaming "Thinking" / reasoning display |
| Web | Web search integrated into the chat workflow, with optional Wikimedia Commons image search when web search is enabled |
| Tools | File, code, web, and utility tools behind an approval flow |
| Attachments | Text, code, archives, documents, images, and video |
| Vision | `mmproj` support for multimodal models |
| Artifacts | HTML, SVG, Mermaid, Markdown, and highlighted source code; improved Markdown list and table rendering |
| Workspace | Browse, open, edit, save, copy, share, and download generated files |
| Chat controls | Edit and copy messages; copy raw code blocks; improved chat spacing and assistant response width |
| Telemetry | Per-message statistics such as tokens/s, prefill, and cache information |
| Settings | Context, sampling, expert cache, I/O and compute overlap, media, and tool controls; streamlined defaults |
| Wide screens | Persistent chat-history panel |
| Local API server | Optional on-device server (Anthropic Messages + OpenAI chat APIs) so other apps, such as Claude Code, can use the loaded model |
| Optional acceleration | Separate Hexagon/NPU build path for supported Snapdragon environments |

## What's new in v0.4.0

This update focuses on the chat experience, themes, model selection, and optional visual search:

- **Chat layout:** improved chat spacing and assistant responses that use the available width more consistently.
- **Markdown display:** improved rendering for Markdown lists and tables.
- **Themes:** additional premium-style theme choices.
- **Image search:** optional Wikimedia Commons image search, gated by the web-search setting.
- **Model loading:** updated model-selection/loading workflows intended to reduce unnecessary copying when the selected file has a usable filesystem path and Android permissions allow access.
- **MTP sidecar:** support for selecting a separate MTP sidecar where applicable; behavior depends on the model and device.
- **Settings defaults:** blank system prompt by default, tools disabled by default, and an unlimited-token setting option.

Some Android document providers expose files as `content://` URIs instead of ordinary filesystem paths. Direct file access therefore depends on the provider, Android permissions, and the selected model's location; the import/copy workflow may still be necessary.

Validate these features with your target device and model before treating them as production-verified.

## Model support

Load `.gguf` models from the Models screen.

Models used during development include:

- Qwen3.5 4B Q4
- Qwen3.5 9B Q5
- Qwen3.6 35B-A3B Q4 MTP

The app is not limited to these. MoE models are detected automatically, and regular dense GGUF models use the standard loading path. For multimodal models, import the `mmproj` projector separately.

## Performance notes

There is no single "BigMoeOnEdge+ speed" number.

Generation speed depends on the model, quantization, context size, storage speed, cache settings, device hardware, and thermal conditions. Web search and tool use add latency on top of generation.

**I/O and compute overlap:** the Settings screen has an *I/O and compute overlap* switch. When enabled, the engine issues the next weight reads while the current layer is computing, so flash latency is hidden behind the work. Its effect depends on the model and device, so compare tokens/s with the switch on and off on your own hardware.

Some feature tests intentionally use a smaller model so they finish faster. Those tests show that a feature works. They do not represent the performance of a large model.

## Download

The latest release includes an installable Android APK:

**[Download the latest APK](https://github.com/hamodexd217/BigMoeOnEdge-plus/releases/latest)**

Current release: **BigMoeOnEdge+ v0.4.0** (check the repository's Releases page for the published tag and APK).

The app version and engine version (BigMoeOnEdge 0.28.0) are versioned separately.

## Requirements

### Running the app

- Android 10 or newer
- Enough RAM and storage for the selected model
- A compatible model and `mmproj` file for vision or video input
- A network connection for web search

### Building from source

- Android Studio (Koala or newer)
- Android SDK 35
- JDK 17
- Android NDK
- CMake 3.22.1
- Internet access for the first Gradle dependency sync

Native code is built in **Release mode even for debug APKs**, because an unoptimized native build is too slow for real inference.

## Build

Clone the repository:

```bash
git clone https://github.com/hamodexd217/BigMoeOnEdge-plus.git
cd BigMoeOnEdge-plus
```

Build the default ARM64 debug APK:

```bash
./gradlew assembleDebug
```

Build for ARM64 and x86_64:

```bash
./gradlew assembleDebug -PbmoeAbis=arm64-v8a,x86_64
```

Run JVM unit tests:

```bash
./gradlew testDebugUnitTest
```

Run instrumentation tests on a connected device or emulator:

```bash
./gradlew connectedDebugAndroidTest
```

## Loading models

1. Open **Models**.
2. Tap **Choose `.gguf` (model or mmproj)**.
3. Select the model or projector.
4. Load it.

Models are copied into app-private storage, because the native engine needs a real filesystem path. For debug builds, you can also push models with `adb` into the app's model directory and refresh the Models screen.

## Multimodal models

Vision-capable models use an `mmproj` projector. The app can import the model and projector separately, discover projector candidates, and select the correct one when loading.

Image and video support depends on the exact model, projector, and device. Video is handled as sampled frames, not continuous video understanding.

## Web search and tools

Web search and tools run inside the normal chat workflow.

- Web features need network access.
- Searches and tool calls can take noticeable time.
- Tool execution can require additional model reasoning.
- Large local models make these operations considerably slower.

## Artifacts and workspace

Generated files and code are handled as artifacts instead of plain chat text:

- HTML, SVG, and Mermaid previews
- Rendered Markdown
- Highlighted source-code artifacts
- Copyable code
- Offline JavaScript execution, only when explicitly requested
- Workspace browsing and file management
- Saving, editing, sharing, and downloading files

Common source formats such as Kotlin, Java, Python, C/C++, JavaScript/TypeScript, JSON, YAML, shell, Dockerfile, and SQL are saved as code artifacts. Languages that need an external interpreter, such as Python, are not executed by the app.

## Chat and attachments

Conversations persist through Room. Supported features include:

- Editing and copying messages
- Copying raw code blocks
- Persistent per-chat drafts
- Text and code attachments
- Images and video
- ZIP, JAR, TAR, and GZ archives
- DOCX, XLSX, PPTX, and other supported document formats
- File navigation and workspace operations

Attachment processing uses size limits, so very large files do not silently consume excessive memory.

## Local API server (experimental)

BigMoeOnEdge+ can expose the model that is loaded in the app to other apps on the same device, for example **Mobile Harness running Claude Code**.

Turn it on in **Settings → Local API server** (the last section; tap its header to expand it).

| | |
| --- | --- |
| Endpoints | `POST /v1/messages` (Anthropic Messages, SSE streaming, `tool_use`, thinking blocks), `POST /v1/messages/count_tokens` (estimate), `POST /v1/chat/completions` (OpenAI), `GET /v1/models`, `GET /health` |
| Address | `http://127.0.0.1:<port>`, default port 8080. Loopback only unless you explicitly enable listening on the network |
| Authentication | An API key is generated the first time you turn the server on and is required by default. Send it as `x-api-key: <key>` or `Authorization: Bearer <key>`. It can be switched off only while the server is loopback-only |
| Model | Whatever is loaded in the app. Any `model` name in a request is accepted |
| Settings | The server uses the app's own settings: Context size, Max tokens, Thinking, sampling and the MTP / guess-ahead options. The only server-specific controls are on/off, port, key, authentication, "Force server settings" and the queue wait |
| Long prompts | The KV cache is reused when a request starts with the same tokens as the previous one, and only the new part is prefilled. `event: ping` keeps streaming clients alive during a long prefill |
| Concurrency | One request at a time, with a short queue; if the queue wait runs out the server answers HTTP 529. The in-app chat has priority over API requests |
| Service | Runs in a foreground service (with a notification and a Stop button) so Android does not kill it |

Quick test from a computer (`adb forward tcp:8080 tcp:8080` first):

```bash
curl -s http://127.0.0.1:8080/v1/messages \
  -H "x-api-key: <your key>" -H "content-type: application/json" \
  -d '{"model":"any","max_tokens":64,"messages":[{"role":"user","content":"Say hi."}]}'
```

For Claude Code, point `ANTHROPIC_BASE_URL` at the endpoint and use the key as `ANTHROPIC_AUTH_TOKEN` (check the variable names against your Claude Code version). Claude Code sends very large prompts, so set the app's **Context size** to 32768 or more and reload the model; a big context costs RAM for the KV cache.

Full details, every curl command, and what was and was not verified: [`docs/API_SERVER.md`](docs/API_SERVER.md).

## Native engine

Native dependencies are vendored rather than pulled in as git submodules.

- `app/src/main/cpp/core`: the vendored BigMoeOnEdge 0.28.0 engine, plus local patches.
- `app/src/main/cpp/third_party/llama.cpp`: the vendored llama.cpp snapshot.
- `EngineNativeBridge.kt`: JNI bridge to the engine.
- `patches/`: project-specific native changes in re-applicable patch form (`0004` adds the stateless chat mode used by the API server: tools in the chat template, parsed tool calls, stop strings, per-request sampling, KV prefix reuse).

The optional Hexagon/NPU path is separate from the default CPU build and requires the Snapdragon toolchain.

## Project structure

```text
app/
  src/main/java/com/bigmoe/onedge/
    core/       engine, generation, model management, context
    parser/     markdown, code fences, artifact languages
    tools/      web, file, and utility tools
    server/     local API server (HTTP, Anthropic/OpenAI translation, foreground service)
    data/       Room persistence and settings
    ui/         Compose UI
  src/main/cpp/
    core/       BigMoeOnEdge engine + local patches
    third_party/llama.cpp
docs/
  STATUS.md
  model-loading.md
  tool-calling.md
  API_SERVER.md
  multimodal-feasibility.md
  ...
patches/
scripts/
.github/workflows/
```

## Testing and status

The app has been built through GitHub Actions and tested on real Android hardware during development.

Development testing covered model loading and generation, Room conversations, web search, reasoning, tools, text and code attachments, artifacts, code blocks, vision and video flows, model and projector loading, chat persistence, and workspace operations.

The local API server is the newest feature and has had the least real-world testing; [`docs/API_SERVER.md`](docs/API_SERVER.md) lists what was verified and what was not.

[`docs/STATUS.md`](docs/STATUS.md) has the full test checklist, implementation notes, and what still needs validation on real devices.

## Known limitations

BigMoeOnEdge+ is under active development, and behavior depends on the model and device.

- Inference speed varies a lot between devices and models.
- Web search requires a network connection and may be slow with large local models.
- Multimodal behavior depends on the exact model and `mmproj` combination.
- Image and video support still needs validation on more devices (see `docs/STATUS.md`).
- Some engine and device combinations need more real-device testing.
- Video input uses sampled frames, not continuous video understanding.
- Voice input is not part of this project.
- Grammar-constrained tool calls are not part of this project. This also applies to the API server: tool calls are parsed from the model's text, so a small model can emit a malformed call.
- The local API server is new and experimental: it handles one request at a time, does not read images sent through the API, does not enforce a thinking budget, and `count_tokens` is an estimate.

## Credits

BigMoeOnEdge+ builds on:

- [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge)
- [llama.cpp](https://github.com/ggml-org/llama.cpp)
- Jetpack Compose, Room, DataStore, and the Android ecosystem

The native engine and vendored components keep their own licenses and notices.

## License

BigMoeOnEdge+ is licensed under the **Apache License 2.0**. See [`LICENSE`](LICENSE).

---

<p align="center">
  Built for local AI on Android.
</p>
