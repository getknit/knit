---
id: "2026-09.535d"
slug: the-mesh-service-asks-for-the-location-foreground-type-on-the-location-tiers
title: "The mesh service asks for the location foreground type on the location tiers"
date: 2026-09-18
topics: [reliability, service, android, permissions]
---

# ADR 2026-09.535d — The mesh service asks for the location foreground type on the location tiers

Status: Accepted (2026-09-18)

**What was observed.** On the lab Pixel 3 (blueline, Android 12, API 31) Wi-Fi Aware worked only while
Knit was on screen. The moment the activity left the foreground, the discovery loop's next
`rearmSubscribe` threw `SecurityException: UID 10207 does not have Coarse/Fine Location permission` out
of `WifiPermissionsUtil.enforceLocationPermission`; `WifiAwareTransport.onSessionDead` read that as the
framework-side client dying, dropped the whole session and re-attached; the attach succeeded (it needs
no location), `startPublish` threw the same way, and round it went — 45 attach cycles in four minutes,
`disc=[] cue=[] reach=[]` throughout, and a node that was not even a responder in between. The previous
build's process showed the same blindness before the restart, so it was not new; it went unnoticed
because no pre-33 device in the lab meshed over Aware until the Pixel 3 arrived (the Moto G has no
Aware). Work item #62, found during the ADR 2026-09.kb68 battery trial, whose lonely-node measurement it
also masked (every re-attach resets `lonelySince` by design).

It was mistaken for a radio fault, and it is a permission-model fact. `ACCESS_FINE_LOCATION` is granted,
but as "While using the app": the location **app-op** is `MODE_FOREGROUND`, and on API 29–32 the Aware
service checks that app-op on every `publish` and `subscribe`. `checkSelfPermission` still says granted;
only the call fails. From 33 discovery rides `NEARBY_WIFI_DEVICES` with `neverForLocation` and none of
this applies.

**What changed.** The manifest declares `foregroundServiceType="connectedDevice|location"`, and
`MeshService.postForeground` claims `meshForegroundServiceTypes()` — `connectedDevice` everywhere, plus
`location` on exactly the API levels where `ui/Permissions.kt`'s `requiredRadioPermissions` carries the
location grant (29–32). The function derives from that permission set rather than from a second `SDK_INT`
ladder, so the two tiers cannot drift; `MeshForegroundServiceTypesTest` pins the mapping and
`MeshServiceForegroundReclaimTest` pins that sdk 36 claims `connectedDevice` alone.

Read from `ActiveServices` and `OomAdjuster` on `android12-release`, not from memory, because the
obvious version of this fix — the manifest attribute — does nothing on its own:

- The app-op is lifted for a backgrounded uid by the `PROCESS_CAPABILITY_FOREGROUND_LOCATION` capability,
  which `OomAdjuster` derives on every pass from the **live** `ServiceRecord.foregroundServiceType` — the
  bits handed to `startForeground` — ANDed with `mAllowWhileInUsePermissionInFgs`. Before this change the
  pre-34 branch passed `0`, so on the very versions that needed the bit the service held no type at all
  (`ServiceCompat` forwards `FOREGROUND_SERVICE_TYPE_NONE` verbatim and masks anything else to the
  since-Q set, which both of ours are in).
- On 29 that is the whole story: a location-typed foreground service sits at
  `PROCESS_STATE_FOREGROUND_SERVICE_LOCATION` and the op passes, however the service was started.
- On 30–32 the capability also needs `mAllowWhileInUsePermissionInFgs`, which `setFgsRestrictionLocked`
  grants only to a start from a TOP or visible uid (or system, device owner, the temp allowlist, a
  background-activity token). **The battery-optimization allowlist is not on that list, and neither is
  `BOOT_COMPLETED`** — both are exemptions to the *start* restriction, not to while-in-use. The flag is
  re-evaluated on every `startService` while false, and `KnitApp`'s `ON_RESUME` observer already calls
  `MeshService.start` from a visible activity before it heals, after which `onStartCommand` re-posts the
  state with the type (ADR 2026-09.f69x). So a boot- or sticky-restarted service on 30–32 still
  discovers only on screen until Knit is opened once, and from then on for the life of the record.
- Only `publish` and `subscribe` are location-gated in `WifiAwareServiceImpl`; `attach`,
  `updatePublish`, `sendMessage` and `requestNetwork` are not. Whatever was up before the refusal —
  the attach, the publish, the responder, every NDP — keeps working. That is what made tearing it all
  down the wrong response.

