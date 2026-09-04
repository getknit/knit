# Mesh invariants

Never-break rules for anything under `mesh/`, `protocol/`, or `data/` custody. The operational detail
behind each lives in `context/mesh-transport.md`, `context/wire-format.md`, and
`context/store-and-forward.md`.

## Keep each radio behind `MeshTransport`

- Nothing outside `mesh/wifiaware/` may import `android.net.wifi.aware.*`. `ConnectivityManager` /
  `NetworkRequest` / `NetworkCapabilities` have exactly two importers: `mesh/wifiaware/` for the NAN data
  path, and `net/AndroidInternetGate.kt`, the validated-Internet seam (ADR: link previews) that answers "is
  the *default* network a route to the Internet right now" and hands that `Network` out for a fetch to bind
  to. Nothing may call `bindProcessToNetwork`: it is process-global and would move the mesh sockets onto the
  default network.
- Nothing outside `mesh/bluetooth/` may import `android.bluetooth.*` (the Meshtastic GATT client lives at
  `mesh/bluetooth/meshtastic/MeshtasticGatt` under that boundary; its pure session/codec sit in `mesh/lora/`,
  which imports no Android at all — ADR 038).
- Nothing outside `mesh/spool/OkHttpSpoolDialer.kt` and `linkpreview/OkHttpPreviewFetcher.kt` may import
  `okhttp3.*` (detekt's `ForbiddenImport` enforces it). Each sits behind a pure seam — `SpoolLink`/`SpoolSocket`
  for the Internet plane's socket, `PreviewFetcher` for the link-preview fetch — for the same reason the radios
  sit behind `MeshTransport`: everything protocol- or policy-shaped above it (`SpoolConnection`, `ScopeSync`,
  `LinkPreviewService`) stays pure and runs against an in-process fake in unit tests. The preview fetcher
  additionally binds every socket and DNS lookup to the `Network` the gate handed it, so preview bytes can
  never ride the Wi-Fi Aware NDI, and refuses any address `PublicAddressPolicy` calls private on every hop.
- Everything above the transport talks only to the `MeshTransport` interface; `CompositeMeshTransport`
  runs every radio at once behind that seam (Bluetooth preferred, Wi-Fi Aware second, LoRa last), so
  orchestration (`MeshManager`/`MeshRouter`) is unchanged and another sibling transport drops in the same
  way — the LoRa plane (ADR 038) is a fast-plane-only child, `neighbors` always empty, that carries the
  broadcast subset plus sealed DM-form chat, the latter through `MeshTransport.longRangeFanout` — the seam
  reserved for a plane with no data path (ADR 039; never widen `shouldFastFanout` for it, that is the NAN
  coordination plane). `MeshTransport.shortRange` (LoRa = false) tells the composite a sighting doesn't imply
  proximity, so it's excluded from the foreign-reachable union and from `shortRangeReachable`. The socket
  record codec (`mesh/link/LinkFraming`) is transport-neutral and shared by the NAN NDP socket and the BLE
  L2CAP socket.
- After changing the `MeshTransport` interface, run `:app:testDebugUnitTest` — a test double
  (`RecordingTransport` in `MeshRouterTest`) implements that interface and won't be caught by
  `assembleDebug`. Same trap on `ForwardStore` (`FakeForwardStore`, `FakeCustody`) and `RatchetStore`.

## The Internet plane is a custody-plane sibling, not a third transport

`ScopeSync` (`mesh/spool/`, `docs/SPOOL_PROTOCOL.md`) sits beside `ForwardSync` under `MeshManager` —
deliberately **not** behind `MeshTransport`, whose seam is peer-addressed and radio-shaped while a scope
has no neighbors (ADR 019). It reaches the app through exactly two existing doors and adds no delivery
semantics of its own: `InboundPipeline.canCarry` authenticates a pulled frame, and
`MeshRouter.handleInbound` delivers it (dedup, custody, roster vetting, and the onward mesh relay come
free). Two invariants that are easy to break:

- **Only frames matching the scope frame-set rule may be sealed into a scope, in *both* directions**
  (`ScopeFrames.eligibleFor`, spec §4.4) — a scope is not a general-purpose upload channel. The group
  half has two traps: a `groupleave` carries its group id in the **payload** (never in
  `RelayEnvelope.group`), and the sender is vetted against the **founding** roster (members ∪ departed),
  because a leaver is already departed when its own leave frame is evaluated. A cleartext `profile` rides
  **both** forms (ADR 022) and is the exception to the addressing pattern: it names no recipient and no
  group, so the DM half matches it on sender alone and the group half rests wholly on the founding roster.
  Do not "tighten" that back to a recipient match — it is the only carrier of the prekey, and without it a
  peer off the radios can never bootstrap or repair a DM session, nor receive group sender-key seeds.
- **A blob that fails validation is quarantined per (spool, scope), never merely dropped** (spec §9.3).
  Spools are untrusted storage: a garbage blob folds into *their* digest and never ours, so without the
  invalid set the two digests diverge forever and the client re-pulls it on every heal round.
- **A blob that bridged but that custody did not keep is *accounted*, not re-pulled** (spec §9.6, ADR 062)
  — the same divergence through the one door §9.3 does not cover, since these blobs are valid and die at
  the custody store's dead-on-arrival guard. The scope TTL (48 h) outlives mesh custody (24 h) on purpose,
  so this band is half the retention window, not an edge case. Three traps: decide it by asking the store
  (`!store.has(id)`), never by re-deriving the custody TTL rule here; never fold an id that is accounted
  **and** held, since an XOR fold would cancel it out; and prune the set to the spool's listing. The set
  must outlive a connection — `accepted` is the per-connection race guard and still clears on reconnect,
  which is what lets a custody wipe re-converge by the ordinary route.
- **Attachments are a second object class, deliberately outside the scope digest**
  (`ScopeAttachments`, spec §4.5/§6.5/§9.5). Presence is discovered by asking (`ahave`), never by
  anti-entropy, because the quota is in *bytes* and a byte budget cannot be identical on every node —
  folding it in would make two spools with different budgets diverge forever. Two more traps: the
  attachment id is **keyed** (`HKDF(nonceKey, …)`), since the plain hash rides the mesh in cleartext
  and would otherwise let a spool confirm a frame belongs to a scope; and a client must **never** send
  an attachment record to a spool that omitted the three HELLO limits — an unknown record is skipped
  without an answer, stranding that `q` until the request timeout.
- **The attachment push-half deferral is a delay, never a veto** (`AttachmentDeferPolicy`, spec §9.5,
  ADR 021). Bytes wait while the radios are still carrying them, and only attachments — gating *frames*
  would make the scope digest a function of local mesh state and it would never converge again. Two
  rules keep a deferral from becoming a stranded image: it must **re-open by itself** when the evidence
  lapses (which is why the expiring `MeshTransport.reachable` sighting is half the rule and the
  never-expiring delivery tick cannot be the whole of it — a frame can be acked while its bytes were
  never pulled), and it must **end before the frame leaves custody**, since an attachment stops being
  nameable once `ScopeAttachments.references` no longer sees its frame. Group scopes never defer: the
  sealed group tick flips on the *first* member's receipt, so it can never mean "everyone holds it".
- **A profile has two propagation paths and they order on one number.** The cleartext `profile` frame
  is first contact only (it is self-certifying — the node id IS the hash of the `pubKey` in its own
  payload — so it can never be encrypted); presentation updates to an established contact ride
  `CTL_PROFILE` sealed inside v2 chat, which is what makes them cross the Internet plane. Both writers
  gate on the sender's **profile version** (`ProfilePayload.version`, the same value the cleartext
  frame puts in its envelope `sentAt`, stored as `PeerEntity.updatedAt`) — never on the carrying
  frame's own `sentAt`, or a re-sent ctl outranks a genuinely newer profile. The sealed path never
  touches the pinned key, the prekey, the device tag or the capabilities, and never inserts a peer row.
  **A presentation field rides all three profile layouts together** — `ProfileContent`, `ProfilePayload`
  and the compact `ProfileV2` — or the next sealed update silently reverts it (the `openToChat` precedent in
  `docs/WIRE_COMPAT.md`); and it is not a status notice unless `peerPresentationNotices` is taught it.
