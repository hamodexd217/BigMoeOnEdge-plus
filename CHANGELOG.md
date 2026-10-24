# BigMoeOnEdge+ Changelog

## Unreleased - NPU prefill + MTP fix

- NPU prefill now works together with Guess ahead (MTP and n-gram). Before, three independent gates refused the combination: `config.cpp` ("prefill.device does not combine with speculative decoding"), `native-bridge.cpp` (rejected any `I_SPEC_SOURCE`) and the Settings switch (`npuCombinationAllowed`). Removing the gates alone would have been unsafe, so the engine was also fixed: the MTP draft pass during prefill ran while the target's weights were rebound to the device (and with the arena hooked in), and the MTP head's layer, experts and KV were pulled into the device rebind. See docs/NPU_MTP_FIX.md.
- The NPU "minimum prompt tokens" threshold must now exceed the speculative verify width (draft_max + 1); the app raises it automatically and the Settings list only offers valid values.
- A separate MTP sidecar GGUF is loaded CPU-only while NPU prefill is on, so it does not open a second HTP session.
- MTP / n-gram Guess ahead can now be loaded together with an mmproj. Before, `session.cpp` refused both at load and on any image turn. Image turns decode plainly (the engine already disabled drafting for them), and both contexts' KV is cleared around them, so text turns afterwards re-prefill target and draft consistently.
- Not compiled with the NDK/Hexagon toolchain and not run on a device in this environment.

## Signing fix

- Added a stable release signing key mechanism (`app/build.gradle.kts`, env/secrets driven) and updated the NPU CI workflows to require it, so future APKs share one certificate and install as updates. See docs/SIGNING.md.
- One-time limitation: v0.5.5 and earlier were signed with a throwaway CI debug key that no longer exists, so the first build signed with the new key cannot update them; uninstall is required once.

## 0.6.0 — 2026-10-23

- **Auto settings** (Settings → top card): scans RAM, CPU cores/fast cores, SoC, a quick memory-speed test and whether the NPU runtime is available, then builds settings for the chosen model in three tiers: **Fastest**, **Balanced** and **Most accurate (slowest)**. MoE and dense models get different plans (expert cache, dropped/substituted experts, prefetch, guess-ahead, context, threads).
- Each save shows an **estimated tokens/second**; **Measure** loads the model with that save, generates 64 tokens and records the real speed, then restores the model that was loaded before. A "this may take a while" prompt shows a time estimate first, and measuring can be cancelled.
- **Saves**: named, switchable (Use), renamable, deletable, and downloadable/importable as small JSON files. Switching a save only changes the engine settings Auto settings owns; sampling, tools and theme are untouched.
- Measuring is crash-guarded: plans are kept inside a cautious RAM budget, a save that wouldn't fit is skipped instead of loaded, only one big load exists at a time, the screen stays on while measuring, and a crash marker lowers a save's memory use automatically if the app is killed mid-measurement. The Fastest tier no longer uses expert substitution, two-wave reads or a wide prediction window (the settings its first version crashed a 35B-A3B model with), and its expert-count cut is milder (8 → 6); older saves are cleaned automatically. NPU prefill is opt-in (off by default). Each save has a "Lower memory" button.
- Bumped to 0.6.0 (versionCode 11).

## 0.5.9 — 2026-10-22

- Fixed NPU unavailable at runtime ("HTP0 was not registered (devices: CPU)"): the manifest now declares `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`, which Android 12+ requires before an app can load Qualcomm's FastRPC library that ggml-hexagon dlopen()s.
- Fixed the NPU payload verifier false-failing under `set -o pipefail` (`strings | grep -q` SIGPIPE); it now greps the binary directly.
- Bumped versionCode to 10 so the NPU build installs as an update instead of being mistaken for the CPU-only 0.5.8 APK.

## 0.5.8 — 2026-10-22

- Fixed the Android NPU workflow to run Gradle from the project root and use the correct root-relative APK path.
- Hardened the Hexagon CMake path with explicit SDK/toolchain validation, the existing `PREBUILT_LIB_DIR=android_aarch64` selector, a fresh build directory, and stale-output invalidation. The reported SDK `string(FIND...)` failure is not reproduced here and remains pending a real toolchain CI run.
- Updated the native-build container reference to the documented Snapdragon toolchain `v0.8` image and added ELF/backend-marker/payload validation before packaging and after extracting files back out of the APK.
- Made the Hexagon build script remove only its own staged libraries instead of deleting the entire `jniLibs/arm64-v8a` directory.
- Added a source-level NPU validator and made settings/upstream wiring validators follow the current app version instead of hard-coding 0.5.7.
- Clarified that a successful CI package is not proof of on-device HTP execution; runtime verification on a supported Snapdragon device is still required.

This is an NPU build-pipeline hardening change. It does not claim an APK has been built or benchmarked in this environment.

## 0.5.7 — 2026-10-22

- Reorganized Engine Performance into four grouped cards: Hexagon prefill, MoE streaming/I/O, speed/quality, and CPU compute/memory.
- Added native-wired controls for batch/prefill threads, ubatch width, sampling seed, two-wave I/O, row-stream window, NPU routing thresholds, N-gram matching, drop normalization/prefill, and expert-stream diagnostics.
- Added the upstream decision-prefix-cache policy to the scored-choice API settings.
- Added an explicit force switch for expert-cache sizes below 1500 MiB while keeping the previous BigMoeOnEdge+ default behavior.
- Enabled I/O/compute overlap in fresh-install defaults. The native build still reports overlap inactive when the expert-ready hook is absent or streaming/cache is unavailable.
- Made Hexagon/NPU controls visible in CPU-only builds, disabled with an explanation instead of hiding the settings.
- Added a source-level parity inventory separating supported options from unsupported desktop/GPU/planner/debug-only options.
- Preserved BigMoeOnEdge+ identity, API, database, MTP sidecar flow, and custom UI.

This is a settings/UI/native-wiring update on the existing local engine baseline, not a wholesale merge of upstream 0.33.x.

## 0.5.6 — 2026-10-21

- Fixed host-side handling of routing index tensors whose buffers are owned by a non-host backend.
- Avoided streaming/rebinding device-owned expert weight tensors and restricted route-mutating diagnostics to host-readable tensors.
- Preserved BigMoeOnEdge+ UI, API, multimodal, persistence, split-GGUF, and custom MTP paths.
- Documented the current upstream boundary and verification limits in `docs/UPSTREAM_UPGRADE_0.5.6.md`.

This is a compatibility fix on the existing 0.28.0 engine baseline, revisioned locally as 0.28.1. It is not a wholesale merge of upstream 0.33.x.
