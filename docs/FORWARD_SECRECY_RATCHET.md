# Forward secrecy for DMs — the epoch-rekey ratchet (crypto scheme v2)

Status: **implemented** · plan approved 2026-08-14 · landed in phases (crypto core → wire → schema →
receive → send → reset hardening → observability). The group scheme that builds on it is
`docs/GROUP_FORWARD_SECRECY.md` (the v2 group form — both landed in this one unreleased bump). This document is the normative spec for the v2 DM
crypto scheme; `mesh/crypto/ratchet/` is the reference implementation and
`RatchetCryptoTest`/`RatchetEngineTest` are the executable anchors (an iOS/CryptoKit port implements
this file, not the Kotlin). Crypto scheme **v3** (ADR 059, 2026-08-29) is this same ratchet — chain, epochs,
header and bootstrap unchanged — with a derived nonce, a header-bound AAD and a compact plaintext; §4, §5
and §6 carry the deltas inline rather than as a separate document.

## 1. Why, and why this shape

The v1 scheme (static keys: per-message random AES-256-GCM content key, HPKE/X25519-wrapped per
recipient — `docs/ARCHITECTURE.md` §crypto) has no forward secrecy: record ciphertext today,
compromise the identity key file whenever, decrypt everything ever sent. That is tolerable for a
proximity mesh and fatal for the planned internet relay plane, whose relays hold sealed frames at
rest — every relay disk would be a harvest-now-decrypt-later honeypot. The ratchet therefore ships
*before* that plane, and doubles as its key source: scope derivation needs a ratcheted pairwise
secret (§8).

Three decisions were locked by the maintainer up front:

1. **Epoch-rekey ratchet, not a full Double Ratchet.** PFS at *epoch* granularity (a bounded window
   of messages shares a compromise fate) in exchange for a much smaller state machine. The mesh's
   delivery model — verbatim ciphertext re-served for 24 h, permanent mid-chain holes from custody
   quota eviction, no ordering anywhere — punishes per-message DH ratcheting; epochs fit it.
2. **X3DH-style bootstrap via a signed prekey published in the profile.** DMs stay offline-first:
   the first message to a peer whose profile is pinned is self-contained (no round trip), exactly
   like v1's encrypt-to-static-key, but forward-secret once the prekey rotates.
3. **Session state in Room** (`knit.db`, SQLCipher): ratchet advance + message insert + skipped-key
   consumption commit in **one transaction**, which closes the classic crash window between "state
   advanced" and "plaintext persisted". Cost: a DB wipe loses sessions — handled by the session
   reset path (§7), which is required anyway for reinstalls.

Scope: **DMs only.** Groups keep the v1 per-member wrap (group key state is its own future design),
the Nearby broadcast room stays plaintext by design, receipts/reactions stay cleartext-signed
(their encryption is a separate roadmap item). *(Both since landed in the same v2 train: the group
form in `docs/GROUP_FORWARD_SECRECY.md`, sealed receipts/reactions as ctl values 5/6 in
`docs/ENCRYPTED_RECEIPTS_REACTIONS.md` — the ctl registry grew, everything else here is unchanged.)*

## 2. Keys

| Key | Lifetime | Where | Purpose |
|---|---|---|---|
| Identity X25519 (`IK`) | device lifetime | `filesDir/identity.key` (Tink HPKE keyset; raw scalar extracted for v2) | X3DH identity DH; also v1 HPKE. Public half is the `hpkePub` already in `PublicKeyBundle` — **nothing about identity or nodeId changes** |
| Identity Ed25519 | device lifetime | `identity.key` | frame signatures (unchanged); signs prekeys |
| Signed prekey (`SPK`) | rotates every 7 d; privs kept: current + 4 (~35 d) | `identity.key` (`Stored.prekeys`, additive field) | X3DH medium-term DH base; survives DB wipes so in-flight inits stay openable |
| X3DH ephemeral (`EK`) | one derivation | never stored (pub rides the init until confirm) | binds root freshness |
| Epoch keypair | until superseded + acked + 48 h (≤16/peer, 30 d hard cap) | `ratchet_local_epochs` | per-epoch DH; **its retention is the PFS window** |
| Chain / message keys | delete-as-you-go; skipped keys ≤48 h | `ratchet_recv_epochs` / `ratchet_skipped_keys` | per-message AEAD keys |

