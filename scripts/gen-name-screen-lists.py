#!/usr/bin/env python3
"""Generate the three word lists the name screen reads (ADR 2026-11.y4pc), from the shipped profanity and slur
lists plus English dictionaries and name data:

  * name_embeddable_en.txt -- list terms safe to match INSIDE a longer word: four letters or more, and no
    dictionary word or real name contains them. "pikey" qualifies; "dick" does not (Dickson, Benedick).
  * name_exclusions_en.txt -- longer benign words that hand-labelling usernames found holding an embeddable term
    ("spikey" holds "pikey"). The screen cuts a token at each one before it looks inside it.
  * name_allowed_en.txt    -- profanity terms that are also real given names or surnames (Dick, Coon, Shota),
    which pass as whole words in a name. A term on the slur list is never allowed.

The outputs are committed, GENERATED files. Regenerate them after scripts/gen-profanity-list.py, with:

    python3 scripts/gen-name-screen-lists.py --corpus <dir>

<dir> holds the name data, which is not vendored (about 60 MB). Every input is pinned by SHA-256 below and
recorded in each output's header; a different file is refused rather than silently changing the lists:

    <dir>/ssa/babynames.csv                     US Social Security national baby names, 1880-2017, via the
                                                TidyTuesday mirror (ssa.gov refuses scripted downloads):
                                                https://raw.githubusercontent.com/rfordatascience/tidytuesday/master/data/2022/2022-03-22/babynames.csv
    <dir>/census/Names_2010Census.csv           US Census 2010 surnames (inside names.zip):
                                                https://www2.census.gov/topics/genealogy/2010surnames/names.zip
    <dir>/intl/common-forenames-by-country.csv  https://github.com/sigpwned/popular-names-by-country-dataset
    <dir>/intl/common-surnames-by-country.csv   (same repository)

The dictionaries are SCOWL's American and British lists as Debian packages them (wamerican, wbritish
2020.12.07-2, /usr/share/dict/). This is a developer tool run on a workstation; it never runs in the app.
Nothing from the dictionaries or the name data is shipped: each output is a subset of the shipped word lists,
plus the hand-picked exclusions. After regenerating, NameListsTest checks the lists' contract, and
`KNIT_NAME_CORPUS=<dir> ./gradlew :app:testDebugUnitTest --tests '*NameScreenCorpusTest' -i` re-measures how many real
names the shipped screen hides (ADR 2026-11.y4pc).
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import sys
import unicodedata
from pathlib import Path

# --- dials (edit + regenerate to retune) ---
EMBED_MIN_LEN = 4  # a three-letter term inside a word is noise ("ass" in "Cassandra")
REAL_NAME_FLOOR = 100  # births since 1950 (SSA) or people (Census) for a name to count as real
SSA_SINCE = 1950  # people likely alive and naming themselves now
# Benign words that hold an embeddable term, found by hand-labelling a username sample (ADR 2026-11.y4pc).
EXCLUDE_CONTAINERS = [
    "spikey",  # pikey
    "darkeye",  # darkey: "dark eyes"
    "stranny",  # tranny: Russian "strange"
    "dildora",  # dildo: an Uzbek given name
    "phonky",  # honky: phonk, a music genre
]
# Endings that make a word a form of the term it holds ("fucked", "bitchier", "bullshitted" with its doubled letter),
# not benign evidence against it.
INFLECTIONS = ("s", "es", "ed", "er", "ers", "ing", "in", "ier", "iest", "ies", "iness")  # not "y": spunky, cocky

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS = REPO_ROOT / "app" / "src" / "main" / "assets" / "moderation"
PROFANITY_TXT = ASSETS / "profanity_en.txt"
SLURS_TXT = ASSETS / "slurs_en.txt"
EMBEDDABLE_TXT = ASSETS / "name_embeddable_en.txt"
EXCLUSIONS_TXT = ASSETS / "name_exclusions_en.txt"
ALLOWED_TXT = ASSETS / "name_allowed_en.txt"

DICTIONARIES = {
    "american-english": "9f513f1ceadb6a01c5485b7dbdfd5118dc66cd70b59cae2851292112d4066a32",
    "british-english": "7424d6682301dc86f73b0a5c8c53f0ba4c9f0a41fb2d1cb7e5fe7f8a04f15fb0",
}
CORPUS = {
    "ssa/babynames.csv": "9d22e9b1d50d8a45509e959132d4d2deff41b581d41494083c262be4c730642a",
    "census/Names_2010Census.csv": "6b4933178060b4e5ac8dd9a611ec15472d35eab98fe9027474bc5ccc5b7a8708",
    "intl/common-forenames-by-country.csv": "b55fbdd0cbc8ddfa7345322e9ebd479cc52fa1f90a5ee7e382a68b5487b01292",
    "intl/common-surnames-by-country.csv": "32cb28bea558a9d353feeef097da03c6488a4e7ba9398e5983cee2f0b9caa91c",
}


def fold(raw: str) -> str:
    """Lowercase, NFKD-fold, strip combining marks, keep a-z: how the screen reads a word, without leetspeak."""
    s = unicodedata.normalize("NFKD", raw.lower())
    return "".join(c for c in s if "a" <= c <= "z")


def read_list(path: Path) -> list[str]:
    return [w.strip() for w in path.read_text(encoding="utf-8").splitlines() if w.strip() and not w.startswith("#")]


def pinned(path: Path, sha: str) -> Path:
    got = hashlib.sha256(path.read_bytes()).hexdigest()
    if got != sha:
        raise SystemExit(f"error: {path} has sha256 {got}, pinned {sha}; update the pin only on purpose")
    return path


def names(corpus: Path) -> tuple[set[str], dict[str, int]]:
    """Every folded name in the data, and each one's count (SSA births since SSA_SINCE, or Census people)."""
    every: set[str] = set()
    count: dict[str, int] = {}
    with pinned(corpus / "ssa/babynames.csv", CORPUS["ssa/babynames.csv"]).open(newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            n = fold(r["name"])
            every.add(n)
            if int(r["year"]) >= SSA_SINCE:
                count[n] = count.get(n, 0) + int(r["n"])
    census = corpus / "census/Names_2010Census.csv"
    with pinned(census, CORPUS["census/Names_2010Census.csv"]).open(newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["name"] == "ALL OTHER NAMES":
                continue
            n = fold(r["name"])
            every.add(n)
            count[n] = max(count.get(n, 0), int(r["count"]))
    for rel in ("intl/common-forenames-by-country.csv", "intl/common-surnames-by-country.csv"):
        with pinned(corpus / rel, CORPUS[rel]).open(newline="", encoding="utf-8-sig") as f:
            for r in csv.DictReader(f):
                every.add(fold(r.get("Romanized Name") or ""))
    every.discard("")
    return every, count


def build(corpus: Path, dict_dir: Path) -> tuple[list[str], list[str], list[str], dict[str, list[str]]]:
    profanity, slurs = set(read_list(PROFANITY_TXT)), set(read_list(SLURS_TXT))
    terms = profanity | slurs
    words: set[str] = set()
    for name, sha in DICTIONARIES.items():
        words |= {fold(w) for w in pinned(dict_dir / name, sha).read_text(encoding="utf-8", errors="replace").split()}
    every, count = names(corpus)
    # A list term is never benign evidence against another ("fucker" must not make "fuck" unsafe), nor is a word
    # holding a longer list term that holds this one ("motherfuckers"), nor an inflection of the term ("fucked").
    benign = (words | every) - terms - {""}

    def evidence(t: str, w: str) -> bool:
        if w == t or t not in w or any(w in (t + e, t + t[-1] + e) for e in INFLECTIONS):
            return False
        return not any(t in u and u != t and u in w for u in terms)

    holders: dict[str, list[str]] = {}
    for t in sorted(terms):
        if len(t) < EMBED_MIN_LEN:
            continue
        held = sorted(w for w in benign if evidence(t, w))
        if held:
            holders[t] = held
    embeddable = sorted(t for t in terms if len(t) >= EMBED_MIN_LEN and t not in holders)
    allowed = sorted(t for t in profanity - slurs if count.get(t, 0) >= REAL_NAME_FLOOR)
    return embeddable, sorted(EXCLUDE_CONTAINERS), allowed, holders


def header(what: str, transform: str) -> str:
    pins = "\n".join(f"#   {k}  {v}" for k, v in {**DICTIONARIES, **CORPUS}.items())
    return f"""\
# {what}
# Read by the name screen (moderation/NameScreen.kt, ADR 2026-11.y4pc). One term per line; blank lines and
# lines starting with '#' are ignored.
#
# GENERATED FILE -- DO NOT EDIT BY HAND. Regenerate with:  python3 scripts/gen-name-screen-lists.py --corpus <dir>
#
# {transform}
# Inputs (sha256):
{pins}
"""


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--corpus", type=Path, required=True, help="directory holding ssa/, census/ and intl/")
    ap.add_argument("--dict-dir", type=Path, default=Path("/usr/share/dict"))
    a = ap.parse_args()
    embeddable, exclusions, allowed, holders = build(a.corpus, a.dict_dir)
    dead = [e for e in exclusions if not any(t in e for t in embeddable)]
    if dead:
        raise SystemExit(f"error: exclusions holding no embeddable term (dead entries): {dead}")
    outputs = (
        (EMBEDDABLE_TXT, embeddable, header(
            "Profanity and slur terms the name screen may match inside a longer word.",
            f"Transform: terms of profanity_en.txt and slurs_en.txt with >= {EMBED_MIN_LEN} letters that no dictionary "
            "word or name\n# in the inputs contains; a list term, a word holding a longer list term, and an inflection of the "
            "term\n# (INFLECTIONS) never count as such a word.")),
        (EXCLUSIONS_TXT, exclusions, header(
            "Benign words that hold an embeddable term; the name screen cuts a token at each before looking inside.",
            "Transform: the hand-picked EXCLUDE_CONTAINERS dial, sorted; each must hold an embeddable term.")),
        (ALLOWED_TXT, allowed, header(
            "Profanity terms that are also real names, allowed as whole words in a name.",
            f"Transform: terms of profanity_en.txt, minus slurs_en.txt, that are a given name with >= {REAL_NAME_FLOOR} "
            f"SSA births\n# since {SSA_SINCE} or a surname with >= {REAL_NAME_FLOOR} people in the 2010 Census.")),
    )
    for path, terms, head in outputs:
        with path.open("w", encoding="utf-8", newline="\n") as f:
            f.write(head + "\n" + "\n".join(terms) + "\n")
        print(f"wrote {len(terms)} terms -> {path.relative_to(REPO_ROOT)}", file=sys.stderr)
    held = sum(1 for t in holders)
    print(f"  not embeddable: {held} terms held by a benign word, e.g. "
          + ", ".join(f"{t}<{h[0]}>" for t, h in list(holders.items())[:8]), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
