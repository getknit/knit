# The .agents Protocol (superset extensions)

This file documents the broader **.agents Protocol** described at **dotagentsprotocol.com**
(status: DRAFT). It is a *superset* of the lean dotagents architecture in `../SKILL.md`: same
`.agents/` directory idea, plus machine-readable configuration, a global layer, structured
sub-agents / tasks / memories, and a distribution Hub. The Protocol is associated with the SpeakMCP
tooling (note the `speakmcp-settings.json` file), so treat app-specific filenames as
implementation detail that may vary.

**Use the core (SKILL.md) for hand-authored project context. Reach for these extensions when the
user wants:** a global config layer shared across projects, MCP server wiring, structured
sub-agents/tasks/memories with schemas, or shareable config bundles.

Because both the Protocol and the details below are DRAFT, prefer confirming the exact field names
against dotagentsprotocol.com before generating machine config a tool must parse.

## Contents

- [Two layers: global + workspace](#two-layers-global--workspace)
- [Full directory layout](#full-directory-layout)
- [Merge order](#merge-order)
- [Frontmatter conventions](#frontmatter-conventions)
- [Config files](#config-files)
- [Collections: skills, sub-agents, tasks, memories](#collections)
- [The seven-standard convergence](#the-seven-standard-convergence)
- [Design principles](#design-principles)
- [The .agents Hub](#the-agents-hub)
- [Core vs. Protocol: when to use which](#core-vs-protocol-when-to-use-which)

---

## Two layers: global + workspace

The Protocol adds a **global layer** that the lean standard doesn't have:

- **Global:** `~/.agents/` — base configuration shared across all projects.
- **Workspace:** `./.agents/` — project-root overrides that shallow-merge onto the global config.

This is the key structural difference from the core standard, which is purely project-scoped.

## Full directory layout

```text
.agents/
├── speakmcp-settings.json      # General app settings
├── mcp.json                    # MCP servers & tools
├── models.json                 # Model presets & API keys
├── system-prompt.md            # System prompt
├── agents.md                   # Agent guidelines (AGENTS.md-compatible)
├── layouts/
│   └── ui.json                 # UI/layout preferences
├── skills/
│   └── {skill-id}/skill.md     # Skill definition
├── agents/
│   └── {agent-id}/
│       ├── agent.md            # Sub-agent profile + system prompt
│       └── config.json         # Tool/model/connection config
├── tasks/
│   └── {task-id}/task.md       # Recurring task definition
├── memories/
│   └── *.md                    # Persistent memory entries
└── .backups/                   # Auto-rotated, timestamped backups
    ├── skills/
    └── memories/
```

Note this differs from the core layout in `../SKILL.md` (which uses `rules/ context/ memory/
personas/ skills/ specs/ logs/`). The Protocol leans toward machine config + collections; the core
leans toward hand-authored, human-readable context. They share the `.agents/` root and the skills
idea. If a repo mixes both, that's fine — they don't collide.

## Merge order

Configuration resolves lowest-to-highest precedence:

```text
defaults  ←  config.json  ←  ~/.agents/ (global)  ←  ./.agents/ (workspace)
```

- **JSON files:** shallow-merge by top-level key.
- **Collections** (`skills`, `memories`, `agents`, `tasks`): merge by `id` — a workspace entry with
  the same id overrides the global one.

## Frontmatter conventions

The Protocol uses a deliberately minimal frontmatter dialect between `---` fences — described as
"YAML-like, not full YAML, no external dependencies":

- **Strings** may be quoted or bare.
- **Lists** accept either CSV (`tags: a, b, c`) or JSON array (`tags: ["a", "b"]`) form.
- **Keys are sorted deterministically** on write, for clean git diffs.

## Config files

**`mcp.json`** — MCP servers (Model Context Protocol). Maps the Anthropic/Linux Foundation MCP
standard into the `.agents/` container:

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["@mcp/server-filesystem"],
      "transport": "stdio"
    },
    "github": {
      "url": "https://api.github.com/mcp",
      "transport": "streamable-http"
    }
  }
}
```

**`models.json`** — model presets and provider keys. **`system-prompt.md`** — the base system
prompt. **`speakmcp-settings.json`** — general app settings (SpeakMCP-specific).
**`agents.md`** — project guidelines, marked AGENTS.md-compatible, carrying `kind: agents`
frontmatter:

```markdown
---
kind: agents
---

# Project Guidelines

## Build & Test
- Use `pnpm` for package management
- Run `pnpm test` before committing
```

## Collections

### Skills — `skills/{id}/skill.md`

Note: the Protocol uses a lowercase `skill.md` with an `id`/`enabled` frontmatter, distinct from the
agentskills.io `SKILL.md` used by the core standard.

```markdown
---
id: code-review
name: Code Review Expert
description: Thorough code review
enabled: true
---

Review code changes for:
- Security vulnerabilities
- Performance implications
- Test coverage gaps
```

### Sub-agents — `agents/{id}/agent.md` + `config.json`

Sub-agents are delegation targets the main agent can hand work to.

```markdown
---
id: code-reviewer
name: Code Reviewer
description: Reviews code for security
role: delegation-target
enabled: true
connection-type: internal
---

You are a code review specialist. Focus on security vulnerabilities,
performance, and test coverage.
```

```json
{
  "toolConfig": {
    "disabledServers": ["filesystem"],
    "enabledBuiltinTools": ["mark_work_complete"]
  },
  "modelConfig": {
    "mcpToolsProviderId": "openai",
    "mcpToolsOpenaiModel": "gpt-4o"
  },
  "connection": {
    "type": "stdio",
    "command": "my-agent",
    "args": ["--mode", "review"]
  }
}
```

### Tasks — `tasks/{id}/task.md`

Recurring/scheduled work.

```markdown
---
kind: task
id: daily-code-review
name: Daily Code Review
intervalMinutes: 60
enabled: true
runOnStartup: false
profileId: abc-123
---

Review all open pull requests and summarize their status.
Check for any failing CI pipelines.
```

### Memories — `memories/{id}.md`

Persistent, taggable knowledge entries (a more structured cousin of the core's
`memory/decisions.md`).

```markdown
---
id: arch_001
title: Database Architecture
content: PostgreSQL with Drizzle ORM
importance: high
tags: database, architecture, orm
---

We chose PostgreSQL over MongoDB for relational data integrity and
complex query support across billing.
```

## The seven-standard convergence

The Protocol positions `.agents/` as the single directory where seven independent standards coexist
as files — "each standard retains its own specification; this protocol defines how they coexist in
one predictable location":

| Standard | Steward | Maps to |
| :--- | :--- | :--- |
| MCP | Anthropic / Linux Foundation | `mcp.json` |
| AGENTS.md | OpenAI / Linux Foundation | `agents.md` |
| Skills | Anthropic | `skills/*/skill.md` |
| ACP | Zed Industries | agent profiles |
| Sub-Agents | .agents Protocol | `agents/*/agent.md` |
| Tasks | .agents Protocol | `tasks/*/task.md` |
| Memories | .agents Protocol | `memories/*.md` |

## Design principles

1. **Human-readable** — plain JSON and Markdown only; no binary or proprietary formats.
2. **Version-controllable** — deterministic key sorting for clean diffs.
3. **Portable** — relative paths, no vendor lock-in; shareable as profile packs.
4. **Safe by default** — atomic writes (temp + rename), timestamped backups with rotation
   (`.backups/`).
5. **Layered** — global defaults with workspace overrides.
6. **Extensible** — add new config files without breaking existing consumers.

## The .agents Hub

A public catalog for sharing portable agent configurations:

- Publish metadata: summary, author, tags, compatibility.
- "Public-safe defaults": agents, MCP, and skills enabled by default; **memories and recurring tasks
  are opt-in** (they may carry private context) — respect this when publishing or importing.
- Distributed as `.dotagents` bundles.

## Core vs. Protocol: when to use which

| You want to… | Use |
| :--- | :--- |
| Organize a repo's hand-authored context; slim a monolithic AGENTS.md | **Core** (`../SKILL.md`) |
| Share behavioral rules, ADRs, personas, specs with a team via git | **Core** |
| Wire up MCP servers / model presets as committed config | **Protocol** (`mcp.json`, `models.json`) |
| Keep a global config that all projects inherit and override | **Protocol** (`~/.agents/` layer) |
| Define structured, schedulable sub-agents or recurring tasks | **Protocol** (`agents/`, `tasks/`) |
| Package and publish a config bundle for others to install | **Protocol** (Hub / `.dotagents`) |

The two are not mutually exclusive: a repo can keep a lean core layout for human context and adopt
Protocol config files where machine config helps.
