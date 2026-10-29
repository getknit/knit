---
id: "054"
slug: casual-texting-must-not-black-out-the-lora-plane
title: "Casual texting must not black out the LoRa plane: a recipient gate, a 15-minute window, and coalesced receipts"
date: 2026-08-27
topics: [lora, custody, airtime]
---

# ADR 054 — Casual texting must not black out the LoRa plane: a recipient gate, a 15-minute window, and coalesced receipts

Status: Accepted (2026-08-27; `mesh/lora/LoraMeshTransport.coveredByLink`, `LoraAirtime`/`LoraPacePolicy`,
`MeshTransport.FanoutHint`, `mesh/DmAckCoalescer`, `Protocol.CAP_INLINE_ACK`, `LoraReach.LoraOnlySaturated`)

**Superseded in part by ADR 2026-10.ug4a (2026-10-29):** decision 3's tick class and tail (`FrameClass.TICK`,
`TICK_TAIL_SHARE`) — the compact ✓✓ is a `DIRECT` frame, shed before a DM but never held out of a window's last quarter
— and the bucketed budget; the 15-minute window stays.

**Superseded in part by ADR 2026-10.x9fa (2026-10-29):** the DM parts — the `FanoutHint.TICK` class of a DM-form tick (the compact ✓✓ is `DirectKind.RECEIPT`) and the recipient gate on DM-form frames; the link and spool covers now guard the compact DM.

Field report: casually texting one person over the mesh drove the LoRa airtime budget to 100 %, after which a
LoRa-only peer stopped receiving anything for up to an hour. The person being texted was on a **Bluetooth
link**. Three causes, all in code, none of them the radio:

