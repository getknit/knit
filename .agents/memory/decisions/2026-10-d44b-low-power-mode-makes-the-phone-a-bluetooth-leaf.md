---
id: "2026-10.d44b"
slug: low-power-mode-makes-the-phone-a-bluetooth-leaf
title: "Low-power mode makes the phone a Bluetooth leaf"
date: 2026-10-13
topics: [battery, mesh, bluetooth, wifi-aware, power]
---

# ADR 2026-10.d44b — Low-power mode makes the phone a Bluetooth leaf

Status: Proposed (2026-10-13). Phase 0 ran on the Pixel 3 and the Pixel 8 on 2026-10-14 and passed its gate on both;
Phase 1 is built in its first cut (Wi-Fi Aware off and no motion heal, on the switch or Battery Saver), its device
trial owed (the amendments at the end). Nothing of Phases 2–3 is built. The experiment runs on
`exp/low-power-mode`. Phase 1 moves no wire byte. Phase 2 adds a field to the Bluetooth advert's flags byte and a
profile capability bit, both additive under `docs/WIRE_COMPAT.md`, with an iOS half to follow.

**What was asked.** A mode for a phone whose battery is low, or whose owner will be away from a charger for days:
the mesh keeps running, as cheaply as it can, and the owner accepts a worse mesh for it. The first ideas were at
most one link, a slow scan, or a scan only on demand.

## Where the energy goes

**A phone lying still is already close to the floor.** The one clean reading we have is the Pixel 3's of
2026-09-20: about 20 hours on battery, in deep Doze, adb disconnected. Knit's attributed share was about 21 mAh
(Bluetooth 12, CPU 4, sensors 4), or about 1 mAh an hour. Deep Doze had switched Wi-Fi Aware off by itself (the
framework's disable-on-idle default), and nothing of Knit's woke the phone often. The rest of that phone's drain was
the lab: network adb's 1 s TCP keepalive woke it every second or so, and its SIM-less modem hunted for a network. A
battery number needs both gone.

**Knit's timers do not wake a sleeping phone.** A coroutine `delay` runs on the monotonic clock, which stops while
the CPU is suspended, and `ElapsedWait` reads the elapsed clock without ever setting an alarm (ADR 2026-10.pj9w).
The 60 s re-offer, the scan gaps and every tick loop run only while something else holds the CPU up, so slowing one
saves little by itself. What wakes a sleeping phone is a neighbour's traffic arriving over a link or Wi-Fi Aware, a
scan result, the significant-motion sensor, the 15-minute heartbeat alarm, and the spool socket's pings.

**The case the mode exists for is the one Doze does not reach.** A phone carried on a walk never goes into deep
Doze. For that phone, read from the code and not yet measured:

- **Wi-Fi Aware stays up.** Discovery windows, Instant Communication Mode lit for 30 s on every subscribe re-arm, a
  cue to every cue target every `CUE_HEARTBEAT_MS` (30 s), and a data path per sync. It is likely the largest radio
  cost Knit has, and release has no switch for it.
- **The motion heal re-lights both radios.** `MeshService.onSignificantMotion` calls `MeshManager.heal()` at most
  once per `MOTION_HEAL_FLOOR_MS` (60 s). Each heal buys Wi-Fi Aware one re-arm at the aggressive cooldown
  (`healRearmOwed`, ADR 2026-09.kb68) and the Bluetooth scan an immediate window (`wake()`, ADR 2026-09.w3xk). A lone
  phone that keeps moving re-arms up to once a minute, ICM lit about half the time, against the 25 % kb68 relaxed it
  to (10 % on low battery). That was intended, since a moving phone is likely to meet someone, and it is the first
  thing a saver gives up.
- **Every link brings its neighbour's chatter.** Each linked neighbour gets our digest from `reofferToNeighbors`
  every `NEIGHBOR_REOFFER_INTERVAL_MS` of awake time and sends us its own, and every room post crosses each of up to
  `PromotionConfig.DEFAULT_MAX_LINKS` (6) links. Each one that lands on a sleeping phone wakes it. This cost grows
  with the link count, and it is the real reason a link cap pays, more than the airtime a held link spends.
- **Carrying for others costs the most per event.** Serving a blob holds the link busy, at
  `CONNECTION_PRIORITY_HIGH` while either side's screen is on (ADR 2026-10.2da4), and a carrier pulls the
  attachments its carried frames name (`CarrierPullPolicy`).
- **The spool holds a socket.** Its client pings every 25 s on Wi-Fi and 240 s on cellular (ADR 2026-09.wa79). On
  cellular each ping buys the modem's high-power tail.

**What is already cheap.** The presence scan is floored: about 0.6 % receiver duty settled or screen-off, about 4 %
screen-on and alone (ADR 2026-09.w3xk, 2026-09.u8qj). The presence advert costs some 6–18 µA (ADR 2026-10.ryak). A
slower scan or a quieter advert would barely register, and the advert is how a phone that does not scan gets found.
A mode built around the scan rate alone would save almost nothing.

## The mode

**One switch, one key, one reading.** A user switch in Settings, stored as one DataStore key and read through one
pure function that turns the key and the `PowerState` into a posture the planes read: a `mesh/power/` object beside
`PowerPolicy`, JVM-tested, the way `MeshPause.activeDeadline` serves every surface of a pause (ADR 2026-09.wz99).
The posture is an **overlay**. It never writes the user's own settings (the spool switch, the LoRa board, link
previews), so switching it off leaves the phone exactly as it was. Charging lifts the posture while the switch stays
on: the wall pays, kb68's rule. A pause still wins, with the radios down, and the posture applies when they come
back.

