You are finishing the release of BigMoeOnEdge+ v0.5.0 (Android app, Kotlin + Compose + vendored C++ engine) from the ZIP I attach.
Work like an engineer with file/terminal access. Report to me in the language I write to you in: short, plain, no jargon. Keep code, comments and commit
messages in English. Do not stop between steps to ask me to test: finish everything you can, then give me ONE consolidated checklist of what only I can do.

HONESTY RULES (non-negotiable)
* Never claim something compiled, ran, or got faster unless you ran it and saw the output. Quote real output.
* Never invent benchmark numbers. A number goes in a release note only if you measured it, with run count and conditions.
* Do not change any inference setting (model, quantization, context, sampling, threads, KV cache, I/O lanes, cache size) to make a number look better.
* Minimal edits. Do not rewrite stable parts. Commit after every step. Never recreate a file from memory; read it.

FIRST
1. Unzip, `git log --oneline` if a repo exists, then read `docs/PERFORMANCE_INVESTIGATION_0.5.0.md` completely. It records what was measured, what was ruled out,
   corrections to earlier assumptions, what changed in v0.5.0, and what is NOT verified. Then read `docs/RELEASE_NOTES_0.5.0.md` and the "What's new in v0.5.0" section of README.md.
2. Verify its claims yourself by reading the files named there. If anything disagrees with the source, the source wins; fix the doc.

WHAT v0.5.0 CHANGED (all of it was validated statically only; nothing was compiled or run by its author)
* `app/src/main/AndroidManifest.xml` + `app/build.gradle.kts`: `android:appCategory="${bmoeAppCategory}"`, default `game` (Gradle property `bmoeAppCategory`).
* `app/src/main/cpp/perf_tuning.h` (new), `native-bridge.cpp`, `app/src/main/cpp/CMakeLists.txt`, `app/build.gradle.kts`: fast-core pinning + nice -4 on the engine worker
  thread around each generate call; CMake option `BMOE_PIN_FAST_CORES` (default ON), Gradle `-PbmoePinFastCores=false`. Also `Run stats:` and `CPU features compiled in:` logcat lines.
* `app/src/main/java/com/bigmoe/onedge/ui/chat/SlowBlink.kt` (new), `ChatMessageBubble.kt`, `ChatScreen.kt`: infinite animations replaced by a ~2 Hz blink.
* Models/Settings UI: `ui/models/ModelPickerButton.kt` (new; chat top-bar chip opens a model list with Load/Unload), `ui/models/MtpSettingsPanel.kt` (new; MTP / Guess-ahead settings moved here from Settings),
  `ui/models/ModelScreen.kt` (collapsible MTP section per model, Rename as an icon, `PendingLoadDialog` extracted so the chat picker can reuse it), `ui/models/ModelViewModel.kt` (four MTP setters),
  `ui/settings/SettingsScreen.kt` (Performance & engine moved to the top; the Guess-ahead block removed), `ui/chat/ChatScreen.kt` + `ui/navigation/AppNavigation.kt` (new `modelViewModel` parameter).
* Version 0.5.0 / versionCode 5; `scripts/bmoe-capture.sh`.

STEP 1 - BUILD GATE (the part nobody has done yet)
Environment needed: JDK 17+, Android SDK, NDK 27.2.12479018, CMake. If any is missing, say so and stop that step; do not report a source failure when compilation never started.
a. `python3 scripts/kt_lint.py app/src` (baseline is 30 known findings; no new ones).
b. `./gradlew assembleDebug` and `./gradlew assembleRelease` (arm64-v8a). Fix compile errors ONLY in the files listed above, with the smallest change. Show me the errors and the fixes.
c. `./gradlew testDebugUnitTest`.
d. Inspect the APKs: confirm `lib/arm64-v8a/libonedge-engine.so` is present, the merged manifest has `android:appCategory="game"`, versionName 0.5.0, versionCode 5.
e. UI check on a device (or emulator; the UI does not need the model): (1) in a chat, the chip icon opens a list of registered models; Load starts the normal confirmation dialog, Unload unloads, Unload is disabled while a reply is generating, and "Manage models..." opens the Models page. (2) On the Models page the MTP section is folded by default; the arrow expands it; expanded it shows the mmproj note, Attach, and the Guess-ahead settings; changing them shows the reload prompt. Rename works from the pencil icon. (3) Settings opens with "Performance & engine" first; its Experimental part no longer has Guess ahead. Report anything that differs.
f. Rebuild with `-PbmoeAppCategory=undefined -PbmoePinFastCores=false` and confirm the manifest and the native flag really change.

STEP 2 - DEVICE TESTS (only if a device and the model are available; otherwise skip and list it in my checklist)
Same phone, same model file (SHA-256 recorded), same prompt, temperature 0, same threads, I/O lanes and cache settings in every variant. Restart the phone and let it cool to the same
battery temperature before each variant. At least 100 generated tokens, at least 5 runs per variant, discard the first (cold cache), report every run plus the median and spread.
Variants (build each with the Gradle flags): A = 0.4.5 reference; B = v0.5.0 with `-PbmoeAppCategory=undefined -PbmoePinFastCores=false`; C = v0.5.0 `-PbmoePinFastCores=false`; D = v0.5.0 default.
(The UI change cannot be toggled; A vs B also differs by the UI change and diagnostics.)
For every run, capture `adb logcat -s OnEdge-JNI` (confirm the `Engine thread tuning:` line shows `affinity=1`; if `affinity=0` say why) and run `scripts/bmoe-capture.sh`.
DECISION RULE: keep fast-core pinning ON only if variant D's median decode tokens/s is not worse than C's beyond run-to-run spread. Otherwise change the Gradle default to off,
rebuild, and say so. Same rule for `appCategory`: keep `game` only if C is not worse than B.

STEP 3 - OPEN QUESTIONS (only if I give you the files)
If I provide the original BigMoeOnEdge's `third_party/llama.cpp` (its submodule), diff its `ggml/src/ggml-cpu` and graph/model code against this project's vendored copy and report any
difference that could affect decode speed. Do not copy the whole tree over; port only changes that are justified and compatible.

STEP 4 - RELEASE
* Update README.md "What's new in v0.5.0", `docs/RELEASE_NOTES_0.5.0.md` and `docs/PERFORMANCE_INVESTIGATION_0.5.0.md` so every statement matches what you actually ran. Replace "not yet measured" with
  real numbers only where you measured them, with run counts and conditions. Keep the "remaining gap not demonstrated" wording unless the data proves otherwise.
* Tag `v0.5.0`. Attach the release APK. Publish `docs/RELEASE_NOTES_0.5.0.md` as the release body. Verify the files exist and the SHA-256 of the attached APK.
* Build the final project ZIP, verify it unzips, and present it.

FINAL HONESTY CHECK before you say it is done
Did the NDK build finish? Did I see the compiler output? Did I test on the device? Did the comparison use identical settings and phone state? Is every number in the notes a measured one?
If any answer is no, say so plainly in the report and list exactly what remains.
