#!/usr/bin/env python3
"""Generate app/src/main/assets/emoji/emoji_en.tsv -- the reaction picker's emoji catalog -- from the
vendored Unicode `emoji-test.txt`, the vendored CLDR English emoji annotations and emojilib (search keywords).

The output is a committed, GENERATED file. Regenerate it with:

    python3 scripts/gen-emoji-catalog.py            # rewrite the asset from the vendored source
    python3 scripts/gen-emoji-catalog.py --check    # exit 1 if the committed asset is stale (CI)
    python3 scripts/gen-emoji-catalog.py --update 18.0   # re-vendor a newer Unicode emoji version, then regenerate
    python3 scripts/gen-emoji-catalog.py --update-cldr release-49   # re-vendor CLDR's annotations, then regenerate
    python3 scripts/gen-emoji-catalog.py --update-emojilib 4.0.4    # re-vendor emojilib from npm, then regenerate

Source: third_party/unicode-emoji/emoji-test.txt (Unicode License v3, (c) Unicode, Inc.), pinned at the
version/date recorded in the file's own header and in third_party/unicode-emoji/PROVENANCE.md; keywords from
third_party/unicode-cldr/annotations-en.xml + annotationsDerived-en.xml (Unicode License v3), pinned at the
CLDR release tag in third_party/unicode-cldr/RELEASE and third_party/unicode-cldr/PROVENANCE.md; more keywords
from third_party/emojilib/emoji-en-US.json (MIT, (c) Mu-An Chiou), pinned at the npm version in its vendored
package.json and in third_party/emojilib/PROVENANCE.md. This is a
developer tool run on a workstation; it never runs in the app, so the app's no-INTERNET-permission design is
unaffected. Nothing here is hard-coded to a Unicode version: the version, date and sha256 stamped into the
asset header are read off the vendored file at run time, so a bump touches no constants.

Transform (one emoji-test line -> zero or one catalog line):
  * `# group:` headers select the current group, mapped through GROUPS (Unicode's own order) to the small
    integer the app's `EmojiGroup` enum mirrors ordinal-for-ordinal. The `Component` group (bare skin-tone
    swatches, hair components) is skipped. Any OTHER unknown group is a hard error, so a future Unicode
    group fails loudly here and forces the Kotlin enum to grow in step -- never a silent drop;
  * keep only `fully-qualified` lines. `minimally-qualified` and `unqualified` forms are the same emoji
    minus a VS16 the keyboard should add, and `component` entries are not emoji on their own -- the picker
    must only ever emit the one canonical form, or two users' identical reactions tally as two chips;
  * the emoji string is rebuilt from the code-point column and asserted equal to the glyph column;
  * an entry carrying a skin-tone modifier (U+1F3FB..U+1F3FF) is flagged `tone = 1`: the browse grid hides
    those ~1,900 variants under their base, while search still reaches them by name;
  * a base (non-tone) entry carries its CLDR keywords: the `annotations` file first, then `annotationsDerived`
    (ZWJ sequences, keycaps, flags), looked up by the emoji and then by the emoji minus U+FE0F (CLDR strips
    VS16 from its keys). Keywords are lower-cased, de-duplicated, and a keyword equal to the name is dropped.
    A base entry CLDR has no keywords for is a hard error -- re-vendor CLDR in step with a Unicode bump.
    emojilib's keywords follow CLDR's, looked up the same way (its keys keep or drop VS16 as they please, so
    both sides are compared with U+FE0F stripped), with `_` read as a space ("hundred_points" is the name and
    drops out). emojilib is community data that lags Unicode, so an entry it lacks just has CLDR's. Tone
    variants carry none: their derived keywords are the base's plus the tone, so carrying them would put six
    or twenty-six hits on every keyword query; the name ("thumbs up: medium skin tone") still reaches them;
  * file order is preserved (Unicode's curated order is what every picker shows); the emoji version column
    is NOT emitted -- nothing consumes it, `Paint.hasGlyph` is the real capability check on the device.

Output line format (tab-separated; tabs never occur in an emoji or a CLDR name, colons and commas do):

    <emoji>\\t<group 0-8>\\t<tone 0|1>\\t<CLDR short name>[\\t<keyword>|<keyword>|...]

(the keyword column is omitted, not left empty, when an entry has none -- no trailing tab to strip).
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import sys
import tarfile
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter
from collections.abc import Iterable
from pathlib import Path
from typing import NamedTuple

REPO_ROOT = Path(__file__).resolve().parent.parent
VENDOR_DIR = REPO_ROOT / "third_party" / "unicode-emoji"
SOURCE_TXT = VENDOR_DIR / "emoji-test.txt"
SOURCE_LICENSE = VENDOR_DIR / "LICENSE"
CLDR_DIR = REPO_ROOT / "third_party" / "unicode-cldr"
CLDR_RELEASE = CLDR_DIR / "RELEASE"
# Local name -> path under CLDR's `common/`. Order matters: the hand-curated file wins over the derived one.
CLDR_FILES = {"annotations-en.xml": "annotations/en.xml", "annotationsDerived-en.xml": "annotationsDerived/en.xml"}
CLDR_URL_TEMPLATE = "https://raw.githubusercontent.com/unicode-org/cldr/{release}/{path}"
EMOJILIB_DIR = REPO_ROOT / "third_party" / "emojilib"
EMOJILIB_JSON = EMOJILIB_DIR / "emoji-en-US.json"
EMOJILIB_PACKAGE = EMOJILIB_DIR / "package.json"
EMOJILIB_TARBALL = "https://registry.npmjs.org/emojilib/-/emojilib-{version}.tgz"
# Tarball member -> vendored file name.
EMOJILIB_FILES = {"package/dist/emoji-en-US.json": "emoji-en-US.json", "package/LICENSE": "LICENSE", "package/package.json": "package.json"}
OUTPUT_TSV = REPO_ROOT / "app" / "src" / "main" / "assets" / "emoji" / "emoji_en.tsv"

# From Emoji 17.0 the data files live under the UCD version directory (the old /Public/emoji/<ver>/ path 404s).
SOURCE_URL_TEMPLATE = "https://www.unicode.org/Public/{version}.0/emoji/emoji-test.txt"
LICENSE_URL = "https://www.unicode.org/license.txt"

# Unicode's own group order. Index == app.getknit.knit.data.emoji.EmojiGroup ordinal -- grow both together.
GROUPS = [
    "Smileys & Emotion",
    "People & Body",
    "Animals & Nature",
    "Food & Drink",
    "Travel & Places",
    "Activities",
    "Objects",
    "Symbols",
    "Flags",
]
SKIPPED_GROUPS = {"Component"}
SKIN_TONES = range(0x1F3FB, 0x1F3FF + 1)

# `1F44D 1F3FB ; fully-qualified # 👍🏻 E1.0 thumbs up: light skin tone`
LINE = re.compile(r"^([0-9A-F]{4,6}(?: [0-9A-F]{4,6})*)\s*;\s*([a-z-]+)\s*#\s*(\S+)\s+E(\d+\.\d+)\s+(.+?)\s*$")
VERSION = re.compile(r"^# Version:\s*(\S+)")
DATE = re.compile(r"^# Date:\s*(\d{4}-\d{2}-\d{2})")


class Entry(NamedTuple):
    emoji: str
    group: int
    tone: int
    name: str
    keywords: tuple[str, ...] = ()


class Cldr(NamedTuple):
    release: str
    texts: tuple[str, ...]  # in CLDR_FILES order

    @property
    def sha256(self) -> list[str]:
        return [hashlib.sha256(t.encode("utf-8")).hexdigest() for t in self.texts]


class Emojilib(NamedTuple):
    version: str
    text: str

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.text.encode("utf-8")).hexdigest()


class Source(NamedTuple):
    version: str
    date: str
    sha256: str


def parse(lines: Iterable[str]) -> tuple[list[Entry], Counter]:
    """Parse emoji-test lines into catalog entries (see the module docstring for the rules)."""
    group: int | None = None
    skipping = False
    entries: list[Entry] = []
    stats: Counter = Counter()
    for raw in lines:
        line = raw.rstrip("\r\n")
        if line.startswith("# group:"):
            name = line[len("# group:"):].strip()
            if name in SKIPPED_GROUPS:
                skipping, group = True, None
            elif name in GROUPS:
                skipping, group = False, GROUPS.index(name)
            else:
                raise ValueError(f"unknown emoji group {name!r}: add it to GROUPS here and to EmojiGroup in the app")
            continue
        if not line or line.startswith("#"):
            continue
        m = LINE.match(line)
        if not m:
            raise ValueError(f"unparseable emoji-test line: {line!r}")
        cps, status, glyph, _version, name = m.groups()
        stats[status] += 1
        if status != "fully-qualified":
            continue
        if skipping or group is None:
            stats["skipped-group"] += 1
            continue
        emoji = "".join(chr(int(cp, 16)) for cp in cps.split())
        if emoji != glyph:
            raise ValueError(f"code points {cps} do not rebuild the glyph column for {name!r}")
        tone = int(any(ord(c) in SKIN_TONES for c in emoji))
        entries.append(Entry(emoji, group, tone, name))
    return entries, stats


def cldr_keywords(texts: Iterable[str]) -> dict[str, list[str]]:
    """CLDR `<annotation cp=...>a | b | c</annotation>` lines (not the `type="tts"` names) -> cp -> keywords.
    The first file to name a cp wins, so `annotations` overrides `annotationsDerived`."""
    out: dict[str, list[str]] = {}
    for text in texts:
        for a in ET.fromstring(text.encode("utf-8")).iter("annotation"):
            cp = a.get("cp")
            if a.get("type") == "tts" or not cp or not a.text:
                continue
            out.setdefault(cp, [k.strip() for k in a.text.split("|") if k.strip()])
    return out


def emojilib_keywords(text: str) -> dict[str, list[str]]:
    """emojilib's `{"💯": ["hundred_points", "score", ...]}` -> VS16-stripped emoji -> keywords, `_` read as a space."""
    out: dict[str, list[str]] = {}
    for emoji, words in json.loads(text).items():
        out.setdefault(emoji.replace("\ufe0f", ""), [w.replace("_", " ") for w in words if isinstance(w, str)])
    return out


