# BigMoeOnEdge+ 0.5.7 — settings implementation and parity

Date: 2026-10-22. Source baseline: supplied BigMoeOnEdge+ 0.5.5 ZIP, already locally revisioned 0.5.6; upstream comparison: supplied BigMoeOnEdge 0.33.2 ZIP and its visible params registry. This is a settings/UI/JNI integration on the custom app's existing engine, not a wholesale engine upgrade.

## What is now actually wired end-to-end

The `Engine Performance` foldout contains four grouped cards:

1. **Accelerator · NPU prompt prefill** — capability-gated Hexagon toggle; loader threads; minimum prompt width; routed expert reads; full-layer fallback threshold. Controls remain visible but disabled when the installed native library does not include Hexagon.
2. **MoE streaming & I/O** — expert cache fixed/auto/off; auto floor/ceiling; parallel I/O lanes; O_DIRECT; I/O/compute overlap; dense-weight residency; row-gather streaming/window; release mmap; low-cache override.
3. **Speed / quality** — drop-cold experts; active top-k override; speculative MTP/n-gram settings; temporal/predictive prefetch; route-ahead; two-wave I/O; drop renormalisation/prefill; prediction probe; load-all A/B baseline; decision prefix-cache mode.
4. **CPU compute & memory** — decode threads; prompt/batch threads; ubatch width; context; temperature; top-p; top-k; seed.

Every setting newly surfaced in this pass has a DataStore key, a UI callback, a `LoadConfig` field/array position, and a matching `IntIdx` read in `native-bridge.cpp`, except UI-only capability text. The wire layout is pinned by `LoadConfigTest.indexLayoutMatchesNativeBridge`.

### Key integration details

- `overlap` defaults to **on** for new installs. Runtime activation still requires streamed MoE, a working cache and the compiled expert-ready hook. The loaded model status continues to show whether overlap was truly active. Existing explicit stored preferences are preserved.
- `io_two_wave` remains off by default; it is a distinct experimental scheduling policy and is not silently enabled just because overlap is on. It is force-disabled by the JNI bridge when overlap or cache is unavailable.
- Prompt/batch thread count reaches both the target context and MTP draft context via `llama_set_n_threads(ctx, decodeThreads, batchThreads)`.
- NPU settings map to `PrefillDeviceConfig`. They do not imply that the default CPU-only build can use the NPU.
- The `--decide-prefix-cache` equivalent can be selected as Auto/On/Off. Decision scoring remains enabled because it backs BigMoeOnEdge+'s existing scored-choice API; only its prefix-reuse policy is configurable.
- `force-cache` is wired and defaults true to preserve this custom app's prior behaviour for explicitly chosen sub-1500 MiB cache sizes. Users can turn it off; such a low fixed budget will then be rejected by engine validation.
- Existing persisted values remain in the same DataStore preferences; new keys have defaults, so no Room schema migration is required.

## Upstream setting inventory

| Upstream key / capability | BigMoeOnEdge+ 0.5.7 status | Notes |
|---|---|---|
| `model`, `n-predict`, `think` | Already outside Engine Performance | Model manager and Chat sections own model selection, answer length and reasoning toggle. |
| `ctx-size`, `threads`, `threads-batch`, `ubatch` | Integrated | `ubatch` is capped by context size by native config. |
| `temp`, `top-k`, `top-p`, `seed` | Integrated | Sampling remains session-load scoped in this engine. |
| `chatml` | Fixed on by the chat front end | Always wrapping turns is required for the app's chat UX, so it is not exposed as a user switch. |
| `moe-stream` | Auto-selected per model | The app chooses streamed loading for supported MoE models and standard loading otherwise. |
| `io-threads`, `o-direct`, `overlap`, `io-two-wave` | Integrated | Overlap honestly reports inactive if the native hook is missing. |
| `release-mmap`, `cache-mb`, `cache-floor-mb`, `cache-ceil-mb`, `force-cache`, `dense-weights` | Integrated | Small-cache override preserves this app's prior behavior by default. |
| `row-stream`, `row-stream-mb` | Integrated | Mutually gated with NPU prefill. |
| `prefill-device`, `prefill-min-tokens`, `prefill-loaders`, `prefill-routed`, `prefill-routed-full` | Integrated | HTP0/Hexagon prefill; requires the Hexagon-enabled APK; decode remains on CPU. |
| `decide` | Kept enabled by design | Required for existing scored-choice API contract. |
| `decide-prefix-cache` | Integrated | Configurable Auto/On/Off for scored-choice requests. |
| `prefetch`, `predict-prefetch`, `predict-spec-max`, `spec`, `draft`, `mtp-p-min`, `ngram-min-match`, `ngram-max-match` | Integrated | Advanced settings remain under Engine Performance → Experimental. MTP sidecar selection stays in Models because it is a file/model-management action. |
| `n-expert-used`, `drop-cold-experts`, `drop-renorm`, `drop-in-prefill`, `expert-substitute`, `route-ahead`, `predict-log`, `load-all` | Integrated | Some options deliberately change output or are intended only for A/B diagnostics; help text warns about that. |
| `gpu-layers`, `dense-on-device`, `tensor-overrides`, `device-split`, `devices` | Not exposed as active settings | The Android native build explicitly disables OpenCL and does not wire an Android GPU/device-placement API. Showing live controls would imply unsupported acceleration. |
| `prefill-best-effort`, `prefill-verify`, `prefill-slots`, `prefill-slots-on-demand` | Not yet ported | Those controls depend on newer upstream prefill-device placement/verification and slot-budget code absent from this app's 0.28-based core. They are not shown as functional switches. |
| `cache-pin`, `cache-brake`, `cache-pin-fit` | Not yet ported | The supplied custom core has no corresponding runtime policy implementations. Adding only preferences would be fake; require an engine-core port first. |
| `decide-probe`, `llama-debug` | Not surfaced | File-backed diagnostics/log-level wiring differs from the current Android app and needs a separate safe debug interface. |
| `prefetch-sync` | Not exposed | Upstream documents it as test/debug-only; it makes speculative reads synchronous and defeats the latency-hiding behaviour. |

## NPU on Snapdragon 8 Gen 2

The Snapdragon 8 Gen 2 contains a Hexagon HTP accelerator, but Android does not automatically route llama.cpp operations to it. This project's normal CMake path explicitly builds CPU-only (`BMOE_ENABLE_HEXAGON=OFF`). NPU use needs an APK whose `libonedge-engine.so` was built with `BMOE_ENABLE_HEXAGON=ON` and which packages the matching HTP backend/skel libraries. The `android-npu.yml` workflow is the supported route documented in `docs/npu-android-build.md`. In the current ordinary APK, `nativeCapabilities()` reports `npuPrefill=false`, so the switch is disabled. The capability is for prompt prefill only; token-by-token decode remains on CPU. Row-streaming and speculative decoding are currently gated because they conflict with that path.

## Build and test limitations

The local Android build/test attempt is blocked before Gradle starts because the wrapper cannot download Gradle 8.9 from `services.gradle.org` (DNS/network unavailable); no APK should be claimed as built. The Kotlin offline source linter checks syntax balance but emits known pre-existing import-use false positives in unrelated original files. The test suite was updated to pin the new JNI array slots, but JUnit could not execute in this environment. No performance benchmark was collected.
