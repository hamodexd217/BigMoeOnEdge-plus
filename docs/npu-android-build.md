# Hexagon / NPU Android build

The NPU path is intentionally separate from the ordinary Android build.

- CPU-only/default: `./gradlew assembleDebug`
- NPU: GitHub Actions workflow `.github/workflows/android-npu.yml`

The workflow uses upstream BigMoeOnEdge 0.28's pinned Snapdragon toolchain image. The native engine is rebuilt from this exact checkout with `BMOE_ENABLE_HEXAGON=ON`, so the `BMOE_HAVE_HEXAGON` capability bit and the prefill code are part of the actual `libonedge-engine.so` shipped in the APK.

The staging payload contains:

- `libonedge-engine.so`
- `libc++_shared.so`
- `libggml-htp-v73.so`
- `libggml-htp-v75.so`
- `libggml-htp-v79.so`
- `libggml-htp-v81.so`

All four HTP skels are shipped because the ggml Hexagon backend chooses the matching DSP generation at runtime. The settings switch is enabled from the native capability bit, not from a Gradle flag alone.

`-PbmoePrebuiltNative=true` is fail-closed: Gradle verifies the real engine and all four HTP skels before the APK build starts. It also uses a tiny placeholder CMake target so the already-built JNI engine is packaged from `src/main/jniLibs/arm64-v8a` rather than rebuilt by a CPU-only local machine.
