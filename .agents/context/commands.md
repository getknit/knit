# Commands

Build/test/lint invocations. For *which* task to run *when* (and the JDK/lockfile rules), see
`rules/build-and-test.md`; for *why* the tooling is wired the way it is (detekt/ktlint/Kover Gradle
plugins), see `context/toolchain.md`.

```bash
./gradlew :app:assembleDebug        # build (assembleDebug does NOT compile test sources)
./gradlew :app:compileDebugKotlin   # fast compile check of main sources
./gradlew :app:testDebugUnitTest    # JVM unit tests — run these after touching mesh/protocol/data
./gradlew installDebug              # install on a connected device
./gradlew detekt                    # static analysis (dev.detekt plugin; reports in app/build/reports/detekt/)
./gradlew ktlintCheck               # Kotlin style/format lint (ktlint plugin; reports in build/reports/ktlint/)
./gradlew ktlintFormat              # ...and autocorrect the mechanical ktlint violations in place
./gradlew :app:koverHtmlReportDebug # test coverage (Kover) — HTML in app/build/reports/kover/htmlDebug/ (XML: koverXmlReportDebug)
./gradlew :app:connectedDebugAndroidTest -PseedDemo=true  # seeded UI instrumentation suite on ALL attached adb devices (Orchestrator)
./gradlew :app:pixel7api33DebugAndroidTest -PseedDemo=true # same suite on a Gradle-managed emulator ONLY (Pixel 7 @ API 33; ignores adb)
# Firebase Test Lab (physical-device) runs live in the maintainer .private/ overlay — absent in public clones
# Regenerate app/src/main/baseline-prof.txt (needs a device/emulator; see context/baseline-profile.md):
./gradlew -Pknit.baselineProfile=true :baselineprofile:connectedNonMinifiedReleaseAndroidTest
python3 scripts/adr.py new "<title>" --topics a,b  # scaffold an ADR (mints a collision-free id; never pick a number)
python3 scripts/adr.py index                      # regenerate .agents/memory/decisions.md (--check to verify, as CI does)
bash scripts/ide-diagnostics.sh --list          # changed .kt/.kts/.java files — what to iterate for IDE inspections
bash scripts/ide-diagnostics.sh <file>          # ...focus one in the RUNNING Studio, then read it via getDiagnostics
bash scripts/qodana.sh                          # the SAME engine over the whole tree, headless in Docker (~8 GB, slow)
bash scripts/qodana.sh --baseline               # ...accept today's findings as qodana.sarif.json, so later runs show only new ones
# Accessibility (ATF) suite — same checks as the Play pre-launch report; needs API 34+ (@SdkSuppress skips below):
./gradlew :app:pixel8api34DebugAndroidTest -PseedDemo=true -Pandroid.testInstrumentationRunnerArguments.package=app.getknit.knit.a11y  # headless emulator
```

- **JDK 21** is required (the Gradle daemon toolchain is pinned to 21).
- The IntelliJ inspection engine is a **third** analyzer, disjoint from Android Lint, ktlint and detekt
  (e.g. "Unused import directive" is only its). Two ways in, and the difference is not convenience:
  - `ide-diagnostics.sh` — one **focused** file in the Studio you already have open, read back via
    `getDiagnostics`, scoped to files changed vs HEAD. Fast, but it can never report a **global** finding
    ("this class has no callers", "this resource is referenced by nothing") because it only ever sees one
    file. Studio's own headless entry point does not work here (no Gradle sync → no project model); the
    script header explains why, and says not to rebuild it.
  - `qodana.sh` — the same engine over the **whole tree** in a container that syncs Gradle itself, so the
    global findings do land (228 accepted at the 2026-09-02 baseline, `qodana.sarif.json`; a run reports
    only what is NEW against it). `--baseline` re-adopts the current result. Two traps, both already
    handled and both worth knowing before you touch the config:
    - Qodana 2026.2 refuses AGP > 9.1.0 and this project is on 9.3.2. `qodana.yaml` sets
      `gradle.ide.support.future.agp.versions` to suppress that check — load-bearing, and its banner
      explains the one blind spot it leaves (`@Preview` reads as unused; the baseline absorbs it).
    - **Never cache the IDE's project state** (`.qodana/cache/{idea,android,262}`). Restoring it drops
      the Android facets and every AndroidLint inspection silently reports nothing — 38 findings to 0,
      exit code still 0. The script purges those dirs each run and both CI jobs cache only the
      downloads. Measured, not theoretical.
- `koverHtmlReportDebug` / `koverXmlReportDebug` are the per-*variant* tasks; the un-suffixed
  `koverHtmlReport` aggregates all variants. CI scrapes the % from `koverLogDebug`.
- The seeded UI suite must be built with `-PseedDemo=true` for **both** the app and test APKs — see
  `context/testing.md`.
- `adr.py` needs no JDK and no Gradle. `new` writes one file under `.agents/memory/decisions/` and prints
  the citation string; `index` rebuilds the router from those files, so the router is never hand-edited and
  two worktrees adding a decision on the same day cannot conflict on a number. Run `index` before
  committing — CI runs `index --check`, and `.githooks/pre-commit` runs it against the *staged* tree so a
  router left unstaged fails before the push, not after (enable the hooks with
  `git config core.hooksPath .githooks`).
