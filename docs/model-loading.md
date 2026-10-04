# Model loading (Phase 4)

Verified against upstream `examples/android` (v0.24.0):
* Models must be on a real filesystem path: the engine streams experts with `O_DIRECT`. In-app copies land in
  `filesDir/models` (f2fs/ext4). Files read from emulated external storage fall back to buffered I/O.
* Upstream picks the first shard of split models (`-00001-of-0000N.gguf`); siblings are found next to it.
  The model list here hides shards other than the first.
* Upstream defaults: 4 I/O lanes, 4 compute threads, dense weights = anon, expert cache 2000 MiB fixed (auto is
  optional). This app's defaults: auto cache with a 1536 MB RAM floor, 4/4, anon.
* Some models (e.g. Qwen3.8-Flash-Next) need *Pinned* dense weights and a small cache (1000–1500 MiB on 12 GB).

Flow: Models screen → pick `.gguf` (copied with progress, cancellable, space checked) or `adb push` into the
folder shown on screen → "Load" → pre-flight (GGUF magic, RAM < 6 GB warning, cache > 60 % RAM warning, Pinned
warning) → confirm → `Session::open` on the IO dispatcher. The engine reports no progress, so the UI shows an
indeterminate bar. The last loaded path is persisted; "load on app start" is optional (off by default).

Load-time settings (context, sampling, threads, cache, dense weights) are fixed by `Session::open`. Settings
shows "needs reload" and the Models screen offers "Reload".

Heuristic thresholds (6 GB RAM, 60 %) are this project's, not upstream requirements.
