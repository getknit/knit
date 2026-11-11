# Proposal: sealed DM-form chat on the NAN coordination plane

**Status:** IMPLEMENTED 2026-09-07 (ADR 2026-09.7463). Scoped out of the three-Pixel wedge capture
(ADR 2026-09.9dnk); shipped together with the `nanOwedNoLinkPeakMs` diagnostic the *Risks* section below
made a precondition. Nothing here was a wire change. This document is kept as designed-as-shipped history —
the sections below describe the change as built.

## The gap

`FrameFanout.shouldFastFanout` admits broadcast-room chat plus the cleartext metadata frames, and
deliberately excludes **DM-form chat** — `CHAT` with a `recipientId` and no `group`:

```kotlin
FrameType.CHAT -> env.recipientId == null && env.group == null
```

> E2E DM/group *chat* frames are excluded (the broadcast-only arm): they carry wrapped keys and won't fit
> the ~255 B channel, so they ride the NDP flood + custody.

The consequence, measured on the lab rig for ~45 minutes on 2026-09-07: while P8's NDP was wedged and its
Bluetooth was off, its **broadcast room messages kept arriving** on the coordination plane — a 376-char
one included — while every DM, every group-key seed and every `CTL_GROUP_KEY_REQ` sat in its own custody
undelivered, because DM-form has no zero-NDP path. P8 had P7 in `cue=[…]` the entire time. The frames had
a working radio and no permission to use it.

Note what the gap is **not**. Multi-hop relay of sealed pairwise frames already works and needs no change:
`InboundPipeline.canCarry` (line 1693) accepts DM/group chat from any sender, requiring only that it be
encrypted, and verifies the *originator's* signature rather than any claim about the carrier. Proven on the
rig the same day with the spool parked — a DM went **P9 → P7 over BLE → P7 custody → P7 → P8 over NAN** in
3 s while the direct P9↔P8 NDP was failing. A pair bridged only by a third node can already talk.

## Why the size argument no longer holds

Two things in the tree already contradict it.

**Sealed receipts already ride.** `AckSync.kt:441` sends them via `MeshTransport.fastSend`, and per ADR
016/018 a sealed receipt is *wire-indistinguishable* from a real DM — same `CHAT` type, same `recipientId`,
same sealed `enc` payload. `fastSend`'s own contract says so: "the Wi-Fi Aware transport compacts and
fragments ≤ 3 messages toward capable peers, **which is what lets a sealed receipt ride**". If the envelope
shape fitted the channel for a receipt, it fits for a message body.

<!-- doc-lint: allow shouldLongRangeFanout — ADR 039's predicate, deleted by ADR 2026-10.x9fa -->
**LoRa already does exactly this proposal.** `shouldLongRangeFanout` admits precisely the form
`shouldFastFanout` excludes, for precisely this reason — a plane with no data path, where fan-out is the
only path a frame can take (ADR 039). It is wired at *both* sites, origination and inbound re-fan. The NAN
coordination plane is the other no-data-path plane in the system, and it is the one that does not get DM-form.

**The budget.** `coordMsgMax` is the radio's `maxServiceSpecificInfoLength` (255 on all three lab Pixels),
`FastFrameCodec.MAX_PARTS = 3`, `FRAG_HEADER_BYTES = 4` — so a compact/transcoded frame has
**3 × (255 − 4) = 753 B**. Against the measured table in the frame-compaction work: a signed v3 tick is
221 B, a 100-char DM floors around 300 B. A short-to-medium DM fits comfortably; a long one or an
attachment reference does not, and must keep falling back to custody.

## The change

Two call sites, one predicate, no wire change and no new transport method.

**1. A new predicate in `FrameFanout.kt`**, sibling to the existing two and disjoint from
`shouldFastFanout` by construction:

```kotlin
/**
 * Whether [env] should *also* ride [MeshTransport.fastSend] — the targeted coordination-plane sibling of
 * [shouldFastFanout], admitting exactly the DM-form chat frames it excludes. Same set as
 * [shouldLongRangeFanout]: a real DM, its sealed receipt or reaction, a session reset, a group-key seed
 * and an escalated tick are wire-indistinguishable (ADR 016/018), so all of them ride and none is singled
 * out. Targeted, never fanned: one send to the addressee, so a sealed frame is not sprayed at neighbours
 * that have no business holding it, and the ~255 B channel carries one copy rather than N.
 */
internal fun shouldFastSend(env: RelayEnvelope): Boolean =
    env.type == FrameType.CHAT && env.recipientId != null && env.group == null
```

<!-- doc-lint: allow shouldLongRangeFanout — ADR 039's predicate, deleted by ADR 2026-10.x9fa -->
This is deliberately identical to `shouldLongRangeFanout`'s body. Keep them as two named predicates rather
than one shared helper: they answer different questions about different planes and will drift apart the
first time one plane's budget changes.

**2. Origination** — `MeshManager.originateSigned`, beside the two existing fan-out lines:

```kotlin
if (shouldFastFanout(env)) transport.fastFanout(wire)
if (shouldFastSend(env)) transport.fastSend(wire, Peer(env.recipientId!!))   // new
if (shouldLongRangeFanout(env)) transport.longRangeFanout(wire, hint)
```

**3. Inbound re-fan** — `InboundPipeline` (~line 232), so a bridge node forwards at message-plane speed
instead of waiting for its own NDP, which is what makes the P9→P7→P8 case fast rather than merely possible:

```kotlin
if (wire.relay && shouldFastFanout(env)) transport.fastFanout(wire)
if (wire.relay && shouldFastSend(env) && env.recipientId != fromNodeId) {    // new
    transport.fastSend(wire, Peer(env.recipientId!!))
}
if (wire.relay && shouldLongRangeFanout(env)) transport.longRangeFanout(wire)
```

