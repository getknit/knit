---
id: "2026-09.6eb6"
slug: in-app-about-and-licenses
title: "In-app About and licenses: a hand-kept list pinned to the notices file and the release classpath"
date: 2026-09-15
topics: [ui, build, release, settings]
---

# ADR 2026-09.6eb6 — In-app About and licenses: a hand-kept list pinned to the notices file and the release classpath

Status: Accepted (2026-09-15)

**What was observed.** The running app never said what license it was under or where its source lived.
GPL §5 expects a program to carry "appropriate legal notices", and the Install-offline flow
(`ui/invite/ShareApk.kt`) re-conveys the APK phone to phone, so a copy of Knit can arrive with no store
listing and no README behind it. `COPYING`, the README notice and `THIRD-PARTY-NOTICES.md` covered the
repository; the APK carried none of it (work item #14, the last gap from the pre-GPL audit). Settings had
no overflow menu, and nothing in the app showed the version, the build type or where it was installed
from — the three facts every bug report opens with. `THIRD-PARTY-NOTICES.md` had also drifted from the
real graph: it still listed ZXing Android Embedded (dropped for CameraX, ADR 015) and missed OkHttp, Okio,
Guava, Gson, Dagger, Stately, Accompanist and the Compose Multiplatform artifacts that ride in on Koin.

**What changed.** Settings' top bar gains an overflow with two items, About Knit and Open-source licenses
(`ui/about/`, routes `about`, `licenses`, `license/{licenseId}`). About shows the GPL notice, the source /
issues / website links (GitHub is the canonical URL — `Links.REPO_URL`), the License row, and a Build
section (version and code, build type with R8's "minified" read off the artifact, installed from, Android,
device) with a Copy button that emits the crash header's line shapes so a paste into an issue parses the
way a crash report does (`AboutBuildInfo.asText()`, `CrashEnvironment.appLine()` and friends). The
licenses list is `legal/ThirdPartyNotices.kt`, a hand-kept Kotlin mirror of the notices table, and every
row opens the full license text from `app/src/main/assets/legal/` — committed copies (GPL, Apache-2.0,
MIT, Zetetic's BSD, Unicode-3.0), readable with no Internet, which is the point on a phone that got Knit
over Bluetooth. `installedFromPlay()`'s installer read moved to `legal/InstallSource.kt` so the review
prompt and About share the one read; the Install-offline dialog gained a sentence naming the license and
the source.

*The alternative a reader reaches for first* is a license plugin — AboutLibraries or licensee. Neither
fits here: AboutLibraries puts a runtime library and a build-time generated JSON into an APK that F-Droid
must reproduce byte for byte (`context/distribution.md`; `vcsInfo { include = false }` exists because one
stamp broke that compare), and both add a plugin to a toolchain that is stable-only, keeps `:app`'s
classpath deliberately small and locks every configuration. Likewise a Gradle copy task for `COPYING`
would make the asset a build output; a committed copy is "an input like any source file"
(`context/baseline-profile.md`), pinned byte-equal to root `COPYING` by `LicenseAssetsTest`.

*Why the hand-kept list does not drift* — the objection the work item raised against static text —
is two plain-JVM tests that do the plugin's job at test time: `ThirdPartyNoticesSyncTest` pins the
Markdown tables to the Kotlin list row for row (name, order, SPDX id, link), and
`ReleaseClasspathNoticesTest` walks `app/gradle.lockfile`'s `releaseRuntimeClasspath` and fails on any
`group:artifact` with no claiming notice, on any notice that claims nothing, on two notices tying for a
coordinate, and on any `NOT_SHIPPED` prefix that matches nothing. A new dependency fails the build with its
coordinate until it has a row in both files; a removed one fails until its row goes.

**What it costs and does not cover.** ~50 KB of text assets (zip-compressed to about a third). Adding a
shipped dependency now means a row in `THIRD-PARTY-NOTICES.md` *and* `ThirdPartyNotices.ALL`, with the
artifact prefixes that claim it; `CONTRIBUTING.md` says so. `NOT_SHIPPED` is for annotation-only jars R8
strips (JSpecify, Checker qualifiers, JSR-305, Error Prone, J2ObjC, AutoValue, JetBrains annotations) and
nothing else — `javax.inject`/`jakarta.inject` are runtime code (`Provider`) and ride under Dagger. The
install source is the *installer package*, not the signing certificate: it tells Play from F-Droid from
everything else, and "Sideloaded" covers the GitHub APK, a shared copy and any other store alike; the
certificate (`AllowedAPKSigningKeys` in `.fdroid.yml`) would be the truer channel signal if that ever
matters. The MIT asset carries both bundled models' copyright lines rather than one file per holder.

The trap: **nothing from the build machine may reach About.** No git SHA, no build timestamp, no
`buildConfigField` that reads the environment — the release APK is byte-compared by F-Droid's buildserver
against ours, and that one `version-control-info.textproto` was the only diff the last time it was tried.
Version, code and build type come from `BuildConfig`; everything else is `android.os.Build` at runtime.

*Amendment (2026-09-29): a debug build names its commit.* About's Build section, and the Copy block under
its `app:` line, show the checkout's short SHA — in the **debug** variant only. `BuildConfig.GIT_SHA` is
empty in `defaultConfig`, so release, staging and nonMinifiedRelease carry nothing; `androidComponents`
fills it for debug alone, from a `ValueSource` running `git rev-parse --short HEAD` lazily when the debug
`BuildConfig` is generated (not at configuration time, so HEAD never keys the configuration cache), and a
tree with no Git yields an empty field and no row. The trap above stands for every release-shaped build: a
release's version already names its tag, and F-Droid's rebuild has no `.git` to read.
