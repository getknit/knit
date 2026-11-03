#!/usr/bin/env bash
# Stop hook: the code checks pass, and then an uncommitted change under a reviewer's paths gets that reviewer, before
# Claude finishes the turn. The two run here in that order, in one hook, because Claude Code runs a Stop event's hooks
# in parallel: as separate hooks, lintorama or a ktlint fix landed after the reviewers had read the code, and moved
# enough lines to force a second review of the same change. lintorama runs on every stop with a change (it replaces its
# own Stop hook, lintorama-stop.sh, which this calls); ktlintCheck, detekt and lint run only when a reviewer is about to
# be asked, since they take minutes. `--checks` runs them all for an agent and records the pass (run_checks below).
#
# Each reviewer persona (`.agents/personas/<name>.md`) hunts one class of defect from a catalogue of the ones
# this codebase has already shipped and fixed. review-gate.conf maps each to the paths it owns and its stage; this
# script asks for the reviewers of the earliest stage with a review pending, in one message, so they run in parallel
# (.agents/rules/review-rounds.md: the subsystem reviewers first, the test reviewers once none of those is pending).
#
# no uncommitted change under a reviewer's paths                                      -> not pending.
# same HEAD and files as its last stamp, and within its follow-up lines of that diff  -> not pending: the caller
#                                                                                       is applying its findings.
# anything else                                                                       -> pending; owed unless
#                                                                                       this same state was asked
#                                                                                       for already.
# A stamp covers one review round: a commit since that touches the reviewer's paths, or a file the review never saw,
# needs a fresh one — else a small new change would ride an old stamp's slack. With no such commit, the next pass is a
# follow-up: the diff since the stamp is written to `<git dir>/claude-review/<name>.delta` and the message names it,
# so the reviewer reads the delta and the closure of its previous findings rather than the whole change again.
# nothing owed -> exit 0, Claude stops normally; else exit 2 with the instruction on stderr, which Claude
# Code feeds back.
#
# `--stamp <name>` records what a review approved: each reviewer runs it as its last step, so the few-line
# edits that apply its findings don't ask for a review of the review. The state lives in the git dir, so each
# worktree keeps its own. Honors `stop_hook_active`, so the enforced round can always end, except after a stop the
# checks blocked: that stop's continuation comes back to ask for the reviewers.

set -uo pipefail

conf="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/review-gate.conf"
project_dir="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
cd "$project_dir" || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0
state="$(git rev-parse --git-dir)/claude-review"

reviewers() { grep -v -e '^#' -e '^[[:space:]]*$' "$conf"; }

# What a reviewer reads: the tracked diff plus every untracked file's bytes.
snapshot() {
  git diff HEAD -- "$@"
  git ls-files --others --exclude-standard -- "$@" | sort | while read -r f; do
    printf '%s\n' "$f"
    cat "$f"
  done
}

# The working state of the paths as a git tree, written through a throwaway index so the real one is untouched: the
# stamp keeps it, and a follow-up pass's delta is a plain `git diff` between two of them.
worktree_tree() {
  local idx
  idx=$(mktemp)
  cp "$(git rev-parse --git-dir)/index" "$idx" 2>/dev/null || : >"$idx"
  GIT_INDEX_FILE="$idx" git add -A -- "$@" >/dev/null 2>&1
  GIT_INDEX_FILE="$idx" git write-tree
  rm -f "$idx"
}

# Whether a commit since <name>'s stamp left its paths alone: HEAD is the stamped one, or the commits between the two
# touch none of those paths (a docs or hook commit), so the stamped diff still reads against HEAD.
same_base() {
  local name=$1 head
  shift
  head=$(cat "$state/$name.head" 2>/dev/null) || return 1
  [ "$head" = "$(git rev-parse HEAD)" ] && return 0
  git cat-file -e "$head^{commit}" 2>/dev/null && git diff --quiet "$head" HEAD -- "$@"
}

changed_files() { git status --porcelain --untracked-files=all -- "$@" | awk '{print $NF}' | sort; }

fingerprint() { printf '%s\n' "$1" | sha256sum | cut -d' ' -f1; }

