# End-to-end encryption (implemented)

DMs and group chats are E2E-encrypted; the broadcast "Nearby" room stays plaintext by design (no fixed
recipient set). Three crypto schemes coexist, discriminated by `EncEnvelope.v`:

**v3 — v2's DM form, compacted (ADR 059).** The same ratchet, chain, epochs and header as v2 below, with a
derived nonce (`RatchetCrypto.messageNonce`; the `nonce` field rides empty so every fielded build still
decodes and *carries* the frame — `canCarry` decodes the payload), the ratchet header bound into the AEAD's
associated data, and the labeled `MessageContentV2` plaintext (`mesh/crypto/MessageContentV2.kt`: integer
keys, raw ids). Chosen per peer by `mesh/crypto/CryptoScheme` from the pinned profile's `CAP_RATCHET` +
`CAP_CRYPTO_V3`; a content the compact codec cannot carry canonically seals v2 instead. What it buys:
AckSync's `relay = false` live-link tick travels **unsigned** toward a v3 author — the AEAD is the
authenticator — at ~222 B, one packet on every fast plane. Resets, group-key ctl DMs and the group form
stay v2.

**v2 — DMs, forward-secret (the epoch-rekey ratchet).** The default between current builds: outbound
v2 whenever the peer's pinned profile advertises `Protocol.CAP_RATCHET` **and** carries a
`ProfileContent.prekey` (both ride one signed frame). X3DH-style bootstrap off the signed prekey (no
round trip — the first DM to an offline peer still works), then per-epoch X25519 rekeying with a
forward-only message-key chain; the AEAD key is *derived*, never wrapped (`EncEnvelope.keys` is empty,
`EncEnvelope.r` carries the ratchet header). Epochs advance on conversational turnaround, at 200
messages (= the per-sender custody quota), and at 24 h (= the custody TTL); retention of our own epoch
privs IS the PFS window. Session state lives in the `ratchet_*` Room tables (dies with a DB wipe — the
reset path recovers); signed-prekey privates live in `identity.key` beside the identity so in-flight
initiations survive a wipe. The pure engine + session service are `mesh/crypto/ratchet/`
(`RatchetEngine`/`RatchetSessions`, plain-JVM-testable); the full normative scheme — derivation labels,
advance rules, both-initiate races, replacement/reset, the honest security claim — is
**`docs/FORWARD_SECRECY_RATCHET.md`**. Two integration rules matter constantly: decrypt is two-phase
(lock-free peek for moderation, then re-open + commit atomically with the message row;
transaction-outer/mutex-inner), and the **pre-decrypt exists-gate** in `decryptAndDeliver` is what
makes deleting used message keys safe under custody's routine re-serves.

