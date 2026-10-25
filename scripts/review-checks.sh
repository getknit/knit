#!/usr/bin/env bash
#
# Run the checks the review gates' reviewers would run, for a pull request reviewed in CI.
#
# WHY THIS EXISTS. Each reviewer persona (.agents/personas/*-reviewer.md) ends its review by running Gradle: the tests
# of every class it touched, a chaos sweep, a stress run, the regression tests against the pre-change code. In Claude
# Code it does that itself. On a contributor's pull request (.github/workflows/review-gates.yml) the reviewers run in
# a job that holds the Claude token, and that job must never execute the pull request's code, so this script runs the
# same checks first, in a job with no secrets, and the reviewers read its results. It never touches a phone: device
# and lab trials stay the maintainer's.
#
# WHAT IT DOES. Asks `review-gate.sh --owed <base>` which reviewers the commits since <base> owe, then, once each:
#   - the stage-1 reviewers' tests: every test class that names a touched class (`grep -rlw`, mesh/lab/ left out),
#     plus the classes each persona always adds;
#   - mesh-lab-reviewer: lab-lint on the changed scenarios, then a 20-run chaos sweep of the changed *LabTest classes
#     (a fixture-only change sweeps the whole package at 3 runs);
#   - unit-test-reviewer: an 8-run stress of the changed test classes, the regression check against <base>
#     (unit-prefix.sh), the changed :wear tests once, and a compile of changed instrumented sources.
# Every check's verdict is a line in <out>/checks.md and its log is <out>/<check>.log. A failing check never stops the
# others, and the script exits 0 unless it could not run at all: a red check is a finding for the reviewer to read.
#
# Usage: scripts/review-checks.sh --base <rev> [--out <dir>]
#   --base  the commit the pull request branched from (its merge base with the target branch)
#   --out   where the results go (default review-checks/)
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2

base=""
out=review-checks
while (($#)); do
  case "$1" in
    --base) base="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    -h | --help) sed -n '2,/^set -uo/p' "$0" | sed 's/^# \{0,1\}//;$d'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$base" ]] || { echo "review-checks: name the merge base with --base" >&2; exit 2; }
mkdir -p "$out"
summary="$out/checks.md"
: >"$summary"

bash .claude/hooks/review-gate.sh --owed "$base" >"$out/owed.tsv"
if [[ ! -s "$out/owed.tsv" ]]; then
  echo "No reviewer owns a path this pull request changes." >>"$summary"
  exit 0
fi

owed() { awk -F'\t' -v n="$1" '$1 == n { print $3 }' "$out/owed.tsv" | tr ' ' '\n' | sed '/^$/d'; }
is_owed() { [[ -n "$(owed "$1")" ]]; }
present() { while read -r f; do [[ -f "$f" ]] && printf '%s\n' "$f"; done; }

# A test file's class, from its path under <module>/src/test/java/.
fqcn() { sed -E 's#^[a-z]+/src/test/java/##; s#\.kt$##; s#/#.#g'; }

# Runs one check: <name> <label> <command...>. The verdict is the command's exit status; scripts that tally their own
# result (lab-chaos.sh, unit-stress.sh, unit-prefix.sh) also print a summary line, kept beside it.
check() {
  local name=$1 label=$2 rc
  shift 2
  echo "::group::$label"
  "$@" >"$out/$name.log" 2>&1
  rc=$?
  tail -n 40 "$out/$name.log"
  echo "::endgroup::"
  local tally
  tally=$(grep -E '^(lab-chaos|unit-stress|unit-prefix):|: lab-lint/' "$out/$name.log" | tail -n 10)
  if ((rc == 0)); then
    printf -- '- PASS %s\n' "$label" >>"$summary"
  elif ((rc == 124)); then
    printf -- '- TIMEOUT %s (killed by its time limit; no verdict)\n' "$label" >>"$summary"
  else
    printf -- '- FAIL (exit %s) %s\n' "$rc" "$label" >>"$summary"
    if [[ $name == touched-tests ]]; then
      grep -hE '^\s+<failure message=' app/build/test-results/testDebugUnitTest/*.xml 2>/dev/null | head -n 5 |
        sed 's/^ */    /' >>"$summary"
    fi
  fi
  [[ -z "$tally" ]] || printf '%s\n' "$tally" | sed 's/^/    /' >>"$summary"
  printf '    log: %s/%s.log\n' "$out" "$name" >>"$summary"
}

