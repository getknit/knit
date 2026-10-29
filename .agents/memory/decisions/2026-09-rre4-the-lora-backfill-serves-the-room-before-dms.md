---
id: "2026-09.rre4"
slug: the-lora-backfill-serves-the-room-before-dms
title: "The LoRa backfill serves the room before DMs"
date: 2026-09-02
topics: [lora, airtime, custody]
---

# ADR 2026-09.rre4 — The LoRa backfill serves the room before DMs

Status: Superseded by ADR 2026-10.rp3s (2026-10-29) — the backfill it ranked is gone. Was: Accepted (2026-09-02; `LoraFramePolicy.backfillRank` — `RANK_ROOM` 1, `RANK_DM` 2)

ADR 044 gave the bridge's serve priority as profile → DM-form → room, and justified only the first step of
it: "a frame the far side cannot verify is airtime thrown away". That is an argument for the profile and
says nothing about the other two. The DM-over-room half was inherited from the pacing queue's `FrameClass`
order, where it is right for a reason that does not transfer.

An offer buys **four** slots (`BACKFILL_LIMIT`), twelve an hour per publisher, inside the 30 % `BRIDGE`
airtime share. With DMs ranked first, a pocket holding a backlog of five undelivered DMs spends every one of
those slots on them and the room crosses on **no** round — demonstrated, not hypothesised: that is exactly
what `aScarceAllowanceSpendsASlotOnTheRoomBeforeItSpendsFourOnDms` asserts against the old constants.

## What changed

One constant swap. The order is now profile → **room** → DM-form, newest-first within a rank.

The room wins on both terms of the trade:

- **Recipients.** A bridged room post is readable by every member of the far pocket; a bridged DM has exactly
  one addressee. And `coveredByLink` (ADR 054) has already dropped the DMs a live link would carry, so a DM
  reaching the rank is one whose addressee is genuinely far — which is the strongest case *for* a DM, and
  still only one person against N.
- **Cost.** A cleartext room post is typically one packet (155–166 B transcoded, ADR 060/2026-09.mhs5); a
  sealed DM is two (387 B steady-state, 439 B carrying its X3DH init). So the room slot is roughly half the
  air of the DM slot it displaces.

A third argument nobody has to weigh at serve time but which points the same way: broadcast custody expires
at `DEFAULT_BROADCAST_TTL_MS` (6 h) against a DM's `DEFAULT_TTL_MS` (24 h). The room has a quarter of the
window in which the bridge can ever carry it, so deferring it is closer to dropping it than deferring a DM is.

**This is deliberately the reverse of `FrameClass` (BOOTSTRAP > GOSSIP > DM > ROOM > TICK), and the two must
stay opposed.** They answer different questions. `FrameClass` decides who transmits first once both frames
are already paid for, and there the DM wins because one named person is waiting on it. `backfillRank` decides
which frames are worth one of four scarce slots. A frame the rank admits is still dequeued behind a DM in the
queue, which is the right composition: the room gets *in*, the DM still goes *first*.

## The alternative, and why it is not this

**Reserving a slot for the room instead of reordering** — serve three by the old rank plus one room post —
keeps a DM backlog moving at nearly full rate while guaranteeing the room a share. It is the more precise
instrument and it is not what shipped, because it adds a second bounding rule to a path that already has
three, to buy an ordering the rank expresses for free. If DM latency across a bridge turns out to matter more
than this ADR assumes, the reservation is the shape to reach for; the rank is a swap away either direction.

## What it costs

A far pocket's DMs now queue behind the room in **slot** terms. The exposure is bounded — an offer arrives
every 5–15 min, custody holds a DM for 24 h, and the frames displaced are the oldest by rank tie-break, not
the newest — but a pocket with a genuinely busy room will bridge DMs more slowly than it did. That is the
trade this ADR makes on purpose, not a side effect.

The trap is a future reader "aligning" the two orders on the grounds that one of them must be wrong.
`LoraFramePolicyTest.theBackfillRanksTheRoomAheadOfADmAndAProfileAheadOfBoth` pins the rank and says why in
its kdoc; `aScarceAllowanceSpendsASlotOnTheRoomBeforeItSpendsFourOnDms` pins the behaviour that follows.

Follows ADR 2026-09.y8pu, whose "not covered: latency" named this ordering as the remaining reason a room
post crosses late. Device verification on the two-board rig is owed for both.
