<p align="center">
  <img src="docs/assets/logo.png" alt="BigMoeOnEdge+" width="220">
</p>

<h1 align="center">BigMoeOnEdge+</h1>

<p align="center">A tablet-friendly Android app for running local GGUF language models with a native chat UI and a customized BigMoeOnEdge inference engine.</p>

<p align="center">
  <a href="https://github.com/hamodexd217/BigMoeOnEdge-plus/releases/latest">Latest APK</a> ·
  <a href="https://github.com/hamodexd217/BigMoeOnEdge-plus/issues">Issues</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="docs/RELEASE_NOTES_0.6.5.md">Release notes</a> ·
  <a href="docs/STATUS.md">Testing status</a>
</p>

---

## Overview

BigMoeOnEdge+ runs local `.gguf` models on Android. It handles dense and Mixture-of-Experts (MoE) models, keeps conversations on the device, streams answers with their reasoning, and can search the web, use tools, read images through compatible `mmproj` files, edit files, and serve the loaded model over an optional local API.

**Version 0.6.5** (`versionCode` 19). The native engine is the project's customized BigMoeOnEdge 0.28.1-compatible source with a vendored llama.cpp. This repository is the source; build it to get an APK (see [Build from source](#build-from-source)).

## What's new in 0.6.5

The full list is in the [changelog](CHANGELOG.md) and the [release notes](docs/RELEASE_NOTES_0.6.5.md). In short:

| Area | Highlights |
|---|---|
| Models | LFM3 text and LFM3-VL vision support, Flint and LFM2 / LFM2.5 supported, readable load errors, NPU memory failures no longer crash the app |
| Sampling | Min P, repetition penalty and window, frequency and presence penalty |
| Tools | No tool-round limit, external tools (HTTP tools and MCP servers), editor-style file tools with undo |
| Chat | Live "what is the model doing" status line with timings, saved per message; in-app browser; chat-switch glitch fixed |
| Native UI | The model decides when to use a card; wrench-menu switch |
| Appearance | "Liquid glass" renamed **Glass** |

## Features

### Local models and chat

- Load dense and MoE GGUF models; import projector (`mmproj`) files for compatible vision models (including LFM3-VL).
- Model families built into the engine include Qwen, **LFM2 / LFM2.5 / LFM3**, **Flint** and many others from the vendored llama.cpp. A model with an architecture the engine does not know fails with a plain-language message instead of a raw error.
- Persistent Room-backed conversations and per-chat drafts.
- Streaming text and reasoning, message editing/copying, source links, and per-message statistics (speed, prefill speed, thinking time, tokens, context).
- A **live status line** under the answer shows what the model is doing and for how long (reading the prompt, thinking, using a tool), plus prompt-reading and generation speed.
- Messages sent while a model is loading are queued and sent in order once it is ready.
- A centered chat composer with a maximum width, designed for tablets. On wide screens the menu button in the chat toolbar hides or shows the chat-history pane.
- Links and image previews open in an **in-app browser** instead of leaving the app.

### Sampling and settings

- **Auto Settings:** Fastest, Balanced, Most accurate, or a Custom Settings profile; saves can be measured, renamed, exported and imported.
- Sampling controls — Temperature, Top-P, Top-K, **Min P, Repetition penalty, Repeat window, Frequency penalty, Presence penalty**, and Seed — apply per request and do not require reloading the model. The penalties are off by default and are switched off while Guess ahead (MTP / n-gram) is on. Load-time settings (context size, threads, batch width, cache, I/O and routing) still require a reload.
- **Model & loading** opens automatically when a change needs the loaded model to be reloaded.
- Three surface styles: **Solid colors**, **Gradient** and **Glass** (translucent, frosted-looking surfaces without costly real-time blur), plus built-in and custom palettes.

### Tools

Tools are opt-in. Turn them on in **Settings → Tools, web search, pictures & videos → Tools** or with the wrench button under the chat input. Anything that changes or deletes a file, or reaches out to the network through an external tool, asks first.

- **Files:** list, read, search (with context lines), create, rename / move / **copy**, delete, zip.
  - `edit_file` replaces exact text.
  - `edit_lines` replaces, inserts or deletes by line number, several edits per call.
  - `replace_in_files` does project-wide find and replace (plain or regex) with one diff to approve.
  - `undo_edit` restores a file to how it was before the last AI edit (kept in memory until the app closes).
- **Code** and **Utilities:** code search/analysis, a sandboxed JavaScript runner, date/time, calculator, unit converter, random numbers and device status.
- **Web search** and optional image search, with automatic or always-on behaviour.
- **External tools:** add your own **HTTP tools** (an address with `{argument}` placeholders, method, headers) and **MCP servers** (Streamable HTTP; tools are discovered once and cached, you choose which ones the model may use). Each call asks for approval by default and results are treated as untrusted data. See [`docs/tool-calling.md`](docs/tool-calling.md).
- There is **no limit on the number of tool rounds** in a turn. A turn ends when the model answers, when you press Stop, when the context is full, or when the model repeats the exact same call nine times in a row.

