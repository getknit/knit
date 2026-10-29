#!/usr/bin/env python3
"""Lint mesh-lab scenarios for the flaws that can be read off the source (app/src/test/.../mesh/lab/).

WHY THIS EXISTS. Every mesh-lab flake so far has been a scenario assuming an order the mesh does not promise
(.agents/context/testing.md, "Mesh in a box"; the mesh-lab-reviewer's catalogue). Most of those need a reader
or a chaos sweep to find, at minutes a round. Some are visible in the text: a `delay` used as a sync point, a
`tryAwait` whose answer nobody reads, a hold re-applied right after a release, a filtered release with no
re-link before the oracle, a clock jump past the 48 h bound. This finds those in a second, before the sweep and
the review spend their minutes on them.

WHAT IT DOES. Reads every `*LabTest.kt` under the lab package (or the files given), blanks out comments and
string literals so nothing in prose or a message matches, and checks each rule (`--rules` lists them). A
finding prints as `path:line: lab-lint/<rule>: <what is wrong> — <what to do>`; any finding exits 1.

A finding that is deliberate is allowed where it stands, with its reason: `// lab-lint: allow <rule> — <why>`
on the flagged line or in the comment block right above it (KDoc included). A marker with no reason does not
count. Pure stdlib; tests: `python3 -m unittest scripts/test_lab_lint.py`.

Usage: scripts/lab-lint.py [--rules] [--hook] [FILE ...]
  --rules  print each rule and the flake shape it guards against, then exit
  --hook   read a Claude Code PostToolUse payload on stdin and lint the one file it edited (exit 2 on findings,
           which hands the findings back to the agent); anything that is not a lab scenario passes silently
"""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LAB = REPO / "app/src/test/java/app/getknit/knit/mesh/lab"
CONST_ROOTS = [REPO / "app/src/main/java", LAB]

HOUR_MS = 3_600_000
MAX_JUMP_MS = 48 * HOUR_MS

RULES = {
    "sleep-as-sync": "a `delay`, `sleep`, `withTimeout*` or `lab.settle()` in a scenario is a timed wait, never an event: on "
    "a slow runner the thing it waits for lands later (catalogue shape 6). Await the effect with `lab.await`/`tryAwait`; a "
    "pause before a check that nothing more arrives says so with an allow.",
    "dropped-await-result": "`tryAwait` returns false when the wait runs out; dropped, the scenario carries on past an "
    "event that never happened and fails a step later under the wrong name (catalogue shape 9). Wrap it in "
    '`assertTrue("…", …)` or use `lab.await`.',
    "release-then-hold": "a hold re-applied right after a `release` of the same pipe leaves a gap the far side's answer "
    "crosses unheld (catalogue shape 5). Use `release(to, keepHolding = true)`.",
    "filtered-release-then-oracle": "a frame a `release { … }` filters out is lost like a dropped packet; with no re-link "
    "before `assertConverged`, only the 60 s re-offer brings it back, past the oracle's wait (catalogue shape 5). "
    "Re-link (`lab.unlink` + `lab.link`, `linkAll`, a lossy `connect`) or `reoffer()` before the oracle.",
    "direct-disconnect": "`transport.disconnect` skips what `lab.unlink` waits for (both collectors handed the departure, "
    "the board's link set, the manager's acted-on set), so the next step races the departure (catalogue shape 6). "
    "Use `lab.unlink`.",
    "virtual-time": "the lab's stacks run on `Dispatchers.Default` in real time; a test dispatcher or `runTest` moves "
    "nothing they wait on (testing.md, \"Time is real\"). Use `runBlocking` and the lab's awaits.",
    "clock-jump-over-48h": "a scenario's clock jumps add up past 48 h (or one cannot be sized) and a `heal()`, `restart()` "
    "or `restoreFromBackup()` follows: the ratchet sweep they run then reaps receive epochs stamped a real day ago as two "
    "days stale (testing.md, the calendar bullet). Keep the total under 48 h before a sweep.",
    "relay-count-sync": "a count of relay decisions (`framesRelayed`, `framesOriginated`, `framesSuppressed`) since a "
    "baseline is no sync: a relay still in its 0–150 ms jitter lands inside or outside the count (#119, catalogue "
    "shape 1). Await the store or the frame by a named predicate.",
    "ignore-without-issue": "an ignored scenario is a finding parked with its issue (`@Ignore(\"#NN: …\")`, testing.md), "
    "so the next reader can tell a known bug from a forgotten test.",
    "missing-oracle": "a scenario ends in `assertConverged` (directly or through a helper in its file) so it catches "
    "what its author did not think to assert (testing.md, \"Write a scenario at user level\"). A scenario whose "
    "subject is something the oracle cannot see allows this with its reason.",
    "missing-chaos-rule": "every `*LabTest` carries `@get:Rule val chaos = LabChaos.rule()`, or it runs plain under "
    "chaos and its failures name no seed (`LabChaosCoverageTest`, catalogue shape 11).",
    "plane-collectors-at-boot": "(MeshLab.kt) every child of a node's `CompositeMeshTransport` is read `.collecting` "
    "in a `while` wait, directly or through a `val collecting` getter: the composite subscribes each child on a `merge` coroutine of its own, and a "
    "shared flow drops what it is handed before then — a restarted node lost a link file so (catalogue shape 13). Wrap "
    "the child so its collectors are counted (`LabCollectors`) and add it to the boot's wait.",
}

