#!/usr/bin/env python3
"""Print a Markdown table of one Weblate component's progress per language, read through `wlc`.

Usage: progress.py PROJECT/COMPONENT [--url URL] [--include-source]

It runs `wlc --format json` with the user's own configuration (server and API key), so it needs nothing else:
one `ls` for the component's translations, then one `stats` per language. Columns: strings, translated %,
approved % (only meaningful with the review workflow on), needs editing, failing checks, suggestions, comments.
Pure stdlib.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys


def wlc(args: list[str], url: str | None) -> object:
    command = ["wlc", "--format", "json", *(["--url", url] if url else []), *args]
    try:
        done = subprocess.run(command, capture_output=True, text=True, check=False)
    except FileNotFoundError:
        sys.exit("progress: wlc is not installed (pipx install wlc)")
    if done.returncode != 0:
        sys.exit(f"progress: wlc {' '.join(args)} failed: {(done.stderr or done.stdout).strip()}")
    return json.loads(done.stdout)


def as_list(data: object) -> list[dict]:
    if isinstance(data, dict):
        return data.get("results", [data])
    return list(data)


def percent(value: object) -> str:
    return f"{float(value):.1f} %" if isinstance(value, (int, float)) else "—"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("component", help="PROJECT/COMPONENT")
    parser.add_argument("--url", help="API URL ending in /api/ (default: from the wlc configuration)")
    parser.add_argument("--include-source", action="store_true", help="also list the source language")
    args = parser.parse_args(argv)
    if args.component.count("/") != 1:
        parser.error("give the component as PROJECT/COMPONENT")

    translations = as_list(wlc(["ls", args.component], args.url))
    rows = []
    for translation in translations:
        code = translation.get("language_code") or translation.get("code")
        if not code or (translation.get("is_source") and not args.include_source):
            continue
        stats = wlc(["stats", f"{args.component}/{code}"], args.url)
        if not isinstance(stats, dict):
            continue
        rows.append(
            (
                code,
                stats.get("total", "—"),
                percent(stats.get("translated_percent")),
                percent(stats.get("approved_percent")),
                stats.get("fuzzy", "—"),
                stats.get("failing", "—"),
                stats.get("suggestions", "—"),
                stats.get("comments", "—"),
            )
        )

    print("| Language | Strings | Translated | Approved | Needs editing | Failing checks | Suggestions | Comments |")
    print("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    for row in sorted(rows):
        print("| " + " | ".join(str(cell) for cell in row) + " |")
    if not rows:
        print("\n(no translations besides the source)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
