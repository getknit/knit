# Mesh invariants

Never-break rules for anything under `mesh/`, `protocol/`, or `data/` custody. The operational detail
behind each lives in `context/mesh-transport.md`, `context/wire-format.md`, and
`context/store-and-forward.md`.

## Keep each radio behind `MeshTransport`

- Nothing outside `mesh/wifiaware/` may import `android.net.wifi.aware.*` (or
  `ConnectivityManager`/`NetworkRequest` for the NAN data path).
- Nothing outside `mesh/bluetooth/` may import `android.bluetooth.*`.
- Everything above the transport talks only to the `MeshTransport` interface; `CompositeMeshTransport`
  runs both radios at once behind that seam (Bluetooth preferred, Wi-Fi Aware second), so orchestration
  (`MeshManager`/`MeshRouter`) is unchanged and another sibling transport drops in the same way. The socket
  record codec (`mesh/link/LinkFraming`) is transport-neutral and shared by the NAN NDP socket and the BLE
  L2CAP socket.
- After changing the `MeshTransport` interface, run `:app:testDebugUnitTest` — a test double
  (`RecordingTransport` in `MeshRouterTest`) implements that interface and won't be caught by
  `assembleDebug`.

## Keep pure mesh logic Android-free

`MeshRouter`, `SeenSet`, `WireCodec`, `MeshMetrics`, `BlobExchange`, and `Conversations` have no Android
dependencies and are unit-tested with `FakeLoopTransport`/fakes. Keep them that way. `MeshRouter` relay
timing is driven by an injectable `jitter` lambda so tests use a fixed delay + virtual time.

## Forward `signed`/`sig` verbatim on relay — never re-encode them

The wire is layered CBOR of opaque `@ByteString` blobs (`WireEnvelope.signed`/`sig`,
`RelayEnvelope.payload`), **not** kotlinx sealed polymorphism, precisely so a relay rewrites only
`ttl`/`hops` (`WireEnvelope.relayed()`) and passes `signed`+`sig` through byte-for-byte. Decoding `signed`
to a `RelayEnvelope` and re-encoding it could legally reorder CBOR keys and break the originator's Ed25519
signature — the old "an old relay re-encodes and breaks the signature" bomb. Keep `RelayEnvelope.type` a
plain `String` too (an unknown future type must *decode and relay*, not throw).

## Wire changes are a coordinated break — additive only

**Read `docs/WIRE_COMPAT.md` before changing any wire type.** Changing `WireEnvelope`'s shape, the
`WireCodec` config, the signing input, the `SERVICE_NAME`, or removing/renaming a field/type is a
coordinated wire break; adding a nullable/defaulted field or a new `type` is additive. Structure detail:
`context/wire-format.md`.

## Custody must converge — the content-digest rule

**Anything the content digest is folded over must be bounded by a rule that's identical on every node**
(same key, same direction, same origins, same liveness). Evict by the **frame-global `(sentAt, id)`** on
**every** origin (`ORIGIN_SELF` included), fold **live** ids only, and refuse a frame past its
frame-global expiry at store time. This makes the **TTL constants
(`DEFAULT_TTL_MS`/`DEFAULT_BROADCAST_TTL_MS`) and the broadcast-chat classification
convergence-critical — treat changing them like a wire change.** Two nodes that disagree hold different
live sets continuously and churn the NDP cue plane forever. Full failure history + how to verify
(`…debug.STORE`, `liveFingerprint` parity): `context/store-and-forward.md`.

## Inbound handlers must never throw

Decrypt/verify failures must never throw out of the inbound handler — `onDeliver` runs before the router
schedules the relay, so a throw would stop forwarding (`MeshManager.decryptAndDeliver`;
`verifyInbound` swallows failures and returns false so the router still relays). See
`context/e2e-encryption.md`.
