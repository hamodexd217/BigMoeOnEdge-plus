# Hexagon HTP prompt-prefill build for Android

The ordinary CPU APK is unchanged and stays CPU-only. The optional NPU workflow builds the app's vendored `ggml-hexagon` backend and HTP DSP skeletons from the same source revision as `libonedge-engine.so`. A Snapdragon 8 Gen 2 provides a Hexagon HTP generation compatible with the v73 skeleton target, but hardware family alone is not proof that the runtime and model graph are compatible.

## Build the NPU APK

Run `.github/workflows/android-npu.yml` with **workflow_dispatch**. The workflow checks free disk before starting; caps native and Gradle build parallelism at two workers; deletes the native build tree and toolchain Docker image after staging; and verifies both the staged files and files extracted back out of the APK. The ordinary `.github/workflows/build.yml` is not modified.

The native configure command must supply `-DBMOE_ENABLE_HEXAGON=ON`, the actual `HEXAGON_SDK_ROOT`/`HEXAGON_TOOLS_ROOT`, and `-DPREBUILT_LIB_DIR=android_aarch64`. This snapshot has a CMake include of `${HEXAGON_SDK_ROOT}/build/cmake/hexagon_fun.cmake`; that helper belongs to the external SDK image rather than this source archive. A known failure mode for this SDK helper is a missing/empty `PREBUILT_LIB_DIR`; however, this source archive already supplied the outer `android_aarch64` selector before this patch. The exact `hexagon_fun.cmake` traceback is not attached, and the nested HTP CMake build also passes its own `toolv19_vNN` selector. This patch adds validation and forces a clean build tree to rule out stale CMake cache/artifacts, but it does not claim the specific error is fixed until a real toolchain workflow successfully configures and builds.

## Payload layout

The build intentionally stages only these Android ARM64 ABI libraries in `app/src/main/jniLibs/arm64-v8a/`:

- `libonedge-engine.so`
- `libc++_shared.so`

The DSP-side skeleton ELF files are **not** Android ARM64 JNI libraries. They are staged as APK assets under `app/src/main/assets/hexagon/`:

- `libggml-htp-v73.so`
- `libggml-htp-v75.so`
- `libggml-htp-v79.so`
- `libggml-htp-v81.so`

Before the native engine is loaded, `HexagonRuntimeSetup` extracts the four files to the app-private `files/hexagon-dsp` directory and prepends that directory to `ADSP_LIBRARY_PATH`, preserving pre-existing paths. On a CPU-only APK, the asset bundle is absent and startup setup safely no-ops.

## Capability states and fail-closed behavior

- **Unavailable**: either the native engine lacks `BMOE_HAVE_HEXAGON`, HTP0 is not registered, or HTP0 cannot open its FastRPC/DSP session. The UI shows the reason reported by native code.
- **Available**: a real `ggml_backend_dev_init(HTP0)` probe succeeded. This proves the backend can open a runtime session, not that a model graph has executed on it.
- **Enabled/ready for a loaded model**: the requested HTP prefill path survived `Session::open`; a failed/missing device makes model loading fail instead of silently clearing the device and continuing on CPU.
- **Execution evidence**: after a generation, logcat reports `NPU prefill evidence: device_tokens=... device_nodes=...`. `device_nodes > 0` is the source's explicit count of graph nodes actually computed by the device backend for that prompt. Zero is not proof of execution. Short or reused prompts can legitimately report zero.

The custom prefill path captures layer weights, moves eligible weights to device buffers, reserves a prefill graph on HTP for chunks wider than `min_tokens`, and rebinds to host state for CPU token-by-token decode. The vendored ggml Hexagon backend advertises only its implemented operation set; model shape/type/backend capability checks determine which actual graph nodes can execute there. A successful probe or bundled file cannot guarantee every model offloads or improves in speed.

## Validation commands

```bash
bash scripts/validate-npu-source.sh
bash scripts/verify-npu-payload.sh app/src/main/jniLibs/arm64-v8a app/src/main/assets/hexagon
```

The payload command requires the real staged ELF files. It checks ARM64 architecture for the Android JNI engine/runtime, Hexagon architecture for DSP skeletons, the NPU-specific native status marker, and SHA-256 values. CI re-extracts the payload from the resulting APK and runs the same verifier against the extracted files.

## Device test on Xiaomi Pad 6S Pro / Snapdragon 8 Gen 2

1. Install the artifact `onedge-npu-debug-apk` from the successful NPU workflow. Do not use the ordinary CPU APK for this test.
2. Open **Settings → Engine performance → Accelerator · NPU prompt prefill**. It must say **Available** because native HTP0 runtime initialization succeeded. If it says Unavailable, keep the reason and collect `adb logcat -d -s OnEdge-HTP OnEdge-JNI`.
3. Turn NPU prefill on, keep **Row-stream tables** and **Guess ahead** off, choose a model, and reload it. Successful load means the HTP path initialized; a failure is expected to contain an explicit HTP reason rather than silently falling back.
4. Clear the conversation or use a fresh, long prompt that exceeds **Minimum prompt tokens sent to NPU**. Capture logcat while generating:

   ```bash
   adb logcat -c
   adb logcat -s OnEdge-HTP OnEdge-JNI
   ```

   Look for `NPU prefill evidence: device_tokens=N device_nodes=M`. Treat `M > 0` as the current implementation's evidence that HTP backend graph nodes ran. `M == 0` means this prompt does not demonstrate acceleration.
5. For the A/B comparison, use the same model file, context size, batch width, thread settings, prompt text and empty/fresh KV context. Run the same long prompt with NPU off and then on (reload the model each time). Record prefill time from the completion stats/logs for 3–5 runs and compare medians. Keep generated token count fixed if comparing total time; decode remains CPU-side. Do not infer a speedup from a single run or from model-load time.

## Verification boundary for this source patch

The source/static checks do not compile the Hexagon engine, create an APK, or prove physical-device runtime behavior. This environment has host CMake/Ninja, but no Android SDK/NDK, no Qualcomm Hexagon SDK/toolchain image available locally, and no attached Android device. The Gradle wrapper failed before Gradle startup because `services.gradle.org` could not be resolved. Therefore no APK or device benchmark is claimed as built here. A successful workflow and nonzero device-node telemetry remain required before claiming NPU execution.