def attach_keywords(
    entries: list[Entry],
    keywords: dict[str, list[str]],
    extra: dict[str, list[str]] | None = None,
) -> list[Entry]:
    """Give every base entry its CLDR keywords, then emojilib's [extra] (see the module docstring); tone
    variants get none."""
    out: list[Entry] = []
    for e in entries:
        if e.tone:
            out.append(e)
            continue
        raw = keywords.get(e.emoji) or keywords.get(e.emoji.replace("\ufe0f", ""))
        if raw is None:
            raise ValueError(f"CLDR has no keywords for {e.emoji} {e.name!r}: re-vendor CLDR (--update-cldr) in step with Unicode")
        raw = raw + (extra or {}).get(e.emoji.replace("\ufe0f", ""), [])
        name = e.name.lower()
        kept: list[str] = []
        for k in (k.strip().lower() for k in raw):
            if k and k != name and k not in kept and not any(c in k for c in "\t\n\r|\x1f"):
                kept.append(k)
        out.append(e._replace(keywords=tuple(kept)))
    return out


def read_cldr() -> Cldr:
    missing = [n for n in CLDR_FILES if not (CLDR_DIR / n).exists()] + ([] if CLDR_RELEASE.exists() else ["RELEASE"])
    if missing:
        raise FileNotFoundError(f"missing {', '.join(missing)} under {CLDR_DIR} (see its PROVENANCE.md)")
    texts = tuple((CLDR_DIR / n).read_text(encoding="utf-8") for n in CLDR_FILES)
    return Cldr(CLDR_RELEASE.read_text(encoding="utf-8").strip(), texts)


