# HANDOFF — closeout after the feature pass (2026-10-02)

Read this first, then docs/STATUS.md, docs/settings-parity.md, docs/prompt-and-kv-design.md, docs/tool-calling.md,
docs/multimodal-feasibility.md. Conversation language with the owner: Arabic (short, plain). Code, comments,
commit messages: English. The owner does not want to test after every phase: build everything, audit, then give
ONE consolidated test checklist.

## Environment facts (important)
* The sandbox has no network, Android SDK/NDK, or device, and the Gradle wrapper cannot resolve its distribution here.
* A Kotlin compiler **is** available locally (`kotlinc` 1.9) and was used for pure-Kotlin host smoke tests.
* `python3 scripts/kt_lint.py app/src` remains a fast source sanity check, not an Android compiler.
* Native C++ was checked with host `g++ -fsyntax-only`; `native-bridge.cpp` was checked with a minimal JNI/log stub.
* No claim is made that an Android APK, Room instrumentation test, WebView runtime, or Android mtmd link succeeded here.
* C++ checks use `g++ -fsyntax-only` against the vendored headers; the JNI bridge uses a minimal local `jni.h`/log stub.
* The available host compiler is Kotlin 1.9; project metadata declares Kotlin 2.0.21, so host smoke results are supplemental.

## What is DONE
Phase 1 (delivered earlier as BigMoeOnEdgeExtended-final.zip, built and run by the owner):
settings parity with the original BigMoeOnEdge Android app wired UI→DataStore→LoadConfig→JNI→SessionConfig;
patch 0002 (ggml-cpu expert-ready hook → overlap); Home/Chats screen + floating nav bar (Models · Chats · Settings
· "+"); chat rename/delete; replaceable home picture (res/drawable/home_hero_custom.png); 32 palettes + custom
palette editor; Web search (Brave → SearXNG → DuckDuckGo → Wikipedia; modes ALWAYS/AUTO); Thinking + Web icon toggles.

After Phase 1, the feature pass added the following and these items are now implemented; see docs/STATUS.md for actual verification:
* App name "BigMoeOnEdge+" and launcher icon generated from the owner's logo (adaptive icon, res/drawable-nodpi/
  ic_launcher_foreground_art.png, bg #05362A, legacy mipmap PNGs).
* Native multimodal: patches/0003-style changes are IN the vendored core (session.h/.cpp: ImageRGB,
  SessionConfig::mmproj_path, GenerateRequest::images, Session::supports_vision, kv_has_media, image-turn branch using
  mtmd_tokenize + mtmd_helper_eval_chunks). Top CMake: BMOE_ENABLE_MULTIMODAL (default ON, gradle
  `-PbmoeMultimodal=false` turns it off) sets LLAMA_BUILD_MTMD and MTMD_VIDEO=OFF; core/CMakeLists links `mtmd`
  and defines BMOE_HAVE_MTMD when `TARGET mtmd` exists. All 38 mtmd sources and session.cpp (with/without the macro)
  pass `g++ -fsyntax-only`. NOT verified: that CMake really creates target `mtmd` via LLAMA_BUILD_MTMD, that it links
  on Android, that images work. The re-applicable change set is now saved as `patches/0003-multimodal.patch`.
* JNI: nativeLoadModel(modelPath, ints, floats, csvPath, mmprojPath); nativeCapabilities(); nativeGenerate(system,
  historyFlat, prompt, maxTokens, clearKv, think, imageDims, imageData, callback). Kotlin: EngineNativeBridge,
  EngineBackend (+EngineCaps, ImageData), EngineController.loadModel(path, config, mmprojPath),
  ModelInfo.visionActive/mmprojPath, GenerationRequest.images.
* Room DB v2 (fallbackToDestructiveMigration: old chats are wiped once — tell the owner): tables attachments,
  artifacts (+DAOs, ArtifactRepository implements tools.ArtifactRegistry, ChatRepository.addAttachment/observeAttachments),
  MessageRole.CONTEXT (hidden attachment text, replayed as a user turn).
* workspace package (pure, JVM-testable): Workspace (sandbox, path-escape protection), FileKinds, DiffUtil (LCS +
  unified diff; algorithm validated with a Python port), FileContext (chunking + BM25 relevant extraction),
  HtmlText, WebFetcher (https only, SSRF checks, manual redirects), TextSearch, CodeAnalysis, AttachmentContext.
* tools package: Tool (category, describe, confirmation, execute(args, ctx)), ToolManager (prompt builder with
  signatures, approval via ConfirmationHandler, timeout only around execute, describeCall), FileTools
  (list/read/search/create/edit(with diff approval)/rename/delete(approval)/create_folder/create_project/export_zip),
  CodeTools (search_code, analyze_code, run_javascript via JavaScriptRunner), WebPageTools (read_webpage,
  extract_webpage), WebSearchTool. JsSandbox + WebViewJavaScriptRunner (offline WebView, untested).
  ToolProtocol now repairs raw newlines inside JSON strings (validated with a Python port).
* AgentController: enabledToolNames(settings, registered) (files/code switches, web-page tools when web search on,
  web_search only in AUTO mode), AgentRequest.contextText/images, ToolContext(sessionId, assistantMessageId) passed to
  tools, MAX_TOOL_ROUNDS=6, image turn → engine state invalidated (next turn replays text).
* attachments package: AttachmentManager (import to workspace/uploads with validation, prepareMedia),
  MediaProcessor (decode+EXIF rotate+scale, RGB bytes, video frames via MediaMetadataRetriever; fitSize/rgbFromArgb/
  frameTimesUs are pure).
* ChatViewModel: addAttachment/removePendingAttachment/sendMessage with attachments (CONTEXT message, images),
  approval dialog state (pendingConfirmation + resolveConfirmation), attachment preparation failure abort, toggleToolFiles/Code, saveCodeAsArtifact,
  openFilePath one-shot, artifacts/attachments flows. EngineSettings: toolsFilesEnabled (default true),
  toolsCodeEnabled (false), imageMaxDim (512), videoFrames (4), lastMmprojPath.
* DI: AppContainer builds Workspace (filesDir/workspace), registers ALL tools, AttachmentManager, ArtifactRepository;
  ViewModelFactory builds ChatViewModel with the new params and FileViewModel (ui/files/FileViewModel.kt, state only).
  FileProvider paths: metrics + workspace.

## What was NOT done at the start of this pass (now completed)

1. ChatInputBar — **done**: File/Image/Video pickers, Vision gating, Tools menu, pending chips, Web/Thinking toggles.
2. ChatScreen — **done**: confirmations/diffs, attachment/artifact chips, Files sheet, file navigation, attachment-only send.
3. Code blocks — **done**: MarkdownLite, syntax highlighting/language detection, code-only copy, artifact action; file viewer text also uses the same tokenizer for syntax coloring.
4. File viewer/workspace — **done**: browse, open/edit/save, preview, share/download, delete confirmation.
5. Models/mmproj — **done**: projector discovery/dropdown/suggestion/persistence/Vision state/speculation warning.
6. Settings — **done**: tool switches, media controls, workspace button, capability text.
7. Tests — **done**: expanded JVM coverage and Room attachment/artifact instrumentation tests.
8. Docs/patch — **done**: `patches/0003-multimodal.patch`, README, STATUS, tools guide, multimodal notes.
9. Final audit — **done**: available checks rerun; device-only items are explicitly listed in STATUS.
