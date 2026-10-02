---
id: "2026-09.sjaa"
slug: ble-side-channel
title: "BLE side channel: small floodable frames ride non-connectable extended-advertising pages"
date: 2026-09-16
topics: [mesh, bluetooth]
---

# ADR 2026-09.sjaa — BLE side channel: small floodable frames ride non-connectable extended-advertising pages

Status: Accepted (2026-09-16) — built and JVM-tested; device-trialled 2026-09-17 (results at the end); dark in a
shipped artifact (`BuildConfig.BLE_SIDE_PLANE`, debug on / release off) until the two items the trial left open
are done. Work item knit/knit-next#13.

**What was observed.** On the BLE plane every frame and every file share one ordered L2CAP CoC stream per
link. A blob in flight head-of-line-blocks the small frames behind it and, by saturating the ACL, starves the
reverse direction, so chat stalls while a GIF sends. `TransferPacePolicy` (2.1.0) caps the feed below link
capacity so text interleaves near the wire head; it makes the two share more politely, it does not separate
them. The Wi-Fi Aware plane has had the structural answer since ADR 004: a coordination plane
(`sendMessage`, ~255 B, no data path) that reaches every neighbour at once and keeps working while the one
NDP is busy. BLE had no equivalent. `BluetoothMeshTransport` declared no fast plane, so
`CompositeMeshTransport.fastFanout` turned the fast path into a plain `send` over the links — the same
stream the blob sits on — and a peer that was sighted but not linked (over the link budget, in connect
backoff) got nothing until a link came up. The frame-codec half already existed and named this carrier:
`mesh/link/FastFrameCodec` (ADR 030/060) is transport-neutral by design.

Two radio facts shaped everything below, both from the Bluetooth core spec and Android's stack rather than
from a trial. An AD structure's length byte caps one service-data structure at 252 B, so a page longer than
one AUX PDU cannot even be encoded under one UUID; and `LE Set Extended Advertising Data` on an *enabled*
set accepts only a single complete operation (≤ 251 B) — a longer update is `Command Disallowed`, which
Android surfaces as a failed data set with the old page left on air, the stale-advert bug `BleAdvertiser`
was rewritten to remove. A chained page is also caught or lost whole: with per-PDU loss p a three-PDU chain
lands with probability (1−p)³. So the "chunk a frame across adv PDUs" the issue anticipated is done at the
application layer, with the fragments the codec already has, not by the controller.

**What changed.** The side channel is **internal to `BluetoothMeshTransport`**, as NAN's two planes are
internal to `WifiAwareTransport` — not a third composite child, which would add a duplicate Bluetooth row
to `CompositeMeshTransport.statuses` and need the adapter, the presence table, the connect gating and the
link map exported. `mesh/bluetooth/BleSideChannel` is the Android carrier; beside it four pure, JVM-tested
pieces: `SideCarousel` (the schedule), `SideScanPolicy` (the scan tier), `SideCapableTracker` (who can
hear), `BleFastRoutePolicy` (link copy vs page).

- **A page is one coordination-plane unit.** Service data under `BleConstants.SIDE_SERVICE_UUID` (`0xFE38`;
  presence UUIDs count up from `0xFE30` across wire breaks and `0xFE31`/`0xFE32` were spent pre-launch, so
  page UUIDs count up from `0xFE38` and a page-layout break bumps this one alone) carrying exactly one
  `0x03`/`0x05` frame from `FastFrameCodec.encodeBest(transcode = true)`, or one `0x04` fragment of one, at
  most `BleSideChannel.PAGE_BYTES` = 236 B — one AUX_ADV_IND, so every in-place `setAdvertisingData` is one
  complete HCI operation. A cleartext receipt, reaction, typing cue, a 40-char and a 200-char room post each
  ride one page; the profile bootstrap rides two; the ceiling is 3 × 232 = 696 B, past which the frame rides
  the links and the flood alone (`bleSideTooBig`), exactly as on the NAN plane. Pinned by
  `CoordinationPlaneSizeBudgetTest.theInteractiveSetRidesOneSidePageEachAndTheProfileFitsTheCeiling`. The
  tag registry is the shared one (docs/WIRE_COMPAT.md); no legacy `0x01` ever rides a page, because every
  peer that advertises the flag necessarily has `CAP_FAST_COMPACT`, `CAP_FRAME_TRANSCODE` and `CAP_CRYPTO_V3`.
- **No hop identity in the page, and a page is never presence.** Each advertising set gets its own
  resolvable private address from the controller, so a scanner cannot map a page's address to the presence
  advert's device; the 16 bytes a hop id would cost are the difference between a tick fitting one page and
  two. `InboundFrame.fromNodeId` is the author, the LoRa plane's rule (ADR 038); ADR 061's discipline holds
  trivially because nothing here feeds `BlePresenceTracker` or `deviceFor` (a page's address is
  non-connectable — writing it there would poison the L2CAP dial). Fragments reassemble by fragment id
  alone (two parts on two slots arrive from two addresses); the counter is seeded at random per process so
  two freshly booted senders do not collide, and a wrong assembly fails decode.
