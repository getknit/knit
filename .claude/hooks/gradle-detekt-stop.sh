#!/usr/bin/env bash
# Stop hook: enforce `./gradlew detekt` (the dev.detekt Gradle plugin) before Claude finishes a turn.
#
# detekt clean         -> exit 0, Claude stops normally.
# detekt finds issues  -> exit 2 with the `path:line:col: message [Rule]` findings on stderr, which
#                         Claude Code feeds back to the model so it keeps working and fixes them.
#
# Honors `stop_hook_active` so a genuinely un-fixable finding can't trap the session in an unbounded
# fix loop: after one enforced round we let the turn end.

set -uo pipefail

input=$(cat)

if [ "$(printf '%s' "$input" | jq -r '.stop_hook_active // false' 2>/dev/null)" = "true" ]; then
  exit 0
fi

project_dir="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$project_dir" || exit 0

# Skip when this turn changed no Gradle input (e.g. Markdown/docs-only) — see the guard header.
source "$project_dir/.claude/hooks/lib/gradle-input-guard.sh"
gradle_inputs_changed || exit 0

output=$(./gradlew detekt --console=plain 2>&1)
status=$?

if [ "$status" -ne 0 ]; then
  {
    echo "Stop blocked: \`./gradlew detekt\` found issues (exit $status). Fix them, then finish:"
    echo
    # Surface just the `path:line:col: message [Rule]` findings and the summary line; fall back to a
    # tail if the format ever changes so we never swallow the failure silently.
    printf '%s\n' "$output" | grep -E '\.kts?:[0-9]+:[0-9]+:|weighted issues' \
      || printf '%s\n' "$output" | tail -n 30
  } >&2
  exit 2
fi

exit 0
