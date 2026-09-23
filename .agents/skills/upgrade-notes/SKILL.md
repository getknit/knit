---
name: upgrade-notes
description: Read the vendor's breaking changes, deprecations, migration notes and CVEs for every version between the one pinned now and the one being moved to, through the `whatsnew` MCP server's `upgrade_notes` tool. Use WHENEVER any dependency version changes in this repository — a bump, downgrade, add or swap in `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, a plugin or `buildscript` version, a GitHub Actions `uses:` ref, or a CI image tag — and whenever asked "is it safe to upgrade X", "what changed in X since Y", or to review a Renovate/Dependabot-style bump. Run it BEFORE editing the version, not after the build breaks.
---

# Upgrade notes

Every version change in this repo gets the vendor's own notes read first. The `whatsnew` MCP server
(`https://whatsnew.fyi/mcp`, declared in `.mcp.json`, no auth) returns every tracked release between two
versions with the breaking, deprecation, migration and security sections verbatim, including breaking
changes that shipped in a minor or a patch.

This skill tells you what to read before a bump. `.agents/context/toolchain.md` still decides whether the
bump is allowed (`minCompileSdk`, the Kotlin/KSP lockstep, stable-only). Read both.

## When the tool is missing

If no `whatsnew` tools are loaded, the server is not approved in this session. Tell the user once
(Claude Code: `/mcp`, approve `whatsnew`) and carry on with the upgrade by reading the vendor's release
notes directly. The skill is advisory; never block a bump on it.

## Steps

1. **List what is moving.** For each dependency, take `from` from what is pinned now (the catalog entry,
   or `app/gradle.lockfile` / `settings-gradle.lockfile` for a transitive one) and `to` from the target
   version. Use exact versions, never ranges. A catalog `[versions]` key that several libraries share
   (`lifecycle`, `cameraX`, `room3`) is one call, not one per artifact.

2. **Call `upgrade_notes`**, up to 20 dependencies per call. The tool has no Maven registry: the
   `registry` field takes only npm, pypi, crates and rubygems, so leave it out and let `repository` make
   the match.
   - `name`: the Maven coordinate `group:artifact`, the plugin id, or the action's `owner/repo`.
   - `repository`: the library's GitHub repo as `owner/repo`, whenever it has one. Without it a Maven
     coordinate usually comes back `untracked`. Measured on this catalog: `JetBrains/kotlin`,
     `google/ksp`, `square/okhttp`, `InsertKoinIO/koin`, `coil-kt/coil`, `tink-crypto/tink-java`,
     `robolectric/robolectric`, `pinterest/ktlint`, `detekt/detekt`, `gradle/gradle`.

3. **Check `match` before you read anything.** `match.slug` must be the library you meant. A note that
   says "Matched by name only" is a guess, and it can be wrong:
   - `com.android.tools.build:gradle` (AGP, and `apksig` rides the same version) matches **Gradle**, the
     build tool, by name. Call it with `name: "android-gradle-plugin"` instead.
   - For androidx and other Google libraries with no GitHub repo, the coordinate comes back `untracked`.
     Call `list_products` with the library's product name, then retry `upgrade_notes` with that slug as
     `name`. Measured: `camerax` and `room` resolve this way; Compose, lifecycle, core, activity,
     navigation and datastore are not tracked.
   - Discard a result whose slug is some other product. Don't report its notes.

4. **Read the result per dependency.**
   - `status: ok`: start with `signals` (major bump, breaking mentions, removed and deprecated counts,
     `cves`), then read each entry in `releases` for its `sections` (breaking, security, deprecated,
     migration). The dependency's `changes` is the categorized list across the whole interval. A
     section's `under` names the sub-package in a monorepo release; skip sections about artifacts this
     app doesn't use.
   - `untracked`, `unversioned`, `unreadable`: What's New has no usable history for it. Read the vendor's
     notes yourself and say so.
   - `notes` like "is not tracked yet; the newest we track is …": the target version is newer than the
     tracked history (common with a same-week release), or it is a pre-release. The history holds stable
     releases only, so the `detekt` 2.0.0-alpha line and similar return no notes. Fall back to the
     vendor's notes for that stretch.
   - `truncated` or a cut change list: open the release's `url` or `sourceUrl` for the rest before
     concluding there is nothing breaking.

5. **Act on it.** Apply migrations the notes call for in the same change as the bump. Report to the user,
   per dependency: breaking items that touch code or config this repo uses, CVEs fixed, and anything
   unchecked because it was untracked. Don't paste the whole payload. Then follow
   `.agents/rules/build-and-test.md` (regenerate every lock, then `./gradlew lint`).
