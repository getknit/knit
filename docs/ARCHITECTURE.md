# Knit — Architecture & Implementation Notes

Detailed design notes for the Knit mesh messenger. For build/contribution rules see
[`../AGENTS.md`](../AGENTS.md); for a user-facing overview see [`../README.md`](../README.md).

---

## 1. Overview

Knit is an offline, serverless proximity messenger. Each device is simultaneously a sender, a
receiver, and a relay. Messages flood across an ad-hoc mesh of nearby phones over Google Nearby
Connections (`P2P_CLUSTER`), hopping device-to-device so they can travel beyond direct radio range.

The app surfaces a **"Nearby" public broadcast room** plus **1:1 direct messages** to any peer,
with profiles (name / status / avatar), emoji **reactions**, **@-mentions**, and image
**attachments** (GIF/JPEG/PNG/WebP). DMs flood like broadcast traffic today — only the addressed
recipient delivers/acks them — so the wire format and identity layer still leave room for true
routing and end-to-end encryption without rework.

Design goals, in priority order: (1) correctness of the mesh (dedup, bounded propagation,
duplicate-suppressed flooding), (2) a clean transport abstraction so Nearby can be swapped, (3) a
Signal-like Compose UI, and (4) reliable background operation.

## 2. Module / package map

Single Gradle module `:app`, package root `app.getknit.knit`.

| Package | Responsibility |
|---|---|
| `ui/` | Compose screens + ViewModels: `onboarding/`, `chatlist/` (conversation list), `chat/` (thread + `MentionText`), `contacts/` (new-DM picker), `profile/`, shared `components/` (`Avatar`, `ConnectionStatus`), `theme/`, `util/RelativeTime`, `KnitApp` (Navigation Compose), `Permissions.kt`, `Battery.kt` |
| `mesh/` | `MeshTransport` (interface), `MeshRouter` (dedup + jittered/suppressed flood), `MeshManager` (orchestrator), `MeshService` (foreground service), `MeshMetrics` (counters), `SeenSet`, `FakeLoopTransport`, `BlobExchange` + `BlobStore` (content-addressed pull), `protocol/Wire.kt` |
| `mesh/nearby/` | `NearbyTransport` — the **only** code that touches `com.google.android.gms.*` |
| `data/` | Room (`KnitDatabase`, `message/`, `peer/`, `reaction/`), `MessageRepository`, `PeerRepository`, `ReactionRepository`, `AttachmentStore`, `AvatarStore`, `settings/SettingsStore` (DataStore) |
| `identity/` | `Identity` (stable node id), `NodeId` (derivation), `DeviceIdSource` (`AndroidDeviceIdSource`), `Alias` (deterministic display-name fallback) |
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
- **`NearbyTransport`** — production, over Nearby Connections.
- **`FakeLoopTransport`** — in-process double; `connect()` wires instances into an arbitrary
  topology so a multi-hop mesh (including relay and blob pulls) can be exercised on the JVM with no
  radios.

Future Wi-Fi Aware / BLE transports drop in as additional implementations.

### 3.2 Nearby specifics (`mesh/nearby/NearbyTransport.kt`)

- **Strategy `P2P_CLUSTER`** (many-to-many) for both advertising and discovery. Service id
  `app.getknit.knit.MESH`.
- The device's 8-char **node id is the Nearby endpoint name**, so peers recognize each other and a
  device rejects discovering itself.
- **Advertise-always, discover-in-bursts:** Nearby can't reliably advertise *and* discover
  continuously, so advertising runs constantly while discovery runs in ~12s windows separated by a
  backoff that grows with neighbor count (more peers → scan less). The backoff wait is interruptible
  by `heal()` (heartbeat / motion / Bluetooth-recovery) via a conflated channel.
- **Open mesh:** every connection is auto-accepted (`acceptConnection`); Nearby's
  `authenticationDigits` are ignored.
- **Serialized connection requests:** `requestConnection` calls run one-at-a-time on
  `Dispatchers.IO.limitedParallelism(1)` with `await()`, guarded by a `connecting` set. Nearby fails
  if multiple requests fire concurrently. (See §15 for the deadlock this replaced.)
- **Endpoint bookkeeping:** `endpointToNode` / `nodeToEndpoint` maps, `connected`, `connecting`,
  `errorCounts`, and a `blacklisted` set (after `MAX_ENDPOINT_ERRORS` failures).
