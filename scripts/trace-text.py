#!/usr/bin/env python3
"""Reads the text a crash carried in its stack frames out of a report on stdin.

The frames are `crash/TraceText.kt`'s. Feed it a Play report's text, or `dumpsys dropbox --print data_app_crash`:

    gpc auth token  # then errorReports:search, see the storage.md entry for ADR 2026-10.47rw
    python3 scripts/trace-text.py < report.txt

Prints one line per text found, in the order the report has them.
"""

import re
import sys

FRAME = re.compile(r"knit\.TraceText\.t(\w+)\s*\(TraceText:(\d+)\)")
ESCAPE = re.compile(rb"_([0-9a-f]{2})")


def texts(report):
    """Each carried text in report, its chunks joined before unescaping since a chunk can split an escape."""
    found = []
    for chunk, line in FRAME.findall(report):
        if line == "1":
            found.append("")
        if found:
            found[-1] += chunk
    return [
        ESCAPE.sub(lambda m: bytes([int(m[1], 16)]), text.encode()).decode("utf-8", "replace")
        for text in found
    ]


def main():
    carried = texts(sys.stdin.read())
    if not carried:
        sys.exit("no TraceText frames in this report")
    print("\n".join(carried))


if __name__ == "__main__":
    main()
