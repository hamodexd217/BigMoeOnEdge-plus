# Validation record — BigMoeOnEdge+ 0.5.7

Recorded on 2026-10-22 in the supplied build container.

| Check | Result | Notes |
|---|---|---|
| Settings/JNI static wiring validator | PASS | Both `scripts/validate-settings-0.5.7.sh` and `scripts/validate-upgrade-0.5.7.sh` passed. The validator confirms the added integer indexes, `INT_COUNT=44`, overlap default, dual-context thread setup, grouped UI, docs and app-version alignment. |
| Kotlin/native integer layout | PASS (static) | Embedded Python checked that `LoadConfig.toIntArray()` contains exactly 44 integers and matches the 44 native config indices. This is not a Kotlin compiler or a linked JNI test. |
| Native C++ syntax checks | PASS (syntax only) | `session.cpp`, `native-bridge.cpp` and `router_hook.cpp` each passed a host `clang++ -fsyntax-only` check. `native-bridge.cpp` used a small Android-log header stub; no NDK link was performed. |
| Existing identity/engine validation | PASS by source inspection | Package ID and namespace remain `com.bigmoe.onedge`; custom native engine remains revision 0.28.1; custom expert-ready route and Qwen4Exp MTP code remain present. |
| Kotlin source balance/import scan | PARTIAL | `scripts/kt_lint.py` scanned 108 files. Balanced delimiters passed; it reports 30 pre-existing import-use heuristics in unrelated files. It is not a Kotlin compiler. |
| Unit tests | NOT EXECUTED | `LoadConfigTest` now asserts new JNI slots and overlap default, but Gradle wrapper startup cannot download Gradle 8.9 because DNS/network access to `services.gradle.org` fails. |
| Android APK | NOT BUILT | `./gradlew assembleDebug --no-daemon` exited before Gradle starts with `UnknownHostException: services.gradle.org`. No APK is produced or attached. |
| Native NDK linked build / Snapdragon GPU or NPU runtime test | NOT RUN | No successful Android toolchain build or physical-device runtime test was available. |
| Performance benchmark | NOT MEASURED | No tokens/s improvement is claimed. |
| User identity/data compatibility | SOURCE CHECK ONLY | Package ID, Android manifest, Room schema source, existing API endpoints and existing DataStore key names were preserved. Runtime migration/regression tests could not run. |

## Important limitations

This is a settings/JNI integration on BigMoeOnEdge+'s existing custom core, not a wholesale merge of upstream 0.33.x. Controls which require core code that is absent from the supplied app—GPU/device placement (`gpu-layers`, `dense-on-device`, `tensor-overrides`, `device-split`, `devices`), newer prefill best-effort/verification/slot-budget policies, and cache pin/brake/fit—are documented in `UPGRADE_SETTINGS_0.5.7.md` as not ported, rather than shown as live no-op settings. `decide` remains enabled to preserve the existing scored-choice API; its prefix-cache policy is configurable.

The default CPU-only APK does not contain the Hexagon backend. Snapdragon 8 Gen 2 hardware alone cannot enable NPU inference; the native library and matching HTP/DSP payload must be built and packaged via `.github/workflows/android-npu.yml`. That path targets prompt prefill; decode remains CPU-side in this implementation. The I/O/compute overlap preference defaults on, but actual overlap requires streamed MoE plus a native library compiled with the expert-ready hook and cache-enabled operation. The loaded-model status reports whether it became active.
