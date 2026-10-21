# BigMoeOnEdge+ 0.5.0 — performance investigation

## User-reported measurements

| Build | Reported decode speed |
|---|---:|
| Original upstream | ~6.0 tokens/s |
| BigMoeOnEdgeExtended | ~5.1 tokens/s |
| BigMoeOnEdge+ 0.5.0 | ~4.3 tokens/s |

These are user-reported measurements, not independently reproduced in this environment.

## What the source comparison shows

### Engine generations are not identical
- `BigMoeOnEdge+ 0.5.0` packages an Android/Compose wrapper around the 0.28.0-era engine and a vendored `llama.cpp` snapshot pinned in `app/src/main/cpp/CMakeLists.txt` to `965f897` (2026-09-26), with the expert-ready hook patch and newer experimental paths.
- `BigMoeOnEdgeExtended` is an older Android wrapper around the 0.24.0-era engine and a `llama.cpp` snapshot from 2026-07-08. It does not contain the same fast-core affinity helper, device-prefill path, multimodal path, or the current expert-ready-hook integration.
- The supplied `BigMoeOnEdge-0.29.0` archive is the upstream native C++ project, not an Android app project. Its engine source is newer/different from the engine embedded in the Android builds, so this is not a strictly like-for-like source comparison.

### Performance-relevant differences in Plus
1. **Fast-core affinity is enabled by default in Plus.** The scope is applied around a generation call. On Android, pinning the JNI caller does not guarantee that an already-created ggml worker pool is pinned the same way; on a heterogeneous CPU it can be neutral or counterproductive. This is a credible variable to isolate, not a proven sole cause.
2. **Expert I/O/compute overlap is enabled by default in Plus.** This path is workload/device dependent. It can help when expert reads are the bottleneck, but synchronization and scheduling overhead can lose on other workloads. Extended has no equivalent setting. It should not be forced on as a universal default without device measurements.
3. **Plus emits a separate generation-progress event for every native token.** Answer/reasoning streaming must remain token-level, but progress counters are informational. This creates avoidable coroutine/channel bookkeeping and can increase CPU competition with decode/UI work.
4. **Plus adds multimodal, MTP, API-server, and other features.** Most of these are not on the hot path for ordinary text generation when unused. Their mere presence is not evidence that the model is “confused by tools.”
5. **One favorable change:** Plus skips per-token `/proc` memory snapshots when CSV metrics are not enabled, avoiding diagnostic work on the normal path.

## Patch in this archive

- Fast-core pinning is now **opt-in** (`-PbmoePinFastCores=true`), both in Gradle and CMake.
- Expert overlap defaults to **off** for fresh settings. It remains available as an explicit setting for A/B tests.
- Progress counters are emitted every 8 tokens; answer and reasoning deltas still stream normally, and the final progress event remains exact.

This is a conservative performance-isolation patch, not a claim that one source-level change explains the entire 4.3 vs 5.1 vs 6.0 gap. The two runtime variables are deliberately reversible so the user can measure them on the same tablet/model.

## Validation status and suggested test

The Gradle unit-test command could not start here because the wrapper attempted to download Gradle and this environment has no network access (`UnknownHostException: services.gradle.org`). No APK was built and no physical-device benchmark was run here.

For a fair test on the tablet:
1. Use the same model file, context length, thread count, cache budget, thinking setting, prompt, and output length.
2. Force-stop the app between runs and let the device cool to a similar temperature.
3. Run at least 3 trials per build and compare the median tokens/s, not one run.
4. If this patch improves speed, re-enable **overlap only** and test again. Test fast-core pinning separately with `-PbmoePinFastCores=true`; do not change both at once.
5. Record the app's native `Run stats` log line if available. It separates compute, I/O, stall, management, and loop-overhead time; that is the best evidence for the next optimization.
