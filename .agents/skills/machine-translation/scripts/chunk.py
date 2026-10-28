#!/usr/bin/env python3
"""Split the English strings into chunks for parallel translator agents.

Writes OUT/chunk1.en.xml … chunkN.en.xml: complete resource files that parse on their own, each a run of the English
file's translatable elements in order, with the comments above each element kept (they are the translator's context)
and `translatable="false"` elements left out together with their comments.

With --missing LOCALE only the strings that locale lacks are chunked: the delta after new English strings land.

Usage: chunk.py OUT [--chunks 7] [--missing LOCALE]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import ENGLISH, HEADER, FOOTER, elements, strings_file, translatable, units  # noqa: E402


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("out", type=Path)
    parser.add_argument("--chunks", type=int, default=7)
    parser.add_argument("--missing", metavar="LOCALE", help="chunk only the strings this locale lacks")
    args = parser.parse_args(argv)

    have: set[str] = set()
    if args.missing:
        target = strings_file(args.missing)
        if not target.exists():
            print(f"chunk: {target} does not exist", file=sys.stderr)
            return 2
        have = {n for n, _ in elements(target.read_text(encoding="utf-8"))}

    entries: list[tuple[list[str], str]] = []
    comments: list[str] = []
    for comment, el in units(ENGLISH.read_text(encoding="utf-8")):
        if comment:
            comments.append(comment)
            continue
        name = elements(el)[0][0]
        if translatable(el) and name not in have:
            entries.append((comments, el))
        comments = []

    if not entries:
        print("chunk: nothing to translate")
        return 0
    size = -(-len(entries) // max(1, args.chunks))
    n = -(-len(entries) // size)
    args.out.mkdir(parents=True, exist_ok=True)
    for old in args.out.glob("chunk*.en.xml"):
        old.unlink()
    for i in range(n):
        part = entries[i * size:(i + 1) * size]
        lines = [f"    {c}" for cs, el in part for c in [*cs, el]]
        path = args.out / f"chunk{i + 1}.en.xml"
        path.write_text(HEADER + "\n".join(lines) + "\n" + FOOTER, encoding="utf-8")
        print(f"{path}: {len(part)} strings")
    print(f"chunk: {len(entries)} strings in {n} chunk(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
