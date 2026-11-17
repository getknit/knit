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
sealed receipt can't draw a receipt-for-a-receipt, and the exists-gate can't re-ack a ctl id. One
exception to "never notified" since ADR 2026-10.59fu: someone's reaction to a message this phone wrote
notifies, as a line in that thread's notification, in the **post-commit** half and only when
`ReactionRepository.apply` reports the reactor's visible emoji changed (so a re-delivery never notifies
twice); a retraction withdraws the line. It is still never a message row and never acked.
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
  regardless of which form each emit rode. The clock is bounded to `now + Protocol.MAX_FUTURE_SKEW_MS`
  on both paths before it is stored (the sender picks it; raw, one far-future stamp would outrank every
  later reaction on that message for every recipient, the sender's own retraction included).
  Orphan-permissive (target may not have arrived; the 24 h reaper bounds junk). Group-form sender
  authenticity is the chain itself (an adopted seed implies
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
   Without this, every carrier's digest holds a delivered frame the recipient's digest never folds,
   and the mesh re-offers it endlessly. With it, digests match, and a re-delivery takes genuine
   divergence: a carrier whose quota kept a DM ours evicted, or a back-fill fed by two holders that
   relays one holder's copy to us past our seen window (#176). Each such copy used to draw a fresh
   sealed receipt per SeenSet lapse, per DM, for 24 h. Its re-ack is now bounded per era (§5, ADR
   2026-10.8hn6).
2. **A cleartext ack self-vaccinates**: after originating the legacy receipt, the recipient runs
   `ForwardSync.onAck` on itself — its own fresh custody row follows the identical rule every
   carrier applies to that receipt, and the ack tombstone refuses re-plants.

Storage cost, accepted: a delivered DM plus its sealed receipt ride custody to TTL (two frames where
the purge left zero), bounded by the existing quotas (1000 global / 200 per sender / per-group 200).

## 5. Send-side forms (chosen by the sender of the receipt/reaction)

| Context | Condition | Receipt | Reaction |
|---|---|---|---|
| DM, author/peer capable | pinned bundle + `CAP_RATCHET` (+ prekey/session for the seal) | sealed ctl DM, `relay = true`, flooded + custodied, `sentAt` stamped (custody derives expiry from it) | sealed ctl DM, `relay = true` |
| DM **re-delivered** (row already held, the exists-gate), author capable, not over LoRa | we sealed a receipt for this id under the session root held now, it is live in our own custody, and it is younger than its re-arm floor (15 min, doubling per same-era re-seal to 4 h) | **nothing new**: the held receipt answers it. Otherwise one sealed ctl DM as above, filed with the root its own seal committed under (ADR 2026-10.8hn6). Over LoRa every copy takes the row below, which on Android asks the same rule at its flush | as above |
| DM **delivered over the LoRa plane**, author capable (ADR 054) | as above, `DeliveryPlane.LoRa` | held ≤ 45 s in `DmAckCoalescer` (re-deliveries fold in), then ONE sealed ctl DM (`ack`/`acks`, ≤ 12 ids) originated `relay = true`, hinted `TICK`; a failed seal falls back per id to the cleartext receipt. Android only (a port with no compact door has no such row, ADR 2026-10.8hn6's 2026-11-06 amendment): the ctl DM covers only the ids the row above does not find answered, nothing is originated when none is left, and every id still gets its compact tick | as above |
| DM delivered over LoRa, and we reply within the hold | author's profile carries `CAP_INLINE_ACK` | up to 4 ids ride **inline** as `acks` on the plain sealed reply (v2 arm only; 23 B each reserved out of the LoRa body budget); no standalone tick | — |
| DM, incapable / seal failed | — | cleartext receipt (still purges everywhere, incl. self-vaccinate) | cleartext reaction |
| Group message delivered | author capable, live-linked | sealed single-ack ctl DM over the link: `relay = false`, sealed **once**, never custodied — and **unsigned** when it sealed v3 (ADR 059: the AEAD, with the header bound into its AAD, is the authenticator; ~222 B, one packet on every fast plane); signed toward a v2 author | — |
| Group message delivered | author capable, absent | acks batch per author (≤64, 45 s debounce), then ONE sealed ctl DM (`acks`) **originated `relay = true`** — flooded + custodied + spool-eligible (ADR 033) | — |
| Group message delivered | author incapable | cleartext tick (fresh id per retry, coordination-plane capable) | — |
| Group reaction | every member ratchet-eligible | — | sealed group form via `sealGroup` (all-or-nothing; may mint + distribute a seed, like any group send) |
| Group reaction | any member ineligible | — | cleartext reaction |
| Broadcast room | author capable, live-linked | sealed single-ack ctl DM over the link, `relay = false`, never custodied (the group row's form) | cleartext reaction |
| Broadcast room | author capable, absent | the bare id waits in `AckSync`'s **ride hold** ≤ `RIDE_HOLD_MS` (60 s) for a frame already sealed toward the author — an outbound DM's inline acks, the coalesced DM tick, the instant DM receipt, an escalated group tick (ADR 2026-09.aa27 / y5f3); riders on a LoRa-bound frame capped at `MAX_LORA_TICK_ACKS` in all | — |
| Broadcast room | …and nothing rode | at the deadline, sealed **once** and routed: author seen on a connected spool within `SPOOL_COVER_MS` → **signed** `relay = false` tick pushed straight into the pair's scope(s) (`ScopeSync.pushDirect`, no custody row here); else author on a fast plane → the sealed tick `fastSend` (LoRa TARGETED, one packet; retried as one owed entry on the backoff, dropped on a link); else still held (ADR 2026-09.y5f3) | — |
| Broadcast room | author incapable | cleartext tick (`ackBlockedRoomChat` unchanged, ADR 010) | cleartext reaction |

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
duplicate custodied tick a re-serve can then mint is absorbed idempotently; the DM receipt's own
memo, `DmReAckLedger`, makes the same trade, ADR 2026-10.8hn6); and **a failed flush falls back** (author unpinned between owe and flush → the ids
re-materialize as per-id cleartext owed entries with their original timestamps). An author who links
during the debounce gets the whole batch over the live link instead (`relay = false`, no custody
rows). Cleartext ticks and the broadcast room never escalate: a cleartext receipt in custody would
re-leak the delivery event this scheme sealed away, and the room is the ambient, shorter-lived class
(`owe(escalatable = false)`).

A room tick toward an **absent** sealed-capable author instead **rides** (ADR 2026-09.aa27): the bare id
waits in `AckSync`'s ride hold for a frame this device is going to seal toward that author anyway — an
outbound DM's inline acks (§ `CAP_INLINE_ACK`, ADR 054), the coalesced `CTL_RECEIPT` that `flushDmAcks`
originates, the instant receipt `sealDmReceipt` sends for a DM off any other plane, or an escalated group
tick (the last two since ADR 2026-09.y5f3, which found the field case where every frame to the author was
one of them) — so it crosses for no frame and no custody row of its own. It seals nothing up front, which is
what the old form spent a chain key on before re-sending those bytes on a backoff to nobody; a live link
still ends the wait, as one tick covering the batch. The receiving half is unchanged: `applySealedReceipt`
admits an id whose `recipientOf` is null (a broadcast post) and `ackerFor` has a `NEARBY` arm. Counted as
`receiptsRidden`.

The hold has a **deadline** (ADR 2026-09.y5f3): `RIDE_HOLD_MS` = 60 s after the oldest id — longer than
both 45 s holds, so a receipt sealed in the same minute carries it first. A ride nobody took is then sealed
**once** and sent the cheapest way there is. An author a connected spool heard from within `SPOOL_COVER_MS`
(15 min) gets a **signed** `relay = false` tick pushed straight into the pair's DM scope(s)
(`ScopeSync.pushDirect`, spec §9.4 C-9.4-3): signed because the scope seal takes the 64-byte signature, so
this is the one place the ADR 059 unsigned form cannot go; no custody row on the acker, no frame on the
radios, counted `receiptsSpooled`. Otherwise an author a fast plane currently reaches gets the sealed tick
over `fastSend` — LoRa's targeted path, one packet — kept as one owed entry on the ordinary backoff and
dropped the moment a link carries it. Otherwise it keeps waiting: a link, a sighting or a spool newcomer
re-runs the deadline, and no chain key is ever spent on nobody. The residual is an author reachable only
through a relay with no board and no spool, so the room's details list stays "everyone we heard back
from", never a census.

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
| `AckSync.RIDE_HOLD_MS` | 60 s | how long a room tick waits for a ride before it is sealed and sent by itself — longer than both 45 s holds on purpose (ADR 2026-09.y5f3) |
| `SPOOL_COVER_MS` | 15 min | how recently a connected spool must have heard from a peer for the mesh to route on it: the ride deadline's first route, and the LoRa plane's Internet cover on every DM-form frame |
| `MAX_BATCH_ACKS` | 64 | ids per escalated tick (overflow flushes early); receiver applies ≤ 2× (128) |
| `DmAckCoalescer.HOLD_MS` | 45 s | how long a LoRa-delivered DM's receipt waits for company (ADR 054; `heal` is the backstop) |
| `DmAckCoalescer.MAX_LORA_TICK_ACKS` | 12 | ids per coalesced DM tick — pinned to fit 3 LoRa packets at the ESP32 cap |
| `MAX_INLINE_ACKS` / `INLINE_ACK_BYTES` | 4 / 23 B | ids one reply carries inline, and what each costs out of the composer's LoRa body budget |
| `Protocol.CAP_INLINE_ACK` | `0x40` | the receiver applies `acks` on a plain sealed DM (append-only bit) |
| `Protocol.CAP_CRYPTO_V3` | `0x100` | the receiver opens crypto scheme v3 and accepts the unsigned live-link tick (ADR 059; profile-only, above the BLE advert's 8 bits) |
| pending / escalated ledgers | ≤500 ids / ≤1000 ids, 24 h | in-memory, evict-oldest; loss = one benign duplicate |
| ack tombstone (cleartext era) | 24 h | unchanged `ForwardSync` |
| `MessageContent.rm` / compact label 20 | `Long?` | read bitmask over a receipt's acked ids (§9); absent toward an incapable author |
| `Protocol.CAP_READ_RECEIPTS` | `0x200000` | the receiver reads `rm`; always advertised, never the setting (§9) |
| `ReadSync.LINKED_DEADLINE_MS` | 45 s | how long an owed read waits for a ride toward a linked author before its own frame |
| `ReadSync.QUICK_DELAY_MS` | 1 s | how long after the first read of a burst the best-effort quick copy goes to a near author |
| `ReadSync.ABSENT_BASE_MS` / `ABSENT_CAP_MS` | 15 min / 8 h | an absent author's deadline, doubling per standalone frame, reset by a ride or a link |
| `ReadSync.DAILY_BUDGET` | 50 per rolling 24 h | standalone custodied read frames per reader (`DEFAULT_MAX_PER_SENDER / 4`) |
| `read_marks` owed / told TTL | 24 h / 48 h | the reader's durable outbox; told marks kept so a later receipt can carry the bit again |

## 9. Read receipts (ADR 2026-11.z6jw)

A read is a delivery receipt that also says "and read". It rides the same sealed `CTL_RECEIPT` and the same inline
acks, as one additive field, so it costs no frame where a ✓✓ is going anyway and no LoRa packet ever.

**Wire.** `MessageContent.rm: Long?` (named `"rm"`, compact label 20). Bit i set means the sender has read id i of the
frame's list as sent: `listOfNotNull(ack) + acks` on a `CTL_RECEIPT`, `acks` alone on a plain DM (which never sets
`ack`). Bits at or past the list size are ignored, so the sender spells the mask at a width that depends on the list's
length `n` alone (`ReadMask.encode`): for `n ≤ 4` the shorter of `m` and `m or (-1 shl n)`, every value one CBOR byte;
for `5 ≤ n ≤ 62` `m or (1 shl n)`, the first bit past the list set, so the value lies in `[2^n, 2^(n+1))` (2 B for
5–7 ids, 3 B for 8–15, 5 B for 16–31, 9 B for 32–62); for 63 or 64 ids `m` as it is, the one residual. A frame with any read bit holds at most 64 ids. Toward an author whose pinned profile carries
`Protocol.CAP_READ_RECEIPTS` and `CAP_RATCHET`, every `CTL_RECEIPT` and every plain DM with inline acks carries `rm`,
at that fixed width, nothing read or the setting off included, so a frame's length never tells. Toward any other author `rm` is
absent and the bytes are unchanged. Nothing read-related ever rides a cleartext receipt.

**Receiver.** Every arm that applies acked ids (the signed `CTL_RECEIPT`, the LoRa compact arm, inline acks) reads the
mask before `distinct` and passes `read` per id to `MessageReceiptRepository.record`, which stamps
`message_receipts.readAt` (our clock, never earlier than the row's `notedAt`, filled only while null). A read lands
only on our own message in a DM or a private group, and only for an attributable acker (`ackerFor`); delivery is
applied exactly as before. An old build skips the field and applies the ids as a ✓✓.

**Reader.** The UI reports ids the person saw (`MeshController.onMessagesRead`): rows half on screen for 600 ms in a
foreground chat, or what Mark read clears. The mesh keeps an id only while the setting is on, for someone else's row
in a DM or a private group whose author reads `rm`, and writes it to the durable `read_marks` outbox (owed until
told). `ReadSync` then:

- sets bits for free on every receipt and inline-ack reply sealed toward that author (any held mark, told or not);
- adds owed ids as riders on the signed, `relay = true` copies and on the y5f3 spool push, never on a compact copy and
  never on a live-link `relay = false` tick;
- otherwise, at the author's deadline (45 s while linked, 15 min doubling to 8 h while absent), pushes one frame into
  the author's spool scope if a spool covers them, else originates one sealed, answer-only `CTL_RECEIPT`
  `relay = true`, custodied on the 24 h TTL, within a budget of 50 such frames a day per reader;
- while the author is near (a live link, or a short-range fast plane that hears them), also sends the owed reads not
  yet sent this way once, 1 s after the first read of a burst, as the same sealed answer-only `CTL_RECEIPT`
  `relay = false` (unsigned when it sealed v3, ADR 059's tick) through `fastSend`: one hop, no custody row, no spool,
  never LoRa. Best effort, so it tells nothing: the deadline's frame above still goes unless a ride tells first. It
  never releases a held ✓✓, and it re-reads the block list before sealing. What it costs is cover: a read near its
  author no longer hides in the next ✓✓, so anyone in radio range who can read the envelope sees a receipt for
  already-acked ids about a second after the read (timing, out of scope per §7);
- marks a read **told** only after a `relay = true` frame carrying it is originated, or the spool push returns
  `pushed`. A seal, a live-link send, a compact LoRa bit, an owed entry, a decline and every cleartext fallback leave
  it owed. A refusal is never re-sealed;
- owes again, on a root change toward the author, the reads told under another root in the last 24 h;
- never claims or records a read in `DmReAckLedger`, `AckSync`'s ledgers or `DmAckCoalescer` (§5's ledgers key on the
  message id alone and would swallow it).

**LoRa.** `ReadSync` never offers anything to the compact door. The coalesced ✓✓ (`offerLoraTicks`, each chunk masked
over its own list) and a reply's compact copy carry bits for ids already read: at most 4 bytes on a 9-id tick that
has 10 to spare, so the packet count is unchanged. The inline acks keep their 23-byte reservation, and no byte is
added to it. A compact bit is best effort and never tells. Groups never reach LoRa.

**Setting.** Settings › Chats › Read receipts, on by default, reciprocal: off drops every owed read, zeroes every
mask, and hides every read in the UI while incoming reads are still recorded. On sends nothing retroactively.

