#!/usr/bin/env python3
"""Print a locale's strings one per line, to read the whole translation or sweep it for patterns.

Each line is `name|text` (with --en, `name|English|translation`). A plural reads `one: … ‖ other: …`, leaving out an
item whose words repeat `other` (Romance `many`); `\\n` shows as ⏎. A no-break space prints as a plain space unless
--nbsp shows it as ⍽.

--scan runs the sweeps every language gets (an apostrophe or `\\'`, `...`, a double space, a "(s)"-style form, an
unescaped `"`, a space at either end, a space before `,` or `.`); --grep LABEL=REGEX adds one of the language's own
(register, foreign letters, off-glossary words), and prints only what the sweeps hit.

Usage: flat.py LOCALE [--en] [--nbsp] [--names a,b,…] [--scan] [--grep LABEL=REGEX …]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import ENGLISH, elements, strings_file, texts  # noqa: E402

SCAN = {
    "apostrophe ' or \\'": r"'",
    "three dots": r"\.\.\.",
    "double space": r"  ",
    '"(s)"-style form': r"\w\((?:s|e|es|x|a|as|os|n|en)\)|\w·\w",
    'unescaped "': r'(?<!\\)"',
    "space at either end": r"^[  ]|[  ]$",
    "space before , or .": r" [,.](?!\d)",
}


def flatten(el: str, nbsp: bool) -> str:
    items = texts(el)
    if "" in items:
        shown = items[""]
    else:
        other = items.get("other")
        shown = " ‖ ".join(f"{q}: {t}" for q, t in items.items() if q == "other" or t != other)
    shown = shown.replace("\\n", "⏎").replace("\n", " ")
    return shown.replace(" ", "⍽" if nbsp else " ")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("locale")
    parser.add_argument("--en", action="store_true", help="print the English beside each string")
    parser.add_argument("--nbsp", action="store_true", help="show a no-break space as ⍽")
    parser.add_argument("--names", help="only these names (comma-separated)")
    parser.add_argument("--scan", action="store_true", help="run the sweeps every language gets")
    parser.add_argument("--grep", action="append", default=[], metavar="LABEL=REGEX", help="a sweep of your own")
    args = parser.parse_args(argv)

    path = strings_file(args.locale)
    if not path.exists():
        print(f"flat: {path} does not exist", file=sys.stderr)
        return 2
    rows = elements(path.read_text(encoding="utf-8"))
    if args.names:
        wanted = set(args.names.split(","))
        rows = [(n, el) for n, el in rows if n in wanted]
    english = dict(elements(ENGLISH.read_text(encoding="utf-8")))

    sweeps = dict(SCAN) if args.scan else {}
    for spec in args.grep:
        label, _, pattern = spec.partition("=")
        sweeps[label] = pattern
    if sweeps:
        for label, pattern in sweeps.items():
            rx = re.compile(pattern)
            hits = [(n, el) for n, el in rows if any(rx.search(t) for t in texts(el).values())]
            print(f"== {label}: {len(hits)}")
            for n, el in hits[:15]:
                print(f"   {n}|{flatten(el, True)[:200]}")
        return 0

    for n, el in rows:
        line = flatten(el, args.nbsp)
        if args.en and n in english:
            line = f"{flatten(english[n], False)}|{line}"
        print(f"{n}|{line}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
