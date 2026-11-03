#!/usr/bin/env bash
#
# Run type-resolved detekt over every Kotlin source folder (`detektAll`) and tally what it finds.
#
# WHY THIS EXISTS. detekt runs type-resolved over every source folder (`detektAll`, which `detekt` stands for:
# app/build.gradle.kts, wear/build.gradle.kts, ADR 2026-11.acs5), as a handful of variant tasks, each with its own
# report. This runs every one in one Gradle pass and prints the findings by rule and source folder, so a rule's cost
# shows at a glance and a fix shows as a count going down. Exits 0 when the sweep ran, whatever it found; 1 when a
# task did not get as far as analysing (a compile error, a crash), since then the tally would read low.
#
# Usage: scripts/detekt-sweep.sh [--wear] [--reuse]
#   --wear   also sweep :wear (configures it with -Pknit.wear=true, as its builds do)
#   --reuse  tally the reports already on disk without running Gradle
# Writes build/reports/detekt-sweep.md (the tally) and leaves each task's report in <module>/build/reports/detekt/.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
wear=false
reuse=false
for arg in "$@"; do
  case "$arg" in
    --wear) wear=true ;;
    --reuse) reuse=true ;;
    *) echo "usage: scripts/detekt-sweep.sh [--wear] [--reuse]" >&2; exit 2 ;;
  esac
done

reports=(app:debug app:debugUnitTest app:debugAndroidTest app:debugScreenshotTest app:releaseStubs)
tasks=(:app:detektAll)
props=()
if $wear; then
  reports+=(wear:debug wear:debugUnitTest)
  tasks+=(:wear:detektAll)
  props+=(-Pknit.wear=true)
fi

marker=$(mktemp)
trap 'rm -f "$marker"' EXIT
log=build/reports/detekt-sweep.log
mkdir -p build/reports
if ! $reuse; then
  # A report older than the run's start means its task never analysed, unless Gradle skipped it as up to date; checked
  # below. --continue so one variant's findings never stop the next from running.
  touch "$marker"
  scripts/gradle-turn.sh ./gradlew "${tasks[@]}" "${props[@]}" --continue --console=plain >"$log" 2>&1 || true
fi

stale=()
files=()
for r in "${reports[@]}"; do
  xml="${r%%:*}/build/reports/detekt/${r#*:}.xml"
  name="${r#*:}"
  task=":${r%%:*}:detekt${name^}"
  # A task Gradle skipped as UP-TO-DATE (or restored FROM-CACHE) left the report its unchanged inputs produce: current,
  # though no newer than the marker. A clean variant is always skipped on the next run, since only failures rerun.
  if [[ ! -f "$xml" ]] || { ! $reuse && [[ ! "$xml" -nt "$marker" ]] &&
    ! grep -qxE "> Task ${task} (UP-TO-DATE|FROM-CACHE)" "$log"; }; then
    stale+=("$xml")
  else
    files+=("$xml")
  fi
done

python3 - "${files[@]}" <<'EOF' | tee build/reports/detekt-sweep.md
import collections, re, sys, xml.etree.ElementTree as ET

findings = set()  # (path, line, column, rule): :wear reads :app's wearstatus codec too, so one finding can come twice
for report in sys.argv[1:]:
    for file in ET.parse(report).getroot():
        for error in file:
            findings.add((file.get("name"), int(error.get("line")), int(error.get("column")), error.get("source").removeprefix("detekt.")))

def folder(path):
    m = re.search(r"(?:^|/)(app|wear)/src/([^/]+)/", path)
    return f"{m.group(1)}/{m.group(2)}" if m else "?"

by_rule = collections.defaultdict(collections.Counter)
by_file = collections.Counter()
folders = collections.Counter()
for path, _, _, rule in findings:
    by_rule[rule][folder(path)] += 1
    folders[folder(path)] += 1
    by_file[re.sub(r".*/(app|wear)/src/", r"\1/src/", path)] += 1

cols = sorted(folders, key=lambda f: -folders[f])
print(f"# detekt sweep: {len(findings)} findings, {len(by_rule)} rules\n")
print("| Rule | Total | " + " | ".join(cols) + " |")
print("|---|---:|" + "---:|" * len(cols))
for rule, counts in sorted(by_rule.items(), key=lambda kv: (-sum(kv[1].values()), kv[0])):
    print(f"| {rule} | {sum(counts.values())} | " + " | ".join(str(counts[c] or "") for c in cols) + " |")
print("| **Total** | **" + str(len(findings)) + "** | " + " | ".join(f"**{folders[c]}**" for c in cols) + " |")
print("\n## Most findings by file\n")
for path, n in by_file.most_common(25):
    print(f"- {n:4d}  {path}")
EOF

if ((${#stale[@]})); then
  echo >&2
  echo "These tasks did not analyse this run, so the tally above is short (see $log):" >&2
  printf '  %s\n' "${stale[@]}" >&2
  grep -E '^e: file:|FAILURE|What went wrong' -A2 "$log" | grep -v 'Analysis failed' | head -20 >&2 || true
  exit 1
fi
