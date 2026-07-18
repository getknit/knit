#!/usr/bin/env python3
"""Generate app/src/main/assets/moderation/profanity_en.txt from the vendored
dsojevic/profanity-list corpus.

The output is a committed, GENERATED file. Regenerate it with:

    python3 scripts/gen-profanity-list.py

Source corpus: third_party/profanity-list/en.json (MIT, (c) 2021 David Sojevic), pinned at the commit
recorded below and in third_party/profanity-list/PROVENANCE.md. This is a developer tool run on a
workstation; it never runs in the app, so the app's no-INTERNET-permission design is unaffected.

Transform (one dsojevic entry -> zero or more LexicalTextFilter terms):
  * curate: keep entries with severity >= SEVERITY_FLOOR and drop any carrying a tag in EXCLUDE_TAGS;
  * expand the '|' alternations in `match` FIRST -- this rescues the single-word variant of an otherwise
    multi-word entry (e.g. "apeshit|ape shit" keeps "apeshit");
  * reduce each alternative: lowercase, NFKD-fold + strip combining marks (mirrors the accent handling in
    LexicalTextFilter.normalize) and delete '*' (dsojevic's "one or more of the previous character"
    wildcard) down to its base letter -- the filter's collapseRuns re-catches stretched input at match time;
  * keep only single [a-z]+ tokens of length >= MIN_LEN. This drops multi-word, punctuated, and
    digit-bearing candidates so no "dead" entry (one the whole-token matcher could never hit) is shipped.

Deliberately NOT applied here: leetspeak folding. Folding during selection would turn a digit/symbol
candidate like "2g1c" into "2gic" -> token "gic" and ship garbage; requiring [a-z]+ *before* any fold
drops such candidates instead. The filter still leet-folds at match time, so "sh1t" input matches the
shipped literal "shit". Ship clean literals, match dirty input.

The `allow_partial` / `exceptions` fields are ignored: the filter is always whole-token, so substring
exceptions are moot and partial-match recall is intentionally left to the ML classifier that backstops
the lexical pass.
"""

from __future__ import annotations

import json
import re
import sys
import unicodedata
from pathlib import Path

# --- provenance (keep in sync with third_party/profanity-list/PROVENANCE.md) ---
SOURCE_REPO = "dsojevic/profanity-list"
SOURCE_URL = "https://github.com/dsojevic/profanity-list"
SOURCE_COMMIT = "c27924319aa9bd6f917e3782b4f4b6604a50b652"
RETRIEVED = "2026-07-18"

# --- curation dials (edit + regenerate to retune) ---
SEVERITY_FLOOR = 2  # drop severity 1 (mild/clinical, e.g. "anus")
EXCLUDE_TAGS = {"lgbtq"}  # identity terms -> handled contextually by the ML identity_attack pass
MIN_LEN = 3  # never ship 1-2 char tokens (whole-token over-block risk)

# Terms with heavy innocent usage that would over-block a public room as whole-token matches
# ("the opposite sex", "nude" the colour, "collared shirt", "white-collar"). Genuinely sexual usage is
# still caught contextually by the ML sexual_explicit pass. Edit + regenerate to adjust.
CURATED_DROP = {"sex", "nude", "nudity", "topless", "collared", "collaring"}

# The original hand-curated starter terms, union'd in so a regeneration never regresses the set the
# project shipped before adopting the corpus. All are already single [a-z]+ tokens.
ALWAYS_INCLUDE = [
    "fuck", "fucker", "motherfucker", "shit", "bullshit", "asshole", "bitch",
    "bastard", "dickhead", "cunt", "slut", "whore", "piss", "prick", "wanker", "twat",
]

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCE_JSON = REPO_ROOT / "third_party" / "profanity-list" / "en.json"
OUTPUT_TXT = REPO_ROOT / "app" / "src" / "main" / "assets" / "moderation" / "profanity_en.txt"

TOKEN = re.compile(r"[a-z]+")