Reusing the HPKE X25519 identity key for X3DH is safe by domain separation: every v2 derivation is
labeled `knit/dm/v2/...` (HKDF-SHA256), disjoint from RFC 9180's internal labels. This is the same
posture Signal-family protocols take with their identity keys, and it keeps `PublicKeyBundle` —
whose hash **is** the nodeId — byte-identical.

### 2.1 Signed prekey publication

`ProfileContent` gains an additive nullable field:

```
PrekeyInfo { id: Int, pub: bytes(32), sig: bytes(64) }   // sig = Ed25519 over spkSigningBytes
spkSigningBytes(id, pub) = "knit/dm/v2/spk" ‖ u32be(id) ‖ pub
```

The profile frame's Ed25519 signature already covers the field; the *detached* `sig` exists so the
prekey stays re-verifiable once stored apart from its frame (the `peers` row), and so a non-Knit
implementation can verify a prekey in isolation. Receivers verify `sig` against the (self-certified,
immutably pinned) bundle before adopting. Rotation mints `id+1`, bumps `profileVersion`, and rides
the ordinary profile re-flood; a *newer* profile carrying `prekey = null` clears the pin (peer
downgraded — outbound falls back to v1).

## 3. Session bootstrap (X3DH, no one-time prekeys)

Initiator A → responder B (B may be offline; A holds B's pinned profile = `IK_B` + `SPK_B`):

```
DH1 = X25519(IK_A, SPK_B)      // binds A's identity
DH2 = X25519(EK_A, IK_B)       // binds B's identity + EK freshness
DH3 = X25519(EK_A, SPK_B)      // the FS core: both inputs deletable/medium-term
sessionRoot = HKDF-SHA256(ikm = 0xFF*32 ‖ DH1 ‖ DH2 ‖ DH3, salt = 0*32, info = "knit/dm/v2/x3dh", L = 32)
```

One-time prekeys are a deliberate omission: a serverless mesh cannot track consumption. The cost is
X3DH's standard replay caveat (a captured init can be replayed to re-create the same root — §7's
idempotence rules make that a no-op, and the frame signature prevents third-party forgery).

B computes the mirror (`x3dhRespond`) from the init riding the first frame(s): `EK_A` pub +
`pkid` + the sender's pinned bundle. Everything B needs arrives with the frame — `verifyInbound`
already requires the sender's profile before any DM is processed, so "profile pinned" is an
invariant, not a new requirement.

## 4. Epochs and message chains

Each direction advances through numbered **epochs**. The sender of epoch `se` mints a fresh X25519
pair `(ek, ekPriv)` and DHs against the peer's newest contribution (`pe` = which of the *receiver's*
epochs supplied the base; `pe = 0` means the receiver's SPK, which is only legal while an init is
attached; both numbers are bounded `0..MAX_EPOCH_NUMBER` on the wire, and a header outside those
bounds is `BAD_HEADER` — never a missing-key report, which would drive the reset heuristic below):

```
epochSecret = HKDF-SHA256(ikm = X25519(ekPriv, basePub), salt = sessionRoot,
                          info = "knit/dm/v2/epoch" ‖ dir ‖ u32be(se) ‖ u32be(pe), L = 64)
  dir        = 'i' when the epoch's sender initiated the session, 'r' otherwise
  chainKey_0 = epochSecret[0..31];  epochExport = epochSecret[32..63]
msgKey_n     = HKDF(chainKey_n, salt = 0*32, info = "knit/dm/v2/mk", 32)
chainKey_n+1 = HKDF(chainKey_n, salt = 0*32, info = "knit/dm/v2/ck", 32)
```

