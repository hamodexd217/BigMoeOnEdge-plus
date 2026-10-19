# Performance investigation for v0.5.0 (BigMoeOnEdge+ vs BigMoeOnEdge 0.29.0)

**Reported problem:** on the same phone and model, BigMoeOnEdge 0.29.0 decodes at about 2.0-2.3 tokens/s and BigMoeOnEdge+ 0.4.5 at
about 1.3-1.4 tokens/s, for every model tried (so not a model-specific kernel problem).

**Status at v0.5.0:** partly improved (measured by the app owner, see below); **not fully resolved; the remaining gap has no demonstrated cause.**

## How this was done, and its limits
All of this work was done in an environment with no Android SDK/NDK, no Gradle, no Kotlin compiler, no device and no model. The author
of these changes could not build the app, run a benchmark, or test on hardware. Every speed number below was measured by the app owner
on their own phone and relayed; the author did not measure anything. Static checks that were possible were run (see "Verification").

## Measurements (app owner's phone: 8 cores, 11.4 GB RAM, 12 GB swap)
| Build | Decode tokens/s | Source |
|---|---|---|
| BigMoeOnEdge 0.29.0 | about 2.3 (first report), 2.03 steady-state in the CSV run | reported / CSV |
| BigMoeOnEdge+ 0.4.5 | 1.3-1.4 (reported), 1.23-1.35 steady-state in the CSV run | reported / CSV |
| 0.4.5 + `appCategory="game"` | about 1.7 | reported |
| diagnostic build, short runs (9 and 15 tokens, cold cache) | 1.455-1.487 | logcat |

Run counts and thermal state were not controlled, so treat the figures as indicative, not as a benchmark.

### Resolved settings differed between the two apps (from each app's metrics CSV preamble)
| Setting | Original | Plus |
|---|---|---|
| parallel I/O lanes (`io_threads`) | 8 | 4 |
| expert cache, auto-sized from free RAM (`cache_mb`) | 1905 MiB | 1500 MiB |
| cache hit rate | 41-49% | 32-35% |
Both default to 4 I/O lanes, so the original had been set to 8. Everything else matched: threads 4, temperature 0, ANON dense weights,
drop-cold 100%, overlap on, O_DIRECT, context 4096, ubatch 512.

### Per-token steady state (first 5 tokens and any token with more than 1000 hard faults removed)
| | Original | Plus |
|---|---|---|
| tokens/s | 2.03 (493 ms/token) | 1.23-1.35 (739-811 ms/token) |
| compute (residual) | 375 ms | 579-638 ms |
| flash stall | about 89 ms | 103-119 ms |
| cache management | about 30 ms | 51 ms |
| experts routed per token | 480 | 480 |
| experts dropped | 51% | 56% |
Compute is about 80% of the gap. Plus did the same or less expert work yet took about 1.6x longer. Note `compute` is a residual (wall time minus
stall and cache management), so anything else that costs time lands in it.

### What a live capture on the Plus run showed
* Cores 3-6 (max 2.80 GHz) sat at 2.19 GHz in all 15 samples; the fastest core (max 3.19 GHz) stepped down from 2.48 to 2.09 GHz; cores 0-2
  (max 2.02 GHz) were at their maximum throughout. A flat value like that looks like a ceiling (thermal or vendor policy), not load scaling.
* `kswapd0` (memory reclaim) was using about 85% of a core; 41 MB free; 3.8 GB of swap in use; about 254% of CPU time in the kernel.
* About 600 MiB of the Plus process had been swapped out and 80-90% of its dense weights were resident, against none swapped in the original run.
* Engine threads ran at normal priority (nice 0); the UI threads ran at nice -10.

