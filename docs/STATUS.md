# Source and build status

Date: 2026-11-02

## Current source snapshot: 0.6.5 (`versionCode` 19)

This ZIP contains the updated Android source. It is not a prebuilt APK. Version 0.6.5 collects everything done since
0.6.0 (see `CHANGELOG.md` and `docs/RELEASE_NOTES_0.6.5.md`). `versionCode` is 19 so a build of this release installs
over any of the intermediate test builds.

### Checks made while writing this source

| Check | Result |
|---|---|
| `bash scripts/verify-requested-features.sh` | 27 static integration checks passed (three stale source patterns were corrected in the script) |
| Host `g++ -fsyntax-only` | passed for `lfm3.cpp`, `lfm3vl.cpp`, `clip.cpp`, `mtmd.cpp`, `mtmd-image.cpp`, `llama-arch.cpp`, `llama-model.cpp`, `llama-quant.cpp`, `session.cpp`, `config.cpp`, `prefill_path.cpp` |
| LFM3 chat template | rendered correctly by the vendored Jinja engine (image placeholder, reasoning, tools, tool results, thinking on/off) |
| Kotlin edits | bracket-balance check |

The Gradle build, the unit tests, model loading (LFM3 / LFM3-VL, Flint), the NPU path, the database migration 4→5, the in-app browser and MCP against a real server are exercised by building and running the app on a device.