FIXTURE = "MeshLab.kt"

ALLOW = re.compile(r"lab-lint:\s*allow\s+([\w,\s-]+?)\s*(?:—|--|:)\s*\S")


@dataclass(frozen=True)
class Finding:
    path: Path
    line: int
    rule: str
    detail: str

    def render(self) -> str:
        try:
            shown = self.path.relative_to(REPO)
        except ValueError:
            shown = self.path
        return f"{shown}:{self.line}: lab-lint/{self.rule}: {self.detail}"


# ---------------------------------------------------------------- source masking


def mask(src: str) -> str:
    """The source with every comment and string literal blanked to spaces, newlines and offsets kept.

    String templates' `${…}` code is blanked too: a lab finding never lives inside a message.
    """
    out = list(src)
    i, n = 0, len(src)
    # Stack of contexts: "code" (with a brace depth, for a template's `${ … }`), "str", "raw".
    stack: list[list] = [["code", 0]]

    def blank(a: int, b: int) -> None:
        for k in range(a, b):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        top = stack[-1]
        c = src[i]
        if top[0] == "code":
            if src.startswith("//", i):
                j = src.find("\n", i)
                j = n if j < 0 else j
                blank(i, j)
                i = j
                continue
            if src.startswith("/*", i):
                depth, j = 1, i + 2
                while j < n and depth:
                    if src.startswith("/*", j):
                        depth, j = depth + 1, j + 2
                    elif src.startswith("*/", j):
                        depth, j = depth - 1, j + 2
                    else:
                        j += 1
                blank(i, j)
                i = j
                continue
            if src.startswith('"""', i):
                stack.append(["raw", 0])
                i += 3
                continue
            if c == '"':
                stack.append(["str", 0])
                i += 1
                continue
            if c == "'":
                j = i + 1
                while j < n and src[j] != "'":
                    j += 2 if src[j] == "\\" else 1
                blank(i + 1, j)
                i = j + 1
                continue
            if c == "{":
                top[1] += 1
            elif c == "}":
                if top[1] == 0 and len(stack) > 1:
                    stack.pop()  # the end of a template's `${ … }`
                    out[i] = " "
                    i += 1
                    continue
                top[1] -= 1
            i += 1
            continue
        # Inside a string literal.
        if top[0] == "str" and c == "\\":
            blank(i, min(i + 2, n))
            i += 2
            continue
        if src.startswith("${", i):
            blank(i, i + 2)
            stack.append(["code", 0])
            i += 2
            continue
        if top[0] == "str" and c == '"':
            stack.pop()
            i += 1
            continue
        if top[0] == "raw" and src.startswith('"""', i):
            while src.startswith('"', i + 3):  # a raw string may end in extra quotes: """a""""
                i += 1
            stack.pop()
            i += 3
            continue
        if c != "\n":
            out[i] = " "
        i += 1
    return "".join(out)


