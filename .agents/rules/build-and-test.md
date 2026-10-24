# Build & test discipline

Which task to run when, and the two ways the build bites back. Full command list: `context/commands.md`.
Why the tooling is wired this way (detekt/ktlint/Kover Gradle plugins): `context/toolchain.md`.

## Run the right task

- **JDK 21** is required (the Gradle daemon toolchain is pinned to 21).
- Run `./gradlew :app:testDebugUnitTest` after touching `mesh/`, `protocol/`, or `data/`. **`assembleDebug`
  does NOT compile test sources**, so it won't catch a broken test double.
- After changing the `MeshTransport` interface, run `testDebugUnitTest` — `RecordingTransport` in
  `MeshRouterTest` implements it and won't be caught by `assembleDebug` (see `rules/mesh.md`).
- Run `./gradlew :app:assembleDebug` to validate a build; `./gradlew :app:compileDebugKotlin` for a fast
  main-sources compile check.

## Never overlap Gradle runs in one worktree

Two `testDebugUnitTest` runs in one checkout rewrite each other's `app/build/test-results/testDebugUnitTest/binary/`,
and the first dies with `NoSuchFileException` though every test passed; two compiles of a moved tree write the same
intermediates. Chain your own runs in one command, never one in the background and one in the foreground. Anything
that may run beside another Gradle user — a reviewer or owner persona, `lab-chaos.sh`, `unit-stress.sh` — goes through
`scripts/gradle-turn.sh <command>`, a per-worktree `flock` in the git dir (re-entrant, so a script holding it can nest),
so the reviewers the Stop hook spawns at once read in parallel and take turns at Gradle.

## ktlint autocorrects; detekt doesn't

Both run as `Stop` hooks (`./gradlew ktlintCheck` / `./gradlew detekt`) alongside `./gradlew lint`.
**`./gradlew ktlintFormat` autocorrects** the mechanical ktlint violations in place — run it, then
re-check. detekt has no autofix; fix its findings by hand. The two tools can disagree: ktlint's
one-arg-per-line wrapping can push a function past detekt's `LongMethod=60` — **suppress `LongMethod` on
that function** (with a one-line reason) rather than fighting the formatter (see `MeshManager.sendChat`,
`NotificationChannels.ensure`).

## Regenerate the lockfile for ALL configurations after a version bump

`app/build.gradle.kts` sets `dependencyLocking { lockAllConfigurations() }`, so `app/gradle.lockfile` pins
every configuration. `--write-locks` only rewrites the configs a given task actually resolves:
`:app:assembleDebug` + `:app:testDebugUnitTest` leave the *instrumented*-test configs
(`debugAndroidTestCompileClasspath`, …) at their old locked versions. `./gradlew lint` (which the repo's
stop-hook runs) builds the androidTest lint model, hits those stale locks, and fails with a wall of
"Cannot find a version … {strictly <old>} … enforced by Dependency Locking" errors — which look like a
resolution break but are just a half-updated lockfile. Always regenerate with
`./gradlew :app:dependencies --write-locks` (resolves every configuration), then `./gradlew lint`. This
applies after **any** dependency change, including adding a test dep or bumping Kover.

Write the lock with no maintainer flag in effect: CI and F-Droid build without one. `~/.gradle/gradle.properties`
counts. `translate=true` there puts translate-native on every runtime classpath, so pass `-Ptranslate=false`.

## Regenerate dependency verification in the same commit

Gradle checks every artifact it downloads against `gradle/verification-metadata.xml` and the keys in
`gradle/verification-keyring.keys` (ADR 2026-10.rysj). It fails with "Dependency verification failed" when an artifact
has neither a listed SHA-256 nor a signature from a key trusted for its group. After any change that moves a downloaded
artifact (a catalog bump, a new dependency, a plugin, AGP or Gradle itself), run `scripts/verification-metadata.sh`
after the lockfile, and read its diff before committing:

- a new `trusted-key` or checksum is a new party the build trusts;
- a changed checksum under an unchanged version is never noise.

Never hand-edit an entry to make a build pass. A Gradle bump also moves `distributionSha256Sum` in
`gradle/wrapper/gradle-wrapper.properties`: take it from `https://services.gradle.org/distributions/<zip>.sha256`.
