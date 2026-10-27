#!/usr/bin/env python3
"""Mint, index and lint architecture decision records.

    python3 scripts/adr.py init [--dir PATH] [--no-vendor]
    python3 scripts/adr.py new "<title>" [--topics a,b,c]
    python3 scripts/adr.py index [--check]
    python3 scripts/adr.py lint
    python3 scripts/adr.py where                # the decisions dir, for hooks and CI

Every subcommand also takes `--root PATH` (default: the nearest directory above the
cwd holding `.git`) and `--dir PATH` (default: DEFAULT_DIR below, or `$ADR_DIR`).

Decisions live one per file in the decisions directory, and `<dir>.md` beside it is a
**generated** router over them. Language-agnostic on purpose: stdlib only, no build
system, so the same file serves a Gradle, Go, Cargo, npm or Python repo, and a CI job
can run it in any image that has `python3` (the lintorama image does).

WHY ONE FILE PER DECISION, AND A MINTED ID. Appending `## NNN.` sections to one long
file, with the number chosen by reading the current maximum, collides twice for every
pair of branches that record a decision the same day: once on the number (both read
067, both take 068) and once on the append. Nobody picks a number here, so nothing
collides, and the router's only churn is appended table rows, which `.gitattributes`
marks `merge=union`.

TWO ID FORMS COEXIST, PERMANENTLY. A repo adopting this may already cite `ADR 012` in
code and in commit messages, which cannot be rewritten; those sequence ids stay valid
(`001`, `0012`). Everything new is `YYYY-MM.suffix` (`2026-08.k3f9`). Cite whichever
form an ADR carries; never renumber an old one.

NO DECISION COUNT IN THE ROUTER. `merge=union` is right for a file whose only churn is
appended rows and wrong for any line that *changes*: two branches each adding an ADR
union into two count lines rather than conflicting, and the stale one never leaves. So
every generated line is append-only or constant. `index --check` catches whatever
union still gets wrong.

This is the canonical copy from the `adr` skill; `init` vendors it into the repo as
`scripts/adr.py` so CI and hooks never depend on anyone's home directory.
"""

from __future__ import annotations

import argparse
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from datetime import date as Date
from pathlib import Path

# `init --dir` rewrites this line in the vendored copy, so a repo that keeps its
# decisions elsewhere needs no flag on any later call.
DEFAULT_DIR = ".agents/memory/decisions"

# Where `init` vendors this script, and the command the router's preamble names. Fixed
# rather than derived from `__file__`: the preamble is compared byte-for-byte by
# `index --check`, so it must not depend on where the script was run from.
VENDORED_PATH = "scripts/adr.py"

LINTORAMA_IMAGE = "zaventh/lintorama:9"

# Suffix alphabet: Crockford-ish base32 minus the characters that are misread aloud or
# mistyped out of a code comment — 0/O, 1/l/I. 31^4 ~= 923k, so two branches minting on
# the same day collide with probability ~1e-6 (and `new` refuses to overwrite anyway).
SUFFIX_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"
SUFFIX_LENGTH = 4

# `001`, `0042`: a pre-adoption sequence. Never minted again.
LEGACY_ID = re.compile(r"^\d{3,4}$")
# `2026-08.k3f9`: what `new` mints.
DATED_ID = re.compile(rf"^\d{{4}}-\d{{2}}\.[{SUFFIX_ALPHABET}]{{{SUFFIX_LENGTH}}}$")

FRONTMATTER_KEYS = ("id", "slug", "title", "date", "topics")

# markdownlint MD026: the title becomes the H1, and a heading may not end in these.
HEADING_TRAILING_PUNCTUATION = ".,;:!?"

# The markdownlint style the house lintorama repos use, applied by `lint` when the repo
# has no `.mdlrc` of its own. It is mdl's `relaxed` exclusions (a style file cannot
# extend `relaxed`, so they are copied) plus the ones Keep-a-Changelog files and
# frontmatter-led agent files need. Anything that passes this also passes `relaxed`.
HOUSE_MDL_STYLE = """\
all
exclude_tag :whitespace
exclude_tag :line_length
exclude_rule 'MD006'
exclude_rule 'MD007'
exclude_rule 'MD033'
exclude_rule 'MD034'
exclude_rule 'MD040'
exclude_rule 'MD041'
exclude_rule 'MD047'
exclude_rule 'MD002'
exclude_rule 'MD029'
rule 'MD024', :allow_different_nesting => true
"""


