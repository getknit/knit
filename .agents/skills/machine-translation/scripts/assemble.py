#!/usr/bin/env python3
"""Assemble translated chunks into the locale's strings.xml, in the English file's order.

Reads DIR/chunk1.<locale>.xml … chunkN.<locale>.xml (complete resource files or bare fragments), orders every element
by the English file, re-indents (4 spaces, plural items 8) and writes app/src/main/res/values-<locale>/strings.xml.
A name missing from the chunks, one the English no longer has, or one written twice stops it before anything is
written.

With --merge the chunks hold a delta (chunk.py --missing): their elements join the strings the locale's file already
has, a name the English dropped is removed (and named), and the file is rewritten in the English order.

Usage: assemble.py DIR --locale LOCALE [--merge] [--out PATH]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import elements, english, strings_file, write_resources  # noqa: E402


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("dir", type=Path)
    parser.add_argument("--locale", required=True)
    parser.add_argument("--merge", action="store_true", help="add the chunks to the locale's existing strings")
    parser.add_argument("--out", type=Path, help="default: app/src/main/res/values-<locale>/strings.xml")
    args = parser.parse_args(argv)
    out = args.out or strings_file(args.locale)

    chunks = sorted(args.dir.glob(f"chunk*.{args.locale}.xml"),
                    key=lambda p: int(re.search(r"chunk(\d+)\.", p.name).group(1)))
    if not chunks:
        print(f"assemble: no chunk*.{args.locale}.xml in {args.dir}", file=sys.stderr)
        return 2
    found: dict[str, str] = {}
    problems: list[str] = []
    for path in chunks:
        for name, el in elements(path.read_text(encoding="utf-8")):
            if name in found:
                problems.append(f"{name}: in more than one chunk ({path.name})")
            found[name] = el

    source = [n for n, _ in english()]
    known = set(source)
    if args.merge:
        if not out.exists():
            print(f"assemble: --merge needs an existing {out}", file=sys.stderr)
            return 2
        existing = dict(elements(out.read_text(encoding="utf-8")))
        for name in found.keys() & existing.keys():
            problems.append(f"{name}: already translated in {out.name} (edit it there instead)")
        dropped = sorted(existing.keys() - known)
        merged = {**{n: el for n, el in existing.items() if n in known}, **found}
    else:
        dropped = []
        merged = found
    problems += [f"{n}: not in the English" for n in sorted(found.keys() - known)]
    problems += [f"{n}: missing" for n in source if n not in merged]
    if problems:
        print("\n".join(f"assemble: {p}" for p in problems))
        print(f"assemble: {len(problems)} problem(s), nothing written")
        return 1

    write_resources(out, [merged[n] for n in source])
    for n in dropped:
        print(f"assemble: removed {n} (the English no longer has it)")
    print(f"assemble: wrote {out.relative_to(Path.cwd()) if out.is_relative_to(Path.cwd()) else out}: "
          f"{len(source)} strings ({len(found)} from {len(chunks)} chunk(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
