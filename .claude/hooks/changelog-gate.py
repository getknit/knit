#!/usr/bin/env python3
"""PreToolUse gate for CHANGELOG.md: the countable half of `.agents/rules/changelog.md`.

Reads a PreToolUse payload on stdin (Edit / MultiEdit / Write), reconstructs what the file
would become, and compares the bullets under `## Unreleased` against the ones on disk. Only
*added* bullets are judged, so an edit whose `new_string` carries neighbouring bullets as
context can never be blamed for their prose.

It checks only what a machine can count exactly — length, wrap, and a short list of literal
strings that are never right in this file. Everything requiring taste (marketing register,
a closing sentence that restates the opening, a forced metaphor) belongs to the prompt hook
that runs beside it; this script deliberately says nothing about those. That split exists
because the prompt hook used to invent word counts and quote spans that were not in the text,
and blocked clean bullets on them.

Exit 0 = allow (also the answer to every parse failure or missing dependency: a gate that
cannot read its input must not wedge the file), exit 2 = block with the findings on stderr.
"""

from __future__ import annotations

import json
import re
import sys

# Two sentences, about forty words (`.agents/rules/changelog.md`). These are the outer bounds
# the rule already names, not the target: a bullet at 54 words is over the target and passes.
MAX_WORDS = 55
MAX_SENTENCES = 3
MAX_COLUMNS = 110

# Literal strings with no honest use in a user-facing bullet about a phone messenger. Words
# whose verdict depends on context — ensure, unlock, navigate, robust, key, landscape — are
# deliberately NOT here; a regex cannot tell "unlock your phone" from "unlocks new value", and
# a gate that guesses is the bug this file exists to fix. The prompt hook judges those.
BANNED_PHRASES = [
    "delve",
    "leverage",
    "seamless",
    "seamlessly",
    "testament",
    "pivotal",
    "comprehensive",
    "empower",
    "empowers",
    "at its core",
    "it's important to note",
    "it is important to note",
    "it's worth mentioning",
    "it is worth mentioning",
    "highlighting its importance",
    "underscoring the need",
    "game-changer",
    "in today's",
]

CURLY = {"“": '"', "”": '"', "‘": "'", "’": "'"}

# Abbreviations and version numbers whose period must not end a sentence.
ABBREVIATIONS = ["e.g.", "i.e.", "etc.", "vs.", "Mr.", "Ms.", "Dr.", "approx."]


def emoji_in(text: str) -> list[str]:
    """Pictographic characters. Ranges, not `str.isascii()` — the file uses en dashes and ✓."""
    found = []
    for ch in text:
        cp = ord(ch)
        if (
            0x1F300 <= cp <= 0x1FAFF  # pictographs, emoticons, transport, symbols
            or 0x2600 <= cp <= 0x26FF  # misc symbols
            or 0x2700 <= cp <= 0x27BF  # dingbats
            or cp == 0xFE0F  # variation selector-16 (emoji presentation)
        ):
            found.append(ch)
    return found


def count_sentences(text: str) -> int:
    masked = text
    for abbr in ABBREVIATIONS:
        masked = masked.replace(abbr, abbr.replace(".", "\x00"))
    # A period between digits is a version or a decimal, never a sentence end.
    masked = re.sub(r"(?<=\d)\.(?=\d)", "\x00", masked)
    parts = [p for p in re.split(r"(?<=[.!?])[\"')\]]*\s+", masked) if p.strip()]
    return len(parts)


def count_words(text: str) -> int:
    return len([w for w in re.split(r"\s+", text.strip()) if w])


def unreleased_bullets(content: str) -> list[tuple[str, list[str]]]:
    """Bullets under `## Unreleased`, as (flattened text, raw lines).

    A bullet is a `- ` line plus its indented continuations. Nested list items (`  - `) are
    treated as part of the parent, which matches how this file is written.
    """
    lines = content.splitlines()
    start = None
    for i, line in enumerate(lines):
        if re.match(r"^##\s+Unreleased\s*$", line):
            start = i + 1
            break
    if start is None:
        return []
    end = len(lines)
    for i in range(start, len(lines)):
        if lines[i].startswith("## "):
            end = i
            break

    bullets: list[tuple[str, list[str]]] = []
    current: list[str] | None = None
    for line in lines[start:end]:
        if re.match(r"^- \S", line):
            if current:
                bullets.append((flatten(current), current))
            current = [line]
        elif current is not None and re.match(r"^\s+\S", line):
            current.append(line)
        else:
            if current:
                bullets.append((flatten(current), current))
            current = None
    if current:
        bullets.append((flatten(current), current))
    return bullets


