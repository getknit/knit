---
id: "2026-10.yvn6"
slug: ble-links-step-down-to-the-coded-phy-at-range
title: "BLE links step down to the Coded PHY at range"
date: 2026-10-01
topics: [ble, mesh, power]
---

# ADR 2026-10.yvn6 — BLE links step down to the Coded PHY at range

Status: Accepted (2026-10-01) as an experiment — built and JVM-tested on `feat/ble-coded-phy`, dark in a shipped
artifact behind `BuildConfig.BLE_CODED_PHY` until a walk-apart field trial measures the range it buys and a
battery night measures what it costs. getknit/knit#29.

**What was observed.** #29 asks for the LE Coded PHY (S=8, "long range") so the Bluetooth mesh reaches further
without a LoRa board. Every Bluetooth path ran on 1M: a legacy presence advert, a legacy scan, links on whatever
PHY the stack picked. A throwaway APK on the lab phones (2026-10-01, Pixel 7 + Pixel 8, Knit running on both)
answered what the design hung on:

- **Support is uneven.** The P7 and P8 report `isLeCodedPhySupported`; the Pixel 3 does not.
- **A Coded advert is heard only by a Coded or all-PHY scan.** A 1M scan saw nothing of it.
- **A plain `createInsecureL2capChannel(psm)` to a Coded-only sighting connects on Coded** (`readPhy` tx/rx
  CODED, 0.9–3.2 s). No `connectGatt(PHY_LE_CODED_MASK)` detour is needed to open the link there.
- **A link's PHY moves in place.** A GATT client attached to the live L2CAP ACL in 13–25 ms, and
  `setPreferredPhy` switched CODED ↔ 1M ↔ 2M in 5–700 ms, status 0, with the channel streaming through it.
- **S=8 is honoured, and slow.** On one link at HIGH priority: S=8 ≈ 22 kbps, S=2 ≈ 47, 1M ≈ 70–110, 2M ≈ 120.
- **A controller without Coded answers nothing.** The Pixel 3 gave no `onPhyUpdate` at all to a Coded request.
- **The two RSSIs differ.** At one spot the link read −70 while the adverts read in the −90s.
- At −15 dBm advert power the Coded advert was still heard and the 1M extended one was not — a margin hint, not
  a range number. Range needs phones that move.

