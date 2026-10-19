# BigMoeOnEdge+ v0.5.0 - performance investigation release

**In short:** v0.5.0 recovers *part* of the speed gap to the original BigMoeOnEdge. It does not close it, and the remaining cause is not proven.

## Why this release
On the owner's phone, BigMoeOnEdge+ 0.4.5 decoded at about 1.3-1.4 tokens/s while the original BigMoeOnEdge 0.29.0 decoded at about 2.0-2.3 tokens/s with the
same models and settings, on every model tried.

## What changed
* **`android:appCategory="game"`**, as in the original app. Reported by the owner: about 1.3-1.4 -> about 1.7 tokens/s on their phone. Some phones' performance
  layers use this hint; on others it does nothing, and Samsung's game service has been reported to throttle apps it classifies this way.
  Build without it: `-PbmoeAppCategory=undefined`.
* **Fast-core pinning + a small priority boost** for the engine's compute threads. Experimental, not measured. It cannot raise CPU clock speed (that needs root).
  Build without it: `-PbmoePinFastCores=false`.
* **Less UI work while generating:** the streaming cursor and status indicator now blink about twice a second instead of animating every frame. Experimental, not measured.
* **Model picker in the chat:** the chip button in the chat's top bar now opens a list of your models. Tap one to load it (the usual memory check and projector choice follow) or tap the loaded one to unload it; "Manage models..." still opens the Models page.
* **MTP folded away on the Models page:** each model card has a collapsible "MTP draft" section (arrow). Expanded, it holds the sidecar Attach control, a note that **MTP does not work with an mmproj** (the engine refuses an image turn while Guess ahead is on), and the MTP / Guess ahead settings, which moved here from Settings. Rename is now a pencil icon on the model row.
* **Performance & engine is now the first section in Settings.**
* **Diagnostics:** a `Run stats:` logcat line per generation and a CPU-features line (`adb logcat -s OnEdge-JNI`); `scripts/bmoe-capture.sh` for A/B captures.
* App version is now 0.5.0 (versionCode 5). The engine is still BigMoeOnEdge 0.28.0. No inference setting, model format, or saved data changed.

## Measured so far (by the app owner, one phone, uncontrolled conditions)
| Build | Decode tokens/s |
|---|---|
| Original BigMoeOnEdge 0.29.0 | about 2.0-2.3 |
| BigMoeOnEdge+ 0.4.5 | 1.3-1.4 |
| 0.4.5 + `appCategory="game"` | about 1.7 |
| 0.5.0 (all changes) | **not yet measured** |

## Known limitations of this release
* The remaining gap to the original is unexplained. In captures of Plus the phone showed memory reclaim (`kswapd0` at about 85% of a core, 3.8 GB swap in use) and a CPU
  clock ceiling (big cores at 2.19 of 2.80 GHz). The two apps were not compared under identical phone conditions.
* The fast-core pinning, the animation change and the new UI (model picker, collapsible MTP section, Settings order) were validated statically only. The Android build was not run by the author of these changes.
* Whether the vendored llama.cpp matches the original's is unverified (the original's submodule was not available).

## How to test it yourself (no coding)
1. Restart the phone and close other apps. Use the same model, same prompt, and set the same "I/O lanes" in both apps.
2. Generate at least 100 tokens, three times, in each app. Note the tokens/s.
3. If you can run adb, `adb logcat -s OnEdge-JNI` shows `Engine thread tuning:` and `Run stats:` lines. Report them with your numbers.

Full details: `docs/PERFORMANCE_INVESTIGATION_0.5.0.md`.