AEAD: AES-256-GCM (128-bit tag), key `msgKey_n`. **v2:** a 12-byte random IV carried in
`EncEnvelope.nonce`, **AAD = the unchanged v1 header** `"$id|$senderId|$sentAt|$thread"`. A tampered
ratchet header changes the derived key (the AEAD fails), and the whole content — header included — is
under the frame's Ed25519 signature, which is verified before decrypt; the header needs no second
integrity mechanism. **v3 (ADR 059):** nothing random rides the wire —

```
aad_v3   = aad_v2 ‖ "knit/dm/v3/hdr" ‖ u32be(se) ‖ ek ‖ u32be(pe) ‖ u32be(n) ‖ u8(flags)
                  ‖ (init ? 0x01 ‖ eph ‖ u32be(pkid) ‖ u64be(at) : 0x00)
nonce_n  = HKDF(msgKey_n, salt = 0*32, info = "knit/dm/v3/nonce" ‖ aad_v3, 12)
```

The nonce derives from the **message** key, not the chain key, so a stored skipped key (all that
`ratchet_skipped_keys` keeps) still opens its frame, and each rung of the open ladder and each root
candidate derives its own; the AAD is mixed in so a send-chain rollback (a restored database re-sealing
under the same index) gets a distinct nonce from the fresh frame id, restoring v2's (key, nonce) posture.
Binding the header into the AAD is what makes the sentence above true for a frame that carries *no*
signature (the unsigned live-link tick, §6): `flags` and `init.at` are the two header fields the derived
key does not already bind.

**`sessionRoot` is static per session — there is deliberately no cumulative root chain.** A chained
root (`RK_n = KDF(RK_{n-1}, DH_n)`) requires processing a peer's epochs in order, and this mesh
*guarantees* permanent holes: custody evicts oldest-by-`(sentAt, id)` under a 200-per-sender quota,
so a wholly-evicted epoch would wedge the session forever. Independent epoch derivation means any
subset of epochs, in any order, decrypts; a lost epoch loses only itself. Post-compromise healing
survives at **round-trip granularity**: each side's next epoch uses a fresh DH pair, so once both
sides have rotated after a compromise (and the attacker has lost live access), new epoch secrets are
out of reach even with the root.

**Advance rules** (sender starts a new epoch when any fires):

1. session init (epoch 1);
2. first send after the peer's newest contribution advanced past the current epoch's base — the
   healing half of the ratchet: every conversational turnaround rekeys;
3. `sendCount ≥ 200` — one epoch can never exceed the per-sender custody quota, so a re-served
   backlog spans at most ~2 epochs of skipped-key work;
4. epoch age ≥ 24 h — the custody TTL: no legitimately re-deliverable frame belongs to an epoch
   more than one retention generation old;
5. (forced) after a root change — race adoption or session replacement nulls the send chain.

`ek` rides **every** frame, not just the first of an epoch: with permanent holes, no particular
frame's arrival can be load-bearing. Each frame fully describes how to derive its epoch.

**Numbering is monotone across an *adopted* replacement** (§7): the receiving side keeps its own
`se` counter running, so a reused `(peer, se)` can only follow a device wipe — which also discards the
old epoch privs — or a locally-originated **reset**, which re-initiates from scratch and does restart
`se` at 1. Either way the peer purges its stale rows for that numbering, so there is no collision on its
side. On ours, a reset's re-minted epoch *replaces* the dead era's key of the same number (ADR 027); the
dead-era keys numbered above it survive, which is how the peer's in-flight frames still drain (§7).

**Adopting the peer's newest epoch as our next DH base is jump-damped.** Once we have an anchor
(`peerBaseEpoch >= 1`), an `se` more than `MAX_EPOCH_JUMP` beyond it is not adopted: the frame still
decrypts and delivers, we simply keep the older base. Without the damping a peer that jumped its own
numbering — buggy or hostile — pinned `peerBaseEpoch` where no later epoch could pass the monotone
test, and the healing advance rule never fired again for the life of the session. The bound is
deliberately **not** applied while unanchored (`peerBaseEpoch == 0`), because adopting a replacement
zeroes our anchor while the peer's numbering keeps climbing; refusing there would reject the peer's
next legitimate frame, and refusing a *frame* is `BAD_HEADER`, which drives no reset and so has no way
back. Refusing an *adoption* is recoverable by construction: our sends then name an epoch the peer has
swept, they report `EPOCH_GONE`, and that does trigger a reset.

