#!/usr/bin/env bash
#
# Run a command while holding a Gradle turn: this worktree's, so two Gradle runs in one checkout never overlap, and one
# of the machine's slots, so all the worktrees together never run more Gradle builds than the workstation holds.
#
# WHY THIS EXISTS. The Stop hook spawns every owed reviewer at once, and each runs Gradle in the same worktree. Two
# `testDebugUnitTest` runs that overlap rewrite each other's test-results/binary/ and the first dies with
# `NoSuchFileException` though every test passed (.agents/context/testing.md, the Gradle 9.5 result race); two compiles
# of a moved tree write the same intermediates. Across worktrees the outputs differ, but the memory doesn't: each build
# brings a daemon (up to its 6 GB heap) and test JVMs of ~2.5 GB each, and on 2026-10-06 four worktree agents and a chaos
# sweep ran the 62 GB box out of memory and the kernel killed the session.
#
# Two locks, taken in this order, so a run never sits on a machine slot while it waits for its own worktree:
# - The worktree's turn, an flock on <git dir>/gradle-turn.lock. Each worktree has its own.
# - A machine slot, one of the flocks slot.<n> in <Gradle user home>/knit-gradle-turn/, the directory every worktree and
#   clone building with that Gradle home shares (the same set shares the daemons and the caches). There are
#   KNIT_GRADLE_SLOTS of them (default: one per 8 cores and per 20 GB of memory, at most 4: 3 on the 24-core, 62 GB
#   workstation). A run alone takes a slot whatever the memory, so a full machine never stalls its only build. Beside
#   others it also waits until MemAvailable reaches KNIT_GRADLE_MIN_FREE_MB (default 12288) once for itself and once
#   more for every other run that began in the last KNIT_GRADLE_RAMP_S seconds (default 300): a run that just began
#   has not grown its daemon and test JVMs into their memory yet, so MemAvailable does not show what it will take. One
#   run decides at a time (an flock on admit.lock), so two never count the same free memory. The CI runners on the
#   workstation build outside the turn; the memory gate sees what they hold. A run counts every slot held, whatever
#   KNIT_GRADLE_SLOTS the holder had, so callers with different settings still share one limit.
# - A long run (KNIT_GRADLE_LONG=1, or a mesh-simulator outing: a -Pknit.meshSim=… argument) never takes slot 0 when
#   there are two or more, so hour-long outings and sweep loops always leave a slot for everyone's short runs.
#
# Each holder writes its worktree, pid and start to slot.<n>.who (and the start in seconds to slot.<n>.since), which a
# waiting run prints. KNIT_GRADLE_TURN_DIR overrides the directory. Both locks are re-entrant: a script that holds the
# turn (lab-chaos.sh, unit-stress.sh) and runs another under it does not wait on itself. Waits up to GRADLE_TURN_WAIT
# seconds in all (default 3600), then exits 75. Without flock (macOS), the command runs at once; when the slot directory
# can't be written, it takes only the worktree's turn.
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

# meminfo_mb <field>: a /proc/meminfo field in MB; empty where there is none.
meminfo_mb() { awk -v k="$1:" '$1 == k { print int($2 / 1024); exit }' /proc/meminfo 2>/dev/null || true; }

mem_free_mb() {
  local free
  free=$(meminfo_mb MemAvailable)
  echo "${free:-999999}" # unknown: don't hold a run back on a number we can't read
}

default_slots() {
  local cores total n
  cores=$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 8)
  total=$(meminfo_mb MemTotal)
  n=$((cores / 8))
  ((${total:-0} / 20480 >= n)) || n=$((total / 20480))
  ((n <= 4)) || n=4
  ((n >= 1)) || n=1
  echo "$n"
}

