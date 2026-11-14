#!/usr/bin/env bash
#
# Run the selected unit tests many times at once, the way a loaded CI runner runs them.
#
# WHY THIS EXISTS. A unit-test flake is a window a workstation closes before anyone looks: a late `first()` on a
# SharedFlow, a mock verified before the sibling coroutine called it, a random draw left at its default. CI's runners
# (one vCPU on GitLab, four on GitHub) open those windows one run in thirty; one green local run proves nothing. This
# script runs RUNS copies of the selection, SHARDS of them side by side in separate test JVMs (`unitStressShard<i>` in
# app/build.gradle.kts), so each copy competes for the cores the way a CI fork does, and tallies the per-class XMLs
# (never Gradle's summary — see the Gradle 9.5 result race in .agents/context/testing.md). It is the
# unit-test-reviewer's stress step; outside the mesh lab, which has its own seeded chaos (scripts/lab-chaos.sh).
#
# A green run does not clear a window you reasoned out: most of the flakes in testing.md's catalogue never reproduced
# on a workstation. A red one is a finding by construction: nothing here changes what the test does, only how
# crowded the machine is. The failing classes' XMLs and the Gradle log are kept in <git dir>/unit-stress/kept/.
#
# Usage: scripts/unit-stress.sh --tests <pattern> [--tests <pattern> ...] [--runs <n>] [--shards <k>|auto]
#                               [--quota <pct>]
#   --tests   Gradle --tests filter, repeatable and required (a class, a Class.method, or a package.*)
#   --runs    copies of the selection to run (default 8)
#   --shards  test JVMs side by side per round (default auto: a quarter of the cores, ~2.5 GB of free memory each,
#             at most 8 and never more than --runs)
#   --quota   also cap every round at <pct>% of one core (systemd-run + taskset), the one-vCPU runner's throttle
set -euo pipefail

cd "$(dirname "$0")/.."

patterns=()
runs=8
shards=auto
quota=""
while (($#)); do
  case "$1" in
    --tests) patterns+=("$2"); shift 2 ;;
    --runs) runs="$2"; shift 2 ;;
    --shards) shards="$2"; shift 2 ;;
    --quota) quota="$2"; shift 2 ;;
    -h | --help) sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//;$d'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
