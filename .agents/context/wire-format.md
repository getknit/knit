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
  `id`, `senderId`, `sentAt`, `recipientId`, `group` — plus the opaque per-type `payload`. The `id` is not opaque
  on the way in: every door refuses one that is not the shape its type is minted with (`FrameId.admits`, ADR
  2026-10.9qr4; `docs/WIRE_COMPAT.md` rule 7).
- Per-type content (`ChatContent`, `ProfileContent`, `GroupLeaveContent`, `ReceiptContent`,
  `ReactionContent`, `BlobReqContent`, `BlobHaveContent`, `BlobHasContent`, `KeyReqContent`, `TypingContent`) lives
  inside `payload`; only endpoints parse it. Current `type`s: `chat`, `groupupdate`, `profile`, `receipt`, `reaction`,
  `groupleave`, `blobreq`, `blobhave`, `blobhas`, `blobprog`, `blobcancel`, `keyreq`, `typing` (a best-effort, single-hop, never-custodied "now
  typing" cue — see the typing-indicator flow), `commons`. A `blobreq` may carry an `offset`: the bytes the asker kept
  from a transfer a link drop cut off, answered by a link `FILE_HEADER` that echoes it (ADR 2026-10.wtyc), a `fastOnly`
  (Wi-Fi Aware only, ADR 2026-10.js2p), and a `pull` budget and `until` deadline (how many phones deep it may still
  pull, the receiver counted, absent read as 1; and until when, epoch ms: ADR 2026-10.2drc). A
  `blobhave` asks a neighbour whose link carries `CAP_BLOB_HAVE` which of up to 32 blobs it holds; its `blobhas`
  answers each with a state (NONE, HAVE, SENDING, FETCHING; PARTIAL reserved), a size, the responder's load and the
  query's id (ADR 2026-10.6e5t), and a SENDING one may say when the asker's copy should land (`eta`, ms). A `blobprog`
  goes from a file's receiver back to its sender every 2 s of chunks: the hash, the bytes held and the ms since the
  header by the receiver's clock (ADR 2026-10.nsr7), under the same `CAP_BLOB_HAVE` bit. A `blobcancel` (the hash)
  withdraws an asker's ask: the holder drops it if parked, and a copy queued and not yet streaming (the same bit).

**One signature authenticates every type**: `WireEnvelope.sig` is raw Ed25519 over `signed` (which
binds `type`/`id`/`senderId`), verified byte-exact in `MeshManager.verifyInbound`; the blob traffic — `blobreq`,
`blobhave`, `blobhas`, `blobprog`, `blobcancel`, each with `relay = false` (`FrameType.isUnsignedPointToPoint`; a copy marked for relay is refused by `verifyInbound`, counted as `POINT_TO_POINT_FLOODED`, and never flooded by `MeshRouter.scheduleRelay`) — and the v3 live-link delivery tick (ADR 059 — a `relay = false` DM-form chat addressed to
us, authenticated by its ratchet AEAD) are the only unsigned frames. `MeshManager` signs on origination; `verifyInbound` drops
any flooded frame whose signature is missing/invalid or whose key doesn't derive to `senderId`, but
the router still relays unknown/unverifiable frames verbatim so an old build is never a black hole.
`Protocol.VERSION`/`capabilities` ride (unauthenticated) in the Wi-Fi Aware `serviceSpecificInfo`
advert and (authenticated) on `ProfileContent`.

**The link HELLO and its proof** (ADR 2026-11.264w, paired with knit-ios). A link's first record each way is a
`HELLO`, `nodeId|version|capsHex|n<32 hex>` (`Protocol.hello`; the nonce field is present exactly when the caps carry
`CAP_LINK_PROOF`, and every older reader takes fields 0-2 only). When both HELLOs carry the bit, a `PROOF` record (tag
8, 128 bytes: `sigPub ‖ hpkePub ‖ Ed25519 signature`) follows each way over `"knit/link/v1/proof" ‖ role ‖ both HELLOs`
(`mesh/crypto/LinkProof`), and it is never written toward a HELLO without the bit: older builds drop a link on an
unknown tag. The SSI (`Protocol.advertise`) never carries the nonce. A stored peer's capabilities follow its profile's
presentation version, not the prekey watermark, so an older profile cannot take a bit back.

**The sealed link** (ADR 2026-11.8mhs, knit-next #286, paired with knit-ios #51). Toward a peer whose advert carries
`BleAdvertPayload.FLAG_LINK_SEAL` (bit 4 of byte 23) or whose SSI caps carry `CAP_LINK_SEAL` (0x100000), a link's first
record each way is a `KEY_SHARE` (tag 10, exactly 32 bytes: a fresh X25519 public key), not a HELLO; everything after
rides `SEALED` frames (tag 11, `[0x0B][u32be len][ct ‖ tag]`, `len` 17..65,552, AAD = the 5 header bytes, nonce
`0⁴ ‖ u64be(frame count)`), under `HKDF-SHA256(ss, eD ‖ eR, "knit/link/v1/seal/d2r" | "knit/link/v1/seal/r2d")`
(`mesh/crypto/LinkSeal`, `mesh/link/SealedLinkSocket`). The plaintexts concatenated are today's record stream, from
the HELLO on; frame boundaries mean nothing. The PROOF inside signs `"knit/link/v2/proof" ‖ role ‖ eD ‖ eR ‖ both
HELLOs`; plain links keep v1 byte for byte. Tags 10 and 11 are never written on a plain link (every shipped build drops
the link on them), and a plain link whose two HELLOs both carry the bit is refused. Direct links never seal.

`WireCodec` exposes `encodeWire`/`decodeWire` (WireEnvelope), `encodeEnvelope`/`decodeEnvelope`
(RelayEnvelope), and `encodePayload`/`decodePayload<T>` (content) — see `docs/WIRE_COMPAT.md`.

## What counts as a wire break

Changing `WireEnvelope`'s shape, the `WireCodec` config, the signing input, the `SERVICE_NAME`
(`WifiAwareTransport`), or removing/renaming a field/type is a coordinated wire break; adding a
nullable/defaulted field or a new `type` is additive. See `docs/WIRE_COMPAT.md`. Because the wire is
opaque `@ByteString` CBOR (not kotlinx sealed polymorphism), a relay rewrites only `ttl`/`hops` and
passes `signed`+`sig` through byte-for-byte — see the verbatim-relay rule in `rules/mesh.md`. The two
size-capped fast planes may carry `signed` *transcoded* (`mesh/link/FrameTranscoder`, tag `0x05`, ADR 060):
a transport-local re-encoding the receiver rebuilds byte-exact before verifying, so the bytes that are
signed, stored and relayed still never change — see `context/mesh-transport.md`.

Note the **TTL constants** (`DEFAULT_TTL_MS`/`DEFAULT_BROADCAST_TTL_MS`) and the broadcast-chat
classification are **convergence-critical** — treat changing them like a wire change (see
`context/store-and-forward.md`).
