#!/usr/bin/env bash
#
# Guard Knit's release build against R8 renaming a platform class.
#
# WHY THIS EXISTS. A library that bundles its own copy of a platform class (ARSCLib ships org.xmlpull.v1.*,
# android.util.AttributeSet and android.content.res.XmlResourceParser) hands R8 a *program* class with a
# platform name, and R8 full mode renames it like any other. At runtime the platform's own class wins, so
# every reference R8 rewrote now names a type the framework's objects do not implement. It has bitten three
# times, each at a vector drawable: an IncompatibleClassChangeError (ADR 012), a `throw null` on the chat
# list (ADR 050), and a ClassCastException to a renamed XmlResourceParser that shipped in 2.11.0-alpha.1 and
# stopped Knit opening at all (GitHub #59). The renames sat in every mapping for months before an R8 upgrade
# made one fatal, and no dex size gate sees them. app/build.gradle.kts's StripPlatformCopies transform now
# removes such copies from every plain jar a variant packages; this gate catches one that gets past it, such
# as a copy inside an AAR, which the transform does not see.
#
# WHAT IT MEASURES. Every class R8's mapping lists under a new name, checked against the classes android.jar
# defines. One match fails the build: find the artifact that ships the copy and strip it (StripPlatformCopies), or
# pin the name in app/src/main/keepRules/knit-r8.keep and keep one implementor alive (ADR 050).
#
# Usage: scripts/r8-platform-gate.sh <mapping.txt> [android.jar]
set -euo pipefail

MAPPING="${1:?usage: r8-platform-gate.sh <mapping.txt> [android.jar]}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
# The newest installed platform: classes are only ever added to android.jar, so it covers every older one.
ANDROID_JAR="${2:-$(find "$SDK/platforms" -maxdepth 2 -name android.jar 2>/dev/null | sort -V | tail -1 || true)}"
[ -f "$MAPPING" ] || { echo "r8-platform-gate: no mapping at $MAPPING" >&2; exit 2; }
[ -f "${ANDROID_JAR:-}" ] || { echo "r8-platform-gate: android.jar not found (pass it, or set ANDROID_HOME)" >&2; exit 2; }

# A class line is `original -> renamed:` at column 0; members are indented and comments start with `#`.
renamed="$(awk '/^[^ #].* -> .*:$/ { n = $3; sub(/:$/, "", n); if ($1 != n) print $1 " -> " n }' "$MAPPING")"
platform="$(unzip -Z1 "$ANDROID_JAR" '*.class' | sed 's/\.class$//; s|/|.|g')"
hits="$(awk 'NR == FNR { p[$0] = 1; next } ($1 in p)' <(printf '%s\n' "$platform") <(printf '%s\n' "$renamed"))"

echo "renamed classes: $(printf '%s' "$renamed" | grep -c . || true), checked against $ANDROID_JAR"
if [ -n "$hits" ]; then
  echo
  echo "FAIL: R8 renamed classes the platform defines:"
  printf '%s\n' "$hits" | sed 's/^/    /'
  echo
  echo "      A dependency bundles a copy of each. The platform's copy is the one that loads, so the renamed name"
  echo "      matches nothing at runtime. Strip the copy before R8 (app/build.gradle.kts, StripPlatformCopies only"
  echo "      sees plain jars), or pin the name in app/src/main/keepRules/knit-r8.keep (ADR 050)."
  exit 1
fi
echo "OK"
