---
id: "2026-09.wz99"
slug: the-mesh-pauses-in-place-for-a-fixed-span-and-resumes-itself-from-an-inexact-ala
title: "The mesh pauses in place for a fixed span and resumes itself from an inexact alarm"
date: 2026-09-20
topics: [service, android, ui, settings]
---

# ADR 2026-09.wz99 — The mesh pauses in place for a fixed span and resumes itself from an inexact alarm

Status: Accepted (2026-09-20)

**What was observed.** The mesh foreground notification offered one action, Stop. A phone holding several
BLE L2CAP links contends with another app's Bluetooth work — a fitness tracker's sync, a headset pairing —
and what the user wants then is the radios gone for a quarter of an hour, not a messenger switched off. Stop
was the wrong tool twice over: it stays off until the phone is next rebooted or Knit next opened, and it is
undone by *any* open — `KnitApp`'s route effect and `ON_RESUME` observer start the service on every
navigation (ADR 043's retry), and `startMesh` writes `meshEnabled` back to true. There was no "off for a
while" anywhere in the app.

**What changed.** The running notification now offers `Pause 15 min · Pause 1 hour · Stop` (the shade shows
three actions at most, so the 12 h span the request also floated is not offered; roadmap), and a paused one
`Resume · Stop` under "Knit mesh is paused — Until 3:15 PM". A pause is **one DataStore key**,
`SettingsStore.meshPausedUntil` (a wall-clock deadline, `mesh_pause_until`, phone-bound in
`SettingsKeys.TRANSIENT_PREFIXES`), and one pure reading of it, `MeshPause.activeDeadline(raw, now)`: a value
behind the clock is not a pause, for the service, the chat list's "Mesh paused until …" banner (with its own
Resume, `ChatListViewModel.resumeMesh`, which writes the key and nothing else) and the debug bridge alike.
The service **stays foreground and takes the mesh down**: `applyPause` converges on two main-thread fields,
`pausedUntil` and `meshRunning`, calling `meshManager.stop()` / `start()` only across a real transition and
(re)arming the resume alarms only when the deadline changed — idempotent rather than edge-triggered, so the
seed read on the graph worker, the store collector's first emission, a notification tap and the banner's
write can land in any order, or twice, and the mesh ends where the store says. A tap is applied inline before
its write, so the f69x re-claim at the tail of `onStartCommand` posts the paused text in the same call; a
service created mid-pause (sticky restart, boot, the next open) reads the key on the same worker hop as the
graph (ADR 2026-09.vztn) and comes up with the radios down and the alarms armed. Every plane goes down —
LoRa and the relay plane included — because "paused" has to mean what the notification says.

The resume is **two inexact alarms plus every start**. `setAndAllowWhileIdle` reaches a dozing phone, but its
delivery window is `0.75 × (T − now)` (`AlarmManagerService.maxTriggerTime`): on a one-hour pause, 45 minutes
late is legal. `setWindow(…, 10 min)` is bounded on an awake phone (the floor our targetSdk gets anyway) but
is held to a maintenance window in Doze. So both are armed for the same `ACTION_RESUME`, on different
request codes, cancelled as one; and every non-Stop start — the 15-minute heartbeat, a resume of the app —
runs `expirePauseIfDue()` first, so a late delivery is at worst a late one. `SCHEDULE_EXACT_ALARM` was the
exact route and is not taken: on 14+ it is a Settings trip for a messenger, and "Until 3:15 PM" landing at
3:25 on a phone in a drawer is the honest price.

**The alternative a reader reaches for first** is to stop the service and start it again from an alarm. It
cannot work on most phones: since Android 12 a backgrounded app claims the foreground only under a listed
exemption, an inexact alarm carries none, an exact one needs the permission above, and the battery exemption
is opt-in — so the "automatic" restart would be refused (ADR 043's stillbirth) until the next open. A running
foreground service is also what lets the alarms' `startService` land at all (the heartbeat's own shape), keeps
the f69x re-claim and `START_STICKY` recovery, and is the Resume affordance. `MeshTransport.pause/resume` was
the other wrong door: it is the Wi-Fi Direct radio hand-over (ADR 2026-09.wtmz) — NAN only, BLE keeps
running, exactly the radio a pause exists to release.

**What it costs and does not cover.** Two alarms per pause, cancelled on resume, Stop and every `onDestroy`
branch that cancels the heartbeat (one left armed past a death would create a stillbirth on 31+, or on 29-30
a mesh nobody asked for). Stop cancels the store collector *before* `stopSelf()`: `stopSelf` is asynchronous
and `lifecycleScope` outlives `onDestroy`, so the Stop branch's own null write would otherwise raise the
radios for the moment before they came down. A radio-off warning is withheld under the banner, because a
stopped transport keeps the last health it reported. Not covered: a human tapping Resume then Pause inside
the transports' bring-up latency (`transport.start()` is a launch, `stop()` is synchronous) can orphan a scan
loop — the pre-existing open-then-Stop shape, not widened by code here; a Restricted-battery install whose
demoted service takes an alarm-driven start is refused and stops, as ADR 2026-09.f69x already says. The trap:
`pausedUntil` and `meshRunning` are main-thread fields behind `meshStarted`, like everything else the
service reads — a pause applied from another thread, or a `meshManager.start()` added anywhere but
`applyPause`, is the double start the fields exist to prevent. `MeshServicePauseTest` pins the cases
(the two tokens are distinct — `PendingIntent` identity ignores extras; a plain start re-claims without
resuming; a store write of null resumes; a Pause tapped past an expired one never passes through running; Stop while paused does not bounce the mesh); `MeshPauseTest` the
rules; `ChatListViewModelTest` / `ChatListScreenContentTest` the banner.

**Device-verified 2026-09-20 on the Pixel 3 (Android 12, no battery exemption).** Pause 15 min from the
shade: both BLE links down and the NAN requests released within 200 ms of the tap, the notification flipped
in the same post, `dumpsys alarm` showed the two `RESUME_MESH` alarms with the windows the text above
predicts (`window=+11m14s` on the while-idle one — 0.75 × 15 min — and `+10m0s` on the other). Opening Knit
kept the pause and showed the banner. The resume landed **32 s after the deadline** on an awake, non-dozing
phone: radios back, a peer discovered within a second, both alarms cancelled. A reinstall mid-pause (process
killed, `am start`) came up paused with the alarms re-armed for the same deadline; the banner's Resume and
the shade's Resume both raised the radios and cancelled the alarms; Stop on the paused notification stopped
the service with no radio bring-up on the way out, the key cleared and all three alarms cancelled. Not
measured: the resume delay from deep Doze (the P3 needs Doze lifted for Aware anyway) and a reboot mid-pause.