**What changed.** The experiment has one seam, the mode flow the DI hands `BluetoothMeshTransport`
(`SettingsStore.debugBlePhyMode`, read OFF while the build keeps it dark): OFF (today's plane), AUTO, CODED
(every capable link pinned to S=8, the walk test's ceiling) and ONE_M (Coded discovery, links pinned to 1M).
Diagnostics and `…debug.PHY` set it, and the transport applies a change without a restart.

- **Discovery.** A second presence set, `BleAdvertiser.codedParams()`: extended, connectable, Coded on both PHYs
  (primary advertising on Coded is always S=8), the presence cadence, HIGH power. It carries the presence payload
  bytes, pushed by the same `readvertise()`, so its PSM cannot lag. The presence scan becomes extended on
  `PHY_LE_ALL_SUPPORTED` (`BleScanner.allPhys`); `setLegacy(false)` still delivers legacy adverts.
- **Scoring.** `BlePresenceTracker` keeps one EWMA per PHY and reports the stronger on the 1M scale
  (`CodedPhyPolicy.effectiveRssi`, Coded + 12 dB), counting a PHY only while it was heard in the peer's latest
  burst. Every −90 floor (promotion, the scan's boost gate, the lonely dial, held-link eviction) is unchanged and
  reads the number it always read when the experiment is off.
- **Dialing.** The rules are untouched. `initiateTo` dials the peer's Coded address only when no 1M advert of it
  was heard in the last 8 s (`CodedPhyPolicy.dialCoded`); the link then opens on Coded.
- **Switching.** Each link to a peer heard on Coded gets a `BlePhyControl` (the `BleDoorbell` lifecycle: `isLive`
  re-checked before `connectGatt`, 2 s attach, closed with the link). The larger node id drives; the other side
  attaches only to read and report. It reads link RSSI every 5 s (2 s at −75 or weaker) into `PhyStepper`: down to
  Coded after three smoothed reads at −82 or weaker, back to 1M after 30 s at −72 or stronger, no automatic switch
  within 20 s of the last, and a request unanswered in 3 s, or answered with another PHY, gives up for that link.

The alternative a reader reaches for first is an advert flag saying "I can do Coded". It isn't needed: hearing a
peer's Coded advert proves it, which spends no flags-byte bit and needs nothing from the iOS port (CoreBluetooth
has no Coded PHY, so an iPhone link never gets a PHY handle). Turning the presence advert itself into a Coded one
is out: it must stay legacy for legacy-only scanners and the 31-byte budget.

**What it costs**, and the traps.

- **Scan time.** An all-PHY scan time-shares its window with Coded, the cost the side scan's 1M pin avoids. The
  spike saw no measurable loss of 1M sightings, inside its noise. The field trial compares OFF and AUTO.
- **Advertising sets.** Up to five (presence, two side slots, the watch, Coded). A refused Coded start leaves it
  dark until the next bring-up (`bt coded advert dark <status>`, `bleCodedAdvertDark`); nothing else depends on it.
- **Throughput.** A link held on S=8 moves about a fifth of 1M. Blobs still prefer Wi-Fi Aware past
  `BULK_MIN_BYTES`, and `BLE_PACE_BYTES_PER_SEC` (28 KiB/s) sits above S=8's rate, so a blob on a Coded link
  fills the stack's queue: measure chat latency behind a blob on CODED before the flag flips.
- **The 12 dB credit and the step thresholds are guesses** sized from one spike. `…debug.PHY` overrides the
  thresholds on a debug build; the credit is a constant.
- **Reachable at Coded range.** A peer sighted on Coded counts as nearby (`reachable ⊇ neighbors` holds, since a
  Coded sighting can dial). If links at that range turn out not to hold, the Coded sighting must stop feeding
  `reachable` rather than the floor being loosened further.
- **Never toggled behind the user's back.** Release reads OFF whatever is stored; flipping `BLE_CODED_PHY` in
  release is a product decision with a CHANGELOG line, after the trial.

Tests: `CodedPhyPolicyTest` (scoring, dial choice, every step rule), `BlePresenceTrackerTest`,
`PromotionPolicyTest`. `BlePhyControl` and the Coded set are device-verified only, like `BleDoorbell`.

## Amendment 2026-10-01 — the first walk: a link held, a far peer never came back

**What was observed.** P9 walked 215 m from P7 with both in AUTO. The link stepped to Coded and held, and room
posts and DMs crossed it well. P9 was then switched to OFF and the link dropped. Back in AUTO it did not re-link
until P9 was back at about the old 1M range. The 256 KiB log ring had rolled over before anyone read it. P7's
counters survived: 114 Coded sightings and 4 Coded dials, and P7 is the smaller id, so those were lonely dials.
Reading the code turned up four causes:

- **The two ends waited on each other.** The responder refused a smaller-id dialer it had sighted at all
  (`BleAdmissionPolicy`, ADR 2026-09.shzv), on the grounds that it would dial that peer itself. Its own dial,
  though, needs 12 s of presence at −90 effective. A faint, occasional Coded hit is enough to refuse with and not
  enough to dial with.
- **Presence kept restarting.** Any 8 s silence reset the 12 s dwell. A far peer's Coded hits come seconds apart:
  a 1 s advert, an all-PHY scan that splits its window, and 10% duty with the screen off.
- **The scan rarely listened on Coded.**
- **OFF asked the link back to 1M**, and at that range 1M could not hold it.

**What changed.**

- **Admission.** While the experiment runs, a dialer heard on Coded alone counts as unsighted
  (`CodedPhyPolicy.sightedForAdmission`), so it is admitted whatever the id order, as an iPhone is. "Coded alone"
  (`codedOnly`) means the peer's last 1M hit trails its last Coded hit by more than 8 s. It is measured as that
  lag, not the 1M hit's age, because a close peer's two sightings go stale together between scan windows.
  `dialCoded` now uses the same test. A peer heard on 1M is judged exactly as shzv says, so the Android mesh
  does not move.
- **Presence.** A Coded sighting that follows a Coded sighting is continuous across
  `PresenceConfig.codedGapResetMs` (30 s). Any other pair keeps 8 s, and the 1M path is unchanged byte for byte.
- **Scan.** `BleScanner.allPhys` became `phys` (`ScanPhys.ONE_M` / `ALL` / `CODED`). While a phone has no link,
  or an unlinked peer is heard on Coded alone, `CodedPhyPolicy.scanPhys` gives every other window wholly to
  Coded. It never schedules two in a row, so legacy-only peers are still heard every other window. A Coded-only
  window does not count as quiet time against an iPhone's GATT payload.
- **OFF lets the handles go and leaves each link on its PHY.** A link left on Coded stays there until it drops or
  the mode returns. `codedCapable` survives OFF, so AUTO re-attaches at once.
- **The record.**
  - `bt coded heard <id> hits rssi=a..b 1m eff dwell promotable dials backoff` is logged once a minute for each
    unlinked peer heard on Coded. It is not debug-gated.
  - `bt scan coded windows on|off` is logged at each edge.
  - `bt refused client … codedOnly=` is now logged at info level.
  - `…debug.PHY` reports each PHY's own `rssi1m` / `rssiCoded`.
  - The 12 dB credit is a tuning field (`PhyTuning.codedCreditDb`, `--ei credit`), read live by the presence
    tracker.
  - Raise the log ring (`adb logcat -G 16M`) before a walk.
- **Diagnostics.** Each directly-connected row whose link has a PHY handle carries a chip for its PHY (`1M` /
  `2M` / `Coded`, with Coded in the tertiary colour). The transport publishes the map on `CodedPhyDiag.linkPhys`
  rather than through the `MeshTransport` / `MeshController` seam, which is not worth widening for something dark
  in release. Plumb it properly if the experiment ships.

**Not done, pending the next walk's numbers:** a faster Coded advert while a far peer is wanted, and any change to
the 12 dB credit's default. Links reach further than adverts (Android caps advertising at +1 dBm, and a link's own
transmit power can be higher), so rediscovery is expected to stop short of where a held link drops.

Tests: `CodedPhyPolicyTest` (lag-measured `codedOnly`, admission, scan windows, the Coded gap, the tunable
credit), `DiagnosticsScreenContentTest` (the chip).

## Amendment 2026-10-01 (2) — the step-up lets the controller pick 2M

A link opens on 1M, the PHY its advert was heard on. The stack then upgrades it to 2M by itself when both ends
support it; the spike's Pixel 3 link was on 2M by its first read. AUTO's step-up from Coded asked
`setPreferredPhy` for the 1M mask alone. That preference lasts the link's life, so a link that once visited Coded
was pinned to 1M for good, even where it would otherwise have run on 2M.

The step-up is now `PhyStepper.Action.REQUEST_FAST`. It passes the 1M and 2M masks together and leaves the pick to
the controller, and either answer completes the request. Only the pinned `ONE_M` mode still asks for 1M alone,
since it exists to compare against today's PHY, so a 2M answer to it gives the link up as before.

AUTO never asks for 2M on its own account; a strong 1M link stays where the stack put it. A deliberate 2M step,
which trades range for airtime, is a separate experiment, and it waits on evidence of a throughput or battery
problem. Tests: `CodedPhyPolicyTest`'s step-up cases.

## Amendment 2026-10-02 — a link that drops at range comes back faster

**What was observed.** The second walk ran on 2026-10-01 from 23:31 to 00:01, with P9 walking and P7 in the lab.

- The link stepped from 1M to Coded at −82 and held for about two minutes. It then dropped on a supervision timeout
  (HCI 0x08) at both ends.
- It took 4m44s to come back. For the first 2m40s P9 heard nothing of P7.
  - Part of that was P9's scan slowing down 12 s after the drop. That was a clock bug, fixed separately in ADR
    2026-09.w3xk's 2026-10-02 amendment.
- After that, P9 heard P7 on Coded alone, at −91 to −95 raw.
  - Three Coded dials ended at the 12 s watchdog. Each time it was our own create-connection cancel, with no error
    from the controller.
  - The fourth reached LE Connection Complete in 7.4 s and linked in 8.6 s.
- The cause sits on both ends of the dial:
  - While a direct connect is pending, Android's initiator listens on Coded for 15 ms in every 60 ms (AOSP `le_impl.h`,
    `kScanWindowCodedFast`). Neither lab phone overrides that.
  - P7's Coded set went out once a second.
  - So each 12 s dial got only a few chances at the edge, and each chance needs four Coded packets to land at −91..−95.

