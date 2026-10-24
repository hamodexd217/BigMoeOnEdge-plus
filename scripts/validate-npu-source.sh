#!/usr/bin/env bash
# Static source-level checks for the optional Android Hexagon path.
# This intentionally does NOT claim a successful NPU build or runtime execution.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
fail() { echo "FAIL: $*" >&2; exit 1; }
need() { grep -Fq -- "$2" "$1" || fail "missing '$2' in ${1#"$ROOT"/}"; }

need "$APP/src/main/cpp/CMakeLists.txt" 'option(BMOE_ENABLE_HEXAGON "Enable the optional Hexagon/NPU prefill path" OFF)'
need "$APP/src/main/cpp/CMakeLists.txt" 'set(GGML_HEXAGON ON CACHE BOOL "" FORCE)'
need "$APP/src/main/cpp/CMakeLists.txt" 'target_compile_definitions(onedge-engine PRIVATE BMOE_HAVE_HEXAGON=1)'
need "$APP/src/main/cpp/third_party/llama.cpp/ggml/src/ggml-hexagon/CMakeLists.txt" 'build_htp_skel(v73)'
need "$APP/src/main/cpp/third_party/llama.cpp/ggml/src/ggml-hexagon/CMakeLists.txt" 'build_htp_skel(v75)'
need "$ROOT/scripts/build-hexagon-onedge-engine.sh" '-DBMOE_ENABLE_HEXAGON=ON'
need "$ROOT/scripts/build-hexagon-onedge-engine.sh" '-DPREBUILT_LIB_DIR="$PREBUILT_LIB_DIR"'
need "$ROOT/scripts/build-hexagon-onedge-engine.sh" 'PREBUILT_LIB_DIR="${PREBUILT_LIB_DIR:-android_aarch64}"'
need "$ROOT/scripts/build-hexagon-onedge-engine.sh" 'cmake --build "$OUT" --target onedge-engine'
need "$ROOT/scripts/build-hexagon-onedge-engine.sh" 'verify-npu-payload.sh'
need "$ROOT/scripts/verify-npu-payload.sh" 'libonedge-engine.so'
need "$ROOT/scripts/verify-npu-payload.sh" 'libggml-htp-v73.so'
need "$ROOT/scripts/verify-npu-payload.sh" 'Hexagon DSP skeletons verified in assets directory'
need "$ROOT/scripts/verify-npu-payload.sh" 'qualcomm[[:space:]]+dsp[0-9]+[[:space:]]+processor'
need "$ROOT/scripts/verify-npu-payload.sh" 'ggml-hex: Hexagon backend (experimental)'
need "$ROOT/.github/workflows/android-npu.yml" ':app:assembleDebug -PbmoePrebuiltNative=true'
need "$ROOT/.github/workflows/android-npu.yml" 'Verify the packaged APK payload'
need "$ROOT/.github/workflows/android-npu.yml" 'Disk before cleanup'
need "$APP/src/main/cpp/native-bridge.cpp" '#ifdef BMOE_HAVE_HEXAGON'
need "$APP/src/main/cpp/native-bridge.cpp" 'cfg.prefill.device = "HTP0";'
need "$APP/src/main/cpp/native-bridge.cpp" 'nativeNpuStatus'
need "$APP/src/main/cpp/native-bridge.cpp" 'npu_runtime_status().available'
need "$APP/src/main/cpp/native-bridge.cpp" 'NPU prefill evidence: device_tokens=%d device_nodes=%lld'
need "$APP/src/main/cpp/core/src/engine/session.cpp" 'refusing CPU fallback'
need "$APP/src/main/java/com/bigmoe/onedge/core/HexagonRuntimeSetup.kt" 'ADSP_LIBRARY_PATH'
need "$APP/src/main/java/com/bigmoe/onedge/core/HexagonRuntimeSetup.kt" 'filesDir, TARGET_DIR'
need "$APP/src/main/java/com/bigmoe/onedge/core/EngineTypes.kt" 'npuPrefill = bits and 4 != 0'
need "$APP/src/main/java/com/bigmoe/onedge/ui/settings/SettingsScreen.kt" 'Turn this switch off to allow CPU-only model loading.'
need "$APP/src/main/java/com/bigmoe/onedge/ui/settings/SettingsScreen.kt" 'Hint(viewModel.npuPrefillStatus)'
need "$ROOT/docs/npu-android-build.md" 'device_nodes > 0'
need "$ROOT/docs/NPU_FIX_VALIDATION.md" 'Not performed: Android/Kotlin compilation'

# Check the workflow launches Gradle from repository root, not app/.
if grep -Eq 'working-directory:[[:space:]]*app' "$ROOT/.github/workflows/android-npu.yml"; then
  fail 'NPU Gradle workflow must run from repository root'
fi

bash -n "$ROOT/scripts/build-hexagon-onedge-engine.sh"
bash -n "$ROOT/scripts/verify-npu-payload.sh"
python - "$ROOT/.github/workflows/android-npu.yml" <<'PY'
import sys
try:
    import yaml
except ImportError:
    print('NOTE: PyYAML unavailable; YAML structure check skipped (shell/source checks passed).')
else:
    with open(sys.argv[1], encoding='utf-8') as f:
        yaml.safe_load(f)
    print('PASS: NPU GitHub Actions YAML parses.')
PY

echo 'PASS: NPU source wiring, runtime capability checks, fail-closed prefill, split DSP asset packaging and APK verification gates are present.'
echo 'BOUNDARY: this source check does not compile native code, build the APK, or prove HTP execution on a device.'
