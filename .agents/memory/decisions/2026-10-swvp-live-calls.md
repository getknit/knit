---
id: "2026-10.swvp"
slug: live-calls
title: "Live calls, mesh calls, speech and Knit Anywhere are four build flags"
date: 2026-10-18
topics: [calls, build, tickets]
---

# ADR 2026-10.swvp — Live calls, mesh calls, speech and Knit Anywhere are four build flags

Status: Accepted (2026-10-18)

**What was observed.** One flag, `BuildConfig.CALLS` (ADR 2026-10.3tee's 2026-10-14 amendment), decided four
features at once:

- live calls over WebRTC, whose libwebrtc is about 48 MB of native code across the four ABIs;
- the walkie (ADR 2026-10.79yb), a sub-flag that was off wherever calls were;
- speech (ADR 2026-10.nz3g), whose Moonshine runtime was packaged wherever calls were;
- getting Knit Anywhere tickets, free tickets and the supporter badge, gated on `CALLS_WIRED` because a ticket buys
  TURN and casts (ADR 2026-10.3sk7, 2026-10.s3ep, 2026-10.r99s).

So no build could ship mesh calls without WebRTC, free tickets without calls, or calls without the speech runtime.
The walkie never needed WebRTC: its Opus is the platform's `MediaCodec` (`TalkRecorder`, `TalkPlayer`).

**What changed.** Four flags, each on in debug, off in a shipped artifact and overridden by its own `-P`. Each is read
once in `di/`, and `-Pcalls=` sets both call kinds at once.

| `-P` flag | `BuildConfig` | Read as | Wires | Packages |
|---|---|---|---|---|
| `liveCalls` | `LIVE_CALLS` | `LIVE_CALLS_WIRED` | WebRTC media, the Wi-Fi Direct leg, the capture watch, TURN/STUN, tones, the Calls screen's live rows, `CAP_CALL` | WebRTC, `src/liveCalls/keepRules` |
| `meshCalls` | `MESH_CALLS` | `WALKIE_WIRED` | `WalkieTalk`, its recorder and player, `CAP_WALKIE` | nothing of its own |
| `stt` | `STT` | `STT_WIRED` | the model store, the speech runtime, the key terms; transcripts with the walkie, captions with live calls | Moonshine, `src/stt/` (the download service, `FOREGROUND_SERVICE_DATA_SYNC`, the keep rule) |
| `knitAnywhere` | `KNIT_ANYWHERE` | `ANYWHERE_WIRED` (`di/AnywhereWiring`) | the ticket issuer, free uploads, an invite's customer key, the own badge's switch, the relay rows' free switch | nothing |

`CALLS_WIRED` is either call kind. It wires the call graph, the call screen, the bar and `CallController`, and it
packages core-telecom and `src/calls/AndroidManifest.xml`, since every call of either kind is a self-managed Telecom
call (ADR 2026-10.wbua). `STT_WIRED` is false wherever `CALLS_WIRED` is. The Calls screen and its Settings row exist
where either call kind does (`CALL_SETTINGS_WIRED`): since ADR 2026-10.fcrd every such build has its incoming switches
to set.

- **A build may take mesh calls alone.** Without live calls, `di/CallWiring` hands `CallManager` `CallMedia.None`, so
  `liveEnabled` is false.
  - `start` places every call as a walkie, and refuses `CallRefusal.NO_LIVE` where the peer runs none.
  - A live offer is declined `REASON_MEDIA` before it rings, as a build without the walkie declines a walkie offer.
- **`CAP_CALL` now means "takes a live call".** The chat and the profile gate a walkie on `CAP_WALKIE` alone, and the
  walkie's `liveLikely` is false toward a peer without `CAP_CALL`. Every build that has advertised `CAP_WALKIE` so far
  also advertised `CAP_CALL`, so no shipped pair changes. An older debug build refuses to mesh-call a mesh-only peer as
  "too old".
- **Each kind's and speech's own definitions sit behind its constant.** They live in `liveGraph`, `walkieGraph` and
  `speechGraph` in `di/CallWiring`. Every lookup of one elsewhere (`CallActivity`, `UiModule`, the inbound hooks in
  `MeshModule`, the debug bridge) is an `if (CONST) get() else null`, never a bare `getOrNull`. kotlinc folds the
  branch, so R8 drops what only the branch reached.

The alternative was one umbrella flag with sub-flags under it. That is a smaller change, but R8 drops classes, never
a native library: only packaging keeps libwebrtc out of a mesh-only build, so live calls had to be a flag of their
own.

**What it costs.** Some code stays in every build on purpose:

- the wire's half of the walkie and of TURN (`InboundPipeline.handleTalk`, the cast and `turnreq` code in
  `ScopeSync` and `SpoolConnection`);