def line_of(src: str, offset: int) -> int:
    return src.count("\n", 0, offset) + 1


def matching(code: str, open_at: int) -> int:
    """The offset just past the bracket that closes the one at [open_at] (masked code only)."""
    pairs = {"(": ")", "{": "}", "[": "]"}
    want = [pairs[code[open_at]]]
    i = open_at + 1
    while i < len(code) and want:
        c = code[i]
        if c in pairs:
            want.append(pairs[c])
        elif c in ")}]" and c == want[-1]:
            want.pop()
        i += 1
    return i


def call_end(code: str, paren_at: int) -> int:
    """The end of a call whose `(` is at [paren_at], taking a trailing lambda `{ … }` with it."""
    j = matching(code, paren_at)
    k = j
    while k < len(code) and code[k] in " \t":
        k += 1
    if k < len(code) and code[k] == "{":
        return matching(code, k)
    return j


def innermost_open(code: str, start: int, at: int) -> str:
    """The innermost unclosed bracket between [start] and [at]."""
    stack: list[str] = []
    for c in code[start:at]:
        if c in "({[":
            stack.append(c)
        elif c in ")}]" and stack:
            stack.pop()
    return stack[-1] if stack else "{"


# ---------------------------------------------------------------- scenario structure


@dataclass
class Method:
    name: str
    is_test: bool
    ignored: bool
    head: int  # offset of the first annotation (or `fun`)
    body_start: int
    body_end: int


