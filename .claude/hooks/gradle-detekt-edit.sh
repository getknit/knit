#!/usr/bin/env bash
# PostToolUse hook: run `./gradlew detekt` after Claude edits a Kotlin source file.
#
# Fires on Write/Edit/MultiEdit. Only Kotlin (.kt/.kts) edits trigger the analysis —
# every other file exits 0 immediately, so the ~10s detekt run is spent only when it
# could actually surface something.
#
# detekt clean -> exit 0, Claude continues.
# detekt finds issues -> exit 2 with the findings on stderr, which Claude Code feeds
#                        back to the model so it fixes them before moving on.

set -uo pipefail

input=$(cat)

# The edited path lives in tool_input (Edit/Write/MultiEdit); fall back to the response.
file=$(printf '%s' "$input" | jq -r '.tool_input.file_path // .tool_response.filePath // empty' 2>/dev/null)
case "$file" in
  *.kt | *.kts) ;;
  *) exit 0 ;;
esac

project_dir="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$project_dir" || exit 0

output=$(./gradlew detekt --console=plain 2>&1)
status=$?

if [ "$status" -ne 0 ]; then
  {
    echo "detekt found issues after editing ${file#"$project_dir"/} (exit $status). Fix them, then continue:"
    echo
    # Surface just the `path:line:col: message [Rule]` findings and the summary line; fall
    # back to a tail if the format ever changes so we never swallow the failure silently.
    printf '%s\n' "$output" | grep -E '\.kts?:[0-9]+:[0-9]+:|weighted issues' \
      || printf '%s\n' "$output" | tail -n 30
  } >&2
  exit 2
fi

exit 0
