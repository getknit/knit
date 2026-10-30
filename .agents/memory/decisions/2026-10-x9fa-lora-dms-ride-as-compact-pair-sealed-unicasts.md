---
id: "2026-10.x9fa"
slug: lora-dms-ride-as-compact-pair-sealed-unicasts
title: "LoRa DMs ride as compact pair-sealed unicasts"
date: 2026-10-29
topics: [lora, crypto]
---

# ADR 2026-10.x9fa — LoRa DMs ride as compact pair-sealed unicasts

Status: Proposed (2026-10-29). The LoRa half (Stage 2a) and the crypto half (Stage 2b, below: the seal, the
`lora_direct_seen` table, delivery, the offers in `MeshManager` and the deletion of `MeshTransport.longRangeFanout`).

**Amended 2026-10-30 (below, "firmware 2.8 PKI-encrypts every compact DM"):** the DM is never kept off PKI by the Knit
secondary; it is sized and priced as a PKI packet, and ADR 2026-10.aajj builds Plan B.

**Superseded in part by ADR 2026-10.ug4a (2026-10-29):** the ✓✓'s pacing. It is a `FrameClass.DIRECT` frame now, shed before a DM or a reaction but dequeued in its turn, and it may spend a window's last quarter: ADR 054's tick class and tail are gone. Every airing is booked and refunded against the one ledger, with no bucket.

