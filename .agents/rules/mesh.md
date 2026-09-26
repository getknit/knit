# Mesh invariants

Never-break rules for anything under `mesh/`, `protocol/`, or `data/` custody. The operational detail
behind each lives in `context/mesh-transport.md`, `context/wire-format.md`, and
`context/store-and-forward.md`.

## Keep each radio behind `MeshTransport`

- Nothing outside `mesh/wifiaware/` may import `android.net.wifi.aware.*`. `ConnectivityManager` /
  `NetworkRequest` / `NetworkCapabilities` have exactly two importers: `mesh/wifiaware/` for the NAN data
  path (and its passive `TRANSPORT_WIFI` watch behind `NanInitiatorPolicy`, ADR 2026-09.m8kc — a
  `registerNetworkCallback`, never a request), and `net/AndroidInternetGate.kt`, the validated-Internet seam (ADR: link previews) that answers "is
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
  L2CAP socket. `MeshTransport.coveredByInternet` (ADR 2026-09.y5f3) is the third hint beside
  `suppressDataPath` and `onForeignReachable`: peers a connected spool recently heard from, so a plane with
  no data path keeps DM-form frames to them off the air. It is fed from spool presence only, forwarded to
  every child, acted on by LoRa alone, and must never move the gateway role.
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

- **A spool is connected once it says hello, and only then** (`SpoolConnection.isReady`, ADR
  2026-09.vej5). `SpoolStatus.connected` — and everything downstream of it: `RelayFacts`, coverage,
  `planeFor`, `SpoolPresence` — means a completed hello on an open socket, never that a socket exists: a
  route that swallows the upgrade hands out a socket for the whole connect timeout. A dial nothing answered
  is the dialer's `unreachable` verdict (`failureReason`, no HTTP response), a socket that opened and never
  said hello is dropped through `abort(NO_HELLO)` like every other client-side close (ADR 2026-09.amzn),
  and both hold in `lastError` until a hello completes. A new validated default network
  (`InternetGate.routeChanges`) re-dials a worker with no live hello at once, with its backoff reset — but
  never inside a spool's `Retry-After`, which is the spool's ask about its own load. With **no** validated
  route (`InternetGate.isOnline`, the `online` seam) a worker does not dial at all and keeps the last real
  verdict — the relay row already says the phone is offline — and a relay that stays unreached backs off
  past the minute to fifteen (`SpoolBackoffPolicy`, ADR 2026-09.wa79).
- **A heal round runs when something changed, and every scope once a minute** (ADR 2026-09.wa79). The
  scope table is re-derived on its inputs' events — a DM session confirmed, replaced or forgotten
  (`RatchetSessions.rootChanges`), a pair peer named, evicted, lapsed or pinned (`IntroSync.onPairsChanged`),
  a group root minted or adopted, a commons joined or left, the relay list edited — through
  `ScopeSync.onScopeTableChanged`, with a 60 s poll under the calendar-only transitions (a drain window or
  a pair grace lapsing, a swept root; ADR 2026-09.dcah); `reconcile` wakes a worker only when that table
  differs. A scope is healed when its spool digest *moved*, it took a delivery or a direct push, or local
  custody changed (every scope), else on the 60 s tick — one custody read per round, shared by every due
  scope. `republishPresence` stays first in every round because the tick is what lets a stamp lapse. A
  pushed-whole, fetched or dead attachment is settled for the connection and asked about again only on a
  timed round ten minutes on (or a new session); one still in flight re-marks its scope every 15 s. Don't
  add a wake that carries no change, and don't add a table hook that fires on an unchanged input — the
  before/after view compare in `RatchetSessions` and the `lastPairs` diff in `IntroSync` are the gate.
  Two things the event-driven table exposed: `ScopeStatus.converged` is false until the spool has answered
  an anchor (two absent digests are not agreement), and `MeshRouter` never counts a `spool:` source toward
  overhear suppression — neither a duplicate (the echo of our own push lands inside the relay jitter now)
  nor the first copy that seeds a pending relay's `heardFrom` (#84): a spool copy says nothing about what a
  radio neighbour heard.
