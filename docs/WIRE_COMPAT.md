# Wire forward-compatibility

Read this before touching anything under `mesh/protocol/` or `mesh/crypto/`. Once the app has a real
installed base, a breaking wire change silently partitions the mesh (there is no version negotiation
that can route around it). The format is layered specifically so that almost every future change can be
**additive** instead. These rules keep it that way.

## The layered wire (why it is resilient)

`mesh/protocol/Wire.kt` defines three layers, serialized with one CBOR config
(`ignoreUnknownKeys = true`, `encodeDefaults = false`):

1. **`WireEnvelope`** — the on-radio unit, **frozen forever**. Holds the mutable routing counters
   (`ttl`/`hops`), a `relay` flag, the raw Ed25519 `sig`, and the opaque `signed` blob. It is the only
   layer a relay re-encodes: `WireEnvelope.relayed()` rewrites only `ttl`/`hops` and reuses `signed` +
   `sig` **by reference**, so the bytes the originator signed are forwarded verbatim at every hop.
2. **`RelayEnvelope`** — what `signed` decodes to: only the cleartext fields a relay or store-and-forward
   carrier needs to route (`type`, `id`, `senderId`, `sentAt`, `recipientId`, `group`) plus an opaque
   `payload`. Relays never re-encode it, so additive fields here survive an old relay too.
3. **Per-type content** (`ChatContent`, `ProfileContent`, …) inside `payload`. Only endpoints parse it.

Two load-bearing decisions you must not undo:

- **`sig`, `signed`, and `payload` are `@ByteString ByteArray` (opaque), never nested `@Serializable`
  objects.** If `signed` were a nested object, the outer re-encode on relay could legally reorder CBOR
  keys and break the signature — the exact bomb the layering removes.
- **`RelayEnvelope.type` is a plain `String` discriminator on a concrete class, not kotlinx sealed
  polymorphism.** An unknown `@SerialName` makes a polymorphic decode *throw* (→ frame dropped, not even
  relayed). A plain string decodes fine, so an old build still routes and forwards a `type` it doesn't
  understand — closing the new-frame-type black hole.

## The four version layers

Each evolves independently; bump the right one:

- **Endpoint-info `protoVersion` + `capabilities`** (`Protocol.VERSION` / `Protocol.CAP_*`): the
  advert/handshake hint (Wi-Fi Aware `serviceSpecificInfo` / the BLE service-data payload), known at
  connection time, **unauthenticated** — a routing/degradation hint only, never a trust input.
- **`RelayEnvelope.type` registry**: `chat`, `groupupdate`, `groupleave`, `profile`, `receipt`,
  `reaction`, `blobreq`, `keyreq`, `typing`, `meshpost`.
- **`EncEnvelope.v`**: the E2E crypto scheme — `1` = static keys (AES-GCM + per-recipient HPKE wrap),
  `2` = the ratchet schemes (AES-GCM under a derived key, `keys` empty; the DM form's header rides
  `EncEnvelope.r` — `docs/FORWARD_SECRECY_RATCHET.md` — and the group sender-key form's rides
  `EncEnvelope.g` — `docs/GROUP_FORWARD_SECRECY.md`; forms split on addressing, not on `v`),
  `3` = the DM ratchet with a derived nonce (the field rides empty), a header-bound AAD and the labeled
  `MessageContentV2` plaintext (ADR 059; `docs/FORWARD_SECRECY_RATCHET.md` §5).
- **`MessageContent.v`**: the decrypted plaintext schema (never emitted while it equals the default — which
  is why the v3 compact schema is discriminated by `EncEnvelope.v` and carries a label-0 version of its own).

## Rules that keep changes additive

1. **Add only nullable/defaulted fields.** New fields on any wire/content/envelope type MUST be
   `T? = null` or have a default — `encodeDefaults = false` then omits them on the wire and
   `ignoreUnknownKeys = true` makes an older peer ignore a newer peer's extra field. Precedent:
   `ProfileContent.deviceTag`, `protoVersion`, `capabilities`. (Exception: `@ByteString ByteArray` fields
   are kept non-default — kotlinx can't cheaply detect a default `ByteArray` — so add a new opaque blob
   as its own type, not a defaulted `ByteArray` field.)
2. **Never rename, re-type, or repurpose an existing field.** CBOR keys by the Kotlin property name;
   changing a name, type, or *meaning* in place silently mis-decodes against deployed peers. To change
   semantics, add a new field and deprecate the old.
3. **Never recycle a `type` string** (or a capability bit position). A retired `type` is burned forever;
   reusing it makes an old peer decode the new frame as the old type. Capability bits and version numbers
   are append-only; versions only increase.
4. **Signature input is the whole `signed` blob, verbatim.** `MeshManager.sign` signs
   `WireCodec.encodeEnvelope(env)`; `verifyInbound`/`canCarry` verify against the exact `wire.signed`
   bytes received. Do not reintroduce a per-field canonicalization or re-encode-before-verify step — the
   verbatim-bytes contract is what makes additive fields safe through old relays. `ttl`/`hops` are the
   only mutable-in-flight fields and they live in the (unsigned) `WireEnvelope`, never in `signed`; if
   you ever need another in-flight-mutable field, it MUST go in `WireEnvelope` (unsigned), not in
   `RelayEnvelope`.
5. **A version gate is a *delivery* gate, not a *relay* gate.** An unknown `EncEnvelope.v` /
   `MessageContent.v` → drop locally + `metrics.onDropped(...)`, but still relay/carry (never throw out
   of `onDeliver`; never gate `canCarry` on a scheme version) so a peer that *can* read it still
   receives it.

## Wire-breaking vs. additive changes

**Breaking** (needs a coordinated one-time bump of **both** discovery markers — Wi-Fi Aware
`SERVICE_NAME` *and* BLE `SERVICE_UUID` — plus the DB version) if it: removes, renames, re-types, or
repurposes a field or a `type`; changes `WireCodec`'s config or the `@ByteString` opacity of
`signed`/`sig`/`payload`; changes what `signed` is signed over, the AEAD `header`, the `NodeId`
derivation, or a discovery marker; or makes `RelayEnvelope.type` polymorphic.