class AdrError(Exception):
    """A malformed ADR or a refused command. Reported as `✗ <message>`, never a traceback."""


@dataclass(frozen=True)
class Adr:
    id: str
    slug: str
    title: str
    date: str  # ISO, YYYY-MM-DD
    topics: list[str]
    file: str  # basename, e.g. `2026-08-k3f9-the-cron-cap-is-a.md`


@dataclass(frozen=True)
class Layout:
    root: Path
    rel_dir: str  # repo-relative decisions directory, `/`-separated

    @property
    def decisions(self) -> Path:
        return self.root / self.rel_dir

    @property
    def rel_router(self) -> str:
        return f"{self.rel_dir}.md"

    @property
    def router(self) -> Path:
        return self.root / self.rel_router

    @property
    def folder(self) -> str:
        """The decisions directory's own name, as the router links to it."""
        return self.rel_dir.rsplit("/", 1)[-1]


# -------------------------------------------------------------------------- layout


def find_repo_root(start: Path) -> Path:
    """Walk up to the directory holding `.git` (a directory, or a worktree's file).

    Not the cwd: running this from a subdirectory would otherwise read a decisions
    directory that does not exist and report "0 decisions", which for a generator means
    happily truncating the router instead of failing.
    """
    directory = start.resolve()
    for candidate in (directory, *directory.parents):
        if (candidate / ".git").exists():
            return candidate
    raise AdrError(f"no .git above {directory} — pass --root")


def resolve_layout(args: argparse.Namespace) -> Layout:
    root = Path(args.root).resolve() if args.root else find_repo_root(Path.cwd())
    rel_dir = (args.dir or os.environ.get("ADR_DIR") or DEFAULT_DIR).strip("/")
    if Path(rel_dir).is_absolute() or ".." in Path(rel_dir).parts:
        raise AdrError(f'--dir must be a path inside the repo, got "{rel_dir}"')
    return Layout(root, rel_dir)


# ------------------------------------------------------------------------------ ids


def is_legacy_id(adr_id: str) -> bool:
    return bool(LEGACY_ID.match(adr_id))


def is_valid_id(adr_id: str) -> bool:
    return bool(LEGACY_ID.match(adr_id) or DATED_ID.match(adr_id))


def mint_id(today: Date) -> str:
    """Mint a fresh id. `today` is injected so callers and tests agree on the bucket."""
    suffix = "".join(secrets.choice(SUFFIX_ALPHABET) for _ in range(SUFFIX_LENGTH))
    return f"{today:%Y-%m}.{suffix}"


def file_name_for(adr_id: str, slug: str) -> str:
    """The filename an id + slug must have.

    The `.` in a dated id becomes `-`, so the name has exactly one extension:
    `2026-08.k3f9-foo.md` reads to plenty of tooling as a file called `2026-08` with a
    very long extension.
    """
    return f"{adr_id.replace('.', '-')}-{slug}.md"


def slug_for(title: str) -> str:
    """Kebab slug from a title: first clause, <=5 words, ASCII only."""
    first_clause = re.split(r"[:;—–,(]", title)[0]
    words = re.sub(r"[^a-z0-9]+", "-", first_clause.replace("`", "").lower())
    return "-".join([w for w in words.split("-") if w][:5])


def citation(adr_id: str) -> str:
    """How an ADR is cited in prose, code comments and commit messages."""
    return f"ADR {adr_id}"


def heading_for(adr_id: str, title: str) -> str:
    """The grep anchor every citation resolves through."""
    return f"# {citation(adr_id)} — {title}"


def title_problem(title: str) -> str | None:
    """Why a title cannot be used, or None. Each is a lint failure or a broken anchor."""
    if not title:
        return "the title is empty"
    if "\n" in title or "\r" in title:
        return "the title spans lines; the heading anchor must be one line"
    if title.startswith("#"):
        return "the title starts with `#`; `new` writes the heading itself"
    if title[-1] in HEADING_TRAILING_PUNCTUATION:
        return (
            f'the title ends in "{title[-1]}"; it becomes the H1, and markdownlint '
            "MD026 forbids trailing punctuation in a heading — state it as a claim "
            "without the stop"
        )
    return None


# ---------------------------------------------------------------------- read / parse


