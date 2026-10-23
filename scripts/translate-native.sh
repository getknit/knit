#!/usr/bin/env bash
# Fetch the native sources the translation spike builds (ADR 2026-10.t2h6) into translate-native/third_party/.
#
# Nothing here is committed and nothing is a git submodule, so F-Droid's recipe and an ordinary clone never see it:
# the `:translate-native` module is included only under `-Ptranslate=true`, and that build needs this run first.
# Each repository is fetched at one pinned commit and checked against it, so a moved branch changes nothing.
#
#   slimt         Marian inference for Mozilla's Firefox Translations models (GPL-2.0-or-later). David Ventura's fork:
#                 upstream jerinphilip/slimt plus ~100 commits (NEON paths, the larger shortlist, beam re-decode),
#                 the one that loads the base-memory en-es / es-en models (Phase 0 of the spike, 4 decoder layers).
#   ruy, cpuinfo  slimt's matrix multiply on ARM and x86 (Apache-2.0, BSD-2-Clause).
#   sentencepiece browsermt's fork, the tokenizer the models' vocab.*.spm needs (Apache-2.0).
#   cld2          Compact Language Detector 2 (Apache-2.0), what Firefox's own translations use to name a language.
#
# Usage: scripts/translate-native.sh        (idempotent; re-fetches only a checkout not at its pin)
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST="$ROOT/translate-native/third_party"

fetch() {
  local name=$1 url=$2 commit=$3
  local dir="$DEST/$name"
  if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" = "$commit" ]; then
    echo "ok      $name"
    return
  fi
  rm -rf "$dir"
  mkdir -p "$dir"
  git -C "$dir" init -q
  git -C "$dir" fetch -q --depth 1 "$url" "$commit"
  git -C "$dir" checkout -q FETCH_HEAD
  local got
  got=$(git -C "$dir" rev-parse HEAD)
  if [ "$got" != "$commit" ]; then
    echo "error: $name is at $got, pinned $commit" >&2
    exit 1
  fi
  echo "fetched $name @ ${commit:0:12}"
}

# Parents before the children that live inside their trees: re-fetching a parent removes its children.
fetch slimt https://github.com/DavidVentura/slimt.git fdc0f98706b193bf17cb419f2fe08bf1d9913607
fetch slimt/3rd-party/ruy https://github.com/google/ruy.git c04e5e52ae6b144f74ac032652e3c538bda15c9b
fetch slimt/3rd-party/ruy/third_party/cpuinfo https://github.com/pytorch/cpuinfo.git 082deffc80ce517f81dc2f3aebe6ba671fcd09c9
fetch slimt/3rd-party/sentencepiece https://github.com/browsermt/sentencepiece.git 8cbdf13794284c30877936f91c6f31e2c1d5aef7
fetch cld2 https://github.com/CLD2Owners/cld2.git b56fa78a2fe44ac2851bae5bf4f4693a0644da7b
