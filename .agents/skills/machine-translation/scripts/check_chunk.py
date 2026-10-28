#!/usr/bin/env python3
"""Check one translated chunk against its English chunk, before it is assembled.

Runs the repo's own checker (scripts/translation-check.py: placeholders, plural categories, escapes, glossary, length)
on the pair through a throwaway resource directory, so a chunk is held to exactly what CI holds the whole file to,
and adds what that checker cannot see in a fragment or does not hold every locale to:

  error    the XML does not parse; a name missing, extra, repeated or out of the English order; an attribute that
           differs from the English element's; `\\'` (the house apostrophe is the typographic ’ in every language,
           and aapt's pseudolocalizer has blanked a string ending in an escaped one); an unescaped `"`, which aapt
           strips; a "(s)"-style form inside a <plurals> item, where each item says its form outright
  warning  a plural `many` that differs from `other` in a language where it only covers numbers like a million

Prints the findings and `OK (N strings, W warnings)` or `N error(s)`; exits 1 on any error.

Usage: check_chunk.py EN_CHUNK TRANSLATED_CHUNK --locale LOCALE
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import FOOTER, HEADER, REPO, android_dir, body, elements, opening, texts  # noqa: E402

# Languages whose CLDR `many` only covers numbers like a million: it carries the same words as `other`.
MANY_IS_OTHER = {"pt", "es", "fr", "it", "ca"}
SLASH_FORMS = re.compile(r"\w\((?:s|e|es|x|a|as|os|n|en)\)|\w·\w")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("english", type=Path)
    parser.add_argument("translated", type=Path)
    parser.add_argument("--locale", required=True, help="the locale as Android names it, like fr, pt-BR or es-419")
    args = parser.parse_args(argv)

    errors: list[str] = []
    warnings: list[str] = []
    raw = args.translated.read_text(encoding="utf-8")
    try:
        ET.fromstring(HEADER.split("\n", 1)[1] + body(raw) + FOOTER)
    except ET.ParseError as e:
        print(f"error: {args.translated.name} does not parse: {e}")
        print("1 error(s)")
        return 1

    en = elements(args.english.read_text(encoding="utf-8"))
    tr = elements(raw)
    en_names, tr_names = [n for n, _ in en], [n for n, _ in tr]
    missing = [n for n in en_names if n not in tr_names]
    extra = [n for n in tr_names if n not in en_names]
    repeated = sorted({n for n in tr_names if tr_names.count(n) > 1})
    for n in missing:
        errors.append(f"{n}: missing")
    for n in extra:
        errors.append(f"{n}: not in the English chunk")
    for n in repeated:
        errors.append(f"{n}: written more than once")
    if not (missing or extra or repeated) and en_names != tr_names:
        first = next(i for i, (a, b) in enumerate(zip(en_names, tr_names)) if a != b)
        errors.append(f"{tr_names[first]}: out of order (the English has {en_names[first]} here)")

    language = args.locale.split("-")[0].lower()
    en_by = dict(en)
    for name, el in tr:
        source = en_by.get(name)
        if source is None:
            continue
        if opening(el) != opening(source):
            errors.append(f"{name}: attributes {opening(el)}, the English has {opening(source)}")
        items = texts(el)
        for quantity, text in items.items():
            key = f"{name}[{quantity}]" if quantity else name
            if "\\'" in text:
                errors.append(f"{key}: escaped apostrophe \\' (write the typographic ’)")
            if re.search(r'(?<!\\)"', text):
                errors.append(f'{key}: unescaped " (aapt strips it: write the language\'s quotation marks or \\")')
            if quantity and SLASH_FORMS.search(text):
                errors.append(f"{key}: a \"(s)\"-style form (write this quantity's form outright)")
        if language in MANY_IS_OTHER and "many" in items and items["many"] != items.get("other"):
            warnings.append(f"{name}[many]: differs from other (it should carry the same words)")

    # The repo's checker, on the pair alone.
    with tempfile.TemporaryDirectory() as tmp:
        res = Path(tmp)
        for folder, els in (("values", en), (android_dir(args.locale), tr)):
            (res / folder).mkdir()
            (res / folder / "strings.xml").write_text(HEADER + "".join(el + "\n" for _, el in els) + FOOTER,
                                                      encoding="utf-8")
        run = subprocess.run([sys.executable, str(REPO / "scripts/translation-check.py"), "--res", str(res),
                              "--locale", args.locale], capture_output=True, text=True)
    for line in run.stdout.splitlines():
        m = re.match(r"\S+: (\S+): (error|warning): (.*)", line)
        if m and not (m.group(2) == "error" and m.group(3).startswith("missing")):
            (errors if m.group(2) == "error" else warnings).append(f"{m.group(1)}: {m.group(3)}")
    if run.returncode not in (0, 1):
        errors.append(f"translation-check failed to run: {run.stderr.strip()[:300]}")

    for line in errors:
        print(f"error: {line}")
    for line in warnings:
        print(f"warning: {line}")
    if errors:
        print(f"{len(errors)} error(s), {len(warnings)} warning(s)")
        return 1
    print(f"OK ({len(tr)} strings, {len(warnings)} warning(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
