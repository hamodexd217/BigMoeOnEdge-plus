# BigMoeOnEdge 0.35.0 → BigMoeOnEdge+ 0.6.0: full migration audit

**Purpose:** exhaustive comparison and a safe migration inventory, not a claim that the 0.35.0 implementation has already been merged into the active Android app.

## Sources used

- Upstream source archive: `BigMoeOnEdge-0.35.0.zip` (tag v0.35.0 dated 2026-10-24).
- Target base: the supplied `BigMoeOnEdge-plus-0.6.0` signing-fix-2 source, retaining the previously prepared Flint port (`src/models/flint.cpp`, tokenizer/metadata/graph changes) as the working target.
- Upstream’s `.gitmodules` points to `https://github.com/Helldez/llama.cpp.git`; the upstream source ZIP does **not** include the submodule source.

## Core file-level comparison

Mapped paths: upstream `core/` ↔ target `app/src/main/cpp/core/`.

| Status | Count |
|---|---:|
| Byte-identical | 22 |
| Same path, content differs | 41 |
| Upstream-only files | 43 |
| Target-only files | 2 |
| **Distinct paths across both** | **108** |

The full per-file matrix, byte sizes, line counts and SHA-256 hashes is `UPSTREAM_0.35.0_CORE_FILE_MATRIX.csv`.

### Upstream-only core files (43)

- `include/bmoe/allocate.h`
- `include/bmoe/auto_plan.h`
- `include/bmoe/chat.h`
- `include/bmoe/expert_layout.h`
- `include/bmoe/hardware_profile.h`
- `include/bmoe/log.h`
- `include/bmoe/model_profile.h`
- `include/bmoe/params.h`
- `include/bmoe/placement.h`
- `include/bmoe/plan.h`
- `include/bmoe/planner.h`
- `include/bmoe/probe.h`
- `include/bmoe/run_error.h`
- `src/engine/cache_brake.cpp`
- `src/engine/cache_brake.h`
- `src/engine/chat_messages.cpp`
- `src/engine/chat_messages.h`
- `src/engine/llama_log.cpp`
- `src/engine/llama_log.h`
- `src/log.cpp`
- `src/metrics/memory_snapshot.cpp`
- `src/metrics/memory_snapshot.h`
- `src/moe/expert_layout.cpp`
- `src/moe/gate_gemv.cpp`
- `src/moe/gate_gemv.h`
- `src/moe/tensor_names.cpp`
- `src/moe/tensor_names.h`
- `src/params.cpp`
- `src/plan/allocate.cpp`
- `src/plan/auto_plan.cpp`
- `src/plan/bandwidth_probe.cpp`
- `src/plan/device_probe.cpp`
- `src/plan/gate_probe.cpp`
- `src/plan/hardware_probe.cpp`
- `src/plan/headroom_probe.cpp`
- `src/plan/isolated_probe.cpp`
- `src/plan/memory_topology.h`
- `src/plan/metal_memory_probe.mm`
- `src/plan/model_probe.cpp`
- `src/plan/placement_probe.cpp`
- `src/plan/plan.cpp`
- `src/plan/planner.cpp`
- `src/plan/storage_probe.cpp`

### Target-only core files (2)

- `include/bmoe/recipe.h` — preserve as app-specific extension unless the migration proves it is redundant.
- `src/moe/arch_registry.cpp` — preserve as app-specific extension unless the migration proves it is redundant.

### Modified shared files