## What was checked and found equivalent
Decode loop, abort callback, graph-hook (`cb_eval`) installation conditions, device selection (Plus's `devices_without_prefill()` keeps an NPU
out of the scheduler), JNI settings mapping, defaults in `config.h`, wake lock and foreground service, CPU target (`armv8.2-a+dotprod+fp16`,
no i8mm, OpenMP off, `GGML_NATIVE` off), Release build flags, `_GNU_SOURCE` (ggml defines it itself on Android), the expert-ready hook being
compiled in so overlap is not silently disabled, and the CPU features compiled in (`neon=1 fp16_va=1 dotprod=1 i8mm=0`, confirmed on the phone).
`GGML_LLAMAFILE` differs (on in the Gradle build, off in the original's recipe) but llamafile's matmul returns early for a single token, so it
cannot affect decode.

## Hypotheses ruled out by the data
* Per-token host work (JNI upcalls, chat re-parse, UI update throttling): the engine measured 1.2 ms/token of loop overhead against about 780 ms/token.
* Sampling and thread count: both apps ran temperature 0 and 4 threads.
* Page-fault bursts: the 21-26 tokens per turn with more than 1000 hard faults took no longer than the other tokens.
* Flash I/O as the main cause: stall is only 89-119 ms of 493-811 ms per token.
* Displayed tokens/s: both apps display the engine's decode-only figure.

## Corrections to earlier statements made during the investigation
* It was stated that both projects pin the same llama.cpp commit (`965f897`). That came from a Plus patch note and **could not be verified**: the original
  repository's llama.cpp submodule is not in its ZIP. An older note in `docs/STATUS.md` says the vendored tree was older; the current tree contains
  newer architectures, so that note is stale. Whether the two llama.cpp trees match is **still unknown**.
* `cpu_s_per_token` is process-wide CPU time. In the original the engine is its own process, so the figure excludes the UI; in Plus the UI shares the
  process and is included. The "about 2x CPU-seconds per token" comparison therefore overstated the difference somewhat.
* The two apps were not tested in the same phone state (Plus first, original about 7 minutes later, memory and heat different), so the comparison is not controlled.

## Changes in v0.5.0 (all unmeasured by their author except where stated)
1. **`android:appCategory="game"`** in the manifest, as in BigMoeOnEdge 0.29.0, filled from the Gradle property `bmoeAppCategory` (default `game`;
   `-PbmoeAppCategory=undefined` restores the old behaviour). Some vendor performance layers use it to pick a CPU profile; Samsung's game service has been
   reported to throttle such apps. **Measured by the app owner: about 1.3-1.4 -> about 1.7 tokens/s on their phone.**
2. **Diagnostic logging, no behaviour change:** one `Run stats:` logcat line per generation (compute / I/O / stall / cache-management time per token,
   CPU-seconds, cores busy, hard faults, cache hit, MiB read) and a one-time `CPU features compiled in:` line. Read with `adb logcat -s OnEdge-JNI`.
   `cores_busy` counts spinning threads as busy, so it cannot prove a core is not throttled.
3. **Fast-core pinning + small priority boost** (`app/src/main/cpp/perf_tuning.h`, used from `native-bridge.cpp`): around each generate call the engine's
   worker thread gets a CPU mask of the fast cores (max frequency at least 75% of the fastest) and nice -4; ggml creates its compute threads from that
   thread on each graph compute and, at its defaults, leaves the inherited mask and priority alone, so they inherit both. Restored afterwards. Does
   nothing (and logs why) if per-core frequencies are unreadable, cores look homogeneous, there are fewer fast cores than compute threads, or the kernel
   refuses. It **cannot change CPU frequency, the governor, or thermal limits**. Build flag: `-PbmoePinFastCores=false` / CMake `BMOE_PIN_FAST_CORES`.
   Motivation was indirect (little cores at maximum clock during generation; doubled CPU time); it is not proven that threads were on little cores.
4. **Less UI work while generating:** the streaming cursor and live-status spinner used infinite animations that redraw every display frame (60-120 Hz);
   `ui/chat/SlowBlink.kt` replaces them with a blink that flips about twice a second. Other screens' spinners are unchanged.
5. `scripts/bmoe-capture.sh`: read-only capture of CPU clocks, memory/swap, thermal and reclaim counters while generating, for A/B comparison.
6. Version bump to 0.5.0 (versionCode 5). The Gradle `versionName` had still said 0.4.0 while the README said 0.4.5.

## Verification performed (and what was not)
* Done: ZIP integrity; patches apply to the pristine 0.4.5 tree and reproduce the working tree exactly; `perf_tuning.h` unit-tested on a Linux host with
  9 checks (fake sysfs trees including the owner's core layout, "never oversubscribe", apply and restore of mask and priority); the bridge-side class and
  the logging helper compiled and run on the host with `-Wall -Wextra -Wformat=2` and zero warnings, and compile to nothing with the flag off;
  `scripts/kt_lint.py` reports the same 30 findings before and after (none in the changed files); `#if`/brace balance of `native-bridge.cpp`.
* **Not done:** an Android NDK build, a Gradle build, a Kotlin compile, a device run, any benchmark by the author. `native-bridge.cpp` as a whole could not be
  compiled (no `jni.h`).

## Open questions (what would settle the rest)
1. Are the two llama.cpp trees the same? Diff `third_party/llama.cpp` against the original's submodule (ggml-cpu kernels and graph code).
2. Does the remaining gap survive a controlled A/B (reboot, same free memory, same temperature, same I/O lanes, 100+ tokens, several runs)?
3. Do v0.5.0's pinning and UI changes help, and which of the three changes is responsible? Use `scripts/bmoe-capture.sh` and the `Run stats:` line.
4. Is the frequency ceiling thermal? Record battery/skin temperature before each run and compare `scaling_max_freq` with `cpuinfo_max_freq`.