1. **Nothing gated a DM-form frame off LoRa by recipient.** `CompositeMeshTransport.longRangeFanout` forwards
   unconditionally and `LoraMeshTransport.fanout` never read `linkedPeers` or self — only `fastSend` did (ADR
   044's amendment). So every DM to a pocket-mate, its sealed ✓✓ coming back, and the gateway's re-fan of
   every relayed DM-form frame (`InboundPipeline.onDeliver`) spent LoRa air for a recipient who already had the
   frame over a link, in both directions, and the far peer then went without for the rest of the window.
2. **The ✓✓ cost as much air as the message.** A 100-char DM compacts to 387 B (2 packets, ≈ 3.8 s at LongFast);
   its sealed `CTL_RECEIPT` is 316 B (≈ 3.25 s), ~280 B of which is fixed overhead around a 22-byte payload. And
   `InboundPipeline.acknowledge` sealed a **fresh** receipt on every re-delivery (the exists-gate, deliberately —
   ADR 039 §3's healing channel), which the LoRa sig dedup cannot catch.
3. **The budget was a rolling hour with a cliff.** `LoraAirtime` allowed `min(region duty, 10 %) × 0.5` of an
   hour — 180 s of air in *every* region, the politeness ceiling binding even where the law is 100 % — so ~25
   round trips spent it and `take()` then blocked until samples aged out an hour later. Firmware facts
   (`Router::send`): EU_868/EU_433 NAK at 10 %/h; every other region enforces no TX-utilization cap at all.
   The 5 % is Knit's own politeness figure, and it stays.

Decisions worth not relitigating:

1. **A recipient gate on the floodable paths, keyed on links and never on sightings — for the third time.**
   `coveredByLink`: a DM-form frame addressed to us or to a peer a higher-preference plane holds a live link to
   (`linkedPeers`, the composite's `suppressDataPath` = BLE ∪ NAN `neighbors`) is skipped on the fan-out and
   the bridge backfill, before the sig-dedup slot is spent. `reofferTo` and `fastSend` already did this. A
   sighting (`foreignReachable`) is not a data path — ADR 044's field lesson — so a merely-sighted peer's DM
   still rides. The room is addressed to nobody and is untouched. Counted as `loraSkippedLinked`.
2. **The window is fifteen minutes at the same percentage.** `LoraAirtime.WINDOW_MS` 60 → 15 min; `SAFETY`,
   `POLITE_CEILING_PERCENT`, `BRIDGE_SHARE` and the fallback unchanged. A burst is capped at 45 s of air (a
   quarter of the old cliff), a dark spell at one window, and the hourly total is unchanged; rolling windows
   straddle, so the worst hour is 5/4 of nominal — ≤ 6.25 %, still under the 10 % the EU firmware refuses at
   (`LoraAirtimeTest` pins it). *Rejected:* letting LIVE spend the full 10 % — every packet we send is
   repeated by the peer's board (and by stock nodes on `rebroadcast_mode = ALL`), so 10 % TX is 20–40 %
   channel utilization, Meshtastic's congested band where stock nodes stop repeating us (their polite
   rebroadcast limit is 25 %); also a token bucket / reserve trickle, which cannot avoid the cliff under a
   hard hourly cap and only moves the pain.
3. **Our own delivery tick is its own class, and the originator says so.** The plane cannot read a sealed frame
   (ADR 039 §3), so `MeshTransport.longRangeFanout` gains `hint: FanoutHint = CONTENT`; `originateSigned`
   threads it, the pipeline's `originateTick` seam carries it, and `originateDeliveryTick` marks the group
   escalation. `FrameClass.TICK` sheds first and dequeues last, and `LoraAirtime.admits` refuses it once a
   window is `TICK_TAIL_SHARE` (25 %) from spent — the last of the air goes to content. A `fastSend` frame is a
   tick by policy (`Path.TARGETED` admits only receipts/ticks) and is classed so unconditionally; relayed
   DM-form frames stay opaque `DM`. `QUEUE_CAP_FRAMES` 16 → 32 so a burst that outruns a window waits for the
   next instead of shedding (≤ ~700 B a frame).
4. **A DM that arrived over the board holds its ✓✓, and a reply carries it.** `mesh/DmAckCoalescer` (pure) holds
   the receipts owed to an author for `HOLD_MS` (45 s, `AckSync`'s figure), anchored on the batch's oldest id;
   `InboundPipeline.acknowledge` holds instead of sealing when `plane == DeliveryPlane.LoRa` and the author can
   read a sealed tick — every other plane keeps today's instant receipt, and an author who cannot read one keeps
   the cleartext form. The exists-gate re-ack goes through the same hold, so a backfill burst yields one tick.
   The flush is `MeshManager.originateDeliveryTick` — the group escalation's own path — with `ack`/`acks`
   (≤ `MAX_LORA_TICK_ACKS` = 12, pinned to fit 3 packets at the ESP32 222-B cap), hinted `TICK`, falling back
   per id to the cleartext receipt like AckSync; `heal()` is the backstop timer. The receive side already
   applied `acks` per id under the forged-ack guard, so the batch is compatible with every ratchet-era build.
   **The piggyback**: `sendChat` takes up to `MAX_INLINE_ACKS` (4) pending ids toward a peer whose profile
   carries `Protocol.CAP_INLINE_ACK = 0x40` and seals them as `MessageContent.acks` on the **plain** DM (v2 arm
   only; a v1 fallback gives them back), reserving `INLINE_ACK_BYTES` (23) each out of the composer's LoRa body
   budget so a reply can never become `loraTooBig` to save a tick; `decryptAndDeliverV2` applies them in the
   same commit as the row (txn outer, lock inner — the `applyCtlInTxn` shape). Additive per WIRE_COMPAT: an
   existing field populated in a new case, behind an append-only bit; an old receiver is never sent one and
   still gets the standalone tick. Not folded into `AckSync`: that class encodes the broadcast/group rules
   (a live-link form, verbatim retries, a done-ledger) and a DM tick has none of them. Process death inside the
   hold loses in-memory acks, accepted as for AckSync — the peer's next re-offer re-delivers, re-holds, re-ticks.
5. **The chat says when the plane is saturated.** `LoraFacts.airtimeSpent` (≥ 90 % of the window's live budget,
   a threshold so the facts flow stays quiet) → `LoraReach.LoraOnlySaturated` wherever the LoRa-only notice
   would show: "airtime is used up, messages are delayed", with the explanation dialog. The DMs-off notice
   outranks it.

Cost per exchange: ~7 s of air → ~4 s (a receipt is ~0.2 s riding a reply, ~3 s standalone, a burst of N DMs
costs one tick); in the observed topology the LoRa budget is no longer touched at all. Counters:
`loraSkippedLinked`, `loraTickDeferred`, `receiptsCoalesced` (the field oracle: every unit is a ~3 s frame not
sent), all in `…debug.LORA`. Tests: `LoraMeshTransportTest` (gate on links not sightings, self, the room; a
hinted tick sheds first), `LoraBridgeTest` (backfill gate), `LoraAirtimeTest` (window, worst hour, tail),
`LoraPacePolicyTest`, `CompositeMeshTransportTest` (the hint), `DmAckCoalescerTest`, `InboundPipelineTest`
(hold / Bluetooth instant / incapable cleartext / inline acks under the guard, never re-applied),
`MeshManagerTest` (one hinted tick per flush; the piggyback carries the ids — its ciphertext grows by exactly
theirs — and a v1 fallback gives them back), `CoordinationPlaneSizeBudgetTest` (a 12-ack tick and a budget DM
with 4 inline acks fit the hop), `LoraReachTest`/`LoraStatusRepositoryTest`/`ChatLoraIndicatorTest`.

Honest residuals (accepted): a frame that waits in the pacer longer than the 10-min sig window can be enqueued a
second time by a late relayed copy (pre-existing, now reachable at 32 slots); the backfill beacon still uses the
60-s first-hearing gap; a session-reset reseal burst rides as `CONTENT`; and the composer's DM budget is pinned
at the nominal 233-B packet while an MTU-255 board takes 222 — a few bytes of over-promise at the very top of
the budget, unchanged here. **Still owed:** the three-phone trial in `context/lora-bridge.md`.
