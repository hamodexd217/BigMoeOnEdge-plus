## BigMoeOnEdge+ 0.7.0 — engine synced to upstream 0.39.0

**Heads-up:** the engine core was verified on GitHub Actions on Linux (x64, arm64), macOS and Windows, and the
desktop programs built on all six targets. The Android APK has **not** been run on a phone: treat this as a
release candidate and report anything odd.

### What's new
- **Quantized KV cache.** Settings → Context → *KV cache: keys / values*: f16, q8_0, q5_0, q4_0. Default q8_0
  (about half the cache memory). If the backend can't do a quantized values cache it falls back to f16 on its
  own. **NPU prefill keeps f16.**
- **Prompt checkpoints** for hybrid and recurrent models (Qwen3.5, Nemotron-H, LFM…). Editing earlier text in a
  chat no longer re-reads the whole prompt: the engine rolls back to the nearest saved prompt state. Default 3,
  0 = off. Other models are unaffected.
- **Engine updated to upstream 0.39.0's streaming and I/O stack**, with its fixes: filesystem-aligned direct
  reads, reads past the end of a model file are errors, guard pages on tensor buffers, a fixed aarch64 data race
  in route-ahead's int8 gates, teardown ordering, read-ahead handed back after load, better handling of refused
  memory pins, a cancelled turn no longer leaves the draft model at a different position than the main one.
- **Expert tensors are found from the file itself**, not a per-architecture table: new MoE architectures that
  use the standard expert tensors stream without code changes; unsupported ones are refused with a reason.
- Engine error messages that named a setting now read as plain words.
- **Desktop builds in CI:** `bmoe-cli`, `bmoe-server` and `bmoe-desktop` for Linux, macOS and Windows are built
  from the bundled `upstream/` tree (original project, 0.39.0, unchanged). The Android app is unaffected by them.

### Changed defaults you may notice
- KV cache is q8_0 (was f16). To get the old behaviour, set both to f16.
- Engine version in logs and CSVs reads 0.39.0; the metrics CSV has one extra header line and two extra columns.

### Not included
llama.cpp is not updated (it's the next job), upstream's planner/`--auto`, and the upstream server/web UI/agent
are not part of the Android app. Details: `docs/UPSTREAM_0.39_MERGE.md`.

### Downloads
| File | What |
|---|---|
| `BigMoeOnEdge-plus-0.7.0-android-arm64-v8a.apk` | the Android app, CPU only: works on any arm64 phone |
| `BigMoeOnEdge-plus-0.7.0-android-arm64-v8a-npu.apk` | the same app with the Snapdragon Hexagon NPU backend (v73/v75/v79/v81 DSP skeletons), about 4 MB larger; same package and signature, so it installs over the CPU one |
| `npu-payload-verification-0.7.0.txt` | what the NPU build packaged, with hashes |
| `bmoe-desktop-0.7.0-linux-x86_64.tar.gz`, `…-linux-aarch64.tar.gz` | CLI, server, desktop window |
| `bmoe-desktop-0.7.0-macos-arm64.tar.gz`, `…-macos-x86_64.tar.gz` (experimental) | same |
| `bmoe-desktop-0.7.0-windows-x86_64.zip`, `…-windows-arm64.zip` (experimental) | same |
| `SHA256SUMS.txt` | checksums |

The APK is signed with your release key only if the signing secrets are set (`docs/SIGNING.md`); otherwise with
a throwaway debug key, which can't update over an APK signed with another key. Desktop builds are CPU (+ Metal
on macOS); the GPU/NPU variants upstream ships are not built here.
