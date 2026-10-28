#!/usr/bin/env python3
"""Check the agent docs and design docs for references the code no longer backs.

WHY THIS EXISTS. The routing files, rules, personas and docs point into the code by name: a class, a member, a path,
an ADR id. When the code moves, nothing moves the doc, so it goes on sending readers (and owner and reviewer agents)
to a function that was renamed or a file that was deleted. On 2026-10-09 three such names were found by hand in one
afternoon (`dropQueued`, `openNewEpoch`, `unanchoredRaceWinner`, issue #145's sweep). Each is visible in the text and
in git, so this finds them in a second instead.

WHAT IT CHECKS. Every Markdown file under DOC_ROOTS (ADR files only for ADR ids, since a decision record may name the
symbol it replaced). Inline code spans and, inside fenced blocks, repository paths:

- retired-symbol  a name in a code span was declared in Kotlin once (git history, or the working tree's own diff)
                  and is now in no tracked source file. The strongest signal: the code had it and dropped it.
- missing-member  `Type.member` where `Type` is a class, object or interface declared in the app today and `member`
                  appears in no tracked source file.
- missing-path    a path whose first segment is a repository root or an app package (`mesh/`, `ui/`, `scripts/`,
                  `.agents/`, …) that names no tracked file or directory.
- line-ref        `File.kt:123` past the end of the one tracked file with that name.
- missing-adr     an ADR id (`ADR 024`, `2026-09.k3f9`) with no file in `.agents/memory/decisions/`.

Platform and third-party names are never flagged: a bare name is checked only against what this repository once
declared, and a dotted one only when its head is one of this repository's types.

A finding that is deliberate (a doc recording what a name used to be) is allowed in its paragraph, with a reason:
`<!-- doc-lint: allow <name> — <why> -->`. A marker with no reason does not count. Each finding prints as
`path:line: doc-lint/<rule>: <what is wrong> — <what to do>`; any finding exits 1. Pure stdlib; tests:
`python3 -m unittest scripts/test_doc_lint.py`.

Usage: scripts/doc-lint.py [--rules] [--hook | --stop] [FILE ...]
  --rules  print each rule and what it guards against, then exit
  --hook   read a Claude Code PostToolUse payload on stdin and check the one Markdown file it edited, if it is in
           scope (exit 2 on findings, which hands them back to the agent)
  --stop   Stop-hook mode: check every doc when the working tree has an uncommitted change to a doc or a source
           file (a rename in code is what strands a doc); exit 2 on findings. Honors `stop_hook_active`.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
APP_PACKAGE = "app/src/main/java/app/getknit/knit"
PACKAGE_DIRS = [APP_PACKAGE, "app/src/test/java/app/getknit/knit", "app/src/main", "wear/src/main/java/app/getknit/knit"]

# The Markdown that points into the code. ADR files are read for ADR ids only (see the module doc).
DOC_ROOTS = ["AGENTS.md", "CLAUDE.md", ".agents/context", ".agents/rules", ".agents/personas", ".agents/memory/roadmap.md",
             ".agents/skills/debug-bridge", ".agents/skills/machine-translation", ".agents/skills/upgrade-notes",
             "docs"]
ADR_DIR = ".agents/memory/decisions"
SELF = {"scripts/doc-lint.py", "scripts/test_doc_lint.py"}

SOURCE_EXT = re.compile(r"\.(kt|kts|java|py|sh|xml|json|toml|proto|pro|properties|ya?ml|gradle|txt|cfg|conf)$")
DECL = re.compile(r"\b(?:fun|class|object|interface|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_]\w*\.)*([A-Za-z_]\w*)")
TYPE_DECL = re.compile(r"\b(?:class|object|interface|typealias)\s+([A-Z]\w*)")
WORD = re.compile(r"[A-Za-z_]\w*")
SYMBOL_SPAN = re.compile(r"^[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*")
LINE_REF = re.compile(r"^([\w./-]+\.(?:kt|kts|py|sh|xml|md)):(\d+)(?:-\d+)?$")
ADR_ID = re.compile(r"\b(20\d\d-\d\d)\.([a-z0-9]{4})\b|\bADRs? (\d{3})\b")
ALLOW = re.compile(r"<!--\s*doc-lint:\s*allow\s+(\S+)\s+(?:—|--?)\s*\S[^>]*-->")
RECORD = re.compile(r"<!--\s*doc-lint:\s*record\s+(?:—|--?)\s*\S[^>]*-->")
# A sibling repository named just before the id (`knit-ios`, knit-spool, knit-pair, …): its decision log, not ours.
OTHER_REPO = re.compile(r"\bknit-(?!next\b)[a-z]+\b[^.]{0,80}$")
FILE_EXT = re.compile(r"kt|kts|py|sh|md|json|xml|yml|yaml|toml|txt|png|tflite")
FENCE = re.compile(r"^\s*(```|~~~)")
INLINE_CODE = re.compile(r"(`+)(.+?)\1")
FENCED_PATH = re.compile(r"(?<![\w./-])((?:scripts|\.agents|\.claude|\.github|\.gitlab|app|docs|vectors|wear|watchface|watchface-monogram)/[\w./-]*\w)")

RULES = {
    "retired-symbol": "the name was declared in this repository's Kotlin and has since gone from every tracked source "
    "file. Point at the name that replaced it (grep the commit that removed it), or allow it in its paragraph if the "
    "doc is recording the old name on purpose.",
    "missing-member": "`Type.member` names one of the app's types, but no tracked source file mentions the member. "
    "Fix the member name, or allow it if the doc means a platform member the type inherits.",
    "missing-path": "a path rooted in the repository (`mesh/`, `scripts/`, `.agents/`, …) names no tracked file or "
    "directory. Fix the path, or drop the reference if the file is gone.",
    "line-ref": "`File.kt:N` points past the end of that file. Line refs rot fastest; prefer `Class.member`.",
    "missing-adr": "the ADR id has no file in .agents/memory/decisions/. Fix the id (`scripts/adr.py` lists them).",
}


@dataclass
class Finding:
    path: str
    line: int
    rule: str
    message: str

    def __str__(self) -> str:
        return f"{self.path}:{self.line}: doc-lint/{self.rule}: {self.message}"


@dataclass
class Index:
    """What the code backs today, and what it once declared."""

    tokens: set[str] = field(default_factory=set)
    types: set[str] = field(default_factory=set)
    retired: set[str] = field(default_factory=set)
    files: set[str] = field(default_factory=set)
    dirs: set[str] = field(default_factory=set)
    roots: set[str] = field(default_factory=set)
    adrs: set[str] = field(default_factory=set)
    lines: dict[str, int] = field(default_factory=dict)

    def has_path(self, p: str) -> bool:
        return p in self.files or p in self.dirs


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True, check=False).stdout


def strip_comments(src: str) -> str:
    """Kotlin source with its comments blanked: a KDoc that still names a retired symbol does not keep it alive."""
    out: list[str] = []
    i, n = 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            out.append(" ")
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j])
            i = j
        elif src[i] in "\"'":
            q, j = src[i], i + 1
            while j < n and src[j] != q and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            out.append(src[i : j + 1])
            i = j + 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def build_index() -> Index:
    idx = Index()
    tracked = [f for f in git("ls-files").splitlines() if (REPO / f).exists()]
    if not tracked:
        sys.exit("doc-lint: no tracked files — run it in a git checkout (it reads the index and the history)")
    if git("rev-parse", "--is-shallow-repository").strip() == "true":  # a pass here would hide every retired name
        sys.exit("doc-lint: shallow clone — retired-symbol needs the whole history; fetch with depth 0 "
                 "(GitHub `fetch-depth: 0`, GitLab `GIT_DEPTH: 0`)")
    untracked = git("ls-files", "--others", "--exclude-standard").splitlines()
    for f in tracked + untracked:
        idx.files.add(f)
        if f in SELF:  # this checker and its tests name retired symbols on purpose
            continue
        parts = f.split("/")
        for i in range(1, len(parts)):
            idx.dirs.add("/".join(parts[:i]))
        stem = parts[-1].split(".")[0]
        idx.tokens.add(stem)
        if not SOURCE_EXT.search(f) or not (REPO / f).is_file():
            continue
        try:
            text = (REPO / f).read_text(errors="ignore")
        except OSError:
            continue
        idx.tokens.update(WORD.findall(strip_comments(text) if f.endswith((".kt", ".kts", ".java")) else text))
        idx.lines[f] = text.count("\n") + 1
        if f.endswith(".kt"):
            idx.types.update(TYPE_DECL.findall(text))
    idx.roots = {f.split("/")[0] for f in idx.files if "/" in f}
    for base in PACKAGE_DIRS:
        idx.roots.update(d[len(base) + 1:].split("/")[0] for d in idx.dirs if d.startswith(base + "/"))
    removed = git("log", "-p", "--no-merges", "--format=", "--unified=0", "--", "*.kt")
    removed += git("diff", "HEAD", "--unified=0", "--", "*.kt")
    for line in removed.splitlines():
        if line.startswith("-") and not line.startswith("---"):
            idx.retired.update(DECL.findall(line))
    idx.retired -= idx.tokens
    for f in idx.files:
        if f.startswith(ADR_DIR + "/"):
            name = f.rsplit("/", 1)[-1]
            m = re.match(r"(\d{4}-\d{2})-([a-z0-9]{4})-|(\d{3})-", name)
            if m:
                idx.adrs.add(m.group(3) or f"{m.group(1)}.{m.group(2)}")
    return idx


def paragraph_allows(lines: list[str]) -> list[set[str]]:
    """The names allowed on each line: every marker in the line's paragraph (blank-line separated)."""
    allows: list[set[str]] = [set() for _ in lines]
    start = 0
    for i in range(len(lines) + 1):
        if i == len(lines) or not lines[i].strip():
            names = {m.group(1) for ln in lines[start:i] for m in ALLOW.finditer(ln)}
            for j in range(start, i):
                allows[j] = names
            start = i + 1
    return allows


