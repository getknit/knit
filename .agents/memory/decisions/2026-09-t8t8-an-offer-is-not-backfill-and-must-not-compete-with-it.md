---
id: "2026-09.t8t8"
slug: an-offer-is-not-backfill-and-must-not-compete-with-it
title: "An OFFER is not backfill and must not compete with it"
date: 2026-09-04
topics: [lora, airtime, reliability]
---

# ADR 2026-09.t8t8 — An OFFER is not backfill and must not compete with it

Status: Accepted (2026-09-04; `FrameClass.GOSSIP` exempt from the BRIDGE share, one OFFER queued at a time,
`loraOfferSent` counted at transmit, serving asks the budget before it queues)

`publishOffer` enqueued the `LoraCtl` OFFER on `AirBucket.BRIDGE` — the same 30 % share `serveOne` spends
serving a far gateway's backfill. So a gateway busy serving starved its own offers, and that is a worse
failure than a dropped payload frame: `serveBackfill` runs on the **receiver** of an OFFER, so a gateway
whose offers never fly silences the *other* pocket's ability to send it anything at all, including frames
that pocket is holding in custody for exactly that purpose.

It closes a loop on itself:

```
P9 tx offer  ->  P7 hears it  ->  P7 serves 4/4 (spends BRIDGE)
             ->  P7's own offer starved             (enqueued, never transmitted)
             ->  P9 never hears an offer  ->  P9 cannot serve the frame it is holding
             ->  P9's next offer shows the same gap ->  repeat
```

## What was observed

Two pockets, one board each, `integration/longfast-all` @ `ab481c9`, 2026-09-04. P9 left BLE/NAN **and**
LoRa range, posted to the Nearby room, returned to LoRa range only, and held the frame ~30 minutes on a
recovered −56 dBm / +6 dB link. It landed only when BLE/NAN relinked.

| | P9 | P7 |
|---|---|---|
| `loraOfferSent` (then: enqueued) | 6 | **7** |
| `lora tx offer` (transmitted) | 6 | **2** |
| `loraOfferReceived` | **0** | 5 |
| `gatewaysHeard` | **0** | 1 |
| `loraBridged` | 0 | 12 (= `SERVE_CAP_PER_HOUR`) |
| `loraDroppedQueue` | 7 | **23** |

P7's ledger while starved: `BRIDGE 13372/13500 99 %`, `BOOTSTRAP 7722/11250 69 %`, **`LIVE 0/45000`**. The
plane was not short of air, only of that one bucket. Ruled out by the same run: board-session drops
(`loraSessionUps` 1 on both), Trickle pacing (the timer fired; the offers were generated and then refused
at the pacer), weak signal (P7's own serves were landing), and the 45-minute linger windows (backfill is
stateless per-OFFER).

Two details the issue got a step wrong, and they matter to anyone reading the counters:

- **Airtime refusals never folded into `loraDroppedQueue`.** `LoraPacePolicy.take` *skips* a refused frame
  and leaves it queued; `loraDroppedQueue` counts only `enqueue` admissions that were not `ACCEPTED`. A held
  frame reached that counter one step later, when the queue filled and class shedding evicted it.
- **An OFFER is `FrameClass.GOSSIP`, second-highest**, so shedding evicts it almost last. P7's five
  undelivered offers were *sitting in the queue*, not dropped — which is why the 23 drops were all backfill
  and the starved offers left no trace in any counter whatsoever.

## What changed

**The OFFER is exempt from the BRIDGE share, not from the window.** One clause in `LoraAirtime.admits`:

```kotlin
return bucket != AirBucket.BRIDGE ||
    klass == FrameClass.GOSSIP ||
    bridgeUsedMs + cost <= budgetMs(AirBucket.BRIDGE)
```

It is still charged against the total above that line — a window spent on live chat still delays the
bridge, which is the right order — and still **recorded** against `BRIDGE`, so heavy gossip costs *serving*
its headroom and never the other way round. What bounds its rate is `LoraGossipPolicy`: Trickle gives one
transmit slot per interval over a five-minute floor, at most three a window. That is a harder ceiling than a
share, and it is already the mechanism that exists for this.

**The reserved-slice alternative does not survive the preset.** Reserving a slice of `BRIDGE` that serving
cannot consume is the shape a reader reaches for first, and it cannot be sized. A full 48-prefix OFFER is
205 bytes: ~2.0 s at LongFast, ~13.0 s at LongSlow, against the same 13.5 s bridge budget. A *fractional*
reserve big enough for LongFast never admits an offer at LongSlow; a *time-sized* one large enough for
LongSlow consumes essentially the whole bridge budget there — on both gateways at once, so neither ever
serves and the deadlock comes back wearing the other hat. The exemption has no constant to get wrong.

