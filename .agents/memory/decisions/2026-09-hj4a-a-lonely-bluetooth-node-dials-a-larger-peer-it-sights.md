---
id: "2026-09.hj4a"
slug: a-lonely-bluetooth-node-dials-a-larger-peer-it-sights
title: "A lonely Bluetooth node dials a larger peer it sights"
date: 2026-09-30
topics: [ble, transport, interop]
---

# ADR 2026-09.hj4a — A lonely Bluetooth node dials a larger peer it sights

Status: Accepted (2026-09-30; `mesh/bluetooth/LonelyDialPolicy`, JVM-tested, device-trialled 2026-09-30 below).
Issue #103. The iOS port changes only its interop harness and docs: its `admit()` (knit-ios ADR 2026-09.6es2)
already gives a lonely dialer the verdict `BleAdmissionPolicy` gives here. No wire byte moves.

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
2026-09.u8qj, `SideScanPolicy.kt:51`), so nobody hears the page, and pages were dark in release until 2026-10-08
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

**Device trial, 2026-09-30**, on b6336ddf: the P3, P8 and P9 (P8 the smallest id in range, the Moto G and P7 kept
out, the iPhone's Knit closed, knit-peer stopped), screens dozing, no `HEAL`. Logs in
`~/knit-trials/2026-09-30-103-lonely-smaller-id-dials/`. Six Bluetooth toggles on P8:

- **Runs 1–3, the ordinary path.** Off 10 s, 120 s, and 120 s with P8's Wi-Fi Aware taken dark (`NANFAIL` +
  `NANSTORM`). The link came back 32 s, 46 s and 36 s after the enable, each time by the P3's ordinary dial. A short
  toggle leaves P8 in the clique's 90 s presence linger, and each link-down wakes a scan and a dial. With NAN up,
  its early warning (`onForeignReachable` → chase) boosts the clique's scans too. P8 was never 180 s alone.
- **Runs 4–6, counted.** P8 stayed off until the P3 logged `scan → floor (settled, links=1)` after its post-drop
  dials, and at least 200 s, so it came back already past the window. Run 4: `bt lonely dial ijeeg44…
  (alone=213196ms rssi=-54 dwell=12006ms)`, `bt link up` 12.7 s after the adapter came on, and the P9 logged
  `Admit, sighted=false`.
  The P3's ordinary dial landed 1.2 s later. Run 5: the lonely dial fired (`alone=330895ms dwell=12010ms`) and
  linked the P9 (`Admit, sighted=false`), 1.2 s after the P3's ordinary dial. Run 6: the P3's ordinary dial won
  before P8 sighted anyone, and no lonely dial fired. Links came back 14 s, 135 s and 109 s after the enable.

