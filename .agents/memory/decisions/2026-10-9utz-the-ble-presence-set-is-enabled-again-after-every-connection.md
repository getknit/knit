---
id: "2026-10.9utz"
slug: the-ble-presence-set-is-enabled-again-after-every-connection
title: "The BLE presence set is enabled again after every connection"
date: 2026-10-02
topics: [ble, mesh]
---

# ADR 2026-10.9utz — The BLE presence set is enabled again after every connection

Status: Accepted (2026-10-02). Device-verified on the Pixel 7 with the amendment below (#112).

**What was observed.** knit-ios's link probe ran on 2026-10-01 against a Pixel 7. A link at a 15 ms interval opened
just after the presence set started, and the controller then refused an enable of that set:
`on_set_extended_advertising_enable_complete: … CONNECTION_REJECTED_LIMITED_RESOURCES(0x0d)`. Two kinds of link
did this: a bonded central (`…12:31`, `Meshtastic_1230`) and Knit's own A3 GATT read of an iPhone.

- After the refusal, the presence advert stayed off the air for 33 to 60 s.
- A passive ATS2851 scanner heard it 0.07–0.39 times a second, against about once a second when the phone is idle.
- With the central bonded, the iPhone linked within 30 s in only 2 of 8 samples. The slowest took 109.5 s.

Nothing in the app noticed. `BleAdvertiser` had no `onAdvertisingEnabled`, and a non-null set made `update` swap data
on a set that was off.

The AOSP source explains it (`system/gd/hci/le_advertising_manager.cc`; the logic is the same on 14, 15, 16 and
main):

- **A connection to a connectable set.** The connection terminates the set. `handle_set_terminated` re-enables it
  through `enable_advertiser` with `trigger_callbacks = true`. On an extended-advertising controller the app gets
  `onAdvertisingEnabled(set, true, 4)`.
- **Any connection the stack makes or completes.** The address manager pauses and resumes every set around its
  accept-list and resolving-list writes. `OnResume` re-enables with `trigger_callbacks = false`, so a refusal there
  reaches the app not at all. That is the A3 case.
- **The stack never retries, and never rolls back its bookkeeping.** It still believes the set is enabled. The advert
  returns only when some later pause and resume happens to land after the controller has room again. That later cycle
  is the "came back on its own" in the logs.
- **The legacy HCI path reports success always.** On a controller that uses it, the app's enable callback says 0
  whatever happened.

**What changed.** The transport re-asserts the presence set itself:

- **`BleAdvertiser.reassert()`.**
  - On a live set it calls `enableAdvertising(true, 0, 0)`. The stack sends that command with no "already enabled"
    guard and always calls back with the status. The Core spec (Vol 4 Part E §7.8.56) lets an enabled set be enabled
    again; doing so only resets its duration and event count.
  - On a set whose start failed, it starts one with the newest payload.
  - It does nothing while a start is in flight, after `stop()`, or with the adapter off.
  - `onAdvertisingEnabled` reports every outcome for the live set, asked for or not.
- **`AdvertReassertPolicy.Keeper`** decides when to call it:
  - 2.5 s after any connection opens or closes. That is after the stack's own pause and resume, and outside the window
    in which our enable could make the controller refuse the resolving-list write the pause is for.
  - After a reported refusal, on a doubling wait: 2.5, 5, 10, then 20 s.
  - On a net under both: every 10 s while BLE holds no link, every minute while it holds one.
  - At once on `heal()`.
- **`BluetoothMeshTransport.advertLoop`** runs that schedule. It enables the presence set first, then the Coded set
  (ADR 2026-10.yvn6) while that one is live. The Coded set rides the presence set's turns and keeps no schedule of its
  own.
- **The connection edges come from an `ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` receiver.** It catches the
  bonded watch, a Meshtastic board, the A3 read and other apps' connections, none of which reach the transport any
  other way. The transport also marks an edge itself at link up, link down and the end of a GATT read.
- **Oracles:** `bt advert refused <status>, retry in <ms>` and `bt advert enabled again (refused for <ms>ms)` (`Log.i`),
  plus `advert=` on the debug `bt state` line.

**Alternatives.**

- **Overriding `onAdvertisingEnabled` alone** (the issue's option 1) is the fix a reader reaches for first. It covers
  only the bonded-central case: the `OnResume` re-enable around our own connections, the A3 case, never calls back.
- **Making room in the controller** (option 3): holding the A3 read until the set has settled, or keeping fewer sets.
  This narrows the window, does nothing for a central connecting, and costs the reads their timing. Rejected.
- **A stop-and-restart instead of an enable.** This brings back the old PSM race (`BleAdvertiser`'s doc) and a start
  budget. Rejected.

**What it costs.**

- One binder call and one HCI command per turn: every 10 s alone, every minute linked, plus one per connection edge.
- The loop's wait is a plain `delay`, so it never wakes a suspended phone. The cadence does not relax when the node is
  lonely (kb68), because a lonely node is the one a newcomer must find.
- The settle cannot fully guarantee that our enable never lands inside some other pause the stack starts. The stack's
  own terminated-set re-enable has the same exposure.

**What it does not cover.**

- `WearStatusServer`'s pause-only advert and the side channel's page sets still have no re-assert. The first is up
  only during a mesh pause; the second is non-connectable, dark in release, and already retries its slots. Either can
  take `reassert()` the same way.
- On a legacy-HCI controller a refusal is invisible, so recovery waits on the net.

**Tests.** `AdvertReassertPolicyTest` covers the schedule. `BleAdvertiserTest` checks four things: a live set is
enabled again, a failed start comes back up with the newest bytes, a stopped set stays down, and only the live set's
callbacks are reported. On hardware, the bar is to repeat the 2026-10-01 probe with the central bonded and with it
disabled: the ATS2851 should never hear a 30 s silence, and the iPhone should link within 30 s in at least 7 of 8
samples.

## Amendment (2026-10-02): the ACL receiver is exported

The first Pixel 7 trial (knit-ios link probe, 2026-10-02, eight samples per run) passed with the Meshtastic board off.
Seven of eight iPhone links came within 30 s, and the longest silence the ATS2851 heard was 11.1 s. With the board
bonded the trial failed: three of eight links within 30 s and a 22.1 s silence. Before the fix the same case managed
two of eight, with 33–60 s silences.

The cause was the edge receiver. It never fired on any of the run's 18 ACL edges. The only enables to follow an edge
were the transport's own marks, at link up and at the end of a GATT read, plus the 10 s net. `ACTION_ACL_*` is sent
from the Bluetooth app's process (`BluetoothRemoteDevices`, uid 1002), not from `system_server`. A
`RECEIVER_NOT_EXPORTED` receiver hears only root, `system_server` and its own app, so it never receives these. The
adapter-state receiver keeps working because `system_server` sends `ACTION_STATE_CHANGED`. The ACL receiver is now
`RECEIVER_EXPORTED`. Both actions are protected broadcasts, and a forged edge would only bring an enable forward. It
logs `bt acl edge ACL_CONNECTED|ACL_DISCONNECTED` at debug.

Two observations from the same logs, not acted on here:

- **The Coded set (ADR 2026-10.yvn6) costs the presence set its re-enable.**
  - The stack's resume enables every set in one HCI command. With the board's link at 15 ms the controller refuses
    that whole command (0x0d), and the presence set goes down with it.
  - When `advertLoop` enabled the two sets separately at +11.7 s, presence went back on the air and only the Coded set
    was refused (`coded advertising enable=true status=4`).
  - The stack never removes a refused set from its enabled list, so while the Coded set is live, every later pause and
    resume under load fails as a whole. A shipped build, without the Coded set, should fare better than this trial did.
- **An enable reported as a success can still leave the set off while the link that caused the refusal is open.** In
  the 00:35 run, sample 3, enables at +51.3 s and +52.8 s both reported success during an A3 read's link, yet nothing
  was heard from +49.9 s to +69.5 s, while the ATS2851 kept hearing three other nodes. The first enable after the
  link closed came at +62.8 s, from the net. The `ACL_DISCONNECTED` edge, now delivered, would have brought one at
  +56.8 s.

**The re-run (2026-10-02, 288fc0ea, board bonded).**

- **Coded off (the shipped configuration).** The stack refused nothing all run (no 0x0d line). The edge receiver fired
  21 times, the longest silence was 7.2 s, and 6 of 8 iPhone links came within 30 s. Both misses were on the iPhone's
  side: the ATS2851 heard the Pixel two to eight times inside each of the iPhone's 9–12 s gaps. The silence half of the
  bar passes. The link half, at 6 of 8, falls short only on the iPhone.
- **Coded auto.** 6 of 8 links came within 30 s (up from 3 of 8), and the longest silence was 14.5 s. Each board
  connect was followed by an edge-driven enable 2.5 s later, and every refusal after it was the Coded set's. In 2
  of 8 samples, a presence enable that reported success still left the advert off until the next net turn, about 10 s
  later. That is the Coded experiment's cost to carry (ADR 2026-10.yvn6), not this decision's.