**Additive** (safe) if it only adds a nullable/defaulted field to a content/envelope type, a new `type`
string with its own content class, or a new capability bit — and rule 4 holds.

A change that genuinely needs a break does **not** get to schedule one. Park it in
`docs/NEXT_WIRE_BREAK.md` — the staging list of everything waiting on the next coordinated break, so that
if one ever happens they all ride it together and no single item has to justify the partition alone.

> **Coordination-plane message tags (transport-local, not the wire).** The Wi-Fi Aware fast path frames
> its `sendMessage` payloads with a leading tag byte; the registry is **append-only** like capability
> bits: `0x01` legacy tagged-CBOR frame (kept forever), `0x02` burned, `0x03` compact frame / `0x04`
> fragment (`mesh/link/FastFrameCodec`, emitted only toward peers advertising
> `Protocol.CAP_FAST_COMPACT = 0x20`), `0x05` transcoded frame (`mesh/link/FrameTranscoder`, ADR 060, only
> toward `Protocol.CAP_FRAME_TRANSCODE = 0x80`). `0x03`/`0x04` re-frame only the outer `WireEnvelope` —
> `signed`/`sig` pass through byte-exact (rule 4 holds by construction); `0x05` re-encodes `signed` itself
> but the receiver **rebuilds the byte-identical canonical CBOR before verifying**, so the bytes that are
> signed, stored and relayed still never change. Adding a tag + gating cap bit is therefore additive and
> needs **no** `SERVICE_NAME` bump; old builds count-and-drop unknown non-printable tags. The compact
> form's preset deflate dictionary `DICT_V1` is frozen (golden-hash-pinned); tuning mints `DICT_V2` under a
> fresh dictId, never edits V1. The transcoder's schema 1 is frozen the same way (golden vectors + label
> map): a richer schema is a new tag.

> **Pre-1.0 alpha history.** The precedents below (DB v19 / v21 / v22) document the coordinated wire/discovery
> breaks taken *during pre-release alpha*, when the app had no installed base and every schema bump wiped
> destructively. They are retained as the historical break record and cross-platform rationale. **v1 is the
> production launch baseline** — the markers were reset in lockstep (`SERVICE_NAME` `_knitmesh1._tcp`, BLE
> `SERVICE_UUID` `0xFE30`, `Protocol.VERSION` `1`, DB `v1`); from v1 on the DB migrates forward (no destructive
> fallback) and any wire change either follows the additive rules above or is a real, coordinated break with a
> genuine installed base to protect.

**Precedent — populating an existing field in a new case is additive, not a rule-2 repurpose.** DB v19
began setting the already-existing `ChatContent.attachmentHash`/`attachmentMime` on E2E DM/group frames
too (with the message's *ciphertext* hash), where they were previously null — only the plaintext
broadcast room filled them. The field's *meaning* is unchanged ("the content address to pull for this
message's image"), so an old peer harmlessly ignores it (and on delivery overwrites it with the identical
value decrypted from `MessageContent`), and the frame still verifies byte-exact. The decryption key stays
sealed inside `MessageContent`. This lets a relaying **carrier** — blind to the encrypted refs — see the
blob and custody it (store-and-forward for images). No `SERVICE_NAME` bump; the DB bump is local.
*Metadata cost:* a carrier learns a message carries an image (~size); a fresh per-send attachment key
means the ciphertext hash never correlates identical images across sends.

> **Partially reverted by ADR 035 (see the un-populating precedent below).** The *hash* half stands and
> always will — it is what makes custody of attachments possible. The *mime* half was withdrawn: custody
> addresses bytes by hash and never needed the type, so the cleartext mime bought nothing and told a
> carrier whether the message was a photo or a voice note. A sealed frame now sets `attachmentHash`
> alone.

**Precedent — a coordinated break (DB v21): the 128-bit nodeId.** The nodeId was widened from an 8-char
`[a-z0-9]` (~41-bit) hash to **128 bits** of SHA-256, RFC4648-base32-encoded to a 26-char `[a-z2-7]`
string (`NodeId.kt`, salt bumped to `knit-node-id-v2:`). Since the `NodeId` derivation is a breaking
change (§ above) — every node re-derives a different id from the *same* keypair, so signatures/pins/
custody against the old ids no longer verify — all three markers bumped in lockstep: `SERVICE_NAME`
`.v6 → .v7`, BLE `SERVICE_UUID` `0xFE30 → 0xFE31`, DB `version 20 → 21` (destructive wipe clears the
stale pins + old-format custody). The BLE advert also changed shape (the id now rides as its raw 16
bytes, and the redundant service-UUID-list AD was dropped to keep the payload inside the 31-byte legacy
budget — see `BleAdvertPayload`), which the `SERVICE_UUID` bump already partitions. `Protocol.VERSION`
went `1 → 2` for honesty (nothing gates on it). The keypair itself is untouched (it lives outside the DB),
so no identity is lost — every device just re-derives a wider id.

**Precedent — a coordinated break (DB v22): de-Tink the crypto wire layout.** The published key bundle and
the crypto byte layouts were made Tink-free for cross-platform (iOS) interop: `PublicKeyBundle` now carries
two **raw 32-byte** keys (CBOR `{sigPub, hpkePub}`, `@ByteString`) instead of serialized Tink `Keyset`
protos; `IdentityKeyStore` uses the `_RAW` (NO_PREFIX) templates so `WireEnvelope.sig` is bare 64-byte RFC
8032 (was 69 = `0x01‖keyId[4]‖sig`) and `WrappedKey.wk` is bare RFC 9180 `enc‖ct` (was Tink-prefixed); and
both CBOR codecs (`WireCodec.cbor`, `cryptoCbor`) pin `useDefiniteLengthEncoding = true` (kotlinx's default
is indefinite-length, the awkward case for a Swift codec) + explicit `encodeDefaults = false`. Android keeps
Tink internally — the raw bytes are extracted from / re-imported into `KeysetHandle`s (the reconstruction
`HpkeParameters` are asserted to match the `_RAW` template in `PublicKeyBundleTest`). Because the bundle
bytes are hashed into `NodeId`, every node re-derives a different id (breaking, § above), so all markers
bumped in lockstep: `SERVICE_NAME` `.v8 → _knitmesh3._tcp` (also adopting the Apple-`WiFiAwareServices`
`_name._proto` form — name label ≤15 chars, `_tcp` matching the NDP's TCP data path; the trailing digit is
the version marker now), BLE `SERVICE_UUID`
`0xFE31 → 0xFE32`, `Protocol.VERSION` `2 → 3`, DB `version 21 → 22` (destructive wipe clears stale pins +
old-format custody — the **last** pre-launch destructive bump, before the production reset to the v1 launch
baseline; see `docs/ARCHITECTURE.md` §9 for the migrate-forward posture). The keypair is untouched (only its
public-key *encoding* changed). Golden
vectors (`GoldenVectorTest`) pin the definite-length bytes of every wire type + the raw-key bundle so a
future iOS codec has byte-exact fixtures. Also bundled: the two-way responder HELLO (`LinkHandshake`) so a
link's peer identity is confirmed over the socket, not parsed from the (unauthenticated) discovery advert.