What the trial showed beyond the rule. `dwell=12006`/`12010` is the `msUntilDue` wake timing the dial. In runs 5
and 6, P8's first relaxed scan window after the enable (8 s at LOW_POWER, then 120 s idle, since its `lonelyForMs`
was long past three minutes) sighted nobody. So a lonely screen-off node's latency is bounded by its own relaxed
scan cycle, up to about 128 s plus the dwell, and not by the window. The lab also never reached #103's own shape.
The P3's floor idle measured 120 s, where 240 s was expected: the phones' `PowerState` read interactive while
dumpsys said Dozing (#105), and a three-phone clique's floor is 360 s screen-off at most anyway. The ten-minute
wait needs a bigger or truly screen-off clique to reproduce.

## Amendment 2026-10-02 — the ordinary dial gets the same wake

The ordinary dial (a smaller-id candidate) had the problem this ADR fixed for the lonely dial: on a screen-off phone
the next sighting restarts the dwell, so the dial waited for the connect loop's 60 s ceiling to land in a gap. The
first Android-to-Android trial measured first dials 58–84 s after a Bluetooth toggle. ADR 2026-10.pj9w adds
`PromotionPolicy.msUntilDue`, the twin of `LonelyDialPolicy.msUntilDue`, to `nextConnectWaitMs`, and moves the
discovery path's waits onto the elapsed clock (`ElapsedWait`), so the lonely window and its dwell wake also keep wall
time on a phone that suspends.

## Amendment 2026-11-01 — a lonely dial ends in a proof

"The HELLO is still unauthenticated" above no longer holds between two builds with link proof (ADR 2026-11.264w): the
lonely dial runs the same handshake as any dial, so the link it makes is registered only once the larger peer proved
its id, and its failure backs off as a failed HELLO does. Nothing here widens what a responder admits; R1 makes it
narrower, refusing an unproven claim to an id that can prove itself.

## Amendment 2026-11-06 — only a crossing dial is excused

Status: Accepted (2026-11-07), after its device trial.

"A failure for a peer with a live link now bumps nothing" was written for the crossing above, a dial the peer's own link
overtook, and said more than that. It also excused a dial that began with its peer already held, which crossed nothing:
such a dial wakes the loop with no backoff booked, and on 2026-11-06 that was 158 refused dials in 40 s (ADR 2026-11.xp6d's
amendment of this date). It also contradicted "A refusal is an ordinary failed dial" here and xp6d's "its own no-reply
backoff".

- **The rule.** `DialPlan.InFlight.linkedAtStart` records whether the peer held a link when the dial began, and a dial
  that did backs off as any refusal does (`DialKeys.linkedNow`). It is a required field: the two readings are opposite
  failures, so no default is safe. A held peer's refusals are bounded below: a peer that does not churn gets the ordinary
  backoff, and a fork that holds each link to the confirm age gets two refused dials per tag and address in each 15 s (the
  limits of that bound are below). A dial with no record stays a
  crossing: a clear crossing's record went with the inbound link's registration, and a link's end has none. A clear dial
  is not moved by this, since `mayBegin` refuses one while its id is linked.
- **The stale streak this would leave.** The wrinkle above was right to fear it. A bump on a tag's dial key while an inbound
  link is held survives that link: an accepted link has no dial key to clear, and a proven dial clears only at its first
  record. So `failConnect` records each key a dial that began held was booked under against the link holding then
  (`HeldStreaks`), and that link's end clears exactly those keys, in `teardownLink`, before the flap is booked, so a link
  closed for silence is covered. Nothing reads presence for it: an entry that aged out or a target that moved between 1M
  and Coded would miss the key, and a read could clear another address's streak, which an unconfirmed proven dial must find
  as the dial left it (ADR 2026-11.264w). A replaced link hands its keys and its age to its successor, since the peer is
  still held and the chain really held that long. Past 16 keys a link drops its oldest, left to its own backoff: the
  current key is the one that matters.
- **Only a link that outlived `ProvenDialStreak.CONFIRM_BY_AGE_MS` clears anything.** Otherwise a fork that dials in, refuses
  our dial and closes resets the streak each cycle. With the rule those refused dials stay on the growing backoff. Holding
  each link 15 s buys it the backoff's first two steps, two refused dials per tag and address in each 15 s (at 0 and 10 s).
  That is the bound, not zero: at that age the link cannot be told from an honest one. The age is 264w's constant,
  borrowed: this bound moves if that does.
- **What that bound does not cover.** An accepted dial resets its key by design, so a fork that accepts and proves escapes
  the age gate at churn rate, two handshakes per dial. A fresh address is a fresh key and is not bounded here; that is an
  open follow-up. A faked crossing books nothing, about one free dial per 8 to 12 s, so its dials are not on this backoff.
- **What the gate costs an honest peer.** A link under 15 s leaves its earned streak standing: at most about 24 s after one
  such link, and a run of short links can climb to the cap of about 216 s until a successful dial resets it.
- **A dial that began held and fails after its link has gone is still booked,** with nothing left to clear it. Leaving it
  unbooked would save an honest relink one step, but it conflicts with the bound: the fork closes first and refuses after,
  its next inbound link resets the id's flap streak, and no dial is ever charged. The cost is one relink waiting about 10 s
  (the 12 s watchdog, 25 s on Coded, plus the 5 s HELLO cut is the window in which it can happen).
- **What is left.** A streak under an unresolved form's handle is not earned by any link: such a dial's record cannot say it
  began held, since a handle is never linked. It is the ordinary backoff, up to its cap.
- **The trial (2026-11-07)** is ADR 2026-11.xp6d's of that date. One dial in 26 relinks began held, the Pixel 9's to
  the Pixel 3 at its advert from before a Bluetooth toggle, 3.7 s after the Pixel 3's inbound link: it timed out and booked
  45 s of backoff (streak 3), not none. No dial was refused. Since #309 a tag dial over 1M that times out forgets
  the address it dialed and the tag's Coded sibling, so for a blinded peer a toggle costs at most one such dial at the old
  advert rather than one per backoff step. A clear dial and a Coded dial forget nothing, so a clear peer is as before. Trialled 2026-11-07 against
  main without it, ten Bluetooth toggles on the Pixel 3 beside the Pixel 9 each: no dial went to a pre-toggle address
  with the change, against three timeouts at the Pixel 3's old address in one toggle without it. Neither arm met a
  40 s median relink, because the Pixel 3 refilled its three links with other phones first and the Pixel 9's dials to
  its new tag timed out at −90 dBm (5 of 10 relinked within 180 s with the change, 3 of 10 without).
