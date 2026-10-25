---
id: "2026-10.e42d"
slug: a-frame-records-the-phone-radio-it-arrived-on
title: "a frame records the phone radio it arrived on, not just Nearby"
date: 2026-10-25
topics: [mesh, data, diagnostics]
---

# ADR 2026-10.e42d — a frame records the phone radio it arrived on, not just Nearby

Status: Proposed (2026-10-25). It ships with the developer view's first slice (ADR 2026-10.s7wg) and amends ADR
040 §1. No wire byte, column or migration moves.

**What was asked.** The developer view should say which radio a message came over, Bluetooth or Wi-Fi Aware. Today
it cannot, because the phone never keeps that fact:

- `InboundPipeline.planeOf` maps a spool source to `Internet` and a board frame to `LoRa`. Every other frame becomes
  `Nearby`.
- ADR 040 §1 made that collapse deliberate: "the UI has nothing different to say about them".
- The fact is there to keep. `CompositeMeshTransport` stamps each child's `TransportKind` on the frames it merges.
  `DeliveryPlane` has reserved `Bluetooth` (3) and `WifiAware` (4) since DB v4, and every reader that asks "short
  range?" already counts both (`shortRange`, `shortRangeCodes`, ADR 2026-09.xmte).

The developer view and the field log now have something to say about the difference. Which radio carried the
traffic is the field question behind most open radio work: Coded PHY reach (yvn6), the BLE drain (8jwn), whether
Wi-Fi Aware pays for itself (2t49).

**What is proposed.** `planeOf` returns the radio:

- a spool source is `Internet`, as before;
- a `TransportKind.LoRa` frame is `LoRa`, as before;
- `TransportKind.Bluetooth` is `Bluetooth`, and `TransportKind.WifiAware` is `WifiAware`;
- `TransportKind.Other` stays `Nearby`. That kind covers fakes, the demo transport, the lab's `LabTransport` and
  anything later that does not say.

The plane lands where it always has:

- `messages.receivedVia`. For an inbound message, that is the radio of the hop that handed it to us. For ours, it
  is the radio the first receipt crossed, first evidence winning in `MessageDao.markReceived`.
- `message_receipts.via`.

The one equality on `Nearby` in the inbound path is the walkie chunk's hop (`hop = fromNodeId.takeIf { plane ==
DeliveryPlane.Nearby }` in `dispatchByType`). It becomes `plane.shortRange`, keeping its meaning: a short-range hop
is the evidence `TalkRadioPaths` keeps (ADR 2026-10.79yb).

Every other reader already copes, which makes the change safe:

- The holder query answer (`onHaveAnswer`) reads `plane.shortRange`.
- The attachment deferral and the pair-held proof read `shortRangeCodes`.
- The LoRa receipt coalescer tests `== LoRa`.
- The chat's text, glyph and test tag (`DeliveryStatus`: `arrivedText`, `deliveredText`, `planeGlyph`, `planeTag`)
  map every plane but `Internet` and `LoRa` through `else`. So a person still reads "arrived nearby" with no
  glyph, and the visible UI does not change.

**The alternative a reader reaches for first** is a second column, for example `arrivedKind`, beside `receivedVia`.
It would take a migration, a backup classification and a second first-evidence rule, all to hold a fact the
existing column was reserved for. The other alternative, keeping the collapse and having the developer view infer
the radio, cannot work: a frame's kind lives only as long as the dispatch call.

**What it does not cover.**

- *The path within a radio.* The record cannot tell a Bluetooth link frame from one heard on the BLE side channel
  (ADR 2026-09.sjaa), or a Wi-Fi Aware data path from its message plane (ADR 2026-09.7463). That would need a path
  stamped at the transports' emit points and new codes. It was asked about and set aside on 2026-10-25.
- *The path a relayed frame took.* Only the last hop is recorded. A frame heard on both radios keeps whichever
  this phone processed first, because the `SeenSet` drops the second.
- *Rows written before.* They keep `Nearby`, and nothing is backfilled. The developer view shows those as
  `Nearby (radio not recorded)`.
- *Holder proofs* (`attachment_holders.plane`) keep `Nearby`. Their two writers in `MeshManager`, the holder query
  answer and the file store hook, see no frame kind.

**What stays as it was.**

- ADR 019's rule stands. Carry, relay and convergence never read the plane, and it is never folded into
  `fromNodeId`.
- Nothing new leaves the phone. The plane is never on the wire, and the iOS port keeps its own storage.
- The one surface that leaves by hand is the field log's export. There, the per-plane `latency` lines (ADR
  2026-10.a239) now split `Bluetooth` from `WifiAware`, while older rows still group as `Nearby`. These are enum
  names, not ids, so the log's rules hold. `docs/FIELD_LOG.md` says so.

**What keeps it true.**

- `InboundPipelineTest`. `aDmHeardOverLoraRecordsTheLoraPlane` and its siblings record `Bluetooth` and `WifiAware`,
  and a `TransportKind.Other` frame records `Nearby`. The receipt cases delivered with `TransportKind.Bluetooth`
  mark the tick `Bluetooth`. A walkie chunk over either radio still names its hop.
- `DeliveryStatusTest` pins that `Bluetooth` and `WifiAware` read exactly as `Nearby` does: the same text, no glyph
  and no tag.
- `FieldLogDocumentTest` covers a latency group per radio.
- The mesh lab is unchanged, since `LabTransport` frames are `Other`. A lab scenario that asserts `Nearby` keeps
  passing, and that is correct.
