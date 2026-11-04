---
id: "2026-11.f8pg"
slug: an-update-of-knit-restarts-the-mesh-like-a-reboot-does
title: "An update of Knit restarts the mesh like a reboot does"
date: 2026-11-04
topics: [reliability, service, android]
---

# ADR 2026-11.f8pg — An update of Knit restarts the mesh like a reboot does

Status: Accepted (2026-11-04)

**What was observed.** A Play auto-update stops the app being replaced: the package manager kills the package,
its services included, and leaves no `START_STICKY` record behind, so the mesh service does not come back by itself
(the maintainer noticed a music app stop mid-playback at night when an update landed). Knit's only starters were
`BootReceiver` (`BOOT_COMPLETED` alone) and `KnitApp`'s route and resume effects, so after an unattended update the
mesh stayed dark until Knit was next opened. Builds before ADR 2026-11.82s7 were partly covered by accident: their
repeating `getService` heartbeat alarm survives an update, and on a battery-exempt install it started the service
again within about 15 minutes. 82s7 moved the heartbeat to listener alarms, which die with the process, so on main
nothing restarted it at all. Read from the code and the platform's documented exemptions, not yet measured on a
phone after a store update.

**What changed.** `BootReceiver` also takes `ACTION_MY_PACKAGE_REPLACED`, which the system sends only to the newly
installed version of the package that was replaced (a protected broadcast; not covered by the implicit-broadcast
limits, since it targets the app). It runs the same decision as a reboot, `shouldStartMeshOnBoot`: the mesh was left
enabled, the radio grants are held, not a seeded demo build. It starts through `MeshService.startFromBoot`:
`MY_PACKAGE_REPLACED` is on Android 12's list of background foreground-service-start exemptions beside
`BOOT_COMPLETED`, and like that one it rides the broadcast, so the process-state pre-check would refuse it
(ADR 2026-09.29dw). A pause survives too: the service reads `meshPausedUntil` on create (ADR 2026-09.wz99).

The alternative a reader reaches for first is to bring back a `PendingIntent` alarm that outlives the process. That
restarts the service only where a background start may claim the foreground (the battery exemption), only after up
to a heartbeat, and re-opens the "executing service" ANR 82s7 closed. A second receiver class was the other option;
the gate, the start path and the deferral record are the same, so one receiver takes both actions.

**What it costs and does not cover.** Every install over a running build now starts the mesh, `adb install -r` on a
lab phone included; a phone the user stopped stays stopped. The start lands inside the broadcast's short allowlist
window; a refusal is recorded in `MeshStartGate` and `KnitApp`'s resume retries, as for a reboot. On API 30-32 a
service started from a receiver gets no while-in-use location capability, so Wi-Fi Aware discovers only on screen
until Knit is opened (ADR 2026-09.535d, the residual a boot start already has). Regression: `BootReceiverTest` drives the
receiver with each action from a receiver-importance process without the exemption and requires the service start.
