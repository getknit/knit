---
id: "2026-11.89gg"
slug: an-android-phone-reads-a-blinded-gatt-payload-as-a-blinded-sighting
title: "An Android phone reads a blinded GATT payload as a blinded sighting"
date: 2026-11-12
topics: [ble, mesh, privacy]
---

# ADR 2026-11.89gg — An Android phone reads a blinded GATT payload as a blinded sighting

Status: Proposed (2026-11-12). Companion change A11 of knit-ios ADR 2026-11.prsp, which leads it: the paired change
`blind-gatt-reads` (knit-ios #42). The rules are knit-ios law already (`GattPayloads.swift`, `GattSweep.swift`,
`DoorbellLookup.swift`), and this port follows them. It amends ADR 2026-09.kwq2 (the payload's lifetime and its read
budget) and ADR 2026-11.xp6d (the reader, obligation 8's handles, and the "iPhone" bullet of `docs/NEXT_WIRE_BREAK.md`
item 13). No wire byte moves.

**Context:** since knit-ios ADR 2026-11.prsp, a debug iPhone with its switch on serves a blinded GATT payload: the
24-byte advert with `FLAG_BLINDED` and `FLAG_DIALS_GATT_PEERS` set, bytes 1–16 a `BlindTag`, the cue zero. It serves it
from a `0xFE30` service of its own (P), beside a second `0xFE30` service that holds the doorbell alone (D), added first
and never rotated; each rotation adds a new P before it removes the old one. A clear iPhone, every release build
included, serves one combined service.

