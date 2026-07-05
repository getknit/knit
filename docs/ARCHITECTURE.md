# Knit — Architecture & Implementation Notes

Detailed design notes for the Knit mesh messenger. For build/contribution rules see
[`../AGENTS.md`](../AGENTS.md); for a user-facing overview see [`../README.md`](../README.md).

---

## 1. Overview

Knit is an offline, serverless proximity messenger. Each device is simultaneously a sender, a
receiver, and a relay. Messages flood across an ad-hoc mesh of nearby phones over **two radios at
once** — **Wi-Fi Aware (NAN)** and **Bluetooth LE** — hopping device-to-device so they can travel
beyond direct radio range. There is **no Google Nearby / Google Play services dependency**: the radios
are driven directly through the framework `android.net.wifi.aware.*` and `android.bluetooth.*` APIs,
composed behind one `MeshTransport` seam so everything above is radio-agnostic.

The app surfaces a **"Nearby" public broadcast room**, **1:1 direct messages**, and **multi-member
group chats**, with profiles (name / status / avatar), emoji **reactions**, **@-mentions**, and image
**attachments** (GIF/JPEG/PNG/WebP). DMs and group messages flood like broadcast traffic — only the
addressed recipient(s) deliver/ack them — but they are **end-to-end encrypted** (§14), so relays
carry ciphertext; the public Nearby room stays plaintext by design. A **store-and-forward** custody
layer makes delivery delay-tolerant (design detail in `AGENTS.md` → *Store-and-forward message
delivery*), and on-device **content moderation** screens abusive text and explicit images
(`docs/CONTENT_MODERATION.md`). The wire format and identity layer still leave room for true
(targeted) routing without rework.

Design goals, in priority order: (1) correctness of the mesh (dedup, bounded propagation,
duplicate-suppressed flooding), (2) a clean transport abstraction so radios can be swapped or run
side-by-side, (3) a Signal-like Compose UI, and (4) reliable background operation.

## 2. Module / package map

Single Gradle module `:app`, package root `app.getknit.knit`.

| Package | Responsibility |
|---|---|
| `ui/` | Compose screens + ViewModels: `onboarding/`, `chatlist/` (conversation list), `chat/` (thread + `MentionText`), `contacts/` (new-DM picker), `profile/`, `group/` (details), `diagnostics/`, `blocked/`, `share/` + `invite/` (offline app share), `donate/`, shared `components/` (`Avatar`, `GroupAvatar`, `ConnectionStatus`), `image/` (Coil `BlobFetcher` / `QrCode`), `theme/`, `util/RelativeTime`, `KnitApp` (Navigation Compose), `Permissions.kt`, `Battery.kt` |
| `mesh/` | `MeshTransport` (interface) + `CompositeMeshTransport` (runs the two radios below at once), `MeshRouter` (dedup + jittered/suppressed flood), `MeshManager` (orchestrator), `MeshService` (foreground service), `MeshMetrics` (counters), `SeenSet`, `FakeLoopTransport` / `DemoTransport`, `BlobExchange` + `BlobStore` (content-addressed pull), `ForwardSync` + `ForwardStore` (store-and-forward custody), `KeyExchange` (`keyreq`) + `PendingInbound` (park-until-key), `StoreDigest` + `DigestTracker` (cue-plane anti-entropy), `power/`, `protocol/Wire.kt` |
| `mesh/crypto/` | End-to-end crypto (Google Tink), pure & JVM-testable: `MessageCrypto` (per-message seal/open; the frame signature is applied by `MeshManager`, §14), `PublicKeyBundle`, `MessageContent` (the encrypted payload), `AttachmentCrypto`, `SafetyNumber`, `VerifyPayload`, `Crypto.kt` (`TinkInit`/`AesGcm`) |
| `mesh/wifiaware/` | `WifiAwareTransport` — the **only** code that touches `android.net.wifi.aware.*` (the NAN plane) |
| `mesh/bluetooth/` | `BluetoothMeshTransport` (BLE advertise/scan presence + persistent L2CAP links) + its pure policies (`PromotionPolicy`, `ScanDemandPolicy`, `ConnectBackoffPolicy`, `BlePresenceTracker`) — the **only** code that touches `android.bluetooth.*` (the BLE plane) |
| `mesh/link/` | `LinkFraming` (transport-neutral socket record codec) + `FramedLink` / `LinkSocket` / `LinkHandshake` — shared by the NAN NDP socket and the BLE L2CAP socket |
| `moderation/` | On-device content moderation: `TextModerator` (`LexicalTextFilter` + `MlTextModerator`) + `ImageModerator` (`NsfwImageModerator`) + `SentencePieceTokenizer` — see `docs/CONTENT_MODERATION.md` |
| `data/` | Room (`KnitDatabase`, `message/`, `peer/`, `reaction/`, `blob/`, `group/`, `forward/`), repositories (`MessageRepository`, `PeerRepository`, `ReactionRepository`, `GroupRepository`, `BlobRepository`, `forward/ForwardRepository`), `AttachmentStore`, `AvatarStore`, `settings/SettingsStore` (DataStore), `crypto/` (`DatabaseKey`, `IdentityKeyStore`, `KeystoreSecret` — AndroidKeyStore-wrapped secrets) |
| `identity/` | `Identity` (stable node id **+ E2E public-key bundle**), `NodeId` (derivation), `DeviceIdSource` (`AndroidDeviceIdSource`), `DeviceTag`, `Alias` (deterministic display-name fallback) |
| `notifications/` | `Notifier` (interface) + `MessageNotifier`, `NotificationChannels`, `NotificationHistory`, `NotificationActionReceiver` |
| `di/` | Koin modules: `appModule`, `meshModule`, `moderationModule`, `uiModule` |

### Data flow

```
 Compose UI ──(send chat / react / attach)──▶ MeshManager ──▶ MeshRouter.originate ──▶ MeshTransport.send ──▶ radios
     ▲                       │                                                                                  │
     │                       ▼ onDeliver (persist, ack, react, cache profile, pull blob)                       │
 StateFlow ◀── Repositories ◀┘                                                                                  │
     ▲                                                                                                          │
     └────────── MeshRouter ◀── (dedup + jittered relay) ◀── MeshTransport.inbound ◀──────────────────────────-┘
```

`MeshManager` is a Koin singleton shared by the foreground service and the UI, so both observe and
drive the same mesh instance. Avatars and attachments travel **out of band as file payloads**, not
inside frames (see §3.1, §7).

## 3. Mesh layer

### 3.1 Transport abstraction (`mesh/MeshTransport.kt`)

```kotlin
interface MeshTransport {
    val neighbors: StateFlow<Set<Peer>>       // live data-path links right now (routing target)
    val reachable: StateFlow<Set<Peer>>       // smoothed "who's nearby" for the UI (coordination plane)
    val health: StateFlow<TransportHealth>    // Healthy / Degraded (radio seized) / Unavailable (off)
    val inbound: Flow<InboundFrame>           // frames received (pre dedup/relay)
    val incomingFiles: Flow<ReceivedFile>     // completed file transfers (avatars + attachments)
    val incomingDigests: Flow<ReceivedDigest> // store-and-forward id-lists (data-path anti-entropy)
    fun start(); fun stop(); fun heal()       // heal() = rescan/reconnect now
    suspend fun send(wire: WireEnvelope, to: Peer? = null)   // null = broadcast to all neighbors
    fun fastFanout(wire: WireEnvelope)                       // coordination-plane blast, no data path
    fun fastSend(wire: WireEnvelope, to: Peer)               // coordination-plane point-to-point
    suspend fun sendFile(file: File, to: Peer, meta: FileMeta): Boolean  // false = no live route (caller's re-offer retries)
    suspend fun sendDigest(to: Peer, ids: List<String>)      // advertise held custody ids on link-up
    fun expectBulkTransfer(nodeId: String): Boolean          // arm an on-demand data path for an imminent big blob
    // + kind / hasFastPlane / highThroughput / radioContended and the cross-plane hints suppressDataPath() / onForeignReachable()
}
```

