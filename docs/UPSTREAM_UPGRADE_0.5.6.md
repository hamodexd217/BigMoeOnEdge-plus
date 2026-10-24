# Upstream compatibility update — BigMoeOnEdge+ 0.5.6

Date: 2026-10-21

## Inputs and revision boundary

- Customized application archive: `BigMoeOnEdge-plus-0.5.5`, Android version 0.5.5 / versionCode 6.
- Supplied upstream archive: CMake engine version 0.33.2 (its `third_party/llama.cpp` git submodule is absent from the archive).
- The public upstream repository's default branch was observed at commit `50e5843899266ffb458770fb179318e193eae408`; its changelog on that branch says 0.33.3. At the same check, the published GitHub Releases page showed v0.33.0 as the latest tagged release. The linked `Helldez/llama.cpp` submodule revision is `efaae99292ee34851137332c6efd20292b5ad4af`. The container could not resolve external hosts, so that submodule's source could not be fetched and compared locally. See https://github.com/Helldez/BigMoeOnEdge/commit/50e5843899266ffb458770fb179318e193eae408 and https://github.com/Helldez/BigMoeOnEdge/releases.
- The supplied app vendors `llama.cpp` snapshot `965f897` and an engine core based on BigMoeOnEdge 0.28.0 with local multimodal, stateless API, model-registry, and separate-MTP customizations. Neither uploaded archive contains `.git` metadata, so the original app commit SHA and full source commit history cannot be recovered from these ZIPs.

This is a conservative compatibility update, **not** a claim that all upstream 0.29–0.33 code has been merged. The upstream engine has since introduced a larger planner/configuration/runtime layer and expects a newer companion `llama.cpp` fork. Replacing the local core or submodule without porting the app-specific `Session`/JNI contracts would risk the existing MTP, API, and multimodal paths. The provided upstream archive does not include the exact submodule contents needed to complete or verify that broader port in this environment.

## Code changes applied

1. `RouterHook` now identifies whether graph tensors are in host-readable memory. When the selected-expert index tensor belongs to a non-host backend, it copies the tensor through `ggml_backend_tensor_get` after the isolated graph callback instead of dereferencing the accelerator's pointer from the CPU.
2. Cache-aware routing mutations (expert substitution, route-ahead, and deferred dropping) remain limited to host-readable route/weight tensors. Editing a CPU copy cannot change the routing consumed by an accelerator, so the device's own routing remains authoritative.
3. Expert tensors captured from a non-host-owned buffer are not bound to the file-backed expert streamer. This prevents the streaming rebinder from writing through device-owned memory.
4. Weight tracing and route prediction skip reads from device-owned outputs. Host-side layers retain the existing path.
5. Bumped the app version to 0.5.6 / versionCode 7 and the local engine revision to 0.28.1. This is an app compatibility revision, not the upstream 0.33.2 engine version.

## Preserved custom behavior

No Kotlin/Compose UI, package/application ID (`com.bigmoe.onedge`), navigation, Room entities/migrations, DataStore settings, local API routes, approval flow for tools, multimodal Kotlin processing, HTML/SVG/Mermaid artifact UI, split-GGUF import, embedded MTP path, or separate MTP sidecar path was replaced by upstream code. The custom separate-MTP implementation remains architecture-limited to Qwen4Exp (`general.architecture=qwen4exp`); split MTP sidecars remain unsupported as documented by the existing model-management flow.

## Upstream work not ported in this pass

- The 0.29–0.33 hardware-aware `--auto` planner and associated public plan/probe/placement APIs and UI/settings integration.
- The full upstream core refactor (including the newer planner, model-profile and metrics-memory subsystems) into the app-specific `SessionConfig`, `Runtime`, JNI and Kotlin settings contracts.
- The current upstream `Helldez/llama.cpp` submodule revision and its dependent changes. The supplied upstream ZIP omits this gitlink's contents; the container could not resolve external hosts, and the GitHub connector exposed text API results but not a complete binary archive suitable for vendoring.
- End-to-end MTP/model-compatibility verification against the latest upstream native source, because it requires that submodule and an Android NDK build/device run. The input app archive also references `patches/0005-ctx-used-and-speculative-tail.patch` without including that patch file; the relevant implementation is present in the existing tree, but the absent historical patch was not reconstructed from guesswork.

These omissions are intentional safety boundaries, not assertions that those upstream changes are unnecessary. They must remain listed as outstanding rather than being represented as integrated.

## Verification actually run

- `clang++ -std=c++17 -fsyntax-only` passed for the modified `core/src/moe/router_hook.cpp` with include paths from the vendored source tree.
- Static source inspection confirmed the existing model registry includes `qwen35moe`, `gemma4`, `gpt-oss`, `lfm2moe`, `deepseek4`, `bailingmoe3`, `qwen4exp`, and `nemotron_h_moe` rows, and that the separate-MTP loading path remains in `session.cpp`.
- `./gradlew testDebugUnitTest --no-daemon` and `./gradlew assembleDebug --no-daemon` were both attempted, but Gradle could not start: the wrapper needs `https://services.gradle.org/distributions/gradle-8.9-bin.zip`, and DNS resolution failed with `UnknownHostException: services.gradle.org`. Thus no JUnit test actually executed and no APK was produced. No Android SDK/NDK environment or cached Gradle distribution was found, so an APK build/native Android link, GPU execution, and on-device performance benchmark could not run.
- `scripts/validate-upgrade-0.5.6.sh` passed its static identity/feature-presence checks. A `patch --dry-run` is run separately against the supplied 0.5.5 tree for the new code patch before packaging. No before/after token-per-second measurement was taken. The routing fix is a correctness/stability improvement for device-owned routing buffers; a performance gain is not claimed.
