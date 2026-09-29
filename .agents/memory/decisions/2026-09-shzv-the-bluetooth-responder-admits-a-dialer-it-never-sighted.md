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
