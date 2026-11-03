#!/usr/bin/env python3
"""Derive Knit's versionCode from its versionName (ADR 2026-11.7t9z).

    versionCode = major*10^7 + minor*10^5 + patch*10^3 + stage*10^2 + n
    stage: 0 = alpha, 1 = beta, 2 = rc, 9 = stable

Every hotfix of a line sorts below every pre-release of the next line, so a production patch never pulls Play's alpha
testers back onto the older line, and a stable sorts above its own pre-releases. app/build.gradle.kts computes the
same number (`knitVersionCode`) and refuses a gradle.properties whose `knit.versionCode` disagrees; the stored value
stays because fdroiddata's UpdateCheckData reads it from that file. This copy serves release.yml's preflight and CI.

Usage:
    version-code.py 2.11.0-alpha.4   print the code for a versionName
    version-code.py --check          gradle.properties' knit.versionCode must equal the derived code
    version-code.py --write          set knit.versionCode from knit.versionName in gradle.properties
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

PROPERTIES = Path(__file__).resolve().parent.parent / "gradle.properties"

STAGES = {"alpha": 0, "beta": 1, "rc": 2}
STABLE = 9
VERSION_NAME = re.compile(r"(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?")

# Major stops at 99, not at Play's 2 100 000 000 cap (major 209): the watch APKs take 1 000 000 000 + this code
# (wear/, watchface/), and a phone code at or above 10^9 would land in the watch range.
MAX_MAJOR = 99
MAX_FIELD = 99


def version_code(name: str) -> int:
    """The versionCode for a versionName; ValueError for a name the scheme cannot place."""
    m = VERSION_NAME.fullmatch(name)
    if not m:
        raise ValueError(f"versionName {name!r} is not X.Y.Z or X.Y.Z-(alpha|beta|rc).N")
    major, minor, patch = (int(g) for g in m.group(1, 2, 3))
    stage = STAGES[m.group(4)] if m.group(4) else STABLE
    n = int(m.group(5)) if m.group(5) else 0
    if major > MAX_MAJOR or minor > MAX_FIELD or patch > MAX_FIELD or n > MAX_FIELD:
        raise ValueError(
            f"versionName {name!r} is out of range: major <= {MAX_MAJOR}, minor, patch and N <= {MAX_FIELD}"
        )
    return major * 10**7 + minor * 10**5 + patch * 10**3 + stage * 10**2 + n


def read_properties(text: str) -> dict[str, str]:
    props = {}
    for line in text.splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def main(argv: list[str]) -> int:
    if len(argv) == 1 and not argv[0].startswith("--"):
        print(version_code(argv[0]))
        return 0
    if argv not in (["--check"], ["--write"]):
        print(__doc__, file=sys.stderr)
        return 2
    text = PROPERTIES.read_text()
    props = read_properties(text)
    name = props["knit.versionName"]
    want = version_code(name)
    have = props.get("knit.versionCode")
    if argv == ["--write"]:
        new, count = re.subn(r"(?m)^knit\.versionCode=.*$", f"knit.versionCode={want}", text)
        if count != 1:
            print("gradle.properties has no single knit.versionCode line", file=sys.stderr)
            return 1
        PROPERTIES.write_text(new)
        print(f"knit.versionCode={want} ({name})")
        return 0
    if have != str(want):
        print(
            f"gradle.properties: knit.versionCode={have} but {name} derives {want} "
            "(ADR 2026-11.7t9z); run scripts/version-code.py --write",
            file=sys.stderr,
        )
        return 1
    print(f"OK: {name} -> {want}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
