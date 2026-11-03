---
id: "2026-11.acs5"
slug: detekt-runs-only-type-resolved
title: "detekt runs only type-resolved, on every source folder"
date: 2026-11-02
topics: [build, toolchain, static-analysis]
---

# ADR 2026-11.acs5 — detekt runs only type-resolved, on every source folder

Status: Accepted (2026-11-02). Amends ADR 011, whose detekt ran "without type resolution, the same scope and
behaviour as the old CLI".

**Context:** ADR 011 moved detekt into the `dev.detekt` Gradle plugin with no compile classpath, reading
`src/main` and `src/test` as the old CLI did. That run skipped every rule that needs types without a word, and
in detekt 2.0 that includes `UnusedImport` and `LongParameterList`, both tuned in `config/detekt/detekt.yml`
and so believed to be gating. It also never read `src/debug`, `src/release`, `src/androidTest` or
`src/screenshotTest`, so the import bans, `HardcodedUiText` and `BarePeriodicLoop` did not reach them. The
first type-resolved sweep over every folder (`scripts/detekt-sweep.sh`, 2026-11-02) found 808 findings in 34
rules on `:app` and `:wear`. They were cleared in code, or by a rule switched off or narrowed in `detekt.yml`
with its reason there, and none was baselined.

**Decision:** detekt runs one way, type-resolved, on every Kotlin source folder of `:app` and `:wear`.

- `detektAll` in each module runs the plugin's variant tasks, each against its compilation's classpath with
  the shared config, baseline and `knit` rules. On `:app` these are `detektDebug` (main and debug),
  `detektDebugUnitTest`, `detektDebugAndroidTest` and `detektDebugScreenshotTest`, plus `detektReleaseStubs`.
  That last one reads `src/release`'s stubs against the debug compile's classpath, because `detektRelease`
  would compile all of release to read two files.
- The plugin's plain `detekt` task is switched off and depends on `detektAll`. So `./gradlew detekt`, `check`,
  the Stop hook (`review-gate.sh`) and both CIs all get the type-resolved run, and nothing can run the old
  one by name.
- CI caches what this costs. GitLab's `verify:detekt` runs on skuld after `build:debug`, whose build cache
  already holds the debug and unit-test compiles. It saves the deps and build caches even when detekt finds
  something, so the next pipeline reruns only what changed. GitHub's `verify` job passes `--build-cache` over
  what `setup-gradle` restores. Both pass `-Pknit.wear=true`, so CI covers `:wear` as well.

The alternative a reader reaches for first is keeping plain `detekt` beside it as a quick check. It was not
kept. A check that reads half the folders and skips the typed rules is what let 808 findings collect while
the gate read green.

**Consequences / limits:**

- detekt now compiles what it reads. A cold run builds the debug app and its unit, instrumented and screenshot
  test sources, which is minutes more on a cold tree. A broken androidTest or screenshot-test compile now fails
  detekt as well as the instrumented and screenshot jobs.
- The Stop hook's finding filter matches detekt's and kotlinc's `path:line:col message` lines as well as
  ktlint's `path:line:col:`. Without that it printed only the failure banner.
- The local hook runs without `-Pknit.wear=true`, as its ktlint and lint do, so `:wear` is checked by CI and
  `scripts/detekt-sweep.sh --wear`.
- `knit` rules and `ForbiddenMethodCall` can now use types. The BitmapFactory, OkHttp and other bans stay
  import rules (ADR 051), and the `knit` rules' tests still parse without compiling.
- On GitLab the analysis runs in the single-use daemon's 1664 MB heap. `detektDebug` plus
  `detektDebugUnitTest` reran in 2 min 23 s under that cap locally, with SerialGC and one worker (2026-11-02).
- The guards are the disabled-and-depending `detekt` task in `app/build.gradle.kts` and `wear/build.gradle.kts`,
  `review-gate.sh --checks`, and the two CI jobs. `scripts/detekt-sweep.sh` tallies a run by rule and source
  folder.
