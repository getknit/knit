#!/usr/bin/env bash
# Stop hook: enforce `./gradlew ktlint` (the standalone ktlint CLI) before Claude finishes a turn.
#
# ktlint clean        -> exit 0, Claude stops normally.
# ktlint finds issues -> exit 2 with the `path:line:col: message (rule)` findings on stderr, which
#                        Claude Code feeds back to the model so it keeps working and fixes them.
#                        Most are mechanical — autocorrect locally with `ktlint --format`.
#
# Honors `stop_hook_active` so a genuinely un-fixable finding (e.g. one needing an .editorconfig
# opt-out rather than a code change) can't trap the session in an unbounded fix loop.

set -uo pipefail

input=$(cat)

if [ "$(printf '%s' "$input" | jq -r '.stop_hook_active // false' 2>/dev/null)" = "true" ]; then
  exit 0
fi

project_dir="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$project_dir" || exit 0

output=$(./gradlew ktlint --console=plain 2>&1)
status=$?

if [ "$status" -ne 0 ]; then
  {
    echo "Stop blocked: \`./gradlew ktlint\` failed (exit $status). Fix the style errors below"
    echo "(most autocorrect with \`ktlint --format\`), then finish:"
    echo
    # Surface just the `path:line:col: message (rule)` findings; fall back to a tail if the format
    # ever changes so we never swallow the failure silently.
    printf '%s\n' "$output" | grep -E '\.kts?:[0-9]+:[0-9]+:' \
      || printf '%s\n' "$output" | tail -n 30
  } >&2
  exit 2
fi

exit 0
