#!/usr/bin/env bash
# Stop hook: enforce the Gradle static-analysis checks before Claude finishes a turn —
# ktlintCheck (style), detekt (static analysis), and Android lint (incl. the NewApi API-level check
# that detekt/ktlint don't catch). Consolidates what used to be three separate per-tool hooks.
#
# Runs all three in ONE `./gradlew … --continue` invocation, so a single Gradle run reports every
# finding at once instead of stopping at the first failing tool.
#
# clean            -> exit 0, Claude stops normally.
# any check fails  -> exit 2 with the findings on stderr, which Claude Code feeds back to the model so
#                     it keeps working and fixes them. Most ktlint/detekt issues are mechanical —
#                     autocorrect the ktlint ones with `./gradlew ktlintFormat`.
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

# --continue: run every task even if an earlier one fails, so all findings surface in one pass.
output=$(./gradlew ktlintCheck detekt lint --continue --console=plain 2>&1)
status=$?

if [ "$status" -ne 0 ]; then
  {
    echo "Stop blocked: \`./gradlew ktlintCheck detekt lint\` found issues (exit $status). Fix them, then"
    echo "finish (most ktlint/detekt findings autocorrect with \`./gradlew ktlintFormat\`):"
    echo
    # Surface the per-tool findings — ktlint/detekt `path:line:col:` lines, detekt's weighted summary,
    # and Android lint's error/summary lines; fall back to a tail so a format change never swallows it.
    printf '%s\n' "$output" | grep -iE '\.kts?:[0-9]+:[0-9]+:|weighted issues|error:|Lint found|lint-results|FAILURE' \
      || printf '%s\n' "$output" | tail -n 30
  } >&2
  exit 2
fi

exit 0