def _parse_frontmatter(text: str, file: str) -> dict[str, str]:
    """Parse the five-key frontmatter block.

    Deliberately strict: a typo in a key name has to be an error here, or it becomes a
    silently missing router row.
    """
    if not text.startswith("---\n"):
        raise AdrError(f"{file}: no frontmatter block")
    end = text.find("\n---\n", 3)
    if end == -1:
        raise AdrError(f"{file}: unterminated frontmatter")

    fields: dict[str, str] = {}
    for line in text[4:end].split("\n"):
        if not line.strip():
            continue
        key, colon, value = line.partition(":")
        if not colon:
            raise AdrError(f'{file}: unparseable frontmatter line "{line}"')
        fields[key.strip()] = value.strip()
    return fields


def _quote(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _unquote(value: str) -> str:
    """Invert `_quote`. Unquoted values (legacy files) pass through untouched."""
    if len(value) < 2 or not (value.startswith('"') and value.endswith('"')):
        return value
    return re.sub(r"\\(.)", r"\1", value[1:-1])


def _parse_topics(value: str, file: str) -> list[str]:
    if not (value.startswith("[") and value.endswith("]")):
        raise AdrError(f'{file}: topics must be an inline list, got "{value}"')
    return [t.strip() for t in value[1:-1].split(",") if t.strip()]


def _h1_count(text: str) -> int:
    """ATX `# ` headings outside fenced code, where `# comment` lines are legitimate."""
    count, fence = 0, None
    for line in text.split("\n"):
        marker = re.match(r"^\s{0,3}(`{3,}|~{3,})", line)
        if marker:
            if fence is None:
                fence = marker.group(1)[0]
            elif marker.group(1)[0] == fence:
                fence = None
        elif fence is None and re.match(r"^# ", line):
            count += 1
    return count


def read_adrs(layout: Layout) -> list[Adr]:
    """Read and validate every ADR file, in router order: the legacy sequence first,
    then dated ids by date.

    Every check here exists because its absence would produce a *plausible* router
    rather than a loud failure — a duplicate id silently shadows a decision, and a
    heading that disagrees with its frontmatter breaks the grep anchor every `ADR …`
    citation in the tree relies on.
    """
    if not layout.decisions.is_dir():
        raise AdrError(f"{layout.rel_dir}/ does not exist — run `adr.py init`")

    adrs: list[Adr] = []
    seen: dict[str, str] = {}

    for path in sorted(layout.decisions.glob("*.md")):
        file = path.name
        text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
        fields = _parse_frontmatter(text, file)

        for key in FRONTMATTER_KEYS:
            if key not in fields:
                raise AdrError(f'{file}: missing frontmatter key "{key}"')

        adr_id = _unquote(fields["id"])
        title = _unquote(fields["title"])
        slug, date = fields["slug"], fields["date"]

        if not is_valid_id(adr_id):
            raise AdrError(f'{file}: "{adr_id}" is not a valid ADR id')

        if adr_id in seen:
            raise AdrError(f'duplicate ADR id "{adr_id}": {seen[adr_id]} and {file}')
        seen[adr_id] = file

        expected = file_name_for(adr_id, slug)
        if file != expected:
            raise AdrError(f"{file}: id + slug say the filename should be {expected}")

        if not re.match(r"^\d{4}-\d{2}-\d{2}$", date):
            raise AdrError(f'{file}: date "{date}" is not YYYY-MM-DD')

        problem = title_problem(title)
        if problem:
            raise AdrError(f"{file}: {problem}")

        # The grep anchor. Every `ADR …` citation resolves by finding this line, so it
        # must match the frontmatter exactly.
        heading = heading_for(adr_id, title)
        if f"\n{heading}\n" not in text:
            raise AdrError(f'{file}: expected heading "{heading}"')

        # markdownlint's MD025 misses a second H1 below frontmatter, and a second
        # `# ADR …` line would give a citation two places to land.
        h1s = _h1_count(text)
        if h1s != 1:
            raise AdrError(f"{file}: {h1s} top-level headings; the anchor must be the only one")

        adrs.append(
            Adr(adr_id, slug, title, date, _parse_topics(fields["topics"], file), file)
        )

    def order(adr: Adr) -> tuple:
        if is_legacy_id(adr.id):
            return (0, int(adr.id), "", "")
        return (1, 0, adr.date, adr.id)

    return sorted(adrs, key=order)


# ------------------------------------------------------------------------- render


def render(layout: Layout, adrs: list[Adr]) -> str:
    """The router: a constant preamble, then nothing but table rows.

    Every line is constant or append-only, as `merge=union` assumes (see the module
    docstring for why there is no count).
    """
    folder = layout.folder
    lines = [
        "# Architecture decision records",
        "",
        "The load-bearing decisions and *why* they hold. One file each, in",
        f"[`{folder}/`]({folder}/) — this page is the router. Open the file whose",
        "row matches; do not guess from the title alone. Supersede a decision by",
        "writing a new ADR and changing the old one's `Status:` line, never by",
        "deleting it.",
        "",
        "**This file is generated. Do not edit it by hand.** Add a decision with",
        f'`python3 {VENDORED_PATH} new "<title>" --topics a,b`, write the body,',
        f"then `python3 {VENDORED_PATH} index`.",
        "",
        "**Ids are minted, not chosen.** A new ADR is `YYYY-MM.suffix`",
        "(`ADR 2026-08.k3f9`) with a random suffix, so parallel branches never take",
        "the same number. Cite an ADR by its id, exactly as its heading spells it.",
    ]

    legacy = [a.id for a in adrs if is_legacy_id(a.id)]
    if legacy:
        span = f"`{legacy[0]}`" if len(legacy) == 1 else f"`{legacy[0]}`–`{legacy[-1]}`"
        lines += [
            f"ADRs {span} predate that scheme and keep their sequence numbers,",
            "because they are already cited in code and commit messages. Never",
            "renumber one.",
        ]

    lines += ["", "| ADR | Decision | Topics |", "| --- | --- | --- |"]
    for adr in adrs:
        title = adr.title.replace("|", "\\|")
        lines.append(
            f"| [{adr.id}]({folder}/{adr.file}) | {title} | {', '.join(adr.topics)} |"
        )
    lines.append("")
    return "\n".join(lines)


def scaffold(adr_id: str, slug: str, title: str, today: Date, topics: list[str]) -> str:
    # The body's placeholders are bold-led paragraphs, not headings: a paragraph that is
    # *only* bold text trips MD036, so each label shares its line with prose.
    return f"""---
id: {_quote(adr_id)}
slug: {slug}
title: {_quote(title)}
date: {today:%Y-%m-%d}
topics: [{", ".join(topics)}]
---

{heading_for(adr_id, title)}

Status: Accepted ({today:%Y-%m-%d})

**Context:** what was observed, and what it was mistaken for. Numbers where there
are numbers.

**Decision:** what changed, and what the alternative was, including the one a
reader would reach for first and why it does not work here.

**Consequences / limits:** what this costs, what it does not cover, and the trap
the next person will reach for. Name the test or check that keeps it true.
"""


# ------------------------------------------------------------------------ commands


def command_new(args: argparse.Namespace) -> int:
    layout = resolve_layout(args)
    title = " ".join(args.title).strip()

    problem = title_problem(title)
    if problem:
        raise AdrError(problem)

    slug = slug_for(title)
    if not slug:
        raise AdrError(f'could not derive an ASCII slug from "{title}"')

    if not layout.decisions.is_dir():
        raise AdrError(f"{layout.rel_dir}/ does not exist — run `adr.py init` first")

    topics = [t.strip() for t in (args.topics or "").split(",") if t.strip()]
    bad = [t for t in topics if not re.match(r"^[a-z0-9][a-z0-9-]*$", t)]
    if bad:
        raise AdrError(f"topics are lowercase kebab words, got {', '.join(bad)}")

    today = Date.today()
    adr_id = mint_id(today)
    file = file_name_for(adr_id, slug)
    path = layout.decisions / file

    # Only reachable on a ~1e-6 suffix collision, but a silent overwrite here destroys
    # another branch's decision.
    if path.exists():
        raise AdrError(f"{file} already exists — rerun to mint a different suffix")

    path.write_text(scaffold(adr_id, slug, title, today, topics), encoding="utf-8")

    # Deliberately does NOT run `index`: the router should list a decision once it is
    # written, not while it is a stub, and a stub committed by accident is easier to
    # notice when it is absent from the router.
    print(f"✓ {layout.rel_dir}/{file}")
    print(f"  cite as:  ({citation(adr_id)})")
    print("  next:     write the body, then run `index`")
    return 0


def _decisions(adrs: list[Adr]) -> str:
    return f"{len(adrs)} decision{'' if len(adrs) == 1 else 's'}"


def command_index(args: argparse.Namespace) -> int:
    layout = resolve_layout(args)
    adrs = read_adrs(layout)
    rendered = render(layout, adrs)
    current = layout.router.read_text(encoding="utf-8") if layout.router.exists() else None

    if current == rendered:
        print(f"✓ {layout.rel_router} is up to date ({_decisions(adrs)})")
        return 0

    if args.check:
        print(f"✗ {layout.rel_router} is stale — run `adr.py index`", file=sys.stderr)
        return 1

    layout.router.write_text(rendered, encoding="utf-8")
    print(f"✓ wrote {layout.rel_router} ({_decisions(adrs)})")
    return 0


GITATTRIBUTES_BLOCK = """\
# `{router}` is a generated router (`python3 {script} index`) whose only
# churn is appended table rows, one per new ADR. Branches that each add a decision
# conflict on that append every time, and the resolution is always "keep both rows";
# `merge=union` does that automatically. If union ever produces something odd, rerun
# `index` and it overwrites the file wholesale; `index --check` proves the committed
# router matches the files it describes.
{router} merge=union
"""


def command_init(args: argparse.Namespace) -> int:
    layout = resolve_layout(args)
    done: list[str] = []

    if not layout.decisions.is_dir():
        layout.decisions.mkdir(parents=True)
        done.append(f"created {layout.rel_dir}/")

    # The router is written through the same validation `index` uses, so init on a repo
    # that already has malformed ADRs fails here rather than writing around them.
    rendered = render(layout, read_adrs(layout))
    if not layout.router.exists():
        layout.router.write_text(rendered, encoding="utf-8")
        done.append(f"wrote {layout.rel_router}")
    elif layout.router.read_text(encoding="utf-8") != rendered:
        done.append(f"{layout.rel_router} exists and differs — review, then run `index`")

    attributes = layout.root / ".gitattributes"
    existing = attributes.read_text(encoding="utf-8") if attributes.exists() else ""
    rule = f"{layout.rel_router} merge=union"
    if not any(line.strip() == rule for line in existing.splitlines()):
        block = GITATTRIBUTES_BLOCK.format(router=layout.rel_router, script=VENDORED_PATH)
        separator = "" if not existing else ("\n" if existing.endswith("\n") else "\n\n")
        attributes.write_text(existing + separator + block, encoding="utf-8")
        done.append(f"marked {layout.rel_router} merge=union in .gitattributes")

    if not args.no_vendor:
        done.append(_vendor(layout))

    for line in done:
        print(f"✓ {line}")
    print("  next: wire the commands, CI and the pre-commit check (see the skill's")
    print("        references/wiring.md), then route agents to the router")
    return 0


def _vendor(layout: Layout) -> str:
    """Copy this script into the repo, pinned to the repo's decisions directory."""
    source = Path(__file__).resolve()
    target = layout.root / VENDORED_PATH

    text = source.read_text(encoding="utf-8")
    pin = f"DEFAULT_DIR = {_quote(DEFAULT_DIR)}"
    if pin not in text:
        raise AdrError(f"cannot find `{pin}` in {source} to pin the directory")
    text = text.replace(pin, f"DEFAULT_DIR = {_quote(layout.rel_dir)}", 1)

    if target.exists():
        if target.resolve() == source:
            return f"{VENDORED_PATH} is this script"
        if target.read_text(encoding="utf-8") == text:
            return f"{VENDORED_PATH} is current"
        return f"{VENDORED_PATH} exists and differs — left alone; diff it against {source}"

    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")
    target.chmod(0o755)
    return f"vendored {VENDORED_PATH}"


def command_lint(args: argparse.Namespace) -> int:
    """Run lintorama's markdownlint (and editorconfig-checker) over the ADR files.

    Uses the repo's own `.mdlrc` when it has one — that is what lintorama will apply in
    CI — and the house style otherwise. Exit 3 means nothing ran, which is not a pass.
    """
    layout = resolve_layout(args)
    read_adrs(layout)  # a malformed ADR is a harder failure than a lint finding

    files = [layout.rel_router] if layout.router.exists() else []
    files += [f"{layout.rel_dir}/{p.name}" for p in sorted(layout.decisions.glob("*.md"))]
    if not files:
        print("✓ nothing to lint")
        return 0

    has_mdlrc = (layout.root / ".mdlrc").exists()
    has_editorconfig = (layout.root / ".editorconfig").exists()

    with tempfile.TemporaryDirectory() as tmp:
        style = Path(tmp) / "house.rb"
        style.write_text(HOUSE_MDL_STYLE, encoding="utf-8")
        runner = _lint_runner(layout.root, Path(tmp))
        if runner is None:
            print(
                f"✗ lint did not run: needs docker (image {LINTORAMA_IMAGE}) or a local "
                "`mdl` on PATH",
                file=sys.stderr,
            )
            return 3

        mdl_style = [] if has_mdlrc else ["-s", runner.style_path(style)]
        status = runner.run(["mdl", *mdl_style, *files])
        if has_editorconfig:
            status |= runner.run(["editorconfig-checker", *files])

    source = ".mdlrc" if has_mdlrc else "the house style (no .mdlrc in the repo)"
    if status == 0:
        print(f"✓ {len(files)} files pass markdownlint under {source}")
        return 0
    print(f"✗ lint findings above (markdownlint under {source})", file=sys.stderr)
    return 1


class _LintRunner:
    def __init__(self, root: Path, tmp: Path, docker: bool):
        self.root, self.tmp, self.docker = root, tmp, docker

    def style_path(self, style: Path) -> str:
        return "/adr-lint/house.rb" if self.docker else str(style)

    def run(self, argv: list[str]) -> int:
        if self.docker:
            entrypoint, rest = argv[0], argv[1:]
            argv = [
                "docker", "run", "--rm",
                "-v", f"{self.root}:/code:ro",
                "-v", f"{self.tmp}:/adr-lint:ro",
                "-w", "/code",
                "--entrypoint", entrypoint,
                LINTORAMA_IMAGE, *rest,
            ]
        elif shutil.which(argv[0]) is None:
            print(f"  (skipped {argv[0]}: not on PATH)", file=sys.stderr)
            return 0
        return 1 if subprocess.run(argv, cwd=self.root, check=False).returncode else 0


def _lint_runner(root: Path, tmp: Path) -> _LintRunner | None:
    """Prefer the pinned lintorama image (what CI runs); fall back to a local mdl."""
    if shutil.which("docker"):
        quiet = {"stdout": subprocess.DEVNULL, "stderr": subprocess.DEVNULL}
        cached = subprocess.run(
            ["docker", "image", "inspect", LINTORAMA_IMAGE], check=False, **quiet
        )
        if cached.returncode == 0 or subprocess.run(
            ["docker", "pull", LINTORAMA_IMAGE], check=False, **quiet
        ).returncode == 0:
            return _LintRunner(root, tmp, docker=True)
    if shutil.which("mdl"):
        return _LintRunner(root, tmp, docker=False)
    return None


def command_where(args: argparse.Namespace) -> int:
    """Print the repo-relative decisions directory, for hooks and CI to read rather
    than restate. The router is the same path plus `.md`."""
    print(resolve_layout(args).rel_dir)
    return 0


# --------------------------------------------------------------------------- main


def main(argv: list[str]) -> int:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--root", help="repository root (default: nearest .git above cwd)")
    common.add_argument(
        "--dir", help=f"decisions directory, repo-relative (default: {DEFAULT_DIR})"
    )

    parser = argparse.ArgumentParser(prog="adr.py", description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)

    init = sub.add_parser("init", parents=[common], help="set up a repo (idempotent)")
    init.add_argument(
        "--no-vendor", action="store_true", help=f"do not copy this script to {VENDORED_PATH}"
    )
    init.set_defaults(func=command_init)

    new = sub.add_parser("new", parents=[common], help="scaffold a decision with a minted id")
    new.add_argument("title", nargs="+", help="the decision, stated as a claim")
    new.add_argument("--topics", default="", help="comma-separated router topics")
    new.set_defaults(func=command_new)

    index = sub.add_parser("index", parents=[common], help="regenerate the router")
    index.add_argument("--check", action="store_true", help="fail instead of writing (CI / hooks)")
    index.set_defaults(func=command_index)

    where = sub.add_parser("where", parents=[common], help="print the decisions directory")
    where.set_defaults(func=command_where)

    lint = sub.add_parser("lint", parents=[common], help="markdownlint the ADRs via lintorama")
    lint.set_defaults(func=command_lint)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except AdrError as error:
        print(f"✗ {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
