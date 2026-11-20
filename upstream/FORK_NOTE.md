# upstream/ — BigMoeOnEdge 0.39.0, as released

This folder is the original project's source tree at **0.39.0**, vendored unchanged (apart from the
large screenshots/benchmark data under `docs/`, and its `.github/`, which GitHub only reads at the
repository root). It is what the **desktop builds** in `../.github/workflows/build.yml` compile:
`bmoe-cli`, `bmoe-server` (with its web UI) and `bmoe-desktop`, on Linux, Windows and macOS.

It is **not** what the Android app uses. The app builds `../app/src/main/cpp/core`, the same engine
with BigMoeOnEdge+'s session layer (multimodal, tools, structured output, MTP/NPU fixes) on top,
against the app's own vendored llama.cpp. The two engines share the 0.39 streaming/IO stack.

Keeping the pristine tree here has one purpose: the next upstream release can be diffed against it
(`diff -ru upstream/ <new release>/`) so a merge is a three-way one instead of a guess.

## Submodules are not in the zip

`third_party/llama.cpp` and `third_party/webview` are git submodules upstream and are empty here.
CI fetches them from the tag (see the `desktop` job). To build by hand:

    git clone --depth 1 --branch v0.39.0 --recurse-submodules --shallow-submodules \
        https://github.com/Helldez/BigMoeOnEdge.git /tmp/up
    cp -r /tmp/up/third_party/llama.cpp /tmp/up/third_party/webview upstream/third_party/
    cmake -S upstream -B build -DBMOE_BUILD_TESTS=OFF && cmake --build build -j
