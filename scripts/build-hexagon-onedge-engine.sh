#!/usr/bin/env bash
# Build the real OnEdge JNI engine with the optional Hexagon/NPU backend.
#
# This script must run inside the same Snapdragon toolchain image used by upstream
# BigMoeOnEdge's scripts/build-hexagon-android.sh. It intentionally builds the APP'S
# libonedge-engine.so (not bmoe-cli), then stages the DSP skels and libc++ into
# app/src/main/jniLibs/arm64-v8a so Gradle can package an actually NPU-capable APK.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${OUT:-/workspace/build-hexagon-onedge}"
NDK_ROOT="${ANDROID_NDK_ROOT:?ANDROID_NDK_ROOT must be set by the Snapdragon toolchain image}"
SDK_ROOT="${HEXAGON_SDK_ROOT:?HEXAGON_SDK_ROOT must be set by the Snapdragon toolchain image}"
TOOLS_ROOT="${HEXAGON_TOOLS_ROOT:-}"
CPU_ARCH="${CPU_ARCH:-armv8.2-a+dotprod+fp16}"

cmake -S "$ROOT/app/src/main/cpp" -B "$OUT" \
  -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="-D_GNU_SOURCE" \
  -DCMAKE_CXX_FLAGS="-D_GNU_SOURCE" \
  -DBMOE_ENABLE_HEXAGON=ON \
  -DBMOE_ENABLE_MULTIMODAL=ON \
  -DBMOE_BUILD_TESTS=OFF \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_OPENCL=OFF -DGGML_LLAMAFILE=OFF \
  -DGGML_CPU_ARM_ARCH="$CPU_ARCH" \
  -DHEXAGON_SDK_ROOT="$SDK_ROOT" \
  ${TOOLS_ROOT:+-DHEXAGON_TOOLS_ROOT="$TOOLS_ROOT"} \
  -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF

# Build the real JNI engine and every DSP generation supported by upstream 0.28.
cmake --build "$OUT" --target onedge-engine -j "$(nproc)"
for v in v73 v75 v79 v81; do
    cmake --build "$OUT" --target "htp-$v" -j "$(nproc)"
done

JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"
rm -rf "$JNI"
mkdir -p "$JNI"

find_one() {
    local name="$1"
    local hit
    hit="$(find "$OUT" -type f -name "$name" -print -quit)"
    test -n "$hit" || { echo "ERROR: missing $name under $OUT" >&2; exit 1; }
    printf '%s\n' "$hit"
}

cp "$(find_one libonedge-engine.so)" "$JNI/libonedge-engine.so"
for v in v73 v75 v79 v81; do
    cp "$(find_one "libggml-htp-$v.so")" "$JNI/libggml-htp-$v.so"
done
cp "$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$JNI/"

# Fail closed: an NPU APK without all generations or the real JNI engine is not useful.
for f in libonedge-engine.so libc++_shared.so libggml-htp-v73.so libggml-htp-v75.so libggml-htp-v79.so libggml-htp-v81.so; do
    test -s "$JNI/$f" || { echo "ERROR: staged library is empty: $f" >&2; exit 1; }
done

printf '\nStaged Hexagon JNI libraries:\n'
ls -lh "$JNI"
