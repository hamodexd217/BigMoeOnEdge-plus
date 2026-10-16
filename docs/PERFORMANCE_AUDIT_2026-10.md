# BigMoeOnEdge+ source performance audit and focused optimizations

**Audit date:** 2026-10-16
**Inputs:** `BigMoeOnEdge-orignal upstream 0.29.0.zip` and `BigMoeOnEdge-plus-0.4.0.zip`
**Scope:** static source comparison, focused source edits, host-side syntax/behavior checks, and attempted Android build. No Android device/runtime benchmark was available.

## Executive summary

The supplied fork is a complete Android application, while the supplied upstream archive is a separate engine/CLI/server-oriented project. The fork is not a drop-in copy of the supplied 0.29.0 engine: its Android app vendors a `llama.cpp` tree reported in its local CMake metadata as 0.28.0-era code and carries Android/JNI/UI-specific changes. The upstream archive's `.gitmodules` points to an external `third_party/llama.cpp` submodule, but that submodule's source snapshot is not present in the supplied archive. A wholesale engine upgrade or bulk file copy would therefore be unsafe and was not performed.

Three focused performance changes were made while preserving generation logic and user-facing features:

1. JNI token delta tracking appends only the confirmed suffix rather than copying the full accumulated output string for every update.
2. Per-token CSV flushing is batched to 16 rows; final summaries still flush immediately.
3. Per-token `/proc` memory snapshots are skipped when CSV metrics are disabled, and new installations default the optional Metrics CSV diagnostic to off.

These changes reduce avoidable host-side/inter-token overhead. **They do not change the neural-network computation, sampling, KV cache, expert cache, thread count, I/O policy, model files, or numerical precision. No tokens/second uplift is claimed.** The app's decode TPS accounting excludes some callback/metrics work, so these modifications may improve user-perceived wall-clock throughput more than the displayed `tokens_per_second` field. A controlled device benchmark is required to quantify the effect.

## Archive and project structure findings

### Supplied trees

- Original archive: root `BigMoeOnEdge-0.29.0/`, 628 archive entries. Its root `CMakeLists.txt` identifies version 0.29.0; `.gitmodules` declares `third_party/llama.cpp`, but the actual submodule contents are absent from this archive.
- Fork archive: root `BigMoeOnEdge-plus-0.4.0/`, 3512 archive entries. This is the Android app project, including Compose UI, DataStore, Room, JNI, app settings, native engine source, patches, scripts, and documentation.
- The fork's native engine is `app/src/main/cpp/core/`; it has 65 files at the compared subtree versus 88 in upstream `core/`. Of 65 shared paths, 42 are byte-identical and 23 differ; 23 planner/probe/hardware-oriented paths are upstream-only.

### Android/build configuration found in the fork

- `app/build.gradle.kts`: Android Gradle Plugin 8.7.3 via the version catalog, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Gradle wrapper 8.9, compile SDK 35, min SDK 29, target SDK 35, version 0.4.0.
- ABI selection defaults to `arm64-v8a` and can be overridden with `-PbmoeAbis=...`.
- App native build: `app/src/main/cpp/CMakeLists.txt`, C++17, `GGML_NATIVE=OFF`, `GGML_OPENMP=OFF`, `GGML_OPENCL=OFF`, KleidiAI off by default to keep the Android build from requiring a network fetch, optional multimodal on, optional Hexagon off. `BMOE_ARM_ARCH` defaults to `armv8.2-a+dotprod+fp16`; enabling `i8mm` should only be done on a verified compatible device.
- The project README states Android NDK `27.0.12077973`; `app/build.gradle.kts` does not pin `ndkVersion` itself.
- The local native CMake configure reports the vendored llama.cpp as `0.5.0-dev` / ggml `0.25.3`; this is not the same version-label scheme as the app's engine-version constant. The fork's CMake comments identify its vendored llama.cpp commit as `965f897` dated 2026-09-26.

### Main app architecture and preserved custom work

The fork's Android entry points are the Compose/UI and settings code under `app/src/main/java/com/bigmoe/onedge/`; JNI is in `app/src/main/cpp/native-bridge.cpp`; the in-process inference session is `app/src/main/cpp/core/src/engine/session.cpp`; runtime/configuration and expert streaming live under `app/src/main/cpp/core/src/`. The archive also contains chat/history persistence, model management, tools, workspace/artifacts, web-search integration, multimodal paths, local API work, and existing patches. The changes below do not replace or delete those components.