The `env.recipientId != fromNodeId` guard is the split horizon: never bounce a frame back at the hop that
just handed it to us. `onDeliver` runs once per first-seen frame (`MeshRouter` gates on its `SeenSet`), so
each node re-sends at most once and the echo dies out — the same argument the existing `fastFanout` re-fan
comment makes.

Everything else is already in place and needs no edit:

- `WifiAwareTransport.fastSend:1661` returns early unless `cueTarget.containsKey(to.nodeId)`, so a
  non-neighbour is a no-op, not an error.
- `FastEncodings`/`FastFramePick` size-gate: over 753 B, `choose` returns null and the send no-ops. Custody
  carries it exactly as today.
- The receiver's `SeenSet` drops whichever copy loses the race against the NDP flood or the spool.
- `emitFastWire` already injects a fast frame into the normal inbound path, so dedup, custody, relay,
  moderation and the ratchet all run unchanged.

## What this does not change

- **No wire change.** Same frames, same signatures, same encodings — only which plane carries them. Nothing
  in `docs/WIRE_COMPAT.md` applies and nothing goes in `docs/NEXT_WIRE_BREAK.md`.
- **No reliability promise.** The fast plane is best-effort and unacknowledged. Custody remains the reliable
  path; this is a latency layer over it, exactly as it already is for broadcast.
- **No spool change.** A pair scope derives from the pairwise ratchet root, so a third node still cannot
  bridge a foreign pair's frame over the internet plane, and `ScopeFrames.eligibleFor` (§4.4) still refuses
  it. That is the confidentiality/linkability boundary and should stay.
- **No new exposure.** `fastSend` is targeted, and `RelayEnvelope.recipientId` is already cleartext to every
  relay and every custody holder. A DM reaches one addressee here, versus every neighbour under custody.

## Risks, and what to watch

<!-- doc-lint: allow FanoutHint — ADR 054's shed hint, deleted by ADR 2026-10.x9fa -->
**Channel pressure.** `maxQueuedTransmitMessages` is 8 and the plane is already carrying cues, ticks,
receipts and broadcast chat. A chatty DM thread now competes with them. Mitigation: the size gate sheds
anything large for free, and a `FanoutHint`-style shed order already exists for the scarce-medium case
(ADR 054) if it turns out to be needed here too. Watch `nanMsgSendsFailed` against `nanMsgsAcked`.

**It hides a dead data path.** This is the real cost, and it is the reason to pair the change with a
diagnostic rather than ship it alone. This morning's wedge was *findable* precisely because DMs stopped;
with this change the same wedge would have presented as "everything works, slightly worse", and the custody
divergence would have gone unnoticed for far longer. Ship alongside a surface for "no NDP has formed while
a sync was owed for N minutes" — the `syncOwedSince` episode clock added in ADR 2026-09.9dnk already
computes exactly that, it simply has no consumer outside `checkWedge`.

**Ratchet ordering.** A fast copy can arrive ahead of the NDP/custody copy of an *earlier* frame in the same
session. The ratchet already tolerates this (out-of-order within an epoch; `RATCHET_DUPLICATE` counts the
losers of the race, and ran to 341 on the lab rig with no ill effect), but it is the one interaction worth a
deliberate test rather than an assumption.

## Test plan

Unit, JVM, no radio:

- `FrameFanoutTest` — `shouldFastSend` admits DM-form, rejects broadcast, group-form and non-chat; and is
  disjoint from `shouldFastFanout` over a generated envelope matrix (the property that keeps a future edit
  from putting one frame on both arms).
- `CoordinationPlaneSizeBudgetTest` — extend the existing budget table with a sealed DM at representative
  body lengths, asserting where the 753 B cliff falls, so a codec change that pushes DMs off the plane fails
  loudly instead of silently reverting this feature.
- `MeshRouterTest` / `RecordingTransport` — origination calls `fastSend` once with the right peer; the
  inbound re-fan calls it once and never toward `fromNodeId`.

On the rig (the case that motivated it): put P8's NDP back in the wedged state (or simply park the spool and
turn its Bluetooth off), confirm a DM P8 → P7 now lands on the coordination plane, and confirm
`liveFingerprint` still converges afterwards so custody has not been bypassed.

## As built

Landed as specified, plus the diagnostic. `FrameFanout.shouldFastSend`, the two call sites, and
`MeshMetrics.nanOwedNoLinkPeakMs` fed from `checkWedge`'s episode clock and surfaced on `…debug.STATE`.
`MeshMetrics` needed `@Suppress("LargeClass")` alongside its existing `TooManyFunctions` — a flat counter
registry whose length tracks the metric count.

Device-verified on the three Pixels the same day:

```
P8  I WifiAwareTransport: fast-send legacy=-1B compact=288B parts=2 → 24sd5jd4…
P7  I WifiAwareTransport: fast-frame from hzwyqdbj… id=ogTh4kDP634Nmcr-_NfnPw via=transcoded hop=hzwyqdbj…
```

`legacy=-1B` is the point: the legacy framing cannot represent this frame at all, so without the
compact/transcoded path it could not have ridden the plane — 288 B over 2 fragments, well inside the 753 B
budget. `hop=` names P8 itself, so it arrived over the coordination plane rather than an NDP.

Custody was not bypassed: all three held one `liveFingerprint` (301 rows) after the run. Channel pressure
did not materialise — P9 `nanMsgSendsFailed` 0, P8 25 against 468 acked, healthier than the wedge-era
ratios that motivated the concern.

## Estimated size

Small — roughly 15 lines of production Kotlin across three files, plus tests. The seam, the codec, the
fragmentation, the size gating and the once-per-node re-fan discipline all exist and are in daily use; this
adds a predicate and two call sites that mirror the LoRa plane's existing wiring.