- **Two slots, a 12 s dwell, fewer parts first.** `SideCarousel` owns `SLOTS` = 2 advertising sets
  (`BleAdvertiser.sideParams`: extended, non-connectable, non-scannable, 1M/1M, `INTERVAL_MEDIUM` 250 ms,
  `TX_POWER_MEDIUM` — a page must not reach further than the sighting that gated it; ~1 % airtime each). A
  part dwells `DWELL_MS` = 12 s from first air — one screen-off scan interval (10.24 s) plus slack, two
  LOW_POWER intervals — then yields only if something is waiting, else keeps airing up to `LINGER_MS` = 30 s
  (over-airing is free, every receiver dedups; stopping and restarting a set per message is not). A frame's
  parts go up together when slots allow; a started frame's remaining parts go before any newcomer; then
  fewer-part frames first, FIFO within, so a one-page tick never waits behind a two-page profile. Unstarted
  frames go stale at 30 s (a typing cue at 10 s); a typing cue coalesces per sender; capacity 32, overflow
  sheds the oldest unstarted frame and never one on air. A slot the controller refuses
  (`TOO_MANY_ADVERTISERS` is a device-wide budget the system's own sets share) sits out 60 s; a page the
  controller calls too large halves `pageMax`; a feature refusal on either half turns the channel dark and
  the flag leaves the advert.
- **The gate is one BLE-local flags byte, not a `Protocol` bit.** `BleAdvertPayload` grew 23 → 24 bytes
  (Flags 3 + AD header 4 + 24 = 31, the last spare byte): byte 23 is `flags`, bit 0 `FLAG_SIDE_CHANNEL`,
  set at `readvertise()` only while `BleSideChannel.live` — a fact about *this controller*
  (`isLeExtendedAdvertisingSupported` read after the adapter is on, `leMaximumAdvertisingDataLength` large
  enough), which the `const` `LOCAL_CAPABILITIES` could never vouch for and which `Protocol.kt` reserves the
  bits from 0x100 up against. Additive: the older parser ignores trailing bytes (pinned) and the older scan
  filter matches any length. `SideCapableTracker` counts a flag while its sighting is inside 10 min **or**
  the peer is linked — presence prunes at 90 s and a settled clique's scan is floored for minutes, so a
  linked, flagged peer is routinely absent from it.
- **Routing.** `BluetoothMeshTransport.hasFastPlane = true`, so the composite stops sending the link copy
  itself. `fastFanout` = that link copy to every linked peer, unchanged (it is how a typing cue, which is
  never flooded, reaches a linked peer; the pre-existing double link-send of an originated floodable frame —
  `router.originate` flood plus the fast copy — is neither added nor removed here), **plus** an offer to the
  pages when the channel is live and a flagged peer is around. `fastSend` = the linked addressee over its
  link, never a page: nothing DM-form rides a broadcast carrier. Eligibility stays the caller's
  `shouldFastFanout`, so the set of frames on the page cannot drift from the NAN plane's.
- **Listening costs battery, and the policy says so.** The receive half is a second app-level scan
  (`setLegacy(false)`, `setPhy(PHY_LE_1M)` — `PHY_LE_ALL_SUPPORTED` would time-share the window with Coded —
  hardware-filtered on the side UUID). While a flagged peer is around the phone scans continuously at
  LOW_POWER (~10 % receiver duty) or BALANCED when interactive or charging, instead of the presence scan's
  floor, which is off for minutes. `SideScanPolicy` turns it Off with no flagged peer, during an L2CAP connect
  or the board dial (scanning starves connects — kept as a policy input so a trial can measure whether a
  10 % passive scan actually does), and on a low battery off the charger; A2DP contention caps it at
  LOW_POWER; never LOW_LATENCY. Starts are rationed to one per 30 s because Android's five-starts-per-30 s
  budget is per app and the presence scan shares it (error 6 is a retry, never fatal), and a scan older than
  25 min is restarted before the stack demotes it to opportunistic at 30 min. The scan stops within a tick
  of a connect starting (`beginConnect` wakes the loop).

**What it costs, what it does not cover, and the trap.** Latency on this channel is set by the listener's
scan interval, not the sender: a page dwells 12 s and a LOW_POWER window lands on it within ~5 s (screen-off
~10 s), so the channel is slower than an unblocked link and only wins when the link is blocked or absent.
Throughput is two pages per dwell; a burst of room ticks queues behind that and sheds at 30 s. Everything
on a page is readable by any BLE 5 sniffer within range — the same signed cleartext the NAN coordination
plane already sends, now on a carrier cheap hardware follows. Deliberately out of scope: DM-form frames
and typing toward a DM (one recipient, no business on a broadcast; issue scope); 2M secondary PHY (3–5 dB
less range than the presence advert, so a peer could be sighted with the flag and never hear a page — a
trial knob); hold-until-echoed dwell; more slots. The trap: `hasFastPlane` is read once when the composite
is built and is now unconditionally true for Bluetooth, so anything that later gates on it (nothing does
today) must remember the side channel itself may be dark — the transport handles that inside `fastFanout`
and `fastSend`, never the composite. Owed before the release flag flips: the device trial —
`isLeExtendedAdvertisingSupported` / `leMaximumAdvertisingDataLength` / `isOffloadedFilteringSupported` on
each lab phone; catch rate per page at each scan tier (tune `DWELL_MS`); the #13 case (a GIF A→B over BLE
only while B posts to the room, B's post arriving as `ble-side heard` before the transfer's `file …` line
completes, and the CoC throughput delta); a sighted-but-unlinked third phone hearing a post from the page
alone; connect success with the side scan on vs off and no `scan failed: 6` across a churny bring-up; one
night's battery against the dark build. Grep `ble-side` in logcat; counters `bleSide*` on `…debug.STATE`.