**v2, group form — groups, forward-secret (the sender-key ratchet).** Shares scheme version 2 with
the DM ratchet (both landed in one never-released bump; the forms split on addressing — a group
frame carries `EncEnvelope.g`, a DM `EncEnvelope.r`). The default between current builds: outbound
group-form whenever EVERY other member's pinned profile advertises `CAP_RATCHET` + a prekey AND the
epoch's seed seals to every member — all-or-nothing per message,
any shortfall demotes that message to v1 (which every build reads), re-evaluated per send. Each member
mints a random per-group epoch seed driving a forward-only chain (no DH — trust/freshness/healing are
the pairwise v2 DM ratchet's, which carries the seeds as `MessageContent.ctl = CTL_GROUP_KEY` DMs);
the group frame is `EncEnvelope.g = GroupRatchetHeader {se, n}` with empty `keys` (~10 B vs v1's
~500 B of wraps). Availability inverts the DM form's frame-self-sufficiency: a group frame needs its
sender's seed DM
first — recovery is the persistent seed outbox (`group_key_sends`, acked via `CTL_GROUP_KEY_ACK`),
proactive re-sends (profile arrival / neighbor join / session reset), and the rate-limited
`CTL_GROUP_KEY_REQ` key-request loop, which together subsume the old "group key-gap retransmit" gap
for ratcheted groups. Leave-rekey is atomic with the roster shrink and **eventual** (bounded by the signed
`groupleave` frame's convergence). Engine/facade: `GroupRatchetEngine`/`GroupRatchetSessions` (one
shared ratchet mutex with the DM facade); state in the `group_*` tables; normative spec:
**`docs/GROUP_FORWARD_SECRECY.md`**. The roster it distributes to is integrity-pinned
(`InboundPipeline.vetRoster`: the founding set only ever comes from a roster whose id IS its hash;
membership shrinks only via signed leaves).

**v1 — the fallback (static keys: pre-ratchet peers, mixed-capability groups).** A per-message random content key AES-256-GCM-
encrypts the `MessageContent` (body + mentions + attachment refs) into an `EncEnvelope` carried inside
the encrypted `ChatContent.enc` payload, and the content key is wrapped (Tink HPKE/X25519) to each
recipient. Inbound v1 stays accepted forever; outbound v1 remains the fallback (peer lacks the cap or
prekey — `dmSealedV1Fallback` counts it).

Identity keypairs live in `IdentityKeyStore` (AndroidKeyStore-wrapped, **outside** the DB, together
with the ratchet prekeys), advertised via `ProfileContent.pubKey`, pinned self-certifying into
`PeerEntity.pubKey` (immutable per nodeId), and confirmed out of band via the safety-number/QR screen
(`PeerEntity.verified`) — identity keys are unchanged by the ratchet, so safety numbers are stable.
Image attachments are encrypted to a per-attachment key and content-addressed by ciphertext hash, so
`BlobExchange`/`BlobStore` are unchanged. The key belongs to the file, not the send: a phone seals a plaintext once,
and a file it passes along goes out under the identity it arrived under — a sealed copy's hash and key, or a room
picture's own plaintext hash with **no key** (`AttachmentIdentities`, ADR 2026-10.mptm). So a sealed
`MessageContent` may name a key-less attachment, and a carrier can see that two frames carry one file — never what
it is, unless it holds the key or the room post. Since ADR 035 the attachment's **type** stays inside the seal
too: a sealed frame sets `ChatContent.attachmentHash` and nothing else, so a relay or carrier no longer
learns whether it is carrying a photo or a voice note (size still implies plenty). `MessageContent`
carries the real mime, `InboundPipeline.plaintextContent` substitutes it on delivery, and the spool
fetcher resolves it from its own row (`MeshManager.scopeBlobs`) rather than the frame. The residual is
the blob transfer itself — `LinkFraming.FileHeaderWire` still names a mime to whoever pulls the bytes. **Decrypt/verify failures must never throw out of the
inbound handler** — `onDeliver` runs before the router schedules the relay, so a throw would stop
forwarding; the v2 path's typed failures (`RATCHET_*` drop reasons) are delivery-local for the same
reason, and `canCarry` never inspects the scheme version.

**One signature authenticates every flooded frame** (encrypted *and* plaintext: broadcast `chat`,
`profile`, `groupupdate`, `groupleave`, `reaction`, `receipt`). `WireEnvelope.sig` is raw Ed25519 over
`WireEnvelope.signed` (the canonical `RelayEnvelope` CBOR, which includes the encrypted `ChatContent.enc`
for a DM/group message), and `MeshManager.verifyInbound` (the gate at the top of `onDeliver`) verifies it
**byte-exact over the received `signed` bytes** — no re-encode — and drops any that fail, closing the gap
where a relay could forge a frame (e.g. a profile with a different name) under another node's `senderId`.
Verification reuses the key path (`peers.find(senderId).pubKey` → `PublicKeyBundle.verifier()`, guarded
by `NodeId.fromPublicKeyBundle == senderId`); a `profile` uses the `pubKey` in its own `ProfileContent`
payload since first contact precedes any pin. `blobreq` stays unsigned, and so is the v3 live-link tick — the
one other shape `verifyInbound` admits without a signature (`relay = false`, DM-form chat addressed to us by a
pinned peer), authenticated by its ratchet AEAD instead; every other empty-signature frame drops as
`UNSIGNED_REFUSED`. `EncEnvelope.v`/`MessageContent.v`
gate the crypto-scheme/content-schema versions (unknown ⇒ drop locally + count, but still relay — a
delivery gate, never a relay gate; see `docs/WIRE_COMPAT.md`).

Receipts and reactions are sealed since ADR 018 (`docs/ENCRYPTED_RECEIPTS_REACTIONS.md`): both ride
as `MessageContent.ctl` frames (`CTL_RECEIPT`/`CTL_REACTION`) inside ordinary v2 CHAT frames — DM
form for receipts and DM reactions, group form for group reactions — falling back to the legacy
cleartext frames (never v1: a pre-ratchet build would strip the unknown ctl and persist an empty
bubble) toward incapable peers/groups, and staying cleartext in the broadcast room by design.
Sealed receipts retire the carrier vaccine-purge: nobody can parse them, so delivered DMs age out of
custody on the 24 h TTL uniformly (the recipient custodies its own inbound DMs and a cleartext ack
self-vaccinates — both required for digest convergence, ADR 006). The group tick escalates since
ADR 033: toward an absent sealed-capable author, `AckSync` batches the acks (`MessageContent.acks`)
and originates ONE sealed ctl DM `relay = true` — flooded, custodied, spool-eligible — so the ✓✓
converges like the message it acks; a live-linked author keeps the unicast `relay = false` tick, and
cleartext/broadcast ticks never escalate.

The ratchet export APIs now have a live consumer: `docs/SPOOL_PROTOCOL.md` (ADR 019) derives
internet-relay scope ids and seal keys from `RatchetCrypto.exportRoot` (DM) and a spec-minted shared
group root, in `mesh/crypto/scope/ScopeCrypto`. `RatchetSessions.exportedRoots()` is the seam the
client plane (`mesh/spool/ScopeSync`) reads — **`pairwiseRoot` exports only, under the ratchet mutex,
unconfirmed sessions skipped**, so raw session roots never leave the ratchet facade. The outer seal is
scope-static by design (the spec's §4.2 records why per-epoch keys deadlock); the `exportEpochSeal`
surfaces stay reserved for its registered `sealv = 2` extension. Group scopes derive from the shared
group root instead: minted locally (`mesh/spool/GroupRootPolicy`, persisted in `group_roots`),
gossiped as `GroupKeyPayload.gr` on the existing `CTL_GROUP_KEY` ctl DM, with `rootVersion` doubling
as the scope epoch so a departure re-mint rotates id and seal keys as one. **Any** member may mint —
damped by a preferred-minter-plus-grace rule, not restricted to the creator (spec §3.2, ADR 019's M4
amendment). Roots are adopted and re-gossiped even with the Internet plane switched off; only minting
checks the switch.

**Contacts at a distance (ADR 042, `docs/CONTACT_CARD.md`).** A **contact card** is the `knit-id:v1` QR
payload as a signed link (`mesh/crypto/ContactCard`): body `{v, id, pk, name?, sp?, iat}`, Ed25519 over
`"knit/card/v1" ‖ body`, never re-encoded to verify. The identity key's fourth signing input is the link proof,
`"knit/link/v1/proof" ‖ role ‖ u32be(len) ‖ helloD ‖ u32be(len) ‖ helloR` (`mesh/crypto/LinkProof`, ADR 2026-11.264w),
disjoint from the card's and the prekey's labels at byte 5 and from frame CBOR by its leading `k`. On a sealed link
(ADR 2026-11.8mhs) the proof signs the v2 transcript instead, `"knit/link/v2/proof" ‖ role ‖ eD ‖ eR ‖ …`, which binds
both ephemeral X25519 keys; its label parts from v1 at byte 11, so neither form's signature verifies as the other's.
The link's AEAD keys come from `HKDF(ss, eD ‖ eR, "knit/link/v1/seal/d2r" | "…/r2d")` (`mesh/crypto/LinkSeal`), KDF
info disjoint from every other label (the shared `knit/link/v1/` prefix with the lane keys and the v1 proof is
harmless: KDF info is never signed). Import pins + accepts, never verifies. The intro
handshake is the existing sealed `CTL_PROFILE` DM sent to a peer with no session (`MeshManager.sendIntroTo`
→ `sendCtlDm` → `ratchet.sealDm` initiates X3DH off the card-pinned prekey), driven by the pure
`mesh/IntroSync` (send when sealable, re-send every 20 h while unconfirmed, answer an init-bearing peer
hourly, 48 h grace after confirmation). On the spool plane the pair meets at a **pair scope** before any
session exists: `ScopeCrypto.pairSecret(IK_self, IK_peer)` (static-static X25519 over the identity DH keys,
used nowhere else) → `pairScopeId`/`pairSealKeys` with the DM context — an ordinary DM-form scope the
registry derives from `ScopeRegistry.pairs` while the intro is pending or in grace. The one
identity-derived scope: a stolen identity file plus the peer's bundle yields its id and outer seal
(bootstrap-era routing metadata), never content, never a DM/group scope (spec §3.5, §10.3).

The seal is **deterministic** (SIV-style keyed nonce), which is load-bearing beyond dedup: it makes a
frame's blob id a pure function of (scope, frame), so `ScopeSync` derives its held-set on demand
instead of persisting one. Anything that made sealing non-deterministic would break spool convergence
*and* force a `forward_store` schema change.

Still deferred for E2E (see `memory/roadmap.md`): encrypting the broadcast room (a deliberate
separate decision).
