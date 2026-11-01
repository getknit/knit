---
id: "2026-09.shzv"
slug: the-bluetooth-responder-admits-a-dialer-it-never-sighted
title: "The Bluetooth responder admits a dialer it never sighted"
date: 2026-09-25
topics: [ble, transport, interop]
---

# ADR 2026-09.shzv — The Bluetooth responder admits a dialer it never sighted

Status: Accepted (2026-09-25). Companion change A1 for the iOS port (`knit-ios`, ADR 2026-09.6es2 there).
JVM-tested, and trialled on the Pixel 3 against the port's Linux peer in its iPhone-like profile (results at
the end). The rule it follows is that the Android mesh, which works, keeps every behaviour it has: a peer
the scan sees is judged, scored and torn down exactly as before, and only a peer the scan never sees takes the
new paths.

**What was observed.** The L2CAP responder closed every dialer whose node id did not sort above its own
(`superviseAccepted`: `localNodeId >= clientNodeId`), on the assumption that the larger id always dials. That
holds between two Android phones, which see each other's `0xFE30` service data. It does not hold for an iPhone:
iOS cannot advertise service data, `BleScanner` filters on it, so an Android phone never sights an iPhone and
never dials it. The iPhone dials instead, and whenever its id sorts lower it was refused after its HELLO, with no
log line. Roughly half of all iPhone–Android pairs could never link. The port's Linux peer reproduces it: in its
`ios` profile it advertises only the UUID, and on Knit 2.7 the Pixel 3 closed each of its dials from below the
Pixel's id right after the HELLO (5 dials in 5 minutes, 2 of which connected, no link).

Two more faults sat behind it. Links are keyed by node id, so when `registerLink` replaced a stale link and
closed it, the old link's `onLinkDown` ran `teardownLink(nodeId)` and removed the **replacement**; the eviction
path had the same shape, closing whatever link held the id when it ran rather than the one it scored. And a link
whose peer is not in presence scored −127 dBm for eviction, so an inbound iPhone, never sighted, was always the
first link shed.

**What changed.** `BleAdmissionPolicy.decide` judges each dialer from our id, its id, whether presence holds it
right now, and the age of the link we already hold to it, if any:

- **A dialer we see is judged as before.** It is refused if it sorts below us, because our own dial to it is the
  one to keep (that is how a cross-dial settles), and refused if we already hold a link to it. An Android dialer
  only dials a peer it holds no link to, so the second case means our held link is stale; it is still refused,
  because the HELLO is unauthenticated and this refusal is what keeps a device that claims a sighted peer's id
  from cutting that peer's link. The stale link ends when a write to it fails, as it always did.
- **A dialer we never sighted is admitted in either order.** Nothing on this side will dial it.
- **A second link from an unsighted dialer replaces the first once the held one is 30 s old**
  (`REPLACE_MIN_HOLD_MS`). Its old link died on its side before ours read the end, and it has no other way back.
  A younger held link is kept and the dial refused; the dialer's next attempt after the floor gets in.

Each link gets its own `LinkEvents` callbacks, and `teardownLink(…, only = link)` releases the slot only while
that link still holds it (`ConcurrentHashMap.remove(key, value)`). The link's own end and an eviction both go
through it; `stop` and adapter-off still clear by id. A replaced link's end no longer bumps the peer's backoff.

