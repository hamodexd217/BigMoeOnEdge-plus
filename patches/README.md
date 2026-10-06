# Patches against the vendored BigMoeOnEdge `core/` (0.28.0)

`app/src/main/cpp/core` is now a vendored copy of the upstream BigMoeOnEdge 0.28.0 engine. The patches below are
ALREADY APPLIED in-tree. They are kept as repo-relative patch files so the customized engine can be reconstructed from
pristine 0.28.0 with `patch -p1`, and both patches were verified with `patch --dry-run` in that order.

| patch | what | why |
|---|---|---|
| 0001-session-chat-context | `GenerateRequest::system_prompt`, `history`, `n_predict <= 0` fills the remaining context, error-path history rollback, and KV-token cleanup when a rollback clears the whole KV | lets the app restore saved chats, supports the UI's unlimited-generation setting, and fixes retry/cancel state corruption paths |
| 0002-ggml-cpu-expert-ready-hook | existing llama.cpp CPU expert-ready hook | this is a llama.cpp-side patch, not an engine-core port; the vendored llama.cpp already contains the required expert-ready integration, so 0002 is **not** re-applied as part of the 0.28 core merge |
| 0003-multimodal | `mmproj_path`, image request data, mtmd image preprocessing/eval path, multimodal CMake link, and vision capability plumbing in the engine API | preserves this app's real image support while taking upstream 0.28 as the engine base |

## Upstream 0.25 → 0.28 changes ported

* **0.25:** `nemotron_h_moe` gate-less Nemotron support; upstream core included.
* **0.25:** Ornith 1.5 support; upstream core included. Its old catalog entry is not retained as a special app shortcut.
* **0.26:** `--prefill-device` / optional NPU prefill; ported and compile-guarded, OFF by default.
* **0.26:** `--prefill-loaders N`; ported and exposed only when NPU prefill is available.
* **0.26:** upstream llama.cpp 965f897 and the MTP `n_past`→`pos0` fix; llama.cpp is kept as the app's existing tree, and the 0.28 core uses the upstream `pos0` code.
* **0.26:** Hexagon Android recipe; `scripts/build-hexagon-android.sh` is copied and the normal Gradle/CMake path remains CPU-only unless explicitly enabled.
* **0.27:** `--decide` / Choose-from-options; ported into the JNI/Kotlin API and chat UI.
* **0.27:** prefix-cache / one-prefill decide engine support; included through the 0.28 core.
* **0.27:** refreshed model catalog, including Nemotron-3.5-Lightning Q4_0 and removal of obsolete Ornith shortcut; adapted as an informational catalog in the app because this app's model manager imports local GGUFs rather than owning the upstream demo downloader.
* **0.28:** routed NPU prefill defaults/probe support; upstream core included and NPU stays optional in the app.

## Apply order

```bash
# from the repository root, on a pristine BigMoeOnEdge 0.28.0 checkout
patch -p1 < patches/0001-session-chat-context.patch
patch -p1 < patches/0003-multimodal.patch
```

The patches intentionally do **not** replace or patch `third_party/llama.cpp`; that vendored tree is managed separately.
