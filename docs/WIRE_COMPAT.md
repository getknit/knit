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
  `reaction`, `blobreq`, `keyreq`, `typing`, `commons` (non-custodial; the spool commons' post, spec §7.4),
  `blobhave` and `blobhas` (the blob holder query and its answer: unsigned, point-to-point, ADR 2026-10.6e5t),
  `blobprog` (a file receiver's progress report to its sender: unsigned, point-to-point, ADR 2026-10.nsr7),
  `blobcancel` (an asker withdraws its ask: unsigned, point-to-point, ADR 2026-10.nsr7).
  (`meshpost` is **burned**: minted on an unpushed branch in September 2026 and withdrawn before it
  shipped — never reuse the string, since a lab build may still hold a frame of it.)
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
6. **Every nullable nested `@Serializable` type carries at least one required field.** kotlinx-cbor reads
   an empty map as `null` for a nullable nested field, while iOS reads it as an object with its defaults,
   or fails the whole payload. A nested type whose every field is defaulted can encode as `{}` and then
   means two things. A required field keeps the map non-empty. `IosPayloadShapesTest` pins the quirk.
7. **An envelope `id` has the shape its type is minted with** (`FrameId.admits`, ADR 2026-10.9qr4): a `profile`
   is `profile-<senderId>-<sentAt>`, a `talk` is 11 base64url characters, and every other type, an unknown one
   included, is a `FrameId.new` id (22 base64url characters that round-trip through 16 bytes). Every inbound door
   refuses any other id before dedup, custody or relay, because Android's database drops a leading U+FEFF from
   every id it binds and would file the frame under another's row. A new frame type mints a `FrameId`; a new
   id shape is a break.

## Vectors other ports test against

`vectors/` holds the byte-exact fixtures every Knit port is tested against (`vectors/README.md`):
`wire-v1.json` (the codec fixtures `GoldenVectorTest` builds), `keyed-v1.json` (fixed identities, signed
frames, a sealed v1 DM, the safety number, the link records and the advert) and `ios-emitted-v1.json`
(frames the iOS port signs, which Tink must verify and kotlinx must re-encode to the same bytes). An
additive change adds vectors and moves none. A vector that moves is a break by the definition below, and
the iOS port fails the same day.

## Wire-breaking vs. additive changes

**Breaking** (needs a coordinated one-time bump of **both** discovery markers — Wi-Fi Aware
`SERVICE_NAME` *and* BLE `SERVICE_UUID` — plus the DB version) if it: removes, renames, re-types, or
repurposes a field or a `type`; changes `WireCodec`'s config or the `@ByteString` opacity of
`signed`/`sig`/`payload`; changes what `signed` is signed over, the AEAD `header`, the `NodeId`
derivation, or a discovery marker; makes `RelayEnvelope.type` polymorphic; or mints an envelope `id` of a shape
rule 7 does not name (every build before it drops the frame at the door).

**Additive** (safe) if it only adds a nullable/defaulted field to a content/envelope type, a new `type`
string with its own content class, or a new capability bit — and rule 4 holds.

**A sealed chat may name a key-less attachment** (ADR 2026-10.mptm): a room picture forwarded into a DM or group
keeps its public plaintext hash, so `MessageContent.attachmentKey` is null beside a set `attachmentHash`. No byte
moved and no field changed meaning — a null key has always meant "plaintext" to every reader on both platforms
(the room's own rows), so it needs no capability bit. A file passed along also reuses the ciphertext hash and key
it arrived under, which no receiver can tell from a fresh seal.

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
> map): a richer schema is a new tag. The **BLE side channel** (`mesh/bluetooth/BleSideChannel`, ADR
> 2026-09.sjaa) carries the same `0x03`/`0x04`/`0x05` units, one per extended-advertising page under
> `BleConstants.SIDE_SERVICE_UUID` (`0xFE38`; page UUIDs count up from there, presence UUIDs from `0xFE30`).
> Its gate is not a capability bit but the BLE-local **flags byte** the presence advert grew
> (`BleAdvertPayload` 23 → 24 B, `FLAG_SIDE_CHANNEL`): additive, because the older parser ignores trailing
> bytes and the older scan filter matches service data of any length, and an older build's legacy scan
> cannot decode an extended advert at all. **A new flags-byte bit is additive** under the same reasoning:
> every shipped parser tests only the bits it knows (`Parsed.sideChannel` reads bit 0 alone) and ignores
> the rest, so a bit a reader predates reads as nothing. Bit 1 is `FLAG_DIALS_GATT_PEERS` (companion change
> A3, ADR 2026-09.kwq2; knit-ios ADR 2026-09.xzpt): "I read a UUID-only peer's GATT payload, so I dial it".
> Bit 2 is `FLAG_AWARE` (GitHub #44, ADR 2026-10.2t49's 2026-10-27 amendment): "count on my Wi-Fi Aware now",
> positive, so an advert from a build that predates it never makes a peer an Aware candidate. Additive like the
> others; main builds from 2026-10-24 to 2026-10-27 wrote it with the opposite meaning, which never shipped.
> The value that payload characteristic serves (`DoorbellPolicy.PAYLOAD_UUID`) is the same 24-byte advert
> payload with the digest cue zeroed, so it grows under the same rules as the advert.

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

**Precedent — the eighth additive `ProfileContent` change, and the first by the *nullable* route (the bound
LoRa board, ADR 2026-09.26q3, 2026-09-05).** `ProfileContent.loraNode: Long? = null`, `ProfilePayload.loraNode:
Long? = null`, and `ProfileV2` label **6**: the Meshtastic node number of the board the phone currently holds,
so a contact's phone can line a post its own board heard on the radio channel up with this one. Nullable rather
than defaulted because 0 is not a node number and there is no value that means "none"; elided while unbound,
so every existing golden vector stayed put (three new ones — `profileContentLoraNode`, `profilePayloadLoraNode`,
`messageContentV2ProfileLoraNode`), an older peer's profile reads null, and a board handed on or unpaired is
unsaid by **omission** on the next profile — the prekey's shape. A presentation field, so it rides all three
layouts under the same LWW watermark (the open-to-chat rule above); the transcoder needed nothing (text key
plus a uint, 14 B at most, only while bound —
`FrameTranscoderTest.aBoundBoardProfileRidesAsTextKeyPlusUintAndRebuildsExact`), and
`CoordinationPlaneSizeBudgetTest` measures it on its max-size fixtures. No capability bit, no ctl value, no
version bump, no discovery marker. The DB bump (v9 → v10, which carries the whole bridge): `peers.loraNode` for the claim and
`messages.originPeerId` for the match a heard post made at ingest — local only, tested. *Metadata cost:* the
cleartext profile discloses the board's node number to a carrier; the board already broadcasts that number in
every `NODEINFO` on the public band, so a radio learns nothing new and a spool learns which board a Knit node
holds. The number is self-asserted and unsigned by the board, so a receiver treats a match as an attribution
and keeps the unverified styling — unless the next field lets it check one.

**Precedent — the ninth additive `ProfileContent` change (the bound board's signing key, ADR 2026-09.ggq4,
2026-09-05).** `ProfileContent.loraKey: String? = null`, `ProfilePayload.loraKey: String? = null` and
`ProfileV2` label **7** (`ByteArray?`): the Curve25519 public key the board signs under, base64 of the 32 raw
bytes on the two public layouts (the `pubKey`/`avatarHash` string convention, equality-safe on a data class)
and the raw bytes on the compact sealed one (its `avatarHash` idiom). Nullable, by the `loraNode` route;
elided while unbound *and* while the board's firmware does not sign, so a pre-2.8 user's profile is
byte-identical to before and every existing golden vector stayed put (three new ones —
`profileContentLoraKey`, `profilePayloadLoraKey`, `messageContentV2ProfileLoraKey`). A presentation field on
all three layouts under the same LWW watermark. The transcoder needed nothing (text key plus a 44-character
text string, `FrameTranscoderTest.aBoardKeyProfileRidesAsTextKeyPlusValueAndRebuildsExact`); the cost is
**54 B** on the cleartext profile while a signing board is bound, and `CoordinationPlaneSizeBudgetTest`
carries it on both max-size fixtures: the max-size profile transcodes to **432 B, still two LoRa packets at the
ESP32 cap** (16 B of headroom under the 448-B ceiling), and the max-size intro to 600 B, still three. The
intro's legacy `0x03` form is now 684 B, three bytes past the three-packet ceiling — a form the LoRa plane has
not sent since the ADR 060 flag-day (it transcodes unconditionally), so `anIntroFitsTheLoraHop` now measures
the form the plane sends. No capability bit, no ctl value, no version bump, no discovery marker.
The DB bump (the same v9 → v10): `peers.loraKey` for the claim and `messages.originSigned` for what a heard post's
signature proved at ingest, frozen beside `originPeerId` — local only, tested. *Metadata cost:* none new —
the key already rides every `NODEINFO` the board broadcasts on the public band, and on 2.8 the node number
the profile already carried *is* the CRC32 of it. What a receiver does with it: verifies a heard post's
XEdDSA signature (`mesh/crypto/XeddsaVerify`, over the firmware's own `from ‖ id ‖ portnum ‖ payload`) and
shows the post as that contact's, with a shield. What it proves is bounded: the *radio* named in the profile
transmitted the words — the frame's Ed25519 signature is what binds that claim to the identity — never that
the person typed them.

**Precedent — the first sealed-only presentation field, and the one exception to "all three layouts" (the supporter
badge, ADR 2026-10.r99s, 2026-10-14; spec `docs/SUPPORTER_BADGE.md`).** `ProfilePayload.badges: List<BadgeEntry>? =
null` and `ProfileV2` label **8**, each entry `{w, r}` (`@ByteString` on the named layout; `{1, 2}` raw on the compact
one): the issuer-signed credential's wrapper verbatim and the 16 random bytes its `sub` commits to. Rule 1 by the
nullable route — elided while the holder shows none, so every existing golden vector stayed put (two new ones,
`profilePayloadBadges` and `messageContentV2ProfileBadges`), and an old build skips the key. Rule 6 holds: both entry
fields are required, so an entry never encodes as `{}`. **Not on `ProfileContent`, on purpose**: the cleartext bootstrap
is 432 B of the 448-B two-packet LoRa ceiling (`CoordinationPlaneSizeBudgetTest` is unchanged), and every carrier keeps
the cleartext frame (ADR 2026-09.g64k), relays included. The rule above — a presentation field rides all three layouts
or the next sealed update reverts it — is about a field the cleartext frame sets and the sealed path would overwrite;
the inverse trap is the cleartext frame overwriting a sealed-only field. So a receiver keeps badges in a table of
their own (`peer_badges`, DB v22), replaced whole only by a sealed profile that passes the version gate (absent
clears) and **never named by the cleartext profile writer**, whatever its version. Don't move them onto `peers`, and
don't add a badge field to `ProfileContent` "for symmetry". No capability bit, no ctl value, no version bump, no
discovery marker; the transcoder never sees the sealed plaintext. *Metadata cost:* the field is inside the seal **and a badge change never moves the cleartext profile's
version**, but the sealed profile is not padded, so a carrier that can tell a sealed profile from other sealed DMs and
predicts its size from the cleartext profile it holds can see it is about 185 B larger while a badge is shown (padding
it to one size is parked as a follow-up). The version rule: it is published under a
sealed-only version past it (`badge_sealed_version`), since a cleartext version that moved with no visible field
changed would tell every carrier the holder shows a badge. Every peer with a DM session sees the holder's badge while
the holder shows it, and the holder chose that (opt-in, off by default). *Size:* one badge adds 185 B to a max-size compact sealed profile
(`SupporterBadgeSizeTest`).

**Withdrawn precedent — the one `type` string minted since the v1 baseline (`meshpost`, ADR 2026-09.cf7a and
2026-09.7r4d, September 2026), taken back before it shipped (ADR 2026-09.26q3).** It was the first entry on
`isCustodial` since v1, and it is gone because of exactly the cost every earlier precedent had avoided:
`FrameType.isCustodial` is fixed on every build in the field, so a build without the type held none of those
rows while a build with it held them all — two nodes with continuously different live sets for the whole TTL,
the digest divergence ADR 006 exists to prevent. It was tolerable only behind the `BuildConfig.LORA_PLANE`
gate, and the redesign made it unnecessary: a post heard on the paired board's channel is now a local row, never
a frame, so nothing about it is custodied, fanned out or relayed. Three rules survive the withdrawal, and are
kept here because the next reader will meet the same fork. **A new custodial type is a flag-day, not an
additive change** — additive on the decoder, divergent on the digest, and only a gate that keeps it off shipped
builds makes the second half tolerable (`FrameType.isCustodial`'s kdoc says so at the line where someone would
widen it). `BuildConfig.LORA_PLANE` is no longer such a gate: the plane ships lit from 2.5.0 (ADR
2026-09.6gtm), so a future custodial type needs a gate of its own.
**A decoder flag day and a digest divergence are different things**: relaxing a required field to
optional makes an older build render less while still storing, digesting and relaying normally, and needs only
that a fleet reflash together, where a divergence needs a gate. And **a derived id is not a wire concern**:
`FrameId.forMeshPost` still hashes `(node, packetId)` so one packet is one row however often the board replays
it, and lives only in the local `messages` table now. The string itself is burned in the registry above.

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

**Precedent — the first `type` string added since the v1 baseline that shipped (`commons`, the spool commons'
post, spec §7.4, September 2026), and why it is not the `meshpost` mistake.** It is added as
**non-custodial**: `FrameType.isCustodial` is untouched, so no build in the field holds a row an older build
would not, and the digest rule ADR 006 protects is untouched with it. What makes that honest is where the
frame lives — on the spool that runs the room, sealed under the room's key, entering the app through the
Internet plane's own door and never `originate`d, custodied or relayed. A stray copy on a radio (a member
re-flooding by hand) is an unknown type to an older build and an ignored one to a newer: both decode it,
both relay it (rule 5 — a version or type gate is a delivery gate, never a relay gate), neither delivers it,
and `dispatchByType` names it explicitly so the `else` stays for types nobody has minted yet. The payload
(`CommonsPost`, golden vectors `commonsPost`/`commonsPostFull`) wraps an ordinary `ChatContent` behind the
room's 32-byte scope id: the seal's aad already binds the *blob* to its scope, but the signed bytes are what
a member of two rooms could re-seal from one into the other, so the room is named inside the signature too
and refused on open when it does not match. Authentication is the frame signature against the **pinned**
key like every other frame (members' `profile` frames ride the same scope and pin them), and `enc` stays
null — every member shares the room key, so there is nobody to seal for, exactly the broadcast room's shape.
*Metadata cost:* none on the radios, which never see one; to the spool, one more opaque blob in a scope it
already cannot read.

**Precedent — the commons' follow-ons (`CommonsPost.reaction`, `ChatContent.attachmentKey` and
`attachmentName`, ADR 2026-10.xhfr, October 2026).** Three nullable fields, each elided when absent, so
`commonsPost`/`commonsPostFull` and every `ChatContent` fixture stayed put and `vectors/wire-v1.json` only gained
`commonsPostAttachment` and `commonsReaction`. `CommonsPost.chat` became nullable to carry exactly one of a chat and a
reaction: the decoder that predates it fails on a post with no `chat` and the member quarantines that blob, which is
rule 5's outcome (delivered by nobody, an older room's fold left to its own accounting) and the reason no reaction can
render as an empty bubble on an old build. The two `ChatContent` fields are cleartext only inside a commons' seal and
read only there: a Nearby room post carrying either has the field dropped on delivery, so the room stays key-less and
nameless by design. *Metadata cost:* none on the radios; to the spool, nothing — the post is sealed under the room's key,
and a keyed attachment's address is its ciphertext's, which correlates with nothing outside the room.

**Precedent — the spool plane's second mesh-wire field (`GroupKeyPayload.group`, the founding roster on the
seed, ADR 2026-09.mjaj, 2026-09-18).** The `gr` precedent above, applied once more to the same payload:
`GroupKeyPayload` gained the nullable `group: GroupInfo?` — the roster half of the row (`toFoundingInfo`: id,
members, departed, creator, name; never the photo) — on every `CTL_GROUP_KEY` the one builder emits. It exists
because a member reachable only over a relay has a DM scope that carries the seed and a group scope that
derives from the root inside the seed, and the roster rode only the group scope: the seed parked for want of
the row, the root was refused for want of the roster, and the roster could not be pulled for want of the root
(work item #47). Now a member with no row pins the group from the seed through `reconcileGroup`'s one door,
with `vetRoster`'s derivation check unchanged. Rule 1: `GroupInfo` is a plain data class with no `@ByteString`,
so it nests as a nullable field; elided when absent, so every existing fixture stayed put and `GoldenVectorTest`
gained `groupKeyPayloadRoster` (seeds, root and roster together); an older receiver ignores it and parks the
seed as before (`WireSerializationTest.aPreRosterShapedDecoderIgnoresTheSeedsFoundingRoster`), and an older
sender's seed decodes here with `group` null. No discovery marker, no `EncEnvelope.v`, no `MessageContent.v`,
no ctl value, no capability bit, no DB change — the compact v3 layout never carries `gk`, so it needed nothing.
*Metadata cost:* none — the roster rides inside the DM ratchet ciphertext, so a carrier or spool sees roughly
330 more bytes of ciphertext at the roster cap, and the one reader is the member the seed is addressed to, who
is in the roster it names.

**Precedent — the first capability bit Android never claims (`CAP_DOORBELL = 0x1000`, companion change A4 for the
iOS port, ADR 2026-09.dqvb).** A node that serves a GATT doorbell (a writable characteristic,
`f34c056b-5830-4243-a888-01f92f49e446`, in a primary `0xFE30` service) sets the bit in its HELLO, and the
Bluetooth transport writes that characteristic after the frames it sends on the link. iOS resumes a suspended app
for that write and not for L2CAP data. Every other bit is a feature a build implements, so every local build
claims it. This one describes the platform, and `LOCAL_CAPABILITIES` must never carry it (`ProtocolTest` pins
both). It is read from the HELLO's copy only, by the transport only. It sits above the advert's eight bits, so no
advert shows it. The port sets it in the HELLO alone, from the radio that serves the doorbell, never in its
signed profile, and nothing here reads it from one. An
older Android build ignores the bit, and the iPhone then simply sleeps through its frames. No field, no `type`, no
ctl, no version bump, no discovery marker, no DB change, and every golden vector is unmoved. The two GATT UUIDs are
pinned in `DoorbellPolicyTest` beside it: the characteristic is law from the first build that rings it.

**Precedent — the first field added to a link record (`FileHeaderWire.size`, the stream's length, ADR
2026-10.y9qh, #115).** The `FILE_HEADER` record carries JSON, not CBOR, under its own config
(`ignoreUnknownKeys = true`, `encodeDefaults = true`, `LinkFraming.kt`), so rule 1 holds only by its second half:
a null is not omitted here, it goes out as `"size":null`. The field is additive because every reader skips a key
it does not know — every Android build since before v2.0.0, and the iOS port's `Decodable` header (its
`LinkTests` pin an unknown key; it discards file records until it takes attachments) — and because the sender
always fills it. A `size` that will not decode costs the label, never the file: a failed header decode aborts the
transfer behind it, so `decodeFileHeader` falls back to the three routed fields. No capability bit (the size gates
nothing, trusts nothing and hides nothing — a label, never a bound, like `attachmentSize`), no version bump, no
discovery marker, no DB change. `vectors/keyed-v1.json` gained a `fileHeader` record and moved nothing.
*Metadata cost:* none new — the chunk lengths on the link already sum to the size, and a carrier that pulls the
blob holds every byte of it.

**Precedent — a resume offset on both ends of a blob transfer (`BlobReqContent.offset`, `FileHeaderWire.offset`,
ADR 2026-10.wtyc, #116).** The ask is CBOR, so rule 1 holds as written: the null is omitted, a plain ask is the
bytes it always was (`blobReqContent` did not move), and every reader — older Android builds, the iOS port's
`CBORFields` — skips the key and serves the whole file. The header is the link JSON with `encodeDefaults = true`,
where `size`'s route ("the sender always fills it") does not fit a field that is usually absent; the field carries
`@EncodeDefault(EncodeDefault.Mode.NEVER)` instead, which overrides the config, so a whole-file header is
byte-identical to every earlier build's (`fileHeader` did not move) — the first link field that is omitted rather
than written as null. Rule 2 fixed the arithmetic: `size` had shipped as "the bytes that follow", so it stays the
tail's and the whole is `offset + size`; redefining it as the blob's length would have been a repurpose. Where the
two header fields part ways is what a bad value costs: `size` is a label and fails alone (#115), the offset says
where the bytes go, so one that will not decode refuses the file (`FileHeaderCore` carries it). No capability bit:
only a build that reads the header's offset asks with one, and an older holder answers with a whole file the asker
takes as before. No version bump, no discovery marker, no DB change; `vectors/wire-v1.json` gained
`blobReqContentResumed` and `vectors/keyed-v1.json` `fileHeaderResumed`, and nothing moved. *Metadata cost:* the
offset tells each asked neighbour how much of the blob the asker holds — progress, never content.

**Precedent — a holder query and its answer (`blobhave` / `blobhas`, `CAP_BLOB_HAVE` 0x2000, ADR 2026-10.6e5t).**
Two new `type` strings with content classes of their own, so rule 3 holds and nothing was repurposed. Both are
unsigned and `relay = false`, the third and fourth unsigned shapes after `blobreq` and the v3 tick, and neither is
custodial, replayable or flooded (`isCustodial` stays the fixed list; `FrameTypeTest`). An older build refuses either
as an unsigned frame, and would spend a key request on one from an unpinned sender, so the asker queries **only** a
neighbour whose link (HELLO or Wi-Fi Aware SSI) carries the new capability bit: the `CAP_FILES` argument, a receiver
that consumes a frame silently strands the sender's wait. The bit sits above the BLE advert's 8 and is read only from
the link's copy. Every build sets it and answers, and only the asking is gated (`BuildConfig.BLOB_QUERY`), so a mixed
fleet answers whoever asks; the asking ships on in release since 2026-10-08. `BlobHasItem.state` is an always-encoded int. A
state a reader does not know is never taken for HAVE, which makes the reserved `PARTIAL` (4, with `held`) additive
later. Its serve still needs a request-side gate (`BlobReqContent.end`), never the bit alone. The fast codecs were
not touched: `FastFrameCodec`'s dictionary and `FrameTranscoder`'s type codes are frozen, and these types ride
links only. No version bump, no discovery marker, no DB change. `vectors/wire-v1.json` gained `blobHaveContent`,
`blobHasContent` and `blobHasContentReserved`, and nothing moved. *Metadata cost:* an answer tells a linked
neighbour which of the asked blobs this phone holds, exactly what serving its `blobreq` already told it. The `load`
says how many files it is feeding, never to whom.

**Precedent — a receiver's progress report (`blobprog`, under `CAP_BLOB_HAVE`, ADR 2026-10.nsr7).** A third type under
the holder query's bit, with no bit of its own: none of 6e5t or this had been pushed when it was added. It is unsigned and
`relay = false` like the query, and goes only to a neighbour whose link carries the bit. A build without the type
refuses it as an unsigned frame and loses nothing: its sender judges the feed by pace and write staleness, as before.
It is a frame, not a link record: `LinkFraming.read` throws on an unknown record type, which would drop a peer's
link. A field was added to `blobhas` with it: `BlobHasItem.eta`, defaulted and ignored by an older reader. Nothing in
the fast codecs. `vectors/wire-v1.json` gained `blobProgressContent` and `blobHasContentSendingEta`, and nothing moved.
*Metadata cost:* the sender learns how fast its own file arrives at the neighbour it is feeding.

**Precedent — an asker withdraws its ask (`blobcancel`, under `CAP_BLOB_HAVE`, ADR 2026-10.nsr7).** A fourth type
under the same unshipped bit. The holder drops a parked ask, and takes a copy queued to the asker off the link before it
starts; nothing on the link changes, since a queued file is the holder's own business. A copy already streaming goes on:
cutting one short would need a link record, which tears down an older peer's link. A build without the type refuses it
as an unsigned frame, and the copy comes as before. `vectors/wire-v1.json` gained `blobCancelContent`.
*Metadata cost:* none beyond the ask it withdraws.

**Precedent — an ask for the bytes over Wi-Fi Aware only (`BlobReqContent.fastOnly`, ADR 2026-10.js2p).** A
recipient that has not approved a download its sender declared over the user's limit asks with `fastOnly = true`, and
a holder that reads it serves over a live Wi-Fi Aware link or one it raises, else not at all — never over Bluetooth.
The ask is CBOR, so rule 1 holds as for `offset`: the null is omitted, a plain ask is the bytes it always was
(`blobReqContent` did not move), and every older reader — Android builds and the iOS port's `CBORFields` — skips the
key and serves as before. No capability bit: the flag only narrows what a holder may do, and the asker sends a
fast-only ask only to a neighbour its own Wi-Fi Aware plane links or reaches, which an iPhone never is. The gap an
older Android holder leaves is its composite's fallback: one the asker reaches over Wi-Fi Aware, that cannot raise the
link inside the grace, sends over Bluetooth as it always has. The field is the asker's alone: nothing in `FileHeaderWire`
moved, and the holder's `FileMeta.fastOnly` never leaves the phone. Nothing in the fast codecs (`FrameTranscoder`
passes the unknown key through and verifies its own rebuild). No version bump, no discovery marker, no DB change on the
wire's account; `vectors/wire-v1.json` gained `blobReqContentFastOnly`, and nothing moved. *Metadata cost:* a
neighbour asked learns that this phone holds back a large download over Bluetooth — a setting, never which message.

**Precedent — a pull-through's hop budget and deadline (`BlobReqContent.pull`, `BlobReqContent.until`, ADR
2026-10.2drc, #195).** Every `blobreq` says how many phones deep it may still pull the blob through, the receiver
counted, and the root's deadline for pulling at all. The ask is CBOR, so rule 1 holds as for `offset` and `fastOnly`:
both nulls are omitted, a plain ask is the bytes it always was (`blobReqContent` did not move), and every older reader
skips the keys and serves as before. Rule 2 shaped the default: an absent `pull` reads as 1, a fetch only. That is the
conservative reading of every ask an older build or the iOS port sends today, never a repurpose of their silence. No
capability bit: the fields only narrow what a receiver does with an ask it cannot serve, and a holder serves whatever
it is told. The fast codecs were not touched: `FrameTranscoder` passes the two unknown keys through as text and verifies
its own rebuild (`FrameTranscoderTest`), and `FastFrameCodec` carries `signed` verbatim. No version bump, no discovery
marker; the database only projects two existing columns for the deadline. `vectors/wire-v1.json` gained
`blobReqContentPull` and `blobReqContentPullSpent`, and nothing moved. The iOS companion owes the fields, the rules and
the two vectors. *Metadata cost:* the budget tells a neighbour how far the asker is from the want's root, at most
`PULL_BUDGET` (3) rings, and the deadline is a DM's `sentAt` plus a day, which every carrier of the frame already reads.

**Precedent — a short video's description and a room post's size (`VideoInfo`, `ChatContent.attachmentSize`, ADR
2026-10.hmfe).** A video's bubble needs its duration, size and a poster before the bytes arrive, and none of the three
is a function of bytes the receiver holds yet, so derive-don't-carry cannot reach them until the blob lands (when the
receiver does re-derive the duration and size). They ride as one nested type with every field required — rule 6, and
rule 1's exception for a `ByteArray` — as `MessageContent.video` (named layout, the poster a CBOR byte string) and
`MessageContentV2` label 16 (raw), sealed, and as `ChatContent.video` in a room post. The room also gained
`ChatContent.attachmentSize`, declared by `BluetoothFetchGate.declaresSize`, the second field ADR 2026-10.js2p named:
a room post had no size for "Ask before large Bluetooth downloads" to judge. Both are null and omitted by default, so
every existing frame is the bytes it was; an older reader skips the keys (a room video draws there as a broken
picture, fetched ungated), and the fast codec passes the unknown keys through. A sealed frame's cleartext shell never
supplies either: `InboundPipeline.plaintextContent` clears them. No capability bit, no version bump;
`vectors/wire-v1.json` gained `messageContentVideo`, `messageContentV2Video` and `chatContentVideo`, and nothing
moved. *Metadata cost:* in the room, the poster and length are as public as the post. Sealed, the content stays private,
but not the fact that a poster rides along: the seal does not pad, so a video DM is about 6.5 KB longer than another
attachment DM, and its length shows a carrier it is a video.

**Precedent — a body's formatting as style ranges (`ChatContent.styles` / `MessageContent.styles` under `"s"`,
`MessageContentV2` label 17, ADR 2026-10.p95v).** Bold, italic, strikethrough and monospace are ranges beside a plain
body, never markup in it, so every surface built from the body (and every older reader) sees the same text. They ride as
one CBOR byte string whose bytes are the same in all three layouts: per span, `uvarint(startDelta)
uvarint((length << 3) | code)` (`StyleSpans`), so a typical span is 2 bytes and one bold word costs a v3 message 4 and a
room post 5. The field defaults to empty and `encodeDefaults = false` leaves it out, so an unformatted message is the
bytes it was in every layout — pinned by hand-written hex in `MessageContentTest` / `MessageContentV2Test` and by no
vector moving. **The value's type is frozen as a byte string, forever:** every extension rides inside the bytes (a new
style code, the code-7 escape), never as a new type at the key. A malformed byte string never throws (a truncated or
over-long uvarint ends the read), but a value of another CBOR type fails the typed decode, as with `video`: a sealed
message is dropped `DECRYPT_FAILED` and a room post is not carried by new builds — only a buggy sender's own message
is lost. A receiver normalises the spans once against the clamped body (`InboundPipeline.deliverChat`) and never takes
them off a sealed frame's cleartext shell (`plaintextContent`). An older reader skips the key and shows the text
plainly, so there is no capability bit; the reserved spoiler code (4), whose plain fallback is not harmless, will need
one. `FrameTranscoder` passes `"s"` through as an unknown text key (schema 1 never learns it), and `FastFrameCodec`
carries `signed` verbatim. No version bump; `vectors/wire-v1.json` gained `messageContentStyles`,
`messageContentV2Styles` and `chatContentStyles`, and nothing moved. *Metadata cost:* in the room the styles are as
public as the post; sealed, the seal does not pad, so a styled message is a few bytes longer than the same words plain.