Until this change every Android read that payload as a stranger (`BleGattPayloadReader`'s `stranger (blinded)`).
It sighted nothing and looked up no key, so even a contact holding the iPhone's presence key never recognised or
dialed it, and the address was read again every ten minutes, charging the read budget (#154) with nothing to refund it.
That is why the iPhone's blinding stays off by default even in debug builds.

**Decision:**

- **Every `0xFE30` service is searched.** The payload reader and the doorbell lookup filter `gatt.services` on the
  service UUID and take the characteristic from whichever holds it, never `getService(UUID)`, which returns the first.
  The reader still reads on a fresh connection of its own, closed after the read, so it never reads through a handle
  found before a rotation.
- **A blinded read is a blinded sighting.** A value with `FLAG_BLINDED` and a valid tag prefix is
  `GattPayloads.Outcome.Read`. A malformed one, the flag without `BlindTag.hasPrefix`, stays a stranger, as
  `BleAdvertPayload.parse` drops a malformed advert, and is now counted under the existing `BleBlindDrop.GATT_BLINDED`
  key, repurposed. The read's sighting goes through the blinded advert's path (`blindQueue`, `readBlind`,
  `AdvertAliases.resolve`, `BlindSightings.attribute`): our own tag, a contact's key, a pending announced tag, a claim, a
  hint that every hint agrees on, and otherwise a handle. It is stamped with the advert that started the read, not
  the read's end, since a read takes up to 12 s and the stamp feeds `LinkEventStamps.late`. A later advert at that
  address takes the cached payload through the same path; a cached blinded value is never sighted as though its tag
  were a node id. The sighting carries `viaGatt`, so `gattSighted` holds the resolved id or the handle, and a re-key
  that carries a handle's history to an id carries its GATT mark too.
- **Who dials is unchanged.** `AdvertOrder.iDial` decides on forms, with no rule for a GATT source (knit-ios ADR
  2026-11.prsp weighed one and declined it). Between two blinding phones the larger tag dials, so the role turns at every
  slot. A clear phone leaves the dial to the blinding iPhone, except that the 180 s lonely dial
  (`LONELY_AGGRESSIVE_WINDOW_MS`, ADR 2026-09.hj4a) now reaches a GATT-read blinded iPhone, which is a form `iDial`
  leaves to the other side.
- **The payload's lifetime, four rules.** kwq2's three, two of them now told how to read a tag, and the prsp edge:
  1. **A failed dial** before the channel opens forgets the payload, on a tag dial too. A cold-launched iPhone that comes
     back on PSM 193 while 192 is still held is the case (knit-ios `linux-peer.md`).
  2. **A HELLO** at the address forgets a blinded payload unless the payload's tag is the HELLO's `t` or the tag that
     node has announced (its pending `TAG_NEXT`, by node id over any link: `AdvertAliases.announced`). A HELLO with no
     `t` forgets it. A blinded payload is never compared with an id; a clear one still is. It runs at every HELLO the
     responder parses, before the malformed-offer, claim and admission refusals (the §7 plain refusal runs earlier and
     skips it; an iPhone's plain HELLO carries no seal bit, so it never meets that row), at a clear dial's reply, and on a
     tag dial once its proof verified, before `AdvertAliases.onDialed`; an unproven tag-dial reply forgets nothing. `AdvertAliases` is the worker's, so a blinded payload's check hops to the
     worker and re-reads the payload there; a clear one's stays where it was, before the verdict.
  3. **Quiet**, unchanged: 60 000 ms of scanning unheard with no link up.
  4. **The slot edge.** A blinded read stamps a deadline on the wall clock: the first slot edge after the read
     (`(slotOf(wall) + 1) × BlindTag.SLOT_MS`), plus 35 000 ms (`AdvertRotation.MIN_LEAD_MS`, an announced rotation's
     least lead, and 5 000 ms of writer clock slack), plus a jitter drawn per read in [0, 30 000) ms from a seed the
     transport draws at random when it starts. The payload is forgotten once the wall clock reaches that deadline, or is
     set back below the read's own time, unless a link holds the address, a dial is in flight to it or it is being read.
     It is checked at every scan window, whatever its PHY, and at each advert that would be sighted from the cached
     payload, so a stale tag is never sighted past its deadline and the re-read starts on that advert. So each address
     costs at most one read per slot.

  The no-reply forget (`GattPayloads.forgetUnsealed`, ADR 2026-11.8mhs) takes clear payloads only: a blinded one rotates
  and is forgotten every slot anyway, and the iPhone seals nothing yet (knit-ios #51).
- **The refund.** A blinded charge holds the tag it read, flagged as a tag so it is never compared with an id. It is
  refunded once, when a proven link registers at that address and either the tag the link was dialed by or the proven
  HELLO's `t` equals it. A clear charge keeps its id match. A forger that proves a fresh id and echoes the tag it served
  gets the refund, at the cost of a whole link per read, which is no more than a clear charge allows.
- **The doorbell.**
  - `doorbellTrusted`'s `sightedViaGatt` holds for any GATT-read blinded sighting: one held under a handle passes
    through `!inPresence` as before, and one resolved by key, claim or hint now keeps its GATT mark.
  - A lookup that finds no doorbell within `DoorbellPolicy.DOORBELL_SETTLE_MS` (10 000 ms) of the L2CAP link's open,
    on `elapsedRealtime`, is retried, never `absent`: it drops its client, so the next lookup registers a fresh one and
    discovers again (no Service Changed reaches the phone, and the stack is believed to keep a server's database while
    any client on the address holds it, clearing it with the last one; that is from memory of AOSP's `bta_gattc`, not a
    read of it, and the device trial's settle case checks it: if it is wrong, the lookup at the bound finds the same
    database and the link ends `absent`, as before this change), counts nothing toward `MAX_FAILED_LOOKUPS` and waits out no `LOOKUP_RETRY_MS`. One more lookup
    is due at the bound, so a link whose first lookup came too early still asks for BALANCED (#102) without waiting for
    a ring. Past the bound `absent` stands for the link's life. A failed discovery is retried as before. Swift restarts
    the settle at each `didModifyServices`; here the bound counts from the link's open alone, and nothing waits on
    `onServiceChanged`.
  - The held-link row and `LinkSilence` stay inert toward a link whose HELLO carries `CAP_DOORBELL`, and `dialHeld`
    keeps working toward it, as in the 2026-11-06 claim burst.
- **Why Android never subscribes to an iPhone's Service Changed.** Nothing in the app asks: no
  `setCharacteristicNotification` or descriptor write on `0x2A05` exists outside `meshtastic/`. The stack asks only after
  a pairing: AOSP writes that CCCD from `GATT_ConfigServiceChangeCCC`, whose caller found is `bta_dm_ble_smp_cback` on a
  successful `BTM_LE_COMPLT_EVT` (`system/bta/dm/bta_dm_sec.cc`, read at `main` on 2026-11-12; `btm_sec.cc` was not
  read whole). Knit's links are insecure L2CAP and its GATT clients never bond, so no SMP pairing completes, no
  indication is enabled, and `BluetoothGattCallback.onServiceChanged` never fires toward an iPhone. That matches the
  Pixel 9's snoop of 2026-11-12: no ATT Write Request across 23 connections (knit-ios `linux-peer.md:2263-2271`).
- **Log lines** (at info, built in `BtLines`, never a whole tag nor a tag beside an id; the trial greps them):
  - `bt gatt read <address> blinded (psm <n>)` at a blinded read's end, which `interop.py`'s `GATT_READ_END` books as a
    charge with no node (its A11 `(blinded) \(` branch) and its `bt gatt read (\S+) → <id>` never matches;
  - `bt gatt blinded <address> → <id|~handle> (<how>)` on the worker, once per read, `<how>` one of `key`, `claim`,
    `hint`, `handle`, `own`, `replayed` (a key's answer the proof voided), `malformed`, or `clear`, which a blinded
    form never reaches;
  - `bt gatt forget <address> (edge)` for rule 4;
  - `bt gatt confirmed <address> (tag) → <id>` for a blinded refund, which `GATT_CONFIRMED` (`^bt gatt confirmed (\S+)
    → `) does not match, so the harness's clear-charge model never deletes a charge on it; its `GATT_CONFIRMED_TAG`
    refunds the latest blinded charge at that address;
  - `bt doorbell settling <id> (<n>ms since link up)` for a lookup inside the settle bound.

  The clear `bt gatt read <address> → <id>`, `failed (` and `stranger (` lines, `bt gatt forget`, `bt doorbell found`,
  `bt doorbell absent`, `bt ring` and `bt state`'s `gattBudget=<used>/<12>` keep their shapes. A malformed blinded
  value reads `stranger (malformed-blinded)`.

**What older builds do.** v2.11.0 and v2.11.1 know no `FLAG_BLINDED` (their flags stop at `FLAG_AWARE`) and read the
payload from `getService(0xFE30)`'s first service. Against the split layout that is D, whose only characteristic is the
doorbell, so the read ends `stranger (characteristic)`, charged, every ten minutes; should P ever sort first they would
parse the tag as a clear payload whose id is the tag, sight a `zzzz…` node no HELLO can match, and forget and re-read it
at each dial (rule 2). Their doorbell lookup takes the first service too, so it finds D's doorbell, and would miss it
behind a P that sorted first. That is the cost the contract owes them, and why **no release iPhone serves a blinded
payload**: turning it on in release is `docs/NEXT_WIRE_BREAK.md` item 13's decision, which this change only amends.

**Consequences / limits:**

- **No Service Changed reaches an iPhone's Android client** (above). A doorbell handle that moved would go unnoticed
  for the link's life; the iPhone's D never rotates, and a payload is read only on a fresh connection, which is why this
  holds. Should the reader ever read over a link's ACL, it reads the Database Hash (`0x2B2A`) first.
- **A clear Android does not promptly redial a suspended blinding iPhone** (knit/knit-next#317). By the dial rule it
  dials a blinded form only after 180 s alone, so when a link drops while the iPhone is suspended and still holds
  another, nobody redials until the iPhone wakes or that window passes. The Pixel 7 linked 2 min 49 s after a forced
  relaunch on 2026-11-12, after a failed iPhone dial; that cause is not verified. Changing it is a dial rule, which goes
  back to the maintainer.
- **A suspended iPhone's tag can outlive the edge** (#317), so a rule-4 re-read then returns the same tag, charged and
  refunded as any other.
- **Each unlinked blinding iPhone costs a read per slot and per address rotation,** charged against the 12-read budget
  and refunded once a link proves. A linked address is spared rule 4.
- **The iPhone seals its links** since knit-ios's link-seal port (knit-ios #51, its `main` at `7234ef3`): its HELLO
  carries `CAP_LINK_SEAL`, and its blinded GATT payload carries `FLAG_LINK_SEAL` beside `FLAG_BLINDED`. The flag rides
  the read and the cached payload into the sighting's `HeardDevice.linkSeal`, so the tag dial opens sealed
  (`LinkSealPolicy.dialsSealed`, then `LinkHandshake.dialTagSealed`) with no code of its own here. An older iPhone build
  without the bit links plain: `LinkHandshake.refusesPlain` admits its plain HELLO. Once Android reads the payload, ADR
  2026-11.8mhs's worry about an unread seal flag is met for A11 readers.

**What keeps it true:** `GattPayloadsTest` mirrors knit-ios's `GattPayloadsTests` case for case (rule 2 on tags and
the clear id control, rule 4's constants and its edge+34 999 / edge+65 000 boundary, a clock set back, per-read jitter,
the linked exemption, a forgotten payload taking its edge) and adds Android's own: `heard` past the edge, spared or not;
the blinded refund matched, unmatched and never compared with an id; rule 1's reasons on a tag dial; `forgetUnsealed`
leaving a blinded payload alone. `BleGattPayloadReaderTest` reads a blinded payload, the payload from the second of two
`0xFE30` services and none from a foreign service, and keeps a malformed one a stranger of its own phase.
`DoorbellPolicyTest` covers the lookup across D and P, the combined service and the settle bound, after Swift's
`DoorbellLookupTests`. `BlindGattReadTest` checks that no new line carries a tag and that the harness regexes read them
as intended, and `AdvertAliases.announced`. `LinkSealPolicyTest` opens a tag dial sealed from a blinded GATT payload
that carries the seal flag, and plain without it; `SealedLinkHandshakeTest` admits an older iPhone's plain, proven HELLO
with no seal bit. `IosEmittedVectorTest` opens the iPhone's padded ctl 11 (`carolPresenceKeysToBob`) and rebuilds its
plaintext with `PresenceKeyFrame.content`. The blinding lab scenarios (`PresenceKeyLabTest`, `PresenceKeyForkLabTest`, `PresenceKeyFeedLabTest`) still
pass; the lab has no radio layer, so the GATT path is covered by the unit tests alone. The re-key's GATT mark and rule
1's call site are wiring with no seam, read in review. The device trial is knit-ios's (the paired change's step 5): the Pixel 9, a contact,
resolves the iPhone by key over GATT; the Pixel 3, which the iPhone holds but does not hold the iPhone, holds it under a
handle until a link proves; both link in both dial orders across two slot edges with no redial while linked and no legacy
refusal; each blinded read is refunded once its link proves; rule 4 forgets each address once per slot, 35 to 65 s past
the edge; and every `bt ring` after a rotation is matched by `ble rung by` on the iPhone, with no `bt doorbell absent`.
