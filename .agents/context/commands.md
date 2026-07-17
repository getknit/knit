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
bash scripts/ftl.sh                 # build seeded APKs + run the suite on Firebase Test Lab physical devices
bash scripts/ftl-uiauto.sh          # ...only the black-box UIAutomator package on FTL
bash scripts/ide-diagnostics.sh --list          # changed .kt/.kts/.java files — what to iterate for IDE inspections
bash scripts/ide-diagnostics.sh <file>          # ...focus one in the RUNNING Studio, then read it via getDiagnostics
# Accessibility (ATF) suite — same checks as the Play pre-launch report; needs API 34+ (@SdkSuppress skips below):
./gradlew :app:pixel8api34DebugAndroidTest -PseedDemo=true -Pandroid.testInstrumentationRunnerArguments.package=app.getknit.knit.a11y  # headless emulator
bash scripts/ftl-a11y.sh            # ...on an FTL API-34+ device (defaults to b0q@36)
```

- **JDK 21** is required (the Gradle daemon toolchain is pinned to 21).
- `ide-diagnostics.sh` is the ONLY way to see the Studio editor's own inspections (e.g. "Unused import
  directive") — they are not Android Lint, ktlint, or detekt findings. It drives the Studio you already
  have open, one file at a time, because the IDE bridge only answers for the *focused* editor. Running the
  inspection engine headlessly does **not** work on this project (no Gradle sync → no project model); the
  script header explains why in full. For whole-project/CI coverage use Qodana, not this.
- `koverHtmlReportDebug` / `koverXmlReportDebug` are the per-*variant* tasks; the un-suffixed
  `koverHtmlReport` aggregates all variants. CI scrapes the % from `koverLogDebug`.
- The seeded UI suite must be built with `-PseedDemo=true` for **both** the app and test APKs — see
  `context/testing.md`.
