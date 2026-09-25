#!/usr/bin/env bash
#
# Sweep mesh-lab scenarios under seeded scheduling noise (app/src/test/.../mesh/lab/LabChaos.kt).
#
# WHY THIS EXISTS. A mesh-lab flake is a window a workstation closes before anyone looks: a counter bumped
# after the `send` a scenario waited on, an emission before a late subscriber. CI's slow runners open those
# windows one run in thirty; the throttled-core loop opens them at twenty minutes a run. Chaos mode stretches
# the lab's dispatch, pipe and collector timing on purpose (never what a pipe means), so a scenario that
# fails here is one a slow runner fails too, given the same luck. Run it on any lab test you write or touch
# before you push it.
#
# WHAT IT DOES. One Gradle invocation: every selected scenario runs RUNS times in one JVM on seeds SEED,
# SEED+1, ... (each run a fresh MeshLab), stopping a scenario at its first failure. Then it tallies the
# per-class XMLs (never Gradle's summary — see the Gradle 9.5 result race in .agents/context/testing.md) and
# prints each failure with its seed. A seed replays a distribution of delays, not an exact schedule: re-run
# a failing seed a few times, and fix the scenario by awaiting the event it needs.
#
# Usage: scripts/lab-chaos.sh [--tests <pattern>] [--runs <n>] [--seed <n>|random] [--quota <pct>]
#   --tests  Gradle --tests filter, repeatable (default: the whole app.getknit.knit.mesh.lab package)
#   --runs   runs per scenario (default 20)
#   --seed   first seed (default: random, printed)
#   --quota  also cap the JVM at <pct>% of one core (systemd-run), the slow-runner loop's throttle
set -euo pipefail

cd "$(dirname "$0")/.."

tests=()
runs=20
seed=""
quota=""
while (($#)); do
  case "$1" in
    --tests) tests+=("--tests" "$2"); shift 2 ;;
    --runs) runs="$2"; shift 2 ;;
    --seed) seed="$2"; shift 2 ;;
    --quota) quota="$2"; shift 2 ;;
    -h | --help) sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//;$d'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
((${#tests[@]})) || tests=("--tests" "app.getknit.knit.mesh.lab.*")
if [[ -z "$seed" || "$seed" == random ]]; then seed=$(( ($(date +%s%N) / 1000) % 1000000000 )); fi

results=app/build/test-results/testDebugUnitTest
rm -rf "$results"
mkdir -p app/build

cmd=(./gradlew :app:testDebugUnitTest "${tests[@]}" --rerun --console=plain
  "-Pknit.labChaos=$seed" "-Pknit.labChaosRuns=$runs")
if [[ -n "$quota" ]]; then
  cmd=(systemd-run --user --scope -q -p "CPUQuota=${quota}%" taskset -c 0 "${cmd[@]}" --no-daemon)
fi

echo "lab-chaos: seeds $seed..$((seed + runs - 1)), ${tests[*]}${quota:+, CPUQuota=$quota%}"
set +e
"${cmd[@]}" > "app/build/lab-chaos.log" 2>&1
gradle_status=$?
set -e

python3 - "$results" "$seed" "$runs" <<'EOF'
import glob, re, sys, xml.etree.ElementTree as ET
results, seed, runs = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
files = glob.glob(f"{results}/*.xml")
if not files:
    print("lab-chaos: no test results written — see app/build/lab-chaos.log"); sys.exit(3)
total = failed = 0
for f in sorted(files):
    root = ET.parse(f).getroot()
    err = "".join(e.text or "" for e in root.iter("system-err"))
    for tc in root.iter("testcase"):
        total += 1
        fail = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
        if fail is None:
            continue
        failed += 1
        name = f"{tc.get('classname')}.{tc.get('name')}"
        where = [l for l in err.splitlines() if "MESHLAB-CHAOS-FAIL" in l and name in l]
        first = (fail.get("message") or "").splitlines()[0][:200] if fail.get("message") else ""
        print(f"FAIL {name}\n     {where[-1].split('failed under ', 1)[-1] if where else '(seed not recorded)'}\n     {first}")
print(f"lab-chaos: {total - failed}/{total} scenarios survived {runs} seeded runs each (first seed {seed})")
sys.exit(1 if failed else 0)
EOF
status=$?
if ((status == 0 && gradle_status != 0)); then
  echo "lab-chaos: Gradle failed with every XML green — see app/build/lab-chaos.log"; exit "$gradle_status"
fi
exit "$status"
