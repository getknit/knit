---
id: "2026-09.hj4a"
slug: a-lonely-bluetooth-node-dials-a-larger-peer-it-sights
title: "A lonely Bluetooth node dials a larger peer it sights"
date: 2026-09-30
topics: [ble, transport, interop]
---

# ADR 2026-09.hj4a — A lonely Bluetooth node dials a larger peer it sights

Status: Accepted (2026-09-30; `mesh/bluetooth/LonelyDialPolicy`, JVM-tested, device trial below). Issue #103. The
iOS port changes only its interop harness and docs: its `admit()` (knit-ios ADR 2026-09.6es2) already gives a
lonely dialer the verdict `BleAdmissionPolicy` gives here. No wire byte moves.

**What was observed.** The larger node id dials. A phone that joins a settled, screen-off clique whose peers all
have larger ids waits for one of them to scan, and a settled clique scans at its floor:
`PowerPolicy.settledIdleAfterScan`, which is `idleAfterScan`'s `baseIntervalMs × (1 + links)` — 120 s × 3 = 360 s
with two links, 600 s with four — and never less than `SETTLED_INTERVAL_MS`. The one scan a link-down buys the
clique lands inside the peer's fresh connect backoff (10 s), so the newcomer is not a boost trigger then either.
The issue measured ten minutes and more before the newcomer linked, whatever its own scan was doing.

**What changed.** One exception to the tie-break, on the dialing side only:

- **The rule.** A node holding **zero** Bluetooth links, and holding none for at least
  `PowerPolicy.LONELY_AGGRESSIVE_WINDOW_MS` (180 000 ms), may dial a sighted peer whose node id sorts **above**
  its own (plain string compare, as the tie-break does).
- **The clock** is `noLinkSince`: set at `start()` and again in `teardownLink` when the last link goes. So it is
  the later of transport start and the last link's end, and any link registering ends loneliness
  (`aloneForMs()` is 0 while `links` is non-empty). It is not `lonelyForMs`, which runs from the last link's
  *start* (`lastLinkOrStartAt`) and drives the scan's relaxed cadence (ADR 2026-09.w3xk); that is left alone. An
  adapter-off tears every link down, so a Bluetooth toggle starts the clock at the toggle.
- **The candidates** pass `PromotionPolicy`'s own gates, read from the same `PromotionConfig`: smoothed RSSI ≥
  −90 dBm, dwell ≥ 12 s, off backoff; the transport adds device and PSM known, not linked. The strongest smoothed
  RSSI goes first, and at most one lonely dial is open at a time. "Open" is derived, not kept: the tie-break never
  dials a larger id, so an in-flight dial to one is the lonely dial (`lonelyDialInFlight`).
- **A refusal is an ordinary failed dial** (`HANDSHAKE`) with the ordinary backoff (10 s doubling to 180 s,
  ±20 %). No new penalty, no new counter.
- **The connect loop wakes when the dial comes due.** `LonelyDialPolicy.msUntilDue` (the window closing, or an
  eligible candidate's dwell reaching 12 s, whichever is later) joins the backoff deadline in
  `nextConnectWaitMs`. Without it the dial waits for the next sighting, and on a screen-off phone that sighting
  never ripens the dwell: its scan window is 8 s, and the gap between windows is longer than
  `presenceGapResetMs` (8 s), so every window restarts the peer's dwell. Only the 60 s ceiling would land in a
  gap, a minute late.
- **The oracle.** `bt lonely dial <id> (alone=<ms>ms rssi=<dBm> dwell=<ms>ms)` at info, once per lonely dial,
  right before the ordinary `bt initiating to <id> …`, then `bt connect ok` / `bt link up: <id>` or `bt connect
  <id> failed reason=HANDSHAKE`. The 60 s debug `bt state` line carries `alone=` beside `lonely=`.

**Why the refusal and a cross-dial are safe.** The responder is unchanged: `BleAdmissionPolicy.decide`
(`BleAdmissionPolicy.kt:42-56`, ADR 2026-09.shzv). A responder that has **not** sighted the newcomer admits it
whatever the id order — the case this ADR exists for: a floored clique peer has not scanned since the newcomer
appeared. One that **has** sighted it refuses it (`sighted && local > dialer`), and that responder's own ordinary
dial to the newcomer is what the tie-break keeps: the newcomer is its candidate, and its scan is boosted while it
is. If both dials run at once, the larger peer refuses the lonely one (it sighted the newcomer, or it would not be
dialing), and the newcomer admits the larger peer's dial (sighted, but the dialer sorts above it, and no link is
held). Two links cannot both register for one pair: a responder admits the lonely dial only while it has not
sighted the newcomer, and without a sighting it has no dial of its own to make; were a stale linger ever to let
both through, `registerLink` replaces the first link at each end, as it always has. The HELLO is still
unauthenticated, and nothing here widens what a responder admits.

**A wrinkle fixed on the way.** When the lonely dial is refused after the larger peer's own dial already linked
at the newcomer, `failConnect` used to bump the newcomer's backoff for a peer it now holds a link to (the link's
`registerLink` had cleared the streak a moment earlier), leaving a stale streak for the link's next drop. A
failure for a peer with a live link now bumps nothing.

**The scan needed no change.** A lonely node holds no link, and `ScanDemandPolicy.decide` returns Boost for zero
links (`ScanDemandPolicy.kt:58`), so `floorScan` never floors it. `isBoostTrigger` stays "a peer we'd initiate to
by the tie-break": it only cuts the scan's idle short, and a lonely candidate is already in presence — the connect
loop's own wake above is what times the dial. The scan still relaxes at three minutes on battery (w3xk, kb68);
the relaxed screen-off gap (120 s) is longer than the presence linger (90 s), so a candidate drops out for about
30 s of each cycle, which costs at most one cycle.

**Alternatives dropped.** #103 listed three. Option 2, letting the newcomer's side-channel page wake the clique,
cannot work: the clique's side scan is Off while every capable peer is linked and nothing streams (ADR
2026-09.u8qj, `SideScanPolicy.kt:51`), so nobody hears the page, and pages are dark in release
(`app/build.gradle.kts:350`). Option 3, shortening the settled floor, aims at the wrong number: the 600 s is the
`120 s × (1 + links)` multiplier (`PowerState.kt:59,99`), not `SETTLED_INTERVAL_MS`, and lowering it costs every
settled clique battery all day to shorten a rare join.

**What it costs, and what it does not cover.** A lonely node sighting a larger peer that has sighted it too dials
and is refused until that peer's own dial lands; each attempt pauses its scan for the connect (up to 12 s) and
backs off from 10 s. A lonely node dials a larger peer at the edge of range exactly as an ordinary dial would. It
dials a larger-id iPhone it sighted through its GATT payload (ADR 2026-09.kwq2) too, and the port admits it. An
iPhone waiting below a flagged Android is the port's twin of #103 and is not covered here. The mesh lab has no
radio layer (shzv), so `LonelyDialPolicyTest` pins the rule — the window, the gates, one in flight, smaller ids
left to the ordinary path, the wake — and the device trial is the evidence for the rest. Comments that said a
*smaller*-id peer connects to us (`BluetoothMeshTransport.onForeignReachable`, `PowerState.kt`, w3xk) had the
tie-break backwards and are corrected.

**Device trial** (owed at acceptance): a screen-off clique of three Android phones, Bluetooth toggled on the one
with the smallest id in range, three runs, the iPhone's Knit closed and knit-peer stopped, no `HEAL`, no screen
wake. Pass: each run's `bt lonely dial` then `bt link up` within about four minutes of the toggle.