**Only one OFFER is ever queued.** `publishOffer` calls `LoraPacePolicy.dropQueued(FrameClass.GOSSIP)`
first. An OFFER is a snapshot: one left over from a previous interval names a set we have since changed, so
a far gateway computes its backfill against a lie. It is also what keeps the Trickle timer the real rate
bound on this class, rather than whatever the queue accumulated while the window was spent. A replacement is
not a loss, so it deliberately does **not** count `loraDroppedQueue`.

**`loraOfferSent` counts what reached the air.** It moved from `publishOffer` to `sendFrame`, keyed on
`FrameClass.GOSSIP` beside the DM/TICK line. An offer that never flies is the one failure this counter has
to be able to show, and it read 7 against two transmissions. It also restores the arithmetic
`context/lora-bridge.md` documents: `loraSent − loraDmSent − loraOfferSent` is the profile + room count only
if the offer count is a transmit count.

**Serving asks the budget before it queues.** `serveOne` returns `Serve.SENT`/`SKIPPED`/`NO_AIR`, checking
`LoraAirtime.admits(BRIDGE, …)` after `encodeOrNull` and **before** the sig dedup and the enqueue —
`encodeOrNull`'s own rule, so a frame that never goes out leaves nothing behind that suppresses a later
attempt at it. `serveBackfill` ends the round on the first `NO_AIR`. Until now the budget was consulted only
by the pacer, *after* the hourly allowance had been booked and `loraBridged` counted, so a bridge whose
window was spent kept enqueueing frames that sat until class shedding evicted them: P7's `loraBridged` read
the full 12 with nothing landing, and 23 drops were the pile being cleared. `budget.refund` already returned
the unserved allowance, so the hourly cap becomes honest for free. A serve refused here ticks
`loraAirtimeHeld` on `BRIDGE` exactly as one the pacer holds would: it is the same fact — the frame waits for
a later window and the next OFFER names it again — and refusing it earlier must not make it invisible.

**A held frame is now counted.** `loraAirtimeHeld` (+ `loraAirtimeHeldByBucket`, the shape `loraNakByReason`
already uses) fires the first time the budget makes a queued frame wait — once per frame, flagged on
`OutboundFrame.heldForAir`, not once per pacer wake, or it would report the clock rather than the
congestion. Named *held* and not *refused* on purpose: the frame is not dropped, and calling it a refusal
reproduces the confusion the counter exists to end. `LoraStatus.queued` carries the queue depth beside it,
because `loraBridgeRefused` read **0** all session while the plane was starved.

## What it costs, and what it does not cover

Gossip can now take air that serving would have had: three offers a window is ~6 s of a 45 s LongFast
allowance, ~13 % — bounded by the timer, and paid for out of `BRIDGE`, so it lands on backfill rather than
on chat. That is the trade the whole ADR argues for: serving is the right thing to shed under pressure.

**The serve pre-check reads *recorded* air, not what is queued and unspent.** One round's four frames
therefore still all pass it; what it stops is the second round and every one after, which is where the pile
came from. Making it exact would mean the pacer modelling pending spend, and that was rejected as state a
pure governor should not hold.

**A window spent by live chat still silences the bridge.** The exemption is from the share, not the
allowance, so `LIVE` at 45/45 refuses the offer too. That is deliberate — a message somebody typed outranks
a repair — and the bridge recovers on the next window.

**`loraBridged` is still an enqueue count.** With the pre-check it now counts frames the window can carry,
which is close enough to the truth to read; making it a transmit count needs a per-frame "this is backfill"
mark, since `BRIDGE` + `DM` is also the first-hearing re-offer. Left undone on purpose.

`LoraAirtimeTest.aGossipOfferRidesABridgeBudgetThatServingHasSpent` and
`aGossipOfferStillStopsAtTheWindowTotal` pin both halves of the exemption;
`LoraPacePolicyTest.theOfferGoesWhileTheBackfillBesideItWaitsForTheWindow`,
`aSupersededOfferLeavesTheQueue` and `aHeldFrameIsReportedOnceAndNotOnEveryTake` pin the pacer;
`LoraBridgeTest.aGatewayWhoseBridgeBudgetIsSpentStillGetsItsOwnOfferOntoTheAir` pins the loop end to end —
alice's ledger is pre-spent, she serves nothing, and the only thing that can bring bob's held frame across
is an offer that rides anyway. Reverting the one clause fails all three.

Device verification on the two-board lab rig is owed: the same walk-out-and-return, watching that
`loraOfferSent` matches the `lora tx offer` line count while `bridgeMs` sits at its budget.
