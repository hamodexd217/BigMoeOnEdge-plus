# Model loading (Phase 4)

Historical note: the baseline assumptions below were first cross-checked against upstream `examples/android` (v0.24.0). The current app vendors the BigMoeOnEdge 0.28.0 core with local patches; use the current no-copy workflow and release/status notes for the v0.4.0 behavior.

Baseline behavior and storage notes:
* Models must be on a real filesystem path: the engine streams experts with `O_DIRECT`. In-app copies land in
  `filesDir/models` (f2fs/ext4). Files read from emulated external storage fall back to buffered I/O.
* Upstream picks the first shard of split models (`-00001-of-0000N.gguf`); siblings are found next to it.
  The model list here hides shards other than the first.
* Upstream defaults: 4 I/O lanes, 4 compute threads, dense weights = anon, expert cache 2000 MiB fixed (auto is
  optional). This app's defaults: auto cache with a 1536 MB RAM floor, 4/4, anon.
* Some models (e.g. Qwen3.8-Flash-Next) need *Pinned* dense weights and a small cache (1000–1500 MiB on 12 GB).

Flow: Models screen → **Add GGUF from storage (no copy)** to register a GGUF already in shared Internal storage,
or **Import GGUF file(s) (copy)** to copy a selected file into app-private storage with progress, cancellation,
and free-space checks, or `adb push` into the folder shown on screen → "Load" → pre-flight (GGUF magic, RAM < 6 GB
warning, cache > 60 % RAM warning, Pinned warning) → confirm → `Session::open` on the IO dispatcher. The engine
reports no progress, so the UI shows an indeterminate bar. The last loaded path is persisted; "load on app start"
is optional (off by default).

Load-time settings (context, sampling, threads, cache, dense weights) are fixed by `Session::open`. Settings
shows "needs reload" and the Models screen offers "Reload".

Heuristic thresholds (6 GB RAM, 60 %) are this project's, not upstream requirements.

## Direct paths on Android 11+

The default **Add GGUF from storage (no copy)** action opens Android's document picker. When the selected item comes from Android's External Storage DocumentsProvider, the app resolves it to its real filesystem path, validates the GGUF header, and registers it without copying. Browse to **Internal storage** in the picker for files in the phone's shared storage. Some cloud/Downloads/provider URIs do not expose a real path; use **Import GGUF file(s) (copy)** for those. An **Advanced: enter a path manually** section remains available for troubleshooting.

On Android 11 and newer, Android generally blocks arbitrary shared-storage paths until the user explicitly grants this app **Allow access to manage all files** in the system settings page. The no-copy flow opens that settings page only when needed and retries registration after returning. If the user does not grant access, Import remains available and copies the selected file into app-private storage.

The app declares `MANAGE_EXTERNAL_STORAGE` solely to support this opt-in large-file/no-copy workflow. This is a restricted Android permission and may not be accepted for distribution through Google Play; distribution channels must comply with Google's current all-files-access policy. Direct reads from emulated/shared storage can also fall back to buffered I/O, so permission enables access but does not guarantee the same I/O performance as app-private storage.

