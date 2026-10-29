---
id: "2026-09.7c8n"
slug: gossip-pays-for-its-own-air
title: "Gossip pays for its own air, and a backfill round skips what it cannot afford"
date: 2026-09-07
topics: [lora, airtime, reliability]
---

# ADR 2026-09.7c8n — Gossip pays for its own air, and a backfill round skips what it cannot afford

Status: Superseded by ADR 2026-10.rp3s (2026-10-29) — the GOSSIP bucket and the backfill round are gone. Was: Accepted (2026-09-07; `AirBucket.GOSSIP`, `Serve.NO_AIR` skips the candidate instead of ending the round, the serve log names the reason, `AirtimeSnapshot.totalUsedMs`)

Two independent bugs with one symptom, both in the half of ADR 2026-09.t8t8 that decided *where the OFFER's
air is booked*. A Nearby-room post crossed neither pocket for 50 minutes while the plane's window was 45 %
idle.

## What was observed

Field trial 2026-09-07, `chore/investigate-two`, one board per phone, P9 out of BLE/NAN range of P7 with the
spool plane deliberately off on P7 — so LoRa was the only path, which is what the trial was for. Both
messages left P9; only the DM arrived.

| | sent | on air | landed |
|---|---|---|---|
| DM `KxuZYxRtMFIe…` | 15:14:09 | fan-out, bridge 15:21:57 (lost), re-offer 15:36:59 | P7 rx **15:37:13**, ✓✓ back |
| Nearby `JVGFW1BM…` | 14:55:37 | fan-out 14:55:56 (lost), bridge 15:22:06 (lost) | never |

P9's ledger at 15:44: **`bridgeMs 14026 / 13500`** — past its own budget — `bootstrapMs 10806/11250`,
**`liveMs 0/45000`**, `loraAirtimeHeldByBucket.BRIDGE` 4 → 9 over ten minutes. Total spend 24832 ms of a
45000 ms allowance. The stuck frame costs ~590 ms.

Two rounds are the whole story:

```
15:21:52  rx offer … same=false   bridge served=4/4   (the room post goes out, and is lost on air)
15:36:50  rx offer … same=false   bridge served=0/4   (NO_AIR on candidate #1 — round over)
15:36:59  tx reoffer:KxuZ…                            (a 1-packet DM, admitted 9 s later)
```

The re-offer flying 9 seconds after the round refused everything is the tell: `reofferOne` enqueues without
asking the governor and the pacer checked it just as a 15-minute sample rolled off, while `serveOne` asks up
front and gives up for the whole round. Same bucket, same window, opposite outcomes.

