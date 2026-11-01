#!/usr/bin/env bash
#
# Guard Knit's release dex against a cast R8 adds to a platform class newer than minSdk.
#
# WHY THIS EXISTS. When R8 merges two classes horizontally it can widen a field to java.lang.Object, then read it back
# with a `check-cast` to the field's old type. If that type is a platform class newer than minSdk, the cast breaks older
# phones: ART resolves a check-cast's class before it looks at the value, so the cast throws NoClassDefFoundError even
# when the field is null and even when the library guards every *use* of the class behind an SDK_INT check. R8 9.4.24
# (AGP 9.4.1) did this in 2.10.0 and 2.10.1. It merged Media3's DefaultAssetLoaderFactory into Tink's HpkeContext, cast
# its `logSessionId` to android.media.metrics.LogSessionId (API 31) on the read, and every video send crashed Knit on
# Android 10 and 11 (Play, 2026-10-31). Its ExoPlayerAssetLoader$RenderersFactoryImpl had two more on the same path.
# Lint's NewApi checks our sources, not R8's output, and the emulator smoke test never sends a video on API 29–30.
# R8 9.5.28 (AGP 9.5.0) emits none of these casts. This gate fails a build if a later R8 brings them back.
#
# WHAT IT MEASURES. Every `check-cast` in the APK's dex to a class whose api-versions.xml `since` is above the APK's
# minSdk, when the register being cast was just read from a field typed java.lang.Object, either directly or through
# `move-object` copies with nothing in between. That is how R8 emits the read of a field it widened. A cast after a
# null check, a branch or any other instruction is not counted, which keeps a library's own guarded casts out. 2.10.1's dex has nine such
# casts; three run on API 29–30. The other six are behind an SDK_INT check or only run on the API that adds the class.
#
# WHEN IT FAILS. Each hit prints the dex method and pc. For R8's pc-mapped code, that pc is the line number a crash
# trace would show. With the mapping given, the hit is also retraced to its candidate source frames. Then:
#   1. Preferred: name the class that owned the field in app/src/main/keepRules/knit-r8.keep as
#      `-keep,allowobfuscation,allowshrinking class <name>`. R8 does not merge a kept class, so the field keeps its
#      type and the cast goes away. Rebuild and re-run this gate to confirm.
#   2. Only if `dexdump -d` shows the cast can only run where the class exists (every path to it passes an SDK_INT
#      check at or above the class's level, or it is in a callback the platform only makes on that API): raise the
#      budget, and record the site and that evidence in this header, as dex-bitmap-gate.sh does.
#
# THE BUDGET is 0.
#
# Usage: scripts/dex-api-cast-gate.sh <apk> [mapping.txt] [max-casts]
#   MIN_SDK=<n> overrides the minSdk read from the APK; API_VERSIONS=<file> overrides the newest installed platform's
#   data/api-versions.xml; DEXDUMP / AAPT2 override the tools found in the SDK's build-tools.
set -euo pipefail

APK="${1:?usage: dex-api-cast-gate.sh <apk> [mapping.txt] [max-casts]}"
MAPPING="${2:-}"
MAX_CASTS="${3:-0}"
export LC_ALL=C                   # dexdump prints raw bytes from string constants; awk must not decode them.

[ -f "$APK" ] || { echo "dex-api-cast-gate: no APK at $APK" >&2; exit 2; }
[ -z "$MAPPING" ] || [ -f "$MAPPING" ] || { echo "dex-api-cast-gate: no mapping at $MAPPING" >&2; exit 2; }

# Resolved the same way dex-bitmap-gate.sh resolves dexdump: PATH first, else the SDK this build installs.
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
# `|| true` throughout: under `set -e` a bare failing lookup would exit the script before the message below.
tool() {
  local t
  t="$(command -v "$1" || true)"
  [ -n "$t" ] || t="$(find "$SDK/build-tools" -maxdepth 2 -name "$1" 2>/dev/null | sort -V | tail -1 || true)"
  printf '%s' "$t"
}
DEXDUMP="${DEXDUMP:-$(tool dexdump)}"
[ -x "${DEXDUMP:-}" ] || { echo "dex-api-cast-gate: dexdump not found (set DEXDUMP)" >&2; exit 2; }

