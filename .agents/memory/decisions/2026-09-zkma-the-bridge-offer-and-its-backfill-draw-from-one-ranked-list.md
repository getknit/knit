---
id: "2026-09.zkma"
slug: the-bridge-offer-and-its-backfill-draw-from-one-ranked-list
title: "The bridge offer and its backfill draw from one ranked list"
date: 2026-09-05
topics: [lora, bridge, custody]
---

# ADR 2026-09.zkma — The bridge offer and its backfill draw from one ranked list

Status: Accepted (2026-09-05)

## What was observed

A Nearby-room post sent from the Pixel 9 while its board was out of LoRa range of the Pixel 7's, and still
undelivered eleven minutes after the two came back into range — it landed only when the phones re-linked
over BLE/NAN. The same shape as the failure ADR 2026-09.y8pu fixed, and that fix is intact; this is a
different cause underneath it, and the logs say so plainly. Over three hours the two gateways bridged four
frames each, and every one of them was a `profile`:

```
19:11:44 lora bridge served=4/4 to 9a9cdb65560442ab      (P9 → P7)
19:41:14 lora bridge served=4/4 to e2c7805577eb1016      (P7 → P9)
```

The same four ids both ways, hours apart. Three of the four P7 served were the **recipient's own** profile
publishes, and P9 had already received the fourth at 17:38 — it was re-served twice more, at 19:56 and
19:57. Nothing else ever crossed. `loraBridged` stopped at 4 on each phone.

An OFFER holds ~48 id prefixes in one packet, so it is a **window** onto custody, and the window was the
newest 48 frames by `sentAt`. A `profile`'s `sentAt` is its **publish stamp** — the lab's were two hours to
a day old against a store of that afternoon's chat — so in any pocket with 48 recent frames no profile was
ever named. The far gateway therefore read every profile as missing, for ever; and since
`LoraFramePolicy.backfillRank` puts the key bootstrap first, all four slots an offer buys went to profiles
both pockets already held, round after round. There were never any slots left for a room post. The room
post was not skipped, refused or dropped: it was ranked fifth in a list of four.

That is also why it looked intermittent. A quiet pocket holds fewer than 48 carriable frames, the offer
names everything including the profiles, and the bridge behaves perfectly.

## What changed

`LoraFramePolicy.bridgeOrder` is now the one candidate list, and `MeshManager.bridgeCandidates` builds both
halves of the digest exchange from it: the OFFER names its head, the backfill serves the head of what the
OFFER did not name. The invariant is that **the frames the offer cannot reach are exactly the ones the
serve wants least** — a frame ranked above the offer's cut is re-sent on every round for as long as both
sides hold it.

The list also drops **superseded profiles**: only an author's newest publish is offered or served. Only
that one carries the key and prekey a far pocket needs, and custody really did hold three of one author's.
The rule is symmetric, so two nodes applying it agree about what is missing.

The obvious alternative — grow the offer, or send several — buys nothing: custody is 1000 rows and one
LongFast packet is ~2 s of air, so the window is always far smaller than the store and the disagreement
survives at any size. Sorting the offer by *arrival* time instead of `sentAt` would fix the profile case
specifically (a just-received old profile would be named) but leaves the general defect standing, and
`CarriedFrame` carries no arrival stamp to sort on. Refusing to serve a frame back to its own author would
have recovered three of the four wasted slots and none of the reasoning; with the ranked list an author's
own frames are named in its own offer, so it falls out for free.

## What it costs

Profiles are now first in the offer as well as first in the serve, so a node that knows a great many peers
spends its prefix slots on them and leaves recent chat unnamed — the same disagreement, one rank down. The
per-author collapse bounds it at one prefix per known peer, which is comfortably inside 48 for any pocket
this plane is built for; a mesh large enough to break it needs a bigger offer, not a different order.

This does not touch custody, the wire, or what is stored: it ranks what the ~1 kbps plane offers to carry.

Kept true by `LoraBridgeTest.aRoomPostCrossesAPocketWhoseOfferIsFullOfRecentChat` (a pocket whose offer is
saturated with recent chat, four stale profiles both sides hold, one room post that must still cross) and
`LoraBridgeTest.onlyAnAuthorsNewestProfileIsWorthASlot`. Both fail against the previous split ordering. The
test rig's `FakeCustody` draws from `bridgeOrder` for the same reason the real one does — a fake that
ordered its offer differently from its serve would hide exactly this.

**The trap:** the two halves live in different files (`LoraFramePolicy` ranks, `MeshManager` reads custody),
and either can be "tidied" into its own sort without the other noticing. There is no runtime symptom to
catch it — the plane keeps working, just never on the frame anyone is waiting for.
