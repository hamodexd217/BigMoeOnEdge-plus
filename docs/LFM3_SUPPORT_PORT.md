# LFM3 support port — BigMoeOnEdge+ 0.6.5

Selective source port of upstream llama.cpp PR #31252 ("LFM3-1.7B and LFM3-VL-2B", released in llama.cpp b11960 / v0.8.0) onto the vendored tree. The vendored engine, the project's own `core/` and the expert-streaming patches are untouched.

## Included
- `lfm3` architecture: enum, name, hybrid-memory / recurrent-rollback / split-mode switches, loader factory, RoPE type, quantizer rule (`conv2d_spatial.weight` stays F32).
- `src/models/lfm3.cpp`: hparams, tensors and graph (short-convolution layers, sliding-window and global attention with head-wise gating, partial RoPE through the `rope_freqs` tensor, sandwich norm). The PR derives from a shared `llm_build_lfm_base` this tree does not have, so the short-conv block and dense FFN are carried locally inside `lfm3.cpp` (copied from this tree's own `lfm2.cpp`). LFM2 / LFM2-MoE code was not changed.
- Vision (`tools/mtmd`): `PROJECTOR_TYPE_LFM3VL`, tensor names, clip loading, graph (`models/lfm3vl.cpp`), token counting, 2D-RoPE position input, `smart_resize` preprocessor, `<|vision_start|>` / `<|vision_end|>` wrapping.

## Verified here
- `lfm3.cpp`, `llama-arch.cpp`, `llama-model.cpp`, `llama-quant.cpp`, `clip.cpp`, `lfm3vl.cpp`, `mtmd-image.cpp`, `mtmd.cpp` all pass `g++ -fsyntax-only` against this tree's headers.
- The LFM3 Jinja chat template (from the PR) renders correctly with this tree's Jinja engine for: system + user + image + assistant-with-reasoning, and tool definitions + tool call + tool response, with `enable_thinking` on and off.

## NOT verified / known limits
- No Android build, no model load, no generation, no image test, no device test. Nothing here has ever run against a real LFM3 GGUF.
- Vision resize: the official processor uses torchvision-style resizing (`RESIZE_ALGO_FLAG_TORCH`), which this tree's mtmd does not have. Plain bilinear / bicubic is used, so image embeddings differ slightly from the reference; quality should be close but is untested.
- Only `LFM3` text and `LFM3-VL` vision are ported. LFM3 Audio, Omni and the d1 decision models (PRs #31257, #31275) and video (#31283) are not.
- Vision runs through the existing CPU-side mmproj path. `CONV_2D_DW` / `ROPE` on other backends is unverified.
- `convert_hf_to_gguf` support (the PR's Python parts) is not included; use a ready-made GGUF plus its `mmproj` GGUF.
