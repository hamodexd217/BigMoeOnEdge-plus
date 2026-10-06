# BigMoeOnEdge's Real API - Verified Notes

> **STATUS UPDATE (later in the same project, after this doc was first
> written):** `core/include/bmoe/session.h` itself was located and vendored
> into this package at `app/src/main/cpp/core/include/bmoe/session.h` (copied
> directly from the real `BigMoeOnEdge-0.24.0` release zip, diffed byte-for-
> byte against the three reference files below - all three were confirmed
> **identical** to the real repo). Every field below that was previously
> marked **[inferred]** based on usage in `session.cpp` is now independently
> confirmed against the actual struct declaration. None of the inferences
> turned out wrong. Separately: a *different* AI assistant, in the same
> project, was once asked to reproduce `session.h` from memory and fabricated
> one (wrong `DenseWeightsMode` values, wrong `RunResult` shape, public
> constructor, missing `clear_kv`/`render_text`/`cancel()`) - that fabricated
> version was caught and discarded before it touched any code. It's mentioned
> here only as a standing warning: **never let an assistant regenerate a bmoe
> header from memory** - always paste the real file (now vendored under
> `core/include/bmoe/`, no need to re-fetch it).

This documents the **actual, verified** `bmoe` C++ API, confirmed by reading
the real uploaded source files (`docs/bmoe_real_config.h.txt`,
`docs/bmoe_real_runtime.h.txt`, `docs/bmoe_real_session.cpp.txt` in this same
folder - copies of BigMoeOnEdge's `core/include/bmoe/config.h`,
`core/include/bmoe/runtime.h`, and `core/src/engine/session.cpp`).

Everything below is grounded in that source, not guessed. Where something is
still inferred (not directly read), it's marked **[inferred]**.

## Why this doc exists

