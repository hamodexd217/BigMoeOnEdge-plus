# Upstream 0.39.0 merge — what was taken, what was not, and how it was checked

BigMoeOnEdge+ 0.7.0 · upstream BigMoeOnEdge 0.39.0 · 2026-11-04

Read this before building. Nothing here was run on a device, and no Gradle/NDK build was available while it was
written.

## Why a merge and not a replacement

Upstream 0.39.0 is a desktop-first project (CLI, server, web UI, agent, planner, desktop and iOS apps) whose engine
`session.cpp` was restructured around a **newer llama.cpp** than this app vendors. Compiled as shipped against the
app's llama.cpp it fails on four API gaps (`common_batch`, `llama_context_params::moe_cache_size`,
`common_chat_params::prompt.str()`, a `bool`-returning `common_prompt_checkpoint::load_*`). Upstream's own
llama.cpp is a git submodule and was not in the archive, so it could not be taken either.

So the engine was merged in layers: whatever builds unchanged against the app's llama.cpp is upstream's, and the
session layer, where this app's features live, stays this app's with upstream's changes ported in.

## Taken from upstream verbatim

| Area | Files |
|---|---|
| I/O | `src/io/{file_reader,platform_io,mapping_release}.*` |
| Expert streaming | `src/moe/{router_hook,expert_stream_source,device_arena,dense_weights,row_stream,gguf_offsets,decide_probe}.*`, new `gate_gemv.*`; `expert_layout`, `tensor_names`, `stall_union.h` were already identical |
| Tracing and metrics | `src/metrics/{decode_trace_sink,route_trace_sink}.cpp`, new `memory_snapshot.*`; headers `decode_trace.h`, `route_trace.h`, `expert_source.h` |
| New helpers | `context_checkpoints.*`, `kv_cache_type.h`, `llama_log.*`, `run_error.h` (+ pure-policy headers `routing.h`, `measured_knobs.h`, `model_profile.h`, `hardware_profile.h` that the router hook includes) |
| Config | `config.h`, `config.cpp` (then the app's extras re-added, below) |

## Merged by hand

| File | Upstream's side | This app's side kept |
|---|---|---|
| `config.h` / `config.cpp` | everything | `SamplingConfig` min-p / repeat / frequency / presence + their validation; `RunConfig::progress`, `compute_trace_layers` |
| `metrics.h`, `csv_metrics_sink.cpp` | flash-wait, process footprint, KV types, `unique_ptr` factory | `KvReuse`, `n_ctx_used`, batched CSV flush |
| `session.cpp`, `session.h` | `ExpertLayout`, KV types + V fallback, context checkpoints, cancel/rollback coupling, teardown join, read-ahead release, gate-mirror timing, pin floor | chat history, system prompt, tools, structured output, multimodal, sampling override, MTP sidecar, NPU prefill wiring |
| `runtime.cpp` | new `SessionConfig` fields | the one-shot `run()` |
| `prefill_path.*` | arena `init(slots, slots_on_demand)` | the **NPU+MTP trunk-layer fix** (`learned_layer_ends(n)` overload added to the router hook) and the `fastrpc_mmap` exception catch |
| `native-bridge.cpp` + Kotlin | — | settings plumbing, error text, NPU-forces-f16 |

## Deliberately not merged

- **llama.cpp.** The app's vendored tree is unchanged. Updating it is the next big job and the real blocker for
  taking upstream's `session.cpp`.
- **Planner, `--auto`, knob trials, params registry, hardware/model profiles** (`src/plan`, `params.*`,
  `backend_choice`, `placement`): desktop/CLI machinery with no caller in the app.
- **Upstream's `chat.h` / `chat_messages` / `chat_parse` / `thinking_control` changes:** they assume the newer
  llama.cpp chat API and the server's stateless chat. The app's chat layer is its own.
- **Speculation beside a prefill device (0.37):** the app's own fix is kept (`docs/NPU_MTP_FIX.md`).
- **Server, web UI, agent, desktop app, iOS, skills:** compiled from `upstream/` by CI, not integrated into the
  Android app.

## Behaviour changes you can see

1. **KV cache defaults to q8_0** (was f16). NPU prefill forces f16. Set both to f16 in Settings to get the old
   behaviour back.
2. **Prompt checkpoints default to 3** and only ever apply to hybrid/recurrent models.
3. Engine errors that named a setting now read as plain words.
4. `engine=` in logs and CSVs reads 0.39.0; the CSV gained a preamble line and two trailing columns.
5. Route-ahead chooses its gate format by timing at load rather than always using int8.

## Things I found only by diffing call sites (worth knowing about)

The adopted classes changed some **defaults** the old session relied on silently. Two were real and are fixed:
`RouterHook::set_gate_mirror` (off unless the session sets it; route-ahead would have silently used float gates)
and `ExpertStreamSource::set_pin_floor` (0 unless set). The remaining upstream-only calls
(`routing_record`, `begin/end_record_batch`, `share_memory_with`, knob-trial `set_overlap`) belong to planner
or slot-sharing features that are not enabled here. A third class to watch on device: anything upstream's session
does *between* steps that the app's session never did.

## How this was checked

- `g++ -fsyntax-only` of all 35 engine sources against the app's own vendored llama.cpp, before and after.
- Link closure: all objects built, then every undefined `bmoe::` symbol (158) checked against the definitions in
  the set; every `llama_/ggml_/common_` symbol checked to exist in the vendored tree.
- Nine host tests (copied from upstream 0.39.0 into `core/tests/`), built exactly as `ci/core-host` builds them:
  all pass.
- The repo's own validators (`verify-requested-features.sh`, `validate-settings-0.5.7.sh`,
  `validate-upgrade-0.5.7.sh`) pass; the Kotlin lint reports nothing new.

First GitHub Actions run (2026-11-05): the nine host tests pass on Linux x64/arm64, macOS arm64 and Windows
x64; all six desktop targets build, report 0.39.0 and package. The Android job stopped at the unit-test compile on
two test files that were already broken in 0.6.5 (raw line breaks inside string literals); fixed. `native-bridge.cpp`
now also compiles (against a stub `jni.h`) with the Android macros on.

Still not done, and each is a way this could fail: the NDK/CMake build of the app, the Kotlin compile of the main
sources, loading a model, quantized V on Hexagon and Adreno backends, checkpoints on a real hybrid model. If the
Android build fails, start with the Kotlin settings screen and `native-bridge.cpp`.

## Next merge

`upstream/` holds the pristine 0.39.0 tree. For the next release:
`diff -ru upstream/ <new-release>/` shows exactly what upstream changed, and the table above says where each
file lives in the app. To do the llama.cpp bump first would let `session.cpp` take upstream's version outright.