- **Payloads:**
  - `BYTES` carry either a **CBOR-serialized `Frame`** (→ `inbound`) or a **file header**. The two
    are told apart by a 4-byte magic prefix `FILE_HEADER_MAGIC = "KFH1"` (`0x4B 0x46 0x48 0x31`):
    `decodeFileHeader` returns the parsed header iff the bytes start with the magic, otherwise the
    bytes are decoded as a frame via `WireCodec.decode`. A CBOR frame never begins with the magic
    (a polymorphic CBOR value starts with a map/array marker), and a guard test in
    `WireSerializationTest` pins this invariant down. The header *body* is JSON (transport-internal,
    magic-prefixed — it does not need to migrate to CBOR).
  - `FILE` payloads (avatars + attachments) are buffered by payload id on receipt; on
    `onPayloadTransferUpdate(SUCCESS)` the content `Uri` is copied to local storage and announced on
    `incomingFiles` as a `ReceivedFile` carrying the `FileMeta` (kind + key + mime) sent with it.
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
`NearbyTransport` and (via `MeshManager`) `MeshRouter`. Counters: `framesOriginated`,
`framesDelivered`, `framesRelayed`, `framesSuppressed`, `framesDeduped`, `bytesSent`; `snapshot()`
returns an immutable `Snapshot`. `MeshManager.logMetricsPeriodically()` logs a snapshot every
`60_000 ms`. The `framesSuppressed : framesRelayed` ratio quantifies how much rebroadcasting the
overhear suppression eliminated; `bytesSent` tracks the CBOR byte win.

### 3.5 Orchestration (`mesh/MeshManager.kt`)

Owns the transport + router + a `BlobExchange`, and implements `onDeliver`, which dispatches on
frame type:

- **`ChatFrame`** → if it's a DM addressed to someone else (`!Conversations.isForMe(...)`) it's only
  being relayed: don't persist/notify/ack. Otherwise persist to Room (unacked), link or start
  pulling any referenced attachment blob, fire a notification (mention vs. normal — §11), and ack
  (§5).
- **`ProfileFrame`** → upsert the peer's name/status/pubKey, **merging** so a separately-received
  avatar path isn't clobbered.
- **`ReceiptFrame`** → `markReceived(ackId)` → drives the ✓ tick on the sender's message.
- **`ReactionFrame`** → `ReactionRepository.apply(...)` (last-writer-wins, §6).
- **`BlobRequestFrame`** → `BlobExchange.onRequest(...)` (serve the blob or recurse the pull, §7).

Also: `sendChat` (with optional attachment + mentions + recipientId), `sendReaction`, profile
broadcasting with **content-hash-gated avatar dedup** (§8), `heal()`/`restart()`, the periodic
metrics log, and `neighborCount`/`neighbors` `StateFlow`s for the UI.

## 4. Wire protocol (`mesh/protocol/Wire.kt`)

A `@Serializable sealed interface Frame` carried as **binary CBOR** in a Nearby `BYTES` payload.