The mesh keeps running and the service stays as it is. It is not a pause, and it is not `MeshTransport.pause()`,
which is the Wi-Fi Direct hand-over and is undone by the transfer that took it.

**Turned on by the user.** The switch is offered, never thrown: a prompt when `PowerState.batteryLow` first goes
true (15 %, `PowerMonitor`), and a row in Settings. Following Android's Battery Saver on its own
(`PowerManager.isPowerSaveMode`, not read today) is an open question for Phase 1. The mode costs other people, so
it should not come on behind its owner's back.

## Phase 0 — measure with the switches we have

Two of the biggest levers already exist as debug switches, so the first trial needs no build:

- `…debug.NANOFF` (`SettingsStore.debugNanOff`), applied live by `mesh/SwitchableTransport`: Bluetooth alone,
  without touching the phone's Wi-Fi.
- `…debug.BLECAP` (`SettingsStore.debugBleLinkCap`): at most N links, the weakest shed, a dialer over the cap
  refused.
- The spool and the LoRa board have user switches.

The motion heal has no switch, so it is measured by difference, still against walking.

**The trial.** Three phones on one debug build, on battery, screen off, adb disconnected for each run and
reconnected only to pull logs; a SIM-less phone in airplane mode with Bluetooth and Wi-Fi back on. Doze is held off
on the still arms (`dumpsys deviceidle disable`, restored after) so they stand in for a carried phone. Each arm runs
three hours in the same placement:

1. As shipped.
2. Wi-Fi Aware off.
3. Wi-Fi Aware off, two links.
4. Wi-Fi Aware off, one link.
5. Arms 1 and 3 again on a walk, phones carried, for the motion heal.

Read each arm from `dumpsys batterystats`' discharge step durations (minutes per percent) and Knit's uid lines.
The `bt state` line (links held) and `debug switch: WifiAware off → stopped` are the oracles that the arm ran as
meant.

**The gate.** Phase 1 is built if arm 3 (or 4) stretches the carried phone's minutes per percent by 15 % or more
over arm 1. If no arm does, the mode is not worth a switch, and what the trial found goes into the shipped cadences
instead.

## Phase 1 — the local posture, no wire change

Each lever, the code it touches and what it gives up:

- **Wi-Fi Aware off.** `SwitchableTransport` wraps the Aware child in release too, its `off` flow the debug switch
  or the posture. It already stops the plane live, remembers a hand-over pause across an off spell, and takes the
  plane out of the composite's file route. This gives up fast attachments, the Aware-only peer, and Aware's early
  warning that boosts the Bluetooth scan (`onForeignReachable`). A direct transfer still raises its Wi-Fi Direct
  group on demand.
