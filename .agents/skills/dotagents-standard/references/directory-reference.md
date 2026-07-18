# dotagents directory reference

Deep reference for every part of a dotagents layout. Read the relevant section when authoring a
subdirectory or when you encounter one you're unsure how to use. For the mental model and workflow,
see `../SKILL.md`.

## Contents

- [AGENTS.md (root router)](#agentsmd-root-router)
- [.agents/rules/](#agentsrules)
- [.agents/context/](#agentscontext)
- [.agents/memory/](#agentsmemory)
- [.agents/personas/](#agentspersonas)
- [.agents/skills/](#agentsskills)
- [.agents/specs/](#agentsspecs)
- [.agents/logs/](#agentslogs)
- [Commit vs. gitignore cheat sheet](#commit-vs-gitignore-cheat-sheet)
- [Naming conventions](#naming-conventions)

---

## AGENTS.md (root router)

**Role:** entry point and router. The only file guaranteed to be read every session.

**Format:** Markdown. Compatible with the cross-vendor [agents.md](https://agents.md) standard, so
any tool that reads `AGENTS.md` (Claude Code, Cursor, Gemini CLI, GitHub Copilot, and others) picks
it up.

**Recommended sections:**

- `## Identity` — who the agent is in this repo (role, priorities, tone). Keep it to a few lines.
- `## Context routing` — the conditional table of `**If <trigger>:** <VERB> <path>` lines. This is
  the part that earns dotagents its keep.
- `## Capabilities` — what the agent is permitted to do (e.g. "execute scripts under
  `.agents/skills/`", "write to `.agents/memory/`").

**Keep it to a screenful.** If it's longer, you have content that belongs in `.agents/`. The router
should point, not contain.

**Nested routers:** the AGENTS.md convention supports an `AGENTS.md` inside subdirectories; the one
nearest the file being edited wins. Use this in monorepos where a package needs its own identity or
routing. A nested `AGENTS.md` can itself route into a local `.agents/` or the repo-root one.

**CLAUDE.md / other vendor files:** don't maintain parallel copies. If a tool requires its own file,
make it a redirect: `See AGENTS.md.` That keeps a single source of truth.

---

## .agents/rules/

**Purpose:** invariant behavioral guidelines the agent must always obey. Rules *constrain behavior*.

**Format:** Markdown, one file per topic. No schema — bulleted imperatives are ideal because they're
skimmable and unambiguous.

**Naming:** topic-based — `coding.md`, `comms.md`, `security.md`, `testing.md`.

**When read:** load the rule file(s) relevant to the task (routed from `AGENTS.md`), or all of them
if they're short and universally applicable.

**Example (`rules/coding.md`):**

```markdown
# Coding standards

- No `any` types.
- Use functional patterns where possible.
- Prefer composition over inheritance.
```

**Rule vs. memory:** if it's a standing "always/never" instruction → rule. If it's a fact or past
decision that explains history and may change → memory. Don't blend them.

**Commit:** yes — rules are shared team standards.

---

## .agents/context/

**Purpose:** static reference data the agent occasionally needs but shouldn't carry in-context by
default — schemas, API surfaces, config shapes, data dictionaries.

**Format:** whatever is native to the data. Unlike the rest of the tree, `context/` is *not*
Markdown-first: `schema.sql`, `api.ts`, `openapi.yaml`, `types.json` are all appropriate. The agent
reads them in their natural form.

**Naming:** describe the artifact — `schema.sql`, `api.ts`, `error-codes.json`.

**Access:** read-only. Context describes reality; it isn't a scratchpad. If it drifts from the real
schema, fix it, but don't use it to record decisions (that's `memory/`).

**When read:** only when a routing rule matches ("If working on the database: READ
`.agents/context/schema.sql`"). This is the biggest single token saver — a large schema never enters
context for an unrelated task.

**Example (`context/schema.sql`):**

```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

**Commit:** yes. If a file is generated (e.g. dumped from a live DB), consider a script that
regenerates it rather than hand-editing.

---

## .agents/memory/

**Purpose:** persistent project knowledge that *evolves* — the project's long-term brain. Memory
*preserves context*.

**Format:** Markdown, read/write. This is the one part of the tree the agent is expected to *update*.

**Conventional files:**

- `decisions.md` — Architecture Decision Records (ADRs): why X was chosen over Y, so future work
  stays consistent.
- `user.md` — learned user/team preferences (usually gitignored; see below).

**ADR format (match what's already in the file):**

```markdown
# Architecture decision records

## 001. Use PostgreSQL
Status: Accepted
Date: 2023-10-27

We chose PostgreSQL for its reliability and JSONB support.
```

**When written:** the moment a durable decision is made or a lasting preference is discovered. This
is what lets a later session inherit today's reasoning instead of relitigating it. Append; don't
rewrite history (change `Status:` to `Superseded` rather than deleting an old ADR).

**Commit:** commit `decisions.md` (shared history). Gitignore `user.md` and other genuinely personal
memory.

---

## .agents/personas/

**Purpose:** specialized agent "hats" — role-specific instruction sets the agent adopts temporarily
for a phase of work.

**Format:** Markdown, one file per role.

**Naming:** the role — `qa.md` / `qa-engineer.md`, `architect.md`, `security-auditor.md`,
`tech-writer.md`.

**When used:** a persona is an explicit, temporary mode switch, usually triggered by a routing rule
("If reviewing or testing: ADOPT `.agents/personas/qa.md`"). Adopt it for the relevant phase, then
drop back to the base identity.

**Example (`personas/qa-engineer.md`):**

```markdown
# QA Engineer Persona

**Role:** You are a detail-oriented QA Engineer.
**Goal:** Break the code. Find edge cases.

**Instructions:**
- When reviewing code, look for off-by-one errors.
- Always ask: "What happens if this input is null?"
```

**Persona vs. rule:** a rule always applies; a persona is worn on demand for a specific kind of task.

**Commit:** yes.

---

## .agents/skills/

**Purpose:** reusable, executable capabilities — multi-step procedures an agent performs more than
once (run migrations, cut a release, scaffold a module).

**Format:** [agentskills.io](https://agentskills.io)-compliant. One folder per skill containing a
`SKILL.md` plus supporting files (typically a `scripts/` dir). dotagents defines *where* skills live
(`.agents/skills/`); agentskills.io defines *how* each skill is written.

**Layout:**

```text
skills/
└── database-migration/
    ├── SKILL.md
    └── scripts/
        └── migrate.sh
```

**SKILL.md frontmatter** (agentskills.io):

```markdown
---
name: database-migration
description: Safely runs database migrations using the project's established script.
compatibility:
  os: [linux, darwin]
  dependencies: [psql]
---

# Database Migration Skill
...
## Constraints
- NEVER run this on production without explicit user confirmation.
```

**When used:** routed from `AGENTS.md` ("You may execute scripts under `.agents/skills/`") or when
a task matches a skill's description. Read the `SKILL.md` first, then follow it — and honor its
`## Constraints`.

**Relationship to this skill:** the `dotagents-standard` skill you're reading *is itself* an agentskills.io
skill. A repo's `.agents/skills/` holds project-specific skills; this one is a globally installed,
general-purpose skill about the standard itself.

**Commit:** yes — skills are shared automation.

---

## .agents/specs/

**Purpose:** the requirements of the *current* work — PRDs, feature specs, task briefs. The "what
we're building right now."

**Format:** Markdown, one file per feature/task.

**Naming:** the feature — `feature-x.md`, `checkout-redesign.md`, `2026-q3-auth.md`.

**When read:** at the start of feature work ("If writing new features: CHECK `.agents/specs/` for the
active PRD").

**Context vs. spec:** `context/` is durable and true across tasks; `specs/` is transient and gets
superseded once shipped. Archive or delete stale specs so the folder always reflects live work.

**Commit:** yes while active. Prune when done (or move a "done" spec's lasting decisions into
`memory/decisions.md`).

---

## .agents/logs/

**Purpose:** session logs, agent thought traces, execution history, audit trails — kept out of the
root and out of the way.

**Format:** Markdown, append-only in spirit.

**Naming:** `session_1.md`, or timestamp-based (`2026-07-07-migration.md`). Since agents can't rely
on wall-clock in all environments, sequential naming (`session_N.md`) is a safe default.

**When written:** when you want a durable record of what happened in a session for later debugging or
audit. Optional — many repos won't need it.

**Commit:** usually gitignore (noisy, personal, large). Commit only if audit trails are a deliberate,
shared requirement.

---

## Commit vs. gitignore cheat sheet

| Path | Default |
| :--- | :--- |
| `AGENTS.md` | commit |
| `.agents/rules/**` | commit |
| `.agents/context/**` | commit |
| `.agents/memory/decisions.md` | commit |
| `.agents/memory/user.md` | **gitignore** (personal) |
| `.agents/personas/**` | commit |
| `.agents/skills/**` | commit |
| `.agents/specs/**` | commit while active; prune when done |
| `.agents/logs/**` | **gitignore** (usually) |

Suggested `.gitignore` lines:

```gitignore
.agents/memory/user.md
.agents/logs/
```

## Naming conventions

- **Directories & compound file names:** lowercase, hyphen-separated (`database-migration/`,
  `security-auditor.md`).
- **Single-topic files:** short and descriptive (`coding.md`, `schema.sql`, `decisions.md`).
- **Markdown structure:** `#` for the file title, `##` for sections — conventional and predictable so
  agents can navigate by heading.
- **Skills:** the folder name is the skill id and should match the `name:` in its `SKILL.md`.