**Device trial (2026-09-17, three lab phones — a Pixel 9 Pro XL on API 37, a Pixel 3 on API 31, a Moto G Power
on an API-34 GSI — plus two Pixels on the previous build as the "sees nothing" control, all five linked).**
Every probed controller reports extended advertising with a 1650-byte data maximum, so `pageMax` = 236 on all
three; both slots came up on each (no `TOO_MANY_ADVERTISERS`), no `DATA_TOO_LARGE`, no scan-budget refusal
(`scan failed: 6`) across the session, `bleSideTooBig` = 0. The flags byte gates as designed: each phone
flipped its scan tier within a second of sighting a flagged peer, and the older builds kept linking to the
flagged ones. Multi-part frames reassemble across the two slots' distinct random addresses (`bleSideReassembled`
19 / 8 on the Pixels); the ids-not-addresses decision held. Numbers, receivers screen-on (BALANCED): 20/20 and
12/12 pages caught, steady-state catch latency 1.5–5 s (p50 ~5 s, min 0.2 s); receiver screen-off (LOW_POWER,
the stack's 10.24 s interval): 11/12 caught, p90 15 s — the 12 s dwell is one window there, so a miss per dozen
is the price of that dwell, and the 30 s linger covers it at a chat cadence. A burst of one post every 6 s
saturates two slots exactly (one part per 6 s), so latency climbed to 25 s at the start of that run while three
re-fanned 3-part profiles finished first, then settled to 3 s — the throughput ceiling the design accepts, and
why re-fanned copies show as `STALE` drops on every receiver in a five-phone mesh (harmless: the flood carried
them). The #13 case: while the Moto streamed a 700 KB attachment out over its one L2CAP link to the Pixel 3 and
posted ten room messages, every one of them reached the Pixel 9 off a page 0.3 s before the router delivered
it (its link copy lagged 2–18 s), while the Pixel 3 — the phone *receiving* the stream — caught only 4/10
pages (its scanner pre-empted by its own connection events) and got the rest over the link at 6–19 s: the
channel helps the bystander most and the transfer's own peer least, as the radio review predicted. Airing pages
does not slow the sender's own links: an A/B of the same ten posts with the Moto on the dark build and then
the lit build gave link delivery of 1.5–2.0 s vs 1.6–2.1 s to the Pixel 9 and 2.3–2.9 vs 2.4–2.7 s to the
Pixel 3. One trap met on the way, not of this change: for a few minutes after `installDebug` over a running
mesh the Moto's fresh links reported up on both ends while its frames crossed nothing — measure only after a
probe post round-trips. **Still open before the release flag flips:** a night's battery on a settled clique
against the dark build, and a sighted-but-unlinked peer (this lab links everyone).


## Amendment 2026-10-02 — pages go out at the presence advert's power, now HIGH

ADR 2026-10.ryak raised the presence advert from `TX_POWER_MEDIUM` to `TX_POWER_HIGH`, so `sideParams()` follows it.
The rule above was that a page must not reach further than the sighting that gated it. At equal power it reaches as
far and no further. Left at MEDIUM, a peer sighted 8 dB further out than before would be flagged into the audience
and never hear a page. The airtime is unchanged; each page costs more transmit current while it airs, about
50–150 µA averaged while a slot is up. `BleAdvertiserTest.everyDiscoverySetAdvertisesAtFullPower` pins the pages to
the presence advert's power.