Supersedes: ADR 039's DM form (a DM rode the plane as its signed, ratchet-sealed mesh frame), ADR 2026-10.a4pm (the
reach gate and the probe), ADR 2026-09.5dt2's `RideGate.reachGated`, the DM parts of ADR 054 (the `FanoutHint.TICK`
class of a DM-form tick; the link and spool covers stay), and ADR 2026-10.rp3s's probe, first-hearing re-offer and
heard-loss repair. Amends ADR 2026-09.4n5p (the legacy-DM refusal is the text port's alone) and keeps rp3s's
re-probe, re-keyed.

**What was observed.** A 40-character DM rode LoRa as its signed, ratchet-sealed frame: about 244 B, two packets
broadcast at hop 3 to every board in range, and its ✓✓ another 221 B packet. Nothing on the air said whether it
arrived, so the plane carried a reach gate, a probe per window, a re-offer on first hearing, a heard-loss repair and
a persisted memory for each — the machinery ADR 2026-10.a4pm and 2026-10.rp3s built to guess, from frames the peer
happened to air, whether a DM had a chance. On the 2026-10-04 field walk the DM never landed.

**What changed.** A DM, its ✓✓ and a DM reaction leave as **one** compact packet, tag `0x11` (`LORA_DIRECT_TAG`,
registered in `FastFrameCodec`'s tag registry and `docs/WIRE_COMPAT.md`): `tag 1 | pairTag 4 | salt 8 | frameId 16 |
sentAt u48 6 | AES-256-GCM` around the v3 compact content, 51 B of overhead. It is a Meshtastic **unicast with
`want_ack`** (`MeshPacket` field 10) to the recipient's board, on the bound Knit **secondary** (never slot 0 — a direct
send is refused there, counted `loraDirectSlotZero`; the reason given here, that the firmware PKI-encrypts it on slot 0
only, was wrong: it does on every slot, see the 2026-10-30 amendment), `PRIVATE_APP`,
`hop_limit = HOP_LIMIT`. The firmware's retries and the destination's ACK replace the reach gate. The signed copy still
goes to custody, the spool and the links exactly as before; LoRa no longer carries it, and `LoraFramePolicy.eligible`
admits only an own one-packet room post. Old builds drop `0x11` as `UNKNOWN_TAG`.

The board seam became board-neutral at the minimum a MeshCore board needs: `MeshtasticLink.send` takes a
`LinkDestination` (`Broadcast | Direct(BoardAddr, ack)`, `BoardAddr` an opaque value class over today's node number,
so it can hold MeshCore's key prefix later), returns its handle in `SendResult.Queued.id`, and reports
`PacketOutcome.Acked(byDestination) | Unreachable | Refused(reason, byDestination) | NoOutcome` on `outcomes`.
`MeshtasticSession` keeps the destination of each acked send (bounded, cleared on `stop`) and tells a destination's
ACK from an implicit one; `MAX_RETRANSMIT` / `NO_RESPONSE` are `Unreachable`; a `Routing` `NONE` about a broadcast is
no outcome, so `loraNak` and the `lora nak` log no longer count `NONE`. The DM auto-reply is the one direct send
without `want_ack`.

The transport (`LoraMeshTransport`, a `LoraDirectSink`) seals and sends behind four seams it defines in
`mesh/lora/LoraDirect.kt` — `LoraDirectCrypto` (seal, open), `LoraDirectInbox` (deliver), `LoraDirectSource` (our
unacked DMs as plaintext) and the board map (`Flow<Map<String, LoraBoardHolder>>`, pinned contacts with `loraNode`,
the capability, and a profile version). Every decision is in a pure policy: `LoraDirectPolicy` (reach marks, the
in-flight table and its deadlines, the parked offers) and `LoraReprobe`.

- **Gates**, asked at offer and again before the first byte (`RideGate.direct` / `reachHeld` / `unackedFrameId`,
  `StaleAtSend.NOT_DIRECT` / `HELD` / `ACKED` / `SLOT_ZERO`): DMs switched on; the peer in the board map with the
  capability, at the same board; no live link and no spool cover; the bound slot not 0; one copy per frame id queued
  or awaiting its answer (`hasQueued` and the in-flight table — Stage 3 must keep this); the peer not marked
  unreachable or refused; one send in flight per peer not known reachable (the rest parked, replayed on an ack,
  dropped to the source on a failure); and the seal fitting one packet at `maxPayload` less `want_ack`'s two bytes in
  the `ToRadio` (`loraDirectTooLong` otherwise).
- **Reach is a radio's, never a person's.** `Reachable` after the destination's own ACK, lingering 45 min;
  `Unreachable` after `MAX_RETRANSMIT` or no outcome by the deadline (never `Reachable` on silence); `Refused` when the
  destination's board refuses what reached it, held until their profile changes — hearing them does not lift it. An
  implicit ACK is counted (`loraDirectImplicitAck`) and ends nothing. The author-keyed `reachable` set is fed by a
  fresh authenticated compact DM *from* the peer, never by an ACK. After a restart every board is `Unknown`.
- **Every send ends.** It carries a deadline of `AIRINGS` gaps at the firmware's busiest contention window plus 10 s
  (`LoraDirectPolicy.deadlineMs`, about 2 min for a full packet at LongFast), so the firmware's own answer always
  comes first; past it, `NoOutcome`. The table is bounded (32) and cleared when the board goes. A send whose
  `queueStatus` never came back (`SendResult.Timeout(id)`) is awaited under the handle the session gave it, not
  abandoned. A send that ended unanswered is remembered (bounded) so a destination's ack replayed late from the board's
  to-phone queue still marks the board reachable, booking nothing more. A relay's implicit ack stops the firmware's
  retries (`ReliableRouter::perhapsGenerateImplicitAckForOwnOverheard`), so the field's usual failure is an implicit
  ack, then silence, then `NoOutcome`.
- **The hearing hook.** A packet clears an `Unreachable` mark only if it comes from a board in the map, carries an
  RSSI, did not come off MQTT, is not our own board, and its `rx_time` (`MeshPacket` field 7, now decoded) is within
  3 min when present — which filters the handshake's NodeDB replay and the to-phone queue replayed at reconnect. Any
  portnum counts, slot 0 included; the session now surfaces NODEINFO and a far board's ROUTING packets for it. The
  edge out of `Unreachable` resends the newest unacked DM only once the current gap has passed since the failure, and
  flushes the older ≤ 2 only after its destination acks it; each resend re-asks `isUnacked` at send, never behind a
  timer. A hearing of a received compact DM is noted only after `inbox.deliver` returns, so a ✓✓ it carried is
  applied first.
- **The re-probe keys on our DMs with no ✓✓, whatever the radio said** (`LoraReprobe`, rp3s's schedule, persisted
  under its old `ReprobeStamp` keys): 15 min after the first, doubling to 4 h. A destination's ack of a fresh DM resets
  the gap and asks again one base gap on (the phone may be away from the board); an ack of a re-aired one keeps the gap
  it has earned, so a board that acks everything while its phone stays away is asked at 15, 30, 60 min and on, never
  pinned at 15. A hearing pulls the round forward but never resets it; a ✓✓ on the last re-aired DM resets it. A
  restored schedule for a peer without the capability is forgotten on its first due round; with no board map yet it
  only defers. A refusal lapses to unknown after the re-probe's cap (4 h), so a board set up again without a profile
  edit is asked once more.
- **Air.** A compact packet is priced unsigned (`LoraAirtime.UNSIGNED`: 2.8 signs broadcasts only), admitted against
  `AIRINGS` (5) and **booked for all five at send**, so a send still in flight holds its retries against the next
  frame's admission; a destination's ack (or refusal) refunds the airings that had not flown, counted at the quietest
  band's gap so the refund errs small (`LoraAirtime.refund`). A give-up, silence or a lost board keeps all five. Our
  board's ACK of every compact DM addressed to us — opened or not — is booked as a nominal 8 B. The ✓✓ is
  `FrameClass.TICK`, so it never spends the last quarter of a window. `RETRANSMITS = 4`: firmware 2.8 makes
  `NUM_RELIABLE_UNICAST_ATTEMPTS = 5` attempts (`NextHopRouter.h:115`, v2.8.0 / v2.8.1), 2.7.26 made three, so a 2.7
  board is over-booked — the conservative error. The gap is `getRetransmissionMsec`'s (`RadioInterface.cpp:813-826`):
  `2·air + (2^CW + 2·CWmax + 2^((CWmax+CWmin)/2))·slot + 4.5 s`, `CWmin = 3`, `CWmax = 8`, the slot ≈ 44.5 ms at
  LongFast (`LoraAirtime.slotTimeMs`). The board trial measures the count.
- **Receive.** A `PRIVATE_APP` packet starting `0x11` is taken before the bound-channel filter, on the bound slot or
  index 0, only when addressed to our board, and whatever our own DM switch says (our board already acked it). The
  opener names the sender; a stranger, a failed seal and an out-of-window stamp are each counted and dropped.
- **Composer.** `LoraSizeHint.DM_BODY_BYTES` is 160 B: body plus the inline acks `inlineAcksFor` may spend out of it, in
  one packet at 228 (175 B of plaintext), measured with incompressible text in `CoordinationPlaneSizeBudgetTest`. A
  reply or an attachment leaves no room; the hint now names all three.

**What it costs.** At LongFast a 40-character DM is one 95-B packet, 1.13 s, and its ✓✓ one ~76-B packet, 1.0 s — 2.1 s
for the exchange against about 5 s before (two packets for the DM, one 221-B packet for its ✓✓) — plus the far board's ~0.5 s ACK each way. The worst case is five airings: 5.7 s for that DM, 10.6 s for a full 226-B one (a give-up about 46 s / 56 s after the send on a quiet band). A burst to a board that is off costs one send and its retries, not one
per DM. The plane no longer persists the probe window or the re-offer's hour (`dmProbes` / `reoffered`; an older blob
still loads).

**What it does not cover, and the traps.**

- *A destination's ACK says its radio took the packet.* Never read it as delivery to the phone or as presence; the
  re-probe exists because of that.
- *Mixed fleets.* A Stage-1 author gets no ✓✓ over LoRa from a Stage-2 recipient (the recipient's ✓✓ rides compact,
  which the author's build drops), and a Stage-2 author sends nothing compact to a peer whose profile lacks the
  capability. The capability is learned only through the signed profile.
- *Privacy.* `from`/`to` node numbers are cleartext on the air, and the static per-direction pairTag links packets.
- *Content ctls* (resets, seeds, intro `CTL_PROFILE`, scope config, transfer, call) no longer ride LoRa at all.
- *The want_ack ToRadio.* `PACKET_OVERHEAD` (24) never counted the stated `hop_limit`'s two bytes, so a full broadcast's
  write at MTU 255 is already two bytes past one ATT write (Android splits it as a long write). The direct cap takes
  `want_ack`'s two bytes off its own payload so it is no longer than a broadcast's; a full one is still that long.
- *Plan B, not built:* a PKI unicast on slot 0 (+12 B), the destination's key injected with `add_contact`, if a board
  trial shows a secondary-channel unicast is refused or not acked on some firmware. (Built 2026-10-30 as ADR
  2026-10.aajj: every compact DM is already a PKI unicast, and the key is taught on our own board's refusal.)

Tests: `LoraDirectPolicyTest`, `LoraDirectTransportTest` (over `FakeMeshtasticAir`, which now models a unicast reaching
only its destination, the board's ACK whatever the phone does, every retry as an airing, `MAX_RETRANSMIT` in real
seconds, implicit acks, a lost ACK, no outcome, asymmetric links, the replays and a Knit channel at another index),
`LoraReprobeTest`, `MeshtasticSessionTest` (the routing), `MeshtasticProtoTest` (the `want_ack` and `rx_time` vectors),
`CoordinationPlaneSizeBudgetTest.aDmAtTheHintBudgetIsOneCompactPacketAtTheMtuFloor`. Board trial owed on 2.8 before
release: (0) a `PRIVATE_APP` unicast on the Knit secondary arrives un-PKI'd and its destination acks it; (b) far board
off → `MAX_RETRANSMIT` → unreachable → cleared on hearing → delivered; (c) one packet per DM measured; and the retry
count read off the air.

## The crypto half

Drafted by Stage 2b (2026-10-29), proposed with the rest.

**The seal** (`mesh/crypto/LoraDirectSeal`, the `LoraDirectCrypto` the transport is handed). The key is the static
identity agreement `X25519(our identity key, their identity key)` — the pair secret the spool's pair scopes use
(`ScopeCrypto.pairSecret`). Every derivation is HKDF-SHA256 over it with a 32-byte zero salt and `info = label ‖
senderId ‖ recipientId`, the node ids as their 16 canonical bytes (#178): `knit/lora/v1/key` (32, the AES-256-GCM key
`kDir`), `knit/lora/v1/nonce` (32, a separate nonce key — `kDir` is never fed back in as IKM) and `knit/lora/v1/tag`
(4, the pairTag). All three are directional, so B→A differs from A→B and a reflected packet finds no tag. The nonce is
`HKDF(kNonce, frameId ‖ salt)[0..12]` with an 8-byte salt drawn fresh per seal, so a resend of one frame never reuses a
nonce. The AAD is the 35 header bytes and both node ids, sender first. `docs/SPOOL_PROTOCOL.md` C-3.5-5 is amended: the
pair secret keys the `knit/lora/v1/…` family too, every derivation labelled, and the identity DH key now authenticates
traffic as well as naming scopes. Known answers: `vectors/lora-direct-v1.json` (`LoraDirectVectorTest`); refusals:
`LoraDirectSealTest`. No forward secrecy on this leg (the maintainer's call): the ratchet still seals the signed copy.

**The trust boundary.** The compact door is never a `WireEnvelope`: nothing about it is verified, custodied, relayed
or deduped as a frame, so ADR 059's "one unsigned `WireEnvelope` shape" stands. The sender is never read off the
packet: the pairTag picks candidates out of an index over pinned peers — never ourselves (45763fb6) — and only a
candidate whose key authenticates the AEAD names the sender; colliding tags are all tried. The AEAD opens before any
branch acts. A `sentAt` older than custody's TTL (24 h) or more than `MAX_FUTURE_SKEW_MS` ahead of our own clock is
refused at open (`ClockRefused`, `loraDirectClockRefused`), which bounds a captured packet's replay. No failure on this
door — stranger, auth, clock, refused shape — ever feeds the ratchet's reset heuristic. The index is rebuilt off the
peer table only while the LoRa plane is switched on (`MeshManager.watchLoraPairs`), re-deriving only a changed key; a
peer pinned a moment ago is learned at offer time (`LoraDirectSeal.learn`). A key X25519 refuses is left out.

**KCI and deniability.** A static-static key means whoever steals one identity's DH key can forge compact DMs *to* that
identity from any of its contacts (key-compromise impersonation) and read every past and future compact DM of that
identity's pairs. Both parties hold the key, so a compact DM proves nothing to a third party (deniable), where the signed
copy's Ed25519 signature does. Accepted for one radio packet; the signed copy is unchanged.

**The capability.** `Protocol.CAP_LORA_DIRECT = 0x10000`, Android-only for now (`docs/WIRE_COMPAT.md`). Our profile
carries it only while the plane **and** its DM switch are on and the build is bound to read `0x11`
(`MeshManager.bindLoraDirect`); of the two options the 2a review left open we picked this one, not "let the ✓✓ ride
compact whatever the switch says": a peer with DMs off would otherwise draw compact DMs its board acks and never answer
compact, and the sender's re-probe would ask for ever. Our profile names its board (`loraNode`) only while the plane is
on. The bit is learned only through the signed cleartext profile — the sealed `CTL_PROFILE` never carries
capabilities — so a pinned pair that upgrades while only LoRa joins them learns it late, and one that downgrades keeps a
stale bit until its next profile reaches us; a stale bit costs a packet the far app drops, nothing more. The board map
(`MeshManager.loraBoardMap`) holds pinned peers naming a board; the ranking for a board two profiles name is the
amendment's below.

**Delivery** (`InboundPipeline.deliverLoraDirect`, the `LoraDirectInbox`). The plaintext decodes with the v3 compact
schema and must be supported; exactly three shapes are taken — a message, `CTL_RECEIPT`, `CTL_REACTION` — and any
`gk`/`sc`/`pr`/`xf`/`cl` payload or other `ctl` is dropped as `LORA_DIRECT_REFUSED`. A blocked sender gets nothing, not
even a ✓✓. The local `RelayEnvelope` takes the sender from the index and the recipient as us, and is never originated.
A receipt runs `applySealedReceipt` per id inside one write transaction, with no ratchet mutex: its
`recipientOf(ackId) == sender` guard holds unchanged, the sender being the authenticated pair peer (Q3). A reaction runs
`applySealedReaction` the same way, `noticeReaction` after the commit. A message goes through `deliverChat` —
moderation, the quote judge, the notification, the attachment's pull — with a persist that writes the row, its
`lora_direct_seen` mark and its inline acks in one transaction, then the ✓✓ through `acknowledge` on `DeliveryPlane.LoRa`
(the coalescer). Deliveries are serialised (`loraDirectLock`, never taken inside a transaction).

**The ratchet when the compact copy wins (Q1): the signed copy still runs its ratchet step.** The `lora_direct_seen`
table (DB v28; `frameId, peerId, arrivedAt, ratchetDone`; not backed up, ratchet state) is checked in
`decryptAndDeliver` *before* the exists-gate. A marked v2/v3 copy from the marking sender whose step has not run opens
through the same lock-free peek and committed re-open as `decryptAndDeliverV2`, and the commit writes only
`ratchetDone` and the inline acks the compact copy may have shed — no row, no moderation, no notification — then
`onPeerFrameOpened` runs (pz9g). A step already run, or a v1 copy, is acked as the gate acks. So the ratchet sees exactly
the sequence it would with no LoRa leg: no skipped-key residue for the delivered index, an X3DH init carried only by a
LoRa-delivered opening run is processed when its signed copy lands (the both-initiate race then resolves as ever), and
confirmation and the `pairwiseRoot` export stay on schedule. A ratchet-only open that fails is counted
`LORA_DIRECT_RATCHET_MISSED` and never feeds `onRatchetFailure`. Rejected alternatives: leaving the exists-gate as it was
(a skipped key per compact DM and, for a LoRa-only opening run, an unprocessed init), and opening the signed copy into
the row (a second write and notification). A signed copy that lands first toward a peer claiming the door is marked
with `ratchetDone` true; one that lands while its compact copy is being written keeps the compact row. The exists-gate's
re-ack stays (a compact copy after its signed copy is acked again): it is the compact ✓✓'s only retry.

**Deleted messages stay deleted (Q2).** The signed path's tombstone today is the ratchet's consumed key: a re-served
signed copy of a deleted DM fails as a duplicate. The compact path has no ratchet, so it refuses (and acks) any frame id
`lora_direct_seen` holds whether or not the row exists, and `LocalMessageDelete` never touches the table. Rows are swept
48 h after `arrivedAt` on our own clock, outlasting the 24 h compact window and the spool's 48 h. Receipts and reactions
keep no mark: both are idempotent or last-writer-wins.

**The offers** (`MeshManager.offerLoraDirect`). Beside `originateSigned`, never instead: `sendChat` (DM arm,
`DirectKind.MESSAGE`), the DM branch of `sendReaction` (`REACTION`), `originateDeliveryTick` and `sealDmReceipt` on the
LoRa plane (`RECEIPT`), and `flushPendingFor` for a DM that waited on its key. The content is `row.sealedContent()`
through the v3 layout — the signed copy's own, id and stamp included — with the inline acks the signed copy carried; it
sheds them when that is what fits one packet at the MTU floor (`LORA_DIRECT_PLAINTEXT_BYTES`, 175 B). A content with no
canonical compact form has no compact copy; one still too long is the plane's `loraDirectTooLong`. Only toward a pinned
profile carrying the bit, and only while `sentAt` is inside `LORA_DIRECT_SEND_WINDOW_MS` (the receiver's 24 h less an
hour). `LoraDirectSource` returns our unacked DMs inside the same window that fit one packet, newest first, with no
inline acks, asking the database at every call. A reset's re-seal offers nothing compact: it repairs the ratchet, which
the compact leg never rode. The coalesced ✓✓ leaves as compact ticks of at most `LORA_DIRECT_TICK_ACKS` (10) ids,
riders included — exactly 175 B, pinned by `CoordinationPlaneSizeBudgetTest.aCompactTickAtItsCapIsOnePacketAtTheMtuFloor`
— the first under the signed tick's id; the signed tick keeps `MAX_LORA_TICK_ACKS` (12).

**What leaves LoRa.** `MeshTransport.longRangeFanout`, `FanoutHint`, `FrameFanout.shouldLongRangeFanout`, the
`chatHint`/`hint` arguments, `FarPeerFrameSource`/`framesFor` and the composite's dispatch are deleted; `InboundPipeline`
no longer re-fans a DM-form frame. Every content ctl — resets, seeds and key replies, intro `CTL_PROFILE`, scope config,
transfer, call — travels custody, the spool and the links only. For a pair joined by LoRa alone that costs presentation
(no sealed profile update, no group keys) and the signed copies, so custody converges only when they meet by a link or
a relay.

**Air with real sizes.** A 40-character DM's compact plaintext is 44 B, its packet 95 B (≈ 1.13 s at LongFast); a ✓✓ of
one id is 21 B of plaintext, 72 B on the air (≈ 1.0 s), ten ids 175 B / 226 B; plus the far board's ≈ 8-B ACK each way.

**Residue.** Two copies of one DM landing within the same instant on the signed and compact paths can notify twice (the
row is single). A burst past one in flight and eight parked crosses the rest only on the re-probe or a link. The iOS
port neither sets the bit nor reads `0x11`.

Tests: `LoraDirectSealTest`, `LoraDirectVectorTest`, `InboundPipelineTest`'s compact-door cases, `MeshManagerTest`'s
compact-copy cases, `KnitDatabaseMigrationTest` (26 → 27), `CoordinationPlaneSizeBudgetTest`, and `LoraDirectLabTest`
(scenarios a–i), with `RoomTickPlanesLabTest`'s DM cases rewritten to end on a link. `MeshLab` binds every board node's
seams; `KNIT_LAB_BOARDS=1` gives every node a board, the sweep that ran `SessionLabTest`,
`CustodyLabTest.bothSidesSendWhileApartAndMerge` and `RestoreLabTest` with compact DMs in play.

### Amendment (2026-10-29, Stage 2b review): answers never start a session

`RestoreLabTest` under `KNIT_LAB_BOARDS=1` split a pair on two roots, both responders. A compact copy lands a DM's row
before its signed copy reaches the ratchet, so the ✓✓ (and a reaction) for it was sealed before the reader held the
author's session: as a fresh X3DH init with no session, or on the reader's own still-unanswered init. Every LoRa first
contact became a both-initiate race, and a race leaves ctls carrying the loser's init in custody. Re-served to a node that
has since restored and sent its flagged reset, such a frame wins `RatchetEngine.resolveRace` (no `prevRoot` after a wipe)
and the restored node adopts the dead era (crypto-reviewer's trace; seeds 2000, 2004).

- **The ctl-seal gate.** `RatchetSessions.sealAnswer` (and `sealCtl`) decides under the session lock: with no session,
  or only our own init unanswered, it returns `AnswerSeal.Declined` and persists nothing. It gates the coalesced DM ✓✓
  (`MeshManager.originateDeliveryTick` from `flushDmAcks`), `InboundPipeline.sealDmReceipt` on the LoRa plane and the
  re-ack of a marked signed copy (`loraDirectCopyTaken`), and `MeshManager.sealDmReaction`. A decline never falls back to
  a cleartext receipt or reaction (ADR 018): the ✓✓ goes as its compact ticks alone under a fresh frame id, the signed ✓✓
  is the re-ack the signed copy draws once its ratchet-only open lands; a ✓✓ declined there too (our own reset or init
  still unanswered) is held by id (`MeshManager.holdDeclinedTicks`, ≤ 64 per author) and sealed as one answer, with no
  compact copy, once the session's root moves or on a heal. A declined reaction airs compact and is held the same way
  (`HeldReactions`, newest per message). Both holds are in memory; a process death loses only the signed copy. A ✓✓
  sealed by any path drops its ids from the hold, so none is sealed twice. With no session the gate declines even when
  the ratchet-capable author's prekey will not decode — a cleartext receipt there would purge the DM's signed copy from
  every carrier before its ratchet step ran; only an author without the ratchet bit keeps the legacy receipt.
- **The compact ✓✓ carries the DM ids alone.** Room ticks riding the signed tick never air (rp3s).
- **The compact ✓✓ is the DM path's only.** `originateDeliveryTick` offers compact ticks only from `flushDmAcks`;
  `AckSync`'s group and commons escalations never reach `offerLoraTicks` (a room or group ✓✓ never airs, rp3s).
- **Every signed-first DM from a pinned peer is marked** in `lora_direct_seen`, whatever capability we hold for them: a
  stale profile must not let a re-aired compact copy resurrect a deleted row.
- **The board map's ranking.** A board two profiles claim goes to a contact — verified, an accepted conversation, or a
  DM we wrote in — before a stranger, and to the newer claim only within a rank; our own board is never anyone's, and a
  compact packet from our own board is ignored.

- **A re-delivery's re-seal is an answer too, bounded per era (meets ADR 2026-10.8hn6).** Drafted at the rebase onto
  main's 8hn6 (2026-10-29). Every signed-first v2 DM from a pinned peer is marked, so a re-served signed copy lands in
  `loraDirectCopyTaken`, not at the exists-gate: both call `acknowledge` with `reDelivery`, so 8hn6's ledger bounds both
  doors, and every re-delivery seals as an answer (`sealAnswer`): never a fresh init, and never under our own unanswered
  reset — re-rooted, marked or a restore's, any session with a reset stamp (`lastResetSentAt > 0`). Such a re-delivery
  is declined and held like any other answer, where 8hn6 alone re-sealed under our reset at once; its one receipt in the
  new era is sealed when the hold flushes (the root moves or a heal), or by the next re-delivery once the author
  answered. The exists-gate's unmarked re-delivery (a v1 copy, or a row from before the marks) may ride our own
  unanswered init with no reset stamp (`rideOwnFirstContact`): usually the one our first receipt minted for a v1 first
  contact, which the author already holds in custody, and declining there switched off 8hn6's rule 5 re-arm for a
  receipt the author custodied but never applied. The flag rides any such init, a race-kept winner's included (a v1
  first contact, then both sides initiate and we win, then a re-serve past the floor): nothing on the row tells the two
  apart, so the code is not narrowed, and the case is #189's (below). A marked copy's re-ack never rides our init: there it
  would be a race's winner, whose answers re-served to a restored loser are #189. `AnswerSeal.Sealed` carries the root
  tag its seal committed under, and `sealDmReceipt` files exactly that. A decline files nothing in the ledger, so a held
  ✓✓ can never be skipped as already answered; and a skip touches no hold. A held ✓✓ flushed by
  `MeshManager.flushHeldTicks` is the coalescer's batch shape and records nothing either (8hn6: "neither are the LoRa
  coalescer's batch"), so a later re-delivery off the board costs at most one more receipt, recorded then. Residue: a
  re-delivery with no session at all (a session forgotten since) is declined and waits for one, where 8hn6 started a
  fresh init; the hold is in memory, so a process death loses it and only the next re-delivery holds it again; and under
  our own reset a receipt the author custodied but never applied waits for the reset's answer. This rule rests on the
  answer gate, so it is Android's until a port has one; 8hn6's rule stands for a port without it. Regression:
  `InboundPipelineTest`'s `aReServedDmUnderOurOwnUnansweredResetIsHeldNeitherSealedNorSkipped`,
  `aReServedDmAfterAResetDrawsExactlyOneReceiptInTheNewEra` (now the author's adopted reset) and
  `aV1FirstContactsReceiptIsReSealedPastItsFloorUnderItsOwnInitButNeverUnderOurReset`;
  `RatchetSessionsSealTest.aReDeliverysAnswerRidesOurOwnFirstContactInitButNeverOurReset`; `SessionLabTest`'s
  `aRestoredRecipientHoldsABackFilledDmsReAckUntilItsResetIsAnswered` and
  `aRestoredRecipientThatRestartsBeforeTheAnswerHoldsTheNextBackFillsReAck`.

The residue is #189 (ADR 2026-10.j67x's "not covered", #184 its sibling). It has a second door since the rebase onto
8hn6: an unmarked exists-gate re-ack past its floor rides a race-kept winner's init (`rideOwnFirstContact`, the bullet
above), extending the strand by at most one receipt's custody life, and #189's fix must cover that door too. The
first door: the race winner's answer to the loser's init (`IntroSync.onPeerFrameOpened` → `sendIntroTo` → `sealDm`
under the unconfirmed winning session) still carries the winner's init, and re-served to a restored loser it wins `resolveRace` against the restored node's flagged reset.
`LoraDirectLabTest.aLoraFirstContactLeavesNoInitInAnyTickAndSurvivesARestore` shows it and is held under #189; it needs
only a both-initiate race and a restore inside custody's window, without LoRa.

### Amendment (2026-10-29, ADR 2026-10.4mjm): both #189 doors close at the loser

Rule C closes the #189 residue above, and it closes it at the restored loser, not at either door. The winner's intro
answer and the exists-gate re-ack riding its init (`rideOwnFirstContact`) still carry its init, and either can still
win `resolveRace` against the restored node's unanswered reset; the loser now flags that reset as its own init lost to
a race, and the winner's answer under the reset, sealed past the skew bound after the winning init, moves it back. The
swap does not depend on which door delivered the init. `LoraDirectLabTest.aLoraFirstContactLeavesNoInitInAnyTickAndSurvivesARestore`
runs again: it forces the intro ahead of the answer, requires the swap counted (`ratchetSwappedBack`), and narrows its
custody oracle to ✓✓s and reactions, the gate's claim (`LabNode.initBearingAnswersFrom`). Regressions:
`RatchetSessionsReclaimTest.theSecondDoorAReAckRidingTheWinnersInitIsReclaimedPastToo`, and
`RestoreLabTest.aRestoredRaceLoserWhoMeetsTheWinnersOldInitFirstMovesBackOntoItsReset` (board-less).

### Amendment (2026-10-29): our board's ACK is booked only when the firmware sends one

- **2026-10-29 (`want_ack` decode):** Our board's ACK is booked only when the firmware sent one, replacing "Our board's ACK of every compact DM addressed to us — opened or not — is booked as a nominal 8 B" in **Air**.
  - **Decode:** `MeshtasticProto` now decodes `MeshPacket.want_ack` (field 10) and `next_hop` (field 18) onto `ReceivedPacket` (`wantAck`, `hopLimit`, `nextHop`).
  - **Booking:** `LoraDirectPolicy.boardAcks` books the nominal 8 B for a compact DM addressed to us, opened or not, in two cases:
    - it asked for `want_ack`;
    - its sender's next-hop routing named our board (`next_hop` = our node number's last byte) with hops left.
  - **Firmware** 332b5b0a2, `ReliableRouter::sniffReceived` (`ReliableRouter.cpp:123-158`):
    - a decoded `want_ack` unicast to us with no `request_id` or `reply_id` is ACKed (137-139);
    - without `want_ack`, only the `next_hop` case draws a hop-zero ACK (156);
    - a unicast with neither draws none;
    - a duplicate the board re-ACKs without handing it to the phone (`NextHopRouter.cpp:163-170`) stays unbooked.
  - **Effect:** every compact DM a Knit build sends asks for `want_ack`, so between Knit builds the booking is unchanged. What stops is booking a foreign or forged `0x11` unicast that asked for no ACK.
  - **Guards:**
    - `MeshtasticProtoTest`: field 10 set, absent and false; field 18.
    - `LoraDirectPolicyTest.ourBoardAcksAUnicastThatAskedOrThatNamedUsItsNextHopWithHopsLeft`.
    - `LoraDirectTransportTest.ourBoardsAckIsBookedForEveryCompactDmAddressedToUsThatAskedForOneOpenedOrNot` and `aCompactDmThatAskedForNoAckBooksNothing`.

### Amendment (2026-10-30): firmware 2.8 PKI-encrypts every compact DM

Found 2026-10-29 by the lora-reviewer reading firmware 2.8.0 and 2.8.1, confirmed at `v2.8.1.8e6a88d`. Builds on, and
partly supersedes, "What changed" (the secondary keeping the DM off PKI), "Air" (the unsigned price, the 8-B ACK) and the
trial step (0).

- **Every compact DM is a PKI unicast.** `Router::wouldEncryptWithPKC` (`Router.cpp:1189-1212`; `:1163` at
  `v2.8.0.47db0e3`) never reads the channel index: it excludes only channels named `serial`/`gpio`, broadcasts, a
  licensed owner and the TRACEROUTE, NODEINFO, ROUTING and POSITION ports. So our `0x11` `PRIVATE_APP` unicast on the
  Knit secondary is PKI-encrypted, and the firmware never falls back to the channel key: with no key for the
  destination our own board refuses it (`PKI_SEND_FAIL_PUBLIC_KEY`, 39, `Router.cpp:1347-1355`) and nothing airs. That
  path, the `add_contact` that answers it and the destination's `PKI_UNKNOWN_PUBKEY` (35) are ADR 2026-10.aajj.
- **Sized for PKI, always.** The firmware refuses an encoded `Data` past 227 B under PKI (`Router.cpp:1349`), so one
  packet carries at most `MeshtasticProto.MAX_PAYLOAD` less `PKC_OVERHEAD_BYTES` (12): 219 B, against the 226–229 B the
  `want_ack` write allowed, which made a seal of 220–229 B a `TOO_LARGE` from our own board that never flew. The two
  limits do not stack (`want_ack` bounds the BLE write, PKI the firmware): one pure cap, `LoraDirectPolicy.payloadCap`
  = `min(MAX_PAYLOAD − 12, maxPayload − WANT_ACK_FIELD_BYTES)`, read by the transport's offer and by
  `MeshManager.LORA_DIRECT_PLAINTEXT_BYTES` (168 B, was 175). `LORA_DIRECT_TICK_ACKS` is 9 (ten ids are 175 B), pinned
  by `CoordinationPlaneSizeBudgetTest.aCompactTickAtItsCapIsOnePacketAtTheMtuFloor`; a ten-id tick would have been
  refused as too long and its DM re-probed every 4 h. `LoraSizeHint.DM_BODY_BYTES` stays 160: re-measured with `-i`, a
  160-B body is 164 B of plaintext, so the 12 B came out of the old margin (11 B → 4 B). The overhead is reserved
  always, never learned: every compact DM that flies on 2.8 is PKI. A board in ham mode is the exception — no PKI, the
  unicast channel-encrypted and signed by the firmware's rule (2.8.1 when it fits, `Router.cpp:1270`; 2.8.2 only under
  `BALANCED`/`STRICT`) — and is priced so off the board's own snapshot (`LoraAirtime.price`), never refused at setup.
- **Priced for PKI.** `OutboundFrame.price` (`LoraAirtime.price`, ADR 2026-09.mhs5's 2026-10-30 amendment) adds the 12 B
  to a compact DM at all three places that price one: the pacer's admission, `onTaken`'s booking of every airing, and
  `launchFlight`'s `airMs`, which sets the refund, the retransmit gap and the deadline — one price read once per send. One constant (`MeshtasticProto.PKC_OVERHEAD_BYTES`), shared with the auto-reply.
- **The ACK carries a proof, on index 0.** A 2.8.1 board that holds the sender's key — and one that decrypted a PKI DM
  does — appends `Routing.ack_proof` (`AckProof.cpp:133-146`, `ACK_PROOF_SIZE` 8, `CryptoEngine.h:31`): the receiver
  books `ACK_PAYLOAD_BYTES` = 8 + 10. The PKI DM is reported on the receiver's index 0, so its ACK goes channel-keyed on
  the receiver's primary (`ROUTING_APP` is never PKI'd). Two boards whose primaries share a name but not a PSK decode
  the DM and never the ACK: `MAX_RETRANSMIT`, the board marked unreachable, the DM delivered all the same — the
  re-probe's ✓✓ is what settles it.
- **Received on index 0.** A 2.8 board reports every compact DM PKI-decrypted on index 0 with `pki_encrypted`
  (`MeshPacket` field 17, now decoded onto `ReceivedPacket.pkiEncrypted`, read by nothing that decides); `onDirectPacket`
  already took index 0. Its radio now counts in `boardsHeard` too, but only once the seal opened (index 0 is the public
  slot; a stranger's packet there counts nobody).
- **The "refused" path narrows.** A Restored board still holds keys, decodes the PKI DM on index 0 and acks it, so
  `NO_CHANNEL` → `REFUSED` fires only from a board with no keys at all. `PKI_UNKNOWN_PUBKEY` from the destination is
  never `REFUSED` (ADR 2026-10.aajj).
- **The trial step (0) was wrong.** "A `PRIVATE_APP` unicast on the Knit secondary arrives un-PKI'd" is replaced by:
  (a) two 2.8.1 boards that know each other's keys — a maximum-length compact DM (219 B) and a 9-id ✓✓ arrive
  `pki_encrypted` on index 0 (`meshtastic --listen`), no `TOO_LARGE`, acked `byDestination`; (b) the receiver removed
  from the sender's board — a 39, counted, never silent (with ADR 2026-10.aajj: answered by an `add_contact`); (c) the
  sender removed from the receiver's board — a 35, our NodeInfo on the air, the DM delivered within a minute.

Regressions: `LoraDirectPolicyTest.theCompactCapIsTheFirmwaresPkiCheckUnderTheWantAckWrite` and
`anAckIsBookedWithItsProof`, `LoraPacePolicyTest.aUnicastIsPricedUnsignedAndABroadcastAtItsPortsCliff`,
`LoraDirectTransportTest.aSealPastThePkiCapIsRefusedAtTheOfferAndOneAtItFlies`,
`aDmIsPricedUnsignedAndTheReceiverBooksItsBoardsAck` and `aPkiDmOnIndexZeroIsDeliveredAndItsRadioCountsOnceItOpens`,
`MeshtasticProtoTest.aPacketsPkiEncryptedFlagDecodesAndAbsentIsFalse`, and `CoordinationPlaneSizeBudgetTest`'s two
compact-DM budgets. Not covered: decoding `MeshPacket.ack_proof_status` (field 23, so an `INVALID` proof never marks a
board reachable), a user turning on `use_aead` for the Knit secondary (invisible to Knit; peers without AEAD cannot
decode), and more implicit ACKs with three or more boards now that `LOCAL_ONLY` boards relay opaque PKI unicasts
(`20f7ab1be`).
