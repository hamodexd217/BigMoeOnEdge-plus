#!/usr/bin/env bash
# Build the real OnEdge JNI engine with the optional Hexagon/NPU backend.
#
# This script must run inside the official Snapdragon toolchain image. It builds THIS APP's
# libonedge-engine.so (not bmoe-cli), stages only ARM64 JNI libraries under jniLibs, and stages
# Hexagon DSP skeleton ELFs under assets/hexagon for extraction via ADSP_LIBRARY_PATH at runtime.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${OUT:-$ROOT/build-hexagon-onedge}"
# ggml's parent Hexagon CMake integration needs the Android prebuilt-library selector.
# Without this cache variable, the SDK's hexagon_fun.cmake gets empty string arguments.
PREBUILT_LIB_DIR="${PREBUILT_LIB_DIR:-android_aarch64}"
CPU_ARCH="${CPU_ARCH:-armv8.2-a+dotprod+fp16}"
BUILD_JOBS="${BMOE_BUILD_JOBS:-2}"

# Always use a fresh build tree: a stale CMakeCache can preserve an empty/wrong SDK selector,
# and stale .so files must never be mistaken for a successful Hexagon build.
OUT="$(realpath -m "$OUT")"
if [[ "$(basename "$OUT")" != "build-hexagon-onedge" || "$OUT" == "/" || "$OUT" == "$ROOT" ]]; then
    echo "ERROR: OUT must be a dedicated build-hexagon-onedge directory, got '$OUT'" >&2
    exit 1
fi
rm -rf -- "$OUT"

JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"
DSP_ASSETS="$ROOT/app/src/main/assets/hexagon"
mkdir -p "$JNI" "$DSP_ASSETS"
# Invalidate known outputs before configure so a failed build cannot leave an apparently valid old APK payload.
rm -f "$JNI/libonedge-engine.so" "$JNI/libc++_shared.so" \
    "$JNI/libggml-htp-v73.so" "$JNI/libggml-htp-v75.so" \
    "$JNI/libggml-htp-v79.so" "$JNI/libggml-htp-v81.so" \
    "$DSP_ASSETS/libggml-htp-v73.so" "$DSP_ASSETS/libggml-htp-v75.so" \
    "$DSP_ASSETS/libggml-htp-v79.so" "$DSP_ASSETS/libggml-htp-v81.so"

# Clear staged outputs even if the SDK environment is misconfigured, so stale binaries cannot be
# reused after a failed invocation.
NDK_ROOT="${ANDROID_NDK_ROOT:?ANDROID_NDK_ROOT must be set by the Snapdragon toolchain image}"
SDK_ROOT="${HEXAGON_SDK_ROOT:?HEXAGON_SDK_ROOT must be set by the Snapdragon toolchain image}"
TOOLS_ROOT="${HEXAGON_TOOLS_ROOT:?HEXAGON_TOOLS_ROOT must be set by the Snapdragon toolchain image}"

test -d "$SDK_ROOT" || { echo "ERROR: HEXAGON_SDK_ROOT does not exist: $SDK_ROOT" >&2; exit 1; }
test -f "$SDK_ROOT/build/cmake/hexagon_fun.cmake" || {
    echo "ERROR: expected SDK CMake helper is missing: $SDK_ROOT/build/cmake/hexagon_fun.cmake" >&2; exit 1;
}
test -d "$TOOLS_ROOT" || { echo "ERROR: HEXAGON_TOOLS_ROOT does not exist: $TOOLS_ROOT" >&2; exit 1; }
[[ "$PREBUILT_LIB_DIR" =~ ^[A-Za-z0-9_.-]+$ ]] || {
    echo "ERROR: PREBUILT_LIB_DIR must be a non-empty SDK selector, got '$PREBUILT_LIB_DIR'" >&2; exit 1;
}
printf "Hexagon SDK: %s\nHexagon tools: %s\nPREBUILT_LIB_DIR: %s\n" "$SDK_ROOT" "$TOOLS_ROOT" "$PREBUILT_LIB_DIR"

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
  -DHEXAGON_TOOLS_ROOT="$TOOLS_ROOT" \
  -DPREBUILT_LIB_DIR="$PREBUILT_LIB_DIR" \
  -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF

# Build the real JNI engine and every DSP generation supported by upstream 0.28.
cmake --build "$OUT" --target onedge-engine -j "$BUILD_JOBS"
for v in v73 v75 v79 v81; do
    cmake --build "$OUT" --target "htp-$v" -j "$BUILD_JOBS"
done

# Preserve unrelated ABI libraries. The old known outputs were invalidated before configure.
# DSP skeletons do not belong in jniLibs because their ELF machine is Hexagon, not AArch64.
find_one() {
    local name="$1"
    local hit
    hit="$(find "$OUT" -type f -name "$name" -print -quit)"
    test -n "$hit" || { echo "ERROR: missing $name under $OUT" >&2; exit 1; }
    printf '%s\n' "$hit"
}

cp "$(find_one libonedge-engine.so)" "$JNI/libonedge-engine.so"
for v in v73 v75 v79 v81; do
    cp "$(find_one "libggml-htp-$v.so")" "$DSP_ASSETS/libggml-htp-$v.so"
done
cp "$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$JNI/"

# Fail closed on both ELF machine classes and the runtime-probe marker in the actual app JNI binary.
bash "$ROOT/scripts/verify-npu-payload.sh" "$JNI" "$DSP_ASSETS"
printf '\nStaged Android JNI libraries:\n'
ls -lh "$JNI"
printf '\nStaged Hexagon DSP skeleton assets:\n'
ls -lh "$DSP_ASSETS"