def read_emojilib() -> Emojilib:
    missing = [p.name for p in (EMOJILIB_JSON, EMOJILIB_PACKAGE) if not p.exists()]
    if missing:
        raise FileNotFoundError(f"missing {', '.join(missing)} under {EMOJILIB_DIR} (see its PROVENANCE.md)")
    version = json.loads(EMOJILIB_PACKAGE.read_text(encoding="utf-8"))["version"]
    return Emojilib(version, EMOJILIB_JSON.read_text(encoding="utf-8"))


def source_info(text: str) -> Source:
    version = next((m.group(1) for m in map(VERSION.match, text.splitlines()) if m), None)
    date = next((m.group(1) for m in map(DATE.match, text.splitlines()) if m), None)
    if not version or not date:
        raise ValueError("emoji-test.txt has no '# Version:' / '# Date:' header")
    return Source(version, date, hashlib.sha256(text.encode("utf-8")).hexdigest())


def header(src: Source, cldr: Cldr, emojilib: Emojilib) -> str:
    groups = ", ".join(f"{i} {g}" for i, g in enumerate(GROUPS))
    return f"""\
# Emoji catalog for the reaction picker -- one fully-qualified RGI emoji per line, in Unicode's own order.
# Columns (tab-separated): emoji, group id, skin-tone variant (0|1), CLDR short name (English), and -- on base
# entries only -- the CLDR search keywords, '|'-separated (the column is absent when there are none).
# Group ids: {groups}
# (mirrors app.getknit.knit.data.emoji.EmojiGroup ordinal-for-ordinal). Blank lines and '#' lines with no tab are ignored
# (the keycap #️⃣ data row starts with '#' too).
#
# GENERATED FILE -- DO NOT EDIT BY HAND. Regenerate with:  python3 scripts/gen-emoji-catalog.py
#
# Source:  Unicode emoji-test.txt -- {SOURCE_URL_TEMPLATE.format(version=src.version)}
# Version: Emoji {src.version} ({src.date})  sha256 {src.sha256}
# Keywords: CLDR {cldr.release} annotations/en.xml sha256 {cldr.sha256[0]}
#           CLDR {cldr.release} annotationsDerived/en.xml sha256 {cldr.sha256[1]}
#           emojilib {emojilib.version} dist/emoji-en-US.json sha256 {emojilib.sha256} (MIT (c) Mu-An Chiou)
# License: Unicode License v3 (c) Unicode, Inc. -- full text: app/src/main/assets/emoji/README.md
#
# Transform: keep only `fully-qualified` lines (drops minimally-/unqualified forms and `component`
# swatches); skip the Component group; flag entries carrying a skin-tone modifier (U+1F3FB..U+1F3FF);
# give base entries their lower-cased CLDR then emojilib keywords (tone variants none); preserve file order.
# Full detail: scripts/gen-emoji-catalog.py.
"""


