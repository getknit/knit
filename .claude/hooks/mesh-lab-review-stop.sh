#!/usr/bin/env bash
# Stop hook: a mesh-lab change gets a race review before Claude finishes the turn.
#
# Every mesh-lab flake so far has been one class of defect — a scenario that assumes an ordering the mesh
# does not promise — and the rules against it were already written down when each was written. So a change
# under app/src/test/java/app/getknit/knit/mesh/lab/ is not done until the `mesh-lab-reviewer` agent
# (.agents/personas/mesh-lab-reviewer.md) has read it step by step and stress-run it under seeded chaos.
#
# no uncommitted lab change, or the same one already asked for      -> exit 0, Claude stops normally.
# within FOLLOW_UP_LINES of what the reviewer last stamped          -> exit 0: applying its own findings.
# anything else                                                      -> exit 2 with the instruction on
#                                                                       stderr, which Claude Code feeds back.
#
# `--stamp` records what a review approved: the reviewer runs it as its last step, so the edits that apply
# its findings (a few lines each) don't ask for a review of the review. A new scenario, or a fixture change
# of any size, is past the threshold and asks again. The state lives in the git dir, so each worktree keeps
# its own. Honors `stop_hook_active`, so the enforced round can always end.

set -uo pipefail

FOLLOW_UP_LINES=40
lab="app/src/test/java/app/getknit/knit/mesh/lab"

project_dir="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
cd "$project_dir" || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0
git_dir=$(git rev-parse --git-dir)
asked="$git_dir/claude-mesh-lab-reviewed"
stamped="$git_dir/claude-mesh-lab-review.snapshot"

# What the reviewer reads: the tracked diff plus every untracked file's bytes.
snapshot() {
  git diff HEAD -- "$lab"
  git ls-files --others --exclude-standard -- "$lab" | sort | while read -r f; do
    printf '%s\n' "$f"
    cat "$f"
  done
}

if [ "${1:-}" = "--stamp" ]; then
  snapshot >"$stamped"
  sha256sum <"$stamped" | cut -d' ' -f1 >"$asked"
  echo "mesh-lab review stamped ($(wc -l <"$stamped") lines of lab diff)"
  exit 0
fi

input=$(cat)
if [ "$(printf '%s' "$input" | jq -r '.stop_hook_active // false' 2>/dev/null)" = "true" ]; then
  exit 0
fi

changed=$(git status --porcelain --untracked-files=all -- "$lab" | awk '{print $NF}' | sort)
[ -n "$changed" ] || exit 0

current=$(snapshot)
fingerprint=$(printf '%s\n' "$current" | sha256sum | cut -d' ' -f1)
[ "$(cat "$asked" 2>/dev/null)" = "$fingerprint" ] && exit 0

# Content lines only: a follow-up edit shifts the line numbers in every later hunk header and the blob ids in
# each `index` line, which would otherwise count as changes of their own.
content() { grep -v -e '^index ' -e '^@@ '; }

if [ -f "$stamped" ]; then
  delta=$(diff <(content <"$stamped") <(printf '%s\n' "$current" | content) | grep -c '^[<>]')
  [ "$delta" -le "$FOLLOW_UP_LINES" ] && exit 0
fi
printf '%s\n' "$fingerprint" >"$asked"

{
  echo "Stop blocked: these mesh-lab files changed without a race review:"
  printf '%s\n' "$changed" | sed 's/^/  /'
  echo
  echo "Run the \`mesh-lab-reviewer\` agent (Agent tool, subagent_type \"mesh-lab-reviewer\") on them. It reads the"
  echo "change for the lab's known flake shapes and stress-runs the changed classes under seeded chaos"
  echo "(scripts/lab-chaos.sh), then stamps what it reviewed. Fix each finding, or say why it does not hold, then"
  echo "give the user its verdict and the chaos sweep line."
} >&2
exit 2