### Native UI and artifacts

- **Native UI Components** (optional, off by default) let the model answer with Jetpack Compose cards — comparison tables, slides, lists, badges, metric cards, progress bars, timelines, expandable sections, selectable cards and prompt actions — whenever a request calls for one (a comparison, pros and cons, a timeline, key numbers). They are rendered from a small JSON block: no HTML, CSS, JavaScript or WebView. Enabling them adds roughly 450 tokens to the system prompt.
- **Artifacts** are separate: HTML, SVG and Mermaid previews use the existing preview flow, and source-code blocks stay copyable and highlighted instead of being run.

### Attachments

Text, source files, archives, documents, images and video, subject to model and processing limits.

### Optional local API

The app can expose its loaded model over a local HTTP API for other tools on the device (OpenAI-style chat completions and Anthropic Messages). It is off by default and uses an API key; network binding is configurable. See [`docs/API_SERVER.md`](docs/API_SERVER.md) before enabling it.

## Where to find things

| What | Where |
|---|---|
| Engine profiles, saves, Custom Settings | **Settings → Auto settings** |
| Sampling (Temperature, Top-P, Top-K, Min P, penalties, seed) | **Settings → Engine performance** (the sliders under Top K) |
| Surface style (Solid / Gradient / Glass) | **Settings → Appearance** |
| Native UI, External tools, file / code / utility tools | **Settings → Tools, web search, pictures & videos → Tools**, or the wrench button in the chat |
| Web and image search | **Settings → Tools, web search, pictures & videos → Web search** (image search has its own switch; the automatic follow-up after a web search is separate and off by default) |
| Hide / show chat history on a wide screen | menu button in the chat toolbar |

## Download

Use the [latest published APK](https://github.com/hamodexd217/BigMoeOnEdge-plus/releases/latest). An APK only contains these changes if it was built from this source after they were made — check **Settings** or the release page for version 0.6.5.

## Build from source

Requirements:

- Android Studio with the project's compatible Android Gradle Plugin and Kotlin toolchain.
- Android SDK 35 and a matching NDK/CMake setup for the vendored native engine.
- A network connection the first time Gradle downloads its wrapper and dependencies, unless they are already cached.

Build a debug APK from the repository root:

```bash
./gradlew :app:assembleDebug
```

The APK is written under `app/build/outputs/apk/debug/`. For GitHub Actions, make sure artifact storage has quota and configure stable signing secrets for signed release builds ([`docs/SIGNING.md`](docs/SIGNING.md)); never commit keystores or passwords.

The default build targets `arm64-v8a`. The optional Hexagon/NPU path needs its own toolchain and packaged runtime libraries; a Snapdragon chipset alone does not mean the NPU backend is present in a given APK. See [`docs/npu-android-build.md`](docs/npu-android-build.md).

Quick static check (no Gradle needed): `bash scripts/verify-requested-features.sh`.

## Project layout

```text
app/src/main/java/com/bigmoe/onedge/
  core/       engine coordination, generation, model loading, agent and tool routing
  data/       Room entities, migrations and DataStore settings
  parser/     Markdown, code fences, and artifact metadata
  server/     optional local HTTP API
  tools/      web, file, editor, code, utility and external (HTTP / MCP) tools
  ui/         Jetpack Compose screens, in-app browser and native UI renderer
  workspace/  generated-file workspace, search and diff support
app/src/main/cpp/
  core/       customized native inference engine and local patches
  third_party/ vendored llama.cpp source
patches/      reproducible project-specific native patches
docs/         architecture, model loading, API, tools, porting and validation notes
scripts/      offline checks and diagnostics helpers
.github/workflows/  CI workflows
```

## Limitations

- **LFM3:** text and vision only; audio, omni, decision models and video are not supported. Image resizing uses a plain bilinear/bicubic resize rather than the reference torchvision-style one, so image embeddings differ slightly.
- **Flint:** runs on the CPU path; the NPU path has not been verified for it.
- **NPU prefill:** a dense model can need more NPU memory than the device session can map. The app reports this as an error instead of crashing, but the cause is not removed: turn NPU prefill off or lower the context for such models.
- **External tools:** MCP over the legacy HTTP+SSE transport, local (stdio) servers and OAuth sign-in are not supported. Headers such as API keys are stored on the device like the search key.
- **Prefill speed** is shown after each generation round, because the engine only reports the prompt size when a round ends.
- Behaviour varies by architecture, quantization, context length, engine build options, Android version and thermal state. Estimated memory or speed figures are not measurements. The optional API and NPU path need more real-device testing.

Run `bash scripts/verify-requested-features.sh` for a quick static check of the source; [`docs/STATUS.md`](docs/STATUS.md) lists what has been checked and how.

## Credits and license

BigMoeOnEdge+ builds on [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge), [llama.cpp](https://github.com/ggml-org/llama.cpp), Jetpack Compose, Room, DataStore and the Android ecosystem. Vendored dependencies keep their own notices and licenses. BigMoeOnEdge+ is licensed under Apache License 2.0; see [`LICENSE`](LICENSE).
