# Toolchain (bleeding-edge — do not "fix" these without reading why)

This project intentionally runs on very new tooling (AGP 9.2.1, Gradle 9.4.1, Kotlin 2.4.0,
Compose BOM 2026.06). That forces several non-obvious choices. **Read this before changing build
config, dependencies, or the DI graph.**

## Why these choices

- **DI is Koin, not Hilt.** Hilt's Gradle plugin is broken on AGP 9.x in this window
  (dagger#5083 / #5099). Koin is pure-Kotlin runtime DI with no Gradle plugin / no annotation
  processor, so it can't be broken by AGP. Koin is started in `KnitApplication`; modules live in
  `app/src/main/java/app/getknit/knit/di/`.
- **Built-in Kotlin is overridden to 2.4.0, not AGP's bundled 2.2.10.** AGP 9.2.1 ships KGP 2.2.10,
  whose Kotlin-2.2 compiler cannot read class metadata produced by Kotlin 2.4 (this is what used to
  pin Coil to 3.3.0). The root `build.gradle.kts` puts KGP 2.4.0 on the buildscript classpath
  (`classpath(libs.kotlin.gradle.plugin)`) so built-in Kotlin compiles with 2.4.0 — a supported combo
  (Kotlin 2.4 requires AGP 9.1+ per Google's AGP/Kotlin matrix). **Bumping AGP does not move Kotlin**:
  the 9.3 line (now at RC) and 9.4-alpha still bundle 2.2.10, so the override — not an AGP bump — is
  the lever. Keep KGP and the `ksp` version in lockstep with `kotlin`; KSP adopted independent (KSP2)
  versioning at 2.3.0 (decoupled, Kotlin 2.2+), so it no longer uses the old `<kotlin>-<ksp>` scheme.
- **`android.disallowKotlinSourceSets=false`** is set in `gradle.properties`. AGP 9's built-in
  Kotlin otherwise rejects the `kotlin.sourceSets` DSL that KSP (Room's processor) uses.
- **No explicit `kotlin-android` plugin.** AGP 9's built-in Kotlin handles compilation; only the
  `kotlin.plugin.compose`, `kotlin.plugin.serialization`, and `ksp` plugins are applied.
- Pin third-party versions in `gradle/libs.versions.toml` (version catalog); probe Maven before
  bumping anything that could pull in a newer Kotlin stdlib.

## Static analysis: standalone CLIs, not Gradle plugins

`detekt` runs the standalone CLI (NOT the Gradle plugin) from an isolated `detektCli` configuration
in the root build, mirroring CI's `verify:detekt` job — same jar version (`detekt` in the version
catalog ↔ `DETEKT_VERSION` in `.gitlab-ci.yml`), same `config/detekt/detekt.yml`, same flags. It
never touches `:app`'s classpath, so it can't perturb the app build. The task exits non-zero when
detekt finds issues; HTML/XML/SARIF reports land in `build/reports/detekt/`.

`ktlint` runs the same way — the standalone ktlint CLI (NOT the Gradle plugin, same toolchain reason
as detekt) from an isolated `ktlintCli` configuration in the root build, pinned to the `shadowed`
(fat-jar) variant so it pulls no rulesets onto `:app`. Rules are the ktlint standard ruleset, configured
via the repo-root **`.editorconfig`** (which the CLI auto-discovers) — including the `@Composable`
function-naming opt-out (`ktlint_function_naming_ignore_when_annotated_with = Composable`). The task
lints `app/src/**/*.kt` + the `*.gradle.kts` scripts and exits non-zero on any violation; report at
`build/reports/ktlint/`. **It does NOT autocorrect** (how to fix violations is in
`rules/build-and-test.md`). `detekt` and `ktlint` both run as `Stop` hooks
(`.claude/hooks/gradle-{detekt,ktlint}-stop.sh`) alongside `./gradlew lint`.

## Coverage: the one deliberate plugin exception

`kover` (test coverage) is the **one deliberate exception** to the "tooling runs as a standalone CLI, not
a Gradle plugin" rule above. detekt/ktlint are source analyzers, so a CLI over the sources is strictly
better (isolated, can't perturb `:app`). Coverage is different in kind: it **must** instrument bytecode and
hook the test run, which only the Gradle plugin does cleanly (a CLI would have to swap offline-instrumented
classes into AGP's unit-test task). It's low-risk on this toolchain — unlike Hilt it does no compile-time
codegen; it hooks `testDebugUnitTest` *post-compile* and only adds Java-only agent/offline-runtime jars to
test-scope configs, so it never touches `:app`'s Kotlin-2.4 compile/runtime classpath (verified:
`assembleDebug` + `lint` unaffected). Note: **Kover 0.9.1 applied but silently failed to detect AGP 9.2.1's
build variants** (no per-variant tasks, empty report) — **0.9.8** fixes it, so don't downgrade below it.
Coverage is measured from the debug unit tests (`koverHtmlReportDebug` / `koverXmlReportDebug` — the
per-*variant* tasks; the un-suffixed `koverHtmlReport` aggregates all variants). Only *generated* code is
excluded (Room `*_Impl`, Compose `ComposableSingletons`, `BuildConfig`) via `kover { reports { filters } }`
in `app/build.gradle.kts` — everything hand-written stays measured. **In Kover class globs `*` does NOT
cross the package `.` — use `**` to span packages** (a bare `*_Impl` matches nothing). CI runs it as the
advisory `test:coverage` job (mirrors `verify:detekt`), which archives the HTML/XML and scrapes the % from
`koverLogDebug`. Adding the plugin changed the lockfile (`kover-jvm-agent`) — regenerate per the lockfile
rule in `rules/build-and-test.md` after any Kover bump.