def resolve_path(span: str, idx: Index) -> bool | None:
    """True if [span] names a tracked path, False if it is a repository path that names nothing, None if not a path."""
    p = span.rstrip("/").split("#")[0].removeprefix("./")
    if not p or "/" not in p and not re.search(r"\.(kt|kts|py|sh|md)$", p):
        return None
    if any(c in p for c in "*<>{}…$~ ") or "..." in p or "://" in p or p.startswith(("/", "../")):
        return None
    if "/build/" in "/" + p + "/":  # a Gradle output: generated, never tracked
        return None
    head = p.split("/")[0]
    if "/" not in p:  # a bare file name: any tracked file with that name
        return any(f.endswith("/" + p) or f == p for f in idx.files) or None
    if head not in idx.roots:
        return None
    if any(resolves(c, idx) for c in [p] + [f"{base}/{p}" for base in PACKAGE_DIRS]):
        return True
    # a shorthand that names a file by its tail (`meshtastic/MeshtasticGatt`, `mesh/MeshService`)
    tail = "/" + p
    if any(f.endswith((tail, tail + ".kt")) for f in idx.files) or any(d.endswith(tail) for d in idx.dirs):
        return True
    # `dir/Symbol` (a class in a file named otherwise: `mesh/power/PowerPolicy`) or `dir/File.member`
    parent, _, last = p.rpartition("/")
    stem, _, rest = last.partition(".")
    if rest:  # `dir/File.member`: the file must exist; a file extension must match exactly (above)
        return False if FILE_EXT.fullmatch(rest) else resolve_path(f"{parent}/{stem}", idx) is True
    in_dir = any(resolves(c, idx) for c in [parent] + [f"{b}/{parent}" for b in PACKAGE_DIRS])
    return stem in idx.tokens and (in_dir or any(d.endswith("/" + parent) for d in idx.dirs))


