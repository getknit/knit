#!/usr/bin/env python3
"""Derive the mesh simulator's reference contact tables from the published Bluetooth traces.

The tables are what `mesh/sim/ReferenceTable.kt` reads: for each trace, how many of its inter-contact times (gaps) and
contact lengths lie at or below each point of a log grid (0 s, then 20 points a decade from 1 s), with a header of the
numbers `ContactStats` would give. Only those counts are written: no device, no time of day, nothing of the trace that
could be put back together.

Every trace is read as its devices recorded it, one side at a time: "A saw B" from A's first sighting of B to its last,
ending where A's scans stopped finding B, the gap from one such run to A's next. The two ends' runs are never merged
into one contact, because the iMotes' clocks were set by hand and are not trusted below about five minutes (the Haggle
README), so whether two runs are one meeting cannot be told. Runs of one ordered pair that overlap are joined.

    scripts/sim-reference.py --haggle <cambridge/haggle unpacked> --reality <dump-anonymized.sql> [--out <dir>]

The Haggle directory holds `Exp3/` (Infocom 2005, CRAWDAD cambridge/haggle v. 2006-01-31) and `Exp6/` (Infocom 2006,
v. 2009-05-29); the Reality Mining dump is CRAWDAD mit/reality's `dump-anonymized.sql`. The raw traces may not be
redistributed; the tables are derived counts, written beside the simulator's test resources with their citations.
"""

import argparse
import calendar
import itertools
import math
import re
import sys
import time
from collections import defaultdict
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app/src/test/resources/app/getknit/knit/mesh/sim/reference"
GRID = [0.0] + [10 ** (i / 20) for i in range(151)]
MIN_TAIL = 20


def haggle(path, ids, period_s):
    """One Haggle experiment's runs among the devices in `ids`, as {(by, of): [(start, end)]} in seconds."""
    runs = defaultdict(list)
    with open(path) as lines:
        for line in lines:
            fields = line.split()
            if len(fields) < 4:
                continue
            by, of, start, end = (int(f) for f in fields[:4])
            if by in ids and of in ids and by != of:
                runs[(by, of)].append((start, end))
    return runs


SPAN = re.compile(r"\((\d+),'([0-9: -]+)','([0-9: -]+)',(\d+),(\d+)\)")
DEVICE = re.compile(r"\((\d+),'[0-9a-fA-F]*',(\d+),'")


def stamp(text):
    """A dump timestamp as seconds, read as UTC so no clock change moves it; None for MySQL's zero date."""
    if text.startswith("0000"):
        return None
    return calendar.timegm(time.strptime(text, "%Y-%m-%d %H:%M:%S"))


# The study ran from July 2004 to May 2005; a few phones stamped spans in early 2004, clocks that had reset.
STUDY = ("2004-07-01 00:00:00", "2005-06-01 00:00:00")


def reality(path):
    """Reality Mining's runs of one participant's phone sighting another's, as {(by, of): [(start, end)]}, the
    participants (those whose phone both scanned and was registered to them), and how many runs fell outside the
    study's months."""
    owner = {}
    spans = []
    with open(path, encoding="latin-1") as lines:
        for line in lines:
            if line.startswith("INSERT INTO `device` VALUES"):
                for match in DEVICE.finditer(line):
                    device, person = int(match.group(1)), int(match.group(2))
                    if person > 0:
                        owner[device] = person
            elif line.startswith("INSERT INTO `devicespan` VALUES"):
                for match in SPAN.finditer(line):
                    spans.append((match.group(3), match.group(2), int(match.group(4)), int(match.group(5))))
    scanners = {by for _, _, by, _ in spans}
    people = scanners & set(owner.values())
    begin, finish = (stamp(t) for t in STUDY)
    runs = defaultdict(list)
    outside = 0
    for start_text, end_text, by, device in spans:
        of = owner.get(device)
        if of is None or of == by or by not in people or of not in people:
            continue
        start, end = stamp(start_text), stamp(end_text)
        if start is None or end is None or end < start or start < begin or end > finish:
            outside += 1
            continue
        runs[(by, of)].append((start, end))
    return runs, people, outside


def joined(runs):
    """Each ordered pair's runs in order, overlapping or touching ones joined; and how many joins that took."""
    out = {}
    joins = 0
    for pair, spans in runs.items():
        spans.sort()
        merged = [list(spans[0])]
        for start, end in spans[1:]:
            if start <= merged[-1][1]:
                merged[-1][1] = max(merged[-1][1], end)
                joins += 1
            else:
                merged.append([start, end])
        out[pair] = merged
    return out, joins


def percentile(ordered, p):
    """Nearest rank, as `SimStats.percentile`."""
    if not ordered:
        return None
    rank = (p * len(ordered) + 99) // 100
    return ordered[min(max(rank - 1, 0), len(ordered) - 1)]


def cv(values):
    """Population standard deviation over the mean, as `ContactStats`."""
    if len(values) < 2:
        return None
    mean = sum(values) / len(values)
    if mean <= 0:
        return None
    return math.sqrt(sum((v - mean) ** 2 for v in values) / len(values)) / mean