# Content lines only: a follow-up edit shifts the line numbers in every later hunk header and the blob ids in
# each `index` line, which would otherwise count as changes of their own.
content() { grep -v -e '^index ' -e '^@@ '; }

# The code checks: every static check whose fix moves code a reviewer reads (ktlint, detekt, Android lint, lintorama),
# never the tests, which the reviewers run themselves. Each check runs once per tree it reads and records a pass in the
# state dir, so `--checks` run by an agent, an owner or a reviewer satisfies the Stop hook and the hook never runs the
# same check again on the same tree. Gradle reads the modules and build files, not the docs, the agent library, the hooks
# or the store text, so an ADR written after a pass keeps it; lintorama reads everything. Gradle goes through
# scripts/gradle-turn.sh, so it takes turns with every other Gradle run in this worktree instead of racing it.
gradle_tree() {
  worktree_tree . ':(exclude)*.md' ':(exclude).agents' ':(exclude)docs' ':(exclude).claude' ':(exclude)fastlane' \
    ':(exclude).github'
}

# run_checks [gradle]: lintorama always, the Gradle checks only when asked. Prints the findings; returns 0 clean, 1 on a
# finding, 75 when Gradle's turn stayed busy past GRADLE_TURN_WAIT (the checks are unknown, not failed).
run_checks() {
  local out="" status=0 tree lint_out gradle_out gradle_status
  mkdir -p "$state"
  tree=$(worktree_tree .)
  if [ "$(cat "$state/lintorama.pass" 2>/dev/null)" != "$tree" ]; then
    if lint_out=$(printf '{}' | bash "$project_dir/.claude/hooks/lintorama-stop.sh" 2>&1); then
      printf '%s\n' "$tree" >"$state/lintorama.pass"
    else
      out+="$lint_out"$'\n'
      status=1
    fi
  fi
  [ "${1:-}" = gradle ] || {
    printf '%s' "$out"
    return "$status"
  }
  tree=$(gradle_tree)
  if [ "$(cat "$state/gradle-checks.pass" 2>/dev/null)" != "$tree" ]; then
    gradle_out=$(GRADLE_TURN_WAIT="${GRADLE_TURN_WAIT:-600}" "$project_dir/scripts/gradle-turn.sh" \
      ./gradlew ktlintCheck detekt lint --continue --console=plain 2>&1)
    gradle_status=$?
    if [ "$gradle_status" -eq 0 ]; then
      printf '%s\n' "$tree" >"$state/gradle-checks.pass"
    elif [ "$gradle_status" -eq 75 ]; then
      out+="The Gradle checks did not run: another Gradle run held this worktree past the wait."$'\n'
      [ "$status" -ne 0 ] || status=75
    else
      out+="\`./gradlew ktlintCheck detekt lint\` failed (exit $gradle_status). Most ktlint findings autocorrect with"
      out+=" \`./gradlew ktlintFormat\`; fix detekt and lint findings by hand:"$'\n'
      out+=$(printf '%s\n' "$gradle_out" |
        grep -iE '\.kts?:[0-9]+:[0-9]+:|weighted issues|error:|Lint found|lint-results|FAILURE' ||
        printf '%s\n' "$gradle_out" | tail -n 30)
      out+=$'\n'
      status=1
    fi
  fi
  printf '%s' "$out"
  return "$status"
}

# `--checks` runs every code check now, skipping one whose tree already passed: what an agent runs in place of
# `./gradlew ktlintCheck detekt lint` and lintorama, so the Stop hook finds the pass instead of running them again.
if [ "${1:-}" = "--checks" ]; then
  out=$(run_checks gradle)
  status=$?
  if [ "$status" -eq 0 ]; then
    echo "review-gate: code checks clean"
    exit 0
  fi
  printf '%s\n' "$out" >&2
  exit 2
fi