# --- Stage 1: the tests of every touched class, and the classes each persona always runs. ---
tests=()
for r in bluetooth-reviewer wifi-aware-reviewer lora-reviewer crypto-reviewer blobs-reviewer; do
  is_owed "$r" || continue
  while read -r f; do
    cls=$(basename "$f" .kt)
    while read -r t; do
      tests+=("$(fqcn <<<"$t")")
    done < <(grep -rlw --include='*Test.kt' "$cls" app/src/test | grep -v '/mesh/lab/')
  done < <(owed "$r" | grep '\.kt$' | present)
  case "$r" in
    bluetooth-reviewer) tests+=('*.BluetoothWaitClockTest') ;;
    lora-reviewer)
      tests+=('app.getknit.knit.mesh.lora.*' '*.CoordinationPlaneSizeBudgetTest')
      owed "$r" | grep -q '/meshtastic/' && tests+=('*.BluetoothWaitClockTest')
      ;;
    crypto-reviewer)
      tests+=('*.RatchetEngineTest' '*.RatchetSessionsResetTest' '*.SessionTransactorOrderTest' '*.PendingGroupKeysTest'
        '*.WireSerializationTest' '*.GoldenVectorTest' '*.KeyedVectorTest' '*.IosEmittedVectorTest')
      ;;
  esac
done
if ((${#tests[@]})); then
  mapfile -t tests < <(printf '%s\n' "${tests[@]}" | sort -u)
  args=()
  for t in "${tests[@]}"; do args+=(--tests "$t"); done
  check touched-tests "Tests of the touched classes (${#tests[@]} patterns: ${tests[*]})" \
    timeout 1800 ./gradlew :app:testDebugUnitTest --console=plain "${args[@]}"
fi

# --- Stage 2: mesh-lab-reviewer. ---
if is_owed mesh-lab-reviewer; then
  mapfile -t scenarios < <(owed mesh-lab-reviewer | grep 'LabTest\.kt$' | present)
  if ((${#scenarios[@]})); then
    check lab-lint "lab-lint on ${#scenarios[@]} changed scenario file(s)" python3 scripts/lab-lint.py "${scenarios[@]}"
    args=()
    for f in "${scenarios[@]}"; do args+=(--tests "$(fqcn <<<"$f")"); done
    check lab-chaos "Chaos sweep of the changed scenarios, 20 runs each" \
      timeout 3600 scripts/lab-chaos.sh --no-lint --runs 20 --seed 1000 "${args[@]}"
  else
    check lab-lint "lab-lint (fixture change)" python3 scripts/lab-lint.py
    check lab-chaos "Chaos sweep of the whole lab package, 3 runs each (fixture change)" \
      timeout 3600 scripts/lab-chaos.sh --no-lint --runs 3 --seed 1000
  fi
fi

# --- Stage 2: unit-test-reviewer. ---
if is_owed unit-test-reviewer; then
  mapfile -t app_tests < <(owed unit-test-reviewer | grep '^app/src/test/.*Test\.kt$' | present | fqcn)
  mapfile -t wear_tests < <(owed unit-test-reviewer | grep '^wear/src/test/.*Test\.kt$' | present | fqcn)
  mapfile -t instrumented < <(owed unit-test-reviewer | grep '^app/src/androidTest/' | present)
  if ((${#app_tests[@]})); then
    args=()
    for c in "${app_tests[@]}"; do args+=(--tests "$c"); done
    check unit-stress "Stress of ${#app_tests[@]} changed test class(es), 8 runs" \
      timeout 3600 scripts/unit-stress.sh --runs 8 "${args[@]}"
    args=()
    for c in "${app_tests[@]}"; do args+=(--class "$c"); done
    check unit-prefix "Changed tests against the pre-change code ($base), one core, 30 runs" \
      timeout 3600 scripts/unit-prefix.sh --base "$base" --runs 30 "${args[@]}"
  fi
  if ((${#wear_tests[@]})); then
    args=()
    for c in "${wear_tests[@]}"; do args+=(--tests "$c"); done
    check wear-tests "Changed :wear tests, once" \
      timeout 1800 ./gradlew -Pknit.wear=true :wear:testDebugUnitTest --console=plain "${args[@]}"
  fi
  if ((${#instrumented[@]})); then
    # No managed device here: the reviewer names the emulator run the change owes; this only proves it builds.
    check instrumented-compile "Changed instrumented sources compile" \
      timeout 1800 ./gradlew :app:compileDebugAndroidTestKotlin --console=plain
  fi
  if ((${#app_tests[@]} + ${#wear_tests[@]} + ${#instrumented[@]} == 0)); then
    echo "- Only rigs, fakes or resources changed under the test trees: nothing to stress." >>"$summary"
  fi
fi

exit 0
