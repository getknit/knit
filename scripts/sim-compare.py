#!/usr/bin/env python3
"""Line up mesh-simulator reports by their `summary:` blocks.

Each report `ParkWalkSimTest` writes ends in a `summary:` block of `key=value` lines (`mesh/sim/SimSummary.kt`). This
reads any number of them, groups them by the outing's shape (walkers, minutes, inbound bound, gather, settle) and,
within a shape, by variant (the radio and its numbers, or a label you give), then prints every key as
`mean [min..max]` across the seeds of each variant, the variants side by side. That is the A/B a radio change is
judged by: never one run against another, always seeds against seeds.

Usage:
    scripts/sim-compare.py DIR_OR_REPORT ...
    scripts/sim-compare.py base=/media/walter/data/knit-sim/baseline-c1dcef23d ble1m=/media/walter/data/knit-sim/ble1m
    scripts/sim-compare.py --keys 'dm_all|custody|oracle' base=DIR new=DIR

A `label=` prefix names a variant; without one, a report's variant is its radio. A directory stands for every `*.txt`
in it. A report with no summary (a run killed before its end) is named on stderr and left out.
"""

import argparse
import re
import statistics
import sys
from collections import OrderedDict
from pathlib import Path

SHAPE_KEYS = ("walkers", "minutes", "inbound_slots", "gather", "settle_minutes")
VARIANT_KEYS = ("radio", "connect_m", "drop_m", "dial_ms")
SCENARIO_KEYS = ("seed", "field_m") + SHAPE_KEYS + VARIANT_KEYS
LINE = re.compile(r"^  ([a-z][a-z0-9_]*)=(\S+)$")


def summary(path):
    """The last `summary:` block of a report, as an ordered dict, or None."""
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    starts = [i for i, line in enumerate(lines) if line == "summary:"]
    if not starts:
        return None
    values = OrderedDict()
    for line in lines[starts[-1] + 1:]:
        match = LINE.match(line)
        if not match:
            break
        values[match.group(1)] = match.group(2)
    return values


def reports(arg):
    """(label or None, report paths) for one argument."""
    label, sep, rest = arg.partition("=")
    if not sep or "/" in label:
        label, rest = None, arg
    path = Path(rest)
    files = sorted(path.glob("*.txt")) if path.is_dir() else [path]
    return label, files


def number(value):
    try:
        return float(value)
    except ValueError:
        return None


def cell(values):
    """Mean [min..max] of the numbers, `none` counted apart; words counted by kind."""
    numbers = [n for n in (number(v) for v in values) if n is not None]
    nones = sum(1 for v in values if v == "none")
    words = [v for v in values if number(v) is None and v != "none"]
    parts = []
    if numbers:
        mean = statistics.fmean(numbers)
        whole = all(n.is_integer() for n in numbers)
        fmt = (lambda n: f"{n:.0f}") if whole and abs(mean) >= 10 else (lambda n: f"{n:.1f}")
        shown = fmt(mean) if mean.is_integer() or not whole else f"{mean:.1f}"
        if len(numbers) == 1:
            parts.append(fmt(numbers[0]))
        else:
            parts.append(f"{shown} [{fmt(min(numbers))}..{fmt(max(numbers))}]")
    if words:
        counts = OrderedDict()
        for w in words:
            counts[w] = counts.get(w, 0) + 1
        parts.append(" ".join(f"{w}×{n}" for w, n in counts.items()))
    if nones:
        parts.append("none" if nones == len(values) else f"none×{nones}")
    return " ".join(parts) if parts else "-"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("inputs", nargs="+", help="reports or directories, each optionally label=…")
    parser.add_argument("--keys", help="only the keys this regular expression finds")
    args = parser.parse_args()
    keep = re.compile(args.keys) if args.keys else None

    # shape -> variant -> list of summaries
    table = OrderedDict()
    missing = 0
    for arg in args.inputs:
        label, files = reports(arg)
        for path in files:
            values = summary(path)
            if values is None:
                print(f"no summary (cut short?): {path}", file=sys.stderr)
                missing += 1
                continue
            shape = " ".join(f"{k}={values.get(k, '?')}" for k in SHAPE_KEYS)
            variant = label or " ".join(f"{k}={values.get(k, '?')}" for k in VARIANT_KEYS)
            table.setdefault(shape, OrderedDict()).setdefault(variant, []).append((path, values))

    for shape, variants in table.items():
        print(f"\n== {shape}")
        names = list(variants)
        for name in names:
            seeds = sorted(v.get("seed", "?") for _, v in variants[name])
            print(f"   {name}: {len(seeds)} runs, seeds {' '.join(seeds)}")
        keys = OrderedDict()
        for runs in variants.values():
            for _, values in runs:
                for key in values:
                    if key not in SCENARIO_KEYS and (keep is None or keep.search(key)):
                        keys[key] = None
        cells = [[cell([v[key] for _, v in variants[n] if key in v]) for n in names] for key in keys]
        width = max([len(k) for k in keys] + [3])
        columns = [max([len(n)] + [len(row[i]) for row in cells]) for i, n in enumerate(names)]
        print("   " + " " * width + "  " + "  ".join(n.ljust(columns[i]) for i, n in enumerate(names)))
        for key, row in zip(keys, cells):
            print("   " + key.ljust(width) + "  " + "  ".join(c.ljust(columns[i]) for i, c in enumerate(row)))
    return 1 if missing and not table else 0


if __name__ == "__main__":
    sys.exit(main())