((${#patterns[@]})) || { echo "unit-stress: name the tests with --tests (a whole-suite stress is CI's job)" >&2; exit 2; }
for p in "${patterns[@]}"; do
  [[ "$p" != *,* ]] || { echo "unit-stress: a --tests pattern cannot hold a comma: $p" >&2; exit 2; }
done

if [[ "$shards" == auto ]]; then
  cores=$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN)
  mem_kb=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo 2>/dev/null || true)
  # A shard is a test JVM: the 1 GB heap testOptions gives it, ~0.75 GB of Robolectric metaspace, and its natives.
  by_mem=$((${mem_kb:-0} > 0 ? mem_kb / (2560 * 1024) : 8))
  shards=$((cores / 4))
  ((by_mem < shards)) && shards=$by_mem
  ((shards > 8)) && shards=8
fi
((shards > runs)) && shards=$runs
((shards >= 1)) || shards=1

results=app/build/test-results
state="$(git rev-parse --git-dir)/unit-stress"
rounds_dir="$state/rounds"
rm -rf "$rounds_dir"
mkdir -p "$rounds_dir" app/build
log=app/build/unit-stress.log
: >"$log"

echo "unit-stress: $runs run(s), $shards JVM(s) side by side, ${patterns[*]}${quota:+, CPUQuota=$quota%}"
done_runs=0
gradle_status=0
while ((done_runs < runs)); do
  k=$((runs - done_runs < shards ? runs - done_runs : shards))
  tasks=()
  for ((i = 0; i < k; i++)); do tasks+=(":app:unitStressShard$i"); done
  cmd=(./gradlew "${tasks[@]}" --continue --console=plain "-Pknit.unitStressShards=$k"
    "-Pknit.unitStressTests=$(IFS=,; echo "${patterns[*]}")")
  if [[ -n "$quota" ]]; then
    cmd=(systemd-run --user --scope -q -p "CPUQuota=${quota}%" taskset -c 0 "${cmd[@]}" --no-daemon)
  fi
  # Take turns with every other Gradle run here (a sibling reviewer's) and on the machine: scripts/gradle-turn.sh.
  cmd=(scripts/gradle-turn.sh "${cmd[@]}")
  rm -rf "$results"/unitStressShard*
  set +e
  "${cmd[@]}" >>"$log" 2>&1
  status=$?
  set -e
  ((status == 0)) || gradle_status=$status
  # Each round's XMLs move aside before the next round's copies overwrite the shard directories.
  for ((i = 0; i < k; i++)); do
    if [[ -d "$results/unitStressShard$i" ]]; then cp -r "$results/unitStressShard$i" "$rounds_dir/run$((done_runs + i))"; fi
  done
  done_runs=$((done_runs + k))
done

python3 - "$rounds_dir" "$runs" "$gradle_status" "$state/kept" "$log" "$shards" "$quota" <<'EOF'
import glob, os, shutil, sys, time, xml.etree.ElementTree as ET
rounds, runs, gradle_status, kept_root, log, shards, quota = sys.argv[1:8]
runs, gradle_status = int(runs), int(gradle_status)
KEPT = 10  # failing stresses whose reports are kept; the oldest beyond this are dropped
files = sorted(glob.glob(f"{rounds}/run*/*.xml"))
if not files:
    print(f"unit-stress: no test results written — the build failed or the patterns matched nothing; see {log}")
    sys.exit(3)
tests = {}  # name -> {"ran": n, "fails": [(run, first line)], "skipped": bool}
failing = set()
for f in files:
    run = os.path.basename(os.path.dirname(f))
    for tc in ET.parse(f).getroot().iter("testcase"):
        name = f"{tc.get('classname')}.{tc.get('name')}"
        t = tests.setdefault(name, {"ran": 0, "fails": [], "skipped": False})
        if tc.find("skipped") is not None:
            t["skipped"] = True
            continue
        t["ran"] += 1
        fail = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
        if fail is not None:
            failing.add(f)
            msg = fail.get("message") or fail.get("type") or ""
            t["fails"].append((run, (msg.splitlines() or [""])[0][:200]))
lines = []
failed = [n for n, t in tests.items() if t["fails"]]
for n in failed:
    t = tests[n]
    lines.append(f"FAIL {n}: {len(t['fails'])}/{t['ran']} runs")
    for run, first in t["fails"][:3]:
        lines.append(f"     {run}: {first}")
short = [n for n, t in tests.items() if not t["skipped"] and t["ran"] < runs]
if short:
    lines.append(f"unit-stress: {len(short)} test(s) ran fewer than {runs} times (a JVM died?) — see {log}")
skipped = [n for n, t in tests.items() if t["skipped"]]
if failing:
    kept = os.path.join(kept_root, time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(kept, exist_ok=True)
    for f in sorted(failing):
        shutil.copy2(f, os.path.join(kept, f"{os.path.basename(os.path.dirname(f))}-{os.path.basename(f)}"))
    shutil.copy2(log, kept)
    for old in sorted(glob.glob(os.path.join(kept_root, "*")), key=os.path.getmtime)[:-KEPT]:
        shutil.rmtree(old, ignore_errors=True)
    lines.append(f"unit-stress: failing reports kept in {kept}")
ran = len(tests) - len(skipped)
lines.append(f"unit-stress: {ran - len(failed)}/{ran} tests survived {runs} runs each ({shards} JVMs side by side"
             + (f", CPUQuota={quota}%" if quota else "") + ")" + (f", {len(skipped)} skipped" if skipped else ""))
status = 1 if failed else 0
if not ran:
    lines.append("unit-stress: nothing ran — every selected test skipped, so this run says nothing about them")
    status = 3
elif not failed and gradle_status != 0:
    lines.append(f"unit-stress: Gradle failed with every XML green — see {log}")
    status = gradle_status
print("\n".join(lines))
sys.exit(status)
EOF
