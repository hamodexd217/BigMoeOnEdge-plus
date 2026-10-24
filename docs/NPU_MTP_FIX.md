# NPU prefill + MTP: root cause and fix

## Symptom
Turning on **NPU prefill** while **Guess ahead = MTP** was on either could not be selected (the switch was
greyed out) or the model refused to load with "NPU prefill was requested together with Row-stream tables or
Guess ahead".

## Exact cause
Three separate gates rejected the pair, and behind them the engine was not safe for it:

1. `core/src/config.cpp` — `if (cfg.spec.enabled()) return fail("prefill.device does not combine with speculative decoding")`.
2. `native-bridge.cpp` (`nativeLoadModel`) — refused any load with `I_NPU_PREFILL` and `I_SPEC_SOURCE != 0`.
3. `ui/settings/SettingsScreen.kt` — `npuCombinationAllowed = !rowStream && spec == OFF` disabled the switch.

The gates existed because the engine genuinely broke when speculation ran under a prefill device:

* **Draft pass under device placement.** In `Session::generate`, each prefill chunk calls `place_prefill(true)`
  (weights rebound to HTP, expert arena hooked into `RouterHook`), runs the target decode, and then ran
  `common_speculative_process()` on the MTP draft context *before* placement was reverted. That context shares the
  model and the router callback, so its graph was scheduled against device-bound weights, and the hook took the
  device-arena branch (no host streaming for the MTP head's experts) without `begin_graph()` having been called.
* **The MTP head leaked into the rebind.** With streaming + embedded MTP, the capture warm-up also ran the draft graph,
  so `blk.<n_layer>.*` weights, the head's expert layer and the draft context's KV (`cache_*_l<n_layer>`) were all
  moved to the device / arena. The draft context is cleared and rolled back through its own memory module, which does not
  know the tensors moved, and the arena paced and read an extra layer on every device graph.
* **Width invariant.** The rebind is safe only if every device graph is wider than every CPU graph (llama.cpp reuses a
  same-shaped graph without re-scheduling it). Speculation makes the CPU verify graph `draft_max + 1` wide, not 1, so the
  device threshold has to clear it.

## Fix
* `config.cpp`: the blanket ban is replaced by the real condition, `prefill.min_tokens > spec.draft_max + 1`.
* `session.cpp`: the draft pass now runs after `place_prefill(false)`; `PrefillPath::open` receives the trunk layer count
  (`im.n_layer`); a separate MTP sidecar model is loaded with a CPU-only device list when a prefill device is active.
* `prefill_path.cpp/.h`: layer weights, the dense/expert arena and the state tensors are restricted to trunk layers
  (`il < n_layer_trunk`); `RouterHook::learned_layer_ends(n)` checks only the trunk.
* `native-bridge.cpp`: only Row-stream still conflicts; the NPU threshold is raised to `draft_max + 2` if needed.
* Kotlin: `LoadConfig.npuMinTokensFloor`, the Settings switch no longer depends on Guess ahead, and the threshold list only
  offers valid values. Unit test: `LoadConfigTest.npuMinTokensClearsTheSpeculativeVerifyWidth`.

## Verification status
Host `g++ -fsyntax-only` passes for `config.cpp`, `prefill_path.cpp` and `session.cpp`; `scripts/validate-npu-source.sh`
passes. Not done here: Gradle/JUnit, NDK/Hexagon build, any on-device run. Confirm on the tablet with MTP + NPU prefill on:
logcat should show `NPU prefill evidence: ... device_nodes > 0` and MTP acceptance in the run summary.
