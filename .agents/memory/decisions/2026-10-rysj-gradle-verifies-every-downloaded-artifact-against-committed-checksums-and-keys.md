---
id: "2026-10.rysj"
slug: gradle-verifies-every-downloaded-artifact-against-committed-checksums-and-keys
title: "Gradle verifies every downloaded artifact against committed checksums and keys"
date: 2026-10-24
topics: [build, release]
---

# ADR 2026-10.rysj — Gradle verifies every downloaded artifact against committed checksums and keys

Status: Accepted (2026-10-24)

**What was observed.** `app/gradle.lockfile` pins which version of each dependency resolves, not what its bytes
are. A re-uploaded artifact, a compromised mirror or a swapped plugin under an unchanged version would resolve
silently. The first thing to notice would be F-Droid's byte comparison at release time, or nothing at all for a debug
or test-only artifact. The Gradle wrapper's `distributionSha256Sum` was unset, so the Gradle distribution itself was
unchecked too. The question came up while taking the translation runtime from Maven Central (ADR 2026-10.t2h6), whose
artifact we sign ourselves.

**What changed.** Gradle's dependency verification is on, with checksums and signatures (`sha256,pgp`).
`gradle/verification-metadata.xml` lists what each downloaded artifact must be: libraries, plugins, the tool
configurations the lockfile already locks (lint, ktlint, detekt, Kover, the screenshot renderer), and AAPT2's platform
binary. An artifact passes when its signature verifies against a key trusted for it. Otherwise it needs its SHA-256
listed. The first run trusted 60 keys, listed 716 checksums across 425 components, and recorded 39 keys no key server
had, whose artifacts fall back to checksums. Our own release key (`857DFEFE…`) is trusted for `app.getknit:translate-native`.

The keys are committed, armoured, in `gradle/verification-keyring.keys`, and `<key-servers enabled="false"/>` keeps
every build off the key servers, F-Droid's buildserver included. Sources and javadoc jars, and Gradle's own source
zip (`gradle-<version>-src.zip`, fetched to navigate the Gradle API), are trusted by name, since only the IDE
downloads them. The wrapper pins `distributionSha256Sum`, which matches both of gradle.org's published
checksums. F-Droid deletes the wrapper and runs its own shim, so that pin protects everyone else's builds.

`scripts/verification-metadata.sh` regenerates everything. It turns the key servers on for its run only. It writes
with `-Ptranslate=false`, the lockfile's rule, in passes that merge:

- the locked graph and a debug and a release build;
- the maintainer flags that unlock runtime classpaths (`-Ptranslate=true` with `-Pstt=true`, and with `-Pcalls=false`).
  Unlocked, Gradle reads the metadata of every version a conflict offers, versions the locked passes never see;
- the opt-in modules (`-Pknit.wear`, `-Pknit.baselineProfile`);
- a local knit-translate-native composite build, whose plugins only the root build's file can verify.

A key server that misses a key in one pass gets that key written as ignored, and the ignore outlives a later pass that
fetched it. Ignored wins, so everything the key signed failed ("All public keys have been ignored"), as androidx's key
did on the first run. The script drops ignored keys the keyring holds and runs the passes again until there are none.
`--clean` starts over and drops entries nothing needs.

*Checksums only* was the simpler choice. Gradle scopes most trusted keys to the one artifact version it saw, so
signatures don't remove the churn of a bump. What they add is the check that a new version really came from the same
publisher. Without them, a regeneration after a bump would accept whatever bytes the repository served that day.

**What it costs.** Every dependency, plugin, AGP or Gradle bump now regenerates two more files (about 390 KB) in the
same commit as the lockfile (`.agents/rules/build-and-test.md`, `CONTRIBUTING.md`, the upgrade-notes skill). The diff
is the review: a new trusted key or checksum is a new party the build trusts, and a changed checksum under an unchanged
version is never noise. The trap: an artifact resolved only by a task none of the script's passes runs fails the first
build that needs it with "Dependency verification failed". Add that task to a pass, never a hand-written entry.
Robolectric fetches its android-all jars itself, outside Gradle, so verification doesn't cover them.
