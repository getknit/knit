#!/usr/bin/env bash
# Stop hook: a mesh-lab change gets a race review before Claude finishes the turn.
#
# Every mesh-lab flake so far has been one class of defect — a scenario that assumes an ordering the mesh
# does not promise — and the rules against it were already written down when each was written. So a change
# under app/src/test/java/app/getknit/knit/mesh/lab/ is not done until the `mesh-lab-reviewer` agent
# (.agents/personas/mesh-lab-reviewer.md) has read it step by step and stress-run it under seeded chaos.
#
# no uncommitted lab change, or the same one already reviewed -> exit 0, Claude stops normally.
# a new or changed uncommitted lab diff                        -> exit 2 with the instruction on stderr,
#                                                                 which Claude Code feeds back to the model.
#
# The diff's fingerprint is recorded when the hook fires, so one change set is asked for once; a later edit
# to it asks again. The fingerprint lives in the git dir, so each worktree keeps its own. Honors
# `stop_hook_active`, so the enforced round can always end.

set -uo pipefail

input=$(cat)

if [ "$(printf '%s' "$input" | jq -r '.stop_hook_active // false' 2>/dev/null)" = "true" ]; then
  exit 0
fi

project_dir="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$project_dir" || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

lab="app/src/test/java/app/getknit/knit/mesh/lab"
changed=$(git status --porcelain --untracked-files=all -- "$lab" | awk '{print $NF}' | sort)
[ -n "$changed" ] || exit 0

# What the reviewer would read: the tracked diff plus every untracked file's bytes.
fingerprint=$(
  {
    git diff HEAD -- "$lab"
    git ls-files --others --exclude-standard -- "$lab" | sort | while read -r f; do
      printf '%s\n' "$f"
      cat "$f"
    done
  } | sha256sum | cut -d' ' -f1
)

state="$(git rev-parse --git-dir)/claude-mesh-lab-reviewed"
[ "$(cat "$state" 2>/dev/null)" = "$fingerprint" ] && exit 0
printf '%s\n' "$fingerprint" >"$state"

{
  echo "Stop blocked: these mesh-lab files changed without a race review:"
  printf '%s\n' "$changed" | sed 's/^/  /'
  echo
  echo "Run the \`mesh-lab-reviewer\` agent (Agent tool, subagent_type \"mesh-lab-reviewer\") on them. It reads the"
  echo "change for the lab's known flake shapes and stress-runs the changed classes under seeded chaos"
  echo "(scripts/lab-chaos.sh). Fix each finding, or say why it does not hold, then give the user its verdict and"
  echo "the chaos sweep line."
} >&2
exit 2
