---
id: "2026-11.zmc6"
slug: a-release-build-fails-on-an-r8-cast-to-a-class-newer-than-minsdk
title: "a release build fails on an R8 cast to a class newer than minSdk"
date: 2026-11-01
topics: [build, release, r8]
---

# ADR 2026-11.zmc6 — a release build fails on an R8 cast to a class newer than minSdk

Status: Accepted (2026-11-01). A sibling of ADR 050's `r8-platform-gate.sh` and ADR 051's `dex-bitmap-gate.sh`: a
third check that reads R8's output, because lint and the tests read only the sources. 2.10.0 and 2.10.1 still carry
the crash. Main and 2.11.0-alpha.2 do not. Amended the same day: the release smoke test now sends a video and runs on
API 29 as well, which reverses the third rejected alternative below.

**What was observed.** Play reported a `NoClassDefFoundError: android.media.metrics.LogSessionId` on 2.10.1
(versionCode 34), Android 11, on 2026-10-31. Retraced, it is `VideoTranscoder.export` → Media3 `Transformer.start` →
`DefaultAssetLoaderFactory.createAssetLoader:199`. The frame's reported line, 72, is a dex pc (R8 maps lines by pc
when it drops debug info). At pc 72 the shipped dex has a `check-cast LogSessionId` that Media3's source does not
contain. R8 9.4.24 (AGP 9.4.1) had merged `DefaultAssetLoaderFactory` horizontally into Tink's `HpkeContext`,
widened its `logSessionId` field to `java.lang.Object`, and put the cast back on the read. ART resolves a
check-cast's class before it looks at the value. On API 29 and 30 there is no such class, so the cast throws even
though the field is null and Media3 guards every use of the class behind an SDK check. So every video pick on
Android 10 and 11 crashes 2.10.0 and 2.10.1, and the mesh service dies with the app. The video path has three
more such casts: two in `ExoPlayerAssetLoader$RenderersFactoryImpl.createRenderers` and one in
`ExoPlayerAssetLoader$Factory.createAssetLoader`.

2.10.1's dex has 16 casts of this shape. The others are safe. The five Spatializer casts sit behind
`SDK_INT >= 32`, and the ScrollCapture, media-metrics and `ApplicationExitInfo` casts run only in callbacks or
lambdas the platform makes on the API that adds the class. 2.11.0-alpha.2 and a main release build (AGP 9.5.0,
R8 9.5.28) have none, and 2.10.0 has 16. Nothing caught it before Play did. `NewApi` lint checks our sources, not
R8's output. `release-smoke.py` never sends a video, and runs on whatever API its emulator has.

**What changed.** `scripts/dex-api-cast-gate.sh` dexdumps the APK and counts each `check-cast` to a class whose
`api-versions.xml` level is above the APK's minSdk, when the register was just read from an `Object`-typed field,
either directly or through `move-object` copies with nothing else in between. That is how R8 emits the read of a
field it widened. It fails above a budget of 0, and given the mapping it retraces each hit to candidate source frames.
It runs in GitLab `build:release`, after both signed builds in `release.yml`, and in the release skill's CI mirror.
On a hotfix branch that predates the script, the mirror runs main's copy as advisory. `distribution.md`'s pre-release
steps ask for it before an alpha's tag. It fails 2.10.0, 2.10.1 and the 10-23 staging build, and passes
2.11.0-alpha.2 and main.

The fix it prescribes is to stop the merge, not to raise the budget. With
`-keep,allowobfuscation,allowshrinking class <the field's owner>` in `knit-r8.keep`, R8 does not merge the class,
so the field keeps its type and the cast goes away. That was checked on a rebuild of v2.10.1 with
`DefaultAssetLoaderFactory` and `RenderersFactoryImpl` pinned: their three casts went and the other 13 stayed.

Rejected alternatives:

- **Count every reference to a class above minSdk.** The libraries reference them legitimately behind SDK checks. 2.10.1 has 90 such references
  and main has 73, so a budget on that count would be noise.
- **Let R8 9.5 be the fix and add nothing.** Whether a class gets merged depends on everything else R8 sees, and the
  staging build shows the defect is not tied to one variant. The next AGP or library bump can bring it back unnoticed,
  exactly as 2.11.0-alpha.1's renamed platform class did (ADR 050).
- **Smoke-test a video send on an API 29 emulator.** It would catch this path and only this path. The gate reads the
  whole dex, libraries included, in four seconds and without an emulator.
- **Turn off horizontal class merging.** That costs dex size and speed across the whole app to save a few classes
  that a keep rule protects at no cost.

**What it costs, and what it does not cover.** The gate sees only the widened-field shape. It does not count a cast
after a null check, an `instance-of`, a `const-class`, or a widened method parameter or return type. None of those
appeared in 2.10.x, and a widened parameter would need its own check. A cast that is safe (behind an SDK check, or
only reached on the newer API) still fails the gate. Before raising the budget, the dexdump has to show that, and the
site has to be written into the script's header, as `dex-bitmap-gate.sh` does. Retrace is approximate: R8 reuses short
names across overloads and merges lambda classes, so a hit can name a `$$ExternalSyntheticLambda` frame with line 0.
Read the dexdump to be sure (ADR 051). The gate scans the APK, not the Play bundle, which comes from the same R8 run
(compared on 2.10.0's build, 2026-10-30). 2.10.x users on Android 10 and 11 keep the crash until 2.11.0 ships or a
2.10.2 hotfix pins the three classes.

## Amendment 2026-11-01 — the smoke test sends a video on API 29 too

The gate covers one shape across the whole dex. It cannot see the rest of what R8 can break, and that only shows on
an old API: a class merged differently, an API-modelling miss, something nobody has seen yet. So
`scripts/release-smoke.py` gains a seventh step, and the release skill's CI mirror runs the script twice. It runs once
on the current AVD and once on `Knit_API29`, an x86_64 `android-29` AVD that `emulator-up.sh --avd` boots beside the
current one and stops afterwards. `distribution.md` asks for both runs before an alpha's tag.

The step pushes `scripts/fixtures/smoke-video.mp4` (3 s, 640×360 H.264 Baseline and AAC, 100 KB) to `Movies/`, finds
it in MediaStore, and shares that `content://` item into Knit with `ACTION_SEND`. It picks Nearby, waits for the
re-encode to stage, sends, and waits for the video bubble. Two harness traps shaped it. A `file://` stream in Knit's
`Android/data` folder passed on API 29, but on API 30+ the folder the shell creates is the shell's, and Knit gets
EACCES. `am start --grant-read-uri-permission` grants nothing for an `EXTRA_STREAM`, because `am` does not copy the
stream into ClipData as an app's `startActivity` does, so the URI goes in as the intent's data too. The staged video
is found by its Remove button, because `chat_staged_video` is newer than 2.10 and the mirror runs the script on a
hotfix branch's build. Onboarding's radio grants follow `requiredRadioPermissions`' API tiers: location below 31.

Checked 2026-11-01: 2.10.1 fails at step 7 on API 29 with Play's stack (`k94.m`, pc 72, `LogSessionId`), and
2.11.0-alpha.2 passes on API 29 and API 37. The runs found a separate bug on main: a shared stream Knit cannot read
crashes the app (`FileNotFoundException` from `decodeOrientedBounded`, or a `SecurityException`) instead of failing
the attach as unreadable.