def line(e: Entry) -> str:
    base = f"{e.emoji}\t{e.group}\t{e.tone}\t{e.name}"
    return base + "\t" + "|".join(e.keywords) if e.keywords else base


def render(text: str, cldr: Cldr, emojilib: Emojilib) -> tuple[str, list[Entry], Counter]:
    """The app reads a '#' line with a tab as data (the keycap #️⃣ row), so the header must never hold one."""
    entries, stats = parse(text.splitlines())
    entries = attach_keywords(entries, cldr_keywords(cldr.texts), emojilib_keywords(emojilib.text))
    body = "\n".join(line(e) for e in entries)
    if "\t" in header(source_info(text), cldr, emojilib):
        raise ValueError("a tab in the asset header would read as a data row")
    return header(source_info(text), cldr, emojilib) + "\n" + body + "\n", entries, stats


def report(entries: list[Entry], stats: Counter) -> None:
    tones = sum(e.tone for e in entries)
    per_group = Counter(GROUPS[e.group] for e in entries)
    print(f"wrote {len(entries)} emoji -> {OUTPUT_TSV.relative_to(REPO_ROOT)}", file=sys.stderr)
    print(
        "  statuses: " + "  ".join(f"{k}={v}" for k, v in sorted(stats.items())) + f"  |  tone variants kept: {tones}",
        file=sys.stderr,
    )
    print(f"  keywords: {sum(len(e.keywords) for e in entries)} on {sum(1 for e in entries if e.keywords)} base entries", file=sys.stderr)
    print("  per group: " + "  ".join(f"{g}={per_group[g]}" for g in GROUPS), file=sys.stderr)


