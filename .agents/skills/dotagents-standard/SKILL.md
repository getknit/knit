---
name: dotagents-standard
version: 0.1.0
description: >-
  Set up, author, and navigate the dotagents standard — a slim AGENTS.md "router" at the
  repository root plus a hidden .agents/ directory (rules, context, memory, personas, skills,
  specs, logs, tasks) that splits agent context into small per-topic files loaded on demand
  (progressive disclosure). Use this WHENEVER the user mentions dotagents, dotagentsprotocol.com,
  the .agents/ directory, or an "AGENTS.md router"; wants to organize, split, slim down, or
  migrate a monolithic AGENTS.md / CLAUDE.md / .cursorrules into structured context; wants
  vendor-agnostic agent configuration that every tool (Claude, Cursor, Gemini, Copilot, local
  LLMs) can read; or is working inside a repository that ALREADY contains an AGENTS.md or a
  .agents/ directory and needs to know how to discover and load the right context. Also covers
  the broader ".agents Protocol" superset (global ~/.agents/ layer, mcp.json, structured
  sub-agents / tasks / memories, and the .agents Hub for sharing configs).
---

# dotagents

## What this is

**dotagents** turns a project's agent context from one big file into a **router + a library**.

- The **router** is a slim `AGENTS.md` at the repo root. It is *always* read. It describes the
  agent's identity and, crucially, tells the agent **where to look** for deeper context — but
  only when a task actually needs it.
- The **library** is a hidden `.agents/` directory holding the "heavy" context, split into
  small, single-purpose files organized by *kind* (behavioral rules vs. static reference vs.
  durable memory vs. task specs, etc.).

The whole point is **progressive disclosure**: load a screenful of routing rules up front, then
pull in only the specific files the current task matches. A monolithic `AGENTS.md`/`CLAUDE.md`
forces the agent to read a database schema while editing CSS, mixes "never use `any`" (a rule)
with "we chose Postgres in 2023" (a memory), and invites a clutter of vendor folders
(`.claude/`, `.cursor/`, `.gemini/`) in the root. dotagents fixes all three by *separating by
kind* and *loading conditionally*.

**The value lives entirely in that discipline.** If you dump everything into `AGENTS.md`, you've
gained nothing. If you scatter files but the router never points to them, agents won't find
them. Both halves — a *tight router* and *well-factored files* — must hold.

## Two related specs share the "`.agents`" name

Be aware there are two overlapping standards; know which one the user means:

1. **dotagents** (github.com/bgreenwell/dotagents, Draft 0.1.0) — the lean, hand-authored,
   project-scoped *directory-as-context* architecture described in this skill. This is "the
   dotagents standard" and the default subject here.
2. **The .agents Protocol** (dotagentsprotocol.com, Draft) — a *superset* that keeps the same
   `.agents/` idea but adds machine-readable config (`mcp.json`, `models.json`), a global
   `~/.agents/` layer that merges with the project layer, structured sub-agents / tasks /
   memories with frontmatter schemas, and a public "Hub" for sharing `.dotagents` bundles.

They agree on the core (`.agents/` + progressive disclosure). Use the **core** for
hand-authored project context. Reach for the **Protocol extensions** when the user wants a
global config layer, MCP wiring, structured sub-agents/tasks, or shareable config bundles — see
`references/protocol-extensions.md`.

## The directory map

```text
.
├── AGENTS.md             # Entry point & router (Required). Always read first.
└── .agents/              # The context library (recommended; adapt to your project)
    ├── rules/            # Invariant behavioral guidelines ("No `any` types")
    ├── context/          # Static reference data, read-only (schema.sql, api.ts)
    ├── memory/           # Persistent project knowledge, read/write (decisions.md, user.md)
    ├── personas/         # Specialized agent "hats" (qa.md, architect.md)
    ├── skills/           # Executable capabilities — agentskills.io SKILL.md folders + scripts
    ├── specs/            # Current task requirements / PRDs (feature_x.md)
    └── logs/             # Session logs, thought traces, audit trails
```

Create **only the subdirectories you need** — empty scaffolding is noise. Full per-directory
detail (format, naming, examples, commit guidance) is in `references/directory-reference.md`.

## The two things you'll do