**Only a fresh epoch under the active root moves the session's era** (ADR 2026-09.pz9g). Three things a
successful open records are claims about the era we hold *now*: adopting the peer's epoch as our next DH
base, raising `highestPeAcked`, and — for an initiator — confirming the session (which drops the init from
our frames). A frame drained under any other root says nothing about that era: under a kept `prevRoot`, a
race's other root, or a late race loser's (§7). Such frames open and deliver, and their chain is stored,
but they record none of the three. The receive rows make the rule exact without a flag: every root change
on our side purges them, so each row was derived by exactly one fresh derivation, and that derivation's
root already decided. The live-chain and skipped-key rungs therefore never record any of the three — in
era it would repeat what the first frame of the epoch recorded, out of era it would be wrong. The era is
the root, never the number of our epoch a frame names: a peer can seal under the new root against a key
of ours from the old era, and that frame is this era's.

## 5. Wire form (additive; see docs/WIRE_COMPAT.md)

`EncEnvelope` v2 (same type, `MAX_SUPPORTED_VERSION = 2`; `keys = []` — the message key is derived,
never wrapped, so `WrappedKey` has no v2 role):

```
EncEnvelope { v = 2, nonce, ct, keys = [], r: RatchetHeader }
RatchetHeader { se: Int, ek: bytes(32), pe: Int, n: Int, init: RatchetInit? , flags: Int = 0 }
RatchetInit  { eph: bytes(32), pkid: Int, at: Long }    // attached to EVERY frame until confirmed
flags: bit0 = RESET (§7) — read only while an init rides; set on a reset's fresh init, or on a reset
       sealed under an init the sender already had pending (the "marker", §7). v2 only: v3 binds it.
```

Old builds decode the envelope (ignoring `r`), hit `v > MAX_SUPPORTED_VERSION`, and take the
existing `UNKNOWN_ENVELOPE_VERSION` drop-locally-still-relay path; `canCarry` never inspects `v`
(rule 5 of WIRE_COMPAT), so mixed-version meshes carry v2 custody exactly like v1. Steady-state
overhead ≈ 50 B per frame (+ ~95 B while unconfirmed).

`EncEnvelope` **v3** (ADR 059; `MAX_SUPPORTED_VERSION = 3`) is the DM form only, shaped so every fielded
build still decodes — and therefore still *carries* — it:

```
EncEnvelope { v = 3, nonce = h'' (empty: derived, §4), ct, keys = [], r: RatchetHeader }   // header unchanged
ct           = AES-GCM(msgKey_n, nonce_n, aad_v3, MessageContentV2)                          // the labeled plaintext
```

`nonce` stays a required field carried empty rather than becoming nullable because `canCarry` decodes the
chat payload before custodying a frame: an envelope a pre-v3 build cannot decode is one no fielded build
would carry, which is the per-build custody rule ADR 006 forbids. The plaintext is the compact
`MessageContentV2` schema (integer keys, raw ids; `mesh/crypto/MessageContentV2.kt`), discriminated by the
envelope version, with a reserved label-0 version of its own. Old builds: decode, `v > MAX_SUPPORTED_VERSION`,
`UNKNOWN_ENVELOPE_VERSION`, carry. Group form stays v2 — and v3 is the DM form *by rule*: a group-addressed
v3 envelope is `RATCHET_BAD_HEADER` on every v3 build, so a compact group form is a v4 (roster-gated), never a
v3 variant. Resets and group-key ctl DMs stay v2; `MessageContentV2` reserves label 13 for a raw-seed `gk`
and label 12 for length padding, both additive under `ignoreUnknownKeys`.

