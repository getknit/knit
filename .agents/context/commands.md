# Commands

Build/test/lint invocations. For *which* task to run *when* (and the JDK/lockfile rules), see
`rules/build-and-test.md`; for *why* the tooling is wired the way it is (standalone detekt/ktlint CLI,
the Kover exception), see `context/toolchain.md`.

```bash
./gradlew :app:assembleDebug        # build (assembleDebug does NOT compile test sources)
./gradlew :app:compileDebugKotlin   # fast compile check of main sources
./gradlew :app:testDebugUnitTest    # JVM unit tests — run these after touching mesh/protocol/data
./gradlew installDebug              # install on a connected device
./gradlew detekt                    # static analysis via the standalone detekt CLI (reports in build/reports/detekt/)
./gradlew ktlint                    # Kotlin style/format lint via the standalone ktlint CLI (reports in build/reports/ktlint/)
./gradlew :app:koverHtmlReportDebug # test coverage (Kover) — HTML in app/build/reports/kover/htmlDebug/ (XML: koverXmlReportDebug)
./gradlew :app:connectedDebugAndroidTest -PseedDemo=true  # seeded UI instrumentation suite on ALL attached adb devices (Orchestrator)
./gradlew :app:pixel7api33DebugAndroidTest -PseedDemo=true # same suite on a Gradle-managed emulator ONLY (Pixel 7 @ API 33; ignores adb)
bash scripts/ftl.sh                 # build seeded APKs + run the suite on Firebase Test Lab physical devices
```

- **JDK 21** is required (the Gradle daemon toolchain is pinned to 21).
- `koverHtmlReportDebug` / `koverXmlReportDebug` are the per-*variant* tasks; the un-suffixed
  `koverHtmlReport` aggregates all variants. CI scrapes the % from `koverLogDebug`.
- The seeded UI suite must be built with `-PseedDemo=true` for **both** the app and test APKs — see
  `context/testing.md`.