- **Two links.** The cap seam `debugCap` already uses (`PromotionConfig.maxLinks`, and the at-cap refusal of a
  dialer). Two, not one: on a walk between pockets one link is one drop from alone, and the second is what hands the
  leaf on. One link stays a trial arm.
- **Links at low power.** `BleLinkTuner` maps a `TunePriority.LOW_POWER` that nothing asks for. The leaf asks for it
  at link-up and wherever `LinkBoostPolicy` lets go, where it asks BALANCED today. A file to or from the leaf still
  boosts, because a fast transfer spends less radio time in all. This gives up throughput: a link's drain is the
  host's buffer share times the interval (ADR 2026-10.2da4), and LOW_POWER's interval is 100–125 ms on AOSP against
  BALANCED's 30–50.
- **No motion heal.** `onSignificantMotion` does not heal under the posture. The 15-minute heartbeat's heal and the
  app resume's stay: a heal re-asserts the presence advert (ADR 2026-10.9utz), and a leaf that hardly scans lives on
  its advert. An app resume is the on-demand scan the first ideas asked for, with no button to add.
- **A scan that does not hunt.** Relaxed from the first minute (no `LONELY_AGGRESSIVE_WINDOW_MS`), on the
  low-battery interval (300 s) whatever the battery reads, no Coded range hunt after a drop (`RangeHunt`, ADR
  2026-10.yvn6), and nothing past the settled floor while the leaf holds its cap. It cannot stop scanning outright
  in this phase. The tie-break has the larger id dial (`DialPlan.ordinaryCandidates`), and a smaller id dials a
  larger one only after three minutes with no link at all (`LonelyDialPolicy`, ADR 2026-09.hj4a), so a leaf that
  did not scan would never be linked to a smaller-id peer that has company. Phase 2 closes that.