def resolves(p: str, idx: Index) -> bool:
    return idx.has_path(p) or idx.has_path(p + ".kt")


def check_span(span: str, idx: Index) -> list[tuple[str, str, str]]:
    """Findings for one inline code span, as (rule, name, message)."""
    span = span.strip()
    out: list[tuple[str, str, str]] = []
    m = LINE_REF.match(span)
    if m:
        name, n = m.group(1), int(m.group(2))
        hits = [f for f in idx.lines if f == name or f.endswith("/" + name)]
        if len(hits) == 1 and n > idx.lines[hits[0]]:
            out.append(("line-ref", span, f"`{span}` is past the end of {hits[0]} ({idx.lines[hits[0]]} lines)"))
        return out
    if "/" in span or re.search(r"\.(kt|kts|py|sh|md)$", span):
        if resolve_path(span, idx) is False:
            out.append(("missing-path", span, f"`{span}` names no tracked file or directory"))
        return out
    m = SYMBOL_SPAN.match(span)
    if not m:
        return out
    segs = m.group(0).split(".")
    if span[m.end():].startswith(("…", "...")):  # `Class.aPrefix…` names a member by its prefix
        prefix = segs.pop()
        if segs and segs[0] in idx.types and not any(t.startswith(prefix) for t in idx.tokens):
            return [("missing-member", f"{segs[0]}.{prefix}", f"no source mentions a `{segs[0]}` member starting `{prefix}`")]
        return out
    for s in segs:
        if s.isupper() and "_" not in s:  # a bare all-caps word is prose (`BLOCKED`, `HAVE`) more often than a constant
            continue
        if s in idx.retired:
            out.append(("retired-symbol", s, f"`{s}` was declared in this repository and is in no source file now"))
    if len(segs) > 1 and segs[0] in idx.types:
        for s in segs[1:]:
            if s.endswith("_") and any(t.startswith(s) for t in idx.tokens):  # `Protocol.CAP_*`, a family by prefix
                continue
            if "_" in s and s.lower() == s:  # snake_case is a protobuf field (Meshtastic's), never a Kotlin member
                continue
            if s not in idx.tokens and s not in idx.retired and not s[0].isdigit():
                out.append(("missing-member", f"{segs[0]}.{s}", f"`{segs[0]}` is the app's type, but no source mentions `{s}`"))
    return out


