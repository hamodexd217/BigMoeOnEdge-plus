# Flint support port — BigMoeOnEdge+ 0.6.0

This is a selective source port of the upstream llama.cpp Flint model implementation (PR #30928) onto the vendored source already present in this app. The original vendored snapshot and project-local Qwen4Exp/expert-ready-hook code are retained; the upstream 0.35.0 `core/` is not substituted.

## Included native changes

- `flint` architecture registration and loader factory wiring.
- Flint model tensor layout and compute graph: leading dense blocks, routed experts, one shared expert branch, Q/K normalization, optional per-layer RoPE, sliding/full attention patterns and attention sinks.
- Flint BPE pre-tokenizer regex, `tokenizer.ggml.pre=flint` recognition, `clean_spaces=false`, and Flint end-of-generation token handling.
- A conservative F32 source-precision fallback for Flint's MoE down-projection path (the older vendored snapshot does not have the exact upstream `build_amax_src1_f16()` helper), plus a KV-shift guard for mixed RoPE/no-RoPE layers. This fallback may reduce performance and is not claimed to be byte-for-byte identical to upstream PR #30928.
- BigMoeOnEdge+ expert-streamer recipe for the standard split expert tensor names.
- Jinja `\xNN` string escape parsing needed by Flint's native chat template.

## Important limits

- This is not a replacement of the entire llama.cpp tree and does not implement a HF-to-GGUF converter in the Android app.
- No Android APK build, runtime model load, generation smoke test, memory test, cancellation test, or device test is claimed by this patch package.
- A Linux CMake build of the native `llama` target succeeded, including `src/models/flint.cpp`; the modified Jinja `lexer.cpp` translation unit also compiled directly. The full `llama-common` target was not completed within the execution window, and no Android Gradle/APK build was performed.
- Flint model loading is intended for CPU first. Snapdragon Hexagon/NPU support for Flint remains unverified and must not be inferred from the generic backend's existence.
- Any real runtime validation still needs a known-good Flint GGUF and tests of tokenizer/template parsing, first-token output, tool turns, and expert streaming.

## Upstream basis

- https://github.com/ggml-org/llama.cpp/pull/30928 (Flint model support)
- https://github.com/ggml-org/llama.cpp/pull/30929 (Jinja `\xNN` escapes)
- Upstream reference tree pin used by BigMoeOnEdge 0.35.0: `ad98071a583d66c922ba36264433e0435f0e0b42`
