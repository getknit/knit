# Knit — Architecture & Implementation Notes

Detailed design notes for the Knit mesh messenger. For build/contribution rules see
[`../AGENTS.md`](../AGENTS.md); for a user-facing overview see [`../README.md`](../README.md).

---

## 1. Overview

Knit is an offline, serverless proximity messenger. Each device is simultaneously a sender, a
receiver, and a relay. Messages flood across an ad-hoc mesh of nearby phones over Google Nearby
Connections (`P2P_CLUSTER`), hopping device-to-device so they can travel beyond direct radio range.

The app surfaces a **"Nearby" public broadcast room**, **1:1 direct messages**, and **multi-member
group chats**, with profiles (name / status / avatar), emoji **reactions**, **@-mentions**, and image
**attachments** (GIF/JPEG/PNG/WebP). DMs and group messages flood like broadcast traffic — only the
addressed recipient(s) deliver/ack them — but they are **end-to-end encrypted** (§14), so relays
carry ciphertext; the public Nearby room stays plaintext by design. The wire format and identity
layer still leave room for true (targeted) routing without rework.

Design goals, in priority order: (1) correctness of the mesh (dedup, bounded propagation,
duplicate-suppressed flooding), (2) a clean transport abstraction so Nearby can be swapped, (3) a
Signal-like Compose UI, and (4) reliable background operation.

## 2. Module / package map

Single Gradle module `:app`, package root `app.getknit.knit`.

