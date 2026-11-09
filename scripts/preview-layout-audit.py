#!/usr/bin/env python3
"""Audit every Compose preview for content its own layout cuts off (GitHub #62's shape).

    python3 scripts/preview-layout-audit.py                      # every preview, every variant
    python3 scripts/preview-layout-audit.py --only CallScreenCallerContext,CallScreenLongNameCaller
    python3 scripts/preview-layout-audit.py --report DIR         # re-read an earlier run without Gradle

Writes the preview manifest (`gen-screenshot-tests.py --manifest`), runs `PreviewClippingTest` under
`-Pknit.previewLayout=<dir>` through `scripts/gradle-turn.sh`, then prints each case's findings: a text or a
scrolling area cut off with no scroll to reach it, a text overflowing its box with no ellipsis, two texts on top of
each other. The variants (a 360×640 dp phone, the same in en-XA long text, 411×891 at font scale 1.5) are
`PreviewClippingTest.Variant`. Exits 1 when anything is found, or when a preview has no result for some variant (a
crashed test JVM); GitHub CI's `preview-layout` job runs it on every change as a gate. Read a finding against the
preview it names before changing a layout; a preview the audit cannot judge goes in `SKIP` with its reason.
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DIR = ROOT / "app/build/preview-layout"
TEST = "app.getknit.knit.ui.preview.PreviewClippingTest"

# Previews the audit cannot judge, each with why. Keep this short: a skipped preview is a screen nobody checks.
SKIP = {
    # Its text field in a dialog never lets Compose idle under Robolectric (PreviewStringsTest fails on it too), and a
    # dialog is its own window, outside the frame the audit reads.
    "RenameGroupDialog": "never idles under Robolectric",
}


def run(out: Path, only: set[str] | None) -> None:
    out.mkdir(parents=True, exist_ok=True)
    manifest = out / "manifest.json"
    subprocess.run([sys.executable, ROOT / "scripts/gen-screenshot-tests.py", "--manifest", manifest], check=True)
    entries = json.loads(manifest.read_text(encoding="utf-8"))
    known = {e["wrapper"] for e in entries}
    stale = SKIP.keys() - known
    if stale:
        sys.exit(f"SKIP names previews that no longer exist: {', '.join(sorted(stale))}")
    if only:
        missing = only - known
        if missing:
            sys.exit(f"no preview wraps as: {', '.join(sorted(missing))}")
    entries = [e for e in entries if e["wrapper"] not in SKIP and (not only or e["wrapper"] in only)]
    manifest.write_text(json.dumps(entries, indent=1) + "\n", encoding="utf-8")
    layout = out / "layout"
    for old in layout.glob("*.txt") if layout.exists() else []:
        old.unlink()
    print("Composing every preview at each variant (PreviewClippingTest)…", flush=True)
    subprocess.run(
        [
            ROOT / "scripts/gradle-turn.sh",
            "./gradlew",
            ":app:testDebugUnitTest",
            f"-Pknit.previewLayout={out}",
            "--tests",
            TEST,
            "--rerun",
            "-q",
        ],
        cwd=ROOT,
        check=False,  # every failing case is a finding, read from the files below
        stdout=subprocess.DEVNULL,
    )


def report(out: Path) -> int:
    cases = sorted((out / "layout").glob("*.txt"))
    if not cases:
        sys.exit(f"no results under {out / 'layout'}: did Gradle fail before the test ran?")
    # Every preview the manifest names must have a result for every variant: a test JVM that died part-way leaves
    # previews unchecked, which must not read as clean.
    wrappers = {e["wrapper"] for e in json.loads((out / "manifest.json").read_text(encoding="utf-8"))}
    seen = [case.stem.split("@") for case in cases]
    variants = {variant for _, variant in seen}
    unchecked = sorted(f"{w}@{v}" for w in wrappers for v in variants if [w, v] not in seen)
    if unchecked:
        print(f"{len(unchecked)} cases have no result (did the test JVM die?): {', '.join(unchecked[:20])}")
        return 1
    found = 0
    for case in cases:
        lines = [line for line in case.read_text(encoding="utf-8").splitlines() if line]
        if not lines:
            continue
        found += 1
        wrapper, variant = case.stem.split("@")
        print(f"\n{wrapper} @ {variant}")
        for line in lines:
            print(f"  {line}")
    print(f"\n{found} of {len(cases)} cases have findings." if found else f"✓ {len(cases)} cases, nothing cut off.")
    return 1 if found else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--only", help="comma-separated wrapper names (as in the screenshot tests) to limit the run to")
    parser.add_argument("--dir", type=Path, default=DEFAULT_DIR, help=f"work directory (default {DEFAULT_DIR})")
    parser.add_argument("--report", action="store_true", help="print an earlier run's findings without Gradle")
    args = parser.parse_args(argv)
    if not args.report:
        run(args.dir.resolve(), set(args.only.split(",")) if args.only else None)
    return report(args.dir.resolve())


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