## Source-level performance findings

The levels below distinguish direct source evidence from hypotheses that require runtime profiling.

### Confirmed avoidable overhead addressed

**A. Repeated accumulated-output copying at the JNI boundary**
File: `app/src/main/cpp/native-bridge.cpp`, helper `next_delta()` (near the beginning of the file). Before the change, once a cumulative prefix was confirmed, it used `sent.assign(cur, 0, safe)`, copying the entire already-emitted string on each token update. It now appends just the new suffix after the existing byte-prefix check. The string still grows as output grows, but the total copying is no longer quadratic solely because every callback recopies the prefix. The exact prefix check and incomplete-UTF-8 protection remain intact.

**B. CSV synchronous flush on every generated token**
File: `app/src/main/cpp/core/src/metrics/csv_metrics_sink.cpp`, `CsvMetricsSink::on_token()`. The prior implementation called `fflush()` for each row. This can trigger a synchronous file flush on the generation thread even though the data are diagnostic. It now flushes every 16 rows and at `on_summary()`/close. This is relevant when Metrics CSV is enabled; it is not evidence that the inference kernel itself became faster. If the process is killed abruptly, up to 15 latest rows may be lost.

**C. Per-token process/device memory snapshots even without a metrics sink**
File: `app/src/main/cpp/core/src/engine/session.cpp`, `GenTally::record()`. Process memory and device memory helpers read `/proc` files. The results are CSV diagnostic columns, yet the previous code collected them even when no CSV sink was attached. The call now gates those reads on `sink != nullptr`; numeric inference and summary counters remain unchanged. Memory snapshot columns are zero when sampling was not requested. `app/src/main/cpp/core/include/bmoe/metrics.h` documents this.

**D. Diagnostic feature defaults off for new installs**
File: `app/src/main/java/com/bigmoe/onedge/data/local/datastore/EngineSettingsDataStore.kt`. `metricsCsv` default changes from true to false. The switch remains available at Settings → Diagnostics → Metrics CSV; existing persisted user preference values are read as before and are not forcibly overwritten. Help text now describes the performance/IO cost and recommends opting in while diagnosing a run. This does not remove telemetry; it avoids its costs unless requested.

### Important timing caveat

`app/src/main/cpp/core/src/engine/session.cpp` reports `RunSummary::tokens_per_second` from `n_gen / gen_seconds`, with loop/callback overhead tracked separately as `loop_overhead_s` (near the summary calculation around line 2295 in this archive). The edits to JNI delta handling and CSV writes mostly affect work between decode steps, not the timed kernel interval. Consequently, they may not materially move the displayed decode-only TPS metric even when the chat stream finishes sooner in wall-clock time. Benchmarks should report both decode TPS and total generated tokens divided by wall-clock generation time.

### Upstream/fork differences investigated but deliberately not bulk-ported

The compared engine subtrees share 65 relative paths: 42 identical, 23 modified. The fork-specific differences concern app integration and Android/platform behavior. Upstream-only files include hardware/model probes, planning, device placement, bandwidth probing, and chat/CLI/server helpers; these are not on the Android token decode hot path and depend on broader upstream architecture.

- `core/src/moe/expert_stream_source.cpp`: upstream adds platform/cache pinning paths not present in the Android fork. The upstream code itself has Android-specific `mlock` limitations; pinning can affect residency but is not a demonstrated TPS improvement for this target and can increase memory pressure.
- `core/src/moe/router_hook.cpp`: upstream has host/servable-buffer checks for placement paths not used by the fork's normal CPU inference configuration; the fork's optional Hexagon path is for prefill, not a generic decode accelerator.
- `core/src/engine/prefill_path.cpp`: upstream changes include on-demand/best-effort slot allocation, primarily relevant to prefill and memory use; no evidence showed this is the cause of decode TPS gap.
- `core/src/moe/dense_weights.cpp` and `device_arena.cpp`: differences include allocation/pinning fallback semantics. These affect reliability and platform handling; changing them without a device/Android NDK build would carry risk without a verified decode benefit.
- The 0.29.0 source archive does not include the exact referenced `llama.cpp` submodule source, so a source-compatible native engine upgrade could not be verified. No wholesale submodule replacement or low-level math change was made.