| Package | Responsibility |
|---|---|
| `ui/` | Compose screens + ViewModels: `onboarding/`, `chatlist/` (conversation list), `chat/` (thread + `MentionText`), `contacts/` (new-DM picker), `profile/`, shared `components/` (`Avatar`, `ConnectionStatus`), `theme/`, `util/RelativeTime`, `KnitApp` (Navigation Compose), `Permissions.kt`, `Battery.kt` |
| `mesh/` | `MeshTransport` (interface), `MeshRouter` (dedup + jittered/suppressed flood), `MeshManager` (orchestrator), `MeshService` (foreground service), `MeshMetrics` (counters), `SeenSet`, `FakeLoopTransport`, `BlobExchange` + `BlobStore` (content-addressed pull), `protocol/Wire.kt` |
| `mesh/crypto/` | End-to-end crypto (Google Tink), pure & JVM-testable: `MessageCrypto` (per-message seal/open + signature), `PublicKeyBundle`, `MessageContent` (the encrypted payload), `AttachmentCrypto`, `SafetyNumber`, `VerifyPayload`, `Crypto.kt` (`TinkInit`/`AesGcm`) |
| `mesh/wifiaware/` | `WifiAwareTransport` + `AwareFraming` — the **only** code that touches `android.net.wifi.aware.*` |
| `data/` | Room (`KnitDatabase`, `message/`, `peer/`, `reaction/`, `blob/`, `group/`), repositories, `AttachmentStore`, `AvatarStore`, `settings/SettingsStore` (DataStore), `crypto/` (`DatabaseKey`, `IdentityKeyStore`, `KeystoreSecret` — AndroidKeyStore-wrapped secrets) |
| `identity/` | `Identity` (stable node id **+ E2E public-key bundle**), `NodeId` (derivation), `DeviceIdSource` (`AndroidDeviceIdSource`), `Alias` (deterministic display-name fallback) |
| `notifications/` | `Notifier` (interface) + `MessageNotifier`, `NotificationHistory`, `NotificationDismissReceiver` |
| `di/` | Koin modules: `appModule`, `meshModule`, `uiModule` |

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
    val neighbors: StateFlow<Set<Peer>>      // currently-connected peers
    val inbound: Flow<InboundFrame>          // frames received (pre dedup/relay)
    val incomingFiles: Flow<ReceivedFile>    // completed file transfers (avatars + attachments)
    fun start(); fun stop(); fun heal()      // heal() = rescan/reconnect now
    suspend fun send(frame: Frame, to: Peer? = null)         // null = broadcast to all neighbors
    suspend fun sendFile(file: File, to: Peer, meta: FileMeta)
}
```

Supporting types, all declared in the same file:

```kotlin
data class Peer(val nodeId: String)
data class InboundFrame(val frame: Frame, val fromNodeId: String)
enum class FileKind { AVATAR, ATTACHMENT }
data class FileMeta(val kind: FileKind, val key: String, val mime: String)   // key = nodeId | content hash
data class ReceivedFile(val fromNodeId: String, val path: String, val kind: FileKind, val key: String, val mime: String)
```

`sendFile` carries a `FileMeta` so the receiver can route a completed transfer: an **AVATAR**
(`key` = sender node id) updates that peer's avatar; an **ATTACHMENT** (`key` = content hash) feeds
the blob-exchange pull (§7).

Two implementations:
- **`WifiAwareTransport`** — production, over Wi-Fi Aware (NAN); see §3.2.
- **`FakeLoopTransport`** — in-process double; `connect()` wires instances into an arbitrary
  topology so a multi-hop mesh (including relay and blob pulls) can be exercised on the JVM with no
  radios.

A future sibling transport (e.g. a BLE fallback for non-NAN devices — deliberately not built) would
drop in as an additional implementation behind the same interface.

### 3.2 Wi-Fi Aware specifics (`mesh/wifiaware/WifiAwareTransport.kt`)

- **Service `SERVICE_NAME = "app.getknit.knit.MESH.v2"`.** Each node `attach()`es once, then both
  **publishes and subscribes** the service — the symmetric analogue of Nearby's `P2P_CLUSTER`. The
  device's 8-char **node id + version + caps** (`Protocol.advertise`) ride in the publish
  `serviceSpecificInfo`, so a subscriber learns a peer's identity on discovery with zero round-trips
  and rejects discovering itself.
- **Two channels.** The discovery channel (`serviceSpecificInfo`, ~255 B) carries only the advert. The
  reliable, high-bandwidth path is a **NAN data path (NDP): a TCP socket over link-local IPv6**, which
  carries every frame and file (and the connect handshake).
- **Persistent accept-any responder + loop-driven clients.** Each node runs **one** responder built from
  its *publish* session with **no peer handle** (`WifiAwareNetworkSpecifier.Builder(publishSession)
  .setPort(port)`) + one `ServerSocket` that accepts a data path from **any** initiator — because
  per-peer responders don't compose (a device already serving one peer can't stand up a second, which
  stranded a third phone joining a pair). A deterministic tie-break gives one link per pair: the
  **larger** nodeId is the client (subscriber-side initiator via
  `Builder(subscribeSession, peerHandle)`, reads the responder's IPv6+port from `WifiAwareNetworkInfo`,
  connects the socket), the smaller is the server. Since accept-any doesn't reveal who connected, the
  **initiator sends its advert as the first `AwareFraming.Type.HELLO` record**; the responder reads it to
  identify the peer. `discoveryLoop` drives client initiations to *all* eligible discovered peers
  (serialized, one at a time), so a connected device still links to more — a real multi-node mesh. A fixed
  app-wide PSK encrypts the link; real authentication is the per-frame signature + E2E layer above.
- **Neighbor = live socket.** A peer enters `neighbors` once its socket is up (triggering the
  store-and-forward / key-exchange "new neighbor" hooks upstream) and leaves on socket close /
  network `onLost`. Client links own a per-peer `NetworkCallback` (unregistered on teardown); server
  links share the responder (its callback is left alone when one client leaves).
- **Instant Communication Mode** (API 33, when supported) speeds discovery + data-path bring-up for the
  brief-encounter (festival) case.
- **Self-healing without disturbing live links.** `onServiceDiscovered` fires only *once* per subscribe
  session per peer, so re-firing discovery means restarting subscribe — but closing a session **tears
  down the NDPs anchored to it** on real chipsets. So `rearmSubscribe()` restarts **only subscribe**
  (publish + responder stay up) and **only while isolated** (`peers.isEmpty()`), woken by a `healSignal`
  channel (peer discovered, link up/down, `heal()`, screen-on). A periodic `AwareFraming.Type.KEEPALIVE`
  record on each socket keeps an idle NDP from timing out. A time-boxed client `requestNetwork`
  (`HANDSHAKE_TIMEOUT_MS`) + a per-peer backoff prevent leaked requests exhausting NAN's scarce data
  interfaces.
- **Bounded.** Total links capped at `MAX_LINKS`; client handshakes serialized one-at-a-time (NAN has
  only 1–2 data interfaces). `ACTION_WIFI_AWARE_STATE_CHANGED` drives `health` (→ `Degraded` when
  Wi-Fi is off or another Wi-Fi mode seizes the radio) and re-attach on recovery. Hardware without
  `FEATURE_WIFI_AWARE` has no fallback — the transport stays `Degraded` and the UI gates with an
  "unsupported" state.
- **Socket framing (`mesh/wifiaware/AwareFraming.kt`).** A raw socket is a byte stream, so records are
  length-prefixed `[type:1][len:4 big-endian][payload]`: a `FRAME` carries one CBOR `WireEnvelope`
  (→ `inbound`); a file streams as `FILE_HEADER` (JSON `FileHeaderWire`: kind + key + mime) →
  `FILE_CHUNK`s → `FILE_END`, saved to the cache and announced on `incomingFiles` as a `ReceivedFile`.
  The writer serializes files and interleaves live frames *between* chunks so an 8 MiB blob never
  stalls traffic; a per-file receive ceiling matches the 8 MiB send cap.
- **Byte metrics:** `send` records `metrics.onBytesSent(bytes.size * targets.size)` — one frame
  broadcast to N neighbors counts as `frameSize × N`, so the CBOR win is measurable in the field.
  (`sendFile` is not counted.)

### 3.3 Routing, dedup & duplicate-suppressed flooding (`mesh/MeshRouter.kt`, `mesh/SeenSet.kt`)

`MeshRouter` is transport-agnostic and unit-tested (`FakeLoopTransport` / a fake). It does **not**
blind-flood. On each inbound frame `handleInbound`:

1. **`SeenSet.add(frame.id)`** — if this is the **first sighting**, deliver locally via the
   `onDeliver` callback (persist / ack / react / cache), then **schedule** a relay.
2. If it's a **duplicate** (already seen), it is never re-delivered or re-relayed — but it *is*
   evidence the frame is already propagating, so it's counted toward **overhear suppression**
   (`metrics.onDeduped()` + `countOverheard`).

**Jittered relay with overhear suppression** replaces immediate rebroadcast (which storms a dense
cluster with O(neighbors²) redundant sends):

- `scheduleRelay` keeps the synchronous early-outs — a **non-`relayable`** frame (e.g.
  `BlobRequestFrame`) and a **TTL-exhausted** frame (`hops >= ttl`) are dropped immediately, exactly
  as before. Otherwise it records a `PendingRelay { relayed = frame.incrementHop(), heardFrom, count, job }`
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
`framesDelivered`, `framesRelayed`, `framesSuppressed`, `framesDeduped`, `bytesSent`; `snapshot()`
returns an immutable `Snapshot`. `MeshManager.logMetricsPeriodically()` logs a snapshot every
`60_000 ms`. The `framesSuppressed : framesRelayed` ratio quantifies how much rebroadcasting the
overhear suppression eliminated; `bytesSent` tracks the CBOR byte win.

### 3.5 Orchestration (`mesh/MeshManager.kt`)

Owns the transport + router + a `BlobExchange`, and implements `onDeliver`, which dispatches on
frame type:

- **`ChatFrame`** → blocked-sender drop. A **group** message (`group != null`) reconciles the
  self-describing roster and delivers if we're a member; a **DM** addressed to someone else
  (`!Conversations.isForMe(...)`) is only relayed (don't persist/notify/ack). For a DM/group meant for
  us, the encrypted envelope is **verified + decrypted** (`decryptAndDeliver`; dropped on any failure,
  and never throwing — §14) before persisting to Room (unacked), pulling any referenced attachment
  blob, notifying (mention vs. normal — §11), and acking (§5).
- **`GroupUpdateFrame`** → reconcile the stored group from the carried roster (e.g. a rename), no
  message persisted.
- **`ProfileFrame`** → upsert the peer's name/status/avatar and **pin its E2E key** trust-on-first-use
  into `PeerEntity.pubKey` (a changed key resets `verified` — §14), merging so an unfetched avatar
  isn't clobbered.
- **`ReceiptFrame`** → `markReceived(ackId)` → drives the ✓ tick on the sender's message.
- **`ReactionFrame`** → `ReactionRepository.apply(...)` (last-writer-wins, §6).
- **`BlobRequestFrame`** → `BlobExchange.onRequest(...)` (serve the blob or recurse the pull, §7).

Also: `sendChat` (broadcast / DM / group, with optional attachment + mentions — **encrypts** for
DM/group, §14), `sendReaction`, `sendGroupUpdate`, profile broadcasting with **content-hash-gated
avatar dedup** (§8), `heal()`/`restart()`, the periodic metrics log, and `neighborCount`/`neighbors`
`StateFlow`s for the UI.

## 4. Wire protocol (`mesh/protocol/Wire.kt`)

> **Note (layered wire-format break):** the single `@Serializable sealed interface Frame` described
> below has been replaced by a **three-layer envelope** — a frozen `WireEnvelope` (ttl/hops/sig/opaque
> `signed`) wrapping a `RelayEnvelope` (routing fields + opaque `payload`) wrapping per-type content
> (`ChatContent`, `ProfileContent`, …). Relays forward the `signed` bytes byte-for-byte, so additive
> fields and new `type`s no longer require a break. The catalog below still describes the *logical*
> fields (now split: routing fields → `RelayEnvelope`, content → the per-type payloads), and the per-
> frame signature is now one `WireEnvelope.sig` over `signed`. See **`docs/WIRE_COMPAT.md`** and the
> "Wire format" bullet in `AGENTS.md` for the current model and the rules that keep changes additive.

Historically (pre-layering) a `@Serializable sealed interface Frame` carried as **binary CBOR** in a
Nearby `BYTES` payload:

```kotlin
sealed interface Frame {
    val id: String; val ttl: Int; val hops: Int
    val relayable: Boolean get() = true   // computed; never serialized
}

