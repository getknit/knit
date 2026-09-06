---
id: "2026-09.aa27"
slug: a-room-delivery-tick-rides-a-frame-already-going-to-its-author
title: "A room delivery tick rides a frame already going to its author"
date: 2026-09-05
topics: [receipts, mesh, lora]
---

# ADR 2026-09.aa27 — A room delivery tick rides a frame already going to its author

Status: Accepted (2026-09-05)

## What was observed

A Nearby-room post from the Pixel 9, received by three phones in the far pocket, ticked ✓✓ from one of them
and from that one only. `InboundPipeline` acks a room post with `escalatable = false` and always has —
*"Broadcast-room ticks stay best-effort-only by design"* — so a room tick reaches its author only over a
live link. The Pixel 7 had a LoRa path and its tick landed in 26 seconds. The Pixel 8 had lost its BLE link
to the author 1 m 44 s before the post arrived and holds no board, so its tick had nowhere to go: it sat
owed for **45 minutes**, until the two phones re-linked and `onNeighborAdded` flushed it (`receiptsResent`
2 → 4 at 19:53:37, seconds after the link returned at 19:53:14).

The waiting was not the whole cost. A room tick toward an absent author still **sealed immediately** — one
ratchet chain key per message, spent on a standalone frame with no path — and then re-sent those same bytes
on a doubling backoff for as long as the entry lived.

And the author was not unreachable in any useful sense. At **19:11:25** the Pixel 8 originated a sealed
`CTL_RECEIPT` toward that exact author, for an unrelated DM ack, and custodied it. The room ticks it had
owed since 19:08:24 could have been three more ids in that frame's list.

## What changed

A room tick toward an **absent** sealed-capable author no longer seals. Its bare id waits in a **ride hold**
(`AckSync.takeRiding`) for a frame this device is going to seal toward that author anyway. Two carriers, both
already built:

- an outbound DM's inline acks (`MessageContent.acks`, the `CAP_INLINE_ACK` form, ADR 054) — no extra frame
  at all; DM acks take the slots first, room ticks fill what is left;
- the coalesced `CTL_RECEIPT` that `flushDmAcks` originates for held DM receipts — a frame that already
  exists and is already custodied, so the ids cost bytes rather than rows.

**The receiving end needed no change whatsoever.** `applySealedReceipt`'s forged-ack guard admits an id whose
`recipientOf` is null, which is exactly a broadcast post, and `ackerFor` already has a `Conversations.NEARBY`
arm returning the frame's sender. Both carriers funnel there today. No wire change, no schema change, no
capability bit.

A live link still ends the wait, now as **one** sealed tick covering the batch instead of the one key per
message the room used to spend up front. So this is strictly cheaper than what it replaces, not a trade.

The alternative — letting room ticks escalate like group ones — was rejected on cost. Escalation floods, so
it leaves a custody row on every carrier it crosses, and a room post's recipient count is unbounded and
unknowable. Worse, a sealed ctl tick is DM-form and gets the 24 h custody TTL while the room post it acks
gets 6 h: the ticks would outlive their own message, one per acker. Folding the ack set into the message's
own custody row instead looks free and breaks ADR 018 — receipts were sealed precisely so a carrier cannot
see who received what.

## What it costs

If the acker never sends that author anything — no DM, no DM receipt — the ids age out silently and that
acker is missing from the author's list. The ride narrows the gap; it does not close it, and the room's
details screen remains "everyone we heard back from", never a census. Counted as `receiptsRidden`, whose
gap from the ticks still held is the part this does not cover.

`canSeal` gates the hold for the same reason it gates escalation: a ride only exists inside a frame sealed
to that author, so a legacy author keeps today's cleartext best-effort entry.

**The trap:** a room ack must never be handed back to `DmAckCoalescer`. That hold *originates* what it still
holds when its debounce runs out, which is precisely the custody row this path exists to avoid — hence the
`InlineAcks` split, which exists only so the give-back can put each half in the hold that owns it.

Kept true by `AckSyncTest.aRoomTickTowardAnAbsentAuthorWaitsForARideInsteadOfSealing`,
`aWaitingRoomTickIsHandedToTheNextFrameGoingThatWay`, `aRoomTickWaitingForARideIsNotReHeldByACustodyReServe`,
`aLinkEndsTheWaitAsOneBatchedTick`, `aLegacyAuthorsRoomTickKeepsTheCleartextBestEffortForm`, and
`MeshManagerTest.aRoomTickWaitingForARideTakesTheSlotsADmAckLeaves` /
`theCoalescedTickCarriesTheRoomTicksWaitingForTheSameAuthor`.
