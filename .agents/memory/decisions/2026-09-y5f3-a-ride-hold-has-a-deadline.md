---
id: "2026-09.y5f3"
slug: a-ride-hold-has-a-deadline
title: "A ride hold has a deadline, and the spool is the room tick's first way home"
date: 2026-09-13
topics: [receipts, mesh, lora, spool]
---

# ADR 2026-09.y5f3 — A ride hold has a deadline, and the spool is the room tick's first way home

Status: Accepted (2026-09-13). Amends ADR 2026-09.aa27 (the ride hold now flushes on a timer).

## What was observed

A field day, 2026-09-13: the Pixel 9 out of BLE/NAN range with a board and cell data, the Pixel 7 in the
lab with a board. Three Nearby-room posts left the Pixel 9; two crossed LoRa (`lora rx chat` on the
Pixel 7 within 2 s), and their ✓✓ arrived **48 minutes later**, seconds after `bt link up` — the Pixel 7's
counters read `receiptsSealed 16→17 ticksUnsigned 6→7 receiptsResent 1→2` at 19:05:33 and nothing before.
Neither phone logged a single `lora tx send:` all day.

The cause was ADR 2026-09.aa27, read against a case it did not consider. It parks a room tick toward an
*absent* sealed-capable author in `AckSync.holdForRide` — "absent" being `transport.neighbors`, the
BLE/NAN links — with no deadline, to ride a frame this phone will seal toward that author anyway. Two
things went wrong at once. An author reachable only over LoRa counts as absent, so the TARGETED path that
aa27's own "what was observed" credits ("the Pixel 7 had a LoRa path and its tick landed in 26 seconds")
was never asked. And the hold's only carriers were `flushDmAcks` (a DM that arrived *over the board*) and
an outbound DM's inline acks; the DMs that day arrived over the **spool** in half a second, so
`InboundPipeline.sealDmReceipt` sealed an instant single-`ack` receipt (18:19:00, 18:30:20) and the group
tick escalated through `originateDeliveryTick` (18:38:05) — three sealed frames to exactly that author,
none of which consulted `takeRiding`. Meanwhile every LoRa copy of a DM-form frame that day was wasted
air: the Pixel 7 had each DM off the spool before its LoRa copy left the Pixel 9's board.

## What changed

**A ride hold has a deadline.** A room tick waits `AckSync.RIDE_HOLD_MS` = **60 s** — longer than both
45 s holds (`DmAckCoalescer.HOLD_MS`, `AckSync.TICK_BATCH_DEBOUNCE_MS`) on purpose, so a receipt or group
tick sealed in the same minute carries it first. At the deadline (`flushDueRides`, armed like the group
debounce and backstopped by `retryPending`), if nothing carried it, the batch is sealed **once** and
routed, in this order:

1. the author was seen on a connected spool within `SPOOL_COVER_MS` = **15 min** — the signed
   `relay = false` tick is pushed *directly* into every DM/pair scope the two share
   (`ScopeSync.pushDirect`): no custody row here, no flood, no air;
2. else the author is on a fast plane (`transport.reachable`; LoRa feeds it from fresh frames) — the
   tick goes out `fastSend`, LoRa's TARGETED path, one packet, class `TICK`; it stays retryable as one owed
   entry on the existing doubling backoff and is dropped the moment a link carries it;
3. else it keeps waiting — aa27's rule stands: never spend a chain key on nobody. A link
   (`onNeighborAdded`), a new sighting (`onReachable`, fed by `watchReachable` and by a spool newcomer) or
   the heal heartbeat re-runs the deadline.

**Every sealed frame to the author is a ride.** The instant DM receipt and the escalated group tick now
take the waiting room ids beside their own (`acks` next to `ack`; the receiver already merged the two).
Riders on a frame bound for the 3-packet LoRa hop are capped at `DmAckCoalescer.MAX_LORA_TICK_ACKS` — a
latent aa27 bug: `flushDmAcks` took up to 64 riders onto a frame whose held DM acks were sized to 12.

**The spool covers the board.** `MeshTransport.coveredByInternet(peers)` — a sibling of `suppressDataPath`,
fed from `ScopeSync`'s `peerSeenAt` at the 15-min window — tells the LoRa plane which peers a connected
spool is currently a path to, and every DM-form frame to such a peer stays off the air: the fan-out, the
targeted tick, the first-hearing re-offer, and a frame already queued (`StaleAtSend.INTERNET`). Room posts
are untouched; the spool does not carry the room. This is the first gate on `fastSend` since ADR 044 said
it must never have one, and it is a different kind of gate: the role gate was refused because a passive
board holds no copy to relay, while a cover says the peer demonstrably has a path that costs nothing.

**What the alternative was.** Escalating a room tick into custody like a group's — rejected by aa27 and
still rejected: it floods, leaves a row on every carrier with the 24 h DM-form TTL, one per acker, and the
room's acker count is unbounded. The direct push is the same sealed frame with the custody step removed.

## What it costs

- **"No custody row" holds on the acker only.** `relay` is not part of the sealed record (`sig ‖ signed`),
  so the author pulls the tick as `relay = true` and custodies it like any DM receipt it pulls. The two
  stores then differ until a link re-serves it to the acker — an ordinary custody re-serve, not a
  divergence loop, but `MeshLab.assertConverged`'s custody half cannot pass on a link-less topology (the lab
  scenario re-links first).
