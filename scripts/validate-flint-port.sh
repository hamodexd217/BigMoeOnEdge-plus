#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LLAMA="$ROOT/app/src/main/cpp/third_party/llama.cpp"
CORE="$ROOT/app/src/main/cpp/core"
check() { grep -Fq -- "$2" "$1" || { echo "FAIL: expected '$2' in $1" >&2; exit 1; }; echo "PASS: $3"; }
test -s "$LLAMA/src/models/flint.cpp" && echo 'PASS: Flint model graph source exists'
check "$LLAMA/src/llama-arch.h" 'LLM_ARCH_FLINT' 'architecture enum'
check "$LLAMA/src/llama-arch.cpp" '"flint"' 'architecture name registration'
check "$LLAMA/src/llama-model.cpp" 'new llama_model_flint(params)' 'model factory registration'
check "$LLAMA/src/llama-vocab.h" 'LLAMA_VOCAB_PRE_TYPE_FLINT' 'Flint tokenizer enum'
check "$LLAMA/src/llama-vocab.cpp" 'tokenizer_pre == "flint"' 'Flint tokenizer metadata'
check "$LLAMA/src/llama-vocab.cpp" 'LLAMA_VOCAB_PRE_TYPE_FLINT && (t.first == "<|user|>"' 'Flint end-of-generation tokens'
check "$LLAMA/common/jinja/lexer.cpp" 'incomplete hexadecimal escape' 'Jinja hex escape parser'
# 0.7.0: the per-architecture recipe table is gone. The expert tensors are discovered from the file's
# shapes (bmoe/expert_layout.h), so Flint needs no registry entry, only the standard split expert tensors.
check "$CORE/src/moe/expert_layout.cpp" 'discover_expert_layout' 'expert layout discovery (replaces the arch recipe)'
check "$LLAMA/src/models/flint.cpp" 'ffn_gate_exps' 'Flint uses the standard split expert tensors'
echo 'PASS: all Flint source-level registration checks completed'
