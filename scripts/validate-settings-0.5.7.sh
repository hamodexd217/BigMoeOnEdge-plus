#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
fail() { echo "FAIL: $*" >&2; exit 1; }
for text in   'I_N_THREADS_BATCH' 'I_BATCH_WIDTH' 'I_IO_TWO_WAVE' 'I_ROW_STREAM_MB'   'I_NPU_MIN_TOKENS' 'I_NGRAM_MIN_MATCH' 'I_DROP_RENORM' 'I_LOAD_ALL_EXPERTS'   'I_DECIDE_PREFIX_CACHE' 'I_FORCE_CACHE'; do
  grep -q "$text" "$APP/src/main/cpp/native-bridge.cpp" || fail "missing native index $text"
done
grep -q 'const val INT_COUNT = 44' "$APP/src/main/java/com/bigmoe/onedge/core/EngineTypes.kt" || fail 'Kotlin/native int array count mismatch'
grep -q 'val overlap: Boolean = true' "$APP/src/main/java/com/bigmoe/onedge/data/local/datastore/EngineSettingsDataStore.kt" || fail 'overlap is not enabled by default'
grep -q 'llama_set_n_threads(ctx, cfg.n_threads, cfg.n_threads_batch)' "$APP/src/main/cpp/core/src/engine/session.cpp" || fail 'prompt thread count not applied to target context'
grep -q 'llama_set_n_threads(ctx_dft, cfg.n_threads, cfg.n_threads_batch)' "$APP/src/main/cpp/core/src/engine/session.cpp" || fail 'prompt thread count not applied to MTP context'
grep -q 'PerformanceGroup("Accelerator' "$APP/src/main/java/com/bigmoe/onedge/ui/settings/SettingsScreen.kt" || fail 'NPU group missing'
grep -q 'PerformanceGroup("MoE streaming & I/O' "$APP/src/main/java/com/bigmoe/onedge/ui/settings/SettingsScreen.kt" || fail 'streaming group missing'
grep -q 'PerformanceGroup("Speed / quality' "$APP/src/main/java/com/bigmoe/onedge/ui/settings/SettingsScreen.kt" || fail 'speed/quality group missing'
grep -q 'PerformanceGroup("CPU compute & memory' "$APP/src/main/java/com/bigmoe/onedge/ui/settings/SettingsScreen.kt" || fail 'compute/memory group missing'
VERSION="$(sed -n 's/.*versionName = \"\([^\"]*\)\".*/\1/p' "$APP/build.gradle.kts" | head -n1)"
[[ -n "$VERSION" ]] || fail 'could not read versionName'
grep -q "## $VERSION" "$ROOT/CHANGELOG.md" || fail "changelog missing $VERSION"
echo "PASS: settings wiring, JNI array size, grouped UI, default overlap, threads, docs and version alignment ($VERSION)."
BMOE_ROOT="$ROOT" python - <<'PY'
import re
from pathlib import Path
import os
root = Path(os.environ['BMOE_ROOT'])
k = (root / 'app/src/main/java/com/bigmoe/onedge/core/EngineTypes.kt').read_text()
n = (root / 'app/src/main/cpp/native-bridge.cpp').read_text()
arr = re.search(r'fun toIntArray\(\): IntArray = intArrayOf\((.*?)\n    \)', k, re.S).group(1)
values, token, depth = [], '', 0
for char in arr:
    if char == '(': depth += 1
    elif char == ')': depth -= 1
    if char == ',' and depth == 0:
        values.append(token.strip()); token = ''
    else:
        token += char
if token.strip(): values.append(token.strip())
enum_body = re.search(r'enum IntIdx\s*\{(.*?)\n\};', n, re.S).group(1)
indices = [m.group(1) for m in re.finditer(r'\b(I_[A-Z0-9_]+)\b', enum_body) if m.group(1) != 'I_COUNT']
assert len(values) == len(indices) == 44, (len(values), len(indices))
print('PASS: Kotlin array length exactly matches 44 native config indices.')
PY