**Utilize** an existing setup (the common case): a repo already has `AGENTS.md` / `.agents/` and
you must do work in it correctly and efficiently. → See "Utilizing" below.

**Implement** a setup: create a new dotagents layout, or migrate a bloated `AGENTS.md` / `CLAUDE.md`
/ `.cursorrules` into one. → See "Implementing" below.

## The decision taxonomy (the crux)

Whether reading or authoring, the key skill is knowing **which kind** a piece of context is.
Ask, in order:

| If the context is… | it's a… | goes in | read/write |
| :--- | :--- | :--- | :--- |
| An invariant behavioral rule ("always run tests before commit", "no `any`") | **rule** | `rules/` | read |
| Static reference the agent occasionally needs (DB schema, API types, config shape) | **context** | `context/` | read-only |
| Durable knowledge that evolves (why we chose X over Y, learned user prefs) | **memory** | `memory/` | read/write |
| A specialized role adopted temporarily (QA, security auditor, architect) | **persona** | `personas/` | read |
| A reusable, multi-step executable procedure (migration, release, codegen) | **skill** | `skills/{id}/` | read + run |
| The requirements of the *current* task (a PRD, a feature spec) | **spec** | `specs/` | read |
| A session record / audit trail / thought trace | **log** | `logs/` | write |

The two most-confused pairs, worth internalizing:

- **rule vs. memory.** A *rule* is a standing instruction you must always obey ("prefer composition
  over inheritance"). A *memory* is a fact or decision that explains history and may change ("ADR
  001: chose Postgres for JSONB"). Rules constrain behavior; memories preserve context. Mixing
  them is the original sin dotagents exists to prevent.
- **context vs. specs.** *context/* is durable and read-only (the schema that's true across many
  tasks). *specs/* is the transient "what we're building right now" and gets superseded.

When something doesn't fit cleanly, prefer the bin that makes the router rule easiest to write.

## Utilizing an existing setup

When you start work in a repo that uses dotagents, practice disciplined progressive disclosure:

1. **Read `AGENTS.md` first, fully.** It's the map. Note the routing rules — keep them in working
   memory even before you know which you'll need. Also honor **nested** `AGENTS.md` files: the
   AGENTS.md convention lets subdirectories carry their own `AGENTS.md`, and the one *nearest* the
   file you're editing takes precedence over the root.
2. **Match the task to routing rules, then load just those files.** If the router says "If working
   on the database: READ `.agents/context/schema.sql`" and you're editing CSS, you do *not* read
   the schema. Pull in a file the moment its condition matches — and not before.
3. **Adopt a persona only when the task calls for it.** "Now put on the QA hat in
   `.agents/personas/qa.md`" is an explicit, temporary mode switch — do it when reviewing/testing,
   drop it afterward.
4. **Run skills rather than reinventing them.** If `.agents/skills/` has a procedure for what
   you're about to hand-roll (a migration, a release), read its `SKILL.md` and use it. Respect its
   stated constraints (e.g. "never run on production without confirmation").
5. **Maintain memory as you go.** `memory/` is *read/write* by design — it's how the project learns.
   When you make a durable decision, discover a lasting preference, or establish a new invariant,
   write it back: append an ADR to `memory/decisions.md`, note a preference in `memory/user.md`, or
   propose a new line in `rules/`. The next session (yours or a teammate's) inherits it. Match the
   existing file's format (e.g. the ADR heading style already in use).
6. **Respect personal/gitignored files.** `memory/user.md` and similar are often gitignored; read
   them for context but don't commit them or leak their contents into shared files.

**If a task needs context the router doesn't point to**, that's a gap — read the likely file
anyway, complete the task, and then *improve the router* (add the routing rule) so it's found next
time. Treat missing routing as a bug in the setup, not a dead end.

## Implementing / migrating a setup

To set up dotagents in a repo (or split up a monolithic context file):

1. **Inventory** every source of agent context: `AGENTS.md`, `CLAUDE.md`, `.cursorrules`,
   `.github/copilot-instructions.md`, README "development" sections, and scattered vendor folders.
2. **Classify each chunk** using the taxonomy table above. Read `README.md` prose, code standards,
   architecture notes, and to-do specs as *different kinds* even if they currently live in one file.
3. **Create `.agents/` with only the subdirs you need**, and move the heavy/conditional content
   into small single-purpose files. Name files for their topic (`coding.md`, `schema.sql`,
   `decisions.md`), lowercase-with-hyphens for compound names (`database-migration/`).
4. **Leave a tight router in `AGENTS.md`.** After the move, `AGENTS.md` should be roughly a
   screenful: identity + a routing table + capabilities. If it's longer, you haven't moved enough
   out.
5. **Write conditional routing rules** that point to the moved files. A good rule states a
   *trigger* and an *action verb*: `**If touching auth:** READ .agents/context/auth-flow.md`. Avoid
   unconditional "always read everything" — that recreates the monolith.
6. **Promote repeatable procedures to skills.** Any multi-step thing an agent will do more than
   once (run migrations, cut a release) becomes `skills/{id}/SKILL.md` (+ `scripts/`), following the
   agentskills.io format. See the template in `assets/templates/skill-SKILL.md`.
7. **Decide commit vs. gitignore per file.** Commit `.agents/` generally — shared context is the
   payoff for team alignment. Gitignore genuinely personal files (`.agents/memory/user.md`).
8. **Keep `AGENTS.md` spec-compatible.** `AGENTS.md` is the cross-vendor
   [agents.md](https://agents.md) standard, read by Claude, Cursor, Gemini CLI, Copilot, and more.
   Staying compatible is what makes dotagents vendor-agnostic. If a tool insists on `CLAUDE.md`,
   make it a one-liner: `See AGENTS.md.`

Copy-paste starters live in `assets/templates/` — start from `assets/templates/AGENTS.md`.

## The AGENTS.md router pattern

The router is the heart of the standard. Minimal, high-signal, conditional:

```markdown
# AGENTS.md

## Identity
You are a Senior Rust Engineer focused on safety and performance.

## Context routing
- **If working on the database:** READ `.agents/context/schema.sql`.
- **If writing new features:** CHECK `.agents/specs/` for the active PRD.
- **If facing an architectural choice:** CONSULT `.agents/memory/decisions.md` for consistency.
- **If reviewing or testing:** ADOPT the persona in `.agents/personas/qa.md`.

## Capabilities
- You may execute scripts under `.agents/skills/` to validate your work.
```

What makes routing rules good:

- **Conditional, not unconditional.** Every line names a *when*. That's what preserves the token
  savings.
- **An action verb per pointer** — `READ` (load reference), `CHECK` (scan a folder), `CONSULT`
  (cross-check for consistency), `ADOPT` (switch persona), `RUN` (execute a skill). The verb tells
  the agent what to *do* with the file, not just that it exists.
- **Specific paths**, so there's no ambiguity about what to open.
- **Short.** If the router grows past a screenful, push detail down into `.agents/` files and leave
  a pointer.

## Conventions to hold to

- **Keep the root clean.** The reason `.agents/` is hidden and consolidated is to avoid a litter of
  `.claude/`, `.cursor/`, `.gemini/` folders. Route everything through `AGENTS.md` + `.agents/`.
- **Markdown-first, human-readable.** Prefer Markdown; use native formats in `context/` where
  they're the natural fit (`.sql`, `.ts`, `.json`). No binary blobs, no proprietary schemas.
- **One file, one purpose.** Small single-topic files are what make conditional loading possible.
- **Commit for the team; gitignore the personal.** Shared context aligns collaborators; keep
  personal preferences out of the shared tree.
- **`.agents/` is not `.github/`.** `.github/` is platform-specific; `.agents/` is platform-agnostic
  and meant for every kind of agent (IDE, CLI, local LLM).

## Where to go next

- `references/directory-reference.md` — every subdirectory in depth: purpose, file format, naming,
  examples, and commit/gitignore guidance. Read it when authoring or when you hit an unfamiliar
  subdir.
- `references/protocol-extensions.md` — the broader **.agents Protocol** (dotagentsprotocol.com):
  global `~/.agents/` layer + merge order, `mcp.json` / `models.json`, structured sub-agents /
  tasks / memories, and the `.agents` Hub. Read it when the user wants machine config, a global
  layer, or shareable bundles.
- `assets/templates/` — copy-paste starter files: `AGENTS.md`, `rules-coding.md`,
  `memory-decisions.md`, `personas-qa-engineer.md`, `skill-SKILL.md`.