- `CMakeLists.txt` — upstream 4324 bytes / target 3887 bytes. Exact hashes and line counts are in the CSV.
- `include/bmoe/config.h` — upstream 44556 bytes / target 33643 bytes. Exact hashes and line counts are in the CSV.
- `include/bmoe/decide.h` — upstream 4723 bytes / target 4587 bytes. Exact hashes and line counts are in the CSV.
- `include/bmoe/decode_trace.h` — upstream 5841 bytes / target 5288 bytes. Exact hashes and line counts are in the CSV.
- `include/bmoe/expert_source.h` — upstream 8570 bytes / target 8094 bytes. Exact hashes and line counts are in the CSV.
- `include/bmoe/metrics.h` — upstream 25752 bytes / target 25765 bytes. Exact hashes and line counts are in the CSV.
- `include/bmoe/route_trace.h` — upstream 3916 bytes / target 3883 bytes. Exact hashes and line counts are in the CSV.
- `include/bmoe/runtime.h` — upstream 2969 bytes / target 2978 bytes. Exact hashes and line counts are in the CSV.
- `include/bmoe/session.h` — upstream 14555 bytes / target 15496 bytes. Exact hashes and line counts are in the CSV.
- `src/config.cpp` — upstream 20567 bytes / target 20215 bytes. Exact hashes and line counts are in the CSV.
- `src/engine/chat_parse.cpp` — upstream 7334 bytes / target 677 bytes. Exact hashes and line counts are in the CSV.
- `src/engine/chat_parse.h` — upstream 4113 bytes / target 1258 bytes. Exact hashes and line counts are in the CSV.
- `src/engine/decide/decider.cpp` — upstream 4889 bytes / target 4641 bytes. Exact hashes and line counts are in the CSV.
- `src/engine/decide/llama_backend.cpp` — upstream 5307 bytes / target 5301 bytes. Exact hashes and line counts are in the CSV.
- `src/engine/prefill_path.cpp` — upstream 12528 bytes / target 11875 bytes. Exact hashes and line counts are in the CSV.
- `src/engine/prefill_path.h` — upstream 5383 bytes / target 5132 bytes. Exact hashes and line counts are in the CSV.
- `src/engine/runtime.cpp` — upstream 3185 bytes / target 2588 bytes. Exact hashes and line counts are in the CSV.
- `src/engine/session.cpp` — upstream 153616 bytes / target 135200 bytes. Exact hashes and line counts are in the CSV.
- `src/engine/thinking_control.cpp` — upstream 6994 bytes / target 6958 bytes. Exact hashes and line counts are in the CSV.
- `src/io/file_reader.cpp` — upstream 11029 bytes / target 9665 bytes. Exact hashes and line counts are in the CSV.
- `src/io/file_reader.h` — upstream 6094 bytes / target 5136 bytes. Exact hashes and line counts are in the CSV.
- `src/io/mapping_release.cpp` — upstream 16989 bytes / target 15051 bytes. Exact hashes and line counts are in the CSV.
- `src/io/mapping_release.h` — upstream 4790 bytes / target 4563 bytes. Exact hashes and line counts are in the CSV.
- `src/io/platform_io.cpp` — upstream 49566 bytes / target 18232 bytes. Exact hashes and line counts are in the CSV.
- `src/io/platform_io.h` — upstream 21152 bytes / target 11458 bytes. Exact hashes and line counts are in the CSV.
- `src/metrics/csv_metrics_sink.cpp` — upstream 9597 bytes / target 10072 bytes. Exact hashes and line counts are in the CSV.
- `src/metrics/decode_trace_sink.cpp` — upstream 3736 bytes / target 3551 bytes. Exact hashes and line counts are in the CSV.
- `src/metrics/route_trace_sink.cpp` — upstream 2647 bytes / target 2618 bytes. Exact hashes and line counts are in the CSV.
- `src/moe/decide_probe.cpp` — upstream 10484 bytes / target 11132 bytes. Exact hashes and line counts are in the CSV.
- `src/moe/dense_weights.cpp` — upstream 23823 bytes / target 21529 bytes. Exact hashes and line counts are in the CSV.
- `src/moe/dense_weights.h` — upstream 9294 bytes / target 8698 bytes. Exact hashes and line counts are in the CSV.
- `src/moe/device_arena.cpp` — upstream 32046 bytes / target 27420 bytes. Exact hashes and line counts are in the CSV.
- `src/moe/device_arena.h` — upstream 13695 bytes / target 11521 bytes. Exact hashes and line counts are in the CSV.
- `src/moe/expert_stream_source.cpp` — upstream 81939 bytes / target 72550 bytes. Exact hashes and line counts are in the CSV.
- `src/moe/expert_stream_source.h` — upstream 24286 bytes / target 21857 bytes. Exact hashes and line counts are in the CSV.
- `src/moe/gguf_offsets.cpp` — upstream 7726 bytes / target 6203 bytes. Exact hashes and line counts are in the CSV.
- `src/moe/gguf_offsets.h` — upstream 5171 bytes / target 3554 bytes. Exact hashes and line counts are in the CSV.
- `src/moe/router_hook.cpp` — upstream 82565 bytes / target 88692 bytes. Exact hashes and line counts are in the CSV.
- `src/moe/router_hook.h` — upstream 40108 bytes / target 39163 bytes. Exact hashes and line counts are in the CSV.
- `src/moe/row_stream.cpp` — upstream 11519 bytes / target 11281 bytes. Exact hashes and line counts are in the CSV.
- `src/moe/row_stream.h` — upstream 7187 bytes / target 6885 bytes. Exact hashes and line counts are in the CSV.


## Functional parity inventory

### Upstream 0.35 capabilities that need an explicit port/adapter check