### Plausible bottlenecks not modified without profiling

- `Session::generate()` calls `common_chat_parse()` on cumulative generated text each step in `app/src/main/cpp/core/src/engine/session.cpp` (the `shown_view` path). This can cause repeated parsing/copying for longer replies. A streaming parser redesign could change template/tool/reasoning behavior, so it was not attempted without comprehensive parity tests.
- Actual decoding can be limited by expert-weight I/O, memory faults/cache hit rate, CPU scheduling, SoC kernel support, thermal throttling, or memory pressure. Existing cache and prefetch options are device/workload dependent. Without paired telemetry runs there is no safe basis to increase cache or thread counts or toggle direct I/O/overlap/speculation.
- `app/src/main/cpp/CMakeLists.txt` uses a generic ARM target baseline. The optional `i8mm` architecture variant may help only if that CPU feature is supported and kernels use it; forcing it on unknown devices risks an illegal instruction. No default change was made.
- The fork's current config has optional features including `oDirect`, overlap, I/O lanes, and speculative decoding. Their correctness/performance must be benchmarked with the same model and prompt; defaults were deliberately preserved.

## Prioritized plan and outcome

| Priority | Candidate | Confidence/impact | Risk | Outcome |
|---|---|---|---|---|
| 1 | Remove repeated copy of accumulated JNI text | Confirmed overhead; medium/low absolute cost depending on long output | Low; same bytes and checks | Implemented |
| 2 | Avoid per-token `/proc` snapshots unless CSV diagnostics are enabled | Confirmed needless work in ordinary generation | Low; diagnostic columns become zero when not sampled | Implemented |
| 3 | Batch CSV flushes and default optional CSV off | Confirmed filesystem flush overhead when enabled | Low; abrupt crash may lose last 15 rows | Implemented |
| 4 | Profile expert I/O/cache stalls and overlap | Potentially high but device/model dependent | Medium/high if defaults changed blindly | Not changed; requires paired runtime metrics |
| 5 | Port upstream pinning/planner/prefill changes | Mixed benefit; mostly non-decode paths | High compatibility/memory risk | Not ported |
| 6 | Upgrade vendored llama.cpp | Unknown without upstream submodule snapshot and Android build | High | Not attempted |
| 7 | Incremental chat-template parsing | Likely overhead for long cumulative outputs | High behavior/tool-call parity risk | Not attempted |

## Changes made

1. `app/src/main/cpp/native-bridge.cpp`: delta-only append in `next_delta()`; expected to reduce repetitive copying; no output/model math change.
2. `app/src/main/cpp/core/src/metrics/csv_metrics_sink.cpp`: CSV flush interval is 16 rows, with explicit final-summary flush; expected to lower diagnostic file-I/O overhead.
3. `app/src/main/cpp/core/src/engine/session.cpp`: memory snapshots in `GenTally::record()` only if a metrics sink exists; inference and decode counters unchanged.
4. `app/src/main/cpp/core/include/bmoe/metrics.h`: documented zero-value semantics for unsampled memory fields.
5. `app/src/main/java/com/bigmoe/onedge/data/local/datastore/EngineSettingsDataStore.kt`: default `metricsCsv = false`; existing stored preferences remain respected.
6. `app/src/main/java/com/bigmoe/onedge/ui/settings/SettingsScreen.kt`: clearer Metrics CSV help text.
7. `docs/settings-parity.md`: updated telemetry row and scope caveat; older notes in that file refer to an earlier v0.24 comparison and should not be treated as a measured result for the supplied v0.4.0 archive.

## Verification performed

The commands below were actually attempted from the working copy. Results distinguish source-level checks from full builds.

