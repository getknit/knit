---
name: adr
description: Record, index, cite and lint architecture decision records (ADRs) with a convention built not to collide — one file per decision in `.agents/memory/decisions/`, a minted id (`ADR 2026-09.k3f9`) instead of a sequence number, a strict five-key frontmatter, a `# ADR <id> — <title>` grep anchor, a generated router (`decisions.md`, `merge=union`) that CI checks, and markdown that passes markdownlint (mdl) as lintorama runs it. Works in any repo, whatever the language (Android/Gradle, Go, Python, Rust, npm), through one stdlib-only Python script. Use when the user asks to record, write, add or supersede a decision or an ADR; to set up, adopt or port the ADR convention in a repo; to regenerate or check the decisions router; or when a task makes an architectural or product-scope choice in a repo that already has `.agents/memory/decisions/`.
license: MIT
compatibility: Requires Python 3.8 or later (stdlib only) and a git checkout. `lint` needs docker with the zaventh/lintorama:9 image, or a local mdl on PATH. Works in any agent that can run shell commands.
metadata:
  author: Jeffrey Walter Mixon
  version: "1.0.0"
  tested-with: "Python 3.13, zaventh/lintorama:9 (mdl 0.18.1)"
---

# ADR: decision records that do not collide

One decision per file, an id nobody chooses, and a generated router. The convention exists because sequence
numbers collide: parallel branches all read the same "next number", all take it, and then conflict again on the
append to one long file. A minted id (`YYYY-MM.` plus a random four-character suffix) removes the choice, so nothing
collides. The router's only churn is appended rows, which `merge=union` resolves.

The tool is [`scripts/adr.py`](scripts/adr.py) in this skill: stdlib Python 3.8+, no build system, the same file in
every ecosystem. `init` vendors it into the repo at `scripts/adr.py`.

## First, find the command this repo uses

Check before running anything. A repo may already own this convention:

| The repo has | Use |
| --- | --- |
| `scripts/adr.py` | `python3 scripts/adr.py …` (vendored by `init`, or the repo's own) |
| `package.json` scripts `adr:new` / `adr:index` | `npm run adr:new -- "<title>"`, `npm run adr:index` |
| a `Makefile` / `justfile` with `adr-*` targets | those targets |
| none of the above | set it up (see [Setting up a repo](#setting-up-a-repo)) |

**A repo's own ADR tooling wins over this skill's copy**; the writing and lint rules below still apply. The rest of
this file writes `$ADR` for whichever command applies. A repo's copy can predate `lint` and `where`: run those two
from this skill's `scripts/adr.py` with `--root <repo>`, since they only read the ADR files. Never run this copy's
`index` over a repo that has its own: its router preamble may differ, and `index --check` in CI would then fail.

## Recording a decision

1. **Consult first.** Read the router (`.agents/memory/decisions.md`) for prior choices in the area. If one exists,
   the new ADR supersedes it rather than silently contradicting it.
2. **Mint:** `$ADR new "<title>" --topics a,b`. It prints the file path and the citation string,
   `(ADR 2026-09.k3f9)`.
3. **Write the body** (see [Writing one](#writing-one)). Never edit `id`, `slug` or the heading by hand; if the
   title must change, change `title:` and the heading together, since `index` refuses a mismatch.
4. **Index:** `$ADR index` regenerates the router. Never hand-edit the router.
5. **Lint:** `$ADR lint` runs lintorama's markdownlint over the ADRs under the repo's `.mdlrc` (or the strict
   default style if the repo has none). Exit 3 means it could not run (no docker, no `mdl`); say so, and do not
   report it as passing.
6. **Route it.** If the next task in that area would need this decision, add a trigger row to
   `.agents/rules/decisions.md`, or an entry wherever the repo routes from (`AGENTS.md`, a per-subsystem context
   file).
7. **Cite it** where the decision bites: a code comment at the guarded line, the commit body. Write `ADR <id>`
   exactly as the heading spells it.

`new` deliberately does not run `index`. A stub should not appear in the router, and a stub committed by accident is
easier to spot when it is missing from the router.

## The file format

`index` enforces all of this and fails loudly rather than write a plausible router:

```markdown
---
id: "2026-09.k3f9"
slug: the-cache-is-keyed-on
title: "the cache is keyed on the whole Accept string"
date: 2026-09-23
topics: [caching, http]
---

# ADR 2026-09.k3f9 — the cache is keyed on the whole Accept string

Status: Accepted (2026-09-23)

**Context:** …

**Decision:** …

**Consequences / limits:** …
```

- **Filename** is `<id with . → ->-<slug>.md`: one extension, and `ls` sorts by month.
- **Slug** is the title's first clause, at most five words, ASCII kebab. Put the distinctive words first.
- **Frontmatter** has exactly these five keys. `id` and `title` are double-quoted; `topics` is an inline list of
  lowercase kebab words.
- **The heading is the grep anchor.** `# ADR <id> — <title>` (em dash) must match the frontmatter byte for byte, and
  must be the file's only H1. Every citation resolves by grepping for it.
- **Legacy ids** (`001`, `0042`) predate the convention and stay valid forever: they are cited in commit messages
  that cannot be rewritten. Never renumber one, and never mint one.
- **No decision count in the router.** Under `merge=union` a changing line unions into duplicates instead of
  conflicting. Every generated line is constant or append-only.

## Writing one

**Title: the decision, stated as a claim.** "the watch sweeps a window, not the whole catalog", not "Watch sweep
design". A lowercase first word is the idiom. No trailing `.` `,` `;` `:` `!` `?`: the title becomes the H1, and
markdownlint MD026 forbids trailing punctuation in a heading (`new` refuses it).

**Body: the decision and what it cost.** Keep the scaffold's three labels:

- **Context**: what was observed, and what it was mistaken for. Numbers where there are numbers, with how they were
  measured.
- **Decision**: what changed, and the alternative, including the one a reader would reach for first and why it does
  not work here.
- **Consequences / limits**: what it costs, what it does not cover, the trap the next person will hit. **Name the
  test, gate or check that keeps it true.** A decision with no named guard is the one the next refactor undoes
  unnoticed.

**Every count is point-in-time.** A number in an ADR is evidence for that day's decision, never the current figure.
Do not quote an ADR's count as current anywhere.

**Superseding:** write a new ADR that cites the old one, then change the old one's status to
`Status: Superseded by ADR <new id> (<date>)`. Never delete an ADR, and do not rewrite its body to match the new
decision.

### Markdown that passes lintorama

With no `.mdlrc` in the repo, `lint` applies mdl `all` minus whitespace, line length and a few rules
(`HOUSE_MDL_STYLE` in the script). It is stricter than mdl's `relaxed`, so an ADR that passes it passes a repo on
`relaxed` too. In an ADR body that means:

- One H1 only: the anchor. Sub-sections are `##`, then `###`; never skip a level (MD001, MD025).
- No trailing punctuation in **any** heading (MD026), and no two sibling headings with the same text (MD024).
- ATX headings (`## x`) only, surrounded by blank lines (MD003, MD022).
- Never a paragraph that is only bold or italic text; a label shares its line with prose (`**Decision:** …`), the
  way the scaffold writes it (MD036).
- Lists and fenced code blocks surrounded by blank lines (MD031, MD032); `-` bullets throughout (MD004); consistent
  indentation within a list (MD005).
- Fenced code only, never indented code blocks (MD046). No `$ ` prompt on commands shown without output (MD014).
- No horizontal rules: frontmatter's `---` sets the style (MD035).
- Keep a literal `|` out of titles where possible. The router escapes it, but it reads badly.

If the repo has an `.editorconfig`, lintorama also checks the files against it: LF endings, final newline, UTF-8.

## Setting up a repo

1. `python3 <this skill>/scripts/adr.py init` from anywhere in the repo. It is idempotent and creates:
   - the decisions directory (default `.agents/memory/decisions/`; pass `--dir docs/adr` to put it elsewhere,
     which pins the vendored copy to it);
   - the router `<dir>.md`;
   - a commented `merge=union` line in `.gitattributes`;
   - `scripts/adr.py`, a vendored copy (`--no-vendor` to skip).

   It refuses to write the router when existing files in the directory are malformed. See
   [Adopting existing ADRs](#adopting-existing-adrs).
2. Wire aliases, CI's `index --check`, the pre-commit hook and agent routing per
   [`references/wiring.md`](references/wiring.md). Match what the repo already uses (Makefile, `package.json`,
   `justfile`; no Gradle task).
3. Copy [`assets/decisions-rules.md`](assets/decisions-rules.md) to `.agents/rules/decisions.md`, and add routing
   lines to `AGENTS.md`. If the repo has no `AGENTS.md`/`.agents/` yet, the `dotagents-standard` skill covers setting
   one up.
4. Verify: `python3 scripts/adr.py index --check && python3 scripts/adr.py lint`, then a full lintorama run if the
   repo has a lint stop hook or a CI lint job.

### Adopting existing ADRs

Numbered ADRs from another scheme (adr-tools' `0001-record-architecture-decisions.md`, `## NNN.` sections in one
file) migrate by hand, one file each:

- keep the number as a legacy id (`id: "0001"`), and never renumber;
- add the five-key frontmatter, and rename the file to `<id>-<slug>.md`;
- replace the H1 with `# ADR <id> — <title>`, dropping trailing punctuation;
- keep any existing `Status` text.

Then run `index`. It names the first bad file on each run, so iterate until it passes.

## Commands

| Command | Does |
| --- | --- |
| `$ADR new "<title>" [--topics a,b]` | Mints an id and scaffolds the file. Prints the citation. |
| `$ADR index` | Validates every ADR and regenerates the router. |
| `$ADR index --check` | Same validation; exits 1 if the router is stale. Run it in CI and the hook. |
| `$ADR lint` | markdownlint (plus editorconfig-checker) over the ADRs via `zaventh/lintorama:9`, falling back to a local `mdl`. Exits 3 if neither exists. |
| `$ADR init [--dir PATH] [--no-vendor]` | Sets up a repo (idempotent). |
| `$ADR where` | Prints the decisions directory (the hook reads it). |

Every command takes `--root PATH` (default: nearest `.git` above the cwd) and `--dir PATH` (default: the pinned
directory, or `$ADR_DIR`).
