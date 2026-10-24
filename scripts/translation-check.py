#!/usr/bin/env python3
"""Check each translation of the app's strings against the English (app/src/main/res/values-*/strings.xml).

WHY THIS EXISTS. A locale lands whole (.agents/context/translations.md): a machine pass over every string, then
native speakers' corrections through Weblate pull requests. Android Lint is the gate (`MissingTranslation`,
`MissingQuantity`, `StringFormat*`), but it runs behind a full Gradle build and says nothing about what a reader
notices first: a placeholder a translator moved into the wrong plural, an apostrophe aapt will choke on, a string
left in English, a two-word button that came back three times as long, a glossary term rendered two ways. This
reads the XML alone, in a second, before the build and before a reviewer.

WHAT IT DOES. Pairs every translatable `<string>` and `<plurals>` in `values/strings.xml` with its translation in
each `values-<locale>/strings.xml` (or the one `--locale` names) and reports

  error    a name missing from or extra to the translation, an element marked `translatable="false"` that was
           translated anyway, an empty translation, a `tools:ignore` the English carries and the translation lost,
           a placeholder set that differs (per string, per plural item), an unescaped apostrophe or a leading
           `@` / `?`, and plural quantities that differ from the language's CLDR set (what lint's MissingQuantity
           and UnusedQuantity want)
  warning  text identical to the English (once the glossary's keep-as-is terms are taken out), a plural `one` that
           drops the English `one`'s placeholder (Portuguese and French `one` covers 0 too), a short label grown
           past twice its English length, `...` for `…`, a different count of `\\n`, and a glossary term
           with none of its renderings (scripts/translation-glossary.tsv)

as `values-<locale>: <name>: <level>: <what>`. Any error exits 1; warnings alone exit 0. In CI it is advisory
(continue-on-error / allow_failure) while the first locales settle. Pure stdlib; tests:
`python3 -m unittest scripts/test_translation_check.py`.

Usage: scripts/translation-check.py [--locale pt-BR] [--errors-only] [--res DIR] [--glossary FILE]
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RES = REPO / "app/src/main/res"
GLOSSARY = Path(__file__).resolve().with_name("translation-glossary.tsv")

TOOLS_IGNORE = "{http://schemas.android.com/tools}ignore"

# java.util.Formatter conversions as Android strings use them: `%1$s`, `%d`, `%1$.1f`, `%%`. The space flag is left
# out on purpose: "100 % de" would read as `% d`.
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[a-zA-Z%]")

# The plural categories ICU gives each language, which lint checks a translation's `<plurals>` against. Since
# CLDR 38 the Romance languages carry `many` (it only covers numbers like a million). A language not listed is a
# warning to add it here, never a guess.
PLURALS: dict[str, frozenset[str]] = {
    **dict.fromkeys(["pt", "es", "fr", "it", "ca"], frozenset({"one", "many", "other"})),
    **dict.fromkeys(["en", "de", "nl", "sv", "da", "nb", "fi", "el", "hu", "tr", "fa", "hi", "bn", "ur", "sw"],
                    frozenset({"one", "other"})),
    **dict.fromkeys(["ru", "uk", "pl", "cs", "sk", "lt"], frozenset({"one", "few", "many", "other"})),
    "ar": frozenset({"zero", "one", "two", "few", "many", "other"}),
    "he": frozenset({"one", "two", "other"}),
    "ro": frozenset({"one", "few", "other"}),
    **dict.fromkeys(["ja", "zh", "ko", "vi", "id", "th", "ms"], frozenset({"other"})),
}

SHORT = 25  # characters: buttons, tabs, chips, switch titles
GROWTH = 2.0
SLACK = 8  # a short label may always grow by this many characters

LOCALE_DIR = re.compile(r"^values-(?:b\+([a-z]{2,3}(?:\+[A-Za-z0-9]+)*)|([a-z]{2,3})(?:-r([A-Z]{2}))?)$")


@dataclass(frozen=True)
class Entry:
    kind: str  # "string" or "plurals"
    text: str | dict[str, str]
    ignore: str | None
    translatable: bool


@dataclass(frozen=True)
class Finding:
    locale_dir: str
    name: str
    level: str  # "error" or "warning"
    message: str

    def __str__(self) -> str:
        return f"{self.locale_dir}: {self.name}: {self.level}: {self.message}"


@dataclass(frozen=True)
class Glossary:
    keep: tuple[str, ...]  # English terms every locale keeps as written
    terms: dict[str, dict[str, tuple[str, ...]]]  # English term -> locale -> accepted renderings (stems)


def load_entries(path: Path) -> dict[str, Entry]:
    out: dict[str, Entry] = {}
    for el in ET.parse(path).getroot():
        if not isinstance(el.tag, str) or el.tag not in ("string", "plurals"):
            continue
        name = el.get("name")
        translatable = el.get("translatable") != "false"
        if el.tag == "string":
            text: str | dict[str, str] = "".join(el.itertext())
        else:
            text = {item.get("quantity"): "".join(item.itertext()) for item in el if item.tag == "item"}
        out[name] = Entry(el.tag, text, el.get(TOOLS_IGNORE), translatable)
    return out


def load_glossary(path: Path) -> Glossary:
    if not path.exists():
        return Glossary((), {})
    keep: list[str] = []
    terms: dict[str, dict[str, tuple[str, ...]]] = {}
    with path.open(encoding="utf-8", newline="") as f:
        rows = [r for r in csv.reader(f, delimiter="\t") if r and not r[0].startswith("#")]
    header, body = rows[0], rows[1:]
    for row in body:
        english = row[0].strip()
        cells = {header[i]: row[i].strip() for i in range(1, min(len(row), len(header))) if row[i].strip()}
        if cells.get("keep") == "yes":
            keep.append(english)
            continue
        terms[english] = {loc: tuple(v.split("|")) for loc, v in cells.items() if loc not in ("keep", "note")}
    return Glossary(tuple(sorted(keep, key=len, reverse=True)), terms)


def locale_of(dir_name: str) -> str | None:
    """`values-pt-rBR` -> `pt-BR`, `values-b+sr+Latn` -> `sr-Latn`; None for a qualifier that is not a locale."""
    m = LOCALE_DIR.match(dir_name)
    if not m:
        return None
    if m.group(1):
        return m.group(1).replace("+", "-")
    return m.group(2) + (f"-{m.group(3)}" if m.group(3) else "")


def placeholders(text: str) -> list[str]:
    return sorted(PLACEHOLDER.findall(text))


def escapes(text: str) -> list[str]:
    bad = []
    if re.search(r"(?<!\\)'", text) and not (text.startswith('"') and text.endswith('"')):
        bad.append("unescaped apostrophe (write \\')")
    if re.match(r"^[@?]", text):
        bad.append("leading @ or ? (write \\@ or \\?)")
    return bad


def visible(text: str) -> str:
    return PLACEHOLDER.sub("", text).replace("\\'", "'").replace('\\"', '"').strip()


def without(text: str, terms: tuple[str, ...]) -> str:
    for term in terms:
        text = re.sub(re.escape(term), " ", text, flags=re.IGNORECASE)
    return text


def check_text(name: str, en: str, tr: str, glossary: Glossary, locale: str, report) -> None:
    """The checks a single piece of text (a `<string>` or one plural item) gets, beyond its placeholders."""
    for bad in escapes(tr):
        report(name, "error", bad)
    if "..." in tr and "..." not in en:
        report(name, "warning", "'...' where the English uses … (lint: TypographyEllipsis)")
    breaks_en, breaks_tr = en.count("\\n"), tr.count("\\n")
    if breaks_en != breaks_tr:
        report(name, "warning", f"{breaks_en} line break(s) in the English, {breaks_tr} here")
    en_len, tr_len = len(visible(en)), len(visible(tr))
    if 0 < en_len <= SHORT and tr_len > max(en_len * GROWTH, en_len + SLACK):
        report(name, "warning", f"short label grew from {en_len} to {tr_len} characters: check it fits")
    language = locale.split("-")[0]
    for term, renderings in glossary.terms.items():
        wanted = renderings.get(locale) or renderings.get(language)
        if not wanted or not re.search(rf"\b{re.escape(term)}", en, flags=re.IGNORECASE):
            continue
        if not any(w.lower() in tr.lower() for w in wanted):
            report(name, "warning", f"glossary: '{term}' is usually '{wanted[0]}' here")


def check_locale(source: dict[str, Entry], path: Path, glossary: Glossary) -> list[Finding]:
    locale_dir = path.parent.name
    locale = locale_of(locale_dir) or locale_dir
    language = locale.split("-")[0]
    findings: list[Finding] = []

    def report(name: str, level: str, message: str) -> None:
        findings.append(Finding(locale_dir, name, level, message))

    try:
        target = load_entries(path)
    except ET.ParseError as e:
        return [Finding(locale_dir, "-", "error", f"does not parse: {e}")]

    quantities = PLURALS.get(language)
    if quantities is None:
        report("-", "warning", f"no plural categories known for '{language}': add them to PLURALS")

    for name, en in source.items():
        tr = target.get(name)
        if not en.translatable:
            if tr is not None:
                report(name, "error", 'translated, but the English marks it translatable="false"')
            continue
        if tr is None:
            report(name, "error", "missing (lint: MissingTranslation)")
            continue
        if tr.kind != en.kind:
            report(name, "error", f"a <{tr.kind}> here, a <{en.kind}> in the English")
            continue
        if en.ignore and tr.ignore != en.ignore:
            report(name, "error", f'lost tools:ignore="{en.ignore}"')
        if en.kind == "string":
            check_string(name, en.text, tr.text, glossary, locale, report)
        else:
            check_plurals(name, en.text, tr.text, quantities, glossary, locale, report)

    for name in target.keys() - source.keys():
        report(name, "error", "not in the English (lint: ExtraTranslation)")
    return sorted(findings, key=lambda f: (f.level != "error", f.name))


def check_string(name: str, en: str, tr: str, glossary: Glossary, locale: str, report) -> None:
    if not tr.strip():
        report(name, "error", "empty")
        return
    if placeholders(en) != placeholders(tr):
        report(name, "error", f"placeholders {placeholders(en)} in the English, {placeholders(tr)} here")
    if en == tr and re.search(r"[a-z]{3}", without(en, glossary.keep)):
        report(name, "warning", "same as the English")
    check_text(name, en, tr, glossary, locale, report)


def check_plurals(name: str, en: dict, tr: dict, quantities: frozenset[str] | None, glossary: Glossary, locale: str,
                  report) -> None:
    if quantities is not None and set(tr) != quantities:
        report(name, "error", f"quantities {sorted(tr)}, the language wants {sorted(quantities)}")
    allowed = {p for text in en.values() for p in placeholders(text)}
    for quantity, text in tr.items():
        key = f"{name}[{quantity}]"
        if not text.strip():
            report(key, "error", "empty")
            continue
        found = placeholders(text)
        if len(found) != len(set(found)) or not set(found) <= allowed:
            report(key, "error", f"placeholders {found}, the English has {sorted(allowed)}")
        check_text(key, en.get(quantity, en.get("other", "")), text, glossary, locale, report)
    if "other" in tr and placeholders(en.get("other", "")) != placeholders(tr["other"]):
        report(f"{name}[other]", "error", f"placeholders {placeholders(tr['other'])}, the English other has "
                                          f"{placeholders(en.get('other', ''))}")
    if "one" in tr and placeholders(en.get("one", "")) and not placeholders(tr["one"]):
        report(f"{name}[one]", "warning", "drops the count the English one shows (one may cover 0 too)")


def translation_files(res: Path, locale: str | None) -> list[Path]:
    files = sorted(p for p in res.glob("values-*/strings.xml") if locale_of(p.parent.name))
    if locale:
        files = [p for p in files if locale_of(p.parent.name) == locale]
    return files


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--locale", help="check one locale, like pt-BR (default: every values-<locale>/)")
    parser.add_argument("--errors-only", action="store_true", help="print errors, not warnings")
    parser.add_argument("--res", type=Path, default=RES)
    parser.add_argument("--glossary", type=Path, default=GLOSSARY)
    args = parser.parse_args(argv)

    source = load_entries(args.res / "values/strings.xml")
    glossary = load_glossary(args.glossary)
    files = translation_files(args.res, args.locale)
    if args.locale and not files:
        print(f"translation-check: no values-*/strings.xml for {args.locale}", file=sys.stderr)
        return 2

    errors = warnings = 0
    for path in files:
        findings = check_locale(source, path, glossary)
        errors += sum(f.level == "error" for f in findings)
        warnings += sum(f.level == "warning" for f in findings)
        for f in findings:
            if f.level == "error" or not args.errors_only:
                print(f)
    translatable = sum(e.translatable for e in source.values())
    print(f"translation-check: {len(files)} locale(s), {translatable} strings each, {errors} error(s), "
          f"{warnings} warning(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
