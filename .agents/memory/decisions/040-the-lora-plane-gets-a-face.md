---
id: "040"
slug: the-lora-plane-gets-a-face
title: "The LoRa plane gets a face: an arrival plane per message, a header glyph, a board-only picker"
date: 2026-08-25
topics: [lora, ui]
---

# ADR 040 — The LoRa plane gets a face: an arrival plane per message, a header glyph, a board-only picker

Status: Accepted (2026-08-25; `InboundFrame.kind`, `DeliveryPlane.LoRa`, `mesh/lora/LoraPlane.kt`,
`LoraStatusRepository`, `BoardFilter`, `ui/chat/LoraReach.kt`, `LoraSizeHint`)

ADR 038/039 shipped a plane the app could not see: a message that crossed the board was stored as
`DeliveryPlane.Nearby`, the connection header had no LoRa glyph where the Internet plane has its cloud, and
the LoRa screen offered every bonded Bluetooth device as a board. This lands the presentation layer by
mirroring the Internet plane's existing shapes rather than inventing new ones. Decisions worth not
relitigating:

1. **`InboundFrame.kind` reverses 038 §3's "`TransportKind` is diagnostics-only" — for presentation only.**
   `CompositeMeshTransport` stamps each child's kind on the frames it merges (the one place that knows;
   `FramedLink` is shared by BLE and NAN and cannot), the router hands it to `InboundPipeline.onDeliver`, and
   `planeOf(fromNodeId, kind)` maps a board frame to the new `DeliveryPlane.LoRa` (code 5). The phone radios
   still collapse to `Nearby` on purpose (the UI has nothing different to say about them). ADR 019's rule
   stands: carry, relay and convergence never read the kind; `MeshRouter` only forwards it. The plane is never
   encoded into `fromNodeId` — that feeds split horizon and reply addressing. `PendingInbound` keeps the kind
   through the key-bootstrap replay, which LoRa relies on (a DM heard before the sender's beacon).
2. **An inbound row is first-write-wins (`MessageDao.insertIfAbsent`), not an upsert.** The plaintext room
   path bypasses the exists-gate and re-ran `deliverChat`'s upsert on every custody re-serve; its comment argued
   the plane it rewrote was always the same "because the room never crosses the Internet" — false once LoRa is
   a plane, since the room is exactly what LoRa carries. A re-served frame is identical signed bytes and can
   never carry anything new, while the upsert also wiped the voice-note metadata `setVoiceMeta` adds after the
   insert and, for our own room post looping back after the SeenSet lapsed, reset its ✓✓ to ✓. Blob re-pull and
   the typing clear still run on a re-serve (they sit after the persist). The v2 hooks stay on `save` (they are
   exists-gated and commit with the ratchet delta).
3. **The header glyph mirrors the cloud exactly.** `LoraPlane { Off, Down, Live }` is the board's `RelayPlane`,
   folded by a *pushed* `LoraStatusRepository` (the transport's status is already a `StateFlow`; no ticker) into
   `LoraFacts(plane, dms)` — one injected flow, not two, because a second never-emitting relaxed mock stalls
   every ViewModel test. `Icons.Outlined.Sensors`/`SensorsOff` (the only radio glyph with an off variant, needed
   for the colour-blind-safe Down state) sit after the cloud; the label is never rewritten — a board needs this
   phone's Bluetooth, so "radios off but LoRa live" cannot happen, and a LoRa-heard peer already counts in the
   mesh line. The Down edge waits 45 s, not the cloud's 12: the session reconnects on a 5 s-and-up backoff and a
   Knit-channel write reboots the board on purpose.
4. **The transport's UI face is a seam (`LoraPlaneStatus`), bound to the transport only when the build ships
   the plane and to a dark stand-in otherwise.** `MeshModule` promises release never instantiates the
   GATT/session singletons; a repository resolved by every open chat would have, through `get<LoraMeshTransport>()`.
5. **The picker's board verdict is a heuristic with an escape hatch, never a filter that drops.** A device is
   board-like when LE-capable and named `Meshtastic_xxxx`, or `<short>_xxxx` (a renamed board — the firmware
   keeps the four MAC hex digits), or carrying the Meshtastic service UUID in the stack's cache (positive-only;
   the cache is empty for most LE bonds). `BoardFilter` shows those plus the bound board; the rest are counted
   behind "Show all paired devices". The bonded list is a Binder call, read on its own arm (resume, the toggle,
   a bound-address change) — never on link churn, which it was.
6. **A connected board earns a channel verdict.** The selected slot's *name* is shown ("Channel 1 · Knit") and
   a slot that is not `KnitChannel.NAME` — index 0, the unnamed primary, included — is flagged with the
   "Set up Knit channel" button emphasized: both boards must be provisioned before a frame crosses, and this
   was the setup step most people still owed.
7. **A DM whose peer only the board has heard gets a pinned notice, like the relay notice.** `LoraReach`
   reads `peerTransports[peer] == {LoRa}` with the plane live and the thread not relay-covered (a covered DM
   has a better carrier); a `LoraOnlyDmsOff` variant says nothing reaches them while the switch is off. The
   copy says "last heard" — the plane's reachable set lingers 45 min. The room and groups never render it.
8. **The composer's "long message" hint is sized by body budgets pinned against real frames.**
   `LoraSizeHint` (room 400 B, session-initial DM 320 B, minus 260 B for a quoted reply and 170 B for an
   attachment reference) sits below the true ceilings, and `CoordinationPlaneSizeBudgetTest` builds frames at
   exactly those sizes — deflate-hostile bodies, the largest reply, an attachment ref — and checks they fit in
   ≤ 3 packets, so the hint can under-warn but never over-promise. Shown only when the draft would ride LoRa
   (`LoraCarry`: the room, or a DM with private messages over LoRa on; never a group), read off the draft
   in the composer via `derivedStateOf` so it recomposes on threshold crossings, not per keystroke.

Not a wire change, no migration (`receivedVia` existed since DB v4; a new code), custody untouched; old
builds read code 5 as `Unknown` → "arrived nearby". Deferred, still: an in-app scan + bond flow
(`MeshtasticScanner`/`MeshtasticBonder` are written but unwired — device-only verifiable, and the scan must go
through `BleConnectArbiter`), and a per-message marker for a post that was `loraTooBig` (no persisted
evidence; the composer hint covers the sending side). Surfaces + tags: `context/lora-bridge.md`.

## Amendment 2026-10-25 — the phone radios are recorded apart (ADR 2026-10.e42d, Proposed)

§1's "the phone radios still collapse to `Nearby` on purpose" gives way once ADR 2026-10.e42d lands. `planeOf` will
then record `Bluetooth` (3) and `WifiAware` (4), the codes reserved for them, because the developer view (ADR
2026-10.s7wg) and the field log's latency lines have something to say about the difference. The chat still reads
both as "arrived nearby", with no glyph. The rest of §1 is unchanged: carry, relay and convergence never read the
kind.

## Amendment 2026-10-31 — "reached their LoRa radio" between ✓ and ✓✓ (Proposed)

**Why.** Over LoRa our own DM shows ✓ the moment it is sealed and its ✓✓ normally ~45 s later (the reader's
`DmAckCoalescer` holds the receipt, far longer when their phone is away from its board). The destination board's
routing ACK is back in about 2 s (field trial 2026-10-30: 1.6–2.6 s) and was invisible. It is the one piece of evidence
in between, and it says the LoRa plane is working.

**What changed.**

1. **A middle state, `DeliveryStatus.RadioReached`.** One of our DMs whose compact copy (ADR 2026-10.x9fa) the
   destination's own board acked shows the LoRa mark (`Icons.Filled.Sensors`) ahead of a single ✓, tagged
   `chat_tick_lora_reached` (apart from `chat_tick_lora`, which says the ✓✓ came back over LoRa), and reads "Reached
   their LoRa radio" on the bubble's description, the chat-list row and the details screen. The words say what the ACK
   proves — their **radio** took the packet — never their phone: it may be away from its board, and the board's to-phone
   queue drops a packet when full after acking it. The details screen adds the time and "Waiting for their phone to
   confirm". `received` always wins: once the ✓✓ lands the Delivered rendering is unchanged. Never for a group, a room
   post, a reaction, a receipt, or a DM a link or the spool covered (it never aired). The one fold is
   `DeliveryStatus.of(message, reached)` (only a DM's `Sent` lifts), shared by the bubble's row mapping, the chat list
   and the details screen; `tickGlyph` / `tickGlyphTag` are the one glyph rule.
2. **The truth source is the transport's settle edge.** `LoraMeshTransport.onRadioReached(frameId, late)` is a
   constructor lambda (beside `onBoardBound`), bound only in `di/MeshModule` to `MeshManager.onLoraRadioReached`. It is
   called for a `DirectKind.MESSAGE` send on a destination's ACK (`PacketOutcome.Acked(byDestination = true)`), on time
   (`settle`) or late (`onUnawaited`: an ack replayed from our board's to-phone queue after the send ended). An implicit
   ACK never calls it. A resend or a re-probe carries the row id, so its ack counts for the same DM. It runs on the
   plane's single outcome collector, so the manager's side is one lock-free `MutableStateFlow.update` and nothing else.
   A flight pushed out of the full in-flight table is now remembered like one past its deadline, so its late ack is read.
3. **The ack's proof gates the claim.** `byDestination` is only `packet.from` on a channel-keyed `ROUTING_APP` packet
   whose packet id rides in cleartext, so anyone holding the channel key can forge one. Firmware 2.8.1 reports its check
   of `Routing.ack_proof` as `MeshPacket.ack_proof_status` (field 23); `MeshtasticProto` decodes it onto
   `PacketOutcome.Acked.proof` (`AckProof`). An `INVALID` proof is never shown (`lora direct ack proof invalid` in the
   log). `ABSENT` — every 2.7.x board, ham mode, and a proof the firmware could no longer check because a relay's echo
   cleared its pending entry first — still counts: that residual trust is an unauthenticated routing header, the same one
   the reach marks have always read. A `VALID` proof is the only cryptographic "their board took it".
4. **In memory only.** `MeshController.loraRadioReached` is a map of row id to `LoraRadioReached(atMs, late)` on
   `MeshManager`'s wall clock, first ack wins, bounded at 256 (oldest dropped), never persisted (no column, no
   migration) and never pruned on a ✓✓ — pruning would need a database read on the outcome collector, and every reader
   lets `received` win. After a restart a DM still without its ✓✓ falls back to a plain ✓, which is honest; sign-out
   and a restore end the process and take it with them. A late ack shows no time: it can be minutes old.

Not a wire change: nothing new airs and no frame changes. Out of scope: a negative state (their board never answered),
persistence, iOS (compact DMs are Android-only), and whether an `INVALID` proof should also stop the reach mark and the
refund (a follow-up). Device trial: `lora-bridge.md`'s "UI surfaces".
