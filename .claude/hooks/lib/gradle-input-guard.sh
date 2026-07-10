#!/usr/bin/env bash
# Shared guard for the `gradle-*-stop.sh` Stop hooks.
#
# The Gradle tasks these hooks enforce (lint, detekt, ktlintCheck, testDebugUnitTest, koverLogDebug)
# only consume Android/build inputs: the `app/` module, the `gradle/` version catalog + wrapper, the
# `config/` tool configs, the root build scripts, `.editorconfig` (ktlint), and the root lockfile.
# A turn that only touched Markdown, docs, `.agents/`, `.claude/`, or shell scripts can't change their
# result, so running them is pure wasted wall-clock. Source this and early-exit when nothing relevant
# changed:
#
#   source "$project_dir/.claude/hooks/lib/gradle-input-guard.sh"
#   gradle_inputs_changed || exit 0
#
# `gradle_inputs_changed` returns 0 (run the task) / 1 (skip it):
#   * dirty tree  -> decide from the uncommitted change set (tracked-vs-HEAD + untracked).
#                    Any Gradle input changed -> run; changes exist but none are inputs -> skip.
#   * clean tree  -> decide from the last commit's files (HEAD~1..HEAD), so code that was edited AND
#                    committed in the same turn still gets validated, while a docs-only commit is
#                    skipped.
#   * uncertain   -> run (fail-safe: never skip validation when we can't read git state). A clean tree
#                    with no prior commit, or no git repo at all, falls through to here.
#
# Skipping is therefore strictly additive: behavior only changes for a change set that touches no
# Gradle input at all (the "only Markdown was modified" case).

# Repo-root-relative paths (forward slashes, no leading ./) that are inputs to the Gradle tasks.
GRADLE_INPUT_RE='^(app|gradle|config)/|^(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew|gradlew\.bat|\.editorconfig|settings-gradle\.lockfile)$'

# Echo the set of changed files (newline-separated) for the current state.
_gradle_guard_changed_files() {
  if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    # Dirty tree: tracked modifications/deletions/staged (working tree vs HEAD) + untracked files.
    git diff --name-only HEAD 2>/dev/null
    git ls-files --others --exclude-standard 2>/dev/null
  else
    # Clean tree: fall back to the files in the most recent commit.
    git diff --name-only HEAD~1 HEAD 2>/dev/null
  fi
}

# 0 = a Gradle input changed (or state is unknown) -> run the task.
# 1 = changes exist but none are Gradle inputs      -> skip the task.
gradle_inputs_changed() {
  git rev-parse --git-dir >/dev/null 2>&1 || return 0   # no repo -> run

  local files
  files=$(_gradle_guard_changed_files)

  [ -z "$files" ] && return 0                            # nothing to diff (e.g. root commit) -> run

  printf '%s\n' "$files" | grep -qE "$GRADLE_INPUT_RE" && return 0
  return 1
}
