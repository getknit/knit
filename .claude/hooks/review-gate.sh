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
# no commit to its paths and no new file since its stamp, and within its follow-up    -> not pending: the caller
# lines of the stamped tree                                                              is applying its findings.
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
# `--begin <name>` and `--stamp <name> <token>` record what a review approved. A reviewer runs `--begin` before it reads
# anything: it records HEAD and the working tree, prints a token, and on a follow-up pass rewrites the delta to that
# tree. `--stamp`, its last step, stamps the tree it began on, never the tree as it stands then, so an edit made while
# the review ran (by the caller, an owner, the IDE) is not stamped: it reads as diff past the stamp, and past the
# follow-up slack it is owed a pass. The stamp lets the few-line edits that apply its findings through without a review
# of the review. Every state is a git tree (worktree_tree), compared and diffed only through a reviewer's pathspecs; a
# stamp's tree is pinned by `refs/worktree/claude-review/<name>` so `git gc` keeps it. The state lives in the git dir,
# so each worktree keeps its own. Honors `stop_hook_active`, so the enforced round can always end, except after a stop
# the checks blocked: that stop's continuation comes back to ask for the reviewers.

set -uo pipefail

conf="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/review-gate.conf"
project_dir="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
cd "$project_dir" || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0
state="$(git rev-parse --git-dir)/claude-review"

reviewers() { grep -v -e '^#' -e '^[[:space:]]*$' "$conf"; }

# The conf line for reviewer <name>, or a complaint on stderr.
reviewer_line() {
  local line
  line=$(reviewers | awk -F'\t' -v n="$1" '$1 == n')
  if [ -z "$line" ]; then
    echo "review-gate: no reviewer named '$1' in $conf" >&2
    return 1
  fi
  printf '%s\n' "$line"
}

# The working state as a git tree, written through a throwaway copy of the index so the real one is untouched: the
# named paths as they are on disk, untracked files that aren't ignored included, everything else as the index has it,
# so two trees are compared only through a pathspec. A stamp keeps one, and a follow-up pass's delta is a plain
# `git diff` between two of them. It fails rather than answer the index's tree: one pathspec that matches nothing makes
# `git add` refuse them all.
worktree_tree() {
  local idx err status
  idx=$(mktemp)
  cp "$(git rev-parse --git-dir)/index" "$idx" 2>/dev/null || rm -f "$idx"
  if ! err=$(GIT_INDEX_FILE="$idx" git add -A -- "$@" 2>&1 >/dev/null); then
    rm -f "$idx"
    printf 'review-gate: could not snapshot the working tree: %s\n' "$err" >&2
    return 1
  fi
  GIT_INDEX_FILE="$idx" git write-tree
  status=$?
  rm -f "$idx"
  return "$status"
}

# Whether a state recorded on commit <head> still reads against HEAD for the paths: HEAD is that commit, or the commits
# between the two touch none of the paths (a docs or hook commit).
same_base() {
  local head=$1
  shift
  [ "$head" = "$(git rev-parse HEAD)" ] && return 0
  git cat-file -e "$head^{commit}" 2>/dev/null && git diff --quiet "$head" HEAD -- "$@"
}

# The files under the paths that differ between two trees (or commits): deletions and both sides of a rename included.
changed_in() {
  local from=$1 to=$2
  shift 2
  git diff --no-renames --name-only "$from" "$to" -- "$@" | sort
}

# The lines under the paths that differ between two trees, added plus removed. A binary file counts as <cap> + 1, more
# than any follow-up slack. Fails when git does (a tree gone), so the caller never reads its 0 as "nothing moved".
lines_between() {
  local from=$1 to=$2 cap=$3
  shift 3
  git diff --no-renames --numstat "$from" "$to" -- "$@" |
    awk -v big="$((cap + 1))" '{ n += ($1 == "-") ? big : $1 + $2 } END { print n + 0 }'
}

is_tree() { [ -n "$1" ] && git cat-file -e "$1^{tree}" 2>/dev/null; }

# The code checks: every static check whose fix moves code a reviewer reads (ktlint, detekt, Android lint, lintorama),
# never the tests, which the reviewers run themselves. Each check runs once per tree it reads and records a pass in the
# state dir, so `--checks` run by an agent, an owner or a reviewer satisfies the Stop hook and the hook never runs the
# same check again on the same tree. Gradle reads the modules and build files, not the docs, the agent library, the hooks
# or the store text, so an ADR written after a pass keeps it; lintorama reads everything. Gradle goes through
# scripts/gradle-turn.sh, so it takes turns with every other Gradle run in this worktree and on the machine.
gradle_tree() {
  worktree_tree . ':(exclude)*.md' ':(exclude).agents' ':(exclude)docs' ':(exclude).claude' ':(exclude)fastlane' \
    ':(exclude).github'
}