The amendment above expected re-acquiring a link to stop short of where a held one drops. This walk confirms it, and
measures how far short.

**What changed.**

- **A Coded dial gets a 25 s watchdog** (`CodedPhyPolicy.connectTimeoutMs`); a 1M dial keeps 12 s.
  - 25 s is under the stack's own 30 s direct-connect timeout (`connection_manager.cc`), so the watchdog still ends a
    stalled dial.
  - The scan's safety-net wait for a connect in flight grows with it, to 28 s. Each connect's end still wakes the scan.
- **A drop at range makes the Coded advert fast.** The trigger is a link with a PHY handle that ends on its own (`eof`,
  not evicted, stopped or lost to the adapter), on Coded or at or below the step-down threshold.
  - Only the smaller id does it. That is the side the other dials back; the dialer's own advert would not help.
  - For 3 minutes, or until that peer links again, the Coded set goes out every 250 ms instead of every 1 s
    (`CodedAdvertPace`, `PhyTuning.fastAdvertMs` / `fastHoldMs`, settable with `--ei` on `…debug.PHY`).
  - The interval moves in place (`BleAdvertiser.setInterval`): disable, `setAdvertisingParameters` (the stack will not
    write it to an enabled set), enable. The set keeps its handle and its address, since AOSP's `set_parameters` reuses
    the set's current one. A stop-and-restart would allocate a new set, and such a start failed with status 4 twice on
    P7 that night.
  - The change waits 2.5 s after the drop (ADR 2026-10.9utz's settle), so its disable and enable stay out of the
    stack's own pause and resume around the disconnection.
  - While the change runs, `reassert()` does nothing. If the stack has not answered a step 5 s after the change
    began, the next re-assert turn gives the change up and enables the set, so a lost callback cannot keep it off.
  - A refused parameter write or disable leaves the set on the air at its old interval, and nothing retries on its own.
  - Two refused fast enables give the rest of the window up, because a slow advert on the air beats a fast one refused.
    A 250 ms Coded set costs the controller about four times the 1 s one, and on the walk night P7's controller refused
    19 enables beside its links.
- **The Coded set gets retries of its own** (`codedKeeper`, a second `AdvertReassertPolicy.Keeper` with no net). It
  still rides the presence set's re-assert turns as before.
  - A refused enable is retried on the doubling wait and logged, where 9utz only logged it.
  - A start the stack failed internally (status 4) is retried up to five times per bring-up with the current payload.
    The retries are quiet in the log and the metric.
  - TOO_MANY_ADVERTISERS and FEATURE_UNSUPPORTED still leave the set dark until the next bring-up.
- **The record:**
  - `bt phy <id> link dropped on <PHY> rssi=<link RSSI> (<reason>)` for every link with a handle. This fills the walk's
    gap: it never logged a link RSSI at the drop.
  - `bt coded advert fast (<peers>, every <ms>ms)` / `bt coded advert slow`.
  - `bt coded advert refused <status>, retry in <ms>` / `enabled again (refused for <ms>ms)`.
  - `bt coded advert interval <units> refused <status>` and `bt coded advert retry <n>/5`.
  - The `advert` field gains `refused <status>` and `retrying`.

**What it costs.**

- A fast Coded event is about 6.7 ms on air at S=8. At 250 ms that is about 2.7 % transmit airtime, for at most
  3 minutes per drop at range, on the dialed side only.
- A Coded dial can now pause the scan for up to 25 s. That only happens for a peer heard on Coded alone, which is a far
  peer.
- All of it is dark in release, like the rest of the experiment.

Tests: `CodedAdvertPaceTest` (the window, a relink, two peers, giving up, a fresh try), `CodedPhyPolicyTest` (the drop
rule, interval units, the watchdog), and `BleAdvertiserTest`. The last covers: the in-place change and its order;
`reassert` held off during it; a refused parameter write or disable leaving the set on the air; an interval asked for
mid-start, cold, or mid-change; and `withInterval`. The controller's own answers are device-only.

**The next walk checks:**

- On P7: `bt coded advert fast` about 2.5 s after `bt phy <P9> link dropped on CODED`, and no `fast refused`.
- On P9: no `bt scan lonely: relaxed` within 3 minutes of the drop.
- On P9: Coded dials that link in one attempt, or fail at `durMs=25…`.
- Drop to relink, against 4m44s.

## Amendment 2026-10-02 — both presence sets advertise at HIGH, so the credit means 12 dB

ADR 2026-10.ryak raised the 1M presence advert from MEDIUM (−7 dBm) to HIGH (+1 dBm), the Coded set's power since
this ADR. The 12 dB credit is a receiver's margin (Coded S=8 hears about 12 dB deeper than 1M), and it holds only
while both adverts go out at one power. Until now they did not, so a peer heard on both PHYs scored its Coded
reading + 12, which was its 1M reading + 20. The credit stays 12 dB and now means 12 dB. A Coded-only peer is
promotable at the same physical range as before (its reading never moved); a 1M peer's reach grew by 8 dB, so the
1M advert stays fresh further out and `CodedPhyPolicy.dialCoded` picks the 1M address over a slightly wider band,
leaving the `PhyStepper` to step such a link down by its link RSSI, which no advert power touches. The next walk
should note where the 1M advert is last heard, against the walks above.
