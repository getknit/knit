#!/usr/bin/env python3
"""Convert a PeTrack trajectory file (the Jülich/Wuppertal pedestrian archive) to a BonnMotion `.movements` trace.

The archive's `.txt` trajectories are `id frame x y z` rows, metres, at a stated frame rate (`# framerate: 25 fps`). The
simulator replays BonnMotion's format (`mesh/sim/TraceMobility.kt`): one line a pedestrian, `time x y` triples in
seconds and metres, times rising. This resamples each pedestrian at a fixed step (the radio's 0.5 s tick by default),
keeping its first and last frame so it appears and leaves when it did, and shifts the whole run so every coordinate is
non-negative, with a margin. Times start at the run's first frame.

Usage:
    scripts/sim-trace.py IN.txt OUT.movements.gz [--step 0.5] [--margin 1.0]

The output is gzipped when its name ends in `.gz`, with a zero mtime, so the same input always gives the same bytes.
"""

import argparse
import gzip
import re
import sys
from collections import defaultdict

FPS = re.compile(r"framerate:\s*([0-9.]+)\s*fps")


def read(path):
    """(frame rate, {id: [(frame, x, y)]}) from a PeTrack text file."""
    fps = None
    tracks = defaultdict(list)
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                match = FPS.search(line)
                if match:
                    fps = float(match.group(1))
                continue
            parts = line.split()
            if len(parts) < 4:
                continue
            tracks[int(parts[0])].append((int(parts[1]), float(parts[2]), float(parts[3])))
    if fps is None:
        sys.exit(f"{path}: no '# framerate: N fps' line")
    return fps, tracks


def resample(points, fps, step):
    """[(t, x, y)] at every multiple of [step] inside the track, plus its first and last frame."""
    points.sort()
    times = [frame / fps for frame, _, _ in points]
    first, last = times[0], times[-1]
    grid = [first]
    k = int(first / step) + 1
    while k * step < last:
        grid.append(k * step)
        k += 1
    if last > first:
        grid.append(last)
    out = []
    i = 0
    for t in grid:
        while i + 1 < len(times) and times[i + 1] <= t:
            i += 1
        if i + 1 < len(times) and times[i + 1] > times[i]:
            done = (t - times[i]) / (times[i + 1] - times[i])
            x = points[i][1] + (points[i + 1][1] - points[i][1]) * done
            y = points[i][2] + (points[i + 1][2] - points[i][2]) * done
        else:
            x, y = points[i][1], points[i][2]
        out.append((t, x, y))
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source")
    parser.add_argument("target")
    parser.add_argument("--step", type=float, default=0.5, help="seconds between resampled waypoints")
    parser.add_argument("--margin", type=float, default=1.0, help="metres between the run and the field's edge")
    args = parser.parse_args()
    fps, tracks = read(args.source)
    start = min(frame for track in tracks.values() for frame, _, _ in track) / fps
    paths = {pid: resample(track, fps, args.step) for pid, track in sorted(tracks.items())}
    x0 = min(x for path in paths.values() for _, x, _ in path) - args.margin
    y0 = min(y for path in paths.values() for _, _, y in path) - args.margin
    lines = []
    for path in paths.values():
        lines.append(" ".join(f"{t - start:.2f} {x - x0:.2f} {y - y0:.2f}" for t, x, y in path))
    data = ("\n".join(lines) + "\n").encode("utf-8")
    if args.target.endswith(".gz"):
        with open(args.target, "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as out:
            out.write(data)
    else:
        with open(args.target, "wb") as out:
            out.write(data)
    print(f"{args.target}: {len(paths)} pedestrians, {sum(len(p) for p in paths.values())} waypoints, "
          f"{max(t for p in paths.values() for t, _, _ in p) - start:.1f} s", file=sys.stderr)


if __name__ == "__main__":
    main()