def flatten(lines: list[str]) -> str:
    joined = " ".join(line.strip() for line in lines)
    return re.sub(r"^-\s+", "", joined).strip()


def apply_edit(content: str, old: str, new: str, replace_all: bool) -> str | None:
    if old == "":
        return None
    if old not in content:
        return None
    return content.replace(old, new) if replace_all else content.replace(old, new, 1)


def projected_content(payload: dict, disk: str) -> str | None:
    """What the file would hold after this call, or None when that can't be determined."""
    tool = payload.get("tool_name") or ""
    ti = payload.get("tool_input") or {}
    if tool == "Write":
        content = ti.get("content")
        return content if isinstance(content, str) else None
    if tool == "Edit":
        return apply_edit(disk, ti.get("old_string", ""), ti.get("new_string", ""), bool(ti.get("replace_all")))
    if tool == "MultiEdit":
        content = disk
        for entry in ti.get("edits") or []:
            content = apply_edit(
                content, entry.get("old_string", ""), entry.get("new_string", ""), bool(entry.get("replace_all"))
            )
            if content is None:
                return None
        return content
    return None


def findings_for(bullet: str, raw: list[str]) -> list[str]:
    findings = []

    words = count_words(bullet)
    if words > MAX_WORDS:
        findings.append(f"{words} words (the rule is two sentences, about forty; the cap here is {MAX_WORDS})")

    sentences = count_sentences(bullet)
    if sentences > MAX_SENTENCES:
        findings.append(f"{sentences} sentences (at most {MAX_SENTENCES}, and only when the third carries a caveat)")

    for line in raw:
        if len(line) > MAX_COLUMNS:
            findings.append(f"line is {len(line)} columns, past the file's {MAX_COLUMNS}-column wrap: {line.strip()!r}")

    lowered = bullet.lower()
    for phrase in BANNED_PHRASES:
        if re.search(rf"(?<!\w){re.escape(phrase)}", lowered):
            findings.append(f"AI vocabulary: {phrase!r}")

    for ch in CURLY:
        if ch in bullet:
            findings.append(f"curly quote {ch!r} — this file uses straight quotes")

    found_emoji = emoji_in(bullet)
    if found_emoji:
        findings.append(f"emoji: {''.join(found_emoji)!r}")

    return findings


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0

    path = ((payload.get("tool_input") or {}).get("file_path")) or ""
    if not path.replace("\\", "/").endswith("/CHANGELOG.md") and path != "CHANGELOG.md":
        return 0

    try:
        with open(path, encoding="utf-8") as fh:
            disk = fh.read()
    except FileNotFoundError:
        disk = ""
    except OSError:
        return 0

    projected = projected_content(payload, disk)
    if projected is None:
        return 0

    before = {text for text, _ in unreleased_bullets(disk)}
    added = [(text, raw) for text, raw in unreleased_bullets(projected) if text not in before]
    if not added:
        return 0

    blocked = []
    for text, raw in added:
        findings = findings_for(text, raw)
        if findings:
            blocked.append((text, findings))

    if not blocked:
        return 0

    print(
        "Blocked: new `## Unreleased` bullet(s) break `.agents/rules/changelog.md`. "
        "Every number below was counted, not estimated.",
        file=sys.stderr,
    )
    for text, findings in blocked:
        preview = text if len(text) <= 160 else text[:157] + "..."
        print(f"\n  bullet: {preview}", file=sys.stderr)
        for finding in findings:
            print(f"    - {finding}", file=sys.stderr)
    print(
        "\nTwo sentences, about forty words: say what changed, then who notices, and stop. "
        "Run the `humanizer` skill on the bullet and re-issue the edit.",
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    sys.exit(main())
