# CLAUDE.md

Single source of truth is **`AGENTS.md`** — a dotagents router that points into the `.agents/` context
library (rules, context, memory, skills) on demand. It's imported below so Claude Code loads it every
session. Don't duplicate guidance here; add it to the right `.agents/` file and let the router point to it.

@AGENTS.md