So the transport now tells the two throws apart. `NanSessionFault.classify` (pure, tier-gated to < 33)
reads a `SecurityException` naming location as `OffScreenLocation`, and everything else — the
`invalid uid+clientId mapping` of a client NAN cycled under us included — as `DeadSession`, which still
takes the old `onSessionDead` road. A location refusal is **held**, not cured: nothing is closed, an
`offScreenBlocked` latch keeps `startPublish` / `startSubscribe` (and through them the loop's re-arm,
the ICM relight and a fresh attach's discovery halves) from calling into the framework, the health reads
the new `TransportHealth.ForegroundOnly`, and the next `heal()` — the app's resume, the 15-minute
heartbeat, the motion trigger — clears the latch and re-files whichever half is missing on the live
session, once. A retry that throws re-latches; a publish or subscribe that takes clears the verdict.
`checkWedge` is inert meanwhile (it reads `Healthy`), which is right: a refusal is not a wedge.

The verdict is a fourth `TransportHealth` value rather than a side channel because every surface that
names a radio state already switches on that enum, and "radio busy" — what the churn used to look like
in Diagnostics — was the wrong story. The composite ranks it between `Healthy` and `Degraded` ("open
Knit" is a cure the user holds; "radio busy" is a wait; any plane that works still wins). The ongoing
notification is the one surface seen *while* it holds, and tapping it opens Knit, which is the cure, so
its line says so; Diagnostics, the chat header and Your mesh name the rule on this version of Android
and that Bluetooth is unaffected; `RadioWarning` ignores it (nothing to switch on).

The alternative a reader reaches for first is `ACCESS_BACKGROUND_LOCATION` on 29–32. It would work on
every start path, and it was not taken: it is an "Allow all the time" dialog for a position Knit never
reads — ADR 2026-09.tss4 keeps `location/AndroidLocationSource` the one `android.location` importer,
collected between a tap and a send and never in a service — and it moves the app into Play's
background-location policy for the sake of a radio. The foreground type grants the *radio's* discovery
call the app-op it needs; no code path gains a position.

**What it costs.** Lint's `ForegroundServicePermission` wants `FOREGROUND_SERVICE_LOCATION` for the
manifest attribute (targetSdk ≥ 34), and the `<service>` suppresses it on purpose: the platform checks
that permission against the *runtime* type at `startForeground`, which on 34+ is `connectedDevice`
alone, and declaring it would put a location foreground service on Play's declaration form for a type
this app never uses there. That is a bet on Play keying its form on permissions rather than on the
attribute; the next upload verifies it, and the fallback is the permission with `maxSdkVersion="32"`.
The while-in-use residual on 30–32 is real: after a reboot, until the user opens Knit once, a Pixel 4
on Android 12 finds phones over Wi-Fi only while on screen — Bluetooth carries in the meantime, the
notification says what to do, and the 15-minute heartbeat retries in case an OEM grants what AOSP does
not. Deliberately not built: a debug fault that simulates the refusal (the Pixel 3 reproduces it by
being backgrounded), and any attempt to detect foreground state inside the transport — `heal()` is the
one signal and the app already sends it at the right moment.

The traps for the next person. Passing `0` (or `FOREGROUND_SERVICE_TYPE_MANIFEST` without the bit) to
`startForeground` on 29–32 silently drops the location type again — the manifest is a bound, not a
grant. A second `SDK_INT` ladder for the type instead of `requiredRadioPermissions` is how the two tiers
drift. And `onSessionDead` must never see a location refusal: the churn it produces is 45 attaches in
four minutes against `NanAttachPolicy`'s per-process leak budget. What keeps this true:
`MeshForegroundServiceTypesTest`, `MeshServiceForegroundReclaimTest`, `NanSessionFaultTest`,
`CompositeMeshTransportTest.healthForegroundOnlySitsBetweenHealthyAndDegraded`, `RadioWarningTest`,
and the copy cases in `ConnectionStatusRowTest`, `DiagnosticsScreenContentTest` and
`YourMeshScreenContentTest`.

**Device trial, Pixel 3 (blueline, API 31), 2026-09-18.** Before the build: `appops get` read
`FINE_LOCATION: foreground` with a `rejectTime` seconds old, `dumpsys activity processes` read
`curCapability=---N` for the backgrounded service, and the log held a refusal every ~5 s. After it: with the
service started from the open app and the app then sent home (`curProcState=4`), `curCapability=L--N`,
`allowWhileInUsePermissionInFgs=true`, and over two off-screen windows (17 min and 7 min 40 s) zero location
refusals, zero re-attaches and 27 successful subscribe re-arms in the second; the plane discovered the
Pixel 8 and Pixel 7 (cues for three minutes) and later the Pixel 9 while backgrounded. NAN sightings of the
lab peers were intermittent at midnight while Bluetooth held them linked — their side, not this phone's.
The residual was exercised by forcing the app-op (`appops set --uid … FINE_LOCATION ignore`, a fresh
process): one `publish refused off screen` line, `offscreen=true` on the state line, no churn; restoring
the op and `…debug.HEAL` logged `retrying discovery after an off-screen location refusal` and re-armed the
responder. The `…debug.STATE` dump now carries a per-radio `transports` array, since the merged `health`
read `Healthy` throughout on Bluetooth's account. Not run: a real reboot — the Pixel 3's network adb is a
non-persistent `adb tcpip`, and a reboot strands it; `am broadcast BOOT_COMPLETED` is refused to shell.
Two things the trial turned up that are not this ADR's (work items #76 and #77): `canReclaimForegroundService` refuses
`MeshService.start` from a `TOP_SLEEPING` uid (an `am start` behind the keyguard, `meshStartDeferred=true`),
and by the same arithmetic — `PROCESS_STATE_RECEIVER` maps to `IMPORTANCE_SERVICE` — it must refuse
`BootReceiver`'s start on an unexempted phone, which ADR 043 says is exempt; and the responder's
`onUnavailable` re-file ran 174 times in 130 ms with no backoff before a re-attach ended it.