**Mistaken for**, in order: a receipt bug (the DM's ✓✓ had already landed); a dead RF link (`snr −11.75 /
rssi −105` on the Signal row, which is last-packet-from-anyone pollution and read `+2 / −89` ten minutes
later); and the ADR 2026-09.t8t8 starvation itself (`loraOfferSent` was 6 and every offer flew — the
exemption works).

### Why the bucket was empty

`publishOffer` booked `AirBucket.BRIDGE`. `admits` exempts `FrameClass.GOSSIP` from the BRIDGE share, but
`record` books it regardless, so the OFFER spends a share it cannot be refused by. ADR 2026-09.t8t8 chose
that deliberately — "heavy gossip costs serving its headroom and never the other way round" — and priced it
at "three offers a window is ~6 s of a 45 s LongFast allowance, ~13 %". The 13 % is against the wrong
denominator: the offers land on the **13.5 s** bridge share, not the 45 s allowance, so it is ~44 %.

It is also self-sustaining rather than incidental. `LoraGossipPolicy` is Trickle, and it snaps to its
five-minute floor on *news* — an offer announcing a set that is not ours. Two gateways that genuinely
disagree therefore sit at the floor for as long as the disagreement lasts, which is exactly as long as
serving cannot afford the frame that would end it:

```
P9 holds a frame P7 lacks  ->  both offers say same=false  ->  Trickle stays at the floor
                           ->  3 offers/window eat ~44 % of BRIDGE
                           ->  serving cannot pay for the frame  ->  the sets still differ  ->  repeat
```

### Why the round served nothing

`LoraFramePolicy.backfillRank` ranks profile → room → DM newest-first, deliberately (ADR 2026-09.rre4): a
room post is readable by the whole far pocket for typically one packet. `serveBackfill` then ended the round
on the first `Serve.NO_AIR`. So the rank puts the **most expensive** candidate first and the loop stops at
the first thing that does not fit — a window holding a one-packet DM but not a three-packet room post serves
*nothing*. `served=0/4`, twice, with seconds of bridge air unspent.

## What changed

**The OFFER books `AirBucket.GOSSIP`.** A fifth bucket, judged against the window total alone — it falls
through `admits`' last arm on `bucket != AirBucket.BRIDGE`, so the `FrameClass.GOSSIP` clause that used to
carry the exemption is gone with nothing lost. Everything ADR 2026-09.t8t8 argued for survives: the offer
still rides a spent bridge, is still charged against the total (a window spent on live chat still silences
it, and that is the right order), and is still rate-bounded by the Trickle timer, which that ADR already
identified as the harder ceiling. What goes is only the *recording* — an exempt class booking the share it
is exempt from.

**Not a reserved slice of BRIDGE.** Still the shape a reader reaches for, still unsizeable, and ADR
2026-09.t8t8 §"The reserved-slice alternative" is unchanged: a full 48-prefix OFFER is ~2.0 s at LongFast
and ~13.0 s at LongSlow against the same 13.5 s budget, so any constant starves at one preset or blocks
serving outright at the other. A separate bucket with **no share at all** has no constant to get wrong,
which is why this is the one shape that works.

**`Serve.NO_AIR` skips the candidate.** `serveBackfill` tries the next one instead of ending the round.
Nothing is queued by a refusal — `serveOne` asks the budget *before* `enqueue` — so there is no pile for
class shedding to clear, which was the reason the round stopped in the first place; that reasoning was about
queue pressure and never applied to a candidate the governor had already declined. `LoraPacePolicy.admitBest`
has always skipped a refused frame for the identical reason. `CANDIDATE_SLACK` (3×) already asks custody for
more candidates than the allowance can send.

**The serve log says why, and which frame.** The round line gains a reason —
`lora bridge served=0/4 to <key> (1 over budget)` — since `served=0/4` alone cannot distinguish an offer that
named everything we hold from a window with no air left, and those want opposite remedies. Each refusal then
names itself:

```
lora bridge held bridge:JVGFW1BMj15mWoVZyIpYiw: 590ms + 0ms queued, BRIDGE 13208/13500
```

Priced by the same ledger that refused it, so the three questions a field trial cannot answer afterwards —
*which* frame, what it cost, and whether the window was spent or merely already promised — are on one line.
The `+ Nms queued` half is the ADR 2026-09.zkma distinction: air the round has committed that the ledger has
not booked, which is the difference between "come back next window" and "this round over-promised".
`loraBridgeRefused` stayed **0** through the whole trial — it counts only the hourly serve cap, and is left
alone rather than widened, since a held frame is not a refused one — so `loraAirtimeHeldByBucket.BRIDGE` was
the sole tell, one indirection away from the question.

**`AirtimeSnapshot.totalUsedMs`.** `LoraRadioViewModel.airtimePercent` and `LoraStatusRepository.saturated`
each summed `live + bridge + bootstrap`, three buckets of five: the settings row's "airtime used" and the
composer's "this will wait" hint had been ignoring `PUBLIC` since ADR 2026-09.7r4d added it, and would have ignored
`GOSSIP` too. `airtimePercent`'s own kdoc already said "every bucket counts". One property, both callers.
`…debug.LORA` gains `gossipMs` and `totalMs` beside the per-bucket rows.

## What it costs, and what it does not cover

**Serving can now spend its whole share, so gossip and backfill compete for the total instead of for each
other.** That is the trade, and it is the right way round: a window is far more often short of *total* air
than of bridge air specifically — this one was 45 % idle — and when it is genuinely short, the total test
refuses both.

**Skipping does not reorder anything.** The rank still decides who is *asked* first; the budget only decides
who can be *paid* for. A round that can afford its first candidate behaves exactly as before —
`LoraBridgeTest.aRoundThatCannotPayForEverythingKeepsWhatTheRankChose` still passes unchanged, and is the
guard against "fix" #2 quietly becoming a rank change.

**A stranded frame still needs a round it can afford.** Neither change re-transmits anything sooner: the
freshness gate (`FRESH_MS`, 15 min) still bars an old room post from the LIVE fan-out for ever, so the
bridge is its only path and the bridge runs at the Trickle cadence. Two lost transmissions on a busy band
(the board reported 26 % channel utilization) is still two lost transmissions. What changed is that the
third attempt is not refused against idle air.

**The trap:** `AirBucket` and `FrameClass` remain orthogonal and it is still a per-call-site decision
(`AirBucket.defaultFor` implies only the bootstrap). A future GOSSIP-classed frame enqueued on `BRIDGE` gets
neither the exemption nor its own ledger, which is the worst of both. `publishOffer` is the only caller.

Pinned by `LoraBridgeTest.theOfferDoesNotSpendTheBridgeShareItIsExemptFrom` (alice's offer flies, her BRIDGE
ledger stays 0) and `aCandidateTheWindowCannotAffordIsSkippedRatherThanEndingTheRound` (a three-packet room
post is unaffordable, the one-packet DM ranked behind it crosses anyway) — both fail on reverting their half.
`LoraAirtimeTest.gossipSpendingLeavesServingsShareAlone` and `offersAtTheTrickleFloorCannotStarveServing`
document the ledger contract; `aGossipOfferRidesABridgeBudgetThatServingHasSpent` and
`aGossipOfferStillStopsAtTheWindowTotal` keep both halves of the t8t8 exemption, now on the new bucket.

Device verification on the two-board rig is owed: the same walk-out-and-return, watching a room post cross on
a round whose first candidate is unaffordable, with `gossipMs` non-zero and `bridgeMs` under its budget.
