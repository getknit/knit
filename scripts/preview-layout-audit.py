#!/usr/bin/env python3
"""Audit every Compose preview for content its own layout cuts off (GitHub #62's shape).

    python3 scripts/preview-layout-audit.py                      # every preview, every variant
    python3 scripts/preview-layout-audit.py --only CallScreenCallerContext,CallScreenLongNameCaller
    python3 scripts/preview-layout-audit.py --report DIR         # re-read an earlier run without Gradle

Writes the preview manifest (`gen-screenshot-tests.py --manifest`), runs `PreviewClippingTest` under
`-Pknit.previewLayout=<dir>` through `scripts/gradle-turn.sh`, then prints each case's findings: a text or a
scrolling area cut off with no scroll to reach it, a text overflowing its box with no ellipsis, two texts on top of
each other. The variants (a 360×640 dp phone, the same in en-XA long text, 411×891 at font scale 1.5) are
`PreviewClippingTest.Variant`. Exits 1 when anything is found. A review list, not a gate: read each finding against
the screenshot it names before changing a layout.
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DIR = ROOT / "app/build/preview-layout"
TEST = "app.getknit.knit.ui.preview.PreviewClippingTest"


def run(out: Path, only: set[str] | None) -> None:
    out.mkdir(parents=True, exist_ok=True)
    manifest = out / "manifest.json"
    subprocess.run([sys.executable, ROOT / "scripts/gen-screenshot-tests.py", "--manifest", manifest], check=True)
    if only:
        entries = [e for e in json.loads(manifest.read_text(encoding="utf-8")) if e["wrapper"] in only]
        missing = only - {e["wrapper"] for e in entries}
        if missing:
            sys.exit(f"no preview wraps as: {', '.join(sorted(missing))}")
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
