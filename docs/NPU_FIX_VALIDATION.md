# NPU-prefill root-cause and fix record (BigMoeOnEdge+ 0.5.8 source)

## Scope and version identity

The patched archive contains app version `0.5.8` (`app/build.gradle.kts`), a custom engine based on the vendored llama.cpp snapshot noted in `app/src/main/cpp/CMakeLists.txt`, and custom router-hook/prefill code. The separate `BigMoeOnEdge-0.33.2.zip` is not the same app source tree and was not overlaid onto the 0.5.8 project.

## Follow-up CI log analysis (2026-10-22)

The uploaded workflow log `logs_107592831267.zip` materially narrows the build blocker:

- The Snapdragon toolchain image pulled successfully: `ghcr.io/snapdragon-toolchain/arm64-android:v0.8`; the log reported Hexagon SDK `6.7.0.0`, Hexagon tools `21.0.04`, Android NDK `r29`, and `PREBUILT_LIB_DIR=android_aarch64`.
- The Android `onedge-engine` target linked successfully (`[222/222] Linking CXX shared library libonedge-engine.so`).
- HTP skeleton targets `htp-v73`, `htp-v75`, `htp-v79`, and `htp-v81` each reached their link/install steps successfully.
- The build script then exited during `scripts/verify-npu-payload.sh`: GNU `readelf` reported `Machine: QUALCOMM DSP6 Processor`, but the verifier accepted only machine strings containing `hexagon`. That was a verifier bug, not a compiler or disk-space failure. The verifier now recognizes both `Hexagon` and GNU binutils' `QUALCOMM DSPn Processor` spelling.
- The workflow's EXIT cleanup also printed many permission errors while deleting the root-owned build tree produced by Docker. Cleanup now uses `sudo rm -rf -- ...` on the dedicated intermediate directory. These cleanup messages were secondary to the verifier rejection, but could leave disk space unreclaimed.

This fix has been tested with a mocked ELF-verification harness, shell syntax checks, YAML parsing, source checks, and patch-application checks. It has **not** yet been tested by a second GitHub Actions run. Passing payload verification still does not prove a FastRPC session opened on the tablet or that prefill graph nodes executed on HTP; require the post-build APK checks and on-device `device_nodes > 0` evidence.

## Root-cause evidence

1. **CPU-only switch condition was compile-time-only.** Before this patch, `app/src/main/cpp/native-bridge.cpp` added capability bit 2 (`caps |= 4`) just for `BMOE_HAVE_HEXAGON`; `EngineTypes.kt` mapped that bit to `EngineCaps.npuPrefill`, and `SettingsScreen.kt` disabled the switch when it was absent. It did not test HTP registration, FastRPC session open, DSP-skeleton discovery, or real graph execution. The fix only advertises HTP as available after `ggml_backend_dev_init(HTP0)` succeeds and exposes a detailed native status string through the Kotlin settings layer.
2. **Explicit failure silently changed the engine to CPU.** Before this patch, `core/src/engine/session.cpp` logged that prefill would stay on CPU and cleared `im.cfg.prefill.device` if `PrefillPath::find_device()` failed. The fix fails model loading with the returned reason instead. `nativeLoadModel()` also rejects a saved NPU request in a CPU APK or when row streaming/speculation conflicts with it.
3. **DSP files were staged in the wrong APK location.** Before this patch, `scripts/build-hexagon-onedge-engine.sh` copied `libggml-htp-v*.so` into `jniLibs/arm64-v8a`, while native JNI libraries in the APK and Hexagon DSP skeletons have different machine architectures and loading requirements. The fix stages only `libonedge-engine.so`/`libc++_shared.so` as ARM64 JNI libs and puts DSP skeletons in `assets/hexagon`; `HexagonRuntimeSetup.kt` extracts these to app-private disk and prepends the directory to `ADSP_LIBRARY_PATH` before loading the engine. CI checks the machine type of both kinds of file and re-verifies the copy extracted from the APK.
4. **The CMake `string(FIND...)` failure is not reproduced in this environment.** The script already passed `-DPREBUILT_LIB_DIR="$PREBUILT_LIB_DIR"`, defaulting to `android_aarch64`, before this patch; its nested HTP CMake also passes `toolv19_vNN`. The exact `hexagon_fun.cmake` traceback and SDK helper are not included in the uploads, so the failing scope is unknown. The build script now verifies the helper exists, checks the outer selector, starts from a fresh CMake build tree, and clears staged outputs before configure so stale cache/artifacts cannot masquerade as a fresh build. This is mitigation, not proof the SDK configure succeeds; that needs the workflow run.
5. **The supplied follow-up CI run did not hit disk exhaustion.** `logs_107592831267.zip` shows the toolchain image was pulled and the Android engine plus all four DSP skeletons built without a `No space left on device` error. The job stopped at the payload verifier before Android Gradle/APK packaging, so disk behavior through the complete APK build is not yet verified. The cleanup trap also printed permission errors because Docker-created build files were root-owned; the workflow now uses `sudo rm -rf --` for the dedicated native build directory. The standard CPU build workflow remains preserved.

## Prefill implementation evidence from source

- `app/src/main/cpp/native-bridge.cpp`: the NPU request builds a `SessionConfig` targeting `HTP0`; the loaded-model info records `npu_prefill=ready` only after `Session::open` succeeds.
- `app/src/main/cpp/core/src/engine/prefill_path.cpp`: `find_device` checks registry presence and attempts a real device initialization; the path captures eligible layer-weight tensors and prepares device buffers/graph reservation.
- `app/src/main/cpp/core/src/engine/session.cpp`: each prefill chunk is placed on device only when it meets the configured token threshold; the placement is reverted for decode; the run summary records `prefill_device_tokens` and the delta of `RouterHook::device_nodes()` as `prefill_device_nodes`.
- `app/src/main/cpp/third_party/llama.cpp/ggml/src/ggml-hexagon/ggml-hexagon.cpp`: the vendored backend advertises a finite set of operations and supports only qualifying tensor types/shapes. Actual model graph support is not guaranteed by the backend registration alone.

## Performed here vs not performed here

Performed: archive/source inspection; Kotlin/native path tracing; source changes; `bash -n` and source-level validation; YAML parsing; static review of payload/package checks; a mocked positive/negative ELF-verifier regression test; patch-application checks; and analysis of the supplied GitHub Actions logs. The logs prove that the Android ARM64 engine linked and HTP skeletons v73/v75/v79/v81 built and linked before the verifier stopped the job.

Not performed: Android/Kotlin compilation, Android NDK CMake configure, Hexagon SDK compilation, real APK assembly/package extraction, install on Xiaomi Pad 6S Pro, FastRPC/DSP runtime session on hardware, or performance A/B measurements. The local environment used for source work has host CMake/Ninja but lacks the Android SDK/NDK and Qualcomm Hexagon SDK/toolchain. A separate GitHub Actions run did have the SDK/toolchain and got through native compilation, but never reached Gradle because payload verification failed. There is no testable APK from these runs. Do not claim working NPU acceleration until the GitHub NPU build passes and device logcat reports `device_nodes > 0` for a real prompt.