```kotlin
sealed interface Frame {
    val id: String; val ttl: Int; val hops: Int
    val relayable: Boolean get() = true   // computed; never serialized
}

@SerialName("chat")    ChatFrame(id, senderId, sentAt, body, recipientId?, sig?, mentions, attachmentHash?, attachmentMime?, ttl, hops)
@SerialName("profile") ProfileFrame(id, senderId, sentAt, name, status, avatarHash?, pubKey?, ttl, hops)
@SerialName("receipt") ReceiptFrame(id, senderId, ackId, ttl, hops)
@SerialName("reaction")ReactionFrame(id, senderId, messageId, emoji?, sentAt, ttl, hops)
@SerialName("blobreq") BlobRequestFrame(id, senderId, hash, ttl, hops)   // relayable = false

@Serializable data class Mention(val nodeId: String, val name: String)   // nested in ChatFrame
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
- **Reserved for the future:** `ChatFrame.recipientId` (null = broadcast; set = the addressed DM
  recipient), `ChatFrame.sig` (Ed25519 authorship signature), `ProfileFrame.pubKey` (identity key).
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
message whose bytes aren't present yet (`MessageDao.hashesNeedingFetch()`).

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

- **Room** (`KnitDatabase`, `knit.db`, `fallbackToDestructiveMigration`) — three tables:
  - `messages`: `id` (PK, wire id), `senderId`, `recipientId?`, `conversationId` (default `NEARBY`,
    indexed), `body`, `sentAt`, `received`, `mentions` (JSON, default `"[]"`), `attachmentHash?`,
    `attachmentMime?`, `attachmentPath?`. DAO exposes `observeAll()` and the conversation-scoped
    `observeForConversation(id)` (both `ORDER BY sentAt ASC`), plus `markReceived`,
    `setAttachmentPath`, and `hashesNeedingFetch`.
  - `peers`: `nodeId` (PK), `name`, `status`, `avatarPath?`, `pubKey?`, `updatedAt`.
  - `reactions`: composite PK `(messageId, reactorNodeId)`, `emoji?`, `updatedAt` (see §6).
- **Repositories** (`MessageRepository`, `PeerRepository`, `ReactionRepository`) are the single
  source of truth; DAOs expose `Flow<List<…>>` for the UI and suspend writes for the manager.
- **`SettingsStore`** (Preferences DataStore, `knit_settings`): node id, display name, status,
  advertising/discovery toggles, `avatarUpdatedAt`, and a last-read watermark driving unread badges.
  **Generates and persists the node id** on first read (`getOrCreateNodeId`, transaction-guarded
  against races; §10).
- **`AttachmentStore`** (§7) — content-addressed image blobs in `filesDir/attachments/`.
- **`AvatarStore`**: own avatar picked → center-cropped square → 256² JPEG q90 in `filesDir`; peer
  avatars cached in `cacheDir/<nodeId>.jpg`. `ownAvatarHash()` is a **SHA-256 of the avatar bytes**
  (stable across devices and unaffected by a no-op rewrite — meaningful enough to gate re-pushes,
  unlike the old length+mtime fingerprint).

## 10. Identity & profiles

- **`Identity.nodeId()`** resolves (and lazily creates) the stable **8-char node id**, caching it.
  The id is derived deterministically so clearing app data regenerates the *same* id:
  `NodeId.derive(seed)` = `SHA-256(SALT + seed)` mapped into an 8-char `[a-z0-9]` string, where the
  seed is the platform device id from `DeviceIdSource` (`AndroidDeviceIdSource` →
  `Settings.Secure.ANDROID_ID`). If no stable device id is available it falls back to a random 8-char
  id. A long-term signing/encryption keypair will be added here for E2E; the wire `pubKey` field is
  its on-ramp.
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
- **Bluetooth recovery:** a `RECEIVER_NOT_EXPORTED` receiver for `ACTION_STATE_CHANGED` → on
  `STATE_ON`, `MeshManager.restart()`.
- All cleaned up in `onDestroy` (including `MeshManager.stop()`, which cancels pending relays). The
  message notification channels are registered up front in `KnitApplication` so they appear in system
  settings before the first notification. Battery optimization is surfaced in onboarding and the
  profile screen (`ui/Battery.kt`).

## 14. Encryption posture

Today: **transport-only**. Nearby Connections encrypts each link, but messages are flooded and
re-encrypted hop-by-hop, so **relays can read plaintext** and authorship is unauthenticated. This is
an explicit MVP scope decision. (DMs also flood today — only the recipient delivers/acks — so a DM is
not yet confidential from relays either.) The path to E2E:

1. Generate an Ed25519/X25519 identity keypair in `Identity` (Keystore-backed) and advertise the
   public key via `ProfileFrame.pubKey`.
2. Sign frames (`ChatFrame.sig`) for authenticity.
3. For DMs, encrypt the body to the recipient's key (e.g. libsodium sealed box) so relays can't read
   it; `recipientId` already addresses them.
4. Optionally add safety-number verification and at-rest DB encryption (SQLCipher).

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
  - Run with `./gradlew :app:testDebugUnitTest`. Use `UnconfinedTestDispatcher` for
    flow-propagation tests and a fixed `jitter` lambda + virtual time for relay-timing tests.
- **Emulator smoke test** validates launch, Koin init, screen rendering, service start, and Nearby
  advertising/discovery — but an emulator generally can't connect to a physical phone.
- **Two-to-three physical phones** are required to validate discovery → connect → message relay,
  profile/avatar exchange, attachment pulls, and — with three nodes — that overhear suppression fires
  (`framesSuppressed > 0` in the metrics log) while messages still deliver.

## 18. Known limitations & deferred work

- Relays can read message contents; authorship unauthenticated (transport-only encryption). DMs
  flood and are not yet confidential from relays.
- Receipts/relays add some flood overhead (now reduced by overhear suppression); no per-recipient
  delivery state beyond the single ✓.
- Tied to Google Play services via Nearby (no de-Googled path yet).
- Destructive Room migrations (history is treated as ephemeral) and unencrypted at-rest storage.
- Deferred by design: Wi-Fi Aware/BLE transports, **true DM routing + end-to-end encryption +
  identity verification**, and SQLCipher. The wire format (`recipientId`/`sig`/`pubKey`) and
  `Identity` already leave room for them.