- **No carrying of attachments.** No carrier pulls for frames that are not ours (`CarrierPullPolicy`'s seam). Small
  frames are still custodied, because that is the mesh's store-and-forward and it is cheap. A fresh ask for a blob
  the leaf holds is still served (ADR 2026-09.4tx5); nobody asks a leaf for what it does not have.
- **Anti-entropy on change.** The leaf re-offers to a neighbour when its own digest has moved since the last offer
  to it, plus a ten-minute backstop, where today it re-offers every 60 s. The backstop keeps the guarantee
  `reofferToNeighbors` exists for, that a lost offer is reconciled, at up to ten minutes' latency.
- **The spool off.** An overlay on `SettingsStore.spoolEnabled`, never a write to it. Its replacement, a check-in
  every 15–30 minutes (connect, sync, close), is Phase 3.

LoRa is left as configured. With a board paired, the board's own battery and LoRa's reach are the cheapest long
range the phone has.

## Phase 2 — the advert says so

**The leaf's state rides the advert's flags byte.** `BleAdvertPayload`'s byte 23 keeps bits 2–7 reserved, and every
shipped parser tests only the bits it knows, so a field there is additive. Two bits make one field with three
values: not a leaf, a leaf with a free slot ("dial me"), and a full leaf ("don't"). The flags are rewritten as the
link count crosses the cap.

**A peer that honours it says so in its profile.** Whether a build dials leaves is a fact about the build, not the
controller, so it is a profile capability (`Protocol` keeps the bits from 0x100 up for the authenticated profile,
and 0x10000 is the next free one), not a third flag. A peer carrying it dials a leaf with a free slot whatever the
id order, never dials a full one, and re-offers to a leaf on change plus the backstop. A leaf waits to be dialled by
a peer it knows carries the capability, and dials anyone else by the ordinary rule. This is
`FLAG_DIALS_GATT_PEERS`'s shape: one side says it will dial, and the other then waits.

With it, the leaf can stop scanning between heals, refused dials against a full leaf stop, and its neighbours' 60 s
re-offers stop waking it.

**iOS.** An iPhone cannot advertise service data, so it can never be a leaf in this form, and it does not dial one
until knit-ios reads the field. Until then an iPhone treats a leaf as an ordinary peer, and a dial to a full leaf
is refused and backs off. It is a paired change, under `.agents/rules/paired-change.md`.

## Phase 3 — later, each its own decision

- The spool check-in in place of the held socket.
- Tap to download for a received attachment over a size.
- A board posture: the phone holds its board link at low power and lets LoRa carry the room and the DMs, with the
  Bluetooth mesh on its floor. This one is the `lora-owner`'s.
- An opportunistic scan (`SCAN_MODE_OPPORTUNISTIC`, which hands over other apps' scan results at no cost) and
  batched results (`setReportDelay`) for the leaf's scan, once Phase 0 says what the scan costs a carried phone.
  Knit uses neither today.

## Alternatives

- **A slower scan, or a scan only on demand.** It addresses the cheapest cost, and a sleeping phone's scan gaps
  stretch anyway. The on-demand part survives as the app resume's heal, and in Phase 2 as a leaf that waits to be
  found.
- **One link.** A trial arm, not the default (above).
- **A quieter advert.** ADR 2026-10.ryak put its cost in microamps, and the advert is how a leaf is found.
- **`MeshTransport.pause()` for Aware off.** It is the Wi-Fi Direct hand-over (ADR 2026-09.wz99 explains why it
  was also the wrong door for a pause), and the transfer's resume would bring Aware back under the posture.
  `SwitchableTransport` already composes the two.
- **Restarting the mesh with a different set of children.** `SwitchableTransport` already turns one child off live
  without taking the other planes down.

## What it costs, and the traps

- **The cost lands on other phones.** A leaf holds two links and carries no attachments. If a whole crowd turns the
  mode on, the mesh thins into pairs and a picture stops travelling by custody. Phase 2's bits let charged phones
  take up the work; nothing makes them. The switch's copy has to say that the phone relays less for others.
- **Delivery is slower both ways.** With Aware off and two links, the leaf hears the mesh only through those links,
  and its own frames leave only through them.
- **An iPhone link keeps its own priority.** `BleDoorbell` asks BALANCED on an iPhone link on purpose (#102: the
  iPhone runs a link it dialled at a 720 ms supervision timeout until asked). Whether iOS grants LOW_POWER's values
  is untested, so the leaf's ask must not override the doorbell's there without an iOS trial.
- **The overlay must never write.** A test pins that switching the posture on and off leaves every user key in
  `SettingsStore` as it was.
- **Guards, once built.** A posture table test beside `PowerPolicyTest`; `SwitchableTransport`'s test for the
  composed gate (the debug switch or the posture); a `mesh/lab/` scenario with a capped leaf in the middle of a
  four-node line that still ends in `assertConverged` for text; and in Phase 2, `BleAdvertPayloadTest` for the
  field and a `DialPlan` test for the leaf's dial rule.
- **Who builds it.** The Bluetooth half (cap, priority, scan, Phase 2's dial rule) goes to `bluetooth-owner` and
  `bluetooth-reviewer`, and the carrier pull and the re-offer to `blobs-owner` and `blobs-reviewer`. The Aware
  switch touches no file under `mesh/wifiaware/`.

## Amendment 2026-10-14 — Phase 0 on the Pixel 3

**What was measured.** `scripts/battery-trial.py` on the Pixel 3 (API 31, a 2.9.0 debug build off
`feat/voip-calling`), from 00:13 to 12:58. The phone came off the charger at 100 %, in airplane mode with Wi-Fi and
Bluetooth back on, with the screen off and Doze held off. adb was disconnected for every window. Three arms ran two
2-hour windows each, interleaved (A B C A B C), each window after 5 minutes' settle (20 for the first). The figure is
the whole phone's mean current, from the fuel gauge's charge counter.

| Arm | Windows (mA) | Mean (mA) | CPU awake | `hal_bluetooth_lock` held | Bluetooth links |
| --- | --- | --- | --- | --- | --- |
| As shipped | 86.3, 87.2 | 86.8 | 38 % | 22 % | 3–4 |
| Wi-Fi Aware off | 43.2, 46.8 | 45.0 | 26 % | 18 % | 3–4 |
| Aware off, two links | 48.6, 40.7 | 44.7 | 18 % | 11 % | 2 |

**What it says.**

- **Aware off saves 42 mA, 48 % of the phone, which nearly doubles the minutes per percent.** The gate asked for
  15 %, so Phase 1 is worth building. Aware held no link in either shipped window, with one to three peers in sight,
  so the 42 mA is what the plane costs on this phone just standing by while Doze is off. For a carried P3 that is
  about 1 Ah a day. It is a question for the shipped build as much as for the saver.
- **The cap made no measurable difference to the current.** The change sits inside the 3–6 mA spread between a pair of
  windows. It did roughly halve the time the Bluetooth HAL's wakelock held the CPU, and it cut the awake share. With
  four links and a quiet clique, links cost the P3 an order of magnitude less than Aware. **So Phase 1 builds Aware off
  and the motion heal first.** The link cap and the low-power link priority wait for a trial that shows them paying:
  a busier clique, or the walk.
- **Nothing the platform bills shows this.** batterystats puts Knit's own uid at 1.5–2.4 mA in every arm. It bills
  Aware's work as modem time: its "Cellular Battery drain" read 41–49 mA in the shipped windows, with the phone radio
  off the whole time, and 4–5 mA with Aware off. The likely reason is that this phone's Wi-Fi firmware runs in the
  modem subsystem. The Bluetooth HAL's wakelock is billed to the kernel. Knit's cost is read from the whole phone's
  charge between arms, never from the Settings battery list or Knit's uid line.

**What it does not cover.** One phone, one night, two windows per arm. Holding Doze off stands in for a carried phone
but is not one: the phone lay still, so the motion heal never fired, and the walking arm is still owed. The P3 has the
lab's oldest Wi-Fi chip, so a newer phone may pay less.

**ICM had no part in it.** Instant Communication Mode needs API 33 (`instantModeAvailable`), so on the P3 (API 31) the
plane never asks for it, and its "icm keepalive" lines are bare publish updates, 27 in four hours. What the P3 paid for
is discovery and the message plane. Session churn is not it either: the coordination-plane watchdog (ADR 2026-09.jjhg)
cycled the session 18 times in one shipped window and 4 times in the other, and the two drew the same current.
Splitting ICM off (the `icmoff` arm, `…debug.NANICM`, which the harness refuses below API 33) needs a newer phone. The
lab's newer Pixels are their owners' daily drivers, so each can give a trial of a few hours, not a night: 30-minute
windows, two or three arms, and the owner's own use flagged out of the windows it touches.

The harness exists to keep two things out of a window: network adb's keepalive,
and Android Studio's `process-tracker` pollers, which Studio starts again every time the phone reappears on adb. Its
`adb` flag threshold went up to 2000 packets after this night's boundary traffic alone ran 365–1367, with no block
showing the keepalive's wake train. The raw data is kept outside the repository.

## Amendment 2026-10-14 — Phase 0 on the Pixel 8

**What was measured.** The same harness on the Pixel 8 (API 37, the same 2.9.0 debug build), from 14:16 to 17:52.
The phone is its owner's daily driver, so the trial was a few hours, not a night: its SIM and home Wi-Fi stayed on,
Doze was held off, and three arms ran two 30-minute windows each, interleaved, each window after 5 minutes' settle
(10 for the first). The owner left it alone, and no window was flagged.

| Arm | Cycle 1 (mA) | Cycle 2 (mA) | Mean (mA) | Billed to Wi-Fi (mA) |
| --- | --- | --- | --- | --- |
| As shipped | 111.9 | 135.8 | 123.9 | 49.7 |
| ICM off | 107.9 | 131.9 | 119.9 | 34.1 |
| Wi-Fi Aware off | 91.9 | 95.9 | 93.9 | 7.3 |

**What it says.**

- **ICM is a small part of Aware's cost.** Turning it off saved 4 mA in both cycles. The rest is discovery and the
  data paths, so a posture that keeps Aware and drops only ICM would buy little. Aware off is the lever.
- **Aware's cost grows with its neighbours.** It cost 20 mA in the first cycle and 40 mA in the second, when a third
  Aware peer came into range and the plane ran syncs (11–12 data-path initiations against 0–2, ten times the
  message-plane sends). The Aware-off windows barely moved between cycles. The 20 mA is the plane's standing cost on
  this phone, half the P3's, and a carried Pixel 8 still pays it all day, so the question for the shipped build stands.
- **The gate passes here too.** Aware off saved 30 mA, a quarter of the phone, about 32 % more minutes per percent.
  The share is smaller than the P3's because a daily driver's own floor is high: about 94 mA with Aware off, the CPU
  awake 73 % of the time, other apps running, and the spool socket held (Knit received about 1,400 Wi-Fi packets per
  window in every arm).
- **The Pixel 8 bills Aware as Wi-Fi,** not as modem time as the P3 does. Knit's own uid line still reads 1–2 mA.

**What it does not cover.** Two 30-minute windows per arm, and the neighbourhood changed between cycles, so the arms
are compared within a cycle. The phone lay still: the walking arm is still owed. The harness needed three fixes to
read API 37: Knit's uid from `appId=`, its power line without the per-part breakdown, and the Broadcom Bluetooth
HAL's `bthal_wakelock` beside the P3's `hal_bluetooth_lock`.

## Amendment 2026-10-15 — Phase 1 as built

**What is built.** The first cut the Phase 0 amendments asked for: Wi-Fi Aware off and no motion heal. The link cap,
the low-power link priority, the scan that does not hunt, the carrier pulls, the re-offer on change and the spool
overlay are not built; each waits for an arm that shows it paying.

- **One reading.** `mesh/power/LowPower.kt`: `LowPowerPolicy.posture` turns the switch, Battery Saver and the charger
  into a `LowPowerPosture`, and `LowPowerSource` is the one flow every plane and surface reads. The posture says
  nothing until the switch has been read, and Battery Saver's state is built from `PowerManager`'s answer, so a
  phone in low-power mode never attaches Wi-Fi Aware at launch only to drop it. A lift the user's own switch did not
  make waits: `LowPowerPolicy.CHARGE_HOLD_MS` (a minute) on the charger, which also covers Battery Saver switching
  itself off at a plug-in, and `LIFT_SETTLE_MS` (5 s) off it, for the moment at an unplug before a sticky Battery
  Saver comes back. Every lift is a fresh attach, and a flapping charger must not spend the attach leak budget (ADR
  052, ADR 055). The mode coming on applies at once.
- **It follows Battery Saver.** Decided with the user on 2026-10-14, answering this ADR's open question: the mode is on
  while the user's switch is on or `PowerManager.isPowerSaveMode` reads true. With only Wi-Fi Aware and the motion
  heal given up, the phone still relays fully over Bluetooth, so the objection that the mode costs other people,
  which kept it to the user's hand, mostly falls away. `PowerMonitor` writes Battery Saver to `BatterySaverState`,
  beside `PowerState` rather than in it: no radio's cadence reads it.
- **The switch.** `data/settings/LowPowerSwitch`, one key (`low_power_mode`) over the settings DataStore, kept out of
  `SettingsStore` like `TicketKeyStore`. It is the only key the mode writes (`LowPowerSwitchTest`), and `low_power_`
  is a backup-transient prefix: the setting is about this phone's battery. The Settings row sits above the battery
  rows and says when Battery Saver holds the mode on or the charger holds it off.
- **Wi-Fi Aware off.** `SwitchableTransport` now wraps the Aware child in every build, its switch
  `awareSwitchedOff(debugNanOff, posture)`. No file under `mesh/wifiaware/` changed. A switch-driven start waits
  `SwitchableTransport.RESTART_FLOOR_MS` (5 s) after the plane's last stop and a switch-driven stop waits `UP_FLOOR_MS`
  (10 s) after its last start, because the Aware transport's stop and start race inside those windows (below); the
  mesh's own start and stop are never floored. The floors run on awake time, so a stop deferred into a doze lands at
  the next wake, the heartbeat at worst. The plane's health and `switchedOff` follow what it is doing, not what the
  switch asks. A resume always reaches the Aware child, up or not: its hand-over hold outlives its own stop, and a
  call that ended while the plane was off would otherwise leave it held for good.
- **A switched-off plane is not a radio the user turned off.** It reads `TransportHealth.Unavailable`, which the chat
  list's banner took as Wi-Fi off. `MeshTransport.switchedOff` and `TransportStatus.switchedOff` now say Knit did it,
  `radioWarningFor` counts such a plane as absent, and Diagnostics tags its row "low power".
- **No motion heal.** While the posture is on, `MeshService` leaves the significant-motion sensor unarmed, so a
  carried phone takes neither the sensor's wake nor the heal; it arms again when the posture lifts. The heartbeat's
  and the resume's heals stay (ADR 2026-10.9utz).
- **Seen from outside.** The field log writes `low_power` at each posture edge, the bridge's `STATE` carries
  `lowPower` and each transport's `switchedOff`, `…debug.LOWPOWER` sets the switch, and `battery-trial.py` has a
  `lowpower` arm.

**What the review found in the Aware plane.** `wifi-aware-reviewer`'s advisory pass on this cut found that
`WifiAwareTransport.stop()` moved no generation, so an attach in flight at a stop came back as a session nothing
closed, ICM lit and a responder filed, for the whole low-power spell. Until now only a mesh stop reached that; the
mode's switch is the first thing to stop and start the plane routinely in release. Fixed with this cut: `stop()`
clears a `started` flag that `attach()` and `reattach()` respect, and posts `closeStoppedGeneration()` to the plane's
handler, which moves `attachGen` and closes whatever the callbacks queued ahead of it created. Left as
`wifi-aware-owner` follow-ups, none reachable from the posture behind the floors: `started` is a flag, not a
generation, so a delayed post from before a stop (the 800 ms wedge reattach, the serve reattach) can act on the next
life after a quick `MeshManager.restart()`; `onSubscribeStarted` and `onAwareSessionTerminated` have no generation
check; and the rest of `stop()`'s teardown still runs on the caller's thread.

**What is owed.** The device trial: the switch, Battery Saver and the charger's hold on one phone, with the Aware row
switched off, no Wi-Fi banner and the sensor disarmed; then a `shipped,lowpower` battery run, which should land near
the Aware-off arm's 45 mA on the P3. The walking arm still owes the motion heal's own figure.

## Note 2026-10-20 — cold Coded hunts stay off under the posture

ADR 2026-10.yvn6's 2026-10-20 amendment adds radio duty nobody asked for: a cold far sighting on Coded starts a
bounded hunt on the dialing side or a fast Coded advert on the dialed side. Neither starts while the low-power posture
is on: `BluetoothMeshTransport` reads `LowPowerSource.current` through a `lowPower` seam wired in `di/MeshModule`, and
`CodedPhyPolicy.coldHunt` answers `NONE`; a cold hunt already running ends at the next window
(`bt hunt <id>: cold hunt over (off)`), and a cold fast window runs out its hold (3 min at most). This is not a new
lever of Phase 1: the posture still gives up only Wi-Fi Aware and the motion heal, and a drop's hunt and fast advert
are untouched. It keeps the new duty out of a phone the user or Battery Saver asked to save power.


## Amendment 2026-10-24 — not offered for now (ADR 2026-10.2t49)

The mode is dormant: `LOW_POWER_OFFERED = false` in `mesh/power/LowPower.kt` hides its Settings row and makes
`LowPowerSource.posture` inactive whatever the switch, Battery Saver or the charger say, so nothing the person cannot
see turns Wi-Fi Aware off or leaves the motion heal unarmed. Today its one lever, Wi-Fi Aware off, is the person's own
Settings › Wi-Fi Aware switch (`data/settings/AwareSwitch`), and a phone with no Aware peer near now detaches by itself
(2t49's dormancy), which is most of what the mode was for. A phone that had the mode on is moved to the switch set off,
once. The code, the key and `…debug.LOWPOWER` (which answers `offered: false`) stay for a future posture that gives up
more than Aware; flipping the constant brings the row and the Battery Saver trigger back together.