**Precedent — an additive crypto-scheme bump (`EncEnvelope.v` 1 → 2, the DM epoch ratchet).** The whole
forward-secrecy scheme (`docs/FORWARD_SECRECY_RATCHET.md`) shipped without touching a discovery marker,
`Protocol.VERSION`, or any v1 byte: `EncEnvelope` gained the nullable `r: RatchetHeader?` (rule 1 — its
`@ByteString` fields live inside the new `RatchetHeader`/`RatchetInit` types, honoring rule 1's
exception), `ProfileContent` gained the nullable `prekey: PrekeyInfo?`, `MessageContent` gained the
nullable `ctl` marker *inside* the ciphertext (same schema version — additive there too), and
`Protocol.CAP_RATCHET` took the next capability bit. A v1-era build decodes a v2 envelope fine
(`ignoreUnknownKeys` drops `r`), rejects it at the **version gate** (rule 5: drop-locally + count,
still relay/carry — `canCarry` never looks at `v`), and keeps custodying it for peers that can read it.
The v2 fixtures ride alongside the untouched v1 golden vectors in `GoldenVectorTest`; the
old-decoder-ignores-`r` behavior is pinned in `WireSerializationTest`. Senders gate on the peer's
pinned profile carrying **both** `CAP_RATCHET` and a `prekey` (one signed frame — no stale-capability
window), so a v2 frame is only ever addressed to a build that can open it.

**Precedent — extending an UNRELEASED version instead of bumping (the group sender-key ratchet
folded into v2).** Version numbers are only spent when a build that understands the old meaning has
shipped; the v2 crypto scheme never left this branch, so the group scheme
(`docs/GROUP_FORWARD_SECRECY.md`) rides the SAME `EncEnvelope.v = 2` rather than minting a v3 — the
two forms split on addressing (a DM carries `r`, a group frame the new nullable
`g: GroupRatchetHeader?` — two plain ints, no `@ByteString`), and `MAX_SUPPORTED_VERSION` stays 2.
Likewise `Protocol.CAP_RATCHET` covers both forms (they ship together; a second bit would never vary
independently) and the group state tables ride the same unreleased DB v2 migration. The additive
fields still follow rule 1: `MessageContent` gained the nullable `gk: GroupKeyPayload?` + three ctl
values *inside* the ciphertext (`CTL_GROUP_KEY`/`_REQ`/`_ACK` — legal precisely because unknown ctl
values were already consumed as silent no-ops), and `GroupInfo` gained the nullable `departed` list
(the roster-integrity phase). The epoch seeds themselves never touch a new wire surface: they ride
*inside* ordinary v2 DM ctl frames, which v1 relays already custody (the ADR 016 argument
re-applied — a new frame type would not be custodial to old builds). Senders gate the group form on
**every** other member's pinned profile carrying `CAP_RATCHET` + a prekey; any ineligible member
demotes that message to v1, so a ratcheted group frame is only ever addressed to a roster that can
open it. **The rule this precedent adds: released version numbers are append-only; unreleased ones
are still yours to edit.**

**Precedent — the third additive `MessageContent` change (sealed receipts/reactions, ADR 018).**
No new wire surface at all: `MessageContent` gained the nullable `ack: String?` and
`rp: ReactionPayload?` plus two ctl values (`CTL_RECEIPT = 5`, `CTL_REACTION = 6`) — all inside the
ciphertext, legal because unknown ctl values are consumed as chain-advancing silent no-ops (pinned by
`anUnknownCtlCodeAdvancesTheChainAndDoesNothing`), and byte-invisible on ordinary messages
(`encodeDefaults = false`; pinned by `aPlainMessageEncodingIsByteIdenticalWithTheNewFieldsDefaulted`).
The cleartext `receipt`/`reaction` frame types stay accepted inbound forever; `ReactionPayload` is
field-compatible with `ReactionContent` (same CBOR — `GoldenVectorTest` pins both, retraction form
included). One semantic split rides on the ciphertext boundary rather than any wire field: carriers
vaccine-purge on a cleartext receipt exactly as before but cannot on a sealed one, and the custody
rule keys on that form (a property of the frame bytes, identical at every node) — see
`docs/ENCRYPTED_RECEIPTS_REACTIONS.md` §4 for why that stays ADR 006-convergent.

**Precedent — the spool plane's first mesh-wire field (`GroupKeyPayload.gr`, group scopes / M4).** The
Internet plane got its DM half (`docs/SPOOL_PROTOCOL.md`) with *no* mesh wire change at all; group
scopes need exactly one, and it is additive: `GroupKeyPayload` gained the nullable
`gr: GroupRootPayload?` — the shared group root the group scope id and seal keys derive from
(SPOOL_PROTOCOL §3.2/§3.3). It rides the **existing** `CTL_GROUP_KEY` ctl DM rather than a new frame
type or a new ctl value, for the ADR 016/018 reason a third time over: `isCustodial` is a fixed list on
deployed builds, and the root is precisely the thing that must survive store-and-forward to reach an
offline member. `GroupRootPayload` is its own type because its `root` is `@ByteString` and rule 1's
exception keeps those non-default — the `GroupSeed`/`RatchetInit` shape. Two consequences worth pinning:
a distribution may now carry `keys` **empty** with only `gr` set (a member that holds a root but has
never sealed a group frame), which is byte-legal and is why `GoldenVectorTest` pins that exact fixture
alongside the seeds-and-root one; and an old build decodes the ctl, ignores `gr`, and still advances the
DM chain as the pinned silent no-op. No discovery marker, no `EncEnvelope.v`, no `MessageContent.v`, and
no new ctl value is spent. The DB **does** bump (v2 → v3, the `group_roots` table) — local state only,
with a tested `KnitMigrations` entry.

