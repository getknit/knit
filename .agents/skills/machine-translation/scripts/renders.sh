#!/usr/bin/env bash
# Render every screenshot subject in one locale, for agents to inspect for clipped or overflowing text.
#
# Adds a temporary `@Preview(locale = …)` beside the `Rtl` one on both multipreview annotations in
# ScreenshotPreviews.kt, runs validateDebugScreenshotTest (it renders to build/ and never writes a reference; the new
# preview has none, so the task may fail: the renders are still there), copies the locale's PNGs to OUT/shots/,
# restores the annotation file whatever happens, and splits the list into OUT/shots-part00.. (absolute paths).
#
# Usage: renders.sh LOCALE OUT [PARTS=3]
set -euo pipefail

locale="${1:?usage: renders.sh LOCALE OUT [PARTS]}"
out="${2:?usage: renders.sh LOCALE OUT [PARTS]}"
parts="${3:-3}"
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
repo="$(cd "$here/../../../.." && pwd)"
cd "$repo"

previews=app/src/screenshotTest/kotlin/app/getknit/knit/screenshot/ScreenshotPreviews.kt
rendered=app/build/outputs/screenshotTest-results/preview/debug/rendered
qualifier="$(python3 -c "import sys; sys.path.insert(0, '$here'); from common import android_dir
print(android_dir('$locale')[len('values-'):])")"
name="$(python3 -c "import re; s = re.sub(r'[^A-Za-z0-9]', '', '$locale'); print(s[:1].upper() + s[1:])")"

die() { echo "renders: $*" >&2; exit 1; }
git diff --quiet -- "$previews" || die "$previews has uncommitted changes; commit or set them aside"
trap 'git checkout -- "$previews"' EXIT

# A temporary twin of each `Rtl` preview, in the locale.
rtl='@Preview(name = "Rtl", showBackground = true,'
twin="@Preview(name = \"$name\", showBackground = true,"
sed -i \
  -e "s|^$rtl locale = RIGHT_TO_LEFT)\$|&\n$twin locale = \"$qualifier\")|" \
  -e "s|^$rtl device = SCREEN_SPEC, locale = RIGHT_TO_LEFT)\$|&\n$twin device = SCREEN_SPEC, locale = \"$qualifier\")|" \
  "$previews"
added="$(grep -c "name = \"$name\"" "$previews" || true)"
[[ "$added" == 2 ]] || die "expected to add 2 previews, added $added: has ScreenshotPreviews.kt changed shape?"

mkdir -p "$out/shots" "$out/logs"
rm -f "$out"/shots/*.png "$out"/shots-part*
[[ -d "$rendered" ]] && find "$rendered" -name "*_${name}_*.png" -delete
echo "renders: rendering every subject as $qualifier (preview \"$name\"); this takes a few minutes"
scripts/gradle-turn.sh ./gradlew :app:validateDebugScreenshotTest -Ptranslate=false -q \
  >"$out/logs/renders.log" 2>&1 || true

find "$rendered" -name "*_${name}_*.png" -exec cp {} "$out/shots/" \; 2>/dev/null || true
count="$(find "$out/shots" -name '*.png' | wc -l)"
if [[ "$count" == 0 ]]; then
  tail -30 "$out/logs/renders.log" >&2
  die "no *_${name}_*.png rendered (log: $out/logs/renders.log)"
fi
find "$out/shots" -name '*.png' | sort >"$out/shots-all.txt"
split -n "l/$parts" -d "$out/shots-all.txt" "$out/shots-part"
echo "renders: $count renders in $out/shots; parts:"
wc -l "$out"/shots-part* | sed 's/^/  /'
