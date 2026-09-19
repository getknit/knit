---
id: "2026-09.29dw"
slug: the-boot-receiver-s-mesh-start-skips-the-process-state-pre-check
title: "The boot receiver's mesh start skips the process-state pre-check"
date: 2026-09-19
topics: [reliability, service, android]
---

# ADR 2026-09.29dw — The boot receiver's mesh start skips the process-state pre-check

Status: Accepted (2026-09-19)

**What was observed.** Work item #76, surfaced during the #62 device trial (ADR 2026-09.535d). On the lab
Pixel 3 (API 31, no battery exemption) an `am start` of `MainActivity` behind the keyguard left the uid at
`TOP_SLEEPING` (importance 325), the route effect's `MeshService.start` was refused by
`canReclaimForegroundService`, and `…debug.STATE` read `meshStartDeferred: true` until the phone was
unlocked and Knit resumed. The boot case follows from the same mapping, and it is worse: a process whose
only work is a `BOOT_COMPLETED` receiver sits at `PROCESS_STATE_RECEIVER` (11), and
`RunningAppProcessInfo.procStateToImportance` reports everything from `PROCESS_STATE_SERVICE` (10) up as
`IMPORTANCE_SERVICE` (300) — three times the `IMPORTANCE_FOREGROUND` (100) bar. So on every phone without
the exemption, `BootReceiver`'s start was refused *inside the app*, logged as "backgrounded, unexempted —
deferred to the next resume", and the mesh stayed down after a reboot until the user opened Knit. ADR 043
said the receiver "keeps ignoring the result — `ACTION_BOOT_COMPLETED` is a listed exemption", which is true
of the platform and false of our pre-check: the exemption rides the *broadcast* (the system delivers it with
the uid on a temporary foreground-service allowlist, `REASON_BOOT_COMPLETED`), and the pre-check reads only
process state, which cannot show it. `KnitApp`'s `ON_RESUME` observer masks this on any phone that gets
opened; an unattended phone after an OTA reboot is the case that does not. Inferred from the mapping and
pinned in Robolectric, not measured on a device — the P3's network adb is a non-persistent `adb tcpip` that a
reboot strands, and `am broadcast BOOT_COMPLETED` is refused to shell.

**What changed.** `MeshService.startFromBoot(context)` is a second entry point beside `start`: the same
`startForegroundService` call and the same call-site `catch (IllegalStateException)`, without the
`canReclaimForegroundService` pre-check. `BootReceiver` calls it and now records the outcome in
`MeshStartGate` like the other callers, so a boot start the system *did* refuse shows as deferred in
`…debug.STATE` instead of vanishing. The alternative a reader reaches for first — teach the pre-check about
boot, say by checking `Intent.ACTION_BOOT_COMPLETED` or the allowlist — does not work: there is no public
read of the temporary allowlist, and the pre-check is a top-level function the transports consult with no
intent in hand (`WifiAwareTransport.checkWedge`). The other alternative, dropping the pre-check everywhere
and relying on the catch, would re-open what work item #32 closed: `KnitApp`'s route-keyed effect fires on
every navigation, and the pre-check is what keeps those from provoking a refusal the system logs against
the app. A flag on `start` (`exempt = true`) was the same change with a less legible call site.

**What it costs and does not cover.** Nothing on the ordinary path — `start` is unchanged. The boot
allowlist window is short (`DEFAULT_BOOT_TIME_TEMP_ALLOWLIST_DURATION`, 10 s from delivery to *this*
receiver), and the receiver reads the settings DataStore before it asks, so a start that lands late is
refused at the call site like any other; that is the catch's job, and it reports rather than throws. On
30-32 the while-in-use consequence from ADR 2026-09.535d stands: a boot-started service holds the
`location` foreground type but no while-in-use grant, so Wi-Fi Aware discovers only on screen until the
first open — expected, named by `TransportHealth.ForegroundOnly`, and no worse than the mesh being down.
The trap for the next person: `canReclaimForegroundService` is now documented as unable to read an
exemption that rides an event rather than the process, and any future receiver-driven start
(`ACTION_MY_PACKAGE_REPLACED`, an exact alarm) needs the same bypass, not a wider pre-check. Kept true by
`MeshServiceStartTest` (the ordinary start refuses `IMPORTANCE_SERVICE`; the boot start reaches the system
there with no battery exemption) and `BootReceiverTest`'s last case, which sends the broadcast to the
manifest-registered receiver over a Koin graph of fakes and asserts the service start and the gate's
record. The device run — a reboot of an unexempted phone with the mesh left on — is owed.
