#!/usr/bin/env bash
# Regenerate Gradle's dependency verification files: gradle/verification-metadata.xml and the keys it trusts,
# gradle/verification-keyring.keys (armoured; `<keyring-format>` keeps Gradle from writing a binary .gpg beside it).
#
# Gradle checks every artifact it downloads (library, plugin, tool, wrapper-launched Gradle plugin) against these
# files and fails the build on a mismatch. A signed artifact passes when its signature verifies against a trusted key
# for its group; anything else needs its SHA-256 listed. The keyring is committed and key servers are switched off, so
# no build, F-Droid's included, depends on a key server.
#
# Run it after anything that moves a downloaded artifact (a catalog bump, a new dependency, a plugin, AGP or Gradle),
# in the same commit as the regenerated app/gradle.lockfile. Then read the diff: a new trusted key or a new checksum is
# a new party the build now trusts, and a changed checksum for an unchanged version is a red flag, never noise.
#
# Each pass is merged into what is there, so the opt-in modules (-Pknit.wear, -Pknit.baselineProfile) and a local
# knit-translate-native checkout stay covered. --clean deletes both files first, which drops entries no build needs any
# more; a clean run needs the knit-translate-native checkout beside this one, or that build's entries go.
#
# Usage: scripts/verification-metadata.sh [--clean]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
NATIVE="$ROOT/../knit-translate-native"

if [ "${1:-}" = "--clean" ]; then
  if [ ! -d "$NATIVE" ]; then
    echo "error: --clean needs the knit-translate-native checkout at $NATIVE" >&2
    exit 1
  fi
  rm -f gradle/verification-metadata.xml gradle/verification-keyring.keys
fi

# Builds never ask a key server (`<key-servers enabled="false"/>`); a regeneration has to, for any key it has not
# seen. Off is the element; on is its absence (Gradle drops a default when it rewrites the file). On for the run, off
# again however it ends.
METADATA=gradle/verification-metadata.xml
servers_on() { [ ! -f "$METADATA" ] || sed -i '/<key-servers enabled="false"\/>/d' "$METADATA"; }
servers_off() {
  [ -f "$METADATA" ] || return 0
  grep -q '<key-servers enabled="false"/>' "$METADATA" \
    || sed -i 's|^\( *\)<verify-signatures>true</verify-signatures>$|&\n\1<key-servers enabled="false"/>|' "$METADATA"
}
servers_on
trap servers_off EXIT

# -Ptranslate=false: the lockfile's own rule (no maintainer flag), whatever ~/.gradle/gradle.properties says. The
# translate-native AAR is on the compile classpaths either way.
write() {
  scripts/gradle-turn.sh ./gradlew -q --write-verification-metadata sha256,pgp --export-keys -Ptranslate=false "$@"
}

passes() {
  # Every declared configuration of each project (the lockfile locks them all, so the tool configurations of lint,
  # ktlint, detekt, Kover and the screenshot plugin are among them), then a build of each kind for what AGP resolves
  # only while a task runs (AAPT2's platform binary among them).
  write :dependencies :app:dependencies :detekt-rules:dependencies :app:assembleDebug :app:assembleRelease
  # The maintainer flags that unlock runtime classpaths (app/build.gradle.kts, `dependencyLocking`). Unlocked, Gradle
  # walks every version a conflict offers and reads each one's metadata, versions the locked passes never see. The
  # later -P wins over write()'s.
  write -Ptranslate=true -Pstt=true :app:dependencies
  write -Ptranslate=true -Pcalls=false :app:dependencies
  # The opt-in modules.
  write -Pknit.wear=true -Pknit.baselineProfile=true \
    :wear:dependencies :watchface:dependencies :watchface-monogram:dependencies :baselineprofile:dependencies
  # The translation runtime built from source (settings.gradle.kts): its build's own plugins.
  if [ -d "$NATIVE" ]; then
    write -Pknit.translateNative="$NATIVE" :app:dependencies
  else
    echo "note: no knit-translate-native checkout at $NATIVE; its build's entries are left as they are"
  fi
}

# A key a key server failed to serve in one pass is written as ignored, and stays ignored after a later pass fetched it
# into the keyring: every artifact it signed then fails with "All public keys have been ignored". Drop those entries
# and run the passes again, so Gradle records the trust it skipped, until no ignored key is one the keyring holds.
# Prints how many it dropped.
prune_ignored() {
  python3 -I - "$METADATA" gradle/verification-keyring.keys <<'PY'
import re, subprocess, sys
metadata, keyring = sys.argv[1], sys.argv[2]
listing = subprocess.run(["gpg", "--show-keys", "--with-colons", keyring], capture_output=True, text=True, check=True)
held = [line.split(":")[9] for line in listing.stdout.splitlines() if line.startswith("fpr:")]
text = open(metadata).read()
stale = [k for k in re.findall(r'<ignored-key id="([0-9A-F]+)"', text) if any(f.endswith(k) for f in held)]
for key in stale:
    text = re.sub(r'\n *<ignored-key id="%s"[^>]*/>' % key, "", text)
open(metadata, "w").write(text)
print(len(stale))
PY
}

command -v gpg > /dev/null || { echo "error: gpg is needed to read the keyring" >&2; exit 1; }
for round in 1 2 3; do
  passes
  dropped=$(prune_ignored)
  [ "$dropped" = 0 ] && break
  echo "round $round: dropped $dropped ignored key(s) the keyring holds; running the passes again"
done
if [ "$dropped" != 0 ]; then
  echo "error: ignored keys the keyring holds after three rounds; see $METADATA" >&2
  exit 1
fi
echo "Wrote gradle/verification-metadata.xml and gradle/verification-keyring.keys; read the diff before committing."
