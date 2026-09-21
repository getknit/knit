---
id: "043"
slug: a-refused-foreground-start-retires-the-service-instead-of-crashing
title: "A refused foreground start retires the service instead of crashing, and the wedge cure asks first"
date: 2026-08-25
topics: [reliability, service, android]
---

# ADR 043 — A refused foreground start retires the service instead of crashing, and the wedge cure asks first

Play reported `ForegroundServiceStartNotAllowedException` out of `MeshService.onCreate` on Android 15
(Galaxy A14 5G, v2.2.3, still reproducible at HEAD — `5da5601` fixed the *other* one, the 10 s
`ForegroundServiceDidNotStartInTimeException`). The tell is **where** it threw: at `Service.startForeground`
inside `handleCreateService`, not at the `startForegroundService` call site, which is where a blocked
`MeshService.start` would have thrown. So the *creation* was allowed and only the foreground *promotion*
was refused — the signature of a `START_STICKY` restart. Android 12+ lets a backgrounded app claim the
foreground only under a listed exemption, and a system-initiated sticky restart is not one of them. Any
process death off screen — low memory, an OEM app-sleep sweep, or our own Tier-2 wedge cure — therefore
came back to a guaranteed throw out of `onCreate`, which is a guaranteed process kill.

**Why it isn't everyone.** The battery-optimization exemption *is* on the list, and Knit offers it on the
onboarding permission screen. Opt-in, so it splits the install base: users who granted it never see this,
users who didn't crash on every background restart. That is also why the fix cannot be "assume the
exemption" — the unexempted case is the common one.

**The service declines rather than crashes.** `postForeground` returns a `Boolean` and catches
`IllegalStateException` — `ForegroundServiceStartNotAllowedException` extends it, so the catch needs no
`Build.VERSION` dance and no API-31 class reference on a minSdk-29 file. A refused claim makes the instance
a **stillbirth**: `onCreate` calls `stopSelf()` and returns before resolving a single injected field, and
`onStartCommand`/`onDestroy` bail on the same flag — `onDestroy` especially, because `powerMonitor` and
`meshManager` are `by inject()` and touching them there would build the exact Koin graph the early return
exists to skip. `onDestroy` still cancels the heartbeat alarm (no graph needed) so an alarm left armed by an
earlier ungraceful death stops waking the device every 15 minutes for a start the system will refuse anyway.

