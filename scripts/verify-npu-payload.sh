#!/usr/bin/env bash
# Verify the split runtime payload: ARM64 Android JNI libraries and Hexagon DSP-side skeleton ELFs.
# Usage: verify-npu-payload.sh <jniLibs/arm64-v8a> <assets/hexagon>
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="${1:-$ROOT/app/src/main/jniLibs/arm64-v8a}"
DSP_DIR="${2:-$ROOT/app/src/main/assets/hexagon}"
engine_required=(libonedge-engine.so libc++_shared.so)
dsp_required=(libggml-htp-v73.so libggml-htp-v75.so libggml-htp-v79.so libggml-htp-v81.so)

for dir in "$JNI_DIR" "$DSP_DIR"; do
  [[ -d "$dir" ]] || { echo "ERROR: required payload directory not found: $dir" >&2; exit 1; }
done
command -v readelf >/dev/null 2>&1 || { echo "ERROR: readelf is required for ELF architecture verification" >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { echo "ERROR: sha256sum is required" >&2; exit 1; }

check_elf() {
  local file="$1" name="$2" magic
  [[ -s "$file" ]] || { echo "ERROR: missing or empty payload: $file" >&2; exit 1; }
  magic="$(od -An -tx1 -N4 "$file" | tr -d ' \n')"
  [[ "$magic" == "7f454c46" ]] || { echo "ERROR: $name is not an ELF file (magic=$magic)" >&2; exit 1; }
}
machine_of() {
  readelf -h "$1" | awk -F: '/^[[:space:]]*Machine:/ {sub(/^[[:space:]]*/, "", $2); print $2; exit}'
}

for name in "${engine_required[@]}"; do check_elf "$JNI_DIR/$name" "$name"; done
for name in "${dsp_required[@]}"; do check_elf "$DSP_DIR/$name" "$name"; done

engine_machine="$(machine_of "$JNI_DIR/libonedge-engine.so")"
[[ "$engine_machine" == *AArch64* ]] || {
  echo "ERROR: libonedge-engine.so must target Android arm64-v8a; ELF machine=${engine_machine:-unknown}" >&2; exit 1;
}
cpp_machine="$(machine_of "$JNI_DIR/libc++_shared.so")"
[[ "$cpp_machine" == *AArch64* ]] || {
  echo "ERROR: libc++_shared.so must target Android arm64-v8a; ELF machine=${cpp_machine:-unknown}" >&2; exit 1;
}

for name in "${dsp_required[@]}"; do
  machine="$(machine_of "$DSP_DIR/$name")"
  # GNU readelf/binutils names EM_HEXAGON as "QUALCOMM DSP6 Processor" rather
  # than containing the word "Hexagon". Accept both spellings, but keep the
  # ARM64 JNI validation above strict so DSP skeletons cannot pass as JNI libs.
  if ! grep -Eqi 'hexagon|qualcomm[[:space:]]+dsp[0-9]+[[:space:]]+processor' <<<"$machine"; then
    echo "ERROR: $name is not a Hexagon DSP ELF (machine=${machine:-unknown}); do not package DSP skels as Android arm64 JNI libraries" >&2
    exit 1
  fi
done

# An HTP engine binary must contain the runtime-probe marker compiled only into the Hexagon JNI build.
# Search the binary directly (grep -a). Do NOT use `strings | grep -q` here: grep -q exits on the first
# match, strings dies with SIGPIPE (141), and `set -o pipefail` turns a successful match into a failure.
if ! grep -aFq 'Available: HTP0 opened successfully' "$JNI_DIR/libonedge-engine.so"; then
  echo "ERROR: engine lacks the Hexagon runtime-probe marker; this may be a CPU-only JNI binary" >&2
  exit 1
fi

if ! grep -aFq 'ggml-hex: Hexagon backend (experimental)' "$JNI_DIR/libonedge-engine.so"; then
  echo "ERROR: engine lacks a unique vendored ggml-hexagon backend implementation marker; refusing a false-positive prebuilt" >&2
  exit 1
fi

if compgen -G "$JNI_DIR/libggml-htp-*.so" >/dev/null; then
  echo "ERROR: DSP skeletons are incorrectly staged in jniLibs; move them to assets/hexagon" >&2
  exit 1
fi

printf 'PASS: Android JNI ABI=%s; C++ runtime ABI=%s; Hexagon DSP skeletons verified in assets directory.\n' "$engine_machine" "$cpp_machine"
printf '\nSHA-256: Android JNI libraries\n'
sha256sum "${engine_required[@]/#/$JNI_DIR/}"
printf '\nSHA-256: Hexagon DSP skeletons\n'
sha256sum "${dsp_required[@]/#/$DSP_DIR/}"