Capability gating: `Protocol.CAP_RATCHET = 0x10` (append-only). Outbound v2 requires the peer's
pinned, authenticated profile to carry **both** the capability bit **and** a valid `PrekeyInfo` —
they travel on one signed frame, which is the stale-capability mitigation. Otherwise outbound stays
v1 (kept indefinitely; inbound v1 accepted forever). Outbound **v3** additionally requires
`Protocol.CAP_CRYPTO_V3 = 0x100` on the same pinned profile (`mesh/crypto/CryptoScheme`); a content the
compact codec cannot represent canonically seals v2 instead, never mangled.

## 6. Receive ladder and delivery semantics

The mesh re-delivers verbatim ciphertext routinely (60 s re-offer loop; the flood-dedup SeenSet is
in-memory, 10 min, reset per mesh session). Two consequences are baked in:

- **The pre-decrypt exists-gate**: `decryptAndDeliver` short-circuits any encrypted chat whose
  `env.id` is already in `messages` (ack still runs — the vaccine-purge semantics are untouched).
  Without it, "delete the message key after use" would turn every custody re-serve into a
  `DECRYPT_FAILED`. It also spares v1 an HPKE unwrap per re-serve.
- **The open ladder** (engine, pure): stored skipped key → live chain of a known recv epoch
  (deriving-and-storing keys across any index gap, ≤200/epoch) → fresh epoch derivation, trying the
  active root, then the draining `prevRoot`, then — for an unanchored race winner — a late race loser's
  root (§7). Only the last rung under the active root moves the session's era (§4). Typed failures map
  to drop reasons:
  `RATCHET_NO_SESSION` / `RATCHET_EPOCH_GONE` / `DUPLICATE` (benign) / `BAD_HEADER` / `AEAD_FAIL`.
  All are delivery-local; the frame still relays and custodies (the no-throw contract of
  `onDeliver` holds — nothing in the v2 path throws out).

- **The unsigned door (v3, ADR 059).** `verifyInbound` admits exactly one frame shape without a
  signature — `chat`, `relay = false`, no group, addressed to us by a pinned sender other than us — and
  judges only the shape; the AEAD (whose AAD now binds the header, §4) is the authenticator. The rest is
  ordering: the unsigned check runs *before* the plaintext branch and *before* the exists-gate (an unsigned
  frame never takes that shortcut — it is a receipt oracle otherwise), a frame that opens must be a
  `CTL_RECEIPT` or it is refused *before* commit (a stripped-signature replay of a plain DM opens and must
  not deliver; the chain index is untouched, the signed copy lands later), and an open failure is counted
  but never fed to the reset heuristic. Drop reason `UNSIGNED_REFUSED` for every other empty-signature frame.

Persistence is atomic: the engine returns `(plaintext, StateDelta)` and the pipeline commits the
delta with the message row in one `withWriteTransaction`. A crash before commit re-processes cleanly on
the next re-serve (state unchanged, message absent). The send side mirrors this (commit the chain
advance + local row, then flood); a crash between commit and flood is just a chain hole the
skipped-key path absorbs.

## 7. Sessions: races, replacement, reset

- **Both-initiate race.** Two unconfirmed roots meet. Winner (both sides compute it): the init
  whose *initiator* has the lexicographically smaller nodeId — deliberately not timestamp-based,
  since concurrent inits can carry identical `at`. The loser's root drains as `prevRoot` (48 h =
  2× custody TTL) so its in-flight epochs still open; numbering monotone, no collisions. They open
  read-only (§4): the winner never takes the loser's pre-adoption epoch as its DH base, and seals
  against the loser's SPK, init attached, until the loser answers under the winning root.
- **Init idempotence.** A session's resolved peer-init is remembered by its ephemeral key
  (`peerInitEphPub`). Re-served copies — which outlive the resolution by up to the custody TTL, and
  can carry a *newer* timestamp than the session they lost to — match the anchor and are treated as
  already-resolved, never as replacements.
