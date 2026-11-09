#!/usr/bin/env python3
"""Run F-Droid's source scanner over this tree, the way fdroiddata's `fdroid build` does before it builds.

fdroiddata scans a fresh checkout of the tagged commit and refuses the build on any finding, after the reproducible
rebuild in release.yml has already passed: v2.11.0's first fdroiddata build failed on a vendored package.json with no
lock. This runs the same `scan_source` on a copy of the tracked files, so a finding shows up in release.yml (and in
the maintainer's pre-tag checks) instead of in the fdroiddata MR.

The copy matters: scan_source deletes what it reports on or exempts (gradle-wrapper.jar,
gradle-daemon-jvm.properties, anything under a build entry's `scandelete`), so it must never run over the tree that
is then built. The copy holds the tracked files plus untracked ones git does not ignore, as they sit in the working
tree, so an uncommitted release bump is scanned as it will be committed.

When `.fdroid.yml` has a Builds entry for gradle.properties' versionCode, its `scanignore` and `scandelete` apply, as
they do on F-Droid. Needs fdroidserver importable (F-Droid's buildserver image has it; elsewhere `apt install
fdroidserver` and run this with the system python3).

Usage:
    fdroid-scan.py          exit 1 and list the findings when the scanner finds a problem
    fdroid-scan.py TREE     the same over another checkout (a hotfix branch cut before this script existed)
"""

from __future__ import annotations

import argparse
import logging
import re
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

REPO = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent


def build_entry() -> dict:
    """The .fdroid.yml Builds entry for gradle.properties' versionCode, or {} when there is none (main between
    releases)."""
    import yaml

    props = (REPO / "gradle.properties").read_text(encoding="utf-8")
    m = re.search(r"^knit\.versionCode=(\d+)\s*$", props, re.MULTILINE)
    if not m:
        sys.exit("fdroid-scan: no knit.versionCode in gradle.properties")
    meta = yaml.safe_load((REPO / ".fdroid.yml").read_text(encoding="utf-8")) or {}
    for b in meta.get("Builds") or []:
        if str(b.get("versionCode")) == m.group(1):
            return b
    return {}


def copy_tree(dest: Path) -> None:
    files = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=REPO, check=True, capture_output=True,
    ).stdout.split(b"\0")
    with tempfile.TemporaryFile() as buf:
        with tarfile.open(fileobj=buf, mode="w") as tar:
            for raw in filter(None, files):
                rel = raw.decode("utf-8", "surrogateescape")
                if (REPO / rel).is_file() or (REPO / rel).is_symlink():  # a tracked file deleted in the tree drops out
                    tar.add(REPO / rel, arcname=rel, recursive=False)
        buf.seek(0)
        with tarfile.open(fileobj=buf, mode="r") as tar:
            tar.extractall(dest, filter="tar")


def main() -> int:
    try:
        from fdroidserver import common, metadata, scanner
    except ImportError:
        sys.exit("fdroid-scan: fdroidserver is not importable (use F-Droid's buildserver image, or the system python3 "
                 "after `apt install fdroidserver`)")
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s: %(message)s")

    entry = build_entry()
    exempt = {k: entry.get(k) or [] for k in ("scanignore", "scandelete")}
    # scan_source reports a finding only through the CLI's options: with `json` set it collects them in the store
    # passed in, which is printed below (and nothing is logged twice).
    common.options = argparse.Namespace(json=True, verbose=False)
    store = scanner.MessageStore()
    with tempfile.TemporaryDirectory(prefix="fdroid-scan-") as tmp:
        copy_tree(Path(tmp))
        common.get_config()
        problems = scanner.scan_source(tmp, metadata.Build(exempt), json_per_build=store)
    for what, path in store.warnings:
        print(f"fdroid-scan: warning: {what} at {path}", file=sys.stderr)
    for what, path in store.errors:
        print(f"fdroid-scan: error: {what} at {path}", file=sys.stderr)
    named = ", ".join(f"{k} {v}" for k, v in exempt.items() if v) or "no exemptions"
    if problems:
        print(f"fdroid-scan: F-Droid's scanner found {problems} problem(s) ({named}); fdroiddata would refuse this "
              "build", file=sys.stderr)
        return 1
    print(f"fdroid-scan: clean ({named})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
