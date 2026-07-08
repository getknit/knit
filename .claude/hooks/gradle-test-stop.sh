#!/usr/bin/env bash
# Stop hook: enforce `./gradlew :app:testDebugUnitTest` before Claude finishes a turn.
#
# Ordered BEFORE the Kover coverage hook (gradle-kover-stop.sh) on purpose: that hook no-ops when it
# can't parse a coverage number, which is exactly what happens when the unit tests fail to build or
# run. Running the tests here first means a red test is reported and blocks (exit 2) instead of being
# silently swallowed by the coverage guard. When tests pass, Kover reuses the same UP-TO-DATE
# testDebugUnitTest, so this adds no real cost on a green turn.
#
# tests pass  -> exit 0, Claude stops normally.
# tests fail  -> exit 2 with the failing tests + report path on stderr, which Claude Code feeds back
#               to the model so it keeps working and fixes them.
#
# Honors `stop_hook_active` so a genuinely un-fixable failure can't trap the session in an unbounded
# fix loop: after one enforced round we let the turn end.

set -uo pipefail

input=$(cat)

if [ "$(printf '%s' "$input" | jq -r '.stop_hook_active // false' 2>/dev/null)" = "true" ]; then
  exit 0
fi

project_dir="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$project_dir" || exit 0

output=$(./gradlew :app:testDebugUnitTest --console=plain 2>&1)
status=$?

if [ "$status" -ne 0 ]; then
  {
    echo "Stop blocked: \`./gradlew :app:testDebugUnitTest\` failed (exit $status). Fix the failing tests below, then finish:"
    echo
    # Surface the failing-test lines, the summary, and the HTML report path; fall back to a tail so we
    # never swallow the failure silently if Gradle's output format changes.
    printf '%s\n' "$output" | grep -iE 'FAILED|tests? completed|failing tests|See the report at' \
      || printf '%s\n' "$output" | tail -n 30
  } >&2
  exit 2
fi

exit 0
