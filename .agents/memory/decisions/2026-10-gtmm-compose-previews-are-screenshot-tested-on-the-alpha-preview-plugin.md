---
id: "2026-10.gtmm"
slug: compose-previews-are-screenshot-tested-on-the-alpha-preview-plugin
title: "Compose previews are screenshot-tested on the alpha preview plugin"
date: 2026-10-02
topics: [testing, toolchain, ui]
---

# ADR 2026-10.gtmm — Compose previews are screenshot-tested on the alpha preview plugin

Status: Accepted (2026-10-02) as an experiment on `test/compose-testing`. Every public `@Preview` is covered
(166 subjects, 332 images). CI runs it on GitHub and GitLab, advisory until the runners have rendered green.

**What was observed.** Nothing in the tree compared pixels. The Robolectric `createComposeRule` tests assert
semantics, and the seeded FTL suite only captures screenshots for a human to read. Meanwhile the app had 165
`@Preview` functions that were nearly deterministic already: top-level, wrapped in `KnitPreview` (static colour
scheme, `dynamicColor = false`), timestamped from `PREVIEW_NOW`, and free of Koin and ViewModels. Google's
Compose Preview Screenshot Testing plugin turns exactly such previews into golden-image tests on the host JVM,
with no device.

**What changed.** `:app` applies `com.android.compose.screenshot` 0.0.1-alpha16, the newest release. alpha12
added AGP 9 support, and alpha15 fixed the missing-`kotlin-stdlib` crash under Kotlin 2.1+. The plugin has never
shipped a stable release, so this is the third standing exception to toolchain.md's stable-only rule.

- **How it is switched on:** the `android.experimental.enableScreenshotTest` flag, set both in
  `gradle.properties` and in `experimentalProperties`.
- **Where the tests come from:** `scripts/gen-screenshot-tests.py` generates `app/src/screenshotTest/`. Each
  test is a one-line `@PreviewTest` wrapper over main's own `*Preview()`, so the sample data stays in one place.
  - Every wrapper gets light and dark multipreviews: `@ComponentShots`, or `@ScreenShots` on a fixed
    411×891 dp canvas.
  - `--check` fails when a preview has no wrapper.
  - The references sit under `app/src/screenshotTestDebug/reference/` in **Git LFS** — the repo's only LFS
    rule. The release path is LFS-free by rule (`context/distribution.md`), and these are not on it: a clean
    `assembleRelease` with every reference swapped for its pointer produces the same APK as one from the real
    PNGs, so F-Droid's LFS-less clone never opens them. Plain blobs would have put every re-render of about
    15 MB of images into every clone's history. Both screenshot tasks refuse a pointer with a message, and
    `.githooks/` carries LFS's hooks, because `core.hooksPath` hides the ones `git lfs install` writes.
- **First alternative, not taken:** Paparazzi or Roborazzi would mean a second renderer, a second set of
  conventions, and either Robolectric's native graphics or a layoutlib fork to keep in step with compileSdk 37.1.
  Google's plugin ships with AGP's own layoutlib (16.1.0) and with Studio's preview tooling.
- **Second alternative, not taken:** gating the plugin behind a `-P` property, the way `:wear` and
  `:baselineprofile` are gated, would keep it out of F-Droid's configuration. Measurement showed that gate buys
  nothing, so it was dropped. The plugin resolves only into `*ScreenshotTest*` configurations, the
  `releaseRuntimeClasspath` and `debugRuntimeClasspath` lock entries did not move, and
  `app-release-unsigned.apk` built before and after applying the plugin has the same sha256 (`f622b8fe…e1bd39`).

Covering every preview took five changes outside the test source set:

- **Two private previews made public:** `StorageUnavailableScreenPreview` and `LoraRadioScreenPreview`, which is
  the convention anyway.
- **`MeshOffBanner`'s previews read `PREVIEW_NOW`** instead of the wall clock.
- **The zone and locale are pinned on both screenshot tasks** (`UTC`, `en-US`, in `app/build.gradle.kts`).
  Rendering under another `TZ` moved twelve images: the pause deadline, the message-details stamps, and the
  dates on the profile and Your mesh screens.
- **Two components start settled in inspection mode.** The plugin captures a single frame, before any effect
  runs, so `EmptyState`'s fade-in rendered blank and `EncryptionSection`'s QR (encoded off-thread in
  `produceState`) never appeared. Both now start settled under `LocalInspectionMode`, the read `Motion.kt`
  already makes. Nothing changes at runtime.