**Precedent — a whole milestone that touches no mesh wire at all (attachments over spools, M5).** The
spool plane's second feature milestone shipped image bytes across the Internet and added **no** field, no
`type`, no ctl value, no capability bit, and no DB migration. Worth recording precisely because the
default expectation is the opposite: every earlier plane milestone bought its capability with a wire
change. What made it unnecessary is the DB v19 precedent above — `ChatContent.attachmentHash` /
`attachmentMime` already ride in cleartext on E2E frames so a blind carrier can custody the image, and
that is exactly the reference an Internet fetcher needs too. (Since ADR 035 the mime half is gone and the
fetcher resolves the type from its own decrypted row; the *hash* is still the whole reference it needs.) The attachment's *size* never had to join
them, because the spool reports its chunk count. Everything new lives in the client↔spool record layer
(`docs/SPOOL_PROTOCOL.md` §7.3), which has its own additive rules and its own vectors, so `GoldenVectorTest`
is untouched and only `ScopeVectorTest`/`SpoolRecordsTest` gained rows — regenerated together, and
independently re-pinned by `knit-spool`'s `SpecVectorTest`. *Metadata cost:* unchanged on the mesh; on the
Internet plane a spool learns a scope holds an object of roughly `total × 48 KiB` (SPOOL_PROTOCOL §10),
which is stronger than the frame signal and is why the byte quota is per scope.

**Precedent — the fourth additive `MessageContent` change, and the second use of the DB v19 field reuse
(sealed profile updates, ADR 020).** `MessageContent.pr: ProfilePayload?` under a new
`ctl = CTL_PROFILE (8)`. Rule 1: nullable, absent from every frame that does not set it, so no existing
golden vector moved. Ctl `7` stays **reserved** for `CTL_SCOPE_CONFIG` and is named in code so it can
never be recycled. No `EncEnvelope.v` bump, no `MessageContent.MAX_SUPPORTED` bump, no capability bit
(`CAP_RATCHET` already gates it, the ADR 017 precedent), no DB change — an old build consumes the
unknown ctl as the pinned chain-advancing silent no-op.

The carrying chat frame additionally sets the already-existing `ChatContent.attachmentHash` /
`attachmentMime` to the sender's **avatar**, which is the DB v19 precedent above applied a second time:
the field's meaning ("the content address to pull for this message's image") is unchanged, an old peer
ignores it, and it is what lets a blind carrier custody an avatar and the Internet plane fetch one with
no avatar-specific code. *Metadata cost:* a carrier learns this control frame carries an image of roughly
avatar size — the same cost DB v19 already accepted for attachments, and strictly less than the cleartext
`profile` frame it replaces, which floods the name and avatar hash in the clear to everyone.

What did **not** move, and why it is worth recording: group photos needed no wire change at all.
`groupupdate` already carries `GroupInfo.photoHash` in cleartext and is already scope-eligible, so only
the client's attachment-reference reader had to learn about it.

**Precedent — the DB v19 field reuse a third time (inline delivery acks, ADR 054).** `MessageContent.acks`
— the batched-receipt list — populated on a **plain** sealed DM chat, not only on a `CTL_RECEIPT`, so a
reply carries the receipts its author owes the recipient in place of a standalone tick. The field's
meaning ("frame ids this frame acknowledges") is unchanged; the sender attaches it only toward a pinned
profile carrying `Protocol.CAP_INLINE_ACK = 0x40` (the next append-only bit), and an older receiver — which
reads `acks` only on a ctl frame — is never sent one and still gets the standalone tick, so nothing degrades
even if the gate is wrong. No `EncEnvelope.v` or `MessageContent.MAX_SUPPORTED` bump, no ctl, no DB change,
no golden vector moved (`ProtocolTest` round-trips `LOCAL_CAPABILITIES` symbolically). *Metadata cost:* none
on the wire — ~23 B per ack inside the ciphertext, which is what keeps it off a carrier's view.

**Precedent — the fifth additive content change, splitting a field's two jobs (profiles on the spool
plane, ADR 022).** `ProfileContent.version: Long?`. Rule 1: nullable, absent from every frame that does not
set it, so `profileContent`/`profileContentPrekey` did not move and only a new `profileContentVersion`
vector was added. What makes it worth recording is *why* the field exists — the envelope `sentAt` was doing
two incompatible jobs at once. It was the profile's LWW version **and** the frame-global custody expiry key
(`sentAt + ttl`, ADR 006), so a profile nobody had edited for a day was refused as dead on arrival and left
custody entirely: invisible to a radio late joiner, and unavailable to the Internet plane, which can only
seal what custody holds. Splitting them lets `sentAt` be a publish stamp refreshed every 12h while `version`
stays the ordering key.

The compatibility shape is the reverse of the usual one and worth reading before the next such split: a
*new* build reads `content.version ?: env.sentAt`, which is exact for an old peer because `sentAt` is
precisely what the version used to be. An *old* build has no such fallback — it reads the publish stamp as
the version, so its watermark jumps ahead and it then rejects sealed `CTL_PROFILE` updates whose
`payload.version` is the real (smaller) number. Additive on the wire, then, but not symmetric in behaviour;
it was taken while the plane is still testers-only.