| Area | Upstream 0.35.0 behavior | Migration treatment |
|---|---|---|
| Planner API and native planning | hardware/model profiles, probes, allocation, placement, auto-plan, plan diagnostics and fit decisions | Port to the Android engine behind a stable adapter; do not copy the upstream CLI or server screen code verbatim. |
| Hardware placement | `n_gpu_layers`, device split, buffer overrides, device selection and planner-approved placement | Add engine config + a model-aware UI; do not expose a control unless the native build can actually honor it. |
| Cache policies | cache pinning / fit, cache brake under memory reclaim, plus updated source telemetry | Port with conservative defaults and actual residency checks; test memory-pressure behavior on device. |
| Routing acceleration | automatic gate-mirror selection for route-ahead, timing-based selection rather than architecture guesses | Port with runtime benchmarking and a safe fallback. |
| Structured output | request-level JSON schema/object, grammar/template path, output validation and clear `invalid_output` behavior | Merge into the existing API-server request pipeline without replacing tools, stop strings, sampling overrides, cancellation or prefix reuse. |
| Context advice | recommend a minimum context for API clients; warnings before `context_full` | Adapt to the current app’s API server and Compose UI. |
| Model management | unified local/catalog model state, direct HTTPS links, delete/forget and fit verdicts | Translate to Android SAF/private-storage semantics; don't import desktop filesystem assumptions. |
| Plan/Settings UI | plan details, apply/measure-again, grouped settings metadata, setting deep-links and provenance | Rebuild natively in Compose. The upstream Svelte app is not an Android UI component. |
| Server/web/iOS/CLI | upstream server endpoints, web UI, CLI and iOS app | Their *behaviors* may be relevant; their code is not a drop-in replacement for the self-contained Android app. |

### Project capabilities that must survive the merge

- Local in-process Android inference with the existing JNI/Compose/Room/DataStore architecture.
- Saved chats and chat history; thinking/reasoning display; web search, tools and artifacts/workspace.
- Current local API server (OpenAI chat + Anthropic Messages), including stateless `chat_messages_json`, tool-choice, stop-string, per-request sampling and KV-prefix reuse behavior.
- Current multimodal `mmproj` image path and the project's MTP sidecar/guess-ahead workflow.
- Current native customizations, including expert-ready hook, device-resident routing fix, performance tuning, and the current 0.6.0 setting/telemetry wiring.
- The previously prepared Flint patch in this working archive. Its real GGUF runtime and Android/Hexagon behavior remain unverified; keep this limitation explicit.

## Critical dependency gap

The target vendors `Helldez/llama.cpp` at `965f89794fd9395ad5b49dd0adb3c04d8b5c5e13`. The upstream v0.35.0 submodule is pinned to `ad98071a583d66c922ba36264433e0435f0e0b42`. GitHub's compare endpoint reports the pinned commit is **619 commits ahead** of the target snapshot. This is a substantial native dependency migration, not a one-file upgrade.

Therefore, copying `core/` wholesale into the active target would not be a safe “everything” migration: upstream `SessionConfig`/`GenerateRequest` interfaces differ from the project's patched interfaces, and a blind engine replacement risks the local API server, multimodal path, MTP state handling, Flint changes and JNI contract. The right merge is a file-by-file port over a known base, followed by NDK build and device validation.

## Verification performed for this audit

- The upstream 0.35.0 **pure-policy `bmoe_core`** configured and built successfully on Linux with `BMOE_HAVE_LLAMA=OFF` (the source archive intentionally lacks its submodule).
- A separate test that replaced the target core with upstream 0.35.0 while using the older vendored llama.cpp did **not** reach a build result within the environment execution window. It is not recorded as pass or fail.
- No Android Gradle/NDK APK build was produced by this audit; no real Flint GGUF or device-side runtime test was performed.

## Recommended migration order

1. Freeze the target’s custom API contract and write a regression inventory for JNI/Kotlin, multimodal, MTP, tools, cancellation, KV reuse and saved-chat restore.
2. Upgrade/merge the `llama.cpp` submodule to the upstream pin in a separate work branch; reapply and test the expert-ready hook, local architecture changes and Flint model changes against that exact revision.
3. Port upstream `core/` by semantic groups: config/params/logging; model/layout introspection; planner/probes and placement; cache pin/brake; session/structured output; metrics/API adapters.
4. Add the corresponding Compose UI and local-API-server fields only when native support is wired end-to-end.
5. Run host policy tests, Android ARM64 build, APK inspection, then device tests for ordinary dense, MoE Q4, MTP, vision, tools/API server, cancellation, memory pressure and optional Hexagon prefill.

**Status:** audit complete; the 0.35.0 full port is **not** complete. The archive deliberately keeps the existing active 0.6.0 source and its prior Flint edits intact instead of pretending untested upstream code was integrated.