A link the scan has not once sighted since it came up (the `neverSighted` set, dropped on the peer's first
sighting) scores the promotion floor, −90 dBm (`BleAdmissionPolicy.linkRssi`), instead of −127. A link whose
peer was sighted during it and has since left presence still scores −127 and is still the first shed, so a
settled Android mesh evicts exactly as it did. Refusals are logged at debug (`bt refused client …`, with the
held link's age), and an accept says its verdict and whether the dialer was sighted.

The alternative a reader reaches for first is making Android find the iPhone: read the GATT payload from a
UUID-only sighting and dial by the usual rule. That is companion change A3, and it needs a connect per unknown
device plus a check of what iOS background adverts carry. It does not help the case A1 fixes today: an iPhone
that can see us and is already dialing.

**What it costs.** This is not a wire change. Between two Android phones the only new behaviour is at the edge
of range, where one sees the other but not the reverse: a larger dialer we cannot see was admitted before and
still is, and its link now scores −90 until our scan first sights it, and may be replaced by its own redial
where before that redial was refused until the stale link failed a write.

The HELLO is unauthenticated, so a device that claims the node id of a peer we are not sighting can now take a
slot under that id (before, only an id above ours), which lights the `Direct` presence tier for it, and can
replace that peer's held link, at most once per 30 s. Frames stay signed, so the most it can do is cut the link
and show a contact as nearby. The iOS port's dialer must treat a refusal as an ordinary failed dial and retry.

A replaced link is the same neighbour to `MeshManager` (the transport's `neighbors` set does not change), so the
link-up push of profile, avatar, digest and key requests does not run again for it. The new link starts from the
dialer's own DIGEST and the 60 s tick. A dialer that kept our key loses nothing. One that did not gets our frames
by back-fill ahead of our profile: it parks 16, drops the rest and marks them all seen, so its custody converges
only after its SeenSet window. A transport event for a replaced link, so the manager can re-push, is the
follow-up if an iPhone turns out to lose its keys across a redial.

The mesh lab has no radio layer, so no lab scenario covers this; `BleAdmissionPolicyTest` pins the table, the
floor and both scores, and the teardown fix is seen only on hardware.

**Trial, 2026-09-25**, the Pixel 3 against the port's `knit-peer` (`scripts/interop.py` in `knit-ios`), the peer
advertising the UUID alone, so the Pixel never sighted it. It ran on the first revision of this change, which
had no replace floor, scored every absent peer at −90, and still evicted by id; the rework is JVM-tested only,
and a re-run is owed.

- **Below the Pixel's id** (refused on 2.7): `bt accepted client … (Admit, sighted=false)`, linked in 22 to 29 s,
  and the port's whole checklist passed twice, 14 of 14 and 13 of 13 (names both ways, the room both ways
  within 5 s, DMs both ways with both ticks, custody converged, back-fill after a restart).
- **Above the Pixel's id:** 13 of 13.
- **Stale link:** the peer linked and was frozen (its channel stays open, so the Pixel's link goes quiet), and a
  second process with the same identity dialed. The Pixel logged `Replace, sighted=false`, logged no link-down
  for the replaced link, and the new link carried posts both ways in 1.5 to 4.9 s and held to the end of the
  run. The second process held no keys, so custody then waited out its SeenSet window: 77 of the Pixel's frames
  arrived before the Pixel's profile and were dropped. Under the floor, a second dial within 30 s of the first
  link's start is refused once and admitted on its next try.

**Amended 2026-09-29 (A3, ADR 2026-09.kwq2): A1 stays whole.** A foreground iPhone is now *sighted* through its
GATT payload, so it is judged by the old rule: the larger id dials, and a sighted dialer below us is refused. Every
branch of A1 still carries a real case:

- **The unsighted admit** is the fallback that makes a clear `FLAG_DIALS_GATT_PEERS` safe. The flag is clear in a
  release build until the trial, on a phone whose adapter cannot read, toward an iPhone build that ignores the flag,
  and while the iPhone holds a sighting from before the flag. Reverting the admit would re-strand half the pairs in
  each of those cases.
- **The replace path and the −90 dBm score** still serve a backgrounded iPhone. Its UUID sits in Apple's overflow
  area, which this phone cannot read, so its links stay never-sighted and its redial after its side died still
  needs the replace.
- **`teardownLink(only =)` and the per-link `LinkEvents`** fix general replace and evict races, not iPhone ones.

One behaviour changes. A *sighted* iPhone's redial over a held link is now refused, as an Android peer's is, until
the held link fails. #102's 5 s supervision timeout makes that wait longer than the 720 ms one did, and the
iPhone's 10 s dial backoff covers it. The comments that said nothing on this side ever dials an iPhone are updated
(`BleAdmissionPolicy`, `BluetoothMeshTransport.neverSighted`, `BleScanner`).

**Amended 2026-10-10 (AND-3, a paired change with knit-ios): a backgrounded iPhone keeps its link and gets back
in.** A foreground iPhone is sighted through its GATT payload (kwq2), so its link scores its smoothed RSSI and the
`neverSighted` floor never applies to it. Once Knit leaves the front, its UUID moves to Apple's overflow area, this
phone stops hearing it, and 90 s later it leaves presence. Its link then scored −127 and was the first shed at the
6-link budget, and for those 90 s its re-dial was refused whenever our id sorted larger: it was still "sighted".

`CAP_DOORBELL` (`0x1000`) in the link's HELLO is the marker for a peer this phone loses sight of in the background.
Android never claims it; every iPhone that serves the doorbell does, and so does knit-peer's `ios` profile. It is
read from `FramedLink.peer.capabilities`, which `registerLink` builds from the HELLO on both sides (the dialer's on
an accepted link, the reply on a dialed one). Two rules:

- **The score.** `BleAdmissionPolicy.linkRssi(…, doorbell = true)` scores `UNSIGHTED_LINK_RSSI` (−90) while the
  peer is out of presence, never −127; in presence it scores the smoothed RSSI as before. Such a link is shed by a
  candidate 8 dB above −90, as any floor-scored link is, and kept against a weaker one. Every other link keeps the
  old rule.
- **Admission.** A doorbell dialer counts as sighted, for the below-us refusal and the linked refusal, only if
  presence heard it within `DOORBELL_HEARD_MS` (12 000 ms, equal to `PromotionConfig.dwellThresholdMs`; read from
  `Snapshot.lastSeenAgoMs`). Otherwise it takes the unsighted rows: admitted in either order, and replacing a held
  link once that link is 30 s old. An iPhone heard that recently is in front, and this phone can dial it; one it
  can no longer hear may be in the background, where its own dial may be its only way back. `CodedPhyPolicy.sightedForAdmission`
  still feeds the `sighted` input.

This phone does not need to hear a backgrounded iPhone to dial it back. In the 2026-10-10 trial (iOS's
`android-keeps-iphone`, run 1, Pixel 8 at `b7c13e57`), the Pixel re-dialed the backgrounded iPhone itself 8.3 s
after shedding its link, from the presence entry still held in the 90 s linger, about 30 s after it last heard it,
once the eviction backoff ran out; the lab iPhone keeps its address, so the stale dial landed (doorbell found,
`timeout=5000ms`). The admission rule matters only when this phone's own dial cannot land: the iPhone's address
rotated, or the link faded on one side only.

The parallel iOS change (`iphone-dials-flagged-android`) has a foreground iPhone dial a flagged larger Android after a
15 s head start. That dial is refused only when this phone heard the iPhone within 12 s, which between
duty-cycled scan windows it often has not, so the dial is often admitted (below).

The HELLO's bit alone is not trusted: the HELLO is unauthenticated, and a device claiming a sighted Android
peer's id with `0x1000` set would otherwise take the unsighted rows whenever that peer was 12 s unheard (most
of a screen-off phone's scan cycle) and replace its link every 30 s. So the bit counts for admission only when
this phone's own evidence agrees on both counts (`BleAdmissionPolicy.doorbellTrusted`): a link held to that id
must have carried it in its own HELLO, and a peer presence holds must have been last sighted through a GATT
payload (the iPhone path, kwq2; `gattSighted`, cleared by any service-data sighting). A peer presence holds
through service data never takes the doorbell rows, even under a link that once got in claiming the bit. A
genuine iPhone, or knit-peer's `ios` profile, meets both every time. The score needs no such check: it reads
the held link's own HELLO, and only a link already admitted has one.

The score is not monotonic: a doorbell link in presence scores its smoothed RSSI, which can sit below the −90 it
would score once out of presence, so at the budget a far foreground iPhone is shed before a backgrounded one. That is
rare and was left as the contract states it.

The 12 s is wall-clock age of the last sighting, not listening time. The presence scan is duty-cycled (12 s windows
every 30 s while interactive, longer gaps idle or settled), so a foreground iPhone is often more than 12 s since last
heard between windows, and its dial is then admitted: it ends up the central, and #102's BALANCED ask still runs.
The error only ever leans toward linking. The HELLO is unauthenticated, so a device claiming the id of an iPhone this phone
holds a doorbell link to, or sighted through its GATT payload, can take the unsighted rows between scan windows, which is the exposure the original
decision accepted for an unsighted peer: a slot under that id, a replace at most once per 30 s, frames still signed.

Considered and not taken: measuring the 12 s on 1M listening time (`BlePresenceTracker`'s `oneMClock`, as
`codedLagMs` is), so the rule reads the iPhone's state rather than our duty cycle; and counting a dialer we hold a
dial in flight to as sighted, to curb crossed dials. Both would cut the cross-dial churn between scan windows, but
the error they fix only leans toward linking, and #102 still brings an iPhone-dialed link to a 5 s timeout.

Oracle: `bt accepted client <id> (<verdict>, sighted=<b> codedOnly=<b> doorbell=true heardAgoMs=<ms|null>
inPresence=<b>)` and `bt refused client <id> (sighted=<b> codedOnly=<b> heldAgeMs=<ms|null> atCap=<b> doorbell=true
heardAgoMs=<ms|null> inPresence=<b>)`; the `doorbell=` suffix appears only when the HELLO claims the bit, so Android
lines are unchanged, and it ends ` doorbellTrusted=false` when this phone's evidence rejected the claim. An eviction logs `bt evicting <id> (score=<dBm> doorbell=<b>)` between the `promote=… evict=…` line and
`bt link down: <id> (evicted)`, and a debug build's `bt state` line ends `linkScores=<id>:<dBm>,…`. Tests:
`BleAdmissionPolicyTest`'s doorbell rows and `PromotionPolicyTest.aDoorbellLinkOutOfPresenceIsScoredTheFloorNotShedFirst`.

A correction to the 2026-09-29 amendment: `FLAG_DIALS_GATT_PEERS` is no longer clear in a release build. It has been
set in release since `713b29d6` (2026-10-08). The unsighted admit's other cases (an adapter that cannot read, an
iPhone build that ignores the flag, a sighting from before it) still stand, and so does the backgrounded iPhone above.

## Amendment 2026-11-01 — the claim risk now holds only for unproven peers

The cost accepted above (a device claiming the id of a peer we are not sighting takes a slot under it and can replace
its held link every 30 s) now holds only for a peer that cannot prove itself to us: one on a build before link proof
(v2.10.1 and earlier), or one we hold no profile for and that has not proven itself this run. A HELLO claiming a
proof-capable id without a proof is refused before any row here runs, a proven held link is replaced only by a dialer
that proves the same id (under these rows), and a dialer that proves an id displaces an unproven link holding it
whatever its age or sighting (ADR 2026-11.264w). The table above still decides between two unproven ends exactly as
written. The "follow-up if an iPhone loses its keys across a redial" paragraph's gap is closed for a proof displacing
an unproven link: `LinkClaims.onDisplaced` re-runs the peer's link-up work.