# A machine slot.
slots=${KNIT_GRADLE_SLOTS:-}
[[ $slots =~ ^[1-9][0-9]*$ ]] || slots=$(default_slots)
min_free=${KNIT_GRADLE_MIN_FREE_MB:-12288}
[[ $min_free =~ ^[0-9]+$ ]] || min_free=12288
ramp=${KNIT_GRADLE_RAMP_S:-300}
[[ $ramp =~ ^[0-9]+$ ]] || ramp=300
long=${KNIT_GRADLE_LONG:-0}
for arg in "$@"; do [[ $arg != -Pknit.meshSim=* ]] || long=1; done
first=0
if [[ $long != 0 ]] && ((slots > 1)); then first=1; fi
dir=${KNIT_GRADLE_TURN_DIR:-${GRADLE_USER_HOME:-$HOME/.gradle}/knit-gradle-turn}

writable() {
  local i
  mkdir -p "$dir" 2>/dev/null || return 1
  : 2>/dev/null >>"$dir/admit.lock" || return 1
  for ((i = 0; i < slots; i++)); do : 2>/dev/null >>"$dir/slot.$i" || return 1; done
}

# survey <slot>: the runs holding every slot but <slot>, whatever KNIT_GRADLE_SLOTS they ran with. Sets `held` (how
# many), `holders` (who, comma-separated) and `recent` (how many began less than $ramp seconds ago). Probes each slot
# with a lock it drops at once, so a free slot reads free and a crashed holder's stale .who file is never read.
survey() {
  local f n who since now
  now=$(date +%s)
  held=0 holders="" recent=0
  for f in "$dir"/slot.*; do
    [[ $f =~ /slot\.([0-9]+)$ ]] || continue
    n=${BASH_REMATCH[1]}
    [[ $n != "$1" ]] || continue
    ! flock --nonblock "$f" true 2>/dev/null || continue
    who=$(cat "$f.who" 2>/dev/null) || who="slot $n"
    holders+="${holders:+, }$who"
    held=$((held + 1))
    since=$(cat "$f.since" 2>/dev/null) || since=0
    [[ $since =~ ^[0-9]+$ ]] || since=0
    ((now - since >= ramp)) || recent=$((recent + 1))
  done
}

if writable; then
  exec 7>>"$dir/admit.lock"
  said=""
  while :; do
    left=$((deadline - SECONDS))
    ((left > 0)) || give_up
    flock --wait "$left" 7 || give_up
    got=""
    for ((i = first; i < slots; i++)); do
      exec 8>>"$dir/slot.$i"
      if flock --nonblock 8; then
        got=$i
        break
      fi
      exec 8>&-
    done
    survey "${got:--1}"
    if [[ -n $got ]] && ((held == 0)); then break; fi
    free=$(mem_free_mb)
    need=$((min_free * (1 + recent)))
    if [[ -n $got ]] && ((held < slots && free >= need)); then break; fi
    if [[ -z $got || $held -ge $slots ]]; then
      key="full $holders"
      why="a machine Gradle slot ($slots in all"
      ((first == 0)) || why+=", slot 0 kept for short runs"
      why+="), held by $holders"
    else
      key="memory $held $recent"
      why="memory beside $holders: $free MB free, under the $need MB a run beside them needs"
    fi
    [[ -z $got ]] || exec 8>&-
    flock --unlock 7
    if [[ $key != "$said" ]]; then
      echo "gradle-turn: waiting for $why (up to ${wait_s} s in all)" >&2
      said=$key
    fi
    sleep 2
  done
  top=$(git -C "$root" rev-parse --show-toplevel 2>/dev/null) || top=$root
  printf '%s (pid %s, since %s)\n' "${top/#"$HOME"/\~}" "$$" "$(date +%H:%M)" >"$dir/slot.$got.who" 2>/dev/null || true
  date +%s >"$dir/slot.$got.since" 2>/dev/null || true
  exec 7>&-
else
  echo "gradle-turn: can't write $dir; taking only this worktree's turn" >&2
fi

export KNIT_GRADLE_TURN=1
if systemd-run --user --scope --quiet -p CPUWeight=20 -- true >/dev/null 2>&1; then
  exec systemd-run --user --scope --quiet -p CPUWeight=20 -- "$@"
fi
exec "$@"
