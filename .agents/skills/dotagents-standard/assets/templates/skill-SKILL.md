---
name: <skill-id>
description: <One line: what this skill does and when to use it. This is the trigger.>
compatibility:
  os: [linux, darwin]
  dependencies: [<cli-tools-this-skill-needs>]
---

# <Skill Name> Skill

<!--
  This is an agentskills.io-format skill, living at .agents/skills/<skill-id>/SKILL.md.
  Bundle supporting scripts under a scripts/ subfolder next to this file.
-->

<One-paragraph description of what this skill automates.>

## Usage

1. **Pre-check:** <preconditions to verify first>.
2. **Execute:** run `scripts/<script>.sh`.
3. **Verify:** confirm <success signal, e.g. "output contains 'Done'">.

## Constraints

- NEVER <the dangerous thing> without explicit user confirmation.
- If it fails, capture the error output and stop.