- **The spool form is signed.** `ScopeCrypto.seal` requires the 64-byte signature, so route 1 cannot use
  ADR 059's unsigned-v3 tick; the route is chosen *before* the single seal, and a push that fails after
  sealing keeps the signed wire as the owed vehicle (it rides every path; a one-id tick is still one LoRa
  packet at ~223 B).
- **The pusher must account what it pushed** (§9.6's second writer): its own heal loop derives `local` from
  custody, and without the accounted entry the next round would pull the tick back, custody it as
  `ORIGIN_RELAY` and fan it onto the radios — the exact cost this path exists to avoid. A process restart
  forgets the accounted set (C-9.6-4), so the acker re-pulls its own tick once; ADR 062's existing cost.
- **Route 2 keys on `transport.reachable`,** which includes Wi-Fi Aware's 150 s ghost and BLE advert-only
  sightings, so one chain key may go to nobody; the owed backoff (15 min doubling) bounds the re-sends.
- **Presence is stamped before delivery now.** `ScopeSync.acceptClaimed` used to note the peer after
  `deliver`; the receipt answering the DM that reveals the author is originated *inside* `deliver`, so the
  cover would have missed exactly the field case. It is still bookkeeping and never a gate.
- **A give-back keeps its original stamp** (`AckSync.lent`), or every rider taken then returned would push
  the deadline out.
- **Not covered:** an author reachable only through a multi-hop BLE/NAN relay, with no board and no spool,
  keeps aa27's best-effort behaviour and still needs a link.

## What the lab found on the way in

The three scenarios run two real stacks over the real `LoraMeshTransport` (a shared `FakeMeshtasticAir`)
and the real `ScopeSync` (a shared `FakeSpool`), composed through the real `CompositeMeshTransport`; the
lab had neither plane before. The oracle's custody half caught a divergence nothing in this change
introduced: a node's own profile signed twice under one publish stamp — the LoRa beacon signs
`currentProfileEnvelope()` fresh, and while a settings write was still landing it produced a *variant*
(same id, different `signed`). The peer custodied the beacon's bytes, the node its seed's, both pushed
theirs to the spool, and neither side could ever fold the other's blob: two blobs under one id, a scope
digest that never converges, `store.has(id)` true so §9.6 never accounts it. In the field the same window
opens on any settings write that is not itself a republish. `MeshManager.ownProfile()` is now the one
source of the profile's bytes — the custodied row for the current stamp when there is one, else a fresh
signing custodied at once — and the beacon, the reflood, the first-contact push and the startup seed all
read it (`ForwardStore.frame(id, now)` is the by-id read it needed). Two smaller ones: `FakeSpool` locked
its request handler per socket over state shared by every socket, and `FakeMeshtasticAir` kept its links
in a plain list — both fine for a single-threaded rig, neither for two nodes on `Dispatchers.Default`.

Kept true by `AckSyncTest.aRideNobodyCarriesIsPushedToTheSpoolWhenItsAuthorIsPresentThere`,
`aRideNobodyCarriesGoesOverTheFastPlaneWhenItsAuthorIsOnlyReachable`,
`aRideNobodyCarriesKeepsWaitingWhenItsAuthorIsNowhere`, `theSpoolIsPreferredOverTheAirWhenBothCouldReachTheAuthor`,
`aSpoolPushThatFailsAfterSealingKeepsTheSignedTickAsAnOwedBatch`, `theRideDeadlineWakesWithoutAHeal`,
`aRideGivenBackKeepsItsOriginalStampSoTheDeadlineDoesNotMove`, `anIdACarrierRodeIsNeverReHeld`;
`ScopeSyncTest.a directly pushed tick reaches the peer, is never re-pulled by its pusher, and leaves both digests converged`,
`a peer's presence is reported once when it appears and withdrawn when it lapses`,
`presence is stamped before the frame is delivered`;
`LoraMeshTransportTest.aDmFormFrameToAPeerTheInternetPlaneCarriesIsKeptOffTheAir`,
`aTargetedTickToASpoolPresentPeerIsKeptOffTheAir`, `aQueuedFrameIsAbandonedWhenItsRecipientAppearsOnTheInternetPlaneWhileItWaits`,
`internetCoverNeverMovesTheGatewayRole`; `MeshManagerTest.theRideDeadlineSendsATickOverTheFastPlaneToAReachableAuthor`,
`aNewSightingOfTheAuthorFlushesADueRideAtOnce`, `theGroupBatchTickCarriesTheRoomTicksWaitingForTheSameAuthor`,
`aCoalescedTickNeverCarriesMoreRidersThanTheLoraHopFits`; `InboundPipelineTest.aSealedDmReceiptCarriesTheRoomTicksWaitingForItsAuthor`;
`CompositeMeshTransportTest.internetCoverIsForwardedToEveryChild`; and the three `RoomTickPlanesLabTest`
scenarios in `mesh/lab/`, which run the real stacks over a fake air and a fake spool.

## Superseded in part by ADR 2026-10.rp3s (2026-10-29)

The LoRa leg of route 2 is gone: LoRa's `fastSend` refuses every room tick, and `escalateRide` reads only short-range reach, so an author the board alone can hear keeps their tick parked for a link or the spool. The deadline, the spool route and Wi-Fi Aware's air route stay.