**Precedent — the sixth additive content change, batched sealed receipts (`MessageContent.acks`,
ADR 033).** Rule 1: nullable, absent from every frame that does not set it, so no existing golden vector
moved — `messageContentReceipt` (the single form, previously unpinned) and `messageContentReceiptBatch`
were added, and `aPlainMessageEncodingIsByteIdenticalWithTheNewFieldsDefaulted` is the executable rule-1
proof extended to cover it. No new ctl value (the batch rides `CTL_RECEIPT = 5`), no version bump, no
capability bit, no DB change. Why it exists: the sealed group tick escalates into custody when its author
is absent (ADR 033), and per-message custodied ticks would cost roster × messages custody frames — the
batch makes it one frame (and one chain key) per (member, author). The asymmetry, recorded: a ratchet-era
build without the field decodes the batched frame, reads `ack == null`, and applies nothing — the pinned
chain-advancing no-op, acceptable only because the whole sealed-ctl era is on the unreleased v2 train
("released version numbers are append-only; unreleased ones are still yours to edit"). The receiver
applies `ack` plus `acks` per id under the forged-ack guard, `distinct`, bounded at 2× the send cap.

**Precedent — a second whole feature that touches no wire at all (voice notes, ADR 034).** After the M5
attachments milestone above, this is the second time the expected wire change turned out to be unnecessary,
and for the same underlying reason — so it is worth stating as a rule rather than a coincidence. A voice
note is an ordinary attachment carrying an audio MIME: `ChatContent.attachmentMime` /
`MessageContent.attachmentMime` already exist and already ride (the cleartext half only until ADR 035
withdrew it — see the un-populating precedent below), and *populating an existing field in a new
case is additive, not a rule-2 repurpose* (the DB v19 precedent, applied a fourth time — the field's
meaning, "the content address and type to pull for this message", is unchanged). No field, no `type`, no
ctl value, no capability bit, no `EncEnvelope.v`, no `MessageContent.v`. `GoldenVectorTest`,
`ScopeVectorTest` and `SpoolRecordsTest` are all untouched, which is the executable proof.

What *could* have been spent and deliberately was not: a nullable duration and waveform on
`MessageContent`, which rule 1 would have permitted. Both are instead derived from the audio bytes on
**each** side (`VoiceAudio`, run by the sender at ingest and by the recipient in
`InboundPipeline.onObtained`) and stored in local-only columns at DB v5. The argument generalizes: when a
value is a pure function of bytes both ends already hold, deriving beats carrying — the two ends then agree
by construction instead of one trusting a number the other sent, and nothing new leaks to a blind carrier.
The same reasoning kept a quoted voice note's label out of `ReplyRef`: it rides the existing free-text
`snippet` instead, at the honest cost of appearing in the sender's locale. *Metadata cost:* unchanged in
kind, new in content — an `audio/aac` MIME tells a carrier that a message is a voice note, and
its size implies rough duration. (ADR 035 has since withdrawn the MIME half of that, and corrected the
"and a spool" claim — a spool never saw the field. The size half stands.) The DB bumps (v4 → v5) are local only, with a tested `KnitMigrations`
entry.

**Precedent — the first *un*-populating change (the attachment MIME leaves the frame, ADR 035).** Every
precedent above adds a field, a type, a ctl value, or starts filling an existing field in a new case. This
is the first that *stops* filling one: `MeshManager` no longer sets `ChatContent.attachmentMime` on a
sealed frame (`sendChat`, the `CTL_PROFILE` avatar hint, and the `resealAndFlood` retransmit), so a
DM/group frame names the ciphertext hash and nothing else. **The rule this precedent adds: un-populating a
nullable field is additive on exactly the same terms as populating it — the DB v19 precedent run backwards
— provided every deployed reader already tolerates its absence.**

That proviso is the whole of the argument, and here it is discharged rather than assumed. The field keeps
its name, type and meaning, so rule 2 holds and nothing mis-decodes; `encodeDefaults = false` simply omits
the key, so the frame gets *smaller*. On the receive side the sealed copy always won already —
`InboundPipeline.plaintextContent` substitutes `MessageContent.attachmentMime` over the cleartext shell
before the row is written — so an old build types its row, its bubble, its chat-list preview and its
notification stand-in exactly as before. Custody denormalizes `attachmentHash` only; the content digest
folds ids, not payloads; `onCarriedFrame`'s eager blob pull reads the hash. The one non-recipient reader,
`ScopeAttachments.refFor`, already yields a null mime for a `groupupdate` group photo and `ScopeSync`
already reads `ref.mime ?: FALLBACK_MIME` — so the sealed `chat` shape converges on a path that has been
live and tested since M5, and adds no branch anywhere.

Deliberately **not** spent: a generic constant in place of the mime. That would change the field's meaning
from "the type of the referenced blob" to "a placeholder", which *is* a rule-2 repurpose and would need a
new field — besides costing bytes on every attachment frame to say nothing. The `CTL_PROFILE` avatar hint
was nulled with the rest even though `AVATAR_MIME` is a constant that leaked nothing, because leaving it
as the only sealed frame still carrying a mime would have made mime-*presence* a new distinguisher between
a profile update and a user message.

No `EncEnvelope.v`, no `MessageContent.v`, no ctl value, no capability bit, no discovery marker, no DB
change. `GoldenVectorTest` does not move — its `chatContent` fixture is a *plaintext-room* shape (`body`
filled directly), and the room still fills the mime by design — and `ScopeVectorTest`/`SpoolRecordsTest`
carry no mime at all, so `knit-spool` needs no regeneration either. *Metadata cost:* strictly reduced on
the mesh, and **unchanged at a spool**, which never saw the field: `ScopeFrames.seal` seals the whole
`signed` blob, so the Internet plane's leak stays the §10 chunk-count/timing signal. What remains on the
radio is the size (an ~8 s voice note is a distinctive byte range), the fact that the frame carries an
attachment at all, the `FileHeaderWire.mime` on the blob transfer itself (out of scope, ADR 035), and — new
and unavoidable in a staged rollout — the fingerprint that `attachmentHash` without `attachmentMime` is a
patched build.

