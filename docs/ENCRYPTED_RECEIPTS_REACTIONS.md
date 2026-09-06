# Encrypted receipts and reactions — sealed ctl frames (crypto scheme v2 addition)

Status: **implemented** · plan approved 2026-08-15 · ADR 018. Ships in the same unreleased v2 train
as the DM ratchet (`docs/FORWARD_SECRECY_RATCHET.md`, ADR 016) and the group sender-key form
(`docs/GROUP_FORWARD_SECRECY.md`, ADR 017): no new `EncEnvelope.v`, no new capability bit
(`CAP_RATCHET` covers all v2 forms), no DB change. This document is the normative spec;
`InboundPipeline` (ctl dispatch, `acknowledge`), `MeshManager` (`sealDeliveryTick`, `sealReaction`)
and `AckSync` are the reference implementation, `InboundPipelineTest`/`MeshManagerTest`/`AckSyncTest`
the executable anchors.

## 1. Why, and why this shape

Receipts and reactions were the last flooded frames walking the mesh cleartext (signed, unencrypted):

- `ReceiptContent { ackId }` — a DM receipt is broadcast-shaped on the wire (`recipientId == null`),
  floods mesh-wide, and every carrier parses the cleartext `ackId` to vaccine-purge custody. It
  leaks the delivery event and the recipient's activity timing to any observer, forever.
- `ReactionContent { messageId, emoji }` — flooded mesh-wide for **every** context, DMs and private
  groups included: non-members see who reacted with what to which message. The worst leak of the pair.

This is prereq 3 of the internet-relay plane (its relays hold frames at rest; shipping that layer
while the same frames walk the mesh naked is incoherent) and the roadmap "E2E hardening" item.

**Mechanism (the ADR 016/017 precedent):** both ride as `MessageContent.ctl` control frames inside
ordinary v2-sealed CHAT frames. A new frame type would lose custody on every deployed build
(`isCustodial` is a fixed list in `Wire.kt`); an unknown ctl value is a chain-advancing silent no-op
(pinned by test), which is exactly what makes new ctl values additive. On the wire a sealed receipt
or reaction is indistinguishable from conversation.

## 2. Wire form (additive; see docs/WIRE_COMPAT.md)

```
CTL_RECEIPT  = 5   MessageContent.ack: String?          // the acked frame id (single form)
                   MessageContent.acks: List<String>?   // batched form: a custody-escalated group
                                                        // tick acking every listed id (ADR 033)
CTL_REACTION = 6   MessageContent.rp: ReactionPayload?  // { messageId, emoji? }
ReactionPayload { messageId: String, emoji: String? }   // emoji null = retraction
```

`emoji` is any string; by convention one fully-qualified RGI emoji (the picker is the only emitter and
emits nothing else). Receivers refuse a blank or over-long value — more than `TextLimits.REACTION` (32
UTF-16 units) — by applying nothing and counting `DropReason.REACTION_REFUSED`; the value is never
truncated (that splits a sequence into tofu) and never read as a retraction (retraction is the explicit
`null`). Length only, never an emoji-class check, so a build predating a Unicode release still applies its
emoji; the sender applies the same predicate (`isValidReactionEmoji`) before writing its own row.

`MessageContent.VERSION` stays 1 (nullable additive fields, rule 1). `ReactionPayload` is
deliberately field-compatible with the cleartext `ReactionContent` (same names, same CBOR — one
codec for a port; golden vectors pin both forms). The DM form carries `CTL_RECEIPT`/`CTL_REACTION`;
the group form carries `CTL_REACTION` only (ticks are DM-addressed). A ctl payload is **never
v1-wrapped**: a pre-ratchet build would decrypt a v1 ctl, strip the unknown field
(`ignoreUnknownKeys`), and persist an empty message bubble — senders call `sealDm`/`sealGroup`
directly and fall back to the **legacy cleartext frame**, never to v1 (pinned by test).