def update(version: str) -> None:
    VENDOR_DIR.mkdir(parents=True, exist_ok=True)
    for url, dest in ((SOURCE_URL_TEMPLATE.format(version=version), SOURCE_TXT), (LICENSE_URL, SOURCE_LICENSE)):
        print(f"fetching {url}", file=sys.stderr)
        with urllib.request.urlopen(url) as resp:
            dest.write_bytes(resp.read())
    src = source_info(SOURCE_TXT.read_text(encoding="utf-8"))
    print(
        f"vendored Emoji {src.version} ({src.date}) sha256 {src.sha256}\n"
        f"  now update third_party/unicode-emoji/PROVENANCE.md (version, date, sha256, retrieved) and the\n"
        f"  version line in app/src/main/assets/emoji/README.md + THIRD-PARTY-NOTICES.md",
        file=sys.stderr,
    )


def update_cldr(release: str) -> None:
    CLDR_DIR.mkdir(parents=True, exist_ok=True)
    fetches = [(CLDR_URL_TEMPLATE.format(release=release, path=f"common/{p}"), CLDR_DIR / n) for n, p in CLDR_FILES.items()]
    fetches.append((CLDR_URL_TEMPLATE.format(release=release, path="LICENSE"), CLDR_DIR / "LICENSE"))
    for url, dest in fetches:
        print(f"fetching {url}", file=sys.stderr)
        with urllib.request.urlopen(url) as resp:
            dest.write_bytes(resp.read())
    CLDR_RELEASE.write_text(release + "\n", encoding="utf-8")
    print(f"vendored CLDR {release}: now update third_party/unicode-cldr/PROVENANCE.md (release, sha256, retrieved)", file=sys.stderr)


def update_emojilib(version: str) -> None:
    url = EMOJILIB_TARBALL.format(version=version)
    print(f"fetching {url}", file=sys.stderr)
    with urllib.request.urlopen(url) as resp:
        data = resp.read()
    EMOJILIB_DIR.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        for member, name in EMOJILIB_FILES.items():
            f = tar.extractfile(member)  # read by name only: nothing in the archive picks a path on disk
            if f is None:
                raise ValueError(f"{url} has no {member}")
            (EMOJILIB_DIR / name).write_bytes(f.read())
    print(f"vendored emojilib {version}: now update third_party/emojilib/PROVENANCE.md (version, sha256, retrieved)", file=sys.stderr)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--check", action="store_true", help="verify the committed asset matches the vendored source; exit 1 on drift")
    ap.add_argument("--update", metavar="VERSION", help="download emoji-test.txt for this Unicode emoji version (e.g. 18.0) and regenerate")
    ap.add_argument("--update-cldr", metavar="RELEASE", help="download CLDR's English annotations at this release tag (e.g. release-49) and regenerate")
    ap.add_argument("--update-emojilib", metavar="VERSION", help="download emojilib at this npm version (e.g. 4.0.4) and regenerate")
    args = ap.parse_args()
    if args.update:
        update(args.update)
    if args.update_cldr:
        update_cldr(args.update_cldr)
    if args.update_emojilib:
        update_emojilib(args.update_emojilib)
    if not SOURCE_TXT.exists():
        print(f"error: missing {SOURCE_TXT} (see third_party/unicode-emoji/PROVENANCE.md)", file=sys.stderr)
        return 1
    try:
        cldr = read_cldr()
        emojilib = read_emojilib()
    except FileNotFoundError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    rendered, entries, stats = render(SOURCE_TXT.read_text(encoding="utf-8"), cldr, emojilib)
    if args.check:
        current = OUTPUT_TSV.read_text(encoding="utf-8") if OUTPUT_TSV.exists() else ""
        if current != rendered:
            print(f"error: {OUTPUT_TSV.relative_to(REPO_ROOT)} is stale -- run: python3 scripts/gen-emoji-catalog.py", file=sys.stderr)
            return 1
        print(f"ok: {OUTPUT_TSV.relative_to(REPO_ROOT)} matches the vendored source ({len(entries)} emoji)", file=sys.stderr)
        return 0
    OUTPUT_TSV.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT_TSV.open("w", encoding="utf-8", newline="\n") as f:
        f.write(rendered)
    report(entries, stats)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
