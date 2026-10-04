# Settings parity with the original BigMoeOnEdge Android app

Source compared: `BigMoeOnEdge-0.24.0/examples/android` (`AppSettings.kt` = defaults + argv builder,
`SettingsScreen.kt` = labels/hints/choice lists). Every option below goes
**UI → `EngineSettings` (DataStore) → `LoadConfig.toIntArray()` → `nativeLoadModel` → `bmoe::SessionConfig`**.
The wire format is pinned on both sides (`LoadConfigTest` + the `IntIdx` enum in `native-bridge.cpp`; a script
cross-check of the order was run).

| Original setting (CLI flag) | Here | `SessionConfig` field | Notes |
|---|---|---|---|
| mmap baseline (omit `--moe-stream`) | none: chosen automatically per model (GGUF `expert_count`) | `moe.enabled=false` | dense models only; all streaming knobs inert |
| Expert cache MiB (`--cache-mb`, `--force-cache`) | Expert cache | `moe.cache_mb` / `cache_auto` / `force_cache` | same choice list; <1500 → force like the original |
| Auto cache ceiling (`--cache-ceil-mb`) | Auto cache ceiling | `moe.cache_ceil_mb` | |
| (floor) | Auto: keep RAM free | `moe.cache_floor_mb` | extra, was already in this app |
| Parallel I/O lanes (`--io-threads`) | same | `moe.io_threads` | |
| Direct I/O (`--no-odirect`) | same | `moe.o_direct` | |
| I/O and compute overlap (`--overlap`) | same | `moe.overlap` | **needs patch 0002**, see below |
| Dense weights (`--dense-weights`) | same, 4 modes | `moe.dense_weights` | |
| Stream row-gathered tables | same | `moe.row_stream` | |
| Release the model mapping | same | `moe.release_mmap` | only with Anon/Pinned |
| Temporal prefetch (`--prefetch`) | Experimental | `moe.prefetch_layers` | only with cache |
| Predictive prefetch (+`--predict-spec-max`) | Experimental | `moe.predict_prefetch`, `predict_spec_max` | exclusive with the above |
| Route-ahead | Experimental | `moe.route_ahead` | excludes prefetchers + spec |
| Drop cold experts % (default **75**) | Speed / quality | `moe.drop_cold_frac` | only with cache |
| Active experts top-k | same | `SessionConfig::n_expert_used` | 0 = model default |
| Prefer cached experts % | Experimental | `moe.substitute_lambda` | only with cache |
| Guess ahead (MTP / n-gram), tokens per pass, MTP p-min | Experimental | `spec.source`, `draft_max`, `draft_p_min` | |
| Compute threads | Compute | `n_threads` | |
| Context | Compute | `n_ctx` (+ `n_batch=n_ctx`, `n_ubatch=min(512,ctx)` like the engine's own `session_config_from`) | |
| Tokens to generate | Chat → Tokens to generate | `GenerateRequest::n_predict` | + "Unlimited" (patch 0001) |
| Thinking | Chat + brain button | `GenerateRequest::think` | locked ON when `think_control=none` |
| Metrics CSV | Diagnostics | per-token CSV sink passed to `generate()` | file in `<external files>/metrics/`, shareable |
| Sampling (temperature/top-p/top-k) | Compute | `sampling.*` | the original runs greedy; defaults here keep 0.7/0.9/40 |

Defaults are the original app's defaults (fixed 2000 MiB cache, 4+4 lanes, O_DIRECT, overlap, Anon dense,
drop-cold 75 %, top-k model default, thinking off).

## Why this app was ~3 tok/s and the original 5–6 tok/s (diagnosis)
Not a UI problem. Compared implementation vs implementation:

1. **Overlap was never active.** The original's default is `--overlap`, which needs the Helldez llama.cpp
   fork's `ggml_cpu_set_expert_ready_hook` (BigMoeOnEdge `docs/seam.md` §3). This project vendors stock llama.cpp,
   so `BMOE_HAVE_EXPERT_READY_HOOK` was undefined and `native-bridge.cpp` forced `overlap=false`.
   **Fix:** `patches/0002-ggml-cpu-expert-ready-hook.patch` implements the documented hook (a null-checked
   call at the top of the per-expert loop of `ggml_compute_forward_mul_mat_id`). It compiles (host gcc, and the
   overlap code paths in `session.cpp`/`expert_stream_source.cpp` compile with the macro on). Whether the
   threadpool really behaves as the fork's does is **not verified without a device** — the Models/Settings screens
   show "overlap: active / not active" for the loaded model.
2. **Drop-cold-experts 75 %** (the original's default) was not exposed here (effectively 0).
3. **Cache policy:** the original fixes 2000 MiB; this app used "auto", whose size depends on free RAM.
4. **Prefill batching:** this app used `n_batch=512`; the engine's own mapping uses one batch (`n_ctx`).
5. Everything else (threads 4, lanes 4, O_DIRECT, Anon dense, ubatch 512) was already equal.

Compile flags are the same as the original's (`-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`, `GGML_NATIVE=OFF`,
see `scripts/build-android.ps1`); the original runs the engine as a separate CLI process, this app links it
in-process (same code, same `Session`). If your SoC has i8mm you may try `armv8.6-a+dotprod+fp16+i8mm` in
`app/src/main/cpp/CMakeLists.txt` (`BMOE_ARM_ARCH`) — that is an experiment, not parity.
