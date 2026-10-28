"""Shared helpers for the machine-translation skill's scripts. Stdlib only.

Elements are handled as the raw text a translator wrote, never re-serialised through ElementTree, so an escape, an
entity, a no-break space or an attribute's order reaches the assembled file exactly as written.
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
RES = REPO / "app/src/main/res"
ENGLISH = RES / "values/strings.xml"
HEADER = '<?xml version="1.0" encoding="utf-8"?>\n<resources xmlns:tools="http://schemas.android.com/tools">\n'
FOOTER = "</resources>\n"

# One top-level unit as written: a comment, a <string> (self-closing or not) or a <plurals>. Comments come first
# in the alternation, so a `<string` quoted inside a comment is never read as an element.
UNIT = re.compile(r"(<!--.*?-->)|(<string(?=[\s>/])[^>]*?/>|<string(?=[\s>/]).*?</string>)|(<plurals\b.*?</plurals>)",
                  re.S)
NAME = re.compile(r'\bname="([^"]+)"')
ITEM = re.compile(r'<item\b[^>]*\bquantity="(\w+)"[^>]*>(.*?)</item>', re.S)
ATTR = re.compile(r'([\w:]+)="([^"]*)"')


def android_dir(locale: str) -> str:
    """`fr` -> values-fr, `pt-BR` -> values-pt-rBR, `es-419` or `sr-Latn` -> values-b+es+419."""
    parts = locale.replace("_", "-").split("-")
    lang = parts[0].lower()
    if len(parts) == 1:
        return f"values-{lang}"
    if len(parts) == 2 and re.fullmatch(r"[A-Za-z]{2}", parts[1]):
        return f"values-{lang}-r{parts[1].upper()}"
    return "values-" + "+".join(["b", lang, *parts[1:]])


def strings_file(locale: str) -> Path:
    return RES / android_dir(locale) / "strings.xml"


def body(text: str) -> str:
    """What sits inside <resources …>…</resources>; the whole text when it is a bare fragment."""
    m = re.search(r"<resources\b[^>]*>(.*)</resources>", text, re.S)
    return m.group(1) if m else text


def units(text: str) -> list[tuple[str | None, str | None]]:
    """Every (comment, element) unit in file order: exactly one of the pair is set."""
    return [(m.group(1), m.group(2) or m.group(3)) for m in UNIT.finditer(body(text))]


def elements(text: str) -> list[tuple[str, str]]:
    """Every <string> / <plurals> as (name, raw element text), in file order."""
    out = []
    for _, el in units(text):
        if el:
            m = NAME.search(el.split(">", 1)[0])
            out.append((m.group(1) if m else "?", el))
    return out


def translatable(el: str) -> bool:
    return 'translatable="false"' not in el.split(">", 1)[0]


def english() -> list[tuple[str, str]]:
    """The English file's translatable elements, in its order."""
    return [(n, el) for n, el in elements(ENGLISH.read_text(encoding="utf-8")) if translatable(el)]


def opening(el: str) -> dict[str, str]:
    """The element's attributes, read from its opening tag."""
    return dict(ATTR.findall(el.split(">", 1)[0]))


def texts(el: str) -> dict[str, str]:
    """The text of a <string> as {"": text}, or of each plural item as {quantity: text}, raw (escapes kept)."""
    if el.startswith("<plurals"):
        return {q: t for q, t in ITEM.findall(el)}
    m = re.match(r"<string\b[^>]*>(.*)</string>$", el, re.S)
    return {"": m.group(1) if m else ""}


def normalise(el: str) -> str:
    """The element indented as the repo's files are: 4 spaces, plural items 8."""
    if el.startswith("<plurals"):
        head = el.split(">", 1)[0] + ">"
        items = re.findall(r"<item\b.*?</item>", el, re.S)
        return "\n".join([f"    {head}", *(f"        {i}" for i in items), "    </plurals>"])
    return "    " + el.strip()


def write_resources(path: Path, els: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(HEADER + "".join(normalise(el) + "\n" for el in els) + FOOTER, encoding="utf-8")
    ET.parse(path)  # raises on a file aapt would reject outright