def tail_llr(ordered):
    """Power law over exponential from the median up, as `ContactStats.tailLlr`."""
    if not ordered:
        return None
    xmin = ordered[(len(ordered) - 1) // 2]
    if xmin <= 0:
        return None
    tail = [x for x in ordered if x >= xmin]
    if len(tail) < MIN_TAIL:
        return None
    logs = sum(math.log(x / xmin) for x in tail)
    excess = sum(x - xmin for x in tail) / len(tail)
    if logs <= 0 or excess <= 0:
        return None
    alpha = 1 + len(tail) / logs
    lam = 1 / excess
    return sum(
        math.log(alpha - 1) - math.log(xmin) - alpha * math.log(x / xmin) - math.log(lam) + lam * (x - xmin) for x in tail
    ) / len(tail)


def cumulative(ordered):
    """How many of `ordered` lie at or below each grid point."""
    counts = []
    i = 0
    for x in GRID:
        while i < len(ordered) and ordered[i] <= x:
            i += 1
        counts.append(i)
    if i != len(ordered):
        sys.exit(f"a value past the grid's last point: {ordered[-1]} s")
    return counts


def fmt(value, digits):
    return "n/a" if value is None else f"{value:.{digits}f}"


def table(name, about, source, profile, period_s, runs, nodes, dropped):
    """The table's text for one trace; `dropped` runs were left out as faulty."""
    runs, joins = joined(runs)
    t0 = min(spans[0][0] for spans in runs.values())
    t1 = max(spans[-1][1] for spans in runs.values())
    gaps = sorted(b[0] - a[1] for spans in runs.values() for a, b in itertools.pairwise(spans))
    # A run under way when the trace began or still on when it ended has a length that is only a lower bound.
    lengths = sorted(end - start for spans in runs.values() for start, end in spans if start >= t0 + period_s and end <= t1 - period_s)
    sightings = sum(len(spans) for spans in runs.values())
    ordered_pairs = nodes * (nodes - 1)
    hours = (t1 - t0) / 3600
    header = [
        ("name", name),
        ("about", about),
        ("source", source),
        ("profile", profile),
        ("nodes", nodes),
        ("span_h", f"{hours:.1f}"),
        ("runs", sightings),
        ("runs_joined", joins),
        ("runs_dropped", dropped),
        ("per_pair_hour", f"{sightings / ordered_pairs / hours:.4f}"),
        ("pairs_seen_pct", len(runs) * 100 // ordered_pairs),
        ("gaps", len(gaps)),
        ("gap_p50_s", percentile(gaps, 50)),
        ("gap_p90_s", percentile(gaps, 90)),
        ("gap_cv", fmt(cv(gaps), 2)),
        ("gap_tail_llr", fmt(tail_llr(gaps), 2)),
        ("contacts", len(lengths)),
        ("contact_p50_s", percentile(lengths, 50)),
        ("contact_p90_s", percentile(lengths, 90)),
    ]
    lines = [f"# {key}={value}" for key, value in header]
    lines.append("x_s\tgaps_le\tcontacts_le")
    lines += [f"{x!r}\t{g}\t{c}" for x, g, c in zip(GRID, cumulative(gaps), cumulative(lengths))]
    return "\n".join(lines) + "\n", dict(header)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--haggle", type=Path, required=True, help="the unpacked cambridge/haggle directory")
    parser.add_argument("--reality", type=Path, required=True, help="mit/reality's dump-anonymized.sql")
    parser.add_argument("--out", type=Path, default=OUT, help=f"where the tables go (default {OUT})")
    args = parser.parse_args()

    traces = []
    imotes = set(range(1, 42))
    traces.append(
        (
            "infocom05",
            "Haggle iMotes carried at IEEE Infocom 2005, Miami, as each saw the others",
            "CRAWDAD cambridge/haggle v. 2006-01-31, Exp3, iMotes 1-41",
            "imote",
            120,
            haggle(args.haggle / "Exp3/contacts.Exp3.dat", imotes, 120),
            len(imotes),
            0,
        )
    )
    # Nodes 1-20 stood still or rode the lifts with a longer range; 21-98 were carried.
    carried = set(range(21, 99))
    traces.append(
        (
            "infocom06",
            "Haggle iMotes carried at IEEE Infocom 2006, Barcelona, as each saw the others",
            "CRAWDAD cambridge/haggle v. 2009-05-29, Exp6, carried iMotes 21-98",
            "imote",
            120,
            haggle(args.haggle / "Exp6/contacts.Exp6.dat", carried, 120),
            len(carried),
            0,
        )
    )
    runs, people, outside = reality(args.reality)
    traces.append(
        (
            "reality",
            "Reality Mining phones at MIT, 2004-2005, as each saw the other participants' phones",
            "CRAWDAD mit/reality v. 2005-07-01, devicespan between participants' registered phones, July 2004 to May 2005",
            "scan300",
            300,
            runs,
            len(people),
            outside,
        )
    )

    args.out.mkdir(parents=True, exist_ok=True)
    for name, about, source, profile, period, runs, nodes, dropped in traces:
        text, header = table(name, about, source, profile, period, runs, nodes, dropped)
        (args.out / f"{name}.tsv").write_text(text)
        print(" ".join(f"{k}={v}" for k, v in header.items() if k not in ("about", "source")))


if __name__ == "__main__":
    main()
