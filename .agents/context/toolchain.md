# Toolchain (bleeding-edge — do not "fix" these without reading why)

This project intentionally runs on very new tooling (AGP 9.3.0, Gradle 9.5.0, Kotlin 2.4.0,
Compose BOM 2026.06). That forces several non-obvious choices. **Read this before changing build
config, dependencies, or the DI graph.**

## Why these choices

- **DI is Koin, not Hilt.** Hilt's Gradle plugin is broken on AGP 9.x in this window
  (dagger#5083 / #5099). Koin is pure-Kotlin runtime DI with no Gradle plugin / no annotation
  processor, so it can't be broken by AGP. Koin is started in `KnitApplication`; modules live in
  `app/src/main/java/app/getknit/knit/di/`.
- **Built-in Kotlin is overridden to 2.4.0, not AGP's bundled 2.2.10.** AGP 9.3.0 ships KGP 2.2.10,
  whose Kotlin-2.2 compiler cannot read class metadata produced by Kotlin 2.4 (this is what used to
  pin Coil to 3.3.0). The root `build.gradle.kts` puts KGP 2.4.0 on the buildscript classpath
  (`classpath(libs.kotlin.gradle.plugin)`) so built-in Kotlin compiles with 2.4.0 — a supported combo
  (Kotlin 2.4 requires AGP 9.1+ per Google's AGP/Kotlin matrix). **Bumping AGP does not move Kotlin**:
  the 9.3 line we now build on (and 9.4) still bundle 2.2.10, so the override — not an AGP bump — is
  the lever. Keep KGP and the `ksp` version in lockstep with `kotlin`; KSP adopted independent (KSP2)
  versioning at 2.3.0 (decoupled, Kotlin 2.2+), so it no longer uses the old `<kotlin>-<ksp>` scheme.
- **`android.disallowKotlinSourceSets=false`** is set in `gradle.properties`. AGP 9's built-in
  Kotlin otherwise rejects the `kotlin.sourceSets` DSL that KSP (Room's processor) uses.
- **No explicit `kotlin-android` plugin.** AGP 9's built-in Kotlin handles compilation; only the
  `kotlin.plugin.compose`, `kotlin.plugin.serialization`, and `ksp` plugins are applied.
- Pin third-party versions in `gradle/libs.versions.toml` (version catalog); probe Maven before
  bumping anything that could pull in a newer Kotlin stdlib.

## Static analysis: detekt / ktlint Gradle plugins

`detekt` runs via the **`dev.detekt` Gradle plugin** (detekt 2.0.x — the first line that supports
Gradle 9; 1.23.x capped at Gradle 8.12.1, and `dev.detekt` is the new plugin/group id). Applied on
`:app`, it analyzes `src/main/java` + `src/test/java` **without type resolution** (no compile classpath is
wired — same scope/behavior as the old CLI), overlaying `config/detekt/detekt.yml` on detekt's defaults
(`buildUponDefaultConfig = true`). The `detekt` task exits non-zero on findings; reports land in
`app/build/reports/detekt/`. CI's `verify:detekt` job runs `./gradlew detekt`. (Config note: detekt 2.0
renamed several config keys — `LongParameterList.constructorThreshold/functionThreshold` →
`allowedConstructorParameters/allowedFunctionParameters`, `TooManyFunctions.thresholdIn*` →
`allowedFunctionsPer*`, and the style rule `UnusedImports` → singular `UnusedImport`.)

`ktlint` runs via the **`org.jlleitschuh.gradle.ktlint` plugin**, applied on the root project (which
lints the `*.gradle.kts` scripts) and on `:app` (which lints the Kotlin sources). The ktlint *tool*
version is pinned to `libs.versions.ktlint` — independent of the plugin version (`ktlintPlugin`) — so rule
behavior stays fixed. Rules are the ktlint standard ruleset, configured via the repo-root **`.editorconfig`**
(auto-discovered), including the `@Composable` function-naming opt-out
(`ktlint_function_naming_ignore_when_annotated_with = Composable`). `./gradlew ktlintCheck` verifies (reports
in `build/reports/ktlint/`); **`./gradlew ktlintFormat` autocorrects** — a capability the old CLI lacked.
`detekt` and `ktlint` both run as `Stop` hooks (`.claude/hooks/gradle-{detekt,ktlint}-stop.sh`) alongside
`./gradlew lint`.

These are ordinary Gradle plugins now (this reverses the old "standalone CLIs" doctrine — ADR 007 is
Superseded by ADR 011). They still add nothing to `:app`'s compile/runtime classpath — detekt and ktlint
each run analysis in their own isolated task classpath — so the Kotlin-2.4-metadata hazard that motivated
the CLI approach doesn't apply (verified: `assembleDebug` + `lint` unaffected). Applying them locked new
tool configurations into `app/gradle.lockfile` — regenerate per the lockfile rule in
`rules/build-and-test.md` after any detekt/ktlint version bump.

## Coverage: Kover

`kover` (test coverage) runs as a Gradle plugin — coverage **must** instrument bytecode and hook the test
run, which only the plugin does cleanly (a CLI would have to swap offline-instrumented classes into AGP's
unit-test task). It's low-risk on this toolchain — unlike Hilt it does no compile-time
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

## Room schema export: the `androidx.room` Gradle plugin

Room's schema JSON is exported by the **`androidx.room` Gradle plugin** (`room { schemaDirectory("$projectDir/schemas") }`
in `app/build.gradle.kts`), not the raw `ksp { arg("room.schemaLocation", …) }` — the plugin *rejects* an
explicit `room.schemaLocation` arg, so don't add one back. It requires `exportSchema = true` on
`KnitDatabase` and Room ≥ 2.7.0-alpha13 for KSP2 support (we're on 2.8.4 / KSP 2.3.9). With only build
types (no product flavors) it writes to the flat `app/schemas/<db-class>/<version>.json` — the same layout
the KSP arg produced — so the debug-asset wiring and `KnitDatabaseMigrationTest` are unchanged. **Gotcha:**
KSP incremental caching can skip re-export when the committed schema is unchanged (`copyRoomSchemas` shows
`NO-SOURCE`); to force a fresh export after a `@Database` bump, clear `app/schemas/` and rebuild.