**Precedent — declared languages (`ChatContent.lang` / `reads` and `MessageContent.lang` / `reads` under `"l"` /
`"r"`, `MessageContentV2` labels 18 / 19, ADR 2026-10.t2h6's 2026-10-29 amendment).** A sender says what language a
message is in and what language they read: each a CBOR uint indexing `LanguageRegistry`, an append-only list of every
language in Mozilla's translation registry with the likeliest first, so the common ones cost one byte. Rule 1: both are
nullable and absent unless set, and the absence rules make the common case free — `"l"` absent means English or
unknown, `"r"` absent means the same as `"l"` (English when `"l"` is absent) or unknown — so a message in English by an
English reader is the bytes it was in every layout. The sender normalises (`DeclaredLangs.of`, and `sealedContent`
again off the row), and that is pinned by hand-written hex in `MessageContentTest` and by no vector moving. **The value's
type is frozen as a uint, forever, and the table is wire law:** an index never moves or changes its language (a retired
one is burned, rule 3); `vectors/wire-v1.json` carries the whole list as `languageRegistry` and `GoldenVectorTest`
fails unless it is a prefix of `LanguageRegistry.CODES`. A value of another type, or one past an `Int`, fails the typed
decode, as a wrong-typed `"s"` does: a sealed message is dropped `DECRYPT_FAILED` before anything commits (never a
`RATCHET_*` reason) and a room post is not carried by new builds — only a buggy sender's own message is lost. An index
this phone does not know, or a negative integer (an int, not a uint: a port must accept and ignore it rather than fail
the content), decodes and reads as nothing (`DeclaredLangs.fromWire`). In the room and a commons post they
ride the cleartext `ChatContent`; in a DM or group they ride sealed only, mapped by `sealedContent` and read by
`InboundPipeline.plaintextContent` off the plaintext — a sealed frame's shell never supplies them. A receiver treats
them as the sender's claim: a label for translation and the composer's hint, never a moderation, trust, routing or
crypto input. Older builds and the iOS port skip the keys and labels, so there is no capability bit, no `EncEnvelope.v`
or `MessageContent.v` bump, and no paired change. `vectors/wire-v1.json` gained `messageContentLang`,
`messageContentV2Lang`, `chatContentLang` and `languageRegistry`, and nothing moved. *Metadata cost:* 2 B for one field
under a v3 label, 3 B under a named key, 4–6 B for both (each value one byte below index 24, two from there);
`MeshManager.inlineAcksFor` counts them against the LoRa budget at the named size. In the room `reads` is as public as
the body, and it partly gives away a translated draft (an English post by a Spanish reader); sealed, only the members
see it, but the seal does not pad, so a declaring message is a few bytes longer than the same words undeclared.

**Precedent — the reserved ctl spent (`CTL_SCOPE_CONFIG = 7`, `MessageContent.sc`, docs/SPOOL_PROTOCOL.md §5,
ADR 2026-10.8kfx).** The value the `CTL_PROFILE` precedent above held back is now on the wire: `sc:
ScopeConfigPayload?` beside `ctl = 7`, naming a conversation's relays and bounds, sealed in the DM form or the group
sender-key form and applied last-writer-wins on `(version, issuer)`. Rule 1: nullable, absent from every frame that
does not set it, so no existing golden vector moved. Rule 6 shaped the payload: every field but `groupId` is required,
so a config never encodes as `{}` and no port fills in a spool list or a bound it was not sent. The compact v3 layout
has no label for it, so `MessageContentV2.hasNamedOnlyPayload` sends it in the named form, as it does `gk`, `xf` and
`cl`; without that, the compact encoder would drop `sc` and ship `ctl = 7` with nothing beside it. No `EncEnvelope.v`
bump, no `MessageContent.MAX_SUPPORTED` bump and no capability bit (C-5-6): an older build, and the iOS port, which
has no spool plane, consume the unknown ctl as the chain-advancing silent no-op. The receiver's refusals (a URL it
cannot dial, a config naming another conversation, a departed member's) are delivery gates,
never carry gates (rule 5). `vectors/wire-v1.json` gained `messageContentScopeConfigDm` and
`messageContentScopeConfigGroup`, and nothing moved. *Metadata cost:* none on the wire — a sealed ctl is
indistinguishable from any other sealed chat frame — beyond its length, and the relays it names are visible only to
the conversation's members.

**Precedent — the nested-type rule (rule 6) came from the interop audit, not a split.** The 2026-10 audit
decoded `{"inner": {}}` both ways: kotlinx-cbor gave a null `inner`, iOS an `inner` of defaults. No nested
wire type was all-defaults then, so no frame read differently on the two platforms. The rule keeps it that
way, so "absent" and "present with nothing set" never read differently across the two platforms.
`IosPayloadShapesTest.an empty nested map decodes as null for a nullable field` pins
kotlinx's half.

**When you bump a version layer:** add a round-trip test plus an "unknown higher version drops locally
but is counted" test. New crypto scheme ⇒ bump `EncEnvelope.MAX_SUPPORTED_VERSION` + every branch that
tests the version (`InboundPipeline.decryptAndDeliver`, `MeshManager`'s inline-ack give-back,
`ScopeFrames`) **together** — bumping MAX without the branches converts the clean unknown-version drop
into `DECRYPT_FAILED` noise, and a missed equality branch silently routes the new version to an older
path. New content schema ⇒ bump `MessageContent.MAX_SUPPORTED` (or, for the compact schema,
`MessageContentV2.MAX_SUPPORTED`).
