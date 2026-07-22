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
| Native symbols | yes, `-Pknit.nativeSymbols=true` | no |

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
   ships an old *shell* shim with a hardcoded hash table that stops well before Gradle 9.5 and dies with
   `No hash for gradle version 9.5.0`; the image's *Python* shim fetches the live transparency log. This
   is a packaging skew, not an F-Droid limitation.
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

- **No NDK on the APK path.** `ndkVersion` and `ndk { debugSymbolLevel }` are gated behind
  `-Pknit.nativeSymbols=true` (the Play `bundleRelease` invocation only). AGP's strip step degrades
  *silently* when the NDK is absent, which would mean "stripped here, unstripped there". Instead
  `packaging { jniLibs { keepDebugSymbols += "**/*.so" } }` opts out of stripping explicitly on every
  machine. Measured cost: **+8 bytes** — every shipped `.so` is a third-party release build that upstream
  already stripped, so the strip step was always a no-op.
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

## Cutting an off-Play release

1. Bump `knit.versionCode` / `knit.versionName` in `gradle.properties`. **Keep the tag equal to
   `v<versionName>`** — fdroiddata's `Binaries:` URL and `AutoUpdateMode: Version v%v` both expand `%v` to
   the *versionName*, so a `2.1` / `v2.1.0` mismatch breaks both.
2. `./gradlew :app:assembleRelease` with `keystore.properties` pointed at `knit-dist.jks`.
3. Tag `v<versionName>`, attach the APK to the GitHub Release under the filename the `Binaries:` URL
   expects, and update `CurrentVersion`/`CurrentVersionCode` + add a `Builds:` entry in fdroiddata.
4. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` — F-Droid scrapes
   `fastlane/metadata/` straight from this repo at the built commit (descriptions, screenshots, changelog).

Play releases additionally pass `-Pknit.nativeSymbols=true` and use `knit-upload.jks`; see
`.private/` for the maintainer-only store workflow.