**`stopSelf()`, not `START_NOT_STICKY`.** Dropping stickiness would fix the crash and cost the mesh every
recovery it currently gets — including the Tier-2 cure, which is *built* on it. `stopSelf()` in `onCreate`
clears the sticky restart record for this instance only, so the system stops retrying a start that cannot
succeed while `START_STICKY` keeps meaning what it means everywhere else. `meshEnabled` is deliberately
left true: recovery is the next foreground app open (`KnitApp`'s `LaunchedEffect`) or the next reboot
(`BootReceiver`, which *is* exempt — `connectedDevice` is boot-permitted through Android 16), both of which
already start the mesh with no new machinery.

**The wedge cure now asks whether it can come back.** `WifiAwareTransport.checkWedge`'s Tier 2 kills the
process to clear a leaked NAN request, relying on `START_STICKY` in its own KDoc. Against an unexempted
backgrounded app that trade was: a wedged data plane in, no mesh at all out, plus a crash. It is now gated
on `canReclaimForegroundService` (`mesh/MeshService.kt`, a top-level function beside `shouldStartMeshOnBoot`
so the transports need no dependency on the service class), which checks the two exemptions we can read
cheaply — a visible activity via `ActivityManager.getMyMemoryState` (`IMPORTANCE_FOREGROUND`; the service
alone only reaches the weaker `IMPORTANCE_FOREGROUND_SERVICE`, so it can't self-satisfy) and the
battery-optimization exemption. **The gate lives in the transport, not in `NanWatchdogPolicy`**: the policy
is the pure episode clock and stays untouched, the transport owns the side effects, and a binder call per
30 s watchdog tick becomes a binder call only when a kill is actually on the table. Declining leaves the
episode clock running and `lastRestartAt` unstamped, so the cure fires on the next check once the app is
foreground or exempt and the wedge has persisted; Tier 1's session cycle keeps retrying at its own cooldown
throughout, so nothing is lost in the meantime.

**Not covered, deliberately.** The 15-minute heartbeat is `setInexactRepeating` + `PendingIntent.getService`,
and only *exact* alarms carry an FGS-start exemption — so after an ungraceful death that alarm cannot revive
the service either; the system blocks the background `startService` silently. Left as is: the alarm's job is
to nudge a *running* service, moving it to an exact alarm would need `SCHEDULE_EXACT_ALARM` for a
best-effort wakeup, and the two real recovery paths above already cover the case.

*Amendment (2026-08-26, work item #32 — the caller side).* The above hardens the *promotion*; the *request*
was still bare. `Context.startForegroundService` throws `ForegroundServiceStartNotAllowedException` at the
**call site**, before the service is ever created, so `postForeground`'s catch sits downstream of that throw
and can never see it — and `KnitApp`'s effect is keyed on the nav destination, so it re-fires on every
navigation and can be scheduled while foreground yet land after a task switch, a screen-off or an incoming
call has taken the foreground away. `MeshService.start` now returns a `Boolean` and carries **both** guards,
neither redundant: `canReclaimForegroundService` declines the starts we can predict will be refused, and a
`catch (IllegalStateException)` closes the check-to-binder race that *is* the bug. `BootReceiver` keeps
ignoring the result — `ACTION_BOOT_COMPLETED` is a listed exemption.

**A refusal is deferred, not dropped.** A bare `runCatching` would stop the crash and leave a messenger with
no transport behind a "searching" notification that never resolves — the loud failure traded for a quiet one.
The retry is the `ON_RESUME` observer that already calls `meshManager.heal()`, where the foreground state is
guaranteed, and it is **unconditional** rather than gated on the pending flag. Unconditional because the flag
is not the only way the mesh can be down without the composition knowing: the route-keyed effect above only
re-fires on a *navigation*, so any live composition that comes back to a dead service — a stillbirth
`stopSelf`'d into a process the Activity kept alive, an OEM sweep that took the service and not the process —
waits for a screen change the user may never make. (This is defence in depth, not a reproduced bug: a sticky
restart that dies in `onCreate` normally takes a fresh process, so the *next* open is a cold start and the
effect covers it.) Starting an already-running service is an idempotent null-action `onStartCommand`, so the
redundant case costs one binder call per resume — next to the `heal()` on the same line, nothing.

**`MeshStartGate` is observability, not control flow.** A Koin single (`mesh/MeshStartGate.kt`) holding one
`StateFlow<Boolean>`, recorded by the *callers* so `MeshService.start` stays graph-free, and surfaced as
`meshStartDeferred` in the debug bridge's `…debug.STATE` reply. Without it a dead mesh is indistinguishable
from a live one with no peers: `transportHealth` sits at its default and `MeshManager.heal()` no-ops on its
own `started` flag. Recovery does not depend on the flag being right — the retry above is unconditional —
so it can only ever be a diagnostic. No Diagnostics-screen row: the refusal is transient by construction
(it clears on the very next resume), so a row would be unobservable in practice.

*Amendment (2026-09-14, ADR 2026-09.f69x).* "An idempotent null-action `onStartCommand`" held only while the
system still agreed the service was foreground. A background-restricted app is silently demoted on leaving
the screen, and a start into that instance armed the `startForeground()` deadline against an
`onStartCommand` that never called it. The service now re-claims the state on every non-Stop start.

*Amendment (2026-09-19, ADR 2026-09.29dw).* "`BootReceiver` keeps ignoring the result — `ACTION_BOOT_COMPLETED`
is a listed exemption" conflated two exemptions. The boot one is the platform's, honoured inside
`startForegroundService`; `canReclaimForegroundService` reads process state, and a receiver's process is
`IMPORTANCE_SERVICE`, so on every phone without the battery exemption the pre-check refused the boot start
before it reached the system. The receiver now goes through `MeshService.startFromBoot`, which skips the
pre-check and keeps the call-site catch, and records the outcome in `MeshStartGate` like the other callers.

*Amendment (2026-09-20, ADR 2026-09.wz99).* "Recovery is the next foreground app open" holds only while
`SettingsStore.meshEnabled` is on: the notification's Stop is now sticky, and `KnitApp`'s two starters ask
`shouldStartMeshFromUi` first. A *refused* start never clears the flag, so the resume retry above is untouched.
