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

- **Endpoint-info `protoVersion` + `capabilities`** (`Protocol.VERSION` / `Protocol.CAP_*`): the Nearby
  handshake hint, known at connection time, **unauthenticated** — a routing/degradation hint only, never
  a trust input.
- **`RelayEnvelope.type` registry**: `chat`, `groupupdate`, `groupleave`, `profile`, `receipt`,
  `reaction`, `blobreq`, `keyreq`.
- **`EncEnvelope.v`**: the E2E crypto scheme (AES-GCM + HPKE wrap).
- **`MessageContent.v`**: the decrypted plaintext schema.

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

## Is this change wire-breaking?

**Breaking** (needs a coordinated one-time bump of `SERVICE_NAME` + the DB version) if it: removes,
renames, re-types, or repurposes a field or a `type`; changes `WireCodec`'s config or the `@ByteString`
opacity of `signed`/`sig`/`payload`; changes what `signed` is signed over, the AEAD `header`, the
`NodeId` derivation, or `SERVICE_NAME`; or makes `RelayEnvelope.type` polymorphic.

**Additive** (safe) if it only adds a nullable/defaulted field to a content/envelope type, a new `type`
string with its own content class, or a new capability bit — and rule 4 holds.

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

**When you bump a version layer:** add a round-trip test plus an "unknown higher version drops locally
but is counted" test. New crypto scheme ⇒ bump `EncEnvelope.MAX_SUPPORTED_VERSION` + branch in
`MeshManager.decrypt`. New content schema ⇒ bump `MessageContent.MAX_SUPPORTED`.