- **Only frames matching the scope frame-set rule may be sealed into a scope, in *both* directions**
  (`ScopeFrames.eligibleFor`, spec §4.4) — a scope is not a general-purpose upload channel. The group
  half has two traps: a `groupleave` carries its group id in the **payload** (never in
  `RelayEnvelope.group`), and the sender is vetted against the **founding** roster (members ∪ departed),
  because a leaver is already departed when its own leave frame is evaluated. A cleartext `profile` rides
  **both** forms (ADR 022) and is the exception to the addressing pattern: it names no recipient and no
  group, so the DM half matches it on sender alone and the group half rests wholly on the founding roster.
  Do not "tighten" that back to a recipient match — it is the only carrier of the prekey, and without it a
  peer off the radios can never bootstrap or repair a DM session, nor receive group sender-key seeds.
- **A *pulled* blob that fails validation is quarantined per (spool, scope), never merely dropped** (spec
  C-9.3-1). Spools are untrusted storage: a garbage blob folds into *their* digest and never ours, so
  without the invalid set the two digests diverge forever and the client re-pulls it on every heal round.
  The rule is deliberately narrower than "any blob": an unsolicited `event` that fails is released and
  dropped, because an id we never pulled cannot drive that loop, and letting the spool write into a
  bounded set by sending garbage is how it evicts the entries that matter — if the spool really holds
  the id, the next listing names it and the pull path quarantines it (ADR 025, ADR 2026-09.amzn).
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
  ADR 021, amended by ADR 2026-09.xmte and ADR 2026-09.p7j8). Bytes wait while the radios are still
  carrying them, and only attachments — gating *frames* would make the scope digest a function of local
  mesh state and it would never converge again. Two rules keep a deferral from becoming a stranded image:
  it must **re-open by itself** when the evidence lapses (which is why the expiring
  `MeshTransport.reachable` sighting is half the rule and the never-expiring delivery tick cannot be the
  whole of it — a frame can be acked while its bytes were never pulled), and it must **end before the
  frame leaves custody**, since an attachment stops being nameable once `ScopeAttachments.references` no
  longer sees its frame. The evidence half has one shape with three traps. It must name a **short-range
  plane** (`attachmentCarriedByRadio`, not the bare tick — a spool receipt would defer on the very plane
  the push feeds, and a board carries a frame and never a blob). It is read from **either end** of the DM
  — our send they acked over a radio, or their send that reached us over one — or a recipient re-uploads
  every photo BLE just handed it. And a **missing** ack is only evidence once one could have arrived, so
  `ACK_GRACE_MS` covers the round `onCustodyChanged` runs on the send itself; `authoredHere` gates that
  grace and nothing else. Group scopes never defer: the sealed group tick flips on the *first* member's
  receipt, so it can never mean "everyone holds it".
- **A profile has two propagation paths and they order on one number.** The cleartext `profile` frame
  is first contact only (it is self-certifying — the node id IS the hash of the `pubKey` in its own
  payload — so it can never be encrypted); presentation updates to an established contact ride
  `CTL_PROFILE` sealed inside v2 chat, which is what makes them cross the Internet plane. Both writers
  gate on the sender's **profile version** (`ProfilePayload.version`, the same value the cleartext
  frame puts in its envelope `sentAt`, stored as `PeerEntity.updatedAt`) — never on the carrying
  frame's own `sentAt`, or a re-sent ctl outranks a genuinely newer profile. Both writers bound that
  number to `now + Protocol.MAX_FUTURE_SKEW_MS` before it becomes a watermark (`InboundPipeline.clampFuture`,
  ADR 2026-09.gdhp) — the peer picks it, and stored raw one far-future value freezes their row for good.
  The same clamp covers every other sender-supplied last-writer-wins clock the pipeline keeps: a reaction's
  stamp, a group's name and photo clocks, a member's leave and rejoin. Never on the envelope itself (the era
  gate, ack ids and signatures need the raw `sentAt`). The sealed path never
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
  presence bookkeeping and **never** a delivery gate: a frame that fails it still bridges. Since ADR
  2026-09.y5f3 it is also a **mesh input**: `ScopeSync.presentPeers` / `onPresenceChanged` (one function,
  `mesh/spool/SpoolPresence.kt`, read at `SPOOL_COVER_MS` = 15 min by the mesh and at `PRESENCE_LINGER_MS`
  by the presence dot) feeds `MeshTransport.coveredByInternet` — the LoRa plane's second cover, a
  **cover hint and never an election input** (the ADR 044 election reads `suppressDataPath`'s link set;
  never reuse `linkedPeers` for this) — and `AckSync`'s spool route. The stamp now precedes `deliver`
  on purpose (the receipt answering the revealing DM is originated inside it), and is still not a gate.
