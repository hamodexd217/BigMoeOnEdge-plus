# Patches against the vendored BigMoeOnEdge `core/` (0.28.0)

`app/src/main/cpp/core` is a customized vendored engine based on BigMoeOnEdge 0.28.0. Historical patch files 0001 and 0003–0005 document the existing customizations; 0002 belongs to the separately vendored llama.cpp tree. Patch 0006 is the new 0.5.6 follow-up and is already applied in this source tree. The original archives do not contain Git metadata, so reconstruction history should not be inferred from their filenames.

| patch | what | why |
|---|---|---|
| 0001-session-chat-context | `GenerateRequest::system_prompt`, `history`, `n_predict <= 0` fills the remaining context, error-path history rollback, and KV-token cleanup when a rollback clears the whole KV | lets the app restore saved chats, supports the UI's unlimited-generation setting, and fixes retry/cancel state corruption paths |
| 0002-ggml-cpu-expert-ready-hook | existing llama.cpp CPU expert-ready hook | this is a llama.cpp-side patch, not an engine-core port; the vendored llama.cpp already contains the required expert-ready integration, so 0002 is **not** re-applied as part of the 0.28 core merge |
| 0003-multimodal | `mmproj_path`, image request data, mtmd image preprocessing/eval path, multimodal CMake link, and vision capability plumbing in the engine API | preserves this app's real image support while taking upstream 0.28 as the engine base |
| 0004-session-tools-sampling | `GenerateRequest::chat_messages_json / chat_tools_json / chat_tool_choice / stop / sampling_override`, `RunResult::tool_calls / stop_reason / n_prompt_total / n_reused`: stateless OpenAI-format chat turns with tools in the jinja template, parsed tool calls, stop strings, per-request sampling, KV prefix reuse across requests, and a prompt-preserving rollback on cancel | the local API server (docs/API_SERVER.md). Additive: `generate()` behaves exactly as before when `chat_messages_json` is empty |
| 0005-ctx-used-and-speculative-tail | `RunSummary::n_ctx_used / n_prompt_total / n_reused / n_prefill_eval / kv_reuse` (+ `KvReuse` enum, CSV trailer keys). The context figure is computed from counters, and a speculative group's unemitted tail (an accepted end-of-generation token) is kept and recorded instead of wiping the KV when the memory cannot drop it | fixes "0 / n_ctx" in the chat footer on hybrid/recurrent models with MTP, and the full re-prefill that followed. Apply after 0004 |
| 0006-device-resident-routing | Avoid direct CPU dereferences of routed-expert indices and weights owned by non-host backends; use `ggml_backend_tensor_get` to stage route indices and leave host-only routing rewrites disabled for device-resident layers | upstream 0.33.x fix for MoE streaming correctness on discrete/non-host-memory backends; adapts the fix to this app's custom router and keeps the existing streaming/MTP/API architecture |

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
patch -p1 < patches/0004-session-tools-sampling.patch
# The 0005 patch file is referenced by the supplied README but absent in this archive.
# Reapply 0006 only against the matching 0.5.5 customized tree:
patch -p1 < patches/0006-device-resident-routing.patch
```

The patches intentionally do **not** replace or patch `third_party/llama.cpp`; that vendored tree is managed separately. The documented historical reconstruction sequence is incomplete while the 0005 patch file is missing.