def reduce(alt: str) -> str:
    """Reduce one dsojevic `match` alternative to its base literal (no leetspeak fold)."""
    s = unicodedata.normalize("NFKD", alt.lower())
    s = "".join(c for c in s if not unicodedata.combining(c))  # strip \p{Mn}
    return s.replace("*", "")  # '*' = "1+ of previous char" -> base letter


def build() -> tuple[list[str], dict[str, int]]:
    data = json.loads(SOURCE_JSON.read_text(encoding="utf-8"))
    kept: set[str] = set()
    stats = {"severity": 0, "tag": 0, "multiword_or_nonalpha": 0, "too_short": 0, "curated_drop": 0}
    for entry in data:
        if entry.get("severity", 0) < SEVERITY_FLOOR:
            stats["severity"] += 1
            continue
        if EXCLUDE_TAGS & set(entry.get("tags", [])):
            stats["tag"] += 1
            continue
        for alt in entry.get("match", "").split("|"):
            r = reduce(alt)
            if not TOKEN.fullmatch(r):
                stats["multiword_or_nonalpha"] += 1
                continue
            if len(r) < MIN_LEN:
                stats["too_short"] += 1
                continue
            if r in CURATED_DROP:
                stats["curated_drop"] += 1
                continue
            kept.add(r)
    stats["added_from_starter"] = sum(1 for t in ALWAYS_INCLUDE if t not in kept)
    kept.update(ALWAYS_INCLUDE)
    return sorted(kept), stats


def header() -> str:
    tags = ", ".join(sorted(EXCLUDE_TAGS))
    return f"""\
# English profanity word list for LexicalTextFilter -- the deterministic first pass of the hybrid
# text-moderation pipeline. One term per line; blank lines and lines starting with '#' are ignored.
#
# Matching is whole-token and evasion-tolerant (case, diacritics, leetspeak, stretched/spaced letters),
# so each term is a single base literal. Morphological derivatives (plurals, -ing, -ed, compounds) are
# caught by the normalizer or by the ML toxicity classifier that backstops this pass, not enumerated here.
#
# GENERATED FILE -- DO NOT EDIT BY HAND. Regenerate with:  python3 scripts/gen-profanity-list.py
#
# Source:  {SOURCE_REPO} -- {SOURCE_URL}
# Commit:  {SOURCE_COMMIT}  (retrieved {RETRIEVED})
# License: MIT (c) 2021 David Sojevic -- full text: app/src/main/assets/moderation/README.md
#
# Transform: keep severity >= {SEVERITY_FLOOR}; drop the {tags} tag; expand '|' alternations; reduce '*'
# to the base letter; lowercase + NFKD-fold and keep only single [a-z]+ tokens (>= {MIN_LEN} chars);
# union the original curated terms; dedupe; sort. Full detail: scripts/gen-profanity-list.py.
"""


def main() -> int:
    if not SOURCE_JSON.exists():
        print(f"error: missing {SOURCE_JSON} (see third_party/profanity-list/PROVENANCE.md)", file=sys.stderr)
        return 1
    terms, stats = build()
    with OUTPUT_TXT.open("w", encoding="utf-8", newline="\n") as f:
        f.write(header() + "\n" + "\n".join(terms) + "\n")
    tags = ", ".join(sorted(EXCLUDE_TAGS))
    print(f"wrote {len(terms)} terms -> {OUTPUT_TXT.relative_to(REPO_ROOT)}", file=sys.stderr)
    print(
        f"  dropped: severity<{SEVERITY_FLOOR}={stats['severity']}  "
        f"tag[{tags}]={stats['tag']}  "
        f"multiword/nonalpha={stats['multiword_or_nonalpha']}  "
        f"too_short(<{MIN_LEN})={stats['too_short']}  "
        f"curated={stats['curated_drop']}",
        file=sys.stderr,
    )
    print(f"  starter terms the corpus lacked (added): {stats['added_from_starter']}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
