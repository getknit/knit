# Distribution: Google Play and F-Droid

Two channels, two signing identities, one reproducibility contract. Read this before touching release
signing, `packaging`/`ndk` config, Git LFS, or anything that changes release-APK bytes.

## The two channels

| | Google Play | F-Droid |
|---|---|---|
| Artifact | AAB (`bundleRelease`) | universal APK (`assembleRelease`) |
| Built by | us | **F-Droid's buildserver, from source** — then byte-compared against ours |
| Shipped bytes | Google's (Play App Signing re-signs) | **ours**, verbatim |
| Key | `knit-upload.jks` (upload key only) | `knit-dist.jks` (distribution key) |
| Native symbols | yes (default on the `bundle*` path) | no |

Play App Signing means the Play-installed app carries a certificate we do not hold, so a Play install and
an F-Droid install can never share a signature. That is unavoidable and fine — but it makes the *off-Play*
signature worth protecting, because one distribution-key-signed APK serves GitHub Releases, F-Droid,
direct sideload, **and Knit's own offline app-share** (`ui/invite/ShareApk.kt`). A phone handed Knit over
the mesh can still take an in-place update from F-Droid. That property is the whole reason we use
F-Droid's `Binaries:` + `AllowedAPKSigningKeys` flow instead of letting F-Droid sign with its own key.

`AllowedAPKSigningKeys` pins the distribution certificate publicly in fdroiddata, so **rotating
`knit-dist.jks` forces every off-Play user to uninstall and reinstall.** Treat it as permanent.

## The reproducibility contract

F-Droid rebuilds the tagged commit on their buildserver and byte-compares against the APK on our GitHub
Release. Anything that makes the output a function of *the build machine* breaks it.

