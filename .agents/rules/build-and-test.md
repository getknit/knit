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

## Take turns at Gradle, in the worktree and on the machine

Two `testDebugUnitTest` runs in one checkout rewrite each other's `app/build/test-results/testDebugUnitTest/binary/`,
and the first dies with `NoSuchFileException` though every test passed; two compiles of a moved tree write the same
intermediates. Runs in different worktrees write different outputs but share the memory: each brings a daemon and test
JVMs of ~2.5 GB, and parallel worktree agents have run the 62 GB workstation out of memory. So every Gradle run an
agent starts, the main session's included, goes through `scripts/gradle-turn.sh <command>`; a `PreToolUse` hook
(`.claude/hooks/gradle-turn-gate.py`) blocks a bare `./gradlew`. The script takes this worktree's `flock` in the git
dir, then one of the machine's slots in `~/.gradle/knit-gradle-turn/`, which every worktree shares: three on the
24-core, 62 GB workstation (one per 8 cores and per 20 GB, at most 4; `KNIT_GRADLE_SLOTS=<n>` overrides). A build alone
always starts. Beside others it waits for `KNIT_GRADLE_MIN_FREE_MB` (default 12288) of free memory, and that much again
for each build that began in the last five minutes and has not grown into its memory yet; the free memory also counts
what the CI runners on the workstation hold, since they build outside the turn. A long run (`KNIT_GRADLE_LONG=1`, or any
`-Pknit.meshSim` outing) never takes slot 0, so an hour-long outing or sweep loop always leaves a slot for short runs:
set it for a sweep loop or a soak. A waiting run prints who holds the slots. Both locks are re-entrant, so a script
holding the turn (`lab-chaos.sh`, `unit-stress.sh`) can nest, and the reviewers the Stop hook spawns at once read in
parallel and take turns at Gradle. Still chain your own runs in one command, never one in the background and one in the foreground.
The script also runs the command in a systemd scope of CPU weight 20, so the daemon it starts and that daemon's test
JVMs yield the CPU to the IDE and the terminals.

## Every worktree shares one build cache

On the workstation `~/.gradle/gradle.properties` turns on Gradle's local build cache, and
`~/.gradle/init.d/build-cache.gradle` puts it on the data drive. Every worktree and clone shares it, and Gradle drops entries unused for 7 days. A task whose
inputs any worktree has built comes back `FROM-CACHE`: a fresh worktree's first compile took 3 s in place of 1 min 44 s
(2026-11-19). That includes `testDebugUnitTest`. A hit restores that run's XMLs and runs no test, which is the right
answer for the same inputs. Pass `--rerun` when the tests must run again: a flake hunt, or a test that reads what Gradle
cannot see, such as an environment variable (`LocalIssuerCrossCheckTest`). A run that is a draw or writes outside its
outputs never reads or writes the cache: `-Pknit.labChaos`, `-Pknit.meshSim`, `-Pknit.previewStrings`,
`-Pknit.previewLayout`, `KNIT_WRITE_VECTORS`, and the `labChaosShard` and `unitStressShard` tasks. Release and
reproducibility builds pass `--no-build-cache`.

## Never load the machine to make a race show

The workstation is the user's, and they work on it while agents run. No agent (owner, reviewer or the main session)
loads the CPU to reproduce a bug or confirm a fix unless the user has explicitly authorized it for that run, in their
own words; a brief may pass that on only by quoting them. That rules out background busy, spin or sleep loops
(`while :; do :; done`, `while true; do sleep 0; done`, `yes > /dev/null`, `stress-ng`, `dd if=/dev/zero`, a hashing
loop), burner threads added to a test or a scratch harness, extra Gradle runs or JVMs started beside a test to crowd
it, and `--shards` above the stress scripts' `auto`.

The sanctioned crowding is the scripts as written: `unit-stress.sh` and `lab-chaos.sh` at their default shards (a
quarter of the cores, inside `gradle-turn.sh`'s weight-20 scope) and `unit-prefix.sh`'s one-core pin. To get the slow
runner's schedule, slow the test, not the host: `--quota <pct>` on either stress script, or `systemd-run --user --scope
-q -p CPUQuota=50% taskset -c 0` around one Gradle run (`context/testing.md`). A race that does not show under those
is reported by reasoning (a green run clears nothing you reasoned out), with the loaded-host run it would take named
as owed, for the user to authorize. Never run that run unasked.

## Code checks run before any review

The code checks are the static ones whose fix moves code: `ktlintCheck`, `detekt`, Android `lint` and lintorama, not
the tests. `detekt` is the type-resolved `detektAll` on every source folder, so it compiles the debug app and its three
test kinds first (`context/toolchain.md`). Run them with `.claude/hooks/review-gate.sh --checks`, never by hand beside it: it runs Gradle through
`scripts/gradle-turn.sh`, skips a check whose tree already passed, and records the pass, so the Stop hook reuses it
and no tree is checked twice. The Stop hook (`review-gate.sh`) runs the same checks in order before it asks for any
reviewer, and blocks the stop on a finding instead of asking: a ktlint or detekt fix after a review moves lines the
reviewer read and forces a second round. lintorama runs on every stop with a change; the Gradle checks take minutes,
so only when a reviewer is about to be asked. CI runs them all again on every push.

## ktlint autocorrects; detekt doesn't

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
counts. `translate=false` or `stt=false` there takes translate-native or Moonshine off the runtime classpaths the lock
records, so pass `-Ptranslate=true -Pstt=true` if it sets either.

## Regenerate dependency verification in the same commit

Gradle checks every artifact it downloads against `gradle/verification-metadata.xml` and the keys in
`gradle/verification-keyring.keys` (ADR 2026-10.rysj). It fails with "Dependency verification failed" when an artifact
has neither a listed SHA-256 nor a signature from a key trusted for its group. After any change that moves a downloaded
artifact (a catalog bump, a new dependency, a plugin, AGP or Gradle itself), run `scripts/verification-metadata.sh`
after the lockfile, and read its diff before committing:

- a new `trusted-key` or checksum is a new party the build trusts;
- a changed checksum under an unchanged version is never noise.

Never hand-edit an entry to make a build pass. `settings.gradle.kts` keeps the Plugin Portal from serving the
`org.jetbrains.kotlin*` groups: the Portal's copies of their marker POMs differ from Central's and are unsigned, so a
Central miss on a cold build (F-Droid's, `release.yml`'s `build-fdroid`) fails verification there. Keep the fence when
adding a plugin; a JetBrains plugin that is only on the Portal gets its own `content` rule. A Gradle bump also moves `distributionSha256Sum` in
`gradle/wrapper/gradle-wrapper.properties`: take it from `https://services.gradle.org/distributions/<zip>.sha256`.
