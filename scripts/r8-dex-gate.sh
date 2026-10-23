#!/usr/bin/env bash
#
# Guard Knit's R8 output against a keep-rule regression.
#
# WHY THIS EXISTS. Play Console reports three numbers for every uploaded bundle — optimization rate,
# obfuscation rate and shrinking rate — and they move together, because a `-keep` blocks all three over
# whatever it matches. Knit sat at 48/48/48 until ADR 050 dropped three blanket `-keep … { *; }` rules
# (Tink, ARSCLib, apksig) that were 93% of everything R8 was forbidden to touch. The realistic way that
# regresses is not someone re-adding those lines on purpose: it is a dependency upgrade shipping a broad
# *consumer* rule, which is invisible in a diff and silent until the next Play upload months later.
#
# WHAT IT MEASURES. R8 full mode repackages every renamed class into the unnamed root package, so a class
# that still reports a real package name is, by definition, one a keep rule pinned. The share of dex bytes
# sitting in named top-level packages is therefore a direct read of how much of the app R8 was not allowed
# to touch — the same quantity Play scores, computed from the APK with no proto parsing and no extra
# tooling: `apkanalyzer` is already in the cmdline-tools this build installs.
#
# It is deliberately a coarse floor, not a target. Small drifts are normal (a library gains a class, a
# keep matches one more thing); the failure it is built to catch is a step change.
#
# Usage: scripts/r8-dex-gate.sh <apk> [max-named-pct] [max-dex-bytes]
set -euo pipefail

APK="${1:?usage: r8-dex-gate.sh <apk> [max-named-pct] [max-dex-bytes]}"
MAX_NAMED_PCT="${2:-15}"          # ADR 050 landed at 4.6%; 15 is a step-change alarm, not a target.
MAX_DEX_BYTES="${3:-10200000}"    # ADR 050 landed at 5.34 MB of defined code (was 8.27 MB); raised from 7 MB at 7.02 MB,
                                  # to 9 MB at 8.05 MB when Media3 Transformer came in, then to 10.2 MB at 9.11 MB when
                                  # calls and Knit Anywhere shipped in release (ADR 050 amendments).

APKANALYZER="${APKANALYZER:-$(command -v apkanalyzer || echo "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/cmdline-tools/latest/bin/apkanalyzer")}"
[ -x "$APKANALYZER" ] || { echo "r8-dex-gate: apkanalyzer not found (set APKANALYZER)" >&2; exit 2; }

# `P <defined-methods> <referenced-methods> <size-bytes> <package>`, one row per package at every depth.
# Depth-1 rows (no dot) partition the named half of the dex; <TOTAL> covers the renamed root package too.
"$APKANALYZER" dex packages --defined-only "$APK" \
  | awk -F'\t' '$1 ~ /^P / && NF == 4 {
        if ($4 == "<TOTAL>")      total = $3
        else if ($4 !~ /\./)    { named += $3; pkg[$4] = $3 }
      }
      END {
        if (total == 0) { print "r8-dex-gate: apkanalyzer reported no dex code" > "/dev/stderr"; exit 2 }
        pct = 100 * named / total
        printf "defined dex code            %10d bytes (%.2f MB)\n", total, total / 1048576
        printf "in named (kept) packages    %10d bytes (%.1f%%)\n", named, pct
        for (p in pkg) printf "    %10d  %s\n", pkg[p], p | "sort -rn"
        close("sort -rn")
        fail = 0
        if (pct > MAXPCT) {
          printf "\nFAIL: %.1f%% of dex bytes keep their original package names (limit %s%%).\n", pct, MAXPCT
          print  "      Something is blocking R8 from renaming a large slice of the app — most likely a new"
          print  "      dependency shipping a broad consumer keep rule. Find it with:"
          print  "        ./gradlew :app:analyzeReleaseR8Config"
          print  "        open app/build/reports/r8/r8-config-analyzer-release.html"
          print  "      Fix it with a narrow -keepclassmembers (see app/src/main/keepRules/knit-r8.keep)."
          fail = 1
        }
        if (total > MAXBYTES) {
          printf "\nFAIL: %d bytes of defined dex code exceeds the %d-byte ceiling.\n", total, MAXBYTES
          fail = 1
        }
        if (!fail) print "\nOK"
        exit fail
      }' MAXPCT="$MAX_NAMED_PCT" MAXBYTES="$MAX_DEX_BYTES"
