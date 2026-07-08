# Wire format

Reference for the on-radio wire format. The **rule** — additive-only evolution, read
`docs/WIRE_COMPAT.md` before changing any wire type — lives in `rules/mesh.md`. This file is the
structural detail.

The wire format (`mesh/protocol/Wire.kt`) is **layered** binary CBOR (`WireCodec`,
`encodeDefaults = false`, `ignoreUnknownKeys = true`) — not JSON, and deliberately structured so it
can evolve *additively* without another break. The three layers:

- `WireEnvelope` (frozen, the on-radio unit): mutable `ttl`/`hops`, a `relay` flag, the raw `sig`, and
  the opaque `signed` blob. It is the ONLY thing a relay re-encodes — `WireEnvelope.relayed()` rewrites
  just ttl/hops; `signed`+`sig` pass through **byte-for-byte**, so the originator's signed bytes
  survive every hop (this is what kills the old "an old relay re-encodes and breaks the signature"
  bomb). `sig`/`signed`/`payload` are `@ByteString ByteArray` (opaque), never nested objects.
- `RelayEnvelope` (what `signed` decodes to): the cleartext routing fields a relay/carrier needs —
  `type` (a plain **string** discriminator, so an unknown future type decodes instead of throwing),
  `id`, `senderId`, `sentAt`, `recipientId`, `group` — plus the opaque per-type `payload`.
- Per-type content (`ChatContent`, `ProfileContent`, `GroupLeaveContent`, `ReceiptContent`,
  `ReactionContent`, `BlobReqContent`, `KeyReqContent`, `TypingContent`) lives inside `payload`; only
  endpoints parse it. Current `type`s: `chat`, `groupupdate`, `profile`, `receipt`, `reaction`,
  `groupleave`, `blobreq`, `keyreq`, `typing` (a best-effort, single-hop, never-custodied "now typing"
  cue — see the typing-indicator flow).

**One signature authenticates every type**: `WireEnvelope.sig` is raw Ed25519 over `signed` (which
binds `type`/`id`/`senderId`), verified byte-exact in `MeshManager.verifyInbound`; `blobreq` (with
`relay = false`) is the only unsigned frame. `MeshManager` signs on origination; `verifyInbound` drops
any flooded frame whose signature is missing/invalid or whose key doesn't derive to `senderId`, but
the router still relays unknown/unverifiable frames verbatim so an old build is never a black hole.
`Protocol.VERSION`/`capabilities` ride (unauthenticated) in the Wi-Fi Aware `serviceSpecificInfo`
advert and (authenticated) on `ProfileContent`.

`WireCodec` exposes `encodeWire`/`decodeWire` (WireEnvelope), `encodeEnvelope`/`decodeEnvelope`
(RelayEnvelope), and `encodePayload`/`decodePayload<T>` (content) — see `docs/WIRE_COMPAT.md`.

## What counts as a wire break

Changing `WireEnvelope`'s shape, the `WireCodec` config, the signing input, the `SERVICE_NAME`
(`WifiAwareTransport`), or removing/renaming a field/type is a coordinated wire break; adding a
nullable/defaulted field or a new `type` is additive. See `docs/WIRE_COMPAT.md`. Because the wire is
opaque `@ByteString` CBOR (not kotlinx sealed polymorphism), a relay rewrites only `ttl`/`hops` and
passes `signed`+`sig` through byte-for-byte — see the verbatim-relay rule in `rules/mesh.md`.

Note the **TTL constants** (`DEFAULT_TTL_MS`/`DEFAULT_BROADCAST_TTL_MS`) and the broadcast-chat
classification are **convergence-critical** — treat changing them like a wire change (see
`context/store-and-forward.md`).
