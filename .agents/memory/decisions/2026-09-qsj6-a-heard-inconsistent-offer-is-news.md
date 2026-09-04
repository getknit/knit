---
id: "2026-09.qsj6"
slug: a-heard-inconsistent-offer-is-news
title: "A heard inconsistent OFFER is news"
date: 2026-09-03
topics: [lora, airtime, reliability]
---

# ADR 2026-09.qsj6 — A heard inconsistent OFFER is news

Status: Accepted (2026-09-03; `LoraGossipPolicy.onOffer` resets a backed-off interval, `reset` never delays)

`LoraGossipPolicy` shipped with only half of Trickle wired. `onOffer` counted a **same-set** OFFER toward
suppressing our own — RFC 6206's redundancy half, and correct. A **different**-set OFFER, the genuinely
informative case, did nothing at all: no reset, no earlier wake. RFC 6206 resets the interval to `Imin` on any
inconsistent transmission it *hears*, not only on one it acts on. Here the floor was reached from exactly two
call sites in `LoraMeshTransport` — an inbound frame changing our held set, and `serveBackfill` after an actual
serve — so the first sign that a divergent pocket is on the channel bought no acceleration whatsoever.

The cost is a latency the bridge already documents as its ceiling (ADR 2026-09.y8pu's "not covered: latency",
ADR 2026-09.rre4). Once *one* gateway has spoken, the responding side sat out whatever was left of its own
interval — up to `MAX_INTERVAL_MS`, fifteen minutes — before saying what it lacks, and the second, corrective
exchange is the one that carries anything. A reunion shorter than that gets one half of a conversation.

Found by reading the policy against RFC 6206, not by a field failure. The undelivered Nearby-room post of
2026-09-03 (a Pixel 9 in LoRa range only, 17:50–18:08, the frame landing instantly once BLE/NAN returned)
looks like a board-session drop instead — `loraSessionUps` showed three reconnects in the surrounding
85 minutes, and the `LoraGatewayPolicy`/`LoraGossipPolicy` arithmetic says an unrivalled ACTIVE gateway
transmits at least one OFFER inside any fifteen-minute span. The gap is real and independent of that test.

## What changed

`onOffer` snaps the timer to the floor on a set that is not ours — **and only from a backed-off interval**:

```kotlin
ensureInterval(now)
if (sameSet) consistent++ else if (intervalMs > minIntervalMs) reset(now)
```

That guard is load-bearing, not an optimisation, and it is why this is not the plain RFC 6206 shape a reader
reaches for first. Two gateways whose sets *cannot* converge — the per-publisher hourly cap spent, a far
pocket holding a permanent superset, or either side's 48-prefix window truncating a large set — would
otherwise reset each other on every offer, forever. They alternate rather than starve, so the failure is not
silence: it is a standing OFFER every two to three minutes each, roughly `2/3` of the whole BRIDGE budget
(ADR 044 §4) spent announcing a divergence instead of serving it. A timer already at `MIN_INTERVAL_MS` is
already as fast as this policy goes; there is nothing there to accelerate.

`reset` now means **sooner, never later**. It draws a fresh transmit point from `now`, which can land after
one the current interval had already picked and not spent — so an unspent point still ahead keeps its slot,
and one already due stays due:

```kotlin
val pending = if (intervalStart != NEVER && !spent) transmitAt else Long.MAX_VALUE
// ...snap to the floor, re-arm from now...
if (pending < transmitAt) transmitAt = maxOf(pending, now)
```

Without it, news arriving just before our own transmit point pushed that transmission back by up to a floor
interval — the exact opposite of what a reset is for, and it applied to the two pre-existing call sites too.
`LoraMeshTransportTest.aFirstHearingBeaconsAfterASixtySecondGapWhileSessionUpKeepsTheFloor` was silently
relying on the old delay to keep an OFFER out of its packet count, and now pins its gossip floor explicitly.

**The wake is half the fix.** `onCtlPacket` pokes `gossipWake` when the offer was inconsistent, as both
existing reset sites already do. The gossip loop sleeps on a wait computed from the *old*, longer due time;
left asleep it does not merely miss the acceleration, it wakes past the end of the floor interval the reset
opened and `ensureInterval` **doubles** — strictly worse than doing nothing. A spurious wake costs nothing:
the loop re-reads `nextDueAt`, `takeTransmitSlot` declines, and it sleeps again.

## What it costs, and what it does not cover

In sustained divergence the cadence settles near the floor rather than the ceiling — a transmit every five or
six minutes instead of fifteen, one packet each. That is the floor's own meaning, and it is already where a
busy bridge sits: `onFrame` resets on *every* inbound LoRa frame, so the fifteen-minute backoff only ever
describes a silent channel. The new path adds air in one narrow case — two ACTIVE bridging gateways, divergent
sets, and nothing crossing in either direction — and `LoraAirtime`'s BRIDGE budget remains the real bound.

A passive or non-bridging node never publishes, so its `lastOfferPrefixes` stays empty and every heard OFFER
reads inconsistent forever. It costs nothing on the air (`publishOffer` bails before transmitting) but it does
wake the loop and tick `loraPassive` about three times as often; the counter is a diagnostic, and a reader
comparing it across builds should know why it moved.

**Not covered: repeated identical divergence.** An unchanged divergent set heard again is not new information,
and the floor guard is what bounds it rather than any memory of what we last heard. Tracking the last
inconsistent set per publisher would be more precise and was rejected as state a pure timer should not hold;
it stays available if a soak ever shows offers crowding out backfill.

`LoraGossipPolicyTest.anOfferAnnouncingADifferentSetSnapsABackedOffIntervalToTheFloor`,
`aResetDoesNotPushAnAlreadyPendingTransmitLater` and `aResetDoesNotCancelATransmitThatIsAlreadyDue` pin the
policy; `sustainedDivergenceDoesNotBeatTheFloorCadence` pins the guard.
`LoraBridgeTest.hearingAnOfferForADifferentSetSnapsABackedOffTimerToTheFloor` pins the wake end to end — the
far gateway holds a superset whose one extra frame is a DM its own link covers (ADR 054), so nothing crosses
the air in either direction and only the heard offer can explain the acceleration. All three of the reset,
the never-later guard and the wake fail it independently.

Device verification on the two-board lab rig is owed.
