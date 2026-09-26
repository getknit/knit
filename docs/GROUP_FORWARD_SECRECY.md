# Forward secrecy for groups — the sender-key ratchet (crypto scheme v2, group form)

Status: **implemented** · plan approved 2026-08-14 · landed in phases (roster integrity → crypto core →
wire → schema → receive → send/distribution → hardening → observability). This document is the
normative spec for the group crypto scheme; `mesh/crypto/ratchet/GroupRatchet*` is the reference
implementation and `GroupRatchetCryptoTest`/`GroupRatchetEngineTest` are the executable anchors (an
iOS/CryptoKit port implements this file, not the Kotlin). The DM scheme it builds on is
`docs/FORWARD_SECRECY_RATCHET.md` (v2, ADR 016).

## 1. Why, and why this shape

v1 group messages (per-message random AES-256-GCM key, HPKE-wrapped once per member —
`docs/ARCHITECTURE.md` §crypto) have no forward secrecy, and a member whose key isn't pinned at send
time is silently skipped with no retransmit (the roadmap's "group key-gap retransmit"). The planned
internet relay ("spool") plane makes both fatal: relays hold sealed frames at rest, and group scopes
need a key state that rotates on membership change.

The roadmap named the fork — sender-key / pairwise fan-out / MLS-lite — and the adversarial design
review resolved it:

- **MLS-lite (any shared, coordinated group epoch) is unimplementable here.** Shared epochs require
  processing key-change commits in order; this mesh guarantees permanent mid-chain holes (custody
  evicts oldest-by-`(sentAt, id)` under per-sender and per-group quotas) and has no ordering
  anywhere. ADR 016 rejected a *pairwise* cumulative root chain for exactly this; a group-shared
  sequenced state is that wedge times eight parties.
- **Pairwise fan-out loses twice.** N-separate-DMs gives the "same" message N frame ids, breaking
  id-keyed custody dedup, receipts, and reactions. One-frame-with-N-ratchet-wraps entangles every
  group message with N DM session lifecycles (races, resets, replacements), consumes the DM
  skipped-key budget, and still loses wraps when a member's DM session is replaced — plus ~500 B of
  per-member wraps per message at the roster cap.
- **Sender-key fits the mesh's one structural demand: no cross-member coordination.** Each member's
  chain state is authored solely by that member. The trust, freshness, and healing problems are
  delegated to the pairwise v2 DM ratchet that carries the seeds, which already survives this mesh
  (randomized-soak-hardened).

**The trade to state as loudly as ADR 016 stated "no cumulative root chain": v2's survival property
is that key material rides on every frame; sender-key inverts it.** A ratcheted group frame is undecryptable
until a *separate* DM — with its own custody fate — delivers that sender's epoch seed. Availability
is bought back with a persistent seed outbox, proactive re-sends, and a key-request loop (§7), but
recovery is eventual, and so is leave-rekey (§6.1). Custody accelerates seed delivery; the outbox is
the source of truth.

Scope: **group chats only.** DMs stay v2; the Nearby broadcast room stays plaintext by design;
receipts/reactions stay cleartext-signed (separate roadmap item — since shipped as ctl values 5/6,
`docs/ENCRYPTED_RECEIPTS_REACTIONS.md`; sealed group reactions are the one ctl riding this group
form). Groups keep their existing model:
fixed founding roster (≤8), no add, departure only by a member's own signed `groupleave` — enforced
since the roster-integrity phase (`InboundPipeline.vetRoster`): the stored founding set only ever
comes from a roster whose id **is** its hash, the founding set never grows, and only signed leaves
shrink the effective roster. The one growth of the *effective* roster is the mirror image of the
leave: a founding member who left may re-add **themselves**, by a signed frame of their own that lists
them as a member and is newer than their recorded leave (`InboundPipeline.rejoinBy`, 2026-09-11).
Nobody can re-add anyone else, and the founding set — the id's preimage — is untouched. It exists
because a group's id is the hash of its member set, so "create a group with the same people" after
leaving resolves to the same group on every phone; without it the creator's re-create was a dead
letter everywhere but their own device. Group key state distributes secrets to exactly the effective
roster, which is why the integrity phase lands first.

## 2. Keys

| Key | Lifetime | Where | Purpose |
|---|---|---|---|
| Epoch seed (per sender, per group) | current epoch + 48 h drain of the superseded one (§10) | sender: `group_send_chains` (seed retained for re-distribution); receivers: **never stored** (chain derived at adoption, seed discarded) | the distributed secret; everything else derives from it |
| Send chain key | until the next seal advances it | `group_send_chains.chainKey` | forward-only chain position |
| Recv chain / message keys | delete-as-you-go; skipped keys ≤48 h | `group_recv_chains` / `group_skipped_keys` | per-message AEAD keys |
| Epoch export | with its send chain | `group_send_chains.export` | §8 export API (spool plane), no consumer yet |

There are no new long-term keys, no DH, and no change to identity, nodeIds, or safety numbers. A
seed's confidentiality in transit is the v2 DM session's (X3DH + epoch rekeying); its forward
secrecy at rest is retention: deleting a `group_send_chains` row (sender side) and consuming chain
keys (receiver side) **is** the FS guarantee. The current epoch's seed is necessarily retained for
re-distribution — the honest cost accounting lives in §9.

## 3. Seed distribution over the DM ratchet

A sender introduces (or rotates) its group epoch by DMing each other member a control frame:

```
MessageContent { ctl = CTL_GROUP_KEY (2), gk = GroupKeyPayload { groupId, keys: [GroupSeed…] } }
GroupSeed { epoch: Int, seed: bytes(32), mintedAt: Long }
```

sealed **only** under the v2 DM ratchet (`RatchetSessions.sealDm`) — never v1: a static-HPKE-wrapped
seed would void the epoch's forward secrecy against one harvested DM. If a member's DM session can't
seal (no prekey, stale capability), the seed is parked in the outbox and the group message falls
back to v1 for everyone (§5 gate).

Like every ctl frame (ADR 016 precedent), a seed DM is an ordinary custodial chat frame on the wire
— v1 relays flood and custody it for 24 h — and is never persisted, notified, or acked as a message.
A build without the group scheme that decrypts one sees an unknown `ctl` and consumes it as a silent no-op (pinned by
test), still advancing the pair's DM session. Receivers adopt idempotently: a re-served distribution
matches on `(epoch, mintedAt)` and never rewinds the chain; adoption is gated on holding the group,
not having left it, and the sender being in the pinned roster; adoption is rate-limited (§10) so
seed-minting cannot be a skipped-key pump.

The "holding the group" gate has one ordering trap: a member learns of a new group from the roster on
its first frame, but the creator's seed floods *before* that frame (§4), and custody serves the two in
either order. A seed for a group with no local row is therefore **parked before the ratchet commit**
(`PendingGroupKeys`, the `PendingInbound` sibling keyed by group id) and replayed by `reconcileGroup`
once the roster lands — the chain never advanced past it, so the replay is an ordinary open. Consuming
it and discarding the payload, the pre-2026-09-10 behaviour, lost the seed for good: every re-serve was
a `RATCHET_DUPLICATE`, the first message sat at `GROUP_RATCHET_NO_KEY`, and neither the 3-distinct-frame
key-request heuristic nor the floored re-send triggers below were due.

The park is in memory, so a process death between the seed and the roster loses it — and the same three
doors are then shut: the seed was custodied before it was parked, so no peer re-serves it; the creator's
proactive re-send is behind its 15-min floor whenever any trigger fired recently; and one unreadable frame
does not reach the heuristic. `reconcileGroup` therefore has a restart-safe half: on **first sight** of a
group it re-enters our own custody's ratchet-form chat DMs from that roster that never produced a message
row (`MeshManager.replayCustodiedSeedDms`, the DM analogue of `replayCustodiedGroupFrames`). A delivered DM
stops at the exists-gate, a consumed ctl DM drops as a duplicate, and the lost seed opens because a parked
frame never advanced the chain. Found by the `mesh/lab` restart-with-a-parked-seed scenario (2026-09-11).

Since 2026-09-18 (ADR 2026-09.mjaj, work item #47) **the seed carries the founding roster itself** —
`GroupKeyPayload.group`, the roster half of the row (id, members, departed, creator, name; never the photo),
on every distribution — so a member with no row pins the group *from the seed* before the park decision
(`InboundPipeline.pinRosterFromSeed`), through `reconcileGroup` and `vetRoster` exactly as a group frame
would, and adopts the seed and the root on the same pass. The relay is why: a member reachable only over
the Internet plane has a DM scope that carries the seed and a group scope that derives from the root inside
it, and the roster rode only that group scope — three refusals in a ring, and the park expired. The park and
the custody replay above remain as the fallback for a seed from a build without the field, or one whose
roster was refused. One trap: the seed is already custodied when the pin runs, so the first-sight replay is
told to skip it (`replayExcept`), or it re-enters the seed nested under its own pin and the outer commit
silently finds its chain index consumed.

The **outbox** (`group_key_sends`, one row per (group, member)) tracks `sentEpoch/sentAt/ackedAt`.
Receivers acknowledge adoption with `ctl = CTL_GROUP_KEY_ACK` (`gk` carrying the groupId + epoch),
which stamps `ackedAt` and stops re-sends. Re-send triggers: an inbound key-request (§7), the
member's profile/prekey arriving (`flushPendingGroupKeysFor`, the `flushPendingFor` analogue), a
group member appearing as a neighbor with an unacked outbox row (the partition-merge accelerator),
and a DM session reset/replacement with that member (ctl frames are never persisted, so
`resealRecentDmsTo` cannot recover a seed — this hook is the only wipe-side seed plane). The reset
trigger is **forced**: it bypasses the outbox's acked-epoch short-circuit, because a reset means the
peer lost their DB and any recorded ack of the current epoch predates the wipe — without the bypass
the flush silently no-ops and the peer black-holes group frames until the sender's next natural mint
(found by the post-ship branch review; the 15-minute re-distribution floor still applies). The floor
counts only sends under the pairwise root held with that member *now* (ADR 2026-09.qerd): a seed sealed
under a root the member has since left is one it can never open, and it used to stamp the floor all the
same — so a restored member whose reset landed just after our post (sealed under the wiped session) had the
reset's forced flush refused, and sat at `GROUP_RATCHET_NO_KEY` for a quarter hour. A root moves only
through the ratchet's rate-limited paths, and a bare reset marker under an unchanged root stays floored.

**The receive side has a matching local half** (found by the first on-device smoke): a group frame
that arrives *before* its seed is dropped locally but still custodied by the receiver as a carrier —
and a frame the receiver already holds is never re-served by a peer (its id is folded into the
receiver's digest, so no divergence ever cues a re-offer). Seed adoption therefore replays the
receiver's OWN custody for that (group, sender) through the inbound pipeline
(`replayCustodiedGroupFrames`), and `heal()`/startup replay every undelivered custodied group frame
as the backstop. Replay is idempotent — delivered frames stop at the pre-decrypt exists-gate, and
still-keyless ones re-count a `NO_KEY` drop, which is exactly what keeps §7's request heuristic fed
(a stuck frame would otherwise generate no signal at all).

## 4. Epochs and chains

Each sender advances through numbered epochs per group; epoch `e`'s chain derives from its seed:

```
chainKey_0 ‖ epochExport = HKDF-SHA256(ikm = seed, salt = 0*32,
                                       info = "knit/group/v1/epoch" ‖ groupId ‖ '|' ‖ senderId ‖ '|' ‖ u32be(e), L = 64)
msgKey_n     = HKDF(chainKey_n, salt = 0*32, info = "knit/group/v1/mk", 32)
chainKey_n+1 = HKDF(chainKey_n, salt = 0*32, info = "knit/group/v1/ck", 32)
```

Binding groupId + senderId + epoch into the derivation means a leaked or replayed seed cannot be
transplanted across groups, senders, or epoch numbers. AEAD: AES-256-GCM (12-byte random IV, 128-bit
tag), key `msgKey_n`, **AAD = the unchanged v1 header** `"$id|$senderId|$sentAt|$thread"` with
`thread = groupId`; the whole content is under the frame's Ed25519 signature, verified before
decrypt.

Epochs derive independently (no chaining across epochs): any subset of a sender's epochs opens in
any order, and a wholly-evicted epoch loses only itself — the same custody-hole invariant as the DM
scheme. **Advance rules** (sender-local; no cross-member coordination by construction):

1. no live chain (first eligible send, or state deleted by a forced case below);
2. `count ≥ 200` — one epoch never exceeds the per-group/per-sender custody quota;
3. epoch age ≥ 24 h — the custody TTL;
4. (forced) membership departure processed (§6.1) — the chain rows are deleted in the departure
   transaction, so the next send mints and distributes to the remaining members only;
5. (forced) device wipe — no state; the re-mint restarts numbering at 1 with a fresh `mintedAt`.

**Wipe recovery is mint-stamped, not session-replaced.** Receivers key rows by
`(group, sender, epoch, mintedAt)`: adopting a newer mint of an epoch they already hold keeps the
older era's rows draining for 48 h (the DM `prevRoot` pattern), so both eras' custody re-serves
still open; the open ladder tries chains newest-mint-first and the AEAD arbitrates. There is no era
identifier on the wire.

## 5. Wire form (additive; see docs/WIRE_COMPAT.md)

The group form **shares crypto scheme v2 with the DM ratchet** — both landed in one never-released
bump, so there was no reason to spend a version number on the split (`MAX_SUPPORTED_VERSION` stays 2).
The two forms are discriminated by addressing, not by `v`: a group-addressed v2 envelope carries `g`,
a DM carries `r` (`keys = []` in both — the message key is derived, never wrapped):

```
EncEnvelope { v = 2, nonce, ct, keys = [], g: GroupRatchetHeader }   // group-addressed
GroupRatchetHeader { se: Int, n: Int }     // sender epoch + chain index; ~10 B vs v1's ~500 B of wraps
```

groupId rides `RelayEnvelope.group` and the sender on the envelope, both already present — the
header carries only what v1 didn't already say. Old builds decode the envelope (ignoring `g`), hit
`v > MAX_SUPPORTED_VERSION`, and take the existing `UNKNOWN_ENVELOPE_VERSION`
drop-locally-still-relay path; `canCarry` never inspects `v`, so mixed-version meshes carry and
custody the group form exactly like everything else. A group-addressed envelope without `g` (or a
DM-addressed one without `r`) is malformed by construction.

Capability gating: `Protocol.CAP_RATCHET` covers **both** ratchet forms — they ship in the same
release, so a second bit would never vary independently. Outbound group-form requires, for **every**
other member, a pinned authenticated profile carrying `CAP_RATCHET` and a valid `PrekeyInfo` (the
seed rides the DM ratchet, so DM-sealability is a prerequisite), *and*
the current epoch's seed distributable to them. **All-or-nothing per message**: any ineligible
member demotes that message (not the group) to v1, which every build reads; eligibility is
re-evaluated per send, so a group upgrades the instant the last capable profile lands and downgrades
if a member's newer profile clears its prekey. One abandoned pre-ratchet install pins its groups at v1 —
accepted for now (dual-seal is roadmap-listed and rejected for v1 of this feature: sealing v1
alongside the ratchet voids FS against the straggler's static key anyway).

## 6. Receive ladder and delivery semantics

The pre-decrypt exists-gate and two-phase peek/commit contract are unchanged from v2:
`decryptAndDeliver` short-circuits ids already in `messages`; a group open runs lock-free `peekOpen`
(moderation classifies the plaintext before anything persists), then re-opens and commits the state
delta atomically with the message row (`withWriteTransaction` outer, the shared ratchet mutex inner).
`GroupRatchetSessions` shares the **one** mutex with `RatchetSessions` — seed adoption runs inside
the DM commit's `onOpened`, and two locks there would invite an inversion.

The open ladder (engine, pure): stored skipped key → live chain of the newest mint (deriving-and-
storing keys across any index gap, ≤200/epoch) → draining older-mint chains → typed failure:

- `GROUP_RATCHET_NO_KEY` — no adopted seed covers `(sender, se)`: the seed DM hasn't arrived or was
  lost. Feeds the key-request heuristic (§7).
- `GROUP_RATCHET_AEAD_FAIL` — key material present but wrong (stale/foreign mint era). The frame
  signature was already verified, so this is never third-party tamper; it is the post-wipe signal
  and also feeds §7.
- `GROUP_RATCHET_DUPLICATE` (benign re-serve) / `GROUP_RATCHET_BAD_HEADER` (bound violations).

All failures are delivery-local; the frame still relays and custodies (the no-throw contract of
`onDeliver` holds). **Undecryptable history leaves no placeholder rows**: a persisted row with the
real frame id would trip the exists-gate and permanently block the later recovered decrypt — gaps
stay silent and heal via §7 (the DM precedent).

### 6.1 Leave-rekey (eventual, by construction)

Processing a member's signed `groupleave` (`GroupRepository.recordDeparture`) deletes the group's
send chains and the leaver's outbox row **in the same transaction** as the roster shrink; the next
send mints a fresh epoch distributed to the remaining members only. The leaver's recv chains drain
via the 48 h sweep (their pre-leave frames may still legitimately re-serve). Local leave/delete
purges all group ratchet state for the group.

A rejoin (§1's self-re-add) rekeys the same way, so the returning member reads nothing sealed while
they were out (`GroupRepository.recordRejoin`, atomic with the roster change in `reconcileGroup`'s
transaction). Membership itself is never rate-limited — the rejoiner is back the moment their frame
lands — but the rekey it triggers is floored per (group, member) at one an hour
(`InboundPipeline.REJOIN_REKEY_FLOOR_MS`), so a leave/rejoin loop cannot make every member re-mint and
fan seeds out on each turn. Leave and rejoin are last-writer-wins on the member's own `sentAt`: the
two deterministic notice rows (`leave:`/`rejoin:` + group + member) are the clocks, so a custody
re-serve of a pre-leave frame cannot rejoin them and a re-served old leave cannot evict them again.
The rejoiner's seed usually floods ahead of the frame that rejoins them — the seed-before-roster race
in its second shape. Since the seed carries the founding roster (§3) it is itself the rejoiner's own signed
frame listing them, so it rejoins them and adopts on its first pass; a roster-less seed from an older build
is parked in `PendingGroupKeys` under "sender departed" until the frame that rejoins them arrives.

Rekey triggers **only** on a signed leave or rejoin — never on roster shrinkage carried by a frame
(vetRoster ignores those; anything else would let a forged roster remotely trigger rekey fan-out). The claim is
therefore *eventual*: a member who never receives the leave frame (custody TTL is 24 h; partitions
can outlast it) keeps sealing under epochs the leaver holds until their own advance rules rotate —
and keeps the leaver in their roster (and seed distribution) until the leave arrives. The mesh
cannot do better without coordination it structurally lacks; stated here so nobody reads
leave-rekey as instantaneous revocation.

## 7. Key recovery (subsumes "group key-gap retransmit" for ratcheted groups)

Receiver side: ≥3 distinct undecryptable group-frame ids per `(group, sender)` (`NO_KEY`/`AEAD_FAIL`),
each with `sentAt` within 48 h (a replayed ancient frame can't burn the budget), rate-limited to one
request per (group, sender) per hour → send `ctl = CTL_GROUP_KEY_REQ` to that sender as a v2 DM
(same guards as the DM reset heuristic: pinned profile, capability, prekey).

Sender side: verify the requester is a current roster member of a non-left group, apply a 15-minute
per-(group, member) floor, then re-seal the **current + draining previous** seeds in one ctl DM.
**A request never advances the epoch** — advance-on-request would be a rekey-amplification lever.

The v1 fallback path keeps the old silent gap (members without pinned keys are skipped); that
residual shrinks as capability floods and is noted in the roadmap.

## 8. Export API (for the internet relay plane — root mechanism confirmed)

```
epochSeal = HKDF(epochExport, salt = 0*32, info = "knit/group/v1/export/epoch", 32)
```

Per-(sender, epoch), pairwise-shared with every member (anyone holding the seed derives it). It
stays API-only: the spool spec's v1 outer seal is scope-static (per-sender epoch seals would be
unopenable exactly by the seed-lagging member the frame must stay visible to — the spec's §4.2
records the full argument), and this surface is reserved for its registered `sealv = 2` extension.

The **shared group root** this section deferred is now **specified by `docs/SPOOL_PROTOCOL.md` §3.2
and shipped** (2026-08-16, the group-scope milestone), along the reserved mechanism: a root gossiped
as the additive `GroupKeyPayload.gr` field (`{root, version, minter}`) on the existing
`CTL_GROUP_KEY` channel, highest-`(version, minter)` wins, with the leave-rekey hook (§6.1) as the
rotation point — `GroupRepository.recordDeparture` stamps the re-mint due in the same transaction
that resets the send chains.

One departure from the reserved sketch, worth not relitigating: minting is **any member's**, not the
creator's. The creator-only rule left a group whose creator never enables the Internet plane with no
scope at all; the spec now damps concurrent mints with a preferred-minter-plus-grace rule instead
(creator if still a member, else the smallest remaining node id, and anyone else after a 6 h grace),
which also unfreezes a departure re-mint whose deterministic re-minter never comes back. Convergence
is the same `(version, minter)` order either way. `mesh/spool/GroupRootPolicy` is the reference
implementation; adoption additionally bounds the version and requires a founding-roster minter.

## 9. Security claim (honest, epoch-granular, availability-inverted)

Full compromise of a member device at time T exposes, per group:

1. **Stored plaintext history** (`messages`, SQLCipher at rest) — outside the crypto scheme's scope.
2. **Recorded ciphertext of epochs whose key material is still retained**: for each member's chain,
   the current epoch's seed (necessarily retained for re-distribution) plus the ≤48 h draining
   previous one; on the receive side, live chain keys and ≤48 h skipped keys. Anything older is
   irrecoverable — that is the forward-secrecy guarantee, and it is **epoch-granular per sender**.
3. **Future traffic until every member rotates once** after the attacker loses live access: healing
   is inherited from the DM layer (fresh seeds travel through v2 sessions, which heal per round
   trip), so one full epoch rotation per sender re-keys the group.
4. **A departed member reads nothing sealed after their leave is processed** by each sender —
   eventual, per §6.1, bounded by leave-frame convergence, not instantaneous.

Metadata: `GroupRatchetHeader` shows relays exact per-sender send counts and epoch cadence; the
roster already rides cleartext on every group frame (custody's member-targeted push requires it);
a burst of N−1 ctl DMs after a leave is a legible rekey fingerprint. All accepted, same posture as
v2's §9 non-goals. Blocked members still receive seeds (ADR 010: blocking is local presentation and
must stay invisible); their inbound ctl frames die at the existing blocked gate — the one
asymmetry, accepted because their messages are never surfaced anyway.

Non-goals: per-message PFS; instantaneous revocation; dual-seal mixed groups; deniability changes;
hiding group routing metadata; broadcast-room encryption (separate design; receipts/reactions have
since shipped sealed — `docs/ENCRYPTED_RECEIPTS_REACTIONS.md`).

## 10. Constants (convergence-relevant ones mirror custody's — change together or not at all)

| Constant | Value | Tied to |
|---|---|---|
| `MAX_EPOCH_MESSAGES` | 200 | per-group AND per-sender custody quota |
| `MAX_EPOCH_AGE_MS` | 24 h | custody TTL |
| prev-seed retention (sender) | 48 h past supersession | 2× custody TTL (the DM `PREV_ROOT_TTL_MS` analogue) |
| recv-chain / skipped-key / drained-mint sweep | 48 h after last use | 2× custody TTL |
| skipped keys | ≤200/(sender, epoch); ≤2000 global (own budget, separate from the DM's) | epoch cap; DoS bound |
| key-request trigger | ≥3 distinct ids per (group, sender), frame `sentAt` ≤ 48 h old | DM reset-heuristic shape + custody dead-on-arrival guard |
| key-request outbound floor | 1 h per (group, sender) | cheaper + non-destructive vs the DM's 6 h reset |
| re-distribution floor | 15 min per (group, member, pairwise root) | responder flood bound |
| epoch-adoption limit | ≤4 per (group, sender) per 24 h | legitimate advances: count + age + leave + wipe |
| roster cap | 8 founding members (`GroupInfo.MAX_MEMBERS`) | bounds seed fan-out and skipped-key surface |

Sizing note: the per-group custody bucket (200) is shared by up to 8 senders (~25 retained frames
each), so mid-epoch holes are the **norm** for a returning member, not the tail case — the
key-request thresholds and skipped-key caps above are sized off group-bucket math, not the DM's.