Across this project's chat history, an AI assistant repeatedly guessed at
this API (class names `bmoe::Engine`, methods `init()`/`get_llama_context()`,
struct fields that don't exist) before the real source was available. Those
guesses are **wrong** and should not be trusted if they resurface. This file
is the corrected reference.

## The real API surface

### `bmoe::Session` (`core/include/bmoe/session.h`, impl in `core/src/engine/session.cpp`)

Private constructor - the only way to get one is the static factory:

```cpp
static std::unique_ptr<Session> Session::open(
    const SessionConfig & cfg,
    std::string & error,                  // filled on failure
    IRouteTraceSink * route_trace = nullptr,
    IComputeTraceSink * compute_trace = nullptr,
    IIoTraceSink * io_trace = nullptr);
```
Returns `nullptr` + `error` set on failure, or a valid session on success.

```cpp
RunResult Session::generate(
    const GenerateRequest & req,
    const std::function<void(const TokenMetrics &)> & on_token,
    IMetricsSink * sink = nullptr);
```
Blocks until generation completes, is cancelled, or errors.
**Real per-token streaming exists**: `on_token` fires once per generated
token (even under speculative decoding - it's called once per *accepted*
token). `TokenMetrics.piece` always carries that token's raw decoded text
delta. If `GenerateRequest.render_text` is true, `TokenMetrics.text` /
`.reasoning` additionally carry a cumulative, re-parsed "clean" view (chat
template / thinking-tag aware) - more expensive (full re-parse every token)
but display-ready.

```cpp
void Session::cancel();          // atomic flag; the documented way to abort
double Session::load_seconds() const;
const std::string & Session::arch() const;
int Session::n_ctx() const;
int Session::n_expert_used() const;
ThinkControl Session::think_control() const;
void Session::set_cache_budget_mb(int mib);
PplResult Session::perplexity(const PplRequest & req);
```

**No method on `Session` exposes the underlying `llama_context*` or accepts
image/embedding data.** This was checked directly - `Session`'s only public
members are the ones listed above. `llama_context` lives inside a private
`Session::Impl` (pimpl).

### `GenerateRequest` (real fields, confirmed via `req.*` usage in `generate()`'s body)

```cpp
struct GenerateRequest {
    std::string prompt;
    int n_predict;
    bool think;        // per-request enable_thinking
    bool clear_kv;      // true = start a new chat turn from scratch; false = continue
    bool render_text;   // true = also populate TokenMetrics.text/.reasoning (costs a re-parse per token)
    // there may be more fields not exercised by the code paths we read
};
```
**No grammar/GBNF field.** Per-call structural output constraints (as Phase
6's tool-calling design wanted) have no integration point here.

### `SessionConfig` (real fields, confirmed via `cfg.*` usage in `Session::open()`)

Mirrors `RunConfig` (below) minus the one-shot-only fields (`prompt`,
`n_predict`, `progress`, `compute_trace_layers` stay on `RunConfig`;
`n_ctx`, `n_threads`, `n_batch`, `n_ubatch`, `chatml`, `n_expert_used`,
`sampling`, `moe`, `spec` are shared/confirmed present on `SessionConfig` too).

```cpp
struct SessionConfig {
    std::string model_path;
    int n_predict = 128;      // [inferred - RunConfig has this; unclear if
                               // SessionConfig also carries a default or if
                               // it's GenerateRequest-only in practice]
    int n_threads = 4;
    int n_ctx = 2048;
    int n_batch = 0;           // confirmed used (im.cfg.n_batch, cfg.n_batch)
    int n_ubatch = 0;          // confirmed used (cparams.n_ubatch = ...)
    bool chatml = false;       // confirmed used (cfg.chatml, ri.chatml)
    int n_expert_used = 0;     // confirmed used (kv_override logic)
    SamplingConfig sampling;
    MoeStreamConfig moe;
    SpecConfig spec;
};
```

### `SamplingConfig`, `MoeStreamConfig`, `DenseWeightsMode`, `SpecConfig` - copied verbatim from the real `config.h`

See `docs/bmoe_real_config.h.txt` for the full, exact, real header (this is
the authoritative source - re-read it directly rather than trusting any
paraphrase, including this one, if in doubt).

Key gotchas an earlier draft got wrong and should NOT be reintroduced:
- `DenseWeightsMode` is an **enum**: `{ Mmap, Warmed, Anonymous, Pinned }` -
  not a string like `"anon"`.
- `MoeStreamConfig.enabled` **must be set to `true` explicitly** - it
  defaults to `false`, silently disabling all expert streaming (the entire
  point of this engine) if forgotten.
- `SamplingConfig` has `temp, top_k, top_p, seed` - no `penalty_repeat` field
  exists.
- `SpecConfig` has `source` (a `DraftSource` enum: `none`/`mtp`/`ngram`),
  `draft_max`, `draft_p_min`, `ngram_min_match`, `ngram_max_match` - not a
  simplified `{enabled, draft_tokens}` shape.

### `bmoe::run()` (`core/include/bmoe/runtime.h`) - NOT what we use

```cpp
RunResult run(const RunConfig & cfg,
              const std::function<void(const TokenMetrics &)> & on_token = nullptr,
              IMetricsSink * sink = nullptr,
              IRouteTraceSink * route_trace = nullptr,
              IComputeTraceSink * compute_trace = nullptr,
              IIoTraceSink * io_trace = nullptr);
```
A one-shot wrapper: open + one generate + close, in one blocking call. Not
suitable for our persistent multi-turn chat session - we use `Session`
directly instead, which is why this app's native bridge targets `Session`,
not `run()`.

## Two integration gaps that remain genuinely unresolved

These are real limitations of the current upstream `Session` API, not
implementation bugs in this app. Someone needs to decide how to handle each:

### Gap 1 - Multimodal (images/video)

`GenerateRequest` takes only a text `prompt`. No method anywhere accepts
image bytes, CLIP embeddings, or exposes `llama_context*` for a caller to
inject `embd`-based batches itself (which is what `vision_projector.cpp`'s
`process_image_file()` was written to do in Phase 3, before this was known).

Options:
- **(a) Patch BigMoeOnEdge itself** - add something like
  `Session::inject_image_embeddings(...)` to `session.h`/`session.cpp`
  (estimated non-trivial: needs llava/clip linkage, an embd-based prefill
  path, and interaction with the expert-streaming hook if vision layers are
  also MoE-routed). This is a change to the upstream engine, not just this
  app - larger scope, but the "real" fix.
- **(b) Defer multimodal entirely** for now: stub `nativeEvalImage`/
  `nativeEvalVideoFrames` (they already fail closed/return false since
  `g_llama_ctx` is always null now), ship text-only, revisit if/when
  upstream adds a hook.
- **(c) Pre-process outside the engine**: run a small separate
  vision/OCR/captioning step, inject the *result as text* into the prompt.
  Loses real visual grounding but needs zero engine changes.

### Gap 2 - Per-call GBNF / structured tool-call output

`GenerateRequest` has no grammar field, so Phase 6's plan (constrain
sampling to force valid `<tool_call>{...}</tool_call>` JSON) has no
enforcement point at the sampler level right now. `nativeGenerate()` in this
package currently accepts and silently ignores the grammar string (logs a
warning) - tool-call reliability depends entirely on prompting, with
`StreamOutputParser` handling whatever the model actually emits.

Options:
- **(a) Patch BigMoeOnEdge**: add `std::string grammar` to `GenerateRequest`,
  wire it to `llama_sampler_init_grammar` inside `generate()` (estimated
  smaller change than Gap 1 - it's an additive field plus a few lines in the
  sampler setup).
- **(b) Prompt-only + client-side repair**: keep prompting for JSON and
  parsing leniently (already effectively what's happening), accept lower
  reliability, retry on parse failure.

## Fields available for the UI (telemetry pill etc.)

`RunResult.summary` (a `RunSummary`) is populated by `generate()` with, among
others: `tokens_per_second`, `s_per_token`, `n_generated`, `n_prompt`,
`load_seconds`, `cache_hit_pct`, `cache_resident_mib`. This maps directly to
the `ChatMessageBubble` telemetry pill (tokens/sec, etc.) - richer than what
Phase 5's original hand-rolled loop tracked.