# run_checks [gradle]: lintorama always, the Gradle checks only when asked. Prints the findings; returns 0 clean, 1 on a
# finding, 75 when Gradle's turn stayed busy past GRADLE_TURN_WAIT (the checks are unknown, not failed). A tree that
# could not be read records no pass.
run_checks() {
  local out="" status=0 tree lint_out gradle_out gradle_status
  mkdir -p "$state"
  tree=$(worktree_tree .) || tree=""
  if [ -z "$tree" ] || [ "$(cat "$state/lintorama.pass" 2>/dev/null)" != "$tree" ]; then
    if lint_out=$(printf '{}' | bash "$project_dir/.claude/hooks/lintorama-stop.sh" 2>&1); then
      [ -z "$tree" ] || printf '%s\n' "$tree" >"$state/lintorama.pass"
    else
      out+="$lint_out"$'\n'
      status=1
    fi
  fi
  [ "${1:-}" = gradle ] || {
    printf '%s' "$out"
    return "$status"
  }
  tree=$(gradle_tree) || tree=""
  if [ -z "$tree" ] || [ "$(cat "$state/gradle-checks.pass" 2>/dev/null)" != "$tree" ]; then
    gradle_out=$(GRADLE_TURN_WAIT="${GRADLE_TURN_WAIT:-600}" "$project_dir/scripts/gradle-turn.sh" \
      ./gradlew ktlintCheck detekt lint --continue --console=plain 2>&1)
    gradle_status=$?
    if [ "$gradle_status" -eq 0 ]; then
      [ -z "$tree" ] || printf '%s\n' "$tree" >"$state/gradle-checks.pass"
    elif [ "$gradle_status" -eq 75 ]; then
      out+="The Gradle checks did not run: other Gradle runs held the worktree or every machine slot past the"
      out+=" wait."$'\n'
      [ "$status" -ne 0 ] || status=75
    else
      out+="\`./gradlew ktlintCheck detekt lint\` failed (exit $gradle_status). Most ktlint findings autocorrect with"
      out+=" \`./gradlew ktlintFormat\`; fix detekt and lint findings by hand (detekt is the type-resolved detektAll):"$'\n'
      out+=$(printf '%s\n' "$gradle_out" |
        grep -iE '\.kts?:[0-9]+:[0-9]+[: ]|weighted issues|error:|Lint found|lint-results|FAILURE' ||
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

# A reviewer's stamp, as `s_head s_tree s_begun`: the commit and tree it reviewed and when it began. Fails when there is
# none, or its tree is gone, which reads as no stamp: a full review.
read_stamp() {
  s_head="" s_tree="" s_begun=""
  [ -f "$state/$1.stamp" ] && read -r s_head s_tree s_begun <"$state/$1.stamp" && is_tree "$s_tree"
}

# `--begin <name>` is a reviewer's first step: it records the tree the review is about to read under a token for
# `--stamp`. On a follow-up pass it rewrites the delta to that tree, so the delta the reviewer reads is the one it stamps.
if [ "${1:-}" = "--begin" ]; then
  name="${2:?usage: review-gate.sh --begin <reviewer>}"
  line=$(reviewer_line "$name") || exit 1
  IFS=$'\t' read -r _ _ _ paths _ <<<"$line"
  read -ra specs <<<"$paths"
  tree=$(worktree_tree .) || exit 1
  head=$(git rev-parse HEAD)
  mkdir -p "$state/begin"
  find "$state/begin" -type f -mtime +7 -delete 2>/dev/null
  token=$(printf '%s %s %s %s %s\n' "$name" "$head" "$tree" "$$" "$(date +%s%N)" | sha256sum | cut -c1-8)
  printf '%s %s %s %s\n' "$name" "$head" "$tree" "$(date +%s)" >"$state/begin/$token"
  echo "$name: review begun; its last step is \`bash .claude/hooks/review-gate.sh --stamp $name $token\`"
  if read_stamp "$name" && same_base "$s_head" "${specs[@]}"; then
    git diff "$s_tree" "$tree" -- "${specs[@]}" >"$state/$name.delta"
    echo "follow-up delta since its last stamp: $state/$name.delta ($(wc -l <"$state/$name.delta") lines)"
  fi
  exit 0
fi

# `--stamp <name> <token>` stamps the tree `--begin` recorded under <token>, and says how far the paths have moved since.
if [ "${1:-}" = "--stamp" ]; then
  name="${2:?usage: review-gate.sh --stamp <reviewer> <token from --begin>}"
  token="${3:-}"
  line=$(reviewer_line "$name") || exit 1
  IFS=$'\t' read -r _ _ _ paths _ <<<"$line"
  read -ra specs <<<"$paths"
  now=$(worktree_tree .) || exit 1
  begun="$state/begin/$token"
  if [ -z "$token" ] || [ ! -f "$begun" ]; then
    if [ -z "$(changed_in HEAD "$now" "${specs[@]}")" ]; then
      echo "$name: nothing under its paths to stamp"
      exit 0
    fi
    {
      echo "review-gate: no review begun under token '$token', so nothing was stamped: a stamp of the tree as it is"
      echo "now would cover edits made while the review ran. The Stop hook will ask for this review again. Say so in"
      echo "your report, and next time run \`bash .claude/hooks/review-gate.sh --begin $name\` before reading."
    } >&2
    exit 1
  fi
  read -r b_name b_head b_tree b_begun <"$begun"
  if [ "$b_name" != "$name" ]; then
    echo "review-gate: token '$token' began a $b_name review, not a $name one; nothing was stamped" >&2
    exit 1
  fi
  if ! is_tree "$b_tree"; then
    echo "review-gate: the tree token '$token' recorded is gone from the object store; nothing was stamped" >&2
    exit 1
  fi
  rm -f "$begun"
  if read_stamp "$name" && [ "${s_begun:-0}" -gt "${b_begun:-0}" ]; then
    echo "$name: a review that began later has stamped already; this one's older tree was not stamped"
    exit 0
  fi
  mkdir -p "$state"
  printf '%s %s %s\n' "$b_head" "$b_tree" "$b_begun" >"$state/$name.stamp"
  git update-ref "refs/worktree/claude-review/$name" "$b_tree"
  # The pre-tree stamp's files, and the delta the stamp has just answered.
  rm -f "$state/$name.delta" "$state/$name.snapshot" "$state/$name.files" "$state/$name.head" "$state/$name.tree"
  echo "$name: review stamped ($(lines_between "$b_head" "$b_tree" 0 "${specs[@]}") lines changed under its paths)"
  if ! same_base "$b_head" "${specs[@]}"; then
    echo "A commit to its paths landed while the review ran: the next review is a full one."
  elif ! git diff --quiet "$b_tree" "$now" -- "${specs[@]}"; then
    moved=$(lines_between "$b_tree" "$now" 0 "${specs[@]}")
    echo "The tree moved $moved lines under its paths while the review ran: those edits are not stamped and stay owed."
  fi
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

# One snapshot of the working tree serves every reviewer below.
if ! cur=$(worktree_tree .); then
  echo "review-gate: no review state was read or written; fix the snapshot above, then stop again." >&2
  exit 2
fi

# Pass 1: which reviewers have a review pending, and the earliest stage among them.
pending=()
first_stage=""
while IFS=$'\t' read -r name stage follow_up paths relay <&3; do
  read -ra specs <<<"$paths"
  changed=$(changed_in HEAD "$cur" "${specs[@]}")
  [ -n "$changed" ] || continue

  same_round=""
  if read_stamp "$name" && same_base "$s_head" "${specs[@]}"; then
    same_round=1
    if [ -z "$(comm -23 <(printf '%s\n' "$changed") <(changed_in "$s_head" "$s_tree" "${specs[@]}"))" ]; then
      delta=$(lines_between "$s_tree" "$cur" "$follow_up" "${specs[@]}") || delta=$((follow_up + 1))
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
  a_head="" a_tree=""
  [ -f "$state/$name.asked" ] && read -r a_head a_tree <"$state/$name.asked"
  if same_base "$a_head" "${specs[@]}" && is_tree "$a_tree" && git diff --quiet "$a_tree" "$cur" -- "${specs[@]}"; then
    continue
  fi
  to_ask+=("$entry")
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
tree=$cur
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
        echo "round):$(for e in "${to_ask[@]}"; do printf ' %s' "$(cut -f1 <<<"$e")"; done)."
      fi
      echo "Rerun them with \`.claude/hooks/review-gate.sh --checks\`: it records the pass, so this hook won't run them again."
    } >&2
    exit 2
  fi
  [ "$status" -eq 75 ] && checks_note=$out
fi
rm -f "$state/checks.blocked" "$state/checks.out"

[ ${#to_ask[@]} -gt 0 ] || exit 0

head=$(git rev-parse HEAD)
owed=""
relays=""
followups=""
for entry in "${to_ask[@]}"; do
  IFS=$'\t' read -r name stage paths relay same_round <<<"$entry"
  read -ra specs <<<"$paths"
  printf '%s %s\n' "$head" "$cur" >"$state/$name.asked"

  label="$name:"
  if [ -n "$same_round" ] && read_stamp "$name"; then
    git diff "$s_tree" "$cur" -- "${specs[@]}" >"$state/$name.delta"
    label="$name (follow-up pass, its delta: $state/$name.delta, $(wc -l <"$state/$name.delta") lines):"
    followups+=" $name"
  else
    rm -f "$state/$name.delta"
  fi
  owed+="  $label"$'\n'"$(changed_in HEAD "$cur" "${specs[@]}" | sed 's/^/    /')"$'\n'
  relays+="  $name: $relay"$'\n'
done

[ -n "$owed" ] || exit 0
{
  echo "Stop blocked: these changes have no review from the reviewer that owns them:"
  printf '%s' "$owed"
  echo
  echo "Spawn each reviewer named above (Agent tool, subagent_type = its name) on its files, all in one message so"
  echo "they run in parallel. Each begins (\`--begin\`) before it reads, reads the change against its catalogue"
  echo "(.agents/personas/<name>.md), runs its checks, and stamps the tree it began on (.agents/rules/review-rounds.md"
  echo "sets the pass and the labels). Leave their paths alone while they run: an edit made then is not stamped, and"
  echo "past the follow-up slack it is owed another pass."
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