**Precedent — a third whole feature with no mesh-wire change (contacts at a distance, ADR 042).** Two
people who can only pass each other a short string out of band now pin each other's key and become
contacts, and — with the Internet plane on — end up with a working DM scope, and the mesh wire did not
move: no field, no `type`, no ctl value, no capability bit, no `EncEnvelope.v`, no DB migration. The
handshake is the existing sealed `CTL_PROFILE` DM (ADR 020) sent to a peer whose session does not exist
yet, so `ratchet.sealDm` runs the X3DH initiation off the card-pinned prekey and every deployed build
reads the frame. The new surface is (a) a **contact card** — the `knit-id:v1` QR payload as a signed,
versioned link with its own additive rules and golden vectors (`docs/CONTACT_CARD.md`, `ContactCardTest`)
— and (b) one new label family on the **spool** plane, `knit/scope/v1/pair/id` (SPOOL_PROTOCOL §3.5),
which added four `ScopeVectorTest` rows and moved none; `GoldenVectorTest` and `SpoolRecordsTest` are
untouched, and `knit-spool` needs no change. *Metadata cost:* on the mesh, none (a `CTL_PROFILE` is
wire-indistinguishable from any sealed DM); on the Internet plane, a pair scope's id is stable per pair
rather than per session era, bounded by the pending-plus-grace subscription window (§10.1), and the
identity-file compromise row in §10.3 narrows to *conversation* scopes.

**Precedent — the second additive crypto-scheme bump (`EncEnvelope.v` 2 → 3, ADR 059), the first
un-populated `@ByteString`, and the second unsigned frame form.** Three lessons for the next one. (1) A
scheme bump moves **every** equality branch with the constant: `InboundPipeline.decryptAndDeliver`'s DM arm,
`MeshManager`'s inline-ack give-back, and `ScopeFrames.eligibleForDm` — the spool plane would silently have
stopped carrying v3 DMs had that last one not been widened, and the group arms were deliberately *not*
widened (the group form stays v2). (2) "Omit a field" is not additive even behind a version gate when a
carrier decodes the type: `canCarry` decodes `ChatContent`, so a v3 envelope with no `nonce` would have
been refused custody by every fielded build — a per-build custody rule (ADR 006). The field stays required
and rides *empty*; `WireSerializationTest.everyOlderDecoderShapeDecodesAV3Envelope` decodes the real v3
output through the v1- and v2-era decoder shapes as the executable proof. (3) A plaintext schema that never
emits its version (`MessageContent.v` is elided while default) cannot gate anything, so the compact
plaintext is discriminated by the envelope version and reserves a label-0 version inside itself (plus
labels 12–13 for the additive follow-ons, padding and raw-seed group keys: a new label is additive under
`ignoreUnknownKeys`, a new *form* — the group form — is a new version, pinned by
`InboundPipelineTest.aV3GroupAddressedEnvelopeIsABadHeaderNeverAGroupFrame`). The
unsigned live-link tick is a *receiver-shape* rule, not a wire change: `WireEnvelope.sig` was already allowed
to be empty (`blobreq`); what is new is the one shape `verifyInbound` admits that way and the ordering that
keeps it harmless (before the plaintext branch, before the exists-gate, ctl-only after the open, outside the
reset heuristic). `FastFrameCodec` spent flags bit 4 for it — flag bits are append-only like tags and
capability bits, and a new one is only emitted behind a capability because old receivers drop it.
`GoldenVectorTest` gained seven vectors and moved none; `ScopeVectorTest`/`SpoolRecordsTest` untouched.
*Metadata cost:* none on the mesh (a v3 frame is one byte of version away from a v2 one); the unsigned tick
gives up Ed25519 non-repudiation for a frame that only ever says "delivered".

**Precedent: the `0x05` transcoded framing (ADR 060, 2026-08-29).** A transport-local re-encoding of the
*signed* bytes — integer labels, raw ids, the payload inlined — that is not a wire change *because the
receiver rebuilds the canonical CBOR byte-exact and verifies the original signature over it*: what is
signed, stored and relayed never changes, only what travels on two size-capped planes. Three rules made it
additive. (1) The rewriter is generic and path-scoped, so a key it does not know rides as text plus its raw
value — an older or newer build's additive field is passed through, never dropped (a typed mirror would have
made every new wire field a new capability bit). (2) Every transform is self-describing by CBOR major type
with a passthrough fallback, and the sender proves each frame reproduces before sending it, so a string the
schema cannot represent stays the string the signer signed. (3) Emission is capability-gated on an
advert-visible bit (`0x80`, the last one a BLE advert carries) while every receiver accepts every tag, the
`CAP_FAST_COMPACT` posture. Frozen with it: the schema-1 label table (six golden transcoded vectors +
the literal map in `FrameTranscoderTest`); the layout is versioned by *tag*, never edited. What it could not
reach — and the next break can (`docs/NEXT_WIRE_BREAK.md` item 8) — is the stored form: the 7-B empty nonce
in custody, the text ids inside sealed payloads, the millisecond clock. `GoldenVectorTest` moved nothing.

**Precedent: the DB v19 field reuse a sixth time — any emoji in a reaction (2026-09-01).** `ReactionContent.emoji`,
`ReactionPayload.emoji` and `ReactionV2.emoji` were always free-form text; populating them with a skin-tone,
flag or ZWJ sequence instead of one of six fixed glyphs changes nothing on the wire, and the `0x05`
transcoder passes the string verbatim (`pass("emoji", 2)`). The one new receiver rule is the rule-5 shape
applied to a *size*: an emoji over `TextLimits.REACTION` (32 UTF-16 units, ~2× the longest RGI sequence) or
blank applies nothing locally and counts `REACTION_REFUSED`, while custody, relay, fan-out and the ratchet
chain advance are untouched — `canCarry` never reads it, so the custody rule stays identical on every build
(ADR 006). Length-only on purpose: an emoji-class test would make an old build drop every emoji Unicode adds
after it shipped. No field, no `type`, no ctl, no capability bit, no DB change (`emoji TEXT` since v1);
`GoldenVectorTest` moved nothing (its 👍 fixtures are unchanged). *Metadata cost:* a room reaction's emoji
was already cleartext; a sealed reaction's size now varies by up to 61 B with the emoji (229 B → 261 B for
the longest sequence, two LoRa packets instead of one), a weak hint that a reaction is a sequence.

**Counter-precedent — the first feature since ADR 035 that *does* spend a field, and why (arbitrary files,
ADR 2026-09.qq2r).** Two entries above say the expected wire change turned out to be unnecessary. This one
says when it isn't, so the rule reads as a rule rather than as "never add a field". A file attachment is an
ordinary attachment with an arbitrary MIME — that half is free, exactly as ADR 034's audio MIME was — but it
also needs a **name**, and a name is not a pure function of bytes both ends hold. `%PDF-1.7…` does not say
`quarterly-report.pdf`. Derive-don't-carry has nothing to derive from, so `MessageContent.attachmentName` and
`attachmentSize` were spent, at compact labels **14** and **15** (12/13 stay reserved for `pad`/`gk`).