- reading a relay's ticket gates and drawing other phones' verified badges (ADR 2026-10.8t2y, 2026-10.r99s);
- `CallManager`'s live branches in a mesh-only build, gated at run time by `CallMedia.None`.

The traps:

- **A debug build packages all three libraries whatever its flags say,** so its lock state never moves. A
  `-PliveCalls=false` debug APK still carries `libjingle`; check packaging on a release-shaped build.
- **A release-shaped build with any flag on is a maintainer's.** Its runtime classpaths go unlocked.
- **The Koin graph is verified per variant.** `KoinGraphVerifyTest` checks the build it runs in, so a graph change is
  checked again under `-PliveCalls=false`, `-PmeshCalls=false`, `-Pstt=false` and `-PknitAnywhere=false`. The verifier
  reads constructors, so a seam passed inline (`CallMedia.None`) is in its `extraTypes`, and a seam that may be absent
  takes a `null` default.

Checked on 2026-10-18:

- The default `assembleRelease` keeps no `call.CallManager`, `ui.call`, `ui.callsettings`, `org.webrtc`, `ai.moonshine`,
  `androidx.core.telecom`, `OkHttpTicketIssuer` or ticket or badge refresher class in its R8 mapping.
- Under `-PmeshCalls=true`, the release keeps `CallManager`, `WalkieTalk` and core-telecom. It keeps no
  `WebRtcCallMedia`, `DirectCallPath`, `CaptureHealth` or speech class, packages no `libjingle`, and its manifest has
  the call screen and `MANAGE_OWN_CALLS` but no speech service. It is 337 KB larger than the default APK.

Guards:

- `CallManagerTest`'s three mesh-only cases;
- `ChatCallButtonsTest`;
- `ChatViewModelTest.aMeshCallNeedsOnlyTheWalkieBitAndEachKindIsOfferedWhereTheBuildTakesIt`;
- `ProfileDetailsViewModelTest.aBuildWithMeshCallsAloneOffersCallToAWalkieCapablePeerOnly`;
- `CallSettingsScreenContentTest.aBuildWithoutLiveCallsShowsTheTranscriptsAlone`;
- `CallSettingsViewModelTest.withoutLiveCallsThereAreNoLiveRowsAndNoTest`;
- `KoinGraphVerifyTest` under each flag.

## Amendment 2026-10-22: live calls, mesh calls and Knit Anywhere ship

2.10.0-alpha.2 shipped the three through `gradle.properties`, taken off main after the tag. The maintainer asked for
them on in release builds for good from 2.10.0-beta.0, so the defaults moved into `app/build.gradle.kts`, where
F-Droid's -P-free rebuild reads them: `liveCalls`, `meshCalls` and `knitAnywhere` now default to true in every build
type. Speech is unchanged, on in debug and off in a shipped artifact, so a release packages no Moonshine and no
`src/stt/`.

- **The lock state records the shipped runtime.** WebRTC and core-telecom are on `releaseRuntimeClasspath` and
  `stagingRuntimeClasspath` in `app/gradle.lockfile`. A release-shaped build is unlocked only when its runtime differs
  from that: `-PliveCalls=false`, `-PmeshCalls=false` or `-Pstt=true`.
- **WebRTC is a notice.** `ThirdPartyNotices` has a `webrtc` row claiming `io.github.webrtc-sdk:`, with its BSD-3-Clause
  text as `License.BSD_3_CLAUSE_WEBRTC` (`legal/BSD-3-Clause-WebRTC.txt`, route `bsd-3-clause-webrtc`, since Zetetic's
  text already holds the SPDX id's route). Core-telecom is claimed by the AndroidX row.
- **The `-P` flags stay.** Each kind can still be turned off for a maintainer's build, so the libraries stay
  `compileOnly` and the per-kind packaging stays as above.

Checked on 2026-10-22: the default `assembleRelease` packages `libjingle_peerconnection_so.so` in all four ABIs, keeps
`CallManager`, `WebRtcCallMedia`, `org.webrtc`, `androidx.core.telecom` and `OkHttpTicketIssuer` in its R8 mapping and
no `ai.moonshine` or `MoonshineSpeech`, and its manifest has `CallActivity`, `CallService`, `MANAGE_OWN_CALLS`,
`FOREGROUND_SERVICE_PHONE_CALL` and `USE_FULL_SCREEN_INTENT` but no speech service. The unsigned universal APK is
123 MB. `KoinGraphVerifyTest` passes under `-Pstt=false`, the shipped wiring.