- **Replacement.** A genuinely new init (`init.at > establishedAt`, unknown eph) from a confirmed
  peer means they lost state (wipe/reinstall): adopt the new root, archive the old to `prevRoot`,
  and **purge the peer's recv epochs + skipped keys** — their numbering restarts at 1. Old-era
  frames whose epoch numbers don't collide with new rows drain via `prevRoot`; colliding ones fail
  benignly (`DUPLICATE`/`AEAD_FAIL`) — anything delivered pre-wipe is exists-gated anyway, and the
  reset re-seal (below) covers the undelivered. One carve-out (found by the randomized soak): a
  **confirmed initiator with no recorded peer-init anchor** (`peerInitEphPub == null` — we won a race
  without ever processing the loser's init) treats an init that *loses* the nodeId tiebreak as a
  stale race remnant, never a replacement — adopting it would defect to the losing root while the
  peer sits on the winning one, diverging permanently. Refused is not unread, though (ADR
  2026-09.pz9g): the remnant's own frame is the loser's opening DM, which can land *after* the
  post-adoption answers the winner confirmed on, and it is a real message. So on every return that
  refuses such an init, the loser's root is offered as a trailing **read-only** candidate — for a
  `pe = 0` frame, never adopted, and recording nothing (no anchor: a resetter's unflagged follow-up
  frames carry its reset init, and anchoring one would turn the flagged reset into an idempotent
  re-serve). `FLAG_RESET` never gets it: a reset is adopted or refused, never read. The state is also
  every session we initiated whose peer never sent an init, so a genuine wipe of the higher-id peer
  that re-initiates *without* the flag is read rather than refused, and recovers from the peer's side:
  our frames fail on it, its heuristic fires, and its flagged reset is exempt from this carve-out.
  Inbound replacements are additionally rate-limited (1/peer/h, the facade's `allowReplacement` gate).
- **Reset request.** Trigger: ≥3 distinct undecryptable-v2 frame ids from a pinned peer
  (`RATCHET_NO_SESSION`/`RATCHET_EPOCH_GONE`/`RATCHET_AEAD_FAIL`, ADR 023), each from the era it would
  abandon (ADR 024/026), rate-limited to one per 6 h (persisted). The request is an ordinary v2 DM carrying a fresh init, `flags = RESET`, and
  `MessageContent.ctl = CTL_SESSION_RESET` — deliberately *not* a new frame type, which v1 relays
  would refuse to custody (`isCustodial` is a fixed list); as a chat frame it floods, custodies,
  and reaches an offline peer. The receiver (frame-signed, newer `init.at`) adopts per the
  replacement rules and **re-seals its still-unacked DMs from the last 24 h** under the fresh
  session (the `flushPendingFor` mechanics: fresh seal, original `id`/`sentAt` header), recovering
  what the wiped device lost from custody. Control frames are never persisted, notified, or acked
  as messages; inbound replacements are additionally rate-limited (1/peer/h). The resetter's session
  stays unconfirmed until it opens a frame the peer sealed under the new root: a dead-era frame drained
  under its `prevRoot` never confirms it (§4), so every frame it seals meanwhile carries the init, and
  a peer that has not yet seen the reset can adopt it from any of them. The peer answers each new init
  it opens with one sealed frame (`IntroSync`), because its re-seals reuse ids the resetter already
  holds and never reach the ratchet.
- **A reset the peer would refuse is never sent** (ADR 2026-09.qerd). The receiver adopts a flagged init
  only a minute after the last replacement it adopted from this peer, and drops a refused one as a
  `DUPLICATE`; the sender has meanwhile purged the root the receiver is still on, and its own heuristic is
  floored for six hours. So the sender looks at the session it holds first. Over its **own init, still
  unconfirmed, against the peer's current prekey** it never mints a second root: a plain one (a first
  send's) is *marked* — the `CTL_SESSION_RESET` sealed under it, v2, `flags = RESET` on a header that still
  carries the same init, so the peer resolves it idempotently or adopts it under the reset floor, and the
  recovery runs the same (it keys on the ctl, not on a purge) — and one that already is a reset is declined
  for a minute, then re-rooted. When the heuristic asks, over its own init **confirmed under a minute ago**
  it declines: the unreadable frames prove the peer held another session, so taking the init was a
  replacement that started its floor, no later than it sealed the answer (a reset asked for on demand goes
  out — on first contact the peer established, which starts no floor). The first start after a **backup restore**, whose
  ratchet tables came back empty, resets every peer a session was wiped with (DM threads and the other
  members of every group) and treats any session it finds as made since the wipe: the marker under it,
  confirmed or not, or nothing when a reset already went. `IntroSync` answers a marked init once more even if
  it answered the same init unmarked, since the marked frame may be the one the peer adopted. The group seed
  re-send floor counts only seeds sealed under the root held now, so the forced flush a reset triggers is
  never refused by a seed that went out under the root it replaced (`GROUP_FORWARD_SECRECY.md` §3).

## 8. Export API (for the internet relay plane — consumer spec'd)

```
pairwiseRoot = HKDF(sessionRoot, salt = 0*32, info = "knit/dm/v2/export/root", 32)   // symmetric
epochSeal    = HKDF(epochExport, salt = 0*32, info = "knit/dm/v2/export/epoch", 32)  // rotates per epoch
```

`pairwiseRoot` is what the spool plane derives DM scope ids and seal keys from — the consumer shape
is now pinned by `docs/SPOOL_PROTOCOL.md` §3 (`ScopeCrypto` implements it; still no runtime caller
until the client plane ships). `epochSeal` stays API-only: the spool spec's v1 outer seal is
deliberately scope-static (its §4.2 records why per-epoch seal keys deadlock on fresh epochs), and
this surface is reserved for the registered `sealv = 2` epoch-keyed extension. Epoch secrets are
pairwise-shared per (direction, epoch) — the receiver derives the same `epochSecret` to decrypt —
so either side could compute the other's seal keys for a scope when that extension lands.

## 9. Security claim (honest, epoch-granular)

Full compromise of a device at time T exposes, per DM conversation:

1. **Stored plaintext history** (`messages`, SQLCipher at rest) — outside the crypto scheme's
   scope, as in every E2E messenger.
2. **Recorded ciphertext of epochs whose DH is still computable from retained material**: epochs
   whose own-side priv is still in `ratchet_local_epochs` (deleted at superseded + peer-acked +
   48 h; ≤16 per peer; 30 d hard cap), plus init-epoch traffic sealed against a retained SPK
   (≤ ~35 d).
3. **Future traffic until both sides complete one fresh epoch each** after the attacker loses live
   access (round-trip healing).

Identity-file-only compromise (no DB) exposes only slice 2's init-epoch portion. Anything beyond
the retention horizons is irrecoverable — that is the forward-secrecy guarantee.

Non-goals: per-message PFS; one-time prekeys; deniability changes (frames stay Ed25519-signed, as
v1); hiding DM routing metadata (`recipientId` and epoch metadata remain visible to relays, as
today); group/broadcast coverage (separate designs).

## 10. Constants (convergence-relevant ones mirror custody's — change together or not at all)

| Constant | Value | Tied to |
|---|---|---|
| `MAX_EPOCH_MESSAGES` | 200 | per-sender custody quota |
| `MAX_EPOCH_NUMBER` | 2^24 | header sanity ceiling (unreachable by a real conversation) |
| `MAX_EPOCH_JUMP` | 1024 | per-adoption epoch jump bound, once anchored |
| `MAX_EPOCH_AGE_MS` | 24 h | custody TTL |
| `PREV_ROOT_TTL_MS` | 48 h | 2× custody TTL |
| recv-epoch / skipped-key sweep | 48 h after last use | 2× custody TTL |
| skipped keys | ≤200/epoch, ≤2000 global | epoch cap; DoS bound |
| local epoch privs | superseded+acked+48 h; keep newest 3; ≤16/peer; 30 d hard | the PFS window |
| SPK rotation / retention | 7 d / current + 4 | initiation-lateness window |
| reset trigger / outbound rate / inbound rate | 3 distinct ids / 6 h / 1 h | abuse + loop bounds |
