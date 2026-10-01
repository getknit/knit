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
