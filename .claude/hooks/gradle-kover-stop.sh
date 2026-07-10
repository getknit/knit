#!/usr/bin/env bash
# Stop hook: guard test coverage (Kover) against regressions before Claude finishes a turn.
#
# Runs `./gradlew :app:koverLogDebug`, parses the `application line coverage: NN.NNNN%` line, and
# compares it to a per-commit baseline in .claude/hooks/.kover-baseline. The baseline is "banked" at
# the current coverage whenever HEAD moves (a commit is an explicit accept) and ratchets UP within a
# commit as coverage improves — so coverage can never silently erode by more than the threshold
# ($KOVER_GUARD_THRESHOLD, default 0.5) percentage points below the best level seen since the last
# commit.
#
#   coverage steady / improved -> exit 0 (banks improvements), Claude stops normally.
#   coverage dropped > threshold:
#     KOVER_GUARD_MODE=enforce (default) -> exit 2 with the delta on stderr, which Claude Code feeds
#                                           back to the model so it keeps working and adds tests.
#     KOVER_GUARD_MODE=warn              -> exit 0 after emitting a {"systemMessage": ...} notice;
#                                           the turn ends, the user just sees the warning.
#
# Honors `stop_hook_active` so an un-fixable dip can't trap the session in an unbounded fix loop:
# after one enforced round we let the turn end. No-ops (exit 0) if the coverage line can't be parsed
# (e.g. the tests fail to build) — coverage is advisory here, and gradle-test-stop.sh runs just
# before this hook and already blocks on a red test, so we never wedge a turn on the coverage tool.

set -uo pipefail

THRESHOLD="${KOVER_GUARD_THRESHOLD:-0.5}"   # max tolerated drop, in percentage points
MODE="${KOVER_GUARD_MODE:-enforce}"          # enforce | warn

input=$(cat)

# Break the Stop -> continue -> Stop loop: after one enforced round, let the turn end.
if [ "$(printf '%s' "$input" | jq -r '.stop_hook_active // false' 2>/dev/null)" = "true" ]; then
  exit 0
fi

project_dir="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$project_dir" || exit 0

# Skip when this turn changed no Gradle input (e.g. Markdown/docs-only) — see the guard header.
# (Coverage can't move without a source/test change, so there's nothing to re-check.)
source "$project_dir/.claude/hooks/lib/gradle-input-guard.sh"
gradle_inputs_changed || exit 0

baseline_file="$project_dir/.claude/hooks/.kover-baseline"

output=$(./gradlew :app:koverLogDebug --console=plain 2>&1)

# Parse `application line coverage: 43.2357%`. If we can't (build/test failure, format change), don't
# block the turn on the coverage tool — just exit cleanly.
now=$(printf '%s\n' "$output" | sed -n 's/.*coverage: \([0-9.]*\)%.*/\1/p' | tail -n1)
if [ -z "$now" ]; then
  exit 0
fi

head_sha=$(git rev-parse HEAD 2>/dev/null || echo nogit)

base_sha=""; base_pct=""
if [ -f "$baseline_file" ]; then
  read -r base_sha base_pct < "$baseline_file" || true
fi

# New reference point: first run, or HEAD moved (a commit banks the current level). Record and pass.
if [ -z "$base_pct" ] || [ "$base_sha" != "$head_sha" ]; then
  printf '%s %s\n' "$head_sha" "$now" > "$baseline_file"
  exit 0
fi

# Same commit: did coverage drop more than the threshold below the banked high-water mark?
if awk -v now="$now" -v base="$base_pct" -v thr="$THRESHOLD" 'BEGIN { exit !(now < base - thr) }'; then
  drop=$(awk -v now="$now" -v base="$base_pct" 'BEGIN { printf "%.4f", base - now }')
  if [ "$MODE" = "warn" ]; then
    # Non-blocking: surface a notice to the user and let the turn end.
    printf '{"systemMessage": "⚠️ Kover: line coverage dropped %s pp (%s%% → %s%%, threshold %s pp). Consider adding tests."}\n' \
      "$drop" "$base_pct" "$now" "$THRESHOLD"
    exit 0
  fi
  {
    echo "Stop blocked: test coverage dropped ${drop} percentage points this turn"
    echo "(${base_pct}% → ${now}%, allowed drop ${THRESHOLD} pp)."
    echo "Add unit tests for the new/changed code to restore coverage, then finish."
    echo "To bank this level as the new baseline instead, commit — or set KOVER_GUARD_MODE=warn."
  } >&2
  exit 2
fi

# Steady or improved: bank improvements (ratchet up); never silently ratchet down.
if awk -v now="$now" -v base="$base_pct" 'BEGIN { exit !(now > base) }'; then
  printf '%s %s\n' "$head_sha" "$now" > "$baseline_file"
fi

exit 0