- **A pair scope is the DM rule with a different secret, and it is temporary** (`ScopeRegistry.pairs`,
  spec §3.5, ADR 042). It is derived from `ScopeCrypto.pairSecret` — the one identity-keyed scope input —
  and named only while `IntroSync` holds the peer pending or in its 48 h grace. Never widen its frame
  set beyond `eligibleForDm`, never derive it from anything a card holder or a node-id holder could
  compute, and never keep it subscribed past the grace: its id is stable per pair, so its subscription
  window is the whole bound on spool-side linkability. Intro-store writes stay outside the ratchet
  mutex (they run post-commit, in `InboundPipeline`'s `onPeerFrameOpened`/`onProfilePinned` hooks); if one
  ever moves into the ratchet commit it goes through `SessionTransactor`.
- **A scope's convergence state says nothing about its peer** (`ScopeStatus.peerSeenAt`, ADR
  2026-09.2ajk). A scope is derived from the pairwise ratchet root, so it stays subscribed, connected and
  `converged` while its peer sits switched off for a month — every field on `ScopeStatus` but this one is
  a statement about the **spool**. Anything that wants to say a peer is *there* reads `peerSeenAt`, which
  `notePeerPresence` stamps only for a bridged frame whose author is the scope's own `peerId` and which
  passes `mesh/FramePresence.kt`'s `isPresenceEvidence` — the spool's 48 h retention means a client pulls
  old frames as a matter of course, so without the age rule one backlog pull resurrects its author. It is
  presence bookkeeping and **never** a delivery gate: a frame that fails it still bridges.
- **Group-root minting is damped; group-root adoption is not** (`GroupRootPolicy`, spec §3.2). Several
  members minting version 1 at once is normal and self-healing — `(version, minter)` collapses the
  lineages. Refusing to *adopt* a strictly-greater root is the failure mode: the device keeps gossiping
  a root everyone else ignores and never converges again. Bound outbound chatter (the per-(group,
  member) seed-send floor), never adoption.

## The DB transaction is taken BEFORE the ratchet mutex — always

Room over SQLCipher serves this app through a **single** connection, and every `mutex.withLock` block in
`RatchetSessions`/`GroupRatchetSessions` touches the store. So the two acquisitions must always happen in
one order: **transaction OUTER, mutex INNER**. Both facades enforce it themselves via the injected
`SessionTransactor` — take the lock through the private `locked { }` helper, never `mutex.withLock`
directly, and never add a store call under the lock by another route.

Get it backwards and the app deadlocks: the decrypt path (`db.withWriteTransaction { commitOpen(…) }`) holds
the connection and waits for the mutex, while a seal/sweep/export path holds the mutex and waits for the
connection. **Both parties are suspended coroutines, so a thread dump shows nothing** — no thread holds a
transaction, yet every later DB user blocks forever and the process ANRs on whatever reads the database
next. This wedged a lab device on the M4 smoke; `SessionTransactorOrderTest` is the regression, and it
fails loudly (a store call with no enclosing transaction) rather than hanging.

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