- **The direct push is the one push not sourced from custody, and it is accounted** (`ScopeSync.pushDirect`,
  spec §9.4 C-9.4-3, ADR 2026-09.y5f3). It carries exactly one frame class — a room post's **signed**
  `relay = false` delivery tick at its ride deadline (`ScopeCrypto.seal` takes the 64-byte signature, so
  ADR 059's unsigned form cannot ride here) — through the same §4.4 rule, the outward dead-on-arrival
  guard and the size bound as a round's push, and then writes the §9.6 accounted set and folds the anchor
  exactly as `reanchor` would. Skip the accounting and the pusher's next round pulls its own tick back
  into custody and onto the radios — the cost the push exists to avoid. It takes the worker's `round`
  mutex, which `heal` also holds; nothing on the inbound path may push, or that is a deadlock.
- **One frame id, one set of bytes.** A node's own profile is signed from live settings
  (`currentProfileEnvelope`), and its id keys on the publish stamp — so a second signing of the same stamp
  while a settings write is still landing is a *variant*: same id, different `signed`, a second blob at
  the spool that neither side's custody can ever fold, and a scope digest that never converges. The
  mesh-in-a-box lab caught the LoRa beacon doing exactly that. `MeshManager.ownProfile()` is the one
  source now — the custodied row for the current stamp when there is one, a fresh signing that is
  custodied at once otherwise — and every plane, reflood and first-contact push reads it. Don't sign a
  profile anywhere else. The signing and every write of the stamp or the version sit under one
  `profileLock` (ADR 2026-09.qztx): the settings are read one key at a time, so a signer that overlaps an
  edit reads the new stamp with the old version and custodies *that* — the edit's own bytes then lose the
  `INSERT … IGNORE` while the flood has already carried them (both CI runners hit it, 2026-09-16). A new
  caller that wants the bytes reads `ownProfile()`; one that wants a fresh stamp goes through
  `broadcastProfile()` / `republishProfile()`; nothing writes `profilePublishedAt` or `profileVersion`
  outside them, and the stamp is `nextPublishStamp()` — strictly past the last, so two edits in one tick
  are two frames. The watcher that mints on an edit (`watchProfileChanges`) is `distinctUntilChanged` with
  **no** `drop(1)`: every settings flow is a projection of one DataStore that re-emits on a write to any key,
  and a drop ahead of distinct made the second identical emission look like an edit — a fresh stamp on every
  launch. Its first value is compared against the custodied frame instead (`custodyPresentsOtherThan`, after
  the seed), which is also what publishes an edit made while the mesh was stopped.
- **A blob is served only to a fresh ask, and never asked for while it is arriving** (`BlobExchange`, ADR
  2026-09.4tx5, superseding 2026-09.ywzn). Nothing is pushed to a peer that did not just ask: a neighbour
  that asked while we lacked the bytes is served on its next ask (its own 60 s tick), because the serving
  side cannot tell an asker that still lacks them from one whose copy is already arriving from somebody else
  — in a clique that push bought every recipient a copy per neighbour (#79). Whether bytes are on the way
  is a **read of the link**, never a set with a TTL here: `MeshTransport.arrivingFiles()` (a `FILE_HEADER`
  in, no `FILE_END` yet, off `FramedLink.rxKey`) keeps `want` and the tick's re-ask quiet for that hash,
  and `fileInFlightTo(peer, key)` (queued or streaming, counted from the enqueue) refuses a second copy on
  a re-ask whatever the 45 s memo says. A transfer's length is the receiver's controller's, so no constant
  covers it. Don't add a `noteIncoming` memo or a wanter TTL back.
- **The database says which attachments are missing; `BlobExchange`'s `fetching` set is a swept memo of it**
  (ADR 2026-09.ptv8). A `want` parked with no neighbor (a frame heard over the board alone) is reclaimed by
  the 30-min `FETCH_TTL_MS` sweep, and `onNeighborAdded` re-asks only from the memo — so
  `MeshManager.rewantMissingBlobs()` re-reads `messages.hashesNeedingFetch()` (+ the carrier-only custody
  hashes under budget) on every neighbor join and 60 s re-offer tick, not only at startup. Any new "what do
  we still need" path reads the database the same way; never make the memo the source. The re-ask itself is
  the tick's `onNeighborAdded` per linked neighbour (`want` is a no-op for a hash already in the set), and it
  is what moves a hop-by-hop pull since ADR 2026-09.4tx5 — gate it on `arrivingFiles()`, never remove it.
- **Group-root minting is damped; group-root adoption is not** (`GroupRootPolicy`, spec §3.2). Several
  members minting version 1 at once is normal and self-healing — `(version, minter)` collapses the
  lineages. Refusing to *adopt* a strictly-greater root is the failure mode: the device keeps gossiping
  a root everyone else ignores and never converges again. Bound outbound chatter (the per-(group,
  member) seed-send floor), never adoption.
- **The seed carries the founding roster, and a row it creates goes through `reconcileGroup`** (spec
  C-3.2-16, ADR 2026-09.mjaj). Every `CTL_GROUP_KEY` the one builder `MeshManager.groupKeyPayload` emits
  carries `GroupKeyPayload.group` — the roster half of the row (`toFoundingInfo`: id, members, departed,
  creator, name; never the photo, a blob that rides the group frames) — because a relay-only member's DM
  scope carries the seed while the roster rode only the group scope the seed's own root derives. A member
  holding no row (or holding the sender as departed) pins the group from the seed *before* the park
  decision (`InboundPipeline.pinRosterFromSeed`), through `reconcileGroup` and `vetRoster` like every other
  roster — never by a second door, never without the derivation check. Two traps: the seed is already
  custodied when the pin runs, so the first-sight custody replay must skip it (`replayExcept`) or the outer
  commit silently finds its chain consumed; and `PendingGroupKeys` is now the fallback for a roster-less
  (older-build) or refused seed, not the founding path — don't make a scenario wait on `groupSeedsHeld`.

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

The one bound that is *not* identical on every node is the per-link `IngressBudget` on room posts in
`MeshRouter` (ADR 2026-09.vybk), and it is safe only because of where it sits: **before** `SeenSet.add`, custody and relay. A
refused frame is never marked seen, so the custody re-offer serves it again through the same meter — a
delay, never a veto — and the carried sets re-converge at the metered pace. Never move the meter past the
seen-set add (a refused frame would be deduped forever and the digests would diverge for good), never meter
a duplicate (a re-serve is evidence, not a cost), and never widen it to an addressed or sealed frame class
without the same reasoning: a DM's receipt and a group's seed converge through custody and must not be
throttled at the link.

**The block list is a presentation input and never a custody one** (ADR 010, ADR 2026-09.bts9). It is read on
the local delivery path only — `handleChat` and its siblings, the notification count, the DAO's read filters —
and must never enter `InboundPipeline.canCarry`, the forward store, the relay decision, or `KeyExchange.want`:
a blocker that refuses the blocked sender's frames is re-served them every exchange and refuses them every
time, for the life of the block (work item #45). The same holds for any other per-node input — a setting, a
moderation verdict, a version — a local decision is a delivery gate (`docs/WIRE_COMPAT.md` rule 5).

## Inbound handlers must never throw

Decrypt/verify failures must never throw out of the inbound handler — `onDeliver` runs before the router
schedules the relay, so a throw would stop forwarding (`MeshManager.decryptAndDeliver`;
`verifyInbound` swallows failures and returns false so the router still relays). See
`context/e2e-encryption.md`.
