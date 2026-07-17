#!/usr/bin/env bash
#
# Surface Android Studio's OWN inspection findings (the IntelliJ inspection engine) to a coding agent.
# This is the only tool that reproduces what the Studio editor actually shows you — e.g. "Unused import
# directive" (`KotlinUnusedImport`), which is neither an Android Lint check (`./gradlew lint`) nor a
# ktlint rule. (detekt's `style > UnusedImports` is enabled here and covers *that* one specific case,
# but not the rest of the inspection engine.)
#
# It works by driving the Studio you ALREADY have open: this script focuses a file in the running IDE,
# waits for its analysis to settle, and then the agent reads the diagnostics back over the IDE bridge
# (Claude Code's `getDiagnostics`). The script itself prints nothing useful — it is one half of a loop,
# and the agent supplies the other half.
#
#   ┌───────────────────────────────────────────────────────────────────────────────────────────────┐
#   │ WHY THIS DRIVES A LIVE IDE INSTEAD OF RUNNING `bin/inspect.sh` HEADLESSLY                      │
#   │ (this replaces scripts/ide-inspect.sh, deleted 2026-07-16 — do NOT rebuild it)                 │
#   │                                                                                                │
#   │ Android Studio keeps the Gradle-derived module graph in the IDE's *system* dir, not in .idea/  │
#   │ (.idea/misc.xml sets ExternalStorageConfigurationManager enabled=true; .idea/modules.xml lists │
#   │ only the root Knit.iml — no app module). A headless run with an isolated IDE home therefore    │
#   │ starts with ZERO source roots, and the only thing that can build the model is a Gradle sync —  │
#   │ which the headless entry points never await: GradleWarmupConfigurator.importProjects ends at   │
#   │ AutoImportProjectTracker.scheduleProjectRefresh(), fire-and-forget (the whole                  │
#   │ CommandLineInspectionProjectConfigurator API is @Obsolete since 2024.1). `studio.sh warmup`    │
#   │ narrates the race in log/warmup/warmup.log: the import starts, ~2s later "All configuration    │
#   │ phases are completed" → "Cancelling Running Builds" kills it mid-flight. The inspection then   │
#   │ analyzes an empty project and dies with `IllegalStateException: Tools are not initialized`.    │
#   │                                                                                                │
#   │ The old script blamed a `Descriptions are missed for tools: ComposePreview…` error and told    │
#   │ you to retry after a Studio update. That was a MISDIAGNOSIS: it's a bare non-fatal LOG.error   │
#   │ (InspectionApplicationBase.runAnalysis:552) that the run walks straight past — the real fatal  │
#   │ is getTools() at :564. Verified still broken on Quail 2 (2026.1.2). No Studio update fixes it. │
#   │                                                                                                │
#   │ So: don't rebuild the project model — reuse the one your running Studio already synced.        │
#   │ For whole-project / CI coverage this is the wrong tool; use Qodana (`qodana-android`).         │
#   └───────────────────────────────────────────────────────────────────────────────────────────────┘
#
# Usage:
#   scripts/ide-diagnostics.sh --list            List changed Kotlin/Java files vs HEAD (what to iterate).
#   scripts/ide-diagnostics.sh <file>            Focus <file> in the running IDE and wait for analysis.
#
#   The agent loop is one file at a time:
#       scripts/ide-diagnostics.sh --list
#       for each file:  scripts/ide-diagnostics.sh <file>   then call getDiagnostics
#
# Why one file at a time: the IDE bridge only answers for the FOCUSED editor. Opening several files at
# once leaves only the last one active; every other file (and any file that is merely open in a
# background tab) returns "Timeout getting diagnostics". Same reason for the settle wait — querying a
# file the instant it opens races the analyzer and also times out.
#
# Requirements:
#   * Android Studio already RUNNING with this project open and synced. This script will not start it;
#     a cold IDE has no project model, which is the whole problem described above.
#   * The agent's IDE bridge connected to that Studio instance.
#
# Env overrides:
#   STUDIO_HOME   Android Studio install dir (must contain bin/studio.sh). Auto-detected otherwise.
#   SETTLE        Seconds to wait for analysis after focusing a file (default: 6). Raise for big files.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SETTLE="${SETTLE:-6}"

usage() { sed -n '38,44p' "$0"; }

[[ $# -eq 0 ]] && { usage; exit 2; }

# --- --list: what the agent should iterate over ------------------------------------------------------
if [[ "$1" == "--list" ]]; then
  cd "$REPO_ROOT"
  git diff --name-only HEAD -- '*.kt' '*.kts' '*.java'
  git ls-files --others --exclude-standard -- '*.kt' '*.kts' '*.java'
  exit 0
fi
[[ "$1" == "-h" || "$1" == "--help" ]] && { usage; exit 0; }

# --- resolve the file -------------------------------------------------------------------------------
TARGET="$1"
[[ -f "$TARGET" ]] || TARGET="$REPO_ROOT/$1"
if [[ ! -f "$TARGET" ]]; then
  echo "ERROR: no such file: $1" >&2
  exit 2
fi
TARGET="$(cd "$(dirname "$TARGET")" && pwd)/$(basename "$TARGET")"

# --- locate Android Studio --------------------------------------------------------------------------
if [[ -z "${STUDIO_HOME:-}" ]]; then
  for cand in \
    "$HOME/Downloads/android-studio" \
    /opt/android-studio \
    /usr/local/android-studio \
    "$HOME/android-studio"; do
    [[ -x "$cand/bin/studio.sh" ]] && STUDIO_HOME="$cand" && break
  done
fi
if [[ -z "${STUDIO_HOME:-}" || ! -x "$STUDIO_HOME/bin/studio.sh" ]]; then
  echo "ERROR: could not find Android Studio. Set STUDIO_HOME to an install dir containing bin/studio.sh." >&2
  exit 3
fi

# --- require a RUNNING instance ---------------------------------------------------------------------
# studio.sh on a cold IDE would launch a fresh one that has to sync from scratch — and an unsynced IDE
# reports nothing useful. Fail loudly instead of silently booting a useless instance.
if ! pgrep -f "$STUDIO_HOME/bin/studio" >/dev/null 2>&1; then
  echo "ERROR: Android Studio is not running. Open this project in Studio, let it finish syncing, then retry." >&2
  exit 4
fi

# --- focus the file and let the analyzer settle -----------------------------------------------------
# The launcher forwards the path to the running instance over its socket and exits (~0.5s); it does not
# start a second IDE.
"$STUDIO_HOME/bin/studio.sh" "$TARGET" >/dev/null 2>&1 || {
  echo "ERROR: failed to hand $TARGET to the running Studio." >&2
  exit 5
}
sleep "$SETTLE"

echo "Focused in Studio: ${TARGET#"$REPO_ROOT"/}"
echo "Analysis settled (${SETTLE}s). Now read it with getDiagnostics (no uri = the focused editor)."