- `./gradlew :app:testDebugUnitTest --no-daemon` — **blocked before Gradle started** because wrapper download failed with `java.net.UnknownHostException: services.gradle.org`. `ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset, and no `adb`/Android SDK/NDK is installed. JVM tests did not run; no APK was produced.
- Host CMake configure command: `cmake -S /mnt/data/audit_work/hostcheck -B /mnt/data/audit_work/hostcheck/build -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_SUPPRESS_REGENERATION=ON -DCMAKE_EXPORT_COMPILE_COMMANDS=ON` — **passed**. This host-only harness points at the fork's vendored llama.cpp and `core/`; it is not an Android ABI build.
- Host native build commands `cmake --build /mnt/data/audit_work/hostcheck/build --target bmoe_core -j2` and then the same command with `-j4` — **not completed**; both attempts timed out/interrupted while compiling the large vendored llama.cpp/ggml tree. Configure success is not a complete build.
- Syntax-only C++ compile commands reconstructed from the host `compile_commands.json` and run with `-fsyntax-only` — `core/src/engine/session.cpp` **passed**, `core/src/metrics/csv_metrics_sink.cpp` **passed**. These used host GCC and the host configuration, not Android NDK toolchains.
- `native-bridge.cpp` syntax-only check with JDK JNI headers and a minimal local `android/log.h` stub — **passed**; this did not link against Android or validate APK packaging.
- Standalone delta/UTF-8 behavior test built with `g++ -std=c++17 -O2 -Wall -Wextra -Werror` and executed as `/mnt/data/audit_work/next_delta_test` — **passed** for prefix extension, shorter/mismatched prefix, recovery, incomplete UTF-8, and completed multi-byte emoji.
- `python3 scripts/kt_lint.py` — an earlier completed run reported 30 lint findings across 147 files. Its output was compared with the untouched archive baseline and was byte-identical, so no new Kotlin lint findings were introduced in that run. A later repeat attempt with a 120-second outer timeout did not complete; treat that repeat as inconclusive. The existing findings include unresolved symbols/import expectations and are not claimed fixed by this patch.
- `git diff --check` — **passed** in the patch-generation and patch-application worktrees. `git apply --check` also **passed** against a pristine copy of the supplied fork; after applying, all eight changed/new files matched the working tree byte-for-byte. `unzip -t` reported no archive errors.

No runtime model benchmark was performed: there was no attached Android device, Android SDK/NDK, or runnable matching model session. The approximately 3 TPS and 5.6–6.2 TPS values from the request have not been reproduced under identical conditions. No speedup percentage is claimed.

## Recommended benchmark procedure

On the same device and OS build, use the same exact GGUF/mmproj and quantization, prompt/tokenization, context occupancy, output token count, sampler, temperature, thread settings, cache policy, I/O lanes, overlap/speculation options, and thermal/power state. Run at least three alternating baseline/optimized trials after a consistent warm-up. Record model load time, time to first token, prefill tokens/s, decode-only tokens/s, total wall-clock generated tokens/s, cache hits, I/O/stall/compute time, major page faults, CPU time, and temperatures. For comparable CSV diagnostics, enable Metrics CSV in both versions, but also run ordinary chat with it off to assess user-facing overhead. Separate cold-cache and warm-cache tests. The reference screenshot alone is not a controlled benchmark.

## Apply the patch in GitHub Codespaces

1. Download and extract `BigMoeOnEdge-plus-performance-audit.zip`, or copy `BigMoeOnEdge-plus-performance-fixes.patch` into your Codespace.
2. For a patch apply, run from the repository root:

   ```bash
   git apply --check BigMoeOnEdge-plus-performance-fixes.patch
   git apply BigMoeOnEdge-plus-performance-fixes.patch
   git diff --check
   git diff --stat
   git diff
   ```

3. Re-run the Android build in an environment with Gradle 8.9 available, Android SDK 35, and the project's compatible Android NDK installed:

   ```bash
   ./gradlew :app:testDebugUnitTest --no-daemon
   ./gradlew :app:assembleDebug --no-daemon
   ```

4. Inspect output APKs under `app/build/outputs/apk/`. Test on-device before accepting a performance claim.
5. Commit and push only after reviewing the diff and runtime behavior:

   ```bash
   git add app/src/main/cpp/native-bridge.cpp \
     app/src/main/cpp/core/src/metrics/csv_metrics_sink.cpp \
     app/src/main/cpp/core/src/engine/session.cpp \
     app/src/main/cpp/core/include/bmoe/metrics.h \
     app/src/main/java/com/bigmoe/onedge/data/local/datastore/EngineSettingsDataStore.kt \
     app/src/main/java/com/bigmoe/onedge/ui/settings/SettingsScreen.kt \
     docs/settings-parity.md docs/PERFORMANCE_AUDIT_2026-10.md
   git commit -m "Reduce per-token diagnostic overhead"
   git push
   ```

No changes were pushed to a remote repository during this audit.