**v3 (ADR 059).** Toward an author whose pinned profile carries `Protocol.CAP_CRYPTO_V3`, the same ctl rides
crypto scheme v3 (`docs/FORWARD_SECRECY_RATCHET.md` §5): the plaintext is the labeled `MessageContentV2`
layout — `ack`/`acks`/`rp.messageId` as raw 16-byte ids, a tick 21 B instead of 39, a twelve-ack batch
72 B lighter — and the nonce is derived. The ids are canonical-or-nothing: an id the compact codec cannot
round-trip makes the seal fall back to v2 (`MessageContent.sealBytes`). The live-link tick (§5) is where the
signature goes too.

Old builds: a sealed receipt/reaction is an ordinary v2 CHAT frame — `UNKNOWN_ENVELOPE_VERSION`
drop-locally-still-relay, custodied opaquely (`canCarry`'s `enc != null` holds). Ratchet-era builds
without these codes: decrypt, unknown ctl, silent no-op, chain advanced. Inbound **cleartext**
receipt/reaction frames stay accepted forever (`handleReceipt`/`handleReaction` untouched).

## 3. Delivery semantics

Control-frame contract unchanged: never persisted as a message, never notified, never acked — a
sealed receipt can't draw a receipt-for-a-receipt, and the exists-gate can't re-ack a ctl id.
Row effects commit **inside the ctl transaction** (the ratchet/chain advance and the tick/reaction
land atomically; a crash re-processes cleanly on re-serve):

- `CTL_RECEIPT` → `markReceived` for `ack` and/or every `acks` id — `distinct`, bounded at
  2 × the send-side batch cap (128), the forged-ack guard run **per id**:
  `recipientOf(id) == null || == sender`. The null arm is load-bearing: a group/broadcast message
  has no `recipientId`, and that IS the sealed group tick.
  Since DB v6 the same call also records **who** acked, in the local `message_receipts` table
  (`MessageReceiptRepository.record`, one transaction with the tick) — that is what lets "Message info"
  name the members a group send has reached and the ones it hasn't (ADR 036). Nothing about it is a wire
  change: the acker was always the receipt's authenticated `senderId`, and the tick still means "≥1
  recipient received it". The one asymmetry to preserve: the **row** is gated on roster membership
  (`InboundPipeline.ackerFor`) because the null arm would otherwise let any signed node write itself into
  that list; the **tick** is not, and must never inherit that gate.
- A **plain** sealed DM chat may carry `acks` too — the inline form (ADR 054, gated on the author having seen
  our `CAP_INLINE_ACK`): applied per id under the same guard, in the same commit as the message row, once per
  frame (the exists-gate stops a re-delivery before the decrypt, so nothing re-applies).
- `CTL_REACTION` → `ReactionRepository.apply(messageId, sender, emoji, frame.sentAt)` — the same
  table and the same LWW clock as the cleartext path, so mixed-form retract/replace races converge
  regardless of which form each emit rode. Orphan-permissive (target may not have arrived; the 24 h
  reaper bounds junk). Group-form sender authenticity is the chain itself (an adopted seed implies
  roster membership at adoption); a departed member can still seal reactions under the draining
  chain for ≤48 h — the same window as their in-flight chats, accepted. A refused emoji (§2) is a
  chain-advancing no-op exactly like an unknown ctl code: consumed, counted, nothing applied.

## 4. The vaccine-purge retirement (the structural trade)

Stated as loudly as ADR 016's "no cumulative root chain" and ADR 017's availability inversion:
**a carrier cannot parse what it cannot read, so sealed receipts never vaccine-purge. Nobody purges
— the delivered DM and its sealed receipt age out of custody on the frame-global 24 h TTL uniformly
on every node, exactly like group/broadcast custody always has.**

Convergence (ADR 006) holds because the custody rule keys on the receipt's **form** — a property of
the frame bytes, identical at every observer:

| Receipt form | Old build | Ratchet-era lab build | This build |
|---|---|---|---|
| cleartext | parses, purges + tombstones | same | same (path untouched) |
| sealed | opaque v2 chat: relays + custodies, no purge | decrypts, unknown ctl no-op, no purge | `markReceived` only, no purge |

Two composition rules keep the populations digest-convergent:

1. **The recipient custodies its own inbound DMs** (the carry gate's `isForMe` exclusion is gone).
   Without this, every carrier's digest holds a delivered frame the recipient's digest never folds —
   endless re-offers, and the exists-gate would re-ack each one (~a fresh sealed receipt per SeenSet
   lapse, per DM, for 24 h). With it, digests match and a re-serve means genuine divergence (e.g.
   quota eviction), self-healed by the re-custody that precedes dispatch.
2. **A cleartext ack self-vaccinates**: after originating the legacy receipt, the recipient runs
   `ForwardSync.onAck` on itself — its own fresh custody row follows the identical rule every
   carrier applies to that receipt, and the ack tombstone refuses re-plants.

Storage cost, accepted: a delivered DM plus its sealed receipt ride custody to TTL (two frames where
the purge left zero), bounded by the existing quotas (1000 global / 200 per sender / per-group 200).

## 5. Send-side forms (chosen by the sender of the receipt/reaction)

| Context | Condition | Receipt | Reaction |
|---|---|---|---|
| DM, author/peer capable | pinned bundle + `CAP_RATCHET` (+ prekey/session for the seal) | sealed ctl DM, `relay = true`, flooded + custodied, `sentAt` stamped (custody derives expiry from it) | sealed ctl DM, `relay = true` |
| DM **delivered over the LoRa plane**, author capable (ADR 054) | as above, `DeliveryPlane.LoRa` | held ≤ 45 s in `DmAckCoalescer` (re-deliveries fold in), then ONE sealed ctl DM (`ack`/`acks`, ≤ 12 ids) originated `relay = true`, hinted `TICK`; a failed seal falls back per id to the cleartext receipt | as above |
| DM delivered over LoRa, and we reply within the hold | author's profile carries `CAP_INLINE_ACK` | up to 4 ids ride **inline** as `acks` on the plain sealed reply (v2 arm only; 23 B each reserved out of the LoRa body budget); no standalone tick | — |
| DM, incapable / seal failed | — | cleartext receipt (still purges everywhere, incl. self-vaccinate) | cleartext reaction |
| Group message delivered | author capable, live-linked | sealed single-ack ctl DM over the link: `relay = false`, sealed **once**, never custodied — and **unsigned** when it sealed v3 (ADR 059: the AEAD, with the header bound into its AAD, is the authenticator; ~222 B, one packet on every fast plane); signed toward a v2 author | — |
| Group message delivered | author capable, absent | acks batch per author (≤64, 45 s debounce), then ONE sealed ctl DM (`acks`) **originated `relay = true`** — flooded + custodied + spool-eligible (ADR 033) | — |
| Group message delivered | author incapable | cleartext tick (fresh id per retry, coordination-plane capable) | — |
| Group reaction | every member ratchet-eligible | — | sealed group form via `sealGroup` (all-or-nothing; may mint + distribute a seed, like any group send) |
| Group reaction | any member ineligible | — | cleartext reaction |
| Broadcast room | always | cleartext tick (`ackBlockedRoomChat` unchanged, ADR 010) | cleartext reaction |

The sealed tick's two deliberate constraints: **seal-once-resend-verbatim** (sealing consumes a DM
chain key; per-retry re-sealing at the 15-min heartbeat would burn epochs and starve real DMs out of
the receiver's ≤200/epoch skipped-key budget — a duplicate is router-deduped inside the SeenSet
window and a benign `RATCHET_DUPLICATE` beyond), and **no cleartext downgrade when it doesn't fit**
(form must not become an on-path observable of link state). A sealed tick outgrows a *single* ~255 B
coordination-plane message; toward a `CAP_FAST_COMPACT` author the Wi-Fi Aware transport now carries
it as ≤ 2 compact fragments (`mesh/link/FastFrameCodec` — still best-effort, the owed-entry retry
loop stays the reliability mechanism), while toward a legacy author `fastSend` still no-ops and the
tick waits for a live link.

**The retry cadence is a backoff, not a heartbeat** (ADR 053). Seal-once-resend-verbatim means the
sealed tick re-sends *one frame id* for the entry's whole 24 h life, and the router's SeenSet only
suppresses a repeat for 10 minutes while the heartbeat runs every 15 — so every flat retry cleared the
window and landed on a consumed ratchet chain index, ~96 `RATCHET_DUPLICATE` drops at the author per
stuck tick. A sealed owed entry now doubles from one heartbeat (15 m, 30 m, 1 h, 2 h, 4 h, then an 8 h
ceiling), holding the same horizon at ~8 re-sends. A live link overrides the schedule — it is the
reliable path home the backoff is waiting for. The cleartext form is exempt: rebuilt with a fresh id per
attempt, it costs the author a dedup rather than a decrypt.

The escalated batch (ADR 033) adds three rules of its own: **batches never ride the coordination
plane** (a 16-ack batch already outgrows the ≤2-fragment compact budget — pinned by
`CoordinationPlaneSizeBudgetTest` — so escalation goes through `originateSigned`, structurally never
`fastSend`); **escalated ids are remembered** (a done-but-remembered ledger absorbs the exists-gate's
re-ack on every custody re-serve, so nothing re-seals; a process restart forgets it, and the one
duplicate custodied tick a re-serve can then mint is absorbed idempotently — the DM-receipt
precedent); and **a failed flush falls back** (author unpinned between owe and flush → the ids
re-materialize as per-id cleartext owed entries with their original timestamps). An author who links
during the debounce gets the whole batch over the live link instead (`relay = false`, no custody
rows). Cleartext ticks and the broadcast room never escalate: a cleartext receipt in custody would
re-leak the delivery event this scheme sealed away, and the room is the ambient, shorter-lived class
(`owe(escalatable = false)`).

A room tick toward an **absent** sealed-capable author instead **rides** (ADR 2026-09.aa27): the bare id
waits in `AckSync`'s ride hold for a frame this device is going to seal toward that author anyway — an
outbound DM's inline acks (§ `CAP_INLINE_ACK`, ADR 054) or the coalesced `CTL_RECEIPT` that `flushDmAcks`
originates — so it crosses for no frame and no custody row of its own. It seals nothing up front, which is
what the old form spent a chain key on before re-sending those bytes on a backoff to nobody; a live link
still ends the wait, as one tick covering the batch. The receiving half is unchanged: `applySealedReceipt`
admits an id whose `recipientOf` is null (a broadcast post) and `ackerFor` has a `NEARBY` arm. Counted as
`receiptsRidden`. An acker that never sends that author anything still ages out silently, so the room's
details list stays "everyone we heard back from", never a census.

## 6. Blocked-sender posture (ADR 010)

Outbound: ticks and seeds to blocked authors still seal and send — withholding would reveal the
block. Inbound: a blocked peer's sealed ctl dies at the chat blocked gate, which diverges from their
cleartext receipt (accepted forever, no blocked gate) — a version-dependent asymmetry with no
observable tell (nothing is emitted either way; only the blocker's own tick display differs).
Residual, pre-existing class: a blocked member's sealed group *reaction* still draws a delivery tick
(`ackBlockedRoomChat` runs pre-decrypt; we hold no chain for blocked members, their seed DMs die at
the gate) — exactly as their undecryptable group chats already do since the group form shipped.

## 7. Security claim

- A mesh observer no longer learns which DM was delivered when, the recipient's ack timing, or any
  reaction's reactor/emoji/target in DM and group contexts — all of it is chat-shaped v2 ciphertext.
  Traffic analysis still sees a recipient-originated v2 chat frame shortly after a DM's delivery
  (timing correlation is out of scope, as in v2's §9).
- Broadcast-room receipts/reactions stay cleartext by design (the room itself is plaintext).
- Forward secrecy of the sealed forms is the carrying session's (v2 epoch granularity / sender-key
  epoch granularity). Nothing here adds key material or retention beyond the carrying scheme's.
- The v1-fallback residual (cleartext receipts/reactions toward incapable peers/groups) shrinks as
  capability floods; `receiptsSealedFallback`/`reactionsSealedFallback` count it (Diagnostics).

**The unsigned live-link tick (ADR 059).** Its authenticity rests on the pairwise ratchet AEAD alone: only
the two session parties hold the message key, the associated data binds `id|sender|sentAt|recipient` *and*
the ratchet header, and X3DH binds the initiator's identity key, so neither a forged init nor a re-labelled
capture opens. What a forger can cost the recipient is one failed open (≈ 3-5× an Ed25519 verify, nothing
persisted) — never a session reset (unsigned failures are kept out of the reset heuristic), never a
receipt (the exists-gate is bypassed for unsigned frames), never a delivered message (only a `CTL_RECEIPT`
may pass the door, refused before commit otherwise). Non-repudiation is not traded away for anything a
human reads: the frame that goes unsigned is the receipt, and the signed, custodied forms of every other
receipt and reaction are untouched.

## 8. Constants

| Constant | Value | Tied to |
|---|---|---|
| `CTL_RECEIPT` / `CTL_REACTION` | 5 / 6 | `MessageContent.ctl` registry (append-only) |
| `TextLimits.REACTION` | 32 UTF-16 units | send + receive cap on a reaction emoji, both forms; ~2× the longest RGI sequence (15); bounds a sealed v3 reaction at ≤ ~290 B transcoded — two LoRa packets, never three |
| `DropReason.REACTION_REFUSED` | counter | an inbound reaction whose emoji is blank or over the cap: nothing applied, frame still custodied/relayed, chain still advanced |
| sealed receipt custody TTL | 24 h via stamped `sentAt` | frame-global custody expiry (ADR 006; the e11aa89 lesson) |
| tick seal budget | 1 chain key per owed tick / per escalated batch | AckSync seal-once cache; ≤500 owed entries / 24 h |
| `TICK_BATCH_DEBOUNCE_MS` | 45 s | how long an absent author's acks accumulate before escalating (heal is the backstop) |
| `MAX_BATCH_ACKS` | 64 | ids per escalated tick (overflow flushes early); receiver applies ≤ 2× (128) |
| `DmAckCoalescer.HOLD_MS` | 45 s | how long a LoRa-delivered DM's receipt waits for company (ADR 054; `heal` is the backstop) |
| `DmAckCoalescer.MAX_LORA_TICK_ACKS` | 12 | ids per coalesced DM tick — pinned to fit 3 LoRa packets at the ESP32 cap |
| `MAX_INLINE_ACKS` / `INLINE_ACK_BYTES` | 4 / 23 B | ids one reply carries inline, and what each costs out of the composer's LoRa body budget |
| `Protocol.CAP_INLINE_ACK` | `0x40` | the receiver applies `acks` on a plain sealed DM (append-only bit) |
| `Protocol.CAP_CRYPTO_V3` | `0x100` | the receiver opens crypto scheme v3 and accepts the unsigned live-link tick (ADR 059; profile-only, above the BLE advert's 8 bits) |
| pending / escalated ledgers | ≤500 ids / ≤1000 ids, 24 h | in-memory, evict-oldest; loss = one benign duplicate |
| ack tombstone (cleartext era) | 24 h | unchanged `ForwardSync` |
