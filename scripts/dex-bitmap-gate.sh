#!/usr/bin/env bash
#
# Guard Knit's release dex against an unbounded BitmapFactory decode.
#
# WHY THIS EXISTS. Play Console's app-quality scan flags "BitmapFactory without downsampling" — any
# `BitmapFactory.decode*` call made without a `BitmapFactory.Options` carrying an `inSampleSize`, which
# decodes an image at whatever resolution the image itself declares. For Knit that is not a memory-hygiene
# nit but an input-validation one: avatars and attachments arrive from peers over the mesh, and while a
# blob's *byte* size is bounded its *pixel* count is not, so a small, highly compressible image decodes to
# hundreds of MB. ADR 051 moved every decode behind `data/ImageDecode.kt`; detekt's ForbiddenImport keeps
# our sources there. This is the other half — the dex sees library code too, and libraries are exactly
# where such a call arrives silently on a dependency bump.
#
# It also exists because the Play report itself is unreadable: R8 full mode merges and moves methods across
# classes, so Play's retrace named two `com.google.crypto.tink.*.<clinit>` methods for calls that are
# physically in `MessageNotifier` and `androidx.core`. Names in that report cannot be trusted; this script
# is how you get the truth, and it prints the same evidence the investigation needed.
#
# WHAT IT MEASURES. Every `invoke-*` of `Landroid/graphics/BitmapFactory;.decode*` in the APK's dex whose
# descriptor does NOT take a `BitmapFactory$Options`, reported with the (obfuscated) class + method that
# contains it — feed those to `retrace` against app/build/outputs/mapping/release/mapping.txt, but treat
# the answer as a hint, not a fact, and read the surrounding `dexdump -d` instead.
#
# THE BUDGET is 3. Two are androidx.core's own: `IconCompat.toIcon(Context)` decodes a URI-backed icon
# on its TYPE_URI / TYPE_URI_ADAPTIVE_BITMAP branch, and `ShortcutManagerCompat.pushDynamicShortcut` inlines
# the same method. Knit only ever builds icons with `IconCompat.createWithAdaptiveBitmap(bitmap)`, so that
# branch is dead for us — but it is reachable on paper, so R8 keeps it and the scanner counts it.
# The third is Media3's (ADR 051 amendment, ADR 2026-10.hmfe): `TtmlSubtitle.getCues` decodes a base64 image
# cue out of a TTML subtitle track. Only the video encoder's extractor reaches it, on the sender, over a file
# the user picked on their own phone; a received video plays through the platform MediaPlayer, never Media3.
# A fourth hit is a real regression: ours, or a new dependency's.
#
# Usage: scripts/dex-bitmap-gate.sh <apk> [max-unbounded]
set -euo pipefail

APK="${1:?usage: dex-bitmap-gate.sh <apk> [max-unbounded]}"
MAX_UNBOUNDED="${2:-3}"           # ADR 051 landed at 2, both in androidx.core; Media3 TTML made it 3 (see header).

# Resolved the same way r8-dex-gate.sh resolves apkanalyzer: PATH first, else the SDK this build installs.
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
# `|| true` on both: under `set -e` a bare failing lookup would exit the script before the message below.
DEXDUMP="${DEXDUMP:-$(command -v dexdump || true)}"
[ -n "$DEXDUMP" ] || DEXDUMP="$(find "$SDK/build-tools" -maxdepth 2 -name dexdump 2>/dev/null | sort -V | tail -1 || true)"
[ -x "${DEXDUMP:-}" ] || { echo "dex-bitmap-gate: dexdump not found (set DEXDUMP)" >&2; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The JDK's jar reads the APK, not unzip: CI's eclipse-temurin image ships no unzip, and installing one from
# archive.ubuntu.com failed every Android job whenever that archive was unreachable (pipelines of 11-03, 11-19).
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
APK_ABS="$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"
mapfile -t DEX_ENTRIES < <("$JAR" tf "$APK_ABS" | grep -E '^classes[^/]*\.dex$' || true)
[ "${#DEX_ENTRIES[@]}" -eq 0 ] || (cd "$WORK" && "$JAR" xf "$APK_ABS" "${DEX_ENTRIES[@]}")
shopt -s nullglob
DEXES=("$WORK"/classes*.dex)
[ "${#DEXES[@]}" -gt 0 ] || { echo "dex-bitmap-gate: no classes*.dex in $APK" >&2; exit 2; }

# dexdump -d prints, per class, a `Class descriptor` line then one block per method with `name` / `type`
# lines followed by the disassembly — so the most recent of each names the method a given call sits in.
"$DEXDUMP" -d "${DEXES[@]}" \
  | awk '
      /^  Class descriptor/ { cls = $0; sub(/.*: */, "", cls); gsub(/'\''/, "", cls) }
      /^      name  *:/     { m = $0; sub(/.*: */, "", m); gsub(/'\''/, "", m) }
      /^      type  *:/     { t = $0; sub(/.*: */, "", t); gsub(/'\''/, "", t) }
      /Landroid\/graphics\/BitmapFactory;\.decode/ {
        if ($0 !~ /BitmapFactory\$Options/) {
          call = $0; sub(/.*Landroid\/graphics\/BitmapFactory;\./, "", call); sub(/ .*/, "", call)
          n++
          printf "    %s.%s %s\n        calls BitmapFactory.%s\n", cls, m, t, call
        }
      }
      END {
        printf "\nunbounded BitmapFactory.decode* call sites: %d (budget %d)\n", n, MAX
        if (n > MAX) {
          print  "\nFAIL: a BitmapFactory.decode* call is decoding at the image'\''s own resolution."
          print  "      If it is ours, route it through data/ImageDecode.kt (decodeOrientedBounded /"
          print  "      decodeBoundedFromBytes) — detekt'\''s ForbiddenImport should have caught it first."
          print  "      If it is a library'\''s, identify it before shipping: peer-supplied images reach"
          print  "      Coil, the notifier and the moderator, and an unbounded decode of one is an OOM."
          print  "      Retrace the class above with app/build/outputs/mapping/release/mapping.txt, but"
          print  "      verify against the dexdump — R8 method merging makes that mapping lossy (ADR 051)."
          exit 1
        }
        print "\nOK"
      }' MAX="$MAX_UNBOUNDED"