def lint_text(path: str, text: str, idx: Index, adr_only: bool = False) -> list[Finding]:
    lines = text.splitlines()
    adr_only = adr_only or bool(RECORD.search(text))
    allows = paragraph_allows(lines)
    findings: list[Finding] = []
    in_fence = False
    for n, line in enumerate(lines, 1):
        allowed = allows[n - 1]
        for m in ADR_ID.finditer(line):
            adr = m.group(3) or f"{m.group(1)}.{m.group(2)}"
            before = (lines[n - 2] if n > 1 else "") + " " + line[: m.start()]
            if OTHER_REPO.search(before):  # an id in a sibling repository's decision log
                continue
            if adr not in idx.adrs and adr not in allowed:
                findings.append(Finding(path, n, "missing-adr", f"ADR {adr} has no file in {ADR_DIR}/"))
        if adr_only:
            continue
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            for m in FENCED_PATH.finditer(line):
                p = m.group(1)
                if p not in allowed and resolve_path(p, idx) is False:
                    findings.append(Finding(path, n, "missing-path", f"`{p}` names no tracked file or directory"))
            continue
        for m in INLINE_CODE.finditer(line):
            span = m.group(2).strip()
            for rule, name, msg in check_span(span, idx):
                if span not in allowed and name not in allowed and name.split(".")[-1] not in allowed:
                    findings.append(Finding(path, n, rule, msg))
    return findings


def doc_files() -> list[Path]:
    out: list[Path] = []
    for root in DOC_ROOTS:
        p = REPO / root
        out.extend(sorted(p.rglob("*.md")) if p.is_dir() else [p] if p.is_file() else [])
    return out


def in_scope(rel: str) -> bool:
    return rel.endswith(".md") and (rel.startswith(ADR_DIR + "/") or any(rel == r or rel.startswith(r + "/") for r in DOC_ROOTS))


def lint_files(paths: list[Path], idx: Index) -> list[Finding]:
    findings: list[Finding] = []
    for p in paths:
        rel = str(p.resolve().relative_to(REPO))
        if rel == f"{ADR_DIR}.md" or rel.endswith("decisions.md"):
            continue
        findings.extend(lint_text(rel, p.read_text(errors="ignore"), idx, adr_only=rel.startswith(ADR_DIR + "/")))
    return findings


def all_docs() -> list[Path]:
    return doc_files() + sorted((REPO / ADR_DIR).glob("*.md"))


def report(findings: list[Finding], stream) -> None:
    for f in findings:
        print(f, file=stream)
    if findings:
        print(f"\n{len(findings)} finding(s). Rules: scripts/doc-lint.py --rules. A deliberate one is allowed in its "
              "paragraph with `<!-- doc-lint: allow <name> — <why> -->`.", file=stream)


def main(argv: list[str]) -> int:
    if "--rules" in argv:
        for name, why in RULES.items():
            print(f"{name}: {why}\n")
        return 0
    if "--hook" in argv:
        try:
            payload = json.load(sys.stdin)
        except (json.JSONDecodeError, ValueError):
            return 0
        target = (payload.get("tool_input") or {}).get("file_path") or ""
        try:
            rel = str(Path(target).resolve().relative_to(REPO))
        except ValueError:
            return 0
        if not in_scope(rel) or not (REPO / rel).is_file():
            return 0
        findings = lint_files([REPO / rel], build_index())
        report(findings, sys.stderr)
        return 2 if findings else 0
    if "--stop" in argv:
        try:
            payload = json.load(sys.stdin)
        except (json.JSONDecodeError, ValueError):
            payload = {}
        if payload.get("stop_hook_active"):
            return 0
        changed = [ln[3:] for ln in git("status", "--porcelain").splitlines()]
        if not any(in_scope(c) or SOURCE_EXT.search(c) for c in changed):
            return 0
        findings = lint_files(all_docs(), build_index())
        if findings:
            print("Stop blocked: `scripts/doc-lint.py` found doc references the code no longer backs. Fix them "
                  "(or allow a deliberate one), then finish:\n", file=sys.stderr)
        report(findings, sys.stderr)
        return 2 if findings else 0
    paths = [Path(a) for a in argv if not a.startswith("--")] or all_docs()
    findings = lint_files(paths, build_index())
    report(findings, sys.stdout)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