Both are nullable and omitted while null, so every prior `GoldenVectorTest` fixture is byte-identical — an
image or a voice note frame did not move, which is the executable half of "additive". `ScopeVectorTest` and
`SpoolRecordsTest` are untouched: the spool plane addresses attachments by hash and has never carried a type
at all.

Three constraints came with them, all worth keeping:

- **Sealed only.** `ChatContent` is unchanged. A filename is a *louder* signal to a blind carrier than the
  MIME ADR 035 deliberately removed — it is frequently the subject of the message — so it never rides
  cleartext. This is also what keeps `docs/NEXT_WIRE_BREAK.md`'s first parked item true: files are not
  offered in the Nearby room, so "a room attachment is an image" still holds.
- **Normalized at the decode boundary, not downstream.** `attachmentName` is open sender-supplied text that
  is drawn, notified and used as a default filename, so both decoders run `AttachmentName.sanitize` — path
  separators and every control *and Unicode-format* character out (the format category is where the bidi
  overrides live), capped, truncated through the stem so the extension survives.
- **`attachmentSize` is a label, never a bound.** It is what the bubble shows before the bytes land; the
  stored blob's own length supersedes it the moment there is one, and nothing allocates on it
  (`docs/SPOOL_PROTOCOL.md` C-4.5-8).

The send is gated on a new capability bit, `CAP_FILES` — a send-time input like `CAP_RATCHET`, because a
build without it drops both fields as unknown keys and renders an attachment it can neither name nor save.
Gate the *send*, not the affordance: hiding the composer's file item on the same bit made the feature
invisible for the whole rollout window, since the bit only arrives with the peer's next profile frame.
Capability-gating is legitimate *here* and forbidden for the `FileHeaderWire.mime` privacy fix for opposite
reasons: a peer that lies about `CAP_FILES` only breaks its own rendering, while a carrier that lies about a
privacy bit turns off someone else's protection.