FUN = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?`?([\w ]+?)`?\s*\(")


def methods(code: str) -> list[Method]:
    found = []
    for m in FUN.finditer(code):
        paren = code.index("(", m.end() - 1)
        after = matching(code, paren)
        brace = code.find("{", after)
        # An abstract or one-line expression body (`= x`) without a block: nothing to lint inside.
        nxt_fun = FUN.search(code, after)
        if brace < 0 or (nxt_fun and brace > nxt_fun.start()):
            continue
        between = code[after:brace]
        if "\n\n" in between:
            continue
        head = m.start()
        # Walk back over the annotations and modifiers right above `fun`.
        lines_before = code[:head].split("\n")
        ann = head
        k = len(lines_before) - 2
        while k >= 0 and (lines_before[k].strip().startswith("@") or lines_before[k].strip() == ""):
            if lines_before[k].strip() == "":
                break
            ann -= len(lines_before[k]) + 1
            k -= 1
        annotations = code[ann:head]
        found.append(
            Method(
                name=m.group(1).strip(),
                is_test=bool(re.search(r"@Test\b", annotations)),
                ignored=bool(re.search(r"@Ignore\b", annotations)),
                head=ann,
                body_start=brace,
                body_end=matching(code, brace),
            ),
        )
    return found


def allowed(src_lines: list[str], line: int, rule: str) -> bool:
    """[rule] is allowed, with a reason, on [line] or in the comment block right above it."""

    def says(text: str) -> bool:
        m = ALLOW.search(text)
        return bool(m) and rule in {r.strip() for r in m.group(1).split(",")}

    if says(src_lines[line - 1]):
        return True
    k = line - 2
    while k >= 0:
        s = src_lines[k].strip()
        if not s.startswith(("//", "*", "/*", "@")):
            break
        if says(s):
            return True
        k -= 1
    return False


CONTINUES = set("=(,[{.&|+-*/?:<>!")


def statement_start(code: str, offset: int) -> int:
    """The offset of the first line of the statement holding [offset], so an allow above a wrapped call covers it.

    Walks back a line at a time while the line above leaves the statement open (more `(`/`[` opened than closed,
    or a trailing operator, `=`, `,`, `{` or `->`) or this line continues it (a leading `.`, `?.`, `)` or `]`),
    and stops at a blank line.
    """
    line_start = code.rfind("\n", 0, offset) + 1
    while line_start > 0:
        prev_start = code.rfind("\n", 0, line_start - 1) + 1
        prev = code[prev_start : line_start - 1].rstrip()
        if not prev.strip():
            break
        here = code[line_start:].lstrip(" \t")
        unclosed = sum(1 if c in "([" else -1 if c in ")]" else 0 for c in prev)
        if unclosed > 0 or prev[-1] in CONTINUES or here[:1] in ".)]" or here.startswith("?."):
            line_start = prev_start
            continue
        break
    return line_start


# ---------------------------------------------------------------- constants, for the clock-jump rule


_CONSTS: dict[str, list[str]] | None = None


def constants() -> dict[str, list[str]]:
    """`const val NAME = <expr>` across main and the lab: NAME → every distinct expression declared for it."""
    global _CONSTS
    if _CONSTS is None:
        table: dict[str, list[str]] = {}
        for root in CONST_ROOTS:
            for f in root.rglob("*.kt"):
                for m in CONST_DECL.finditer(mask_keep_numbers(f.read_text(encoding="utf-8"))):
                    exprs = table.setdefault(m.group(1), [])
                    if m.group(2).strip() not in exprs:
                        exprs.append(m.group(2).strip())
        _CONSTS = table
    return _CONSTS


def mask_keep_numbers(src: str) -> str:
    return re.sub(r"//[^\n]*", "", src)


CONST_DECL = re.compile(r"\bconst\s+val\s+(\w+)(?:\s*:\s*\w+)?\s*=\s*([^\n/]+)")


def local_constants(src: str) -> dict[str, str]:
    """The scenario file's own `const val`s, which win over the repo-wide table."""
    return {m.group(1): m.group(2).strip() for m in CONST_DECL.finditer(mask_keep_numbers(src))}


def evaluate(expr: str, local: dict[str, str] | None = None, depth: int = 0) -> int | None:
    """A millisecond expression: numeric literals, `+ - * /`, parentheses and known `const val`s; else None."""
    if depth > 8:
        return None
    local = local or {}
    flat = re.sub(r"(?<=\d)_(?=\d)", "", expr)
    flat = re.sub(r"(?<=\d)[lL]\b", "", flat)

    def name(m: re.Match) -> str:
        ident = m.group(0).split(".")[-1]
        exprs = [local[ident]] if ident in local else constants().get(ident, [])
        # A name several files declare is used only when every declaration comes to the same number.
        values = {evaluate(e, local, depth + 1) for e in exprs}
        if len(values) != 1 or None in values:
            raise KeyError(ident)
        return f"({values.pop()})"

    try:
        flat = re.sub(r"(?<![\w.])[A-Za-z_][\w.]*", name, flat)
    except KeyError:
        return None
    if not re.fullmatch(r"[\d\s+\-*/()]+", flat):
        return None
    try:
        # Safe: the fullmatch above leaves nothing but digits, whitespace, operators and parentheses.
        return int(eval(flat.replace("/", "//"), {"__builtins__": {}}))
    except (SyntaxError, ZeroDivisionError):
        return None


# ---------------------------------------------------------------- the rules


AWAITS = r"(?:\.await|\.tryAwait|awaitAcquainted|assertConverged|awaitCustodyParity|awaitReceipt|assert\w*)\s*\("
RELINKS = r"(?:lab\.link|\blinkAll|\.connect|\.restart|\.restoreFromBackup|\.reoffer|lab\.unlink)\s*\("


def lint_text(path: Path, src: str) -> list[Finding]:
    code = mask(src)
    lines = src.split("\n")
    out: list[Finding] = []

    def add(offset: int, rule: str, detail: str | None = None) -> None:
        line = line_of(code, offset)
        first = line_of(code, statement_start(code, offset))
        if not allowed(lines, line, rule) and not allowed(lines, first, rule):
            out.append(Finding(path, line, rule, detail or RULES[rule]))

    if path.name == FIXTURE:
        _plane_collectors(code, add)
        return sorted(set(out), key=lambda f: (str(f.path), f.line, f.rule))

    if path.name.endswith("LabTest.kt") and not re.search(r"@get:Rule\s+val\s+\w+\s*=\s*LabChaos\.rule\(\)", code):
        cls = re.search(r"\bclass\s+\w+", code)
        add(cls.start() if cls else 0, "missing-chaos-rule")

    for m in re.finditer(r"\b(?:delay|withTimeoutOrNull|withTimeout)\s*\(|\.sleep\s*\(|\blab\.settle\s*\(", code):
        add(m.start(), "sleep-as-sync")

    for m in re.finditer(r"\.transport\.disconnect\s*\(", code):
        add(m.start(), "direct-disconnect")

    for m in re.finditer(r"\brunTest\b|\badvanceTimeBy\b|\badvanceUntilIdle\b|\bStandardTestDispatcher\b|\bUnconfinedTestDispatcher\b", code):
        add(m.start(), "virtual-time")

    for m in re.finditer(r"@Ignore\b(\s*\()?", code):
        reason = src[m.end() : m.end() + 200] if m.group(1) else ""
        if not re.match(r'\s*"#\d+', reason):
            add(m.start(), "ignore-without-issue")

    all_methods = methods(code)
    helpers = {m.name: m for m in all_methods if not m.is_test}

    for meth in all_methods:
        body = code[meth.body_start : meth.body_end]
        base = meth.body_start
        _dropped_awaits(code, base, body, add)
        _release_then_hold(code, base, body, add)
        _filtered_release(code, base, body, add)
        _relay_counts(code, base, body, add)
        if meth.is_test:
            _clock_jumps(src, code, base, body, add, helpers)
            if not meth.ignored and not _reaches_oracle(code, meth, helpers, set()):
                add(meth.head, "missing-oracle", f"`{meth.name}` never reaches `assertConverged`. " + RULES["missing-oracle"])
    return sorted(set(out), key=lambda f: (str(f.path), f.line, f.rule))  # one finding per rule per line


def _dropped_awaits(code: str, base: int, body: str, add) -> None:
    for m in re.finditer(r"\btryAwait\s*\(", body):
        at = base + m.start()
        if innermost_open(code, base, at) != "{":
            continue  # an argument: its value is read
        q = at
        while q > base and (code[q - 1].isalnum() or code[q - 1] in "._?"):
            q -= 1
        k = q - 1
        while k > base and code[k] in " \t\n":
            k -= 1
        prev = code[k]
        word = re.search(r"(\w+)\s*$", code[base : k + 1])
        if prev in "=(,&|!<>+-*/?:[" or (word and word.group(1) in {"return", "else", "in", "is", "to", "throw"}):
            continue
        if prev == ">" and code[k - 1] == "-":
            continue  # a `when` branch's value
        after = call_end(code, base + m.end() - 1)
        nxt = code[after:].lstrip(" \t")
        wrapped = code[after:].lstrip()
        if nxt[:2] in {"||", "&&", "==", "!="} or wrapped[:1] in ".?":
            continue  # read by the expression it heads, on this line or wrapped onto the next (`.let`, `?:`)
        if nxt.lstrip()[:1] == "}":
            lam = _enclosing_brace(code, base, at)
            if lam is not None and innermost_open(code, base, lam) == "(":
                continue  # the value of a lambda passed as an argument: `assertTrue(run { … })`
        add(at, "dropped-await-result")


def _enclosing_brace(code: str, start: int, at: int) -> int | None:
    stack: list[int] = []
    for i in range(start, at):
        if code[i] in "({[":
            stack.append(i)
        elif code[i] in ")}]" and stack:
            stack.pop()
    for i in reversed(stack):
        if code[i] == "{":
            return i
    return None


def _pair(call: str) -> tuple[str, str] | None:
    m = re.match(r"(\w+)\.transport\.\w+\s*\(\s*(\w+)\.transport", call)
    return (m.group(1), m.group(2)) if m else None


LOOP_HEADS = re.compile(r"\b(?:forEach|forEachIndexed|repeat|for|while)\b\s*([({])")


def _loop_bodies(code: str, start: int, end: int):
    """The `{ … }` body of every loop between [start] and [end]: `forEach {`, `repeat(n) {`, `for (…) {`, `while (…) {`."""
    for m in LOOP_HEADS.finditer(code, start, end):
        brace = m.start(1)
        if code[brace] == "(":
            k = matching(code, brace)
            while k < len(code) and code[k] in " \t":
                k += 1
            if k >= len(code) or code[k] != "{":
                continue
            brace = k
        yield brace, matching(code, brace)


def _release_then_hold(code: str, base: int, body: str, add) -> None:
    """A pipe held again after a release that let go of it: the far side's answer crosses in between.

    An await between them does not close the gap; it widens it. A deliberate re-hold says so with an allow.
    """
    for m in re.finditer(r"\b(\w+)\.transport\.(release|releaseFiles)\s*\(", body):
        paren = base + m.end() - 1
        args_end = matching(code, paren)
        if re.search(r"keepHolding\s*=\s*true", code[paren:args_end]):
            continue
        pair = _pair(body[m.start() :])
        if not pair or "it" in pair:
            continue
        end = call_end(code, paren)
        hold = "hold" if m.group(2) == "release" else r"(?:holdFiles|stackFiles)"
        again = re.compile(rf"\b{pair[0]}\.transport\.{hold}\s*\(\s*{pair[1]}\.transport")
        if again.search(code[end : base + len(body)]):
            add(base + m.start(), "release-then-hold")
            continue
        # The loop's next pass: a hold of the same pipe earlier in the loop body that holds this release.
        for brace, close in _loop_bodies(code, base, base + len(body)):
            if brace < base + m.start() < close and again.search(code[brace : base + m.start()]):
                add(base + m.start(), "release-then-hold")
                break


# What a `release` reorder may do to its batch. Anything not shown to be a pure reorder counts as dropping frames.
REORDERS = {"reversed", "asReversed", "sorted", "sortedBy", "sortedByDescending", "sortedDescending", "sortedWith", "shuffled"}
DROPS = re.compile(
    r"\.(?:drop|take|dropLast|takeLast|dropWhile|takeWhile|dropLastWhile|takeLastWhile|subList|slice|filterIndexed|"
    r"filterIsInstance|filterNotNull|distinct|distinctBy|first|last|single|firstOrNull|lastOrNull|singleOrNull|minus|"
    r"chunked|windowed)\b|\blistOf\s*\(|\bemptyList\s*\(|\s-\s",
)


def _drops_frames(code: str, paren: int, end: int) -> bool:
    """A `release` reorder that may leave a held frame out: anything but `reversed`/`sorted*` and filter/filterNot pairs."""
    lam = code.find("{", matching(code, paren) - 1)
    inner = code[paren:end] if lam < 0 or lam >= end else code[lam:end]
    # No trailing lambda: a reorder passed as a named argument, or none at all (a plain release drops nothing).
    if (lam < 0 or lam >= end) and not re.search(r"\breorder\s*=", code[paren:end]):
        return False
    if DROPS.search(inner):
        return True
    if re.fullmatch(r"\{\s*(?:it|(\w+)\s*->\s*\1)\s*\}", inner.strip()):
        return False  # the identity
    parts = list(re.finditer(r"val\s*\(\s*(\w+)\s*,\s*(\w+)\s*\)\s*=\s*[^\n]*\.partition\b", inner))
    for part in parts:
        after = inner[part.end() :]
        if not all(re.search(rf"\b{name}\b", after) for name in part.groups()):
            return True  # a partition whose one half is never used again drops it
    if re.search(r"\.(?:filter|filterNot)\s*\{", inner):
        return not _partition_only(code, paren, end)
    called = set(re.findall(r"\.(\w+)\s*[({]", inner)) | set(re.findall(r"\.(\w+)\s*\(\s*\)", inner))
    return not (called & REORDERS or parts)


def _filtered_release(code: str, base: int, body: str, add) -> None:
    for m in re.finditer(r"\b\w+\.transport\.release\s*\(", body):
        paren = base + m.end() - 1
        end = call_end(code, paren)
        if not _drops_frames(code, paren, end):
            continue
        rest = code[end : base + len(body)]
        oracle = re.search(r"\bassertConverged\s*\(", rest)
        if oracle and not re.search(RELINKS, rest[: oracle.start()]):
            add(base + m.start(), "filtered-release-then-oracle")


def _partition_only(code: str, start: int, end: int) -> bool:
    """Every `filter { p }` in the call is matched by a `filterNot { p }`, the batch whole again."""
    kept: dict[str, list[str]] = {"filter": [], "filterNot": []}
    for m in re.finditer(r"\.(filter|filterNot)\s*\{", code[start:end]):
        brace = start + m.end() - 1
        kept[m.group(1)].append(" ".join(code[brace + 1 : matching(code, brace) - 1].split()))
    return bool(kept["filter"]) and sorted(kept["filter"]) == sorted(kept["filterNot"])


def _relay_counts(code: str, base: int, body: str, add) -> None:
    """Only inside a wait: a counter read as the asserted outcome is the scenario's subject, not its sync."""
    for w in re.finditer(r"\b(?:await|tryAwait)\s*\(", body):
        paren = base + w.end() - 1
        span = code[paren : call_end(code, paren)]
        c = re.search(r"\b(?:framesRelayed|framesOriginated|framesSuppressed)\b", span)
        if c:
            add(paren + c.start(), "relay-count-sync")


def _jump_events(code: str, src: str, start: int, end: int, helpers: dict[str, Method], local: dict, seen: frozenset):
    """The jumps and sweeps between [start] and [end] in order, a call to a file-local helper expanded in place.

    Yields `("jump", offset, ms_or_None, expr)` and `("sweep", offset, None, "")`.
    """
    names = [n for n in helpers if n not in seen]
    pat = r"\bclock\.advance\s*\(|\.(?:heal|restart|restoreFromBackup)\s*\("
    if names:
        pat += "|\\b(" + "|".join(re.escape(n) for n in names) + r")\s*\("
    for m in re.finditer(pat, code[start:end]):
        at = start + m.start()
        if m.group(0).startswith("clock"):
            paren = start + m.end() - 1
            expr = src[paren + 1 : matching(code, paren) - 1]
            yield ("jump", at, evaluate(expr, local), expr.strip())
        elif m.lastindex and m.group(m.lastindex):
            helper = helpers[m.group(m.lastindex)]
            if code[helper.head : at].count("\n") == 0 and helper.body_start <= at:
                continue  # the helper's own declaration
            yield from _jump_events(code, src, helper.body_start, helper.body_end, helpers, local, seen | {helper.name})
        else:
            yield ("sweep", at, None, "")


def _clock_jumps(src: str, code: str, base: int, body: str, add, helpers: dict[str, Method]) -> None:
    """Jumps past 48 h in all (or one that cannot be sized) with a ratchet sweep after them, helpers followed.

    The sweep is `heal()`'s and the one every start runs (`MeshManager.sweepKeyRetention`, so `restart()` and
    `restoreFromBackup()` too). A scenario that never sweeps after the bound is crossed (a label fade, say) may jump
    a week.
    """
    events = list(_jump_events(code, src, base, base + len(body), helpers, local_constants(src), frozenset()))
    total, flagged_at, why = 0, None, ""
    for kind, at, ms, expr in events:
        if kind == "jump":
            if ms is None:
                flagged_at, why = flagged_at or at, why or f"cannot size `{expr}`, and a sweep follows. "
                continue
            total += ms
            if total > MAX_JUMP_MS and flagged_at is None:
                flagged_at, why = at, f"the jumps reach {total / HOUR_MS:.1f} h here. "
        elif flagged_at is not None:
            add(flagged_at, "clock-jump-over-48h", why + RULES["clock-jump-over-48h"])
            return


def _plane_collectors(code: str, add) -> None:
    """Each child of a `CompositeMeshTransport(listOf…(…))` is read `.collecting` in a `while (…)` wait's condition.

    A bare `collecting` in a condition reads the class's own `val collecting` getter, so the wait may name the children
    through it. The radio's `transport` is a child like the others.
    """
    getter = re.search(r"\bval\s+collecting\s*:\s*Boolean\s*get\s*\(\s*\)\s*=\s*([^\n]+)", code)
    waited = ""
    for w in re.finditer(r"\bwhile\s*\(", code):
        cond = code[w.end() : matching(code, w.end() - 1) - 1]
        if re.search(r"(?<![.\w])collecting\b", cond) and getter:
            cond += " " + getter.group(1)
        waited += cond + "\n"
    for m in re.finditer(r"\bCompositeMeshTransport\s*\(\s*listOf(?:NotNull)?\s*\(", code):
        args, depth, cur = [], 0, ""
        for c in code[m.end() : matching(code, m.end() - 1) - 1]:
            depth += c in "([{"
            depth -= c in ")]}"
            if c == "," and depth == 0:
                args.append(cur.strip())
                cur = ""
            else:
                cur += c
        args.append(cur.strip())
        for arg in filter(None, args):
            # An inline expression has no name a wait could read: name the child first.
            if not re.fullmatch(r"[A-Za-z_]\w*", arg) or not re.search(rf"\b{re.escape(arg)}\s*\??\.collecting\b", waited):
                add(m.start(), "plane-collectors-at-boot", f"`{arg}` is never awaited `collecting`. " + RULES["plane-collectors-at-boot"])


def _reaches_oracle(code: str, meth: Method, helpers: dict[str, Method], seen: set[str]) -> bool:
    body = code[meth.body_start : meth.body_end]
    if re.search(r"\bassertConverged\s*\(", body):
        return True
    for name, helper in helpers.items():
        if name in seen or helper is meth:
            continue
        if re.search(rf"\b{re.escape(name)}\s*\(", body):
            seen.add(name)
            if _reaches_oracle(code, helper, helpers, seen):
                return True
    return False


# ---------------------------------------------------------------- entry points


def lint(paths: list[Path]) -> list[Finding]:
    out: list[Finding] = []
    for p in paths:
        out += lint_text(p, p.read_text(encoding="utf-8"))
    return out


def scenario_files() -> list[Path]:
    return sorted(LAB.glob("*LabTest.kt"))


def hook() -> int:
    """PostToolUse: lint the scenario file the edit touched; exit 2 hands the findings back to the agent."""
    try:
        payload = json.load(sys.stdin)
    except ValueError:
        return 0
    raw = (payload.get("tool_input") or {}).get("file_path") or ""
    path = Path(raw)
    if not raw or not (path.name.endswith("LabTest.kt") or path.name == FIXTURE) or not path.is_file():
        return 0
    try:
        path.resolve().relative_to(LAB.resolve())
    except ValueError:
        return 0
    findings = lint([path])
    if not findings:
        return 0
    print("\n".join(f.render() for f in findings), file=sys.stderr)
    print(
        f"lab-lint: {len(findings)} finding(s) in {path.name}. Fix each, or allow it where it stands with "
        "`// lab-lint: allow <rule> — <why>` (scripts/lab-lint.py --rules).",
        file=sys.stderr,
    )
    return 2


def main(argv: list[str]) -> int:
    if "--rules" in argv:
        for rule, why in RULES.items():
            print(f"{rule}\n    {why}\n")
        return 0
    if "--hook" in argv:
        return hook()
    paths = [Path(a).resolve() for a in argv if not a.startswith("--")] or scenario_files() + [LAB / FIXTURE]
    findings = lint(paths)
    for f in findings:
        print(f.render())
    word = "lab file" if len(paths) == 1 else "lab files"
    print(f"lab-lint: {len(findings)} finding(s) in {len(paths)} {word}" + ("" if findings else ", clean"))
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
