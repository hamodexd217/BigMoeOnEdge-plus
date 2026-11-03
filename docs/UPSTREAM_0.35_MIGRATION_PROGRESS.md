# Upstream BigMoeOnEdge 0.35.0 migration — progress tranche 03

This is a **partial, additive migration** from `Helldez/BigMoeOnEdge` v0.35.0 into BigMoeOnEdge+ v0.6.0. It is not full upstream parity and is not an Android release APK. Existing project-specific paths were kept intact; in particular the vendored llama.cpp revision was not replaced wholesale.

## Integrated in this tranche

- **Appearance settings start collapsed** (`appearanceExpanded = false`) instead of reopening every time the settings screen is composed.
- **Cache Brake** policy is integrated with the live generation session. It compares token compute time with a per-session reference, samples residency only on a suspected slowdown, and reduces the expert-cache budget only when pages are demonstrably non-resident. It will not shrink below the estimated worst-case expert cycle and leaves the budget unchanged when sampling/floor information is unavailable. It is enabled by default for streamed MoE cache; no new UI toggle was added.
- **OpenAI structured output plumbing** now carries `response_format` from `/v1/chat/completions` through `ParsedChat` → `ChatGenRequest` → Kotlin/JNI → `bmoe::GenerateRequest`. `json_object` becomes a generic object schema; `json_schema` passes the nested JSON Schema to llama.cpp's chat-template grammar. Unsupported formats and malformed schema objects are rejected rather than silently ignored.
- The core applies grammar constraints **before** the sampler so a refused token does not advance the sampling chain/RNG state; constrained turns disable MTP/guess-ahead because the draft path does not track grammar state. A structured response fails explicitly and rolls back the turn if the template cannot produce a grammar, parsing fails, or the completed assistant body is not JSON.
- Added upstream `bmoe::log` implementation/header to the core build.
- Added upstream expert-layout inference and tensor-name helpers to the core build. They can recognize split, fused and gate-less layouts and reject unsupported layouts with a reason. They are currently compiled and unit-tested, but are **not yet wired as a general fallback for architectures missing an existing recipe**.
- Preserved the project's existing Flint modifications, MTP sidecar/embedded path, multimodal `mmproj` handling, prefill/Hexagon work, local API server and current llama.cpp snapshot.

## Validation actually performed

- Full host CMake build of the llama.cpp-dependent `bmoe_core` completed successfully: `cmake --build /tmp/bmoe_full_host_build --target bmoe_core -j4` → `[100%] Built target bmoe_core`. This includes `session.cpp`, the new chat grammar support, and the existing vendored llama.cpp/common targets.
- Host syntax-only compilation of `native-bridge.cpp` passed using the configured compiler flags, local JDK JNI headers, and a temporary stub for Android logging. This is not an Android NDK link/build.
- `cache_brake_test.cpp` compiled and passed all 9 policy checks, including stall detection, steady-run behavior, warm-up, halving and the cache-cycle lower bound.
- Upstream `tests/expert_layout_test.cpp` compiled against the target helper and passed all expert-layout checks.
- Host syntax checks for the logger/expert-layout/tensor-name files passed in the earlier tranche.
- Added Kotlin tests for `json_object`, `json_schema`, unsupported `response_format`, and schema propagation through request resolution. **They could not be executed here**, because the Gradle wrapper could not download Gradle 8.9 (`UnknownHostException: services.gradle.org`).

## Not verified / still pending

- No Android APK was built; the Gradle wrapper download failed because `services.gradle.org` was unreachable. Android SDK/NDK were not available for a real Android build.
- No APK inspection or device runtime tests were performed.
- No real-model structured-output run was possible, so actual model-template grammar support is compile-checked but not runtime-verified. Some model templates may require llguidance, which this target does not enable; those requests are rejected explicitly.
- The full v0.35.0 parameter registry and planner/placement system are **not integrated**. They reference config fields and runtime APIs absent from 0.6.0. An incomplete upstream copy was deliberately not included in active CMake.
- The helper `ExpertLayout` is not yet wired to replace architecture recipes; unknown MoE architectures are still refused instead of guessed.
- Not finished: broad hardware/model probe and multi-device placement migration, automatic routing acceleration, model-management/UI parity, any required dependency upgrade/merge, real Flint GGUF runtime proof, and on-device MTP, `mmproj`, API server or Hexagon/NPU regression tests.

## Important distinction

The host core build and native-bridge syntax check passing are useful checks, but they do **not** prove the Android app builds or that structured output, Flint, MTP, multimodal input, or Hexagon/NPU works on-device. Do not treat this archive as a release-ready APK.