**What it costs.**

- **Build and lockfile:**
  - AGP prints an "experimental option" warning on every configure.
  - The plugin is on the build classpath of every build, F-Droid's included.
  - `app/gradle.lockfile` gains the layoutlib, renderer and JUnit-platform entries.
- **Running it:** a warm `validateDebugScreenshotTest` takes about 45 s and `update` about 40 s; the
  configuration cache stays green.
- **LFS:** running or updating the tests needs git-lfs, and every re-render adds its images to LFS storage
  (GitHub's quota) rather than to clone history. CI fetches them in the screenshot job alone: GitHub's
  through an `actions/cache` keyed on the object ids, GitLab's by lifting a pipeline-wide
  `GIT_LFS_SKIP_SMUDGE=1`, which also makes `build:release` there build from pointers, as F-Droid does.
- **The runners' CPUs are not the workstation's.** The references were rendered without AVX-512, and Skia's
  rasterizer can take another SIMD path on a runner that has it. So both CI jobs start advisory, and a few
  green runs on each are what make them enforced.
- **What a failure looks like:** a 2 dp change to `ConnectionStatusRow`'s status dot failed exactly the images
  that draw the row, as `Size Mismatch` on the content-sized shots and a 0.25 % diff on the chat-list screen.
- **Stability:** references rendered under `Asia/Kolkata` validated under `America/Los_Angeles`,
  `Pacific/Kiritimati`, `de_DE` and `ja_JP`.

**What it does not cover, and the traps.**

- **A `Popup` is a separate window the capture leaves out,** so `ReactionPicker` is excluded in the script.
- **`AvatarCropDialog`'s photo waits on `onSizeChanged`,** so its image checks only the dialog chrome.
- **Layoutlib's framework is not the device's.** `Formatter.formatShortFileSize` below a kilobyte renders its
  raw `${NUMBER} ${UNIT}` template, which the reference simply captures.
- **Renames orphan PNGs.** Renaming a preview, or changing a `@Preview` parameter, orphans the old PNG, because
  `update` does not delete one.
- **The plugin is alpha, so its API can still move.** alpha10 changed the reference directory and made
  `@PreviewTest` mandatory.
- **Guards:** `validateDebugScreenshotTest` and `gen-screenshot-tests.py --check` keep this true. The
  "Compose preview screenshot tests" section of `context/testing.md` carries the working rules.

## Amendment 2026-10-23 — every subject also renders long and right to left

**What was observed.** The first translation (Brazilian Portuguese, a machine pass checked on an emulator) clipped
two labels: the "Over 1 MB" segment of a three-way button and the "Mesh call" half of a two-button row. Both were
fixed by rewording, which works once. German will break the same layouts, and so will a large font scale in
English. Rewording can't settle it; each screen has to be checked against long text when it is written, before any
translation exists, and against a right-to-left layout before Arabic does the same.

**What changed.**

- **The debug build generates the two Android pseudolocales** (`isPseudoLocalesEnabled`): en-XA, every string
  accented and padded to about 40 % longer, and ar-XB, the English mirrored and laid out right to left. Release
  does not, so the F-Droid rebuild is untouched.
- **`@ComponentShots` and `@ScreenShots` add a `LongText` (en-XA) and an `Rtl` (ar-XB) render, light only,** and the
  script's custom previews carry the same pair. 233 subjects now make 929 images (about 55 MB in LFS, up from
  26 MB), and a warm `validate` takes about two minutes.
- **Every preview names its locale.** Layoutlib resolves a preview with no locale to whichever locale's resources it
  finds, not to `values/`: with the pseudolocales generated, an unnamed Light rendered as en-XA, and a committed
  `values-pt-rBR/` would have done the same. Light and Dark now name `en-rUS`, which re-keyed all 466 existing
  images. 464 came back pixel-identical, under the same LFS objects. The metrics section's sub-kilobyte size,
  the renderer quirk above, now reads `(123) (B)` instead of its raw template.
- **The render fork's heap is 2 GiB.** 1 GiB, sized for 423 previews, ran out at the last 16 of 929.

**Not taken.** Rendering only the screens, not the components, under the pseudolocales would halve the new images.
But a component is where a label clips (a button, a chip, a segmented row), and a screen preview shows each
component in one state only.

**What it does not do.** A reference records whatever its locale looks like, so a clipped label passes once its
image is committed. The check is a person reading a new subject's `LongText` and `Rtl` images before committing
them, and, once a real translation lands, the same per-language pass on a device.