# The newest installed platform's database: it lists every class any older platform added, with the level that added it.
API_VERSIONS="${API_VERSIONS:-$(find "$SDK/platforms" -path '*/data/api-versions.xml' 2>/dev/null | sort -V | tail -1 || true)}"
[ -f "${API_VERSIONS:-}" ] || {
  echo "dex-api-cast-gate: api-versions.xml not found (set API_VERSIONS, or install a platform)" >&2; exit 2; }

if [ -z "${MIN_SDK:-}" ]; then
  AAPT2="${AAPT2:-$(tool aapt2)}"
  [ -x "${AAPT2:-}" ] || { echo "dex-api-cast-gate: aapt2 not found (set AAPT2 or MIN_SDK)" >&2; exit 2; }
  MIN_SDK="$("$AAPT2" dump badging "$APK" | sed -nE "s/^(minSdkVersion|sdkVersion):'([0-9]+)'.*/\2/p" | head -1)"
fi
case "$MIN_SDK" in '' | *[!0-9]*) echo "dex-api-cast-gate: no minSdk for $APK (set MIN_SDK)" >&2; exit 2 ;; esac

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# `<class name="android/media/metrics/LogSessionId" since="31">` → `Landroid/media/metrics/LogSessionId; 31`, for each
# class added after minSdk. A class with no `since` is as old as the API.
awk -v min="$MIN_SDK" '
  /<class / {
    if (!match($0, /name="[^"]*"/)) next
    name = substr($0, RSTART + 6, RLENGTH - 7)
    if (!match($0, /since="[0-9.]*"/)) next
    since = substr($0, RSTART + 7, RLENGTH - 8)
    if (since + 0 > min + 0) print "L" name ";", since
  }' "$API_VERSIONS" > "$WORK/newer.txt"
[ -s "$WORK/newer.txt" ] || { echo "dex-api-cast-gate: no classes above API $MIN_SDK in $API_VERSIONS" >&2; exit 2; }

unzip -o -q "$APK" 'classes*.dex' -d "$WORK"
shopt -s nullglob
DEXES=("$WORK"/classes*.dex)
[ "${#DEXES[@]}" -gt 0 ] || { echo "dex-api-cast-gate: no classes*.dex in $APK" >&2; exit 2; }

# dexdump -d prints each method as `|[offset] class.method:descriptor`, then one `|pppp: opcode operands` line per
# instruction, pppp being the hex pc. `pend` holds the registers that carry an Object-typed field's value from the
# instruction just before; a move-object copies the mark, a check-cast of a marked register to a newer class is a hit,
# and any other instruction clears every mark. Hits go to hits.txt as `class<TAB>method<TAB>pc<TAB>description`.
"$DEXDUMP" -d "${DEXES[@]}" 2>/dev/null \
  | awk -v hits="$WORK/hits.txt" '
      function hex(s,  i, v) { v = 0; for (i = 1; i <= length(s); i++) v = v * 16 + index("0123456789abcdef", substr(s, i, 1)) - 1; return v }
      NR == FNR { since[$1] = $2; next }
      /\|\[[0-9a-f]+\] / {
        m = $0; sub(/.*\|\[[0-9a-f]+\] /, "", m); split("", pend); next
      }
      match($0, /\|[0-9a-f][0-9a-f][0-9a-f][0-9a-f]: /) {
        pc = hex(substr($0, RSTART + 1, 4))
        ins = substr($0, RSTART + RLENGTH); sub(/ \/\/.*/, "", ins)
        op = ins; sub(/ .*/, "", op)
        args = ins; sub(/^[^ ]* /, "", args)
        n = split(args, a, /, /)
        if (op == "iget-object" || op == "sget-object") {
          split("", pend)
          if (a[n] ~ /:Ljava\/lang\/Object;$/) { pend[a[1]] = 1; field[a[1]] = op " " a[n] }
          next
        }
        if (op ~ /^move-object/) {
          if (a[2] in pend) { pend[a[1]] = 1; field[a[1]] = field[a[2]] } else delete pend[a[1]]
          next
        }
        if (op == "check-cast" && (a[1] in pend) && (a[2] in since)) {
          c = m; sub(/:.*/, "", c); meth = c; sub(/.*\./, "", meth); sub(/\.[^.]*$/, "", c)
          t = a[2]; sub(/^L/, "", t); sub(/;$/, "", t); gsub(/\//, ".", t)
          printf "%s\t%s\t%d\t%s @ pc %d: check-cast %s (API %s) after %s\n", \
            c, meth, pc, m, pc, t, since[a[2]], field[a[1]] > hits
        }
        split("", pend)
      }' "$WORK/newer.txt" -
touch "$WORK/hits.txt"

# An optional retrace: every mapping line of the hit's class and method whose pc range covers the hit's pc. R8 reuses a
# short name for overloads and merges classes, so these are candidates; read the dexdump to be sure (ADR 051).
if [ -s "$WORK/hits.txt" ] && [ -n "$MAPPING" ]; then
  awk -F'\t' '
    NR == FNR { want[$1] = 1; key[$1 SUBSEP $2] = key[$1 SUBSEP $2] " " $3; next }
    /^[^ #].* -> [^ ]*:$/ { cur = $NF; sub(/:$/, "", cur); if (!(cur in want)) cur = ""; next }
    cur != "" && /^    [0-9]+:[0-9]+:/ {
      name = $0; sub(/.* -> /, "", name)
      if (!((cur SUBSEP name) in key)) next
      lo = $0; sub(/^ +/, "", lo); hi = lo; sub(/:.*/, "", lo); sub(/^[0-9]+:/, "", hi); sub(/:.*/, "", hi)
      frame = $0; sub(/^ +[0-9]+:[0-9]+:/, "", frame); sub(/ -> [^ ]*$/, "", frame)
      np = split(key[cur SUBSEP name], pcs, " ")
      for (i = 1; i <= np; i++) if (pcs[i] + 0 >= lo + 0 && pcs[i] + 0 <= hi + 0)
        print cur "." name "@" pcs[i] "\t" frame
    }' FS='\t' "$WORK/hits.txt" FS=' ' "$MAPPING" > "$WORK/frames.txt"
fi

count=0
while IFS=$'\t' read -r c meth pc what; do
  count=$((count + 1))
  printf '    %s\n' "$what"
  if [ -s "$WORK/frames.txt" ]; then
    awk -F'\t' -v k="$c.$meth@$pc" '$1 == k { print "        " $2 }' "$WORK/frames.txt"
  fi
done < "$WORK/hits.txt"

printf '\nminSdk %s, %s\n' "$MIN_SDK" "$API_VERSIONS"
printf 'casts of a newer platform class read from an Object field: %d (budget %d)\n' "$count" "$MAX_CASTS"
if [ "$count" -gt "$MAX_CASTS" ]; then
  echo
  echo "FAIL: R8 widened a field to Object and casts it back to a class older phones do not have. ART resolves a"
  echo "      check-cast's class even for null, so each one throws NoClassDefFoundError below that API level."
  echo "      Name the class that owned the field in app/src/main/keepRules/knit-r8.keep as"
  echo "      -keep,allowobfuscation,allowshrinking class <name>, so R8 cannot merge it, then rebuild. Raise the"
  echo "      budget only for a cast the dexdump shows can run solely where the class exists (this script's header)."
  exit 1
fi
echo "OK"
