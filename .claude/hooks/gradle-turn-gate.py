#!/usr/bin/env python3
"""PreToolUse gate for Bash: an agent's Gradle run goes through `scripts/gradle-turn.sh`.

The turn is machine-wide (a few slots shared by every worktree, see the script's header), so it only holds the
workstation's memory if every agent takes it, the main session included. This blocks a Bash call that starts
`./gradlew` (or any path ending in `gradlew`) at a command position without `scripts/gradle-turn.sh` in front, and
names the command to run instead. Scripts that take the turn themselves (`lab-chaos.sh`, `unit-stress.sh`,
`review-gate.sh --checks`) don't spell `gradlew` at a command position, so they pass.

A mention that isn't a command (`grep gradlew`, a commit message) passes; one after `&&`, `;` or `|` inside quoted
text may not, and the agent then rewords it. Exit 0 = allow (also the answer to every parse failure: a gate that cannot
read its input must not wedge the shell), exit 2 = block with the fix on stderr.
"""

from __future__ import annotations

import json
import re
import sys

# Where a command starts: the start of the line, after a separator, a subshell or a substitution.
_START = r"(?:^|[;&|(\n{]|\$\(|`)\s*"
# What may stand between that start and gradlew: variable assignments and command wrappers. Captured, so a
# `gradle-turn.sh` among them marks the run as taking its turn.
_WRAPPERS = (
    r"(?P<pre>(?:(?:[A-Za-z_][A-Za-z0-9_]*=\S*"
    r"|\S*gradle-turn\.sh"
    r"|timeout(?:\s+-\S+)*\s+\S+"
    r"|nice(?:\s+-n)?(?:\s+-?\d+)?"
    r"|env|command|exec|time|nohup|(?:ba)?sh)\s+)*)"
)
GRADLEW = re.compile(_START + _WRAPPERS + r"(?:\S*/)?gradlew(?=\s|$|[;&|)])", re.MULTILINE)


def untaken(command: str) -> bool:
    """True when the command starts Gradle outside the turn."""
    return any("gradle-turn.sh" not in m.group("pre") for m in GRADLEW.finditer(command))


def main() -> int:
    try:
        payload = json.load(sys.stdin)
        command = payload.get("tool_input", {}).get("command", "")
    except (ValueError, AttributeError):
        return 0
    if not isinstance(command, str) or not untaken(command):
        return 0
    print(
        "Run Gradle through the machine-wide turn: put `scripts/gradle-turn.sh` in front of the Gradle command, e.g.\n"
        "  scripts/gradle-turn.sh ./gradlew :app:testDebugUnitTest -q --tests '<Class>' 2>&1 | tail -40\n"
        "It waits for this worktree and for one of the machine's Gradle slots, then runs the command unchanged "
        "(.agents/rules/build-and-test.md).",
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    sys.exit(main())