**Precedent — the seventh additive `ProfileContent` change, and the first field that must ride *both* profile
paths (the "open to chat" flag, 2026-09-03).** `ProfileContent.openToChat: Boolean = false`,
`ProfilePayload.openToChat: Boolean = false`, and `ProfileV2` label **5**. Rule 1 by the *defaulted* route rather
than the nullable one, on purpose: `encodeDefaults = false` elides the key while false, so a profile that never
set it is byte-identical to one before the field existed (every existing golden vector stayed put; three new
ones — `profileContentOpenToChat`, `profilePayloadOpenToChat`, `messageContentV2ProfileOpenToChat` — were
added), an older peer's profile reads false, and a flip back to off propagates by *omission* — the same shape as
a newer profile carrying no `prekey` clearing the pin. A nullable tri-state would have bought nothing the
receiver does not coerce to false anyway, and a sender emitting an explicit `false` would have cost 12 B on every
profile from every new build forever. Not derivable (the ADR 066 rule inverted — nothing on either end can
compute someone's willingness), so it is carried.

What this precedent adds to the rules: **a presentation field goes on `ProfileContent`, `ProfilePayload` and
`ProfileV2` together.** The sealed `CTL_PROFILE` path (ADR 020) copies the whole presentation set under a newer
version, so a field carried by the cleartext frame alone would be silently reverted by the next sealed update;
and a v3 peer decodes the compact mirror, so a label left out of `ProfileV2` reads false there. The transcoder
(ADR 060) needed nothing: its schema-1 label map is frozen, and the flag rides as its text key plus `f5` (12 B,
only while true — `FrameTranscoderTest.anOpenToChatProfileRidesAsTextKeyPlusBooleanAndRebuildsExact`), which
`CoordinationPlaneSizeBudgetTest` now measures on its max-size profile and intro fixtures. No capability bit, no
ctl value, no `EncEnvelope.v` / `MessageContent.v` bump, no discovery marker. The DB bumps (v8 → v9, one
`peers.openToChat` column) — local only, with a tested `KnitMigrations` entry. *Metadata cost:* the cleartext
profile frame discloses the flag to a carrier exactly as it already discloses the status text beside it; the
sealed path hides an update from carriers for ratchet-capable peers, as for every other presentation field.

**Precedent — the first new `type` string since the v1 baseline, and the first entry on `isCustodial`
(the Meshtastic LongFast bridge, ADR 2026-09.cf7a).** Every precedent above deliberately avoided minting a
`type`, and three of them say why in so many words: `isCustodial` is a fixed list on deployed builds, so a new
type is not custodial to an older peer. `meshpost` — a post overheard on a foreign mesh's public channel and
re-published by the phone whose board heard it — is the case where that argument came out the other way, and
the reasoning is worth having in full because the next reader will meet the same fork.

**Why a `chat` with an extra field does not work here**, which is the shape every precedent above chose. Two
things an *older* build does with such a frame. It attributes the text to the gateway's own user — the frame is
signed by the phone that overheard the post, not by whoever said it, and that is unavoidable: a Meshtastic
speaker has no Knit identity to sign with. And it **renders** it, which earns a sealed `CTL_RECEIPT` from every
recipient — ticks that then ride the LoRa plane home from far pockets for a message nobody is waiting on. A new
type is invisible to both: `dispatchByType` ends `else -> Unit`, so an old build relays it verbatim, renders
nothing, and ticks nothing, and `AckSync` simply never learns the type exists.

**What it spends, and what it does not.** One `type` string (`FrameType.MESH_POST`), one content class
(`MeshPostContent` — three required fields, five nullable/defaulted ones, so a name-less post is `a3` on the
wire; two golden vectors added, none moved), one entry on `isReplayable` (and so on `isCustodial`), one
conversation id, one own custody bucket + TTL, and a local DB bump (v9 → v10, six nullable `messages` columns,
one tested `KnitMigrations` entry). **No** capability bit, no `EncEnvelope.v`, no `MessageContent.v`, no ctl
value, no discovery marker. The `0x05` transcoder needed nothing: its rewriter is generic, so an unknown type
rides as its text name with an opaque payload (`FrameTranscoderTest`), and `FastFrameCodec`'s frozen `DICT_V1`
simply does not contain the new string, which costs a little compression and nothing else.

**The cost, stated plainly, because it is the reason this had never been done.** `FrameType.isCustodial` is
fixed on every build in the field. A build without this change holds none of these rows while we hold them all
— two nodes with continuously different live sets for the whole TTL, which is exactly the digest divergence
ADR 006 exists to prevent and which churns the NDP cue plane between them. It is accepted **only** because the
whole LoRa plane is `BuildConfig.LORA_PLANE`-gated (debug on, release/staging off), so no shipped build ever
mints one; it rides the same release gate the `0x05` flag-day already owes (`roadmap.md`). The rule this
precedent adds: **a new custodial type is a flag-day, not an additive change** — it is additive on the
*decoder* and divergent on the *digest*, and only a gate that keeps it off shipped builds makes the second half
tolerable. `FrameType.isCustodial`'s kdoc and `FrameTypeTest` both say so at the line where someone would
widen it again.

Two smaller rules the same change settles. **A derived frame id is not a wire concern** — `FrameId.forMeshPost`
hashes `(node, packetId)` so every gateway that heard one packet mints one id, which is what makes the
duplicate copies collapse on the `SeenSet`, `insertIfAbsent` and `StoreDigest` machinery instead of multiplying
through it; `FrameId`'s kdoc and `NEXT_WIRE_BREAK.md` both already said the format is free, and this is the
first change to spend that freedom. And **a per-type clause in a convergence-critical quota is convergent when
the type is new**: `ForwardDao.countBySender` now excludes `type = 'meshpost'`, which is a no-op on any build
that stores none, so every node still counts the bucket identically. *Metadata cost:* the frame is cleartext by
construction — it re-publishes something that was already broadcast unencrypted on a public band — so it
discloses to a carrier only what a radio in that neighbourhood could already hear, plus which Knit node was
listening.

**Precedent — relaxing a required field to optional is a flag day on the *decoder*, and that is not the same
thing as a divergence (the LongFast bridge's outbound half, ADR 2026-09.7r4d).** Rule 1 above says every field
added after v1 must be nullable or defaulted, and every precedent here has obeyed it in the direction of
*adding*. This is the first change to go the other way: `MeshPostContent.node` and `packetId` were required
and are now optional, because a post typed **in** the bridged room has neither — its author's phone may hold
no radio at all, and which gateway transmits it, under which node number and with which packet id, is decided
later and elsewhere. `node == null` is then the discriminator between the two shapes the one type carries.

A build that predates the change fails `decodePayload` on the new shape and renders nothing. What matters is
what that does **not** touch: custody and relay run outside `dispatchByType`, so the frame is still stored,
still folded into the content digest and still forwarded. So the two failure modes have to be named
separately — a **decoder** flag day, where an old build silently shows less, and a **digest** divergence,
where two builds hold different live sets and churn the cue plane against each other (the `meshpost` entry
above, ADR 006). This is the first and not the second, and the mitigation is different in kind: a divergence
needs a gate, while this needs only that the fleet reflash together. Both are still flag days, and both are
tolerable here only because `BuildConfig.LORA_PLANE` keeps the whole plane out of shipped builds.

What it spends beyond that: **nothing**. No new `type`, no new `isCustodial` entry, no capability bit, no ctl
value, no conversation id, no DB column, no custody bucket — the outbound post is the same `meshpost`, in the
same room, under the same 6 h TTL and quota, with the same no-receipt rule and the same "never back on the
LoRa band" exclusions. One new golden vector (`meshPostContentAuthored`, `a2` on the wire — body and name),
and the two existing ones are unmoved because both still set all three fields. That economy is the argument
for the whole shape: a `chat` frame carrying a room discriminator would have needed a decision about every
one of those, and got them all wrong on an old build.

**Precedent — a new attachment *kind* that spends no field (link-preview cards, ADR 2026-09.n752).** A
card the sender fetched rides as a content-addressed blob under one new MIME value,
`application/vnd.knit.link-preview`, inside the existing `attachmentMime` — sealed in `MessageContent`
(compact label 4, a plain string either way) for a DM or group, in the clear on `ChatContent` in the Nearby
room — plus one capability bit, `CAP_LINK_PREVIEW = 0x400`, gating whether a DM/group send attaches one.
The container (`mesh/protocol/LinkPreviewBlob`) has its own golden vectors (`linkPreviewBlob`,
`linkPreviewBlobTextOnly`) and follows rule 1 itself — every field after `url` nullable or defaulted, the
one `@ByteString` nullable — with `v` **required and always emitted**, because an elided version cannot
gate and a decoder must refuse a layout it does not know rather than mis-read it (a new form mints a new
MIME). No field, no `type`, no ctl, no version bump on any layer, no DB change; every prior golden vector
unmoved. One assumption is withdrawn rather than a rule added: the Nearby room now originates a second
attachment kind, so "a room attachment is an image" no longer holds (`docs/NEXT_WIRE_BREAK.md` item 1 keeps
the field it wanted to drop load-bearing) and `MeshBlobStore.saveIncoming` opens a card instead of skipping
it. *Metadata cost:* a room card is cleartext to every carrier — title, description and picture — exactly as
the room body beside it already is; a DM card is "~N opaque bytes" like a photo, its type sealed; and the
sender's own IP reaches the linked site before the send, which is the setting's disclosure and the reason it
defaults off.

**When you bump a version layer:** add a round-trip test plus an "unknown higher version drops locally
but is counted" test. New crypto scheme ⇒ bump `EncEnvelope.MAX_SUPPORTED_VERSION` + every branch that
tests the version (`InboundPipeline.decryptAndDeliver`, `MeshManager`'s inline-ack give-back,
`ScopeFrames`) **together** — bumping MAX without the branches converts the clean unknown-version drop
into `DECRYPT_FAILED` noise, and a missed equality branch silently routes the new version to an older
path. New content schema ⇒ bump `MessageContent.MAX_SUPPORTED` (or, for the compact schema,
`MessageContentV2.MAX_SUPPORTED`).
