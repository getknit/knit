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

## The rank only counts if the round stops at what it can pay for

Found while building the regression above, and the same symptom from the other end. `backfillRank` spends a
round's four scarce slots on the room before the DMs (ADR 2026-09.rre4). `FrameClass` then drains a DM
before the room, and both orders are right: the queue asks who transmits first once the air is paid for, the
rank asks which frames are worth paying for. The defect was that nothing made the second decision stick.

`LoraAirtime` books a frame when it **leaves**, not when it is queued, and `serveOne` asked admission
against *recorded* air — deliberately, so one round's frames all pass and it is the next round that is
stopped (ADR 044). So a round could enqueue four frames worth more air than the window had left, the queue
would drain them cheapest-class-first, and the window would run out part-way down. The frame the queue
reached last was always the one the rank had put first. Measured on the rig: `[profile, dm, dm, dm, room]`
on the air, the room post last, and with a window that could only pay for two of them the room post was
simply never sent.

`serveOne` now asks against what is already **queued** for BRIDGE as well (`LoraPacePolicy.pendingSizes`),
so the round stops at the frames it can actually carry. Because candidates arrive in rank order, the ones it
keeps are the ones the rank chose. The queue's order is untouched, and so is shedding — a live DM still
drains ahead of a backfilled room post, which is what the class order is for.

Reordering the queue instead was the first thing to reach for and is wrong twice over: `FrameClass` also
drives shedding ("a room post never evicts a DM"), and a comparator that ordered BRIDGE frames by rank while
ordering everything else by class is intransitive against live traffic.

**Residual:** the round's room post still goes out after its DMs, so a board that dies mid-round strands it.
That is now bad luck rather than arithmetic — the budget can no longer be the thing that loses it.

Kept true by `LoraBridgeTest.aRoundThatCannotPayForEverythingKeepsWhatTheRankChose`, which spends the bridge
bucket down to about one frame and asserts that the frame the air pays for is the room post.