**Verified end-to-end for 2.2.0 (2026-07-21).** A fully clean build (52/52 tasks, no cache) inside
`registry.gitlab.com/fdroid/fdroidserver:buildserver`, invoked the way fdroidserver does it
(`cd app && gradle assembleRelease` via F-Droid's `gradlew-fdroid` shim), produced an APK byte-identical
to the host build: `sha256 ec688988f95493c8f662d42a058f20741c1c700e64c9e099d76b1dd8b798366f`. Different
machine, different Android SDK install, F-Droid's own Gradle distribution, fresh Maven downloads, and no
`.git` present. AGP already normalizes zip entry timestamps to `1981-01-01`, and R8, resource shrinking,
and baseline-profile generation all proved deterministic.

Notes on that image: **Debian 13 (trixie), JDK 21 is the default**, `git-lfs` is **not** installed (hence
the LFS ban below), and the `fdroid` CLI is absent — it is the build environment, not the tooling.

## Running the real `fdroid build` locally

`fdroid build` needs no merge request and no GitLab account. It also verified byte-identical output
(`ec688988…`, same hash as above) *after* fdroidserver applied its own source rewrites, which is the
result that actually matters for `Binaries:`. Recipe, in the buildserver image:

1. `apt-get install -y fdroidserver` (2.4.2 in trixie), then **overwrite
   `/usr/lib/python3/dist-packages/gradlew-fdroid` with `/usr/local/bin/gradle`**. The Debian package
   ships an old *shell* shim with a hardcoded hash table that stops well before the Gradle this repo
   pins and dies with `No hash for gradle version <ver>`; the image's *Python* shim fetches the live
   transparency log, so it tracks whatever `gradle/wrapper/gradle-wrapper.properties` names. This is a
   packaging skew, not an F-Droid limitation — but it does mean **a wrapper bump must land on a
   *released* Gradle**, never an RC/nightly, or the shim has nothing to verify against.
2. `git config --global --add safe.directory '*'` — the container is root and the source mount is not;
   without it the clone fails with a bare `Git clone failed`.
3. `fdroid init` in a work dir, **then `git init` + commit the metadata**. `fdroid build` derives
   `SOURCE_DATE_EPOCH` from the app checkout and falls back to *the metadata repo's* git log; with no git
   there it crashes in fdroidserver itself (`TypeError: str expected, not NoneType`). Real fdroiddata is a
   git repo, so this only bites local testing.
4. Point `Repo:` at the local checkout and `commit:` at a SHA to test unpushed/untagged work.
5. `fdroid build <appid>:<vercode> -v -t --scan-binary` (local is the default; `--server` opts into the VM).

What fdroidserver does to the source before building — all confirmed harmless here, but worth knowing:
strips the entire `signingConfigs` block and every `signingConfig =` line from `build.gradle.kts`, removes
debuggable flags, writes `local.properties`, and **deletes `gradlew`, `gradlew.bat` and
`gradle-wrapper.jar`** so its own shim is always used. Our output is unaffected because the signing config
is credential-conditional and contributes nothing without creds — keep it that way.

Also verified: `fdroid scanner` reports **0 problems** (the ~32 MB `.tflite` blobs are not flagged), the
dexdump non-free-class scan and extra-signing-block scan are clean, and `UpdateCheckData` correctly reads
`knit.versionCode`/`knit.versionName` out of `gradle.properties`. `Categories` are validated against
fdroiddata's `config/categories.yml` (108 entries) — a bare local repo has no category config, so
`fdroid lint` will call *every* category invalid there; that is a local artifact, not a real failure.

Four inputs were deliberately de-machine-ified to keep it that way:

- **No VCS stamping.** `buildTypes.release { vcsInfo { include = false } }`. AGP otherwise writes
  `META-INF/version-control-info.textproto` holding the local checkout's HEAD revision — or
  `generate_error_reason: NO_SUPPORTED_VCS_FOUND` when built outside a Git work tree. This was the **only**
  difference (1 entry out of 185, identical APK size) between a host build and a rebuild of the same source
  inside `registry.gitlab.com/fdroid/fdroidserver:buildserver`, and it alone would fail verification.

- **No NDK on the APK path.** `ndkVersion` and `ndk { debugSymbolLevel }` are keyed to the **AAB** path:
  on for any `bundle*` task, off for `assembleRelease`. AGP's strip step degrades *silently* when the NDK
  is absent, which would mean "stripped here, unstripped there". Instead
  `packaging { jniLibs { keepDebugSymbols += "**/*.so" } }` opts out of stripping explicitly on the APK
  path. Measured cost: **+8 bytes** — every shipped `.so` is a third-party release build that upstream
  already stripped, so the strip step was always a no-op.

  The split (rather than one global default) is the whole point: symbols live in the AAB's
  `BUNDLE-METADATA` and never enter an APK, so defaulting them on for `bundle*` costs the APK nothing,
  while `assembleRelease` stays NDK-free and byte-identical on F-Droid's buildserver — which has no NDK,
  and no `ndk:` line in `.fdroid.yml`. **Never make it a global default**; that reintroduces exactly the
  machine-dependent stripping this section exists to prevent. `-Pknit.nativeSymbols=<bool>` overrides
  either way (use `=false` to bundle on a machine without the NDK). Verified after the change: a plain
  `assembleRelease` is entry-for-entry CRC-identical to the published, F-Droid-verified 2.2.2 APK, and a
  plain `bundleRelease` carries symbols with no flag. It became a default because 2.2.2/vc11 shipped to
  Play open testing with **zero** symbols — the flag was simply forgotten.
- **Prebuilt `.so` are shipped verbatim, never stripped.** As of 2.2.2 the APK carries CameraX's
  `libimage_processing_util_jni.so` + `libsurface_util_jni.so` (four ABIs, ~165 KB total) alongside the
  existing LiteRT / SQLCipher / datastore / graphics-path natives — added when the QR scanner moved to
  CameraX (ADR 015). They need no special handling on the APK path:
  `packaging { jniLibs { keepDebugSymbols += "**/*.so" } }` opts every `.so` out of stripping there, so the
  packaged APK bytes are identical with or without an NDK.
  Verified on the 2.2.2 release APK — each `.so` is byte-for-byte the size in the upstream AAR, entry
  timestamps normalized to `1981-01-01`, and all four ABIs present (which the release workflow also checks).
  Any *new* native dependency must additionally be **16 KB-page-aligned** (`readelf -lW` → `LOAD
  align=0x4000`); that requirement is why litert is pinned to 1.4.x and it was re-verified for CameraX.
  **Aligned `LOAD`s are not enough: check `GNU_RELRO` too, on every native bump, not only new ones.** Android 16+
  puts the whole app in page-size compat mode (`pageSizeCompat=256`, `PAGE_SIZE_APP_COMPAT_FLAG_RELRO_NOT_ALIGNED`,
  the "isn't 16 KB compatible" warning) when a `.so`'s RELRO "is not a suffix and its end is not PAGE-aligned" —
  it ends mid-page inside a writable segment. DataStore 1.3.0-alpha03-05 shipped such a
  `libdatastore_shared_counter.so` (RELRO `0xc000+0x1000`, after `.data`), with every `LOAD` at `0x4000`; the
  classic lld layout, where RELRO fills the first `RW` `LOAD`, passes even when its end is only 4 KB-aligned.
  The check that decides it is the device's: install on a `ps16k` emulator and read
  `dumpsys package app.getknit.knit | grep pageSizeCompat` (0 is clean).
- **Every SDK package must exist in F-Droid's transparency log — and resolve in their image.** The
  buildserver image's `sdkmanager` is **F-Droid's own** (25.2.0), which resolves packages from
  [`f-droid/android-sdk-transparency-log`](https://github.com/f-droid/android-sdk-transparency-log), not
  Google's repository. A package Google publishes but that log does not carry simply does not exist there:
  `Warning: Failed to find package 'platforms;android-37.1' / Did you mean 'platforms;android-37.0'?`, exit
  1. That is what `compileSdk { release(37) { minorApiLevel = 1 } }` hit in 2026-08, when the log had
  `platforms;android-37.0` and neither `android-37.1` nor a bare `android-37`: it shipped that way on
  main, the release workflow's `build-fdroid` job caught it at the first tag after the bump (exactly the
  divergence that job exists to find), and `6407e7d` reverted to 37.0. The log caught up — as of
  2026-09-15 the image lists *and installs* `platforms;android-37.1`, `build-tools;37.0.0` and
  `build-tools;36.1.0`, which is why the build pins 37.1 / 37.0.0 again. Two rules from that episode:
  the image's package list **lags** the log's `checksums.json` (`platform-37.2_r01.zip` was in the log
  while the image still stopped at 37.1), so a bump is tested in the image, never read off the JSON; and
  **any `compileSdk`/`buildToolsVersion` bump is mirrored** into `release.yml`'s `sdkmanager` line,
  `.gitlab-ci.yml` and `qodana.yaml`. Check with `docker run --rm
  registry.gitlab.com/fdroid/fdroidserver:buildserver sdkmanager --install "platforms;android-<ver>"
  "build-tools;<ver>"`.
- **No JDK auto-download.** The `foojay-resolver-convention` plugin is deliberately absent from
  `settings.gradle.kts`, and the `toolchainUrl.*` lines are stripped from
  `gradle/gradle-daemon-jvm.properties`. Both would fetch an unpinned JDK from api.foojay.io. Gradle now
  fails loudly instead, and the builder installs JDK 21 (the recipe's `sudo:` block does this).
  `./gradlew updateDaemonJvm` regenerates those URLs — delete them again if you run it.
- **No Git LFS.** See below.

`dependencyLocking { lockAllConfigurations() }` + `app/gradle.lockfile` is an asset here: it pins every
resolved dependency version, so F-Droid's rebuild resolves exactly what we did.

## Git LFS is banned in this repo

`*.tflite` used to be tracked in Git LFS. It is not, and must not be again. F-Droid's buildserver has no
LFS support ([fdroidserver#1190](https://gitlab.com/fdroid/fdroidserver/-/issues/1190), open since 2024),
so a checkout there yields ~130-byte pointer stubs — and `NsfwImageModerator`/`MlTextModerator` both
degrade to allow-all on an unreadable model *by design*. The build would succeed and ship a quietly
unmoderated app that also fails byte-comparison. The `checkModerationModels` task
(`app/build.gradle.kts`, wired into `preBuild`) hard-fails on a stub or a sub-1 MB model so this can never
regress silently.

## The F-Droid source scanner flagged TensorFlow Lite — and the from-source replacement is broken

**Resolved upstream; kept because the litert decision it explains is permanent.** F-Droid's source scanner
(fdroidserver **master**, which its CI runs — not the older Debian package or a tagged release, whose
scanner is *not* version-catalog-aware and won't reproduce this) resolved the `libs.litert` version-catalog
alias to `com.google.ai.edge.litert:litert` and flagged it as a prebuilt Google-Maven "usual suspect"
(`suss` signature `com.google.ai.edge.litert:litert:(2|1.[34])`), which **hard-failed `fdroid build`**.
The obvious fix — the from-source `de.schliweb:tensorflow-lite-fdroid`
(what `org.fairscan.app` uses) — was tried on-device and **REJECTED**: its from-source TFLite 2.18.0
miscomputes our Detoxify/ALBERT model, saturating every output to a binary `0`/`1` (clean text scores
`1.0` on `severe_toxicity`) so it would block every message. Verified delegate-independent (identical with
XNNPACK on, off, and single-thread); Google's litert scores correctly (`0.00004` clean, `0.957` for real
abuse). It is a fundamental op-level miscompute, not a config knob — `fairscan`'s OCR model happens to
work, ours doesn't. So Knit keeps Google's litert and takes the F-Droid exception:

- **No `AntiFeatures: NonFreeDep` — it was proposed, then dropped.** litert is Apache-2.0 but ships as a
  prebuilt binary F-Droid can't rebuild, so the metadata originally declared `NonFreeDep`. Once the scanner
  signature was corrected upstream (below) there was nothing left to declare, and the block came out during
  the new-app review; the merged fdroiddata file has no AntiFeatures. **Do not add it back** on a litert
  bump within the 1.x line.
- **`scanignore` removed — `(2|1.[34])` was an over-broad false positive on the published 1.x line, and the
  fix went upstream.** We first suppressed the flag with `scanignore: [app/build.gradle.kts]`; reviewer
  linsui asked us to drop it (MR 43609), which left the build red on the scanner. The catch: the *published*
  `litert:1.3.x`/`1.4.x` AAR (byte-checked from `dl.google.com/android/maven2`) is just the plain
  `org.tensorflow.lite` interpreter — the rebranded classic TFLite, 12 classes, Apache-2.0, **zero
  `com.google.android.play` refs**. The `litert/kotlin/BUILD` target that *does* carry the Play coupling
  (`AiPackModelProvider` + `com.google.android.play:ai-delivery`, added in 1.3.0) is real in the source
  tree, but a git tag snapshots the whole monorepo including that in-progress Kotlin API — it publishes as
  **`litert-api:2.x`**, never as `litert:1.3/1.4.x`. The coupling reaches litert only at 2.x, transitively:
  `litert:2.x → litert-api:2.x → ai-delivery`. So `(2|1.[34])` both over-flags the interpreter line *and*
  misses the real carrier `litert-api:2`. Fixed by **`fdroid/fdroid-suss!63`** (signature →
  `litert(-api)?:2`: drop `1.[34]`, add the `litert-api` arm), **merged 2026-07-26**. suss.json regenerated,
  our pinned `litert:1.4.2` stopped matching, and 43609 went green with no `scanignore` — all nine jobs,
  verified 2026-07-31 — and merged. (Even before the fix the shipped APK was clean: we call only
  `org.tensorflow.lite.Interpreter` and R8 strips the rest, so the release dex has zero Play classes
  regardless.)
- **`commit:` is the full 40-char hash**, not the tag (linsui's request — tags are mutable). No `sudo:`
  block either: the buildserver already has JDK 21 selected, so the manual install was redundant. No
  `output:` line either — fdroidserver locates the APK itself, and the review dropped the one we had.
- **`AutoUpdateMode: Version`** (bare, not `Version v%v`). Under `UpdateCheckMode: Tags` the metadata
  schema forbids a format string (`^(None|Version( \+.+)?)$`); `Version v%v` is an `UpdateCheckMode: HTTP`
  construct and fails `check-jsonschema`. The tag itself is the version source.
- **`UpdateCheckMode: Tags ^v[0-9.]+$`** — the regex is load-bearing, not decoration. `checkupdates` walks
  *this repo's tags*, not the Releases, so every tag is visible to F-Droid's bot whether or not fdroiddata
  mentions it (`check_tags()`: `pattern = mode[5:] if len(mode) > 4 else None`, then
  `re.compile(pattern).match(tag)` against the raw tag name, applied *before* the newest-5 cut). Without the
  filter a `v2.4.0-alpha.0` pre-release tag would be picked up and auto-turned into a `Builds:` entry
  shipping an alpha to every F-Droid user as a stable release. Schema-legal (`^Tags( .*)?$`), and
  `release.yml`'s preflight re-applies the exact same expression to the tag it is building — see
  "Pre-releases" below.

None of this touches the app or the released APK — the moderation model already runs correctly on Google's
litert, so the APK ships unchanged and stays byte-reproducible. This is purely a metadata accommodation. **If
litert is ever bumped, do not move to `2.x`** — it renames the lib and pulls the ai-delivery graph — and
re-verify any TFLite runtime change on-device against `ToxicityInstrumentedTest` (the moderators degrade
silently to allow-all / block-all on a bad interpreter).

## Cutting an off-Play release

`.github/workflows/release.yml` does it: push a `v*` tag and it builds, signs, verifies and drafts the
GitHub Release. Prepare the commit first —

1. Bump `knit.versionCode` / `knit.versionName` in `gradle.properties`. **Keep the tag equal to
   `v<versionName>`** — fdroiddata's `Binaries:` URL expands `%v` to the *versionName*, so a `2.1` /
   `v2.1.0` mismatch breaks the binary lookup. (`AutoUpdateMode` is bare `Version` under `UpdateCheckMode:
   Tags` — see the F-Droid-scanner section below for why `Version v%v` is wrong there.)
2. Update `CurrentVersion` / `CurrentVersionCode` and add a `Builds:` entry in `.fdroid.yml`, and add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` — F-Droid scrapes `fastlane/metadata/`
   straight from this repo at the built commit (descriptions, screenshots, changelog).
3. Tag `v<versionName>` and push it. The `preflight` job re-checks every one of the above and refuses the
   release if any disagrees, so a mistake costs a re-tag, not a bad artifact.
4. Publish the draft Release, **then** open the fdroiddata MR (see below). Not before: `fdroid build`
   fetches `Binaries:`, which 404s until the Release is public.

The workflow ends at a **draft** Release: the `Binaries:` URL stays 404 until you click publish, which is
the right state while fdroiddata still points at the previous version. Drop `--draft` to go straight out.

### Pre-releases (sideloadable alphas / betas / RCs)

Same workflow, same tag namespace, **same distribution key** — a `v2.4.0-alpha.0` tag produces a signed,
reproducibility-verified APK that testers can sideload. Which path a tag takes is decided by the
versionName alone: a semver pre-release suffix (`-alpha.N` / `-beta.N` / `-rc.N`) means test build,
anything matching `X.Y[.Z]` means stable release.

| | stable `v2.2.3` | pre-release `v2.4.0-alpha.0` |
|---|---|---|
| GitHub Release | **draft**, becomes *Latest* when you publish | **published immediately**, `--prerelease --latest=false` |
| fdroiddata | must name this version | must **not** name it (enforced) |
| Signing key | `knit-dist.jks` | `knit-dist.jks` — same key |
| Reproducibility gate | yes | yes |
| Changelog | `changelogs/<code>.txt` required | optional; falls back to CHANGELOG.md's `## Unreleased` |

Because it carries the distribution certificate, a pre-release **installs in place over an F-Droid, GitHub
or mesh-shared install** and keeps the user's data — which is the whole point, and the reason not to cut
these with a throwaway key. The cost is that a tester cannot go *back*: Android refuses the versionCode
downgrade, so reverting to the shipped stable means uninstalling. The next stable, whose versionCode is
higher again, updates over the alpha normally. The Release notes say all of this.

To cut one:

1. Bump **both** `knit.versionCode` and `knit.versionName` in `gradle.properties` — e.g. `13` /
   `2.4.0-alpha.0`. There is one monotonic versionCode counter shared by stable and pre-release builds;
   gaps in F-Droid's sequence are fine, a versionCode reused or moving backwards is not. The following
   stable then takes `14`, so it can update over the alpha.
2. **Leave `.fdroid.yml` alone.** No `Builds:` entry, no `CurrentVersion*` bump. Preflight fails the
   release if any of them names the pre-release.
3. Optionally add `fastlane/metadata/android/en-US/changelogs/<code>.txt`; without one the Release notes
   use CHANGELOG.md's `## Unreleased` section.
4. Tag `v<versionName>` and push. The Release is published (not drafted) as soon as CI is green.

**Before the first pre-release tag ever lands, the `UpdateCheckMode` filter must be merged into
fdroiddata.** Preflight reads `.fdroid.yml`, but `fdroid checkupdates` runs against the merged
`metadata/app.getknit.knit.yml` — our copy is documentation to F-Droid's bot, nothing more. An alpha tagged
while fdroiddata still says a bare `UpdateCheckMode: Tags` is exactly the accident preflight exists to
prevent, and preflight cannot see it. Mirror the line first (it is a one-line MR), confirm it merged, then
tag.

Preflight enforces the separation in **both** directions, because each failure mode is silent otherwise:

- a pre-release tag that `UpdateCheckMode`'s regex would *match* is refused — that is the alpha-ships-to-
  everyone accident;
- a stable tag that the regex would *not* match is also refused — an over-tight regex silently freezes
  F-Droid on the previous version, with no error anywhere.

The `build-fdroid` reproducibility job runs for pre-releases too. It doubles the CI cost of an alpha, and
it is worth it: alphas are exactly where toolchain and dependency churn lands, so a repro break surfaces
while it is cheap to fix rather than at the next stable tag.

### Keeping `.fdroid.yml` and fdroiddata in sync

`.fdroid.yml` is the source of truth, but it is not copied verbatim — fdroidserver's canonical writer
strips comments, so **comments are the only permitted difference**; every other byte must match the merged
`metadata/app.getknit.knit.yml`. That invariant was silently broken once: the new-app review (MR 43609)
dropped an `AntiFeatures` block, `MaintainerNotes` and every `output:` line, reordered `Categories`
alphabetically, moved `AllowedAPKSigningKeys` after `Builds:`, put builds in ascending versionCode order,
and pruned all but the newest build entry — none of which was mirrored back here until 2.2.3. So work in
this direction: **update the fdroiddata file from `.fdroid.yml`, and reconcile `.fdroid.yml` to whatever
the review actually merges.** Verify with

```bash
diff <(grep -v '^\s*#' .fdroid.yml | grep -v '^$') <(grep -v '^$' ../fdroiddata/metadata/app.getknit.knit.yml)
```

The update MR itself is a two-line-plus-entry change against current `master` (`Update Knit to <version>`,
matching fdroiddata's own convention). `AutoUpdateMode: Version` means F-Droid's bot would eventually pick
the tag up on its own; sending the MR just skips the wait.

**GitHub Actions runs the copy of the workflow that exists at the tag**, so editing `release.yml` only
affects tags cut afterwards. Fixing a workflow bug for an already-created tag means moving the tag.

Required repo secrets (four; the job that reads them declares `environment: release`, so you can attach
required-reviewer or tag-only protection rules to exactly that job):

| Secret | Value |
|---|---|
| `KNIT_DIST_KEYSTORE_B64` | `base64 -w0 knit-dist.jks` |
| `KNIT_DIST_STORE_PASSWORD` | keystore password |
| `KNIT_DIST_KEY_ALIAS` | `knit-dist` |
| `KNIT_DIST_KEY_PASSWORD` | key password |

They feed `KNIT_SIGNING_*` env vars (`KNIT_UPLOAD_*` still works; the neutral name exists because this
path must never be confused with the Play upload key). The keystore is decoded to `$RUNNER_TEMP`, shredded
in an `if: always()` step, and never enters the workspace or an artifact.

Three things the workflow checks that no local build does:

- **The signing certificate equals `AllowedAPKSigningKeys`.** Signing the public APK with the Play upload
  key is the one unrecoverable mistake here, and it is otherwise invisible until users can't update.
- **The APK is reproducible.** A parallel job rebuilds the same tag *unsigned* inside
  `registry.gitlab.com/fdroid/fdroidserver:buildserver` with no secrets and no Gradle cache, and
  `apksigcopier compare` (the same tool `fdroid verify` uses) proves the signed APK is that build plus a
  signature. A GitHub runner is a third build environment; reproducibility was only ever proven between
  the maintainer's machine and F-Droid's container. Nothing publishes if they diverge.
- **No VCS stamp, all four ABIs.** Cheap regression guards on the two things most likely to silently
  change what gets shipped.

To build one by hand instead: `./gradlew :app:assembleRelease` with `keystore.properties` pointed at
`knit-dist.jks`, then attach the APK under the filename the `Binaries:` URL expects.

Play releases use `knit-upload.jks` (native symbols are on by default for `bundleRelease`); see
`.private/` for the maintainer-only store workflow.
