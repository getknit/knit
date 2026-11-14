#!/usr/bin/env bash
#
# Run a command while holding a Gradle turn: this worktree's, so two Gradle runs in one checkout never overlap, and one
# of the machine's few slots, so all the worktrees together never run more Gradle builds than the workstation holds.
#
# WHY THIS EXISTS. The Stop hook spawns every owed reviewer at once, and each runs Gradle in the same worktree. Two
# `testDebugUnitTest` runs that overlap rewrite each other's test-results/binary/ and the first dies with
# `NoSuchFileException` though every test passed (.agents/context/testing.md, the Gradle 9.5 result race); two compiles
# of a moved tree write the same intermediates. Across worktrees the outputs differ, but the memory doesn't: each build
# brings a daemon and test JVMs of ~2.5 GB, and on 2026-10-06 four worktree agents and a chaos sweep ran the 62 GB box
# out of memory and the kernel killed the session.
#
# Two locks, taken in this order, so a run never sits on a machine slot while it waits for its own worktree:
# - The worktree's turn, an flock on <git dir>/gradle-turn.lock. Each worktree has its own.
# - A machine slot, one of KNIT_GRADLE_SLOTS (default 1) flocks in <Gradle user home>/knit-gradle-turn/, the directory
#   every worktree and clone building with that Gradle home shares (the same set shares the daemons and the caches).
#   One, because the CI runners on the workstation build outside the turn and take their own share of it. With more
#   than one, a run that finds another slot held also waits until MemAvailable reaches KNIT_GRADLE_MIN_FREE_MB (default
#   12288); a run alone takes its slot whatever the memory, so a full machine never stalls its only build. Each holder
#   writes its worktree and pid to slot.<n>.who, which a waiting run prints. KNIT_GRADLE_TURN_DIR overrides the
#   directory.
#
# Both are re-entrant: a script that holds the turn (lab-chaos.sh, unit-stress.sh) and runs another under it does not
# wait on itself. Waits up to GRADLE_TURN_WAIT seconds in all (default 3600), then exits 75. Without flock (macOS), the
# command runs at once; when the slot directory can't be written, it takes only the worktree's turn.
#
# The command runs in a transient systemd scope of CPU weight 20 (the default is 100). A daemon it starts lands in that
# scope and stays there, so a test sweep that fills every core still yields to the IDE and the terminals the agents run
# in. Nice can't do this: an agent's builds share one cgroup with the IDE or terminal it runs in, and the kernel splits
# CPU between cgroups before nice ranks the threads inside one. A weight only splits a contended CPU, so an idle machine
# still gives Gradle every core. A daemon started outside the turn keeps its cgroup until it exits (`./gradlew --stop`).
# Without a systemd user manager (CI containers, macOS) the command runs unscoped.
#
# Usage: scripts/gradle-turn.sh <command> [args...]
#   e.g. scripts/gradle-turn.sh timeout 900 ./gradlew :app:testDebugUnitTest -q --tests 'app.getknit.knit.FooTest'
set -euo pipefail

(($#)) || { echo "usage: scripts/gradle-turn.sh <command> [args...]" >&2; exit 2; }
if [[ -n "${KNIT_GRADLE_TURN:-}" ]] || ! command -v flock >/dev/null 2>&1; then
  exec "$@"
fi
wait_s=${GRADLE_TURN_WAIT:-3600}
deadline=$((SECONDS + wait_s))
give_up() { echo "gradle-turn: still busy after ${wait_s} s; giving up" >&2; exit 75; }

# This worktree's turn.
root="$(dirname "$0")/.."
lock="$(git -C "$root" rev-parse --absolute-git-dir)/gradle-turn.lock"
exec 9>>"$lock"
if ! flock --nonblock 9; then
  echo "gradle-turn: another Gradle run holds this worktree; waiting for it (up to ${wait_s} s)" >&2
  flock --wait "$wait_s" 9 || give_up
fi

# A machine slot.
slots=${KNIT_GRADLE_SLOTS:-1}
[[ $slots =~ ^[1-9][0-9]*$ ]] || slots=1
min_free=${KNIT_GRADLE_MIN_FREE_MB:-12288}
[[ $min_free =~ ^[0-9]+$ ]] || min_free=12288
dir=${KNIT_GRADLE_TURN_DIR:-${GRADLE_USER_HOME:-$HOME/.gradle}/knit-gradle-turn}

writable() {
  local i
  mkdir -p "$dir" 2>/dev/null || return 1
  for ((i = 0; i < slots; i++)); do : 2>/dev/null >>"$dir/slot.$i" || return 1; done
}

mem_free_mb() {
  if [[ -r /proc/meminfo ]]; then
    awk '/^MemAvailable:/ { print int($2 / 1024); exit }' /proc/meminfo
  else
    echo 999999 # unknown: don't hold a run back on a number we can't read
  fi
}

# holders <slot>: who holds each slot but <slot>, comma-separated; empty when none does. Probes each with a lock it
# drops at once, so a free slot reads free and a crashed holder's stale .who file is never printed.
holders() {
  local j s="" who
  for ((j = 0; j < slots; j++)); do
    ((j != $1)) || continue
    ! flock --nonblock "$dir/slot.$j" true 2>/dev/null || continue
    who=$(cat "$dir/slot.$j.who" 2>/dev/null) || who="slot $j"
    s+="${s:+, }$who"
  done
  printf '%s' "$s"
}

if writable; then
  said=""
  while :; do
    key="" why=""
    for ((i = 0; i < slots; i++)); do
      exec 8>>"$dir/slot.$i"
      if flock --nonblock 8; then
        others=$(holders "$i")
        [[ -n $others ]] || break 2
        free=$(mem_free_mb)
        ((free < min_free)) || break 2
        key="memory beside $others"
        why="memory: $free MB free, under $min_free, beside $others"
        exec 8>&-
        break
      fi
      exec 8>&-
    done
    if [[ -z $key ]]; then
      others=$(holders -1)
      key="full $others"
      why="a machine Gradle slot ($slots in all), held by $others"
    fi
    if [[ $key != "$said" ]]; then
      echo "gradle-turn: waiting for $why (up to ${wait_s} s in all)" >&2
      said=$key
    fi
    ((SECONDS < deadline)) || give_up
    sleep 2
  done
  top=$(git -C "$root" rev-parse --show-toplevel 2>/dev/null) || top=$root
  printf '%s (pid %s, since %s)\n' "${top/#"$HOME"/\~}" "$$" "$(date +%H:%M)" >"$dir/slot.$i.who" 2>/dev/null || true
else
  echo "gradle-turn: can't write $dir; taking only this worktree's turn" >&2
fi

export KNIT_GRADLE_TURN=1
if systemd-run --user --scope --quiet -p CPUWeight=20 -- true >/dev/null 2>&1; then
  exec systemd-run --user --scope --quiet -p CPUWeight=20 -- "$@"
fi
exec "$@"