Supporting types are declared in the same file: `Peer(nodeId, protoVersion, capabilities)` carries the
advertised (unauthenticated) routing hint; `InboundFrame` carries the verbatim `WireEnvelope` plus its
already-decoded `RelayEnvelope` (so the router and delivery paths don't each re-decode); `FileMeta` /
`ReceivedFile` route a completed transfer by `FileKind` — an **AVATAR** (`key` = sender node id) updates
that peer's avatar, an **ATTACHMENT** (`key` = content hash) feeds the blob-exchange pull (§7); and
`ReceivedDigest` carries a neighbor's held custody id-list. `send` takes a `WireEnvelope` (the frozen
on-radio unit, §4); the two `fast*` methods are the best-effort **coordination-plane** path that reaches
neighbors with no data path (§3.2) and no-op on a transport without one.

Implementations:
- **`WifiAwareTransport`** — the Wi-Fi Aware (NAN) plane: a coordination plane (Aware messages) plus one
  ephemeral NDP data path; see §3.2.
- **`BluetoothMeshTransport`** — the Bluetooth LE plane: BLE advertise/scan presence plus several
  persistent L2CAP links; see §3.2.1.
- **`CompositeMeshTransport`** — runs the two radio planes **simultaneously** behind this one interface
  (merging `neighbors`/`reachable`/`health`/`inbound` by nodeId, preferring Bluetooth for sends), so
  `MeshManager`/`MeshRouter` stay radio-agnostic. DI (`di/MeshModule.kt`) gates each child on hardware
  support, so a device with only one radio runs that plane alone. One deliberate exception to the
  Bluetooth-first rule: **ATTACHMENTs prefer the `highThroughput` plane** (a NAN NDP moves megabytes in
  under a second vs. L2CAP's seconds-to-minutes) — any size rides a live NAN link if one exists, and one
  ≥ 128 KiB (`BULK_MIN_BYTES`, compared against the **transcoded wire blob**, not the picked file — a
  multi-MB GIF lands at ~150-250 KB as a 480px animated WebP) additionally arms an on-demand bring-up
  via `expectBulkTransfer` and grace-waits ≤ 10 s (off the inbound dispatch coroutine) before falling
  back to Bluetooth. Every decision logs a `file route: …` line under the `CompositeMeshTransport` tag.
  See the AGENTS.md gotcha for why a plain routing preference is not enough (steady-state digest parity
  + BLE suppression) and why the bulk mark must stay out of the NAN recovery machinery.
- **`FakeLoopTransport`** / **`DemoTransport`** — in-process doubles for JVM tests and the demo build;
  `FakeLoopTransport.connect()` wires instances into an arbitrary topology so a multi-hop mesh (relay,
  blob pulls, custody) runs on the JVM with no radios.

### 3.2 Wi-Fi Aware specifics (`mesh/wifiaware/WifiAwareTransport.kt`)

- **Service `SERVICE_NAME = "app.getknit.knit.MESH.v8"`.** Each node `attach()`es once, then both
  **publishes and subscribes** the service. The device's **node id + version + caps**
  (`Protocol.advertise`) ride in the publish `serviceSpecificInfo`, so a subscriber learns a peer's
  identity on discovery with zero round-trips and rejects discovering itself. `SERVICE_NAME` is the
  transport marker: any breaking change to the cue or socket format (or the node-id derivation) bumps it
  — a **hard cut** (old and new builds simply don't discover each other), history `.v2`…`.v6` in
  `docs/DIGEST_PULL_REATTACH.md`; `.v7` widened the node id to 128 bits (see §10); `.v8` swapped NDP
  security from the passphrase to a fixed 32-byte PMK (`setPmk`).
- **One data interface → two planes.** Real chipsets (Pixel 7/8/9) report `maxNdiInterfaces == 1`, and
  each aware *network* needs its own NDI — so a node holds at most one **outbound** (initiator) NDP, and
  cannot mix roles concurrently. (The **accept-any responder** is one network that can officially serve
  **many concurrent inbound NDPs** on the same NDI — on-device-verified 2026-07-04; the shipped transport
  still serializes serves by choice, and the concurrent-serve redesign is proposed in
  `docs/NAN_CONCURRENCY_REAUDIT.md`.) So the transport does **not** try to link everyone — it runs two
  planes (the deep treatment, including the NDI-wedge root cause, is in `AGENTS.md` and
  `docs/NAN_CONCURRENCY_REAUDIT.md`):
  - **Coordination plane** — Wi-Fi Aware *messages* (`DiscoverySession.sendMessage`, ~255 B, best-effort)
    ride discovery follow-up frames and need **no data path**, so they reach every neighbor at once and
    keep working while the one NDP is busy. Each node cues `nodeId|version`, a `StoreDigest` content
    digest of its custody set (XOR of held frame ids — incremental and restart-stable); `DigestTracker`
    flags a peer *sync-wanted* when either side's digest changed since the last sync. Small floodable
    frames also ride this plane as a best-effort **fast fan-out** (`fastFanout`/`fastSend`), deduped by the
    receiver's `SeenSet`, so they propagate with zero NDP. This is the `hasFastPlane` path
    `CompositeMeshTransport` routes the fast methods to.
  - **Data plane** — one ephemeral NDP (a TCP socket over link-local IPv6), brought up **only** for a
    sync-wanted peer (the larger nodeId initiates; the smaller serves an **accept-any responder** anchored
    to its publish session, because per-peer responders don't compose — one already serving a peer can't
    stand up a second). On link-up each side advertises the custody ids it holds (a
    `LinkFraming.Type.DIGEST` record) and pushes back only the frames the peer lacks, then tears the NDP
    down on quiescence — freeing the interface for the next pair. An idle mesh does **zero** data-path work.
- **Accept-any responder handshake.** Since the responder accepts a data path from any initiator, the
  **initiator sends its advert as the first `LinkFraming.Type.HELLO` record** (`mesh/link/LinkHandshake`,
  shared with BLE) so the responder learns who connected. A fixed app-wide 32-byte PMK (`setPmk` — skips
  the per-NDP passphrase→PMK derivation; same public-constant security) encrypts the link; real
  authentication is the per-frame signature + E2E layer above. Instant Communication Mode (API 33, when
  supported) speeds discovery + data-path bring-up for the brief-encounter (festival) case.
- **Neighbor vs. reachable.** `neighbors` is the ≤1 live NDP link — the routing target for `send` /
  `sendFile` and the "new neighbor" (store-and-forward / key-exchange) hooks — and it **flaps** as
  ephemeral syncs come and go, so the **UI reads the smoothed `reachable` set** (coordination-plane
  sightings, lingered `REACHABLE_LINGER_MS`) instead.
- **Lifecycle hardening.** A time-boxed client `requestNetwork` (`HANDSHAKE_TIMEOUT_MS`) + per-peer
  backoff, a single-flight / generation-guarded `reattach()`, an idempotent responder, and a
  process-restart `checkWedge` watchdog keep the single NDI off the "no interfaces available" wedge (the
  full lifecycle is in `AGENTS.md` and `docs/DIGEST_PULL_REATTACH.md`; the framework root cause of both
  wedges — a 0-NDP TERMINATING request the framework never reaps — plus the ghost-proof fix is in
  `docs/NAN_CONCURRENCY_REAUDIT.md` §2).
  `ACTION_WIFI_AWARE_STATE_CHANGED` drives `health` (→ `Degraded` when Wi-Fi is off or another Wi-Fi mode
  seizes the radio) and re-attach on recovery. A device without `FEATURE_WIFI_AWARE` runs the Bluetooth
  plane instead (§3.2.1); only a device with **neither** radio is "unsupported".
- **Socket framing (`mesh/link/LinkFraming.kt`).** Transport-neutral — the same codec runs over the NAN
  NDP socket and the BLE L2CAP socket. A byte stream is chunked into length-prefixed records
  `[type:1][len:4 big-endian][payload]`: `FRAME` (one CBOR `WireEnvelope` → `inbound`); a file as
  `FILE_HEADER` (JSON `FileHeaderWire`: kind + key + mime) → `FILE_CHUNK`s → `FILE_END` (→
  `incomingFiles`); `DIGEST` (a custody id-list); plus `HELLO`/`KEEPALIVE`. The writer serializes files
  and interleaves live frames *between* chunks so an 8 MiB blob never stalls traffic; a per-file receive
  ceiling matches the 8 MiB send cap.
- **Byte metrics:** `send` records `metrics.onBytesSent(bytes.size * targets.size)` — one frame
  broadcast to N neighbors counts as `frameSize × N`, so the CBOR win is measurable in the field.
  (`sendFile` is not counted.)

### 3.2.1 Bluetooth LE specifics (`mesh/bluetooth/BluetoothMeshTransport.kt`)

The BLE plane runs **simultaneously** with Wi-Fi Aware behind `CompositeMeshTransport`, and is
**preferred** for sends (persistent, cheap links vs. NAN's one ephemeral NDP). Unlike NAN it can hold
**many** links at once, so it needs no single-slot cue dance; it is also the mesh plane for phones with
no Wi-Fi Aware hardware.

- **Presence** — connectable BLE advertising of a fixed 23-byte service-data payload
  (`BleAdvertPayload`: capabilities, the nodeId's raw 16 bytes, digest cue, L2CAP PSM) under a versioned
  16-bit service UUID (`BleConstants.SERVICE_UUID`, bumped on every breaking wire change to hard-partition
  at discovery; the layout/version is implied by the UUID, so there is no format-version byte). The advert
  carries **only** the service-data AD — no separate service-UUID-list AD — so the 16 raw id bytes still
  fit the 31-byte legacy budget; the scan filters on the presence of service data for the UUID. That
  duty-cycled scan feeds `BlePresenceTracker` (per-peer smoothed RSSI + continuous-presence dwell +
  last-seen linger), which drives the `reachable` set. Advertising is always-on so BLE-only devices still
  discover us.
- **Data links** — persistent **L2CAP CoC** sockets; `BluetoothSocketLink` adapts the socket to the same
  `LinkSocket` / `FramedLink` / `LinkFraming` machinery NAN uses, so framing, files, and the `DIGEST`
  id-diff are shared. `PromotionPolicy` (pure) decides which nearby peers to hold a link to (dwell + RSSI,
  bounded by a connection budget, hysteretic weakest-first eviction); `ConnectBackoffPolicy` (pure) backs
  off repeated per-peer connect failures.
- **Adaptive scan** — `ScanDemandPolicy` (pure) duty-cycles the scan by *demand*: a settled clique floors
  the scan (~2 min) while an isolated node scans aggressively. NAN acts as an early-warning — via
  `CompositeMeshTransport.onForeignReachable`, BLE boosts its scan to chase a peer another plane already
  sees onto the cheaper persistent link. `BluetoothAudioMonitor` flags A2DP-audio contention
  (diagnostic-only today; the connect-time gate is deferred — §18).

### 3.3 Routing, dedup & duplicate-suppressed flooding (`mesh/MeshRouter.kt`, `mesh/SeenSet.kt`)

`MeshRouter` is transport-agnostic and unit-tested (`FakeLoopTransport` / a fake). It does **not**
blind-flood. On each inbound frame `handleInbound`:

1. **`SeenSet.add(id)`** — if this is the **first sighting** of the frame id, deliver locally via the
   `onDeliver` callback (persist / ack / react / cache), then **schedule** a relay.
2. If it's a **duplicate** (already seen), it is never re-delivered or re-relayed — but it *is*
   evidence the frame is already propagating, so it's counted toward **overhear suppression**
   (`metrics.onDeduped()` + `countOverheard`).

**Jittered relay with overhear suppression** replaces immediate rebroadcast (which storms a dense
cluster with O(neighbors²) redundant sends):

- `scheduleRelay` keeps the synchronous early-outs — a frame flagged **not to relay** (`relay = false`,
  e.g. a `blobreq`) and a **TTL-exhausted** frame (`hops >= ttl`) are dropped immediately. Otherwise it
  records a `PendingRelay { relayed = wire.relayed(), heardFrom, count, job }`
  in a `Mutex`-guarded `pending` map keyed by frame id, and launches a coroutine that waits a small
  random **`jitter()`** delay, then re-checks under the lock and, if still pending, sends the
  hop-incremented frame to every neighbor **not** in `heardFrom` (split-horizon across every source
  it heard the frame from, not just the first).
- `countOverheard` adds the duplicate's source to `heardFrom` and bumps `count`; once `count`
  reaches **`suppressThreshold`** (default 2 — i.e. one *other* node was heard relaying it), the
  pending relay is **removed and its job cancelled** (cancel happens outside the lock), and
  `metrics.onSuppressed()` fires. This is classic counter-based gossip: redundant rebroadcasts are
  cut in dense meshes while sparse meshes (where no duplicate is overheard) still relay reliably.
- Tunables are constructor params with defaults: `jitterWindowMs = 150`
  (`jitter = { Random.nextLong(jitterWindowMs) }`) and `suppressThreshold = 2`. The `jitter` lambda
  is an injection seam so tests use a fixed delay and virtual time. `metrics` is also injected
  (defaulted) so the same params keep existing call sites source-compatible.
- **Self-originated frames bypass suppression.** `originate()` / `sendOwn()` mark the frame seen and
  send immediately; the echo that returns from the mesh hits the duplicate branch and is a harmless
  no-op.
- `stop()` cancels any relays still waiting out their jitter window and clears the map; it's called
  from `MeshManager.stop()` so coroutines don't linger on the app-lifetime scope. The map is
  otherwise self-draining (an entry is removed on fire or suppress) and transitively bounded by the
  `SeenSet`.

`SeenSet` is a **bounded, time-windowed LRU** (`maxSize` eviction + `ttlMillis` expiry, injectable
clock). Combined with the per-frame TTL/hop-count, propagation is guaranteed to terminate.

### 3.4 Transmission metrics (`mesh/MeshMetrics.kt`)

A pure-JVM, thread-safe counter set (`AtomicLong`s; no Android deps, so it lives in `mesh/` and is
asserted from the same unit tests as `MeshRouter`). It is a Koin singleton injected into both
`WifiAwareTransport` and (via `MeshManager`) `MeshRouter`. Counters: `framesOriginated`,
`framesDelivered`, `framesRelayed`, `framesSuppressed`, `framesDeduped`, `bytesSent` — plus a
`DropReason` breakdown and the key-exchange / pending-inbound recovery counters
(`keyRequestsSent`/`keysServed`/`keysRecovered`, `framesHeld`/`framesReplayed`) surfaced in the
Diagnostics screen; `snapshot()` returns an immutable `Snapshot`. `MeshManager.logMetricsPeriodically()` logs a snapshot every
`60_000 ms`. The `framesSuppressed : framesRelayed` ratio quantifies how much rebroadcasting the
overhear suppression eliminated; `bytesSent` tracks the CBOR byte win.

### 3.5 Orchestration (`mesh/MeshManager.kt`)

Owns the transport + router + `BlobExchange` + `ForwardSync` + `KeyExchange` + `PendingInbound`, and
implements `onDeliver`, the router's per-frame callback. It first runs `verifyInbound` (§14: a byte-exact
Ed25519 check of `WireEnvelope.sig`, drop-but-still-relay on failure; a `NO_SENDER_KEY` drop parks the
frame in `PendingInbound` and asks `KeyExchange` for the sender's profile), then dispatches on the
cleartext `RelayEnvelope.type` **string** (a discriminator, not a sealed subtype — §4) and its decoded
per-type content:

- **`chat`** → blocked-sender drop. A **group** message (`group != null`) reconciles the self-describing
  roster and delivers if we're a member; a **DM** addressed to someone else (`!Conversations.isForMe(...)`)
  is only relayed + carried (don't persist/notify/ack). For a DM/group meant for us the `ChatContent.enc`
  envelope is **decrypted** (`decryptAndDeliver`; dropped on any failure, and never throwing — §14) before
  persisting to Room (unacked), screening the body/image on-device (`docs/CONTENT_MODERATION.md`), pulling
  any referenced attachment blob, notifying (mention vs. normal — §11), and acking (§5). The broadcast
  room is plaintext.
- **`groupupdate`** → reconcile the stored group from the carried roster (e.g. a rename), no message
  persisted. **`groupleave`** → apply the member's leave tombstone.
- **`profile`** → upsert the peer's name/status/avatar (last-writer-wins on `sentAt`) and **pin its E2E
  key** trust-on-first-use into `PeerEntity.pubKey` (a changed key resets `verified` — §14), merging so an
  unfetched avatar isn't clobbered; then **replay** any frames `PendingInbound` parked for that sender.
- **`receipt`** → `markReceived(ackId)` (gated to the DM's `recipientId`) drives the ✓ tick and purges the
  carried DM copy mesh-wide (`ForwardSync.onAck`). A DM's receipt floods and is custodied; a
  **broadcast/group** receipt is a unicast, non-flooded, non-custodied tick the deliverer owes the author
  and re-sends until it lands (`AckSync`) — delay-tolerant, so the ✓✓ isn't lost when the author was out of
  range at delivery time. One surviving receipt flips it ("≥1 received"); `onAck` no-ops for it (no single
  recipient), so retries never evict custody.
- **`reaction`** → `ReactionRepository.apply(...)` (last-writer-wins, §6).
- **`blobreq`** → `BlobExchange.onRequest(...)` (serve the blob or recurse the pull, §7). **`keyreq`** →
  `KeyExchange` re-serves the requested peer's profile verbatim or records the asker and recurses (the
  inbound key-recovery path — see `AGENTS.md` → *Store-and-forward message delivery*).

Every custodial frame is also persisted for store-and-forward (`ForwardSync`, `FrameType.isCustodial`).
Also: `sendChat` (broadcast / DM / group, with optional attachment + mentions — **encrypts** for DM/group,
§14; screens abusive text/images before sending), `sendReaction`, `sendGroupUpdate` / `sendGroupLeave`,
profile broadcasting with **content-hash-gated avatar dedup** (§8), `heal()`/`restart()`, the periodic
metrics log, and `neighborCount`/`reachable` `StateFlow`s for the UI.

## 4. Wire protocol (`mesh/protocol/Wire.kt`)

The on-radio format is **layered binary CBOR**, structured so it can evolve *additively* — the
authoritative rules live in **`docs/WIRE_COMPAT.md`** (read it before touching any wire type) and the
"Wire format" bullet in `AGENTS.md`. Three layers, one CBOR config (`encodeDefaults = false`,
`ignoreUnknownKeys = true`):

```kotlin
// L1 — the frozen on-radio unit; the ONLY thing a relay re-encodes (ttl/hops only).
class WireEnvelope(ttl = DEFAULT_TTL /*8*/, hops = 0, relay = true,
                   @ByteString sig, @ByteString signed)   // relayed() rewrites ttl/hops; sig+signed pass byte-for-byte

// L2 — what `signed` decodes to: cleartext routing fields + an opaque per-type payload.
class RelayEnvelope(type: String, id, senderId, sentAt, recipientId?, group?, @ByteString payload)

// L3 — per-type content inside `payload` (only endpoints parse it):
data class ChatContent(body="", mentions, attachmentHash?, attachmentMime?, enc?)     // enc = EncEnvelope for a DM/group
data class ProfileContent(name, status, avatarHash?, pubKey?, deviceTag?, protoVersion?, capabilities?)
data class ReactionContent(messageId, emoji?)  ·  ReceiptContent(ackId)  ·  GroupLeaveContent(groupId)
data class BlobReqContent(hash)  ·  KeyReqContent(nodeIds)               // a groupupdate carries its GroupInfo in `group`

data class Mention(nodeId, name)                                    // canonical id + rendered @name span
data class GroupInfo(id, name?, members, createdBy, photoHash?, photoUpdatedAt?)   // self-describing roster on every group frame
class EncEnvelope(v=1, @ByteString nonce, @ByteString ct, keys)     // E2E envelope; keys = per-recipient WrappedKeys
class WrappedKey(to, @ByteString wk)                               // content key wrapped to one recipient
```

- **One signature, opaque blobs.** `sig`/`signed`/`payload` are `@ByteString ByteArray` — never nested
  `@Serializable` objects — so a relay forwards `signed`+`sig` verbatim (`WireEnvelope.relayed()` touches
  only `ttl`/`hops`) and the originator's Ed25519 signature over `signed` survives every hop. This is *not*
  kotlinx sealed polymorphism: `type` is a plain **string**, so an old build decodes (and still relays) a
  `type` it doesn't understand instead of throwing. `WireEnvelope.sig` is the *single* signature
  authenticating every flooded type (§14).
- **`id`** is the globally-unique dedup key; **`ttl`** (`DEFAULT_TTL = 8`) + **`hops`** bound propagation
  and live in the unsigned `WireEnvelope` (the only in-flight-mutable fields — the signature covers
  neither). The router relays a frame it can't verify verbatim rather than black-holing it; `blobreq`
  (with `relay = false`) is the point-to-point exception, handled hop-by-hop by `BlobExchange` (§7).
- **A `reaction`** carries a fresh random `id` per emit (not derived from message/sender/emoji) so the
  dedup `SeenSet` doesn't swallow a later retract/replace; convergence is `sentAt`'s job (last-writer-wins),
  not the id's. `emoji = null` is a retraction.
- **Addressing & encryption:** `RelayEnvelope.recipientId` (null = broadcast/group; set = the addressed DM
  recipient) and `RelayEnvelope.group` (non-null = a group message; carries the self-describing `GroupInfo`
  roster) stay **cleartext** so relays and store-and-forward carriers can route/reconcile. For DMs and
  groups the body, mentions, and attachment refs are encrypted into `ChatContent.enc` (the `EncEnvelope`);
  the cleartext `body`/`mentions` are blank, while `attachmentHash`/`attachmentMime` carry the *ciphertext*
  hash so a blind carrier can still custody the image (§7, §14). `ProfileContent.pubKey` carries the
  sender's public-key bundle. See §14.
- Avatars and attachments travel as **file payloads** (`incomingFiles`), not inside frames (too large for
  the flood); `ProfileContent.avatarHash` / `ChatContent.attachmentHash` let a peer detect changes and pull
  by content hash.

### `WireCodec` — CBOR

`WireCodec` round-trips each layer to/from bytes — `encodeWire`/`decodeWire` for the outer
`WireEnvelope`, `encodeEnvelope`/`decodeEnvelope` for the signed `RelayEnvelope`, and
`encodePayload`/`decodePayload<T>` for a per-type content — and every `decode*` returns null on malformed
input. It uses **CBOR, not JSON**, configured `encodeDefaults = false` and `ignoreUnknownKeys = true`:

```kotlin
@OptIn(ExperimentalSerializationApi::class)
object WireCodec {
    val cbor = Cbor { ignoreUnknownKeys = true; encodeDefaults = false }
    fun encodeWire(wire: WireEnvelope): ByteArray = cbor.encodeToByteArray(wire)
    fun decodeWire(bytes: ByteArray): WireEnvelope? = runCatching { cbor.decodeFromByteArray<WireEnvelope>(bytes) }.getOrNull()
    // …encodeEnvelope/decodeEnvelope (RelayEnvelope) · encodePayload/decodePayload<T> (per-type content)
}
```

CBOR encodes numbers as binary and length-prefixes strings (no quotes/braces/field-name text per
value), and `encodeDefaults = false` omits defaulted `ttl`/`hops`, empty collections, and null reserved
fields — stripping the framing bytes a JSON frame used to carry. Because each layer is an opaque
`@ByteString` blob rather than one polymorphic tree, a relay re-encodes **only** the `WireEnvelope`
wrapper (`ttl`/`hops`) and passes `signed`+`sig` byte-for-byte, so additive fields and new `type`s no
longer force a coordinated break.

> **What is (and isn't) a wire break.** Adding a nullable/defaulted field or a new `type` string is
> **additive** and safe across builds. A **break** — renaming/re-typing/repurposing a field or `type`,
> changing the `WireCodec` config or the signed input, or the `SERVICE_NAME`/`SERVICE_UUID` markers — is a
> coordinated one-time event (bump the transport marker + DB version). See `docs/WIRE_COMPAT.md`.

## 5. Conversations & direct messages (`data/message/Conversations.kt`)

A pure-Kotlin object (JVM-testable) that defines the conversation namespace:

- **`const val NEARBY = "nearby"`** — the single public broadcast room (shown as "Nearby").
- **`idFor(senderId, recipientId?, selfId)`** — `recipientId == null → NEARBY`; otherwise the DM is
  keyed by the *other* party (a DM I sent is keyed by its recipient, a DM I received by its sender).
- **`isForMe(recipientId?, selfId)`** — `recipientId == null` (broadcast, everyone) or
  `recipientId == selfId` (a DM addressed to me). A node merely relaying someone else's DM gets
  `false` and must not persist/notify/ack it.

DMs still **flood** (no routing table yet): `MeshManager.sendChat` sets `recipientId` and originates
the frame to the whole mesh exactly like broadcast; only the addressed recipient delivers and acks,
and a DM's receipt floods back (the recipient is the only one who acks). The chat list shows the
always-present Nearby room plus one row per DM thread; the contacts picker starts a new DM.

## 6. Reactions (`data/reaction/`, `data/ReactionRepository.kt`)

- **Schema** — table `reactions`, composite **PK `(messageId, reactorNodeId)`** (at most one
  reaction per person per message), columns `emoji: String?` and `updatedAt: Long`, indexed on
  `messageId`, **no FK** to `messages` (a reaction may arrive before its target message and must
  persist as an orphan that the UI later joins).
- **Last-writer-wins** — `ReactionRepository.apply(...)` upserts only if the incoming `updatedAt` is
  strictly newer than the stored clock for that (message, reactor); equal/older frames (duplicates,
  out-of-order add/retract/replace) are dropped. `emoji = null` is a **retraction tombstone** row —
  a null at a newer clock must still beat a stale "add", which a DELETE couldn't.
- **UI** — `observeReactions()` exposes a flat flow of non-tombstone rows; `ChatViewModel` groups by
  `messageId` then `emoji` into a `ReactionSummary(emoji, count, mine)` per message. `sendReaction`
  toggles (tapping your current emoji retracts; a different one replaces) and floods a
  `reaction` frame.

## 7. Attachments & content-addressed blob exchange (`data/AttachmentStore.kt`, `mesh/BlobExchange.kt`)

Images are **content-addressed** and pulled on demand, so the (large) bytes don't ride the flood.

- **`AttachmentStore`** — `ingest(uri)` returns `Ingested(hash, mime, path)`: GIFs are copied
  verbatim (animation preserved); other images are EXIF-oriented, downscaled to `MAX_DIMENSION = 1280`,
  and re-encoded JPEG q85; inputs are rejected if empty or `> 8 MiB`. The **SHA-256** of the stored
  bytes is the hash; files live at `filesDir/attachments/<hash>.<ext>` (the mime is encoded in the
  extension and recovered from it — not stored separately). `saveIncoming(hash, mime, srcPath)`
  copies a received file into place (and dedupes if already present). **Note:** `saveIncoming` trusts
  the supplied hash and does not re-verify it; only the local `ingest` path computes the hash from
  bytes.
- **`BlobExchange`** (with the `BlobStore` interface `has`/`fileFor`/`mimeFor`/`saveIncoming`,
  adapted over `AttachmentStore`) implements a hop-by-hop pull:
  - `want(hash)` — returns early if held or already in flight (`fetching` set); otherwise sends a
    `blobreq` frame (`relay = false`) to **every direct neighbor**.
  - `onRequest(hash, fromNodeId)` — if we hold the blob, send it straight back over the file channel
    (`FileKind.ATTACHMENT`); if not, record the requester in `wanters[hash]` and **recurse** by
    calling `want(hash)` (re-originating the request to our own neighbors). A per-(hash, peer) **serve
    memo** (45 s, un-stamped if the enqueue is refused) dedupes the re-ask storm around a transfer
    slower than the requester's re-ask cadence (the 60 s re-offer, or the `onNeighborAdded` re-ask
    when a new link comes up mid-stream) so a peer is never shipped a second full copy.
  - `onReceived(...)` — save the blob, clear `fetching`, fire `onObtained`
    (`MessageRepository.setAttachmentPath`, filling the local path on every message referencing the
    hash), then forward it to any recorded `wanters` (excluding the giver). So a blob walks back
    hop-by-hop over direct-neighbor file transfers.
  - `onNeighborAdded(peer)` re-asks a new neighbor for everything still missing (catches late joiners).
  - **Storm/loop control:** the `fetching` dedup set, the non-relaying request frame (`relay = false`,
    never flooded — each hop mints a fresh request id), and never bouncing a blob back to its giver.

On startup, `MeshManager.resumePendingFetches()` re-requests any attachment referenced by a stored
message whose bytes aren't present yet (`MessageDao.hashesNeedingFetch()`) — and, since DB v19, any
attachment referenced by a **carried store-and-forward frame** whose bytes are missing
(`ForwardStore.attachmentHashesNeedingFetch()`, gated by the carrier budget below), so a carrier keeps
retrying the image it is custodying for a late joiner.

**Blob custody (DB v19).** Store-and-forward carries the *frame* but historically not the image bytes,
so a custodied message re-served to a late joiner — after the sender and every recipient who pulled it
have left — referenced an image no reachable node held (a permanent "Loading photo" spinner; for E2E
DMs/groups the carrier couldn't even see the sealed hash). Now a node that *carries* a chat frame also
eager-pulls its blob (`ForwardSync`'s `onCarried` hook → `MeshManager.onCarriedFrame` →
`BlobExchange.want`) and holds it, pinned against GC by the `forward_store` reference
(`BlobDao.orphanHashes` / `BlobRepository.deleteIfUnreferenced`) for the frame's carried lifetime — so
the bytes gain the same delay-tolerance as the frame. For E2E frames the (ciphertext) hash now rides in
cleartext (§14 / `docs/WIRE_COMPAT.md`) so a carrier blind to the sealed content can see it; the carrier
holds ciphertext it can't decrypt or screen (the addressed recipient screens on decrypt). The extra
"carrier-only" footprint — blobs referenced by a carried frame but no local `messages` row — is bounded
by a pull-time byte budget (`MeshManager.CARRIER_BLOB_BUDGET_BYTES`, `BlobDao.carrierOnlyBlobBytes()`);
because these blobs are deliberately **not** folded into the custody content digest (`StoreDigest`, §3.1),
that budget is a purely local knob that can differ per node without breaking cue-plane convergence.

## 8. Mentions (`mesh/protocol/Wire.kt`, `data/message/MentionStore`, `ui/chat/MentionText.kt`)

- **Detection** — `List<Mention>.mention(nodeId)` matches on **node id**, never name; a mention of
  *me* routes to the dedicated Mentions notification path (§11).
- **Highlighting** — `MentionText.highlightMentions(body, mentions, spanStyle)` builds `@name`
  tokens, sorts them **longest-first** (so `@Jay` can't grab a prefix of `@Jaylene`), and styles each
  non-overlapping occurrence via a per-char mask. The same file provides compose-time typeahead
  (`activeMentionQuery` / `filterCandidates`); `ChatViewModel` builds the candidate list from peers
  you've received messages from in the thread.
- **Storage** — `object MentionStore` (in `MessageEntity.kt`, its own lenient `Json`) encodes
  mentions to a JSON array string for the `messages.mentions` TEXT column (default `"[]"`); a
  malformed/legacy value decodes to an empty list rather than crashing rendering.

## 9. Data layer (`data/`)

- **Room** (`KnitDatabase`, `knit.db`) — **encrypted at rest with SQLCipher**: the passphrase is a
  random key wrapped under a hardware AndroidKeyStore key by `data/crypto/DatabaseKey`. Schema bumps
  use `fallbackToDestructiveMigration(dropAllTables = true)` (local history is treated as ephemeral
  pre-launch; currently at **DB v19**). Seven tables:
  - `messages`: `id` (PK, wire id), `senderId`, `recipientId?`, `conversationId` (default `NEARBY`,
    indexed), `body` (the **decrypted plaintext**, held only inside the encrypted DB), `sentAt`,
    `received`, `mentions` (JSON, default `"[]"`), `attachmentHash?`, `attachmentMime?`,
    `attachmentKey?` (base64 AES key for an E2E attachment; null for plaintext/broadcast attachments),
    `moderation` (on-device content verdict), `pendingKey` (composed before the recipient's key was
    known; not yet flooded — §14), `kind` (normal vs. a member-left system row). DAO exposes
    `observeAll()` and the conversation-scoped `observeForConversation(id)` (both `ORDER BY sentAt ASC`),
    plus `markReceived`, `deleteByConversation`, and `hashesNeedingFetch`.
  - `peers`: `nodeId` (PK), `name`, `status`, `avatarHash?`, `pubKey?` (pinned E2E public-key bundle),
    `verified` (out-of-band key confirmation, see §14), `deviceTag?` (key-independent block-list
    continuity), `protoVersion?` / `capabilities?` (advertised, diagnostic), `updatedAt`.
  - `reactions`: composite PK `(messageId, reactorNodeId)`, `emoji?`, `updatedAt` (see §6).
  - `blobs`: `hash` (PK, SHA-256), `mime`, `bytes` — content-addressed image bytes (avatars +
    attachments); E2E-attachment bytes are stored as **ciphertext**, addressed by their ciphertext hash.
  - `blob_verdicts`: `hash` (PK), `flagged`, `score` — the on-device NSFW image verdict cached by content
    hash, so identical bytes are scanned once across send/receive (`docs/CONTENT_MODERATION.md`).
  - `groups`: `groupId` (PK), `name`, `members` (JSON roster), `createdBy`, `createdAt`, `nameUpdatedAt`,
    `left` (leave tombstone), `departed` (JSON set of members who left), `photoHash?` / `photoUpdatedAt`.
  - `forward_store`: `id` (PK), `recipientId?`, `groupId?`, `senderId`, `type`, `origin`, `signed`, `sig`,
    `sentAt`, `receivedAt`, `expiresAt`, `attachmentHash?` — the encrypted store-and-forward custody set
    (immutable signed frames re-served to newcomers; the id set the cue-plane content digest folds over —
    §3.2, `AGENTS.md`).
- **Repositories** (`MessageRepository`, `PeerRepository`, `ReactionRepository`, `GroupRepository`,
  `BlobRepository`, `ForwardRepository`) are the single source of truth; DAOs expose `Flow<List<…>>` for
  the UI and suspend writes for the manager.
- **`SettingsStore`** (Preferences DataStore, `knit_settings`): node id, display name, status,
  advertising/discovery toggles, `avatarUpdatedAt`, and a last-read watermark driving unread badges.
  **Generates and persists the node id** on first read (`getOrCreateNodeId`, transaction-guarded
  against races; §10).
- **`AttachmentStore`** (§7) — ingests images into the content-addressed, encrypted `blobs` table
  (keyed by SHA-256). For E2E DM/group attachments the bytes are encrypted first and keyed by their
  ciphertext hash (§14); broadcast-room attachments are stored plaintext-in-the-encrypted-DB.
- **`AvatarStore`**: own avatar picked → center-cropped square → 256² JPEG q90, stored in the
  encrypted `blobs` table (peer avatars likewise, pulled over the mesh). `ownAvatarHash()` is a
  **SHA-256 of the avatar bytes** (stable across devices and unaffected by a no-op rewrite —
  meaningful enough to gate re-pushes, unlike the old length+mtime fingerprint).

## 10. Identity & profiles

- **`Identity.nodeId()`** resolves (and caches) the stable **128-bit node id** — the *self-certifying*
  hash of the device's E2E public-key bundle. `NodeId.derive(seed)` = the first **16 bytes** of
  `SHA-256("knit-node-id-v2:" + seed)`, RFC4648-**base32**-encoded (lowercase, unpadded) to a 26-char
  `[a-z2-7]` string, where `seed` is `Identity.publicKeyBundle()` (the base64 bundle) — **not** a
  platform device id. Because the id is derived from the keypair, a peer can only claim an id it holds
  the private key for (forging a colliding bundle is a 128-bit second-preimage), which is what makes the
  TOFU key pin race-proof and the ~41-bit predecessor did not — this was widened as a coordinated wire
  break (`SERVICE_NAME` `.v7` / BLE `SERVICE_UUID` `0xFE31` / DB v21). base32 keeps the id
  filesystem/delimiter-safe so it stays disjoint from the `g-…` group-id namespace and rides avatar
  filenames / the verify payload. The device's long-term **E2E keypair** lives in
  `data/crypto/IdentityKeyStore` **outside** the destructively-migrated DB (so the id is stable for the
  life of that keypair; an app-data wipe that drops `identity.key` mints a new identity); its public
  bundle is exposed via `Identity.publicKeyBundle()` and advertised in `ProfileContent.pubKey` (§14).
- **`Alias`** maps any node id to a deterministic PascalCase "AdjectiveNoun" (e.g. `EnlightenedZebra`)
  via an FNV-1a hash over word lists — every device derives the same friendly name for a peer with no
  exchange. `displayNameFor(storedName, nodeId)` returns the non-blank profile name else the alias,
  so a peer is never shown as a raw id.
- **Profile broadcasting** (`MeshManager`):
  - On a **new neighbor**, push the current profile frame and (only if needed — §8) the avatar file.
  - On a **profile change** (name/status/avatar, observed via a combined settings flow, `.drop(1)` to
    skip the initial value), bump `profileVersion` to the wall clock, re-broadcast the frame, and
    send the avatar to neighbors **that don't already have this exact avatar**.
  - The profile frame id is `profile-<nodeId>-<profileVersion>`, so an unchanged profile re-sent on
    connect dedups everywhere except the new peer, while a changed profile re-floods.
  - **Avatar re-push dedup:** a `sentAvatarHashes: ConcurrentHashMap<nodeId, hash>` records the last
    avatar hash sent to each neighbor; `sendAvatarIfNeeded` skips the (large) JPEG when the hash is
    unchanged, so editing just your status text no longer re-ships your avatar to everyone. A
    departed peer's entry is cleared in `watchNeighbors`, so a genuine reconnect re-sends once.

## 11. Notifications (`notifications/`)

- **`Notifier`** interface — `createChannel`, `notify`, `notifyMention`, `setChatVisible`, `clear`,
  `onDismissed`. `NotificationChannels` registers the channels per context: `knit_msg_nearby`
  (`IMPORTANCE_DEFAULT` — the busy broadcast room), plus `knit_msg_dms`, `knit_msg_groups_v2`, and a
  dedicated `knit_msg_mentions` (all `IMPORTANCE_HIGH`); separate app channels are the foreground-service
  `knit_mesh` (`IMPORTANCE_MIN`) and `knit_alerts` (`IMPORTANCE_LOW`).
- **Room/DM notifications** use `NotificationCompat.MessagingStyle` so multiple senders show in one
  grouped notification; `NotificationHistory` keeps the last ~8 `NotifMessage`s for that context, and
  each becomes a `Person`-attributed line with the sender's cached avatar.
- **Mentions** branch to a separate, standalone `BigTextStyle` notification on the Mentions channel
  (a message that `@`-mentions you takes `notifyMention`, everything else `notifyIncoming` — they're
  mutually exclusive), so a direct ping isn't buried among other senders.
- `setChatVisible`/`clear` suppress and dismiss notifications while you're reading; a
  `NotificationActionReceiver` wired as the `deleteIntent` calls `onDismissed` so a swipe-away clears
  accumulated state without those messages reappearing.

## 12. UI layer (`ui/`)

- **Compose + Material 3, Navigation Compose.** `KnitApp` hosts a `NavHost` with routes
  `onboarding`, `chatlist`, `contacts`, `profile`, and `chat/{conversationId}` (the conversation id
  is the Nearby room, a peer node id, or a `g-…` group id; it defaults to `Conversations.NEARBY`), plus
  group details, diagnostics, blocked-users, donate, and an offline app-share flow. Start destination is
  `chatlist` if permissions are granted, else `onboarding`. `MeshService` is started by a
  `LaunchedEffect` once the user is past onboarding.
- **Screens:**
  - `OnboardingScreen` — rationale + `RequestMultiplePermissions` + battery-opt prompt.
  - `ChatListScreen` — one row per conversation (always-present Nearby room + DM threads with
    messages), each with a leading visual (room icon vs. peer `Avatar`), last-message preview,
    relative time, and an unread `Badge`; a FAB opens Contacts, an overflow menu opens Profile.
  - `ContactsScreen` — the new-DM picker; lists known peers ∪ live neighbors − self (so you can
    message a neighbor before their profile arrives), online-first; tapping opens `chat/{nodeId}`.
  - `ChatScreen` — a `LazyColumn` of bubbles with reactions, image attachments (Coil + animated
    decoder for GIF/WebP), `@`-mention highlighting and typeahead, auto-scroll, and an IME-padded
    input.
  - `ProfileScreen` — name/status fields, avatar via photo picker + Coil, node id, battery-opt row;
    key verification (safety number / QR) via `ProfileDetailsScreen`.
  - **Also:** `GroupDetailsScreen` (roster / rename / photo / leave), `DiagnosticsScreen` (transport
    health, per-radio Bluetooth/Wi-Fi-Aware status, mesh metrics + store-and-forward digest),
    `BlockedUsersScreen`, `DonateScreen`, and a `ShareTargetScreen` / invite flow that shares the APK
    (and shared content) to a nearby phone offline.
- **ViewModels** (`ChatViewModel`, `ChatListViewModel`, `ContactsViewModel`, `ProfileViewModel`) are
  Koin `koinViewModel()`s. `ChatViewModel` takes the `conversationId` as a runtime parameter and
  `combine`s the conversation's messages (pre-merged with reactions), peers, neighbor count, node id,
  and display name into UI rows (mentions, attachments, reaction summaries, sender name/avatar).
- **Theme** (`ui/theme/`): real M3 light/dark schemes from the brand coral; dynamic color off by
  default so the brand shows.
- **Editable fields use write-through local state**, not the DataStore flow directly (see §15).
- **Coil 3** is configured app-wide in `KnitApplication` with the `AnimatedImageDecoder` so message
  GIFs/WebP animate.

## 13. Background survival (`mesh/MeshService.kt`)

A typed foreground service (`connectedDevice`) hosts `MeshManager` so the mesh survives backgrounding:

- **Foreground notification** (`knit_mesh` channel, `IMPORTANCE_MIN`) with a Stop action.
- **Heartbeat:** inexact ~15-min `AlarmManager` alarm → `ACTION_HEAL` → `MeshManager.heal()`.
- **Significant motion:** a `TriggerEventListener` (re-armed after each fire) → `heal()` (moving
  likely means new peers in range).
- **Radio availability** (Wi-Fi Aware turning on/off, or another Wi-Fi mode seizing it) is handled
  inside `WifiAwareTransport` itself via `ACTION_WIFI_AWARE_STATE_CHANGED`, not here.
- All cleaned up in `onDestroy` (including `MeshManager.stop()`, which cancels pending relays). The
  message notification channels are registered up front in `KnitApplication` so they appear in system
  settings before the first notification. Battery optimization is surfaced in onboarding and the
  profile screen (`ui/Battery.kt`).

## 14. Encryption posture

**Two layers.** (1) *In transit:* each data-path link is encrypted — the Wi-Fi Aware NDP by a fixed
app-wide PSK, the Bluetooth LE L2CAP CoC by the Bluetooth link layer. (2) *End-to-end:* DM and group
messages are encrypted to their recipients so relays — which flood every message hop-by-hop — only ever
carry ciphertext. *At rest:* the Room DB is encrypted with SQLCipher (§9). The public **broadcast room is
plaintext by design** (no fixed recipient set), so a room message takes the unencrypted path (it is still
signed, and store-and-forward-carried).

**Library.** Google **Tink** (`tink-android`) — HPKE/X25519 hybrid encryption, Ed25519 signatures,
and AES-GCM. Originally chosen because the then-`minSdk 29` predated the platform `XDH`/`Ed25519` JCA
algorithms (API 33+); the mesh transport since raised `minSdk` to 33, but Tink stays — it's a
pure-runtime dep (no Gradle plugin) that can't perturb the bleeding-edge toolchain, and there's no
reason to re-implement working, audited crypto against the platform APIs.

**Identity keys** (`data/crypto/IdentityKeyStore`). Each device generates a Tink **hybrid** keypair
(wraps content keys) and an **Ed25519** keypair (signs) on first run. The private keysets are
serialized, AES-256-GCM-wrapped under a hardware AndroidKeyStore key (`KeystoreSecret`), and stored in
`filesDir/identity.key` — deliberately **outside** the destructively-migrated DB, so the identity
survives schema bumps (otherwise every migration would mint a new key and break pinning + stored
ciphertext). The public bundle (`PublicKeyBundle`, base64) is advertised in `ProfileContent.pubKey`.

**Per-message scheme** (static keys, no ratchet — see deferred work in §18), in `mesh/crypto`:

1. Generate a random content key; AES-256-GCM-encrypt the `MessageContent` (body + mentions +
   attachment refs) into `EncEnvelope.ct`.
2. Wrap the content key (Tink hybrid) to **each recipient** — the DM's recipient, or a group's members
   minus self (rosters are capped at 8) — producing one `WrappedKey` each.
3. Put the `EncEnvelope` (nonce, ciphertext, wrapped keys) in `ChatContent.enc`. The envelope is **not**
   signed here: the AEAD AAD binds the message header (`id|sender|sentAt|thread`), and the *single frame
   signature* — `WireEnvelope.sig`, raw Ed25519 over the whole `RelayEnvelope` `signed` bytes (§4) —
   covers the encrypted `ChatContent`, so a wrapped key or ciphertext can't be replayed into another
   message.
4. Originate a `chat` frame with `ChatContent.enc` set, `recipientId`/`group` in the clear (for routing),
   and cleartext `body`/`mentions`/`attachment*` blank. The sender also stores its own **plaintext** copy
   locally.

On receive, `verifyInbound` (the gate at the top of `onDeliver`) has **already** checked
`WireEnvelope.sig` byte-exact against the sender's **pinned** key — for a `profile` frame, against the
`pubKey` in its own payload, since first contact precedes any pin — so after the for-me / group-membership
gate the recipient only unwraps its `WrappedKey`, decrypts, and delivers the plaintext. `MessageCrypto.open`
now just unwraps + AES-decrypts; there is no separate envelope signature. **Decryption/verification failures
must never throw out of the inbound handler** — `onDeliver` runs *before* the router schedules the relay,
so a throw would stop the device forwarding the frame; the crypto calls return null on any failure and the
message is dropped while relaying continues (`MeshManager.decryptAndDeliver`).

**Attachments.** Image bytes are encrypted to a per-attachment key and **content-addressed by their
ciphertext hash**, so the content-addressed pull/dedup (`BlobExchange`/`BlobStore`, §7) is unchanged —
relays serve opaque ciphertext. The key rides inside the encrypted `MessageContent` and is persisted
in `MessageEntity.attachmentKey` so the UI's Coil `BlobFetcher` can decrypt on display.

**Trust & verification.** Peer keys are pinned **trust-on-first-use** into `PeerEntity.pubKey`; if a
peer's advertised key later changes, it's adopted (so comms continue) but `PeerEntity.verified` is
reset and the change is flagged. Users confirm a key out of band on the profile screen — comparing the
`SafetyNumber` (a Signal-style fingerprint derived symmetrically from both identities) or scanning the
peer's identity **QR** (`VerifyPayload` + ZXing) — which sets `verified` and shows a badge.

**Availability edge cases.** Sending requires the recipient's key: a DM composed before the recipient's
key is known is saved `pendingKey` and re-sealed + flooded once the key arrives (`flushPendingFor`); for
a group, members whose key is unknown are skipped for now (a group key-gap retransmit is deferred — §18).
An inbound frame from a not-yet-pinned sender is dropped locally but **parked** in `PendingInbound` and
recovered by a signed, point-to-point `keyreq` to neighbors (`KeyExchange`), then replayed once the
profile arrives (§3.5 / `AGENTS.md`). In practice profiles flood on connect, so keys are usually present
before the first message.

## 15. Build & tooling decisions

The project runs on intentionally bleeding-edge tooling (AGP 9.2.1, Gradle 9.4.1, Kotlin 2.4.0,
Compose BOM 2026.06, compileSdk 36.1). Consequences, all load-bearing:

| Decision | Reason |
|---|---|
| **Koin, not Hilt** | Hilt's Gradle plugin is broken on AGP 9.x (dagger#5083/#5099). Koin has no Gradle plugin / no annotation processor → immune. |
| **Built-in Kotlin overridden to 2.4.0** | AGP 9.2.1 bundles KGP 2.2.10, whose compiler can't read Kotlin-2.4 class metadata. The root `build.gradle.kts` puts KGP 2.4.0 on the buildscript classpath (`classpath(libs.kotlin.gradle.plugin)`) so built-in Kotlin compiles with 2.4.0 (Kotlin 2.4 needs AGP 9.1+). This override — **not** an AGP bump (9.3/9.4 still bundle 2.2.10) — is the lever, and it's why **Coil now tracks latest (3.5.0)**: the old 3.3.0 pin was a Kotlin-2.2-metadata workaround that no longer applies. |
| **`android.disallowKotlinSourceSets=false`** | AGP 9 built-in Kotlin otherwise rejects the `kotlin.sourceSets` DSL KSP (Room) uses. |
| **No `kotlin-android` plugin** | AGP 9's built-in Kotlin compiles Kotlin; only compose / serialization / ksp plugins are applied. |
| **KSP `2.3.9`** | KSP adopted independent (KSP2) versioning at 2.3.0 — decoupled from the compiler, supports Kotlin 2.2+ — so one version tracks Kotlin 2.4.0 (no more `<kotlin>-<ksp>` scheme). |
| **CBOR on the JSON BOM line** | `kotlinx-serialization-cbor` shares the version ref with the JSON artifact, so it's built with the same (toolchain-safe) Kotlin — no separate bump to vet. |
| **LiteRT 1.4.x / SQLCipher / Tink** | The on-device ML runtime (`com.google.ai.edge.litert`), at-rest DB encryption, and E2E crypto are Java + native `.so` with **no Kotlin metadata and no Gradle plugin**, so they can't perturb the pinned Kotlin-2.4 graph — per-dep rationale is in the version catalog. |

The wire codec uses **CBOR** (`kotlinx-serialization-cbor`) and the UI uses **Navigation Compose**. When
adding dependencies, pin versions in `gradle/libs.versions.toml`; built-in Kotlin is now 2.4.0, so deps
built with Kotlin ≤ 2.4 are readable (the old "≤ 2.3 only" ceiling is lifted), but still probe Maven
before bumping anything that could pull in a newer Kotlin stdlib.

## 16. Notable bugs fixed (lessons)

- **Self-joining coroutine deadlock (Nearby era).** In the since-retired Nearby transport, `connectTo`
  did `connectJob = scope.launch { connectJob?.join(); … }`; by the time the coroutine ran, `connectJob`
  pointed at *itself*, so it awaited itself forever and the connect never executed — devices discovered
  each other but never connected. The lesson still holds (don't await a job handle from inside that same
  job; a serialized dispatcher + a `connecting` guard fixed it); the current radios have their own
  lifecycle traps — the single-NDI wedges in `AGENTS.md` / `docs/DIGEST_PULL_REATTACH.md`.
- **One-character text fields.** Binding a Compose `TextField`'s `value` directly to a
  DataStore-backed `StateFlow` lags a keystroke behind (the write→emit round-trip is async), so
  Compose resets the field and only one character registers. Fixed by holding editable text in a
  local `MutableStateFlow` updated synchronously, persisting to DataStore in the background
  (`ProfileViewModel`).
- **Edge-to-edge keyboard overlap.** With `enableEdgeToEdge`, the chat input bar was covered by the
  IME. Fixed with `Modifier.imePadding().navigationBarsPadding()` on the input surface.

## 17. Testing strategy

- **JVM unit tests** (`app/src/test/`, no hardware):
  - Wire/protocol: `WireSerializationTest` (CBOR round-trips incl. the encrypted `chat` frame),
    `FrameSignatureTest` (the single-frame signature), `ProtocolTest` (endpoint-info advert parse),
    `SeenSetTest` (dedup/TTL/eviction), `MeshRouterTest` (dedup, relay-excludes-source + hop increment,
    TTL drop, multi-hop delivery, and the **jittered overhear-suppression** cases — using a fixed
    `jitter` + virtual time), `LinkFramingTest`/`FramedLinkTest` (the socket record codec), and
    `BlobExchangeTest`/`BlobHashTest` (content-addressed pull).
  - Transports & custody: `CompositeMeshTransportTest` (plane merge/preference), `ForwardSyncTest` +
    `ForwardRepositoryTest` (store-and-forward custody + convergent quotas), `KeyExchangeTest` (`keyreq`
    recovery), `PendingInboundTest` (park-until-key), `StoreDigestTest` + `DigestTrackerTest` (cue-plane
    anti-entropy), and the pure BLE policies (`PromotionPolicyTest`, `ScanDemandPolicyTest`,
    `ConnectBackoffPolicyTest`, `BleAdvertPayloadTest`, `BlePresenceTrackerTest`, `PowerPolicyTest`).
  - Data/identity/UI logic: `ConversationsTest`, `ReactionRepositoryTest`, `MentionTest`,
    `MentionTextTest`, `NodeIdTest` / `SelfCertifyingIdentityTest` / `DeviceTagTest`, `AliasTest`,
    `GroupNamingTest` / `GroupMembersStoreTest`, `NotificationTest`, `RelativeTimeTest`, `AvatarCropTest`.
  - E2E crypto: `MessageCryptoTest` (DM + N-member group seal/open; tampered ciphertext, wrong sender
    key, mismatched header, and non-recipient all rejected; bundle encode/decode) and `SafetyNumberTest`
    (symmetry across endpoints, determinism, change-detection, `VerifyPayload` parsing). Tink runs
    unchanged on the JVM, so these need no hardware.
  - Moderation: `LexicalTextFilterTest` (normalization/evasions/Scunthorpe), `HybridTextModeratorTest`
    (lexical short-circuit / ML fallthrough), `SentencePieceTokenizerTest` (HuggingFace-golden parity).
  - Run with `./gradlew :app:testDebugUnitTest`. Use `UnconfinedTestDispatcher` for flow-propagation
    tests and a fixed `jitter` lambda + virtual time for relay-timing tests.
- **Emulator smoke test** validates launch, Koin init, screen rendering, and service start — but an
  emulator has no Wi-Fi Aware or BLE peer radio, so it can't form a real mesh.
- **Two-to-three physical phones** validate discovery → connect → message relay over both radios,
  profile/avatar exchange, attachment pulls, store-and-forward backfill, and — with three nodes — that
  overhear suppression fires (`framesSuppressed > 0` in the metrics log) and the single-NDI cue/digest
  sync converges while messages still deliver.

## 18. Known limitations & deferred work

- DM/group messages are E2E-encrypted (§14), but they still **flood** the whole mesh (only the
  addressed recipient(s) decrypt/ack) — so relays see *who is talking to whom and how much* (the
  cleartext `recipientId`/`group` roster and sizes), just not the contents.
- **No forward secrecy:** E2E uses long-term static identity keys (no ratchet), so compromise of a
  device's identity key would expose past intercepted messages.
- **Reactions, receipts, and the broadcast room are cleartext** metadata (signed, but not encrypted).
  *(The key-request/retransmit path for a frame received before its sender's key is pinned is now
  implemented — `KeyExchange` + `PendingInbound`, §3.5.)*
- Receipts/relays add some flood overhead (reduced by overhear suppression); no per-recipient delivery
  state beyond the single ✓, and DMs have **no routing table** (they flood; store-and-forward carries
  undelivered ones).
- Destructive Room migrations (local history is treated as ephemeral; the DB is SQLCipher-encrypted at
  rest, but a schema bump drops its rows — the identity key lives outside the DB to survive this).
- **Deferred by design:** true (targeted) DM routing, forward secrecy / a ratchet, encrypting
  reactions/receipts/the broadcast room, a group key-gap retransmit, and a BLE connect-time gate on A2DP
  audio contention (see *Out of scope* in `AGENTS.md`).