# `--owed <base>` lists the reviewers the commits since <base> owe, for a pull request nobody reviewed in Claude Code
# (.github/workflows/review-gates.yml): one line per reviewer, `<name>\t<stage>\t<files, space-separated>`, stage 1
# first. It reads and writes no state: CI has no stamps, so every reviewer whose paths the range touches is owed.
if [ "${1:-}" = "--owed" ]; then
  base="${2:?usage: review-gate.sh --owed <base-rev>}"
  while IFS=$'\t' read -r name stage _ paths _ <&3; do
    read -ra specs <<<"$paths"
    files=$(git diff --name-only "$base" HEAD -- "${specs[@]}" | tr '\n' ' ')
    [ -n "$files" ] && printf '%s\t%s\t%s\n' "$name" "$stage" "${files% }"
  done 3< <(reviewers) | sort -t$'\t' -k2,2n -s
  exit 0
fi

if [ "${1:-}" = "--stamp" ]; then
  name="${2:?usage: review-gate.sh --stamp <reviewer>}"
  line=$(reviewers | awk -F'\t' -v n="$name" '$1 == n')
  if [ -z "$line" ]; then
    echo "review-gate: no reviewer named '$name' in $conf" >&2
    exit 1
  fi
  IFS=$'\t' read -r _ _ _ paths _ <<<"$line"
  read -ra specs <<<"$paths"
  current=$(snapshot "${specs[@]}")
  mkdir -p "$state"
  printf '%s\n' "$current" >"$state/$name.snapshot"
  changed_files "${specs[@]}" >"$state/$name.files"
  git rev-parse HEAD >"$state/$name.head"
  worktree_tree "${specs[@]}" >"$state/$name.tree"
  rm -f "$state/$name.delta"
  fingerprint "$current" >"$state/$name.asked"
  echo "$name: review stamped ($(wc -l <"$state/$name.snapshot") lines of diff)"
  exit 0
fi

input=$(cat)
# A stop the pre-review checks blocked is not a finished round: the next stop, though `stop_hook_active`, comes back to
# ask for the reviewers once the checks pass. Any other continued stop ends the round.
if [ "$(printf '%s' "$input" | jq -r '.stop_hook_active // false' 2>/dev/null)" = "true" ]; then
  [ -f "$state/checks.blocked" ] || exit 0
else
  rm -f "$state/checks.blocked"
fi

# Pass 1: which reviewers have a review pending, and the earliest stage among them.
pending=()
first_stage=""
while IFS=$'\t' read -r name stage follow_up paths relay <&3; do
  read -ra specs <<<"$paths"
  changed=$(changed_files "${specs[@]}")
  [ -n "$changed" ] || continue

  current=$(snapshot "${specs[@]}")
  same_round=""
  if [ -f "$state/$name.snapshot" ] && same_base "$name" "${specs[@]}"; then
    same_round=1
    if [ -z "$(comm -23 <(printf '%s\n' "$changed") "$state/$name.files" 2>/dev/null)" ]; then
      delta=$(diff <(content <"$state/$name.snapshot") <(printf '%s\n' "$current" | content) | grep -c '^[<>]')
      [ "$delta" -le "$follow_up" ] && continue
    fi
  fi
  pending+=("$name"$'\t'"$stage"$'\t'"$paths"$'\t'"$relay"$'\t'"$same_round")
  if [ -z "$first_stage" ] || [ "$stage" -lt "$first_stage" ]; then first_stage=$stage; fi
done 3< <(reviewers)

# Pass 2: the pending reviewers of that stage still owed an ask: not when this same state was asked for already (they
# may be running now). A later stage waits, owed or not, until the earlier one has stamped.
to_ask=()
later=""
for entry in "${pending[@]}"; do
  IFS=$'\t' read -r name stage paths relay same_round <<<"$entry"
  if [ "$stage" != "$first_stage" ]; then
    later+=" $name"
    continue
  fi
  read -ra specs <<<"$paths"
  print=$(fingerprint "$(snapshot "${specs[@]}")")
  [ "$(cat "$state/$name.asked" 2>/dev/null)" = "$print" ] && continue
  # The fingerprint goes first: `read` folds an empty same_round's tab into the next field.
  to_ask+=("$print"$'\t'"$entry")
done