@SerialName("chat")        ChatFrame(id, senderId, sentAt, body, recipientId?, sig?, mentions, attachmentHash?, attachmentMime?, group?, enc?, ttl, hops)
@SerialName("groupupdate") GroupUpdateFrame(id, senderId, sentAt, group, ttl, hops)   // floods a rename/creation
@SerialName("profile")     ProfileFrame(id, senderId, sentAt, name, status, avatarHash?, pubKey?, ttl, hops)
@SerialName("receipt")     ReceiptFrame(id, senderId, ackId, ttl, hops)
@SerialName("reaction")    ReactionFrame(id, senderId, messageId, emoji?, sentAt, ttl, hops)
@SerialName("blobreq")     BlobRequestFrame(id, senderId, hash, ttl, hops)   // relayable = false

@Serializable data class Mention(val nodeId, val name)                       // nested in ChatFrame
@Serializable data class GroupInfo(id, name?, members, createdBy)            // self-describing group roster
@Serializable data class EncEnvelope(v, nonce, ct, keys)                     // E2E envelope on an encrypted ChatFrame
@Serializable data class WrappedKey(to, wk)                                  // per-recipient wrapped content key
```

- `id` is the globally-unique dedup key; `ttl` (default `DEFAULT_TTL = 8`) + `hops` bound
  propagation; `Frame.incrementHop()` returns a copy with `hops + 1`.
- **`relayable`** is a computed getter (so it never lands on the wire). It is `true` for the flooded
  room/DM traffic (chat / profile / receipt / reaction) and `false` for `BlobRequestFrame`, whose
  propagation is point-to-point and handled hop-by-hop by `BlobExchange` (§7).
- **`ReactionFrame`** carries a fresh random `id` per emit (not derived from message/sender/emoji) so
  the dedup `SeenSet` doesn't swallow a later retract/replace; convergence is `sentAt`'s job
  (last-writer-wins), not the frame id's. `emoji = null` is a retraction.
- **`Mention`** pairs a canonical `nodeId` (for reliable "did this mention me" detection — display
  names aren't unique) with the exact rendered `name` string (so the receiver can locate the
  `@name` span for highlighting, even when the name contains spaces).
- **Addressing & encryption:** `ChatFrame.recipientId` (null = broadcast/group; set = the addressed
  DM recipient) and `ChatFrame.group` (non-null = a group message; carries the self-describing
  `GroupInfo` roster) stay **cleartext** so relays can route/reconcile. For DMs and groups the body,
  mentions, and attachment refs are encrypted into **`ChatFrame.enc`** (the `EncEnvelope`) and
  authenticated by **`ChatFrame.sig`** (Ed25519); `body` is empty and the cleartext attachment/mention
  fields are blank on the wire. `ProfileFrame.pubKey` carries the sender's public-key bundle. See §14.
- Avatars and attachments travel as **file payloads**, not inside frames (too large for BYTES and the
  flood); `ProfileFrame.avatarHash` / `ChatFrame.attachmentHash` let a peer detect changes and pull
  by content hash.

### `WireCodec` — CBOR

`WireCodec.encode/decode` round-trips frames to/from bytes; `decode` returns null on malformed input.
It uses **CBOR, not JSON**, configured `encodeDefaults = false` and `ignoreUnknownKeys = true`:

```kotlin
@OptIn(ExperimentalSerializationApi::class)
object WireCodec {
    private val cbor = Cbor { ignoreUnknownKeys = true; encodeDefaults = false }
    fun encode(frame: Frame): ByteArray = cbor.encodeToByteArray<Frame>(frame)
    fun decode(bytes: ByteArray): Frame? = runCatching { cbor.decodeFromByteArray<Frame>(bytes) }.getOrNull()
}
```

CBOR encodes numbers as binary and length-prefixes strings (no quotes/braces/field-name text per
value), and `encodeDefaults = false` omits defaulted `ttl`/`hops`, empty `mentions`, and the null
reserved fields — together stripping the ~150 bytes of `"recipientId":null,…,"ttl":8,"hops":0`
framing a JSON chat frame used to carry. Polymorphism rides CBOR's own structure (the `@SerialName`
discriminators are preserved; the JSON-only `classDiscriminator` option no longer applies). The
`<Frame>` type argument on `encodeToByteArray`/`decodeFromByteArray` is **load-bearing** — it
selects polymorphic encoding.

> **This is a deliberate wire-format break.** All nodes must run a CBOR-speaking build to interoperate
> (there is no version negotiation yet — acceptable for a single-app mesh today).

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
  `ReactionFrame`.

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
    `BlobRequestFrame` (relayable = false) to **every direct neighbor**.
  - `onRequest(hash, fromNodeId)` — if we hold the blob, send it straight back over the file channel
    (`FileKind.ATTACHMENT`); if not, record the requester in `wanters[hash]` and **recurse** by
    calling `want(hash)` (re-originating the request to our own neighbors).
  - `onReceived(...)` — save the blob, clear `fetching`, fire `onObtained`
    (`MessageRepository.setAttachmentPath`, filling the local path on every message referencing the
    hash), then forward it to any recorded `wanters` (excluding the giver). So a blob walks back
    hop-by-hop over direct-neighbor file transfers.
  - `onNeighborAdded(peer)` re-asks a new neighbor for everything still missing (catches late joiners).
  - **Storm/loop control:** the `fetching` dedup set, the non-`relayable` request frame (never
    flooded — each hop mints a fresh request id), and never bouncing a blob back to its giver.

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
  use `fallbackToDestructiveMigration` (local history is treated as ephemeral pre-launch). Five tables:
  - `messages`: `id` (PK, wire id), `senderId`, `recipientId?`, `conversationId` (default `NEARBY`,
    indexed), `body` (the **decrypted plaintext**, held only inside the encrypted DB), `sentAt`,
    `received`, `mentions` (JSON, default `"[]"`), `attachmentHash?`, `attachmentMime?`,
    `attachmentKey?` (base64 AES key for an E2E attachment; null for plaintext/broadcast attachments).
    DAO exposes `observeAll()` and the conversation-scoped `observeForConversation(id)` (both
    `ORDER BY sentAt ASC`), plus `markReceived`, `deleteByConversation`, and `hashesNeedingFetch`.
  - `peers`: `nodeId` (PK), `name`, `status`, `avatarHash?`, `pubKey?` (pinned E2E public-key bundle),
    `verified` (out-of-band key confirmation, see §14), `updatedAt`.
  - `reactions`: composite PK `(messageId, reactorNodeId)`, `emoji?`, `updatedAt` (see §6).
  - `blobs`: `hash` (PK, SHA-256), `mime`, `bytes` — content-addressed image bytes (avatars +
    attachments); E2E-attachment bytes are stored as **ciphertext**, addressed by their ciphertext hash.
  - `groups`: `groupId` (PK), `name`, `members` (JSON roster), `createdBy`, `createdAt`,
    `nameUpdatedAt`, `left` (leave tombstone).
- **Repositories** (`MessageRepository`, `PeerRepository`, `ReactionRepository`) are the single
  source of truth; DAOs expose `Flow<List<…>>` for the UI and suspend writes for the manager.
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

- **`Identity.nodeId()`** resolves (and lazily creates) the stable **8-char node id**, caching it.
  The id is derived deterministically so clearing app data regenerates the *same* id:
  `NodeId.derive(seed)` = `SHA-256(SALT + seed)` mapped into an 8-char `[a-z0-9]` string, where the
  seed is the platform device id from `DeviceIdSource` (`AndroidDeviceIdSource` →
  `Settings.Secure.ANDROID_ID`). If no stable device id is available it falls back to a random 8-char
  id. The device's long-term **E2E keypair** lives in `data/crypto/IdentityKeyStore`; its public
  bundle is exposed via `Identity.publicKeyBundle()` and advertised in `ProfileFrame.pubKey` (§14).
- **`Alias`** maps any node id to a deterministic PascalCase "AdjectiveNoun" (e.g. `EnlightenedZebra`)
  via an FNV-1a hash over word lists — every device derives the same friendly name for a peer with no
  exchange. `displayNameFor(storedName, nodeId)` returns the non-blank profile name else the alias,
  so a peer is never shown as a raw id.
- **Profile broadcasting** (`MeshManager`):
  - On a **new neighbor**, push the current `ProfileFrame` and (only if needed — §8) the avatar file.
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
  `onDismissed`. The concrete `MessageNotifier` registers **two channels**, both `IMPORTANCE_HIGH`:
  `knit_messages` (normal room/DM messages) and a dedicated `knit_mentions` (the message channel
  `knit_mesh` for the foreground service is separate, `IMPORTANCE_MIN`).
- **Room/DM notifications** use `NotificationCompat.MessagingStyle` so multiple senders show in one
  grouped notification; `NotificationHistory` keeps the last ~8 `NotifMessage`s for that context, and
  each becomes a `Person`-attributed line with the sender's cached avatar.
- **Mentions** branch to a separate, standalone `BigTextStyle` notification on the Mentions channel
  (a message that `@`-mentions you takes `notifyMention`, everything else `notifyIncoming` — they're
  mutually exclusive), so a direct ping isn't buried among other senders.
- `setChatVisible`/`clear` suppress and dismiss notifications while you're reading; a
  `NotificationDismissReceiver` wired as the `deleteIntent` calls `onDismissed` so a swipe-away clears
  accumulated state without those messages reappearing.

## 12. UI layer (`ui/`)

- **Compose + Material 3, Navigation Compose.** `KnitApp` hosts a `NavHost` with routes
  `onboarding`, `chatlist`, `contacts`, `profile`, and `chat/{conversationId}` (the conversation id
  is the Nearby room or a peer node id; it defaults to `Conversations.NEARBY`). Start destination is
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
  - `ProfileScreen` — name/status fields, avatar via photo picker + Coil, node id, battery-opt row.
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

**Two layers.** (1) *In transit:* each Wi-Fi Aware data path (NDP) is a PSK-encrypted link. (2)
*End-to-end:* DM and group messages are encrypted to their recipients so relays — which flood every
message hop-by-hop — only ever carry ciphertext. *At rest:* the Room DB is encrypted with SQLCipher
(§9). The public **broadcast room is plaintext by design** (no fixed recipient set), so a room message
takes the unencrypted path (it is still signed, and now store-and-forward-carried — §store-and-forward).

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
ciphertext). The public bundle (`PublicKeyBundle`, base64) is advertised in `ProfileFrame.pubKey`.

**Per-message scheme** (static keys, no ratchet — see deferred work in §18), in `mesh/crypto`:

1. Generate a random content key; AES-256-GCM-encrypt the `MessageContent` (body + mentions +
   attachment refs) into `EncEnvelope.ct`.
2. Wrap the content key (Tink hybrid) to **each recipient** — the DM's recipient, or a group's members
   minus self (rosters are capped at 8) — producing one `WrappedKey` each.
3. Ed25519-**sign** a canonical blob over `(id, senderId, sentAt, thread, nonce, ct, wrapped keys)`
   into `ChatFrame.sig` — binding the message to its identity/thread so a key or ciphertext can't be
   replayed into another message.
4. Send a `ChatFrame` with `enc`/`sig` set, `recipientId`/`group` in the clear (for routing), and
   `body`/`mentions`/`attachment*` blank. The sender also stores its own **plaintext** copy locally.

On receive, after the for-me / group-membership gate, the recipient verifies `sig` against the
sender's **pinned** key, unwraps its `WrappedKey`, decrypts, and delivers the plaintext. **Decryption/
verification failures must never throw out of the inbound handler** — `MeshManager.onDeliver` runs
*before* the router schedules the relay, so a throw would stop the device forwarding the frame; the
crypto calls return null on any failure and the message is dropped while relaying continues
(`MeshManager.decryptAndDeliver`).

**Attachments.** Image bytes are encrypted to a per-attachment key and **content-addressed by their
ciphertext hash**, so the content-addressed pull/dedup (`BlobExchange`/`BlobStore`, §7) is unchanged —
relays serve opaque ciphertext. The key rides inside the encrypted `MessageContent` and is persisted
in `MessageEntity.attachmentKey` so the UI's Coil `BlobFetcher` can decrypt on display.

**Trust & verification.** Peer keys are pinned **trust-on-first-use** into `PeerEntity.pubKey`; if a
peer's advertised key later changes, it's adopted (so comms continue) but `PeerEntity.verified` is
reset and the change is flagged. Users confirm a key out of band on the profile screen — comparing the
`SafetyNumber` (a Signal-style fingerprint derived symmetrically from both identities) or scanning the
peer's identity **QR** (`VerifyPayload` + ZXing) — which sets `verified` and shows a badge.

**Availability edge cases.** Sending requires the recipient's key: if no recipient key is known yet
the message is stored locally but not flooded (logged); for a group, members whose key is unknown are
simply skipped (they can't read that message). An inbound encrypted frame from a sender whose key we
haven't pinned is dropped (no key-request/retransmit protocol yet — §18). In practice profiles flood
on connect, so keys are usually present before the first message.

## 15. Build & tooling decisions

The project runs on intentionally bleeding-edge tooling (AGP 9.2.1, Gradle 9.4.1, Kotlin 2.2.10,
Compose BOM 2026.02, compileSdk 36.1). Consequences, all load-bearing:

| Decision | Reason |
|---|---|
| **Koin, not Hilt** | Hilt's Gradle plugin is broken on AGP 9.x (dagger#5083/#5099). Koin has no Gradle plugin / no annotation processor → immune. |
| **Coil pinned 3.3.0** | Coil 3.4.0+ is built with Kotlin 2.4.0; its class metadata is unreadable by the AGP-bundled Kotlin 2.2 compiler. Any dep built with Kotlin > 2.3 breaks compilation here. |
| **`android.disallowKotlinSourceSets=false`** | AGP 9 built-in Kotlin otherwise rejects the `kotlin.sourceSets` DSL KSP (Room) uses. |
| **No `kotlin-android` plugin** | AGP 9's built-in Kotlin compiles Kotlin; only compose / serialization / ksp plugins are applied. |
| **KSP `2.2.10-2.0.2`** | Must match the Kotlin version. |
| **CBOR on the JSON BOM line** | `kotlinx-serialization-cbor:1.8.1` shares the version ref with the JSON artifact, so it's built with the same (toolchain-safe) Kotlin — no separate bump to vet. |

The wire codec uses **CBOR** (`kotlinx-serialization-cbor`) and the UI uses **Navigation Compose**.
When adding dependencies, pin versions in `gradle/libs.versions.toml` and check (e.g. via the
artifact's Gradle module metadata) that they aren't built with Kotlin > 2.3.

## 16. Notable bugs fixed (lessons)

- **Connection deadlock.** `connectTo` did `connectJob = scope.launch { connectJob?.join(); … }`.
  By the time the coroutine ran, `connectJob` pointed at *itself*, so it awaited itself forever and
  `requestConnection` never executed — devices discovered each other but never connected. Fixed with
  a serialized dispatcher + `await()` + a `connecting` guard.
- **One-character text fields.** Binding a Compose `TextField`'s `value` directly to a
  DataStore-backed `StateFlow` lags a keystroke behind (the write→emit round-trip is async), so
  Compose resets the field and only one character registers. Fixed by holding editable text in a
  local `MutableStateFlow` updated synchronously, persisting to DataStore in the background
  (`ProfileViewModel`).
- **Edge-to-edge keyboard overlap.** With `enableEdgeToEdge`, the chat input bar was covered by the
  IME. Fixed with `Modifier.imePadding().navigationBarsPadding()` on the input surface.

## 17. Testing strategy

- **JVM unit tests** (`app/src/test/`, no hardware):
  - Mesh/protocol: `WireSerializationTest` (CBOR round-trips for every frame type + the
    file-header-magic-collision guard), `SeenSetTest` (dedup/TTL/eviction), `MeshRouterTest`
    (dedup, relay-excludes-source + hop increment, TTL drop, multi-hop delivery, and the **jittered
    overhear-suppression** cases — duplicate-within-window suppresses, single-sighting relays — using
    a fixed `jitter` and virtual time), `BlobExchangeTest` (content-addressed pull).
  - Data/identity/UI logic: `ConversationsTest`, `ReactionRepositoryTest`, `MentionTest`,
    `MentionTextTest`, `NodeIdTest`, `AliasTest`, `NotificationTest`, `RelativeTimeTest`.
  - E2E crypto: `MessageCryptoTest` (DM + N-member group seal/open round-trips; tampered ciphertext,
    wrong sender key, mismatched header, missing signature, and non-recipient all rejected; bundle
    encode/decode) and `SafetyNumberTest` (symmetry across endpoints, determinism, change-detection,
    `VerifyPayload` parsing). `WireSerializationTest` also covers the encrypted-`ChatFrame` round-trip.
    Tink runs unchanged on the JVM, so these need no hardware.
  - Run with `./gradlew :app:testDebugUnitTest`. Use `UnconfinedTestDispatcher` for
    flow-propagation tests and a fixed `jitter` lambda + virtual time for relay-timing tests.
- **Emulator smoke test** validates launch, Koin init, screen rendering, service start, and Nearby
  advertising/discovery — but an emulator generally can't connect to a physical phone.
- **Two-to-three physical phones** are required to validate discovery → connect → message relay,
  profile/avatar exchange, attachment pulls, and — with three nodes — that overhear suppression fires
  (`framesSuppressed > 0` in the metrics log) while messages still deliver.

## 18. Known limitations & deferred work

- DM/group messages are E2E-encrypted (§14), but they still **flood** the whole mesh (only the
  addressed recipient(s) decrypt/ack) — so relays see *who is talking to whom and how much* (the
  cleartext `recipientId`/`group` roster and sizes), just not the contents.
- **No forward secrecy:** E2E uses long-term static identity keys (no ratchet), so compromise of a
  device's identity key would expose past intercepted messages.
- **Reactions, receipts, and the broadcast room are cleartext** (metadata); there is **no
  key-request/retransmit** path for a message received before the sender's key is pinned (it's dropped).
- Receipts/relays add some flood overhead (now reduced by overhear suppression); no per-recipient
  delivery state beyond the single ✓.
- Tied to Google Play services via Nearby (no de-Googled path yet).
- Destructive Room migrations (local history is treated as ephemeral; the DB is SQLCipher-encrypted at
  rest, but a schema bump drops its rows — the identity key lives outside the DB to survive this).
- Deferred by design: Wi-Fi Aware/BLE transports, **true (targeted) DM routing**, **forward
  secrecy / a ratchet**, and encrypting reactions/receipts/the broadcast room.
