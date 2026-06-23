#!/usr/bin/env bash
# Stop hook: enforce `./gradlew lint` before Claude finishes a turn.
#
# Lint passes  -> exit 0, Claude stops normally.
# Lint fails   -> exit 2 with the failing output on stderr, which Claude Code
#                 feeds back to the model so it keeps working and fixes the errors.
#
# Honors `stop_hook_active` so a genuinely un-fixable lint failure can't trap the
# session in an unbounded fix loop: after one enforced round we let the turn end.

set -uo pipefail

input=$(cat)

if [ "$(printf '%s' "$input" | jq -r '.stop_hook_active // false' 2>/dev/null)" = "true" ]; then
  exit 0
fi

project_dir="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$project_dir" || exit 0

output=$(./gradlew lint 2>&1)
status=$?

if [ "$status" -ne 0 ]; then
  {
    echo "Stop blocked: \`./gradlew lint\` failed (exit $status). Fix the lint errors below, then finish:"
    echo
    printf '%s\n' "$output" | grep -iE 'error:|FAILURE|Lint found|lint-results' \
      || printf '%s\n' "$output" | tail -n 30
  } >&2
  exit 2
fi

exit 0