# Pass 3: the code checks, before any reviewer reads the code and one after another, never beside the review. A
# ktlintFormat, detekt, lint or lintorama fix after a review moves lines it read, and past its follow-up slack that
# forces a second review of the same change. lintorama runs on every stop, as its own hook did; the Gradle checks only
# when a reviewer is about to be asked. A failure blocks the stop instead of the ask. After CHECK_ROUNDS blocked stops in
# a row, or when the tree hasn't moved since the last failure, the reviewers are asked anyway with the findings named, so
# a check nobody can fix never holds the review or traps the session.
CHECK_ROUNDS=3
mkdir -p "$state"
checks_note=""
tree=$(worktree_tree .)
rounds=$(sed -n 1p "$state/checks.blocked" 2>/dev/null)
rounds=${rounds:-0}
if [ "$(sed -n 2p "$state/checks.blocked" 2>/dev/null)" = "$tree" ] || [ "$rounds" -ge "$CHECK_ROUNDS" ]; then
  checks_note="The code checks still fail and were left unfixed; tell the user. Their last report:"$'\n'
  checks_note+=$(cat "$state/checks.out" 2>/dev/null)
else
  if [ ${#to_ask[@]} -gt 0 ]; then
    out=$(run_checks gradle)
  else
    out=$(run_checks)
  fi
  status=$?
  if [ "$status" -eq 1 ]; then
    printf '%s\n%s\n' "$((rounds + 1))" "$tree" >"$state/checks.blocked"
    printf '%s\n' "$out" >"$state/checks.out"
    {
      echo "Stop blocked: the code checks fail. Fix these, then stop again:"
      echo
      printf '%s\n' "$out"
      if [ ${#to_ask[@]} -gt 0 ]; then
        echo "Owed a review once the checks pass (a fix after a review moves the lines it read and forces another"
        echo "round):$(for e in "${to_ask[@]}"; do printf ' %s' "$(cut -f2 <<<"$e")"; done)."
      fi
      echo "Rerun them with \`.claude/hooks/review-gate.sh --checks\`: it records the pass, so this hook won't run them again."
    } >&2
    exit 2
  fi
  [ "$status" -eq 75 ] && checks_note=$out
fi
rm -f "$state/checks.blocked" "$state/checks.out"

[ ${#to_ask[@]} -gt 0 ] || exit 0

owed=""
relays=""
followups=""
for entry in "${to_ask[@]}"; do
  IFS=$'\t' read -r print name stage paths relay same_round <<<"$entry"
  read -ra specs <<<"$paths"
  printf '%s\n' "$print" >"$state/$name.asked"

  label="$name:"
  if [ -n "$same_round" ] && [ -s "$state/$name.tree" ]; then
    git diff "$(cat "$state/$name.tree")" "$(worktree_tree "${specs[@]}")" -- "${specs[@]}" >"$state/$name.delta"
    label="$name (follow-up pass, its delta: $state/$name.delta, $(wc -l <"$state/$name.delta") lines):"
    followups+=" $name"
  else
    rm -f "$state/$name.delta"
  fi
  owed+="  $label"$'\n'"$(changed_files "${specs[@]}" | sed 's/^/    /')"$'\n'
  relays+="  $name: $relay"$'\n'
done

[ -n "$owed" ] || exit 0
{
  echo "Stop blocked: these changes have no review from the reviewer that owns them:"
  printf '%s' "$owed"
  echo
  echo "Spawn each reviewer named above (Agent tool, subagent_type = its name) on its files, all in one message so"
  echo "they run in parallel. Each reads the change against its catalogue (.agents/personas/<name>.md), runs its"
  echo "checks, and stamps what it reviewed (.agents/rules/review-rounds.md sets the pass and the labels)."
  if [ -n "$followups" ]; then
    echo "On a follow-up pass (${followups# }), hand the reviewer its delta file and its previous findings: it checks"
    echo "each finding closed and reviews the delta; a new finding outside it counts only if it is Blocking."
  fi
  if [ -n "$later" ]; then
    echo "Asked for once these have stamped (test reviewers come second):${later}."
  fi
  echo "Every finding comes labelled Blocking or Non-blocking. Fix each blocking one, or say why it does not hold;"
  echo "apply the non-blocking ones in the same batch or list them for the user as follow-ups. Then give the user"
  echo "the findings and, from each report:"
  printf '%s' "$relays"
  if [ -n "$checks_note" ]; then
    echo
    printf '%s\n' "$checks_note"
  fi
} >&2
exit 2
