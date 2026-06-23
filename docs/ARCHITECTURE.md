# Knit — Architecture & Implementation Notes

Detailed design notes for the Knit mesh messenger. For build/contribution rules see
[`../AGENTS.md`](../AGENTS.md); for a user-facing overview see [`../README.md`](../README.md).

---

## 1. Overview

Knit is an offline, serverless proximity messenger. Each device is simultaneously a sender, a
receiver, and a relay. Messages flood across an ad-hoc mesh of nearby phones over Google Nearby
Connections (`P2P_CLUSTER`), hopping device-to-device so they can travel beyond direct radio range.
The current MVP is a single public broadcast room with profiles; the codebase is structured so 1:1
DMs, alternate transports, and end-to-end encryption can be added without rework.

Design goals, in priority order: (1) correctness of the mesh (dedup, bounded propagation),
(2) a clean transport abstraction so Nearby can be swapped, (3) a Signal-like Compose UI, and
(4) reliable background operation.

## 2. Module / package map

Single Gradle module `:app`, package root `app.getknit.knit`.

| Package | Responsibility |
|---|---|
| `ui/` | Compose screens (`onboarding/`, `chat/`, `profile/`), `KnitApp` (nav), theme, `Permissions.kt`, `Battery.kt` |
| `mesh/` | `MeshTransport` (interface), `MeshRouter` (dedup/TTL/relay), `MeshManager` (orchestrator), `MeshService` (foreground service), `SeenSet`, `FakeLoopTransport`, `protocol/Wire.kt` |
| `mesh/nearby/` | `NearbyTransport` — the **only** code that touches `com.google.android.gms.*` |
| `data/` | Room (`KnitDatabase`, `message/`, `peer/`), `MessageRepository`, `PeerRepository`, `AvatarStore`, `settings/SettingsStore` (DataStore) |
| `identity/` | `Identity` — stable node id (and the future keypair) |
| `di/` | Koin modules: `appModule`, `meshModule`, `uiModule` |

### Data flow

```
 Compose UI ──(send)──▶ MeshManager ──▶ MeshRouter.originate ──▶ MeshTransport.send ──▶ radios
     ▲                       │                                                            │
     │                       ▼ onDeliver (persist, ack, cache)                            │
 StateFlow ◀── Repositories ◀┘                                                            │
     ▲                                                                                    │
     └────────── MeshRouter ◀── (dedup + relay) ◀── MeshTransport.inbound ◀───────────────┘
```

`MeshManager` is a Koin singleton shared by the foreground service and the UI, so both observe and
drive the same mesh instance.

## 3. Mesh layer

### 3.1 Transport abstraction (`mesh/MeshTransport.kt`)

```kotlin
interface MeshTransport {
    val neighbors: StateFlow<Set<Peer>>      // currently-connected peers
    val inbound: Flow<InboundFrame>          // frames received (pre dedup/relay)
    val incomingFiles: Flow<ReceivedFile>    // completed file transfers (avatars)
    fun start(); fun stop(); fun heal()      // heal() = rescan/reconnect now
    suspend fun send(frame: Frame, to: Peer? = null)  // null = broadcast to all neighbors
    suspend fun sendFile(file: File, to: Peer)
}
```

Two implementations:
- **`NearbyTransport`** — production, over Nearby Connections.
- **`FakeLoopTransport`** — in-process double; `connect()` wires instances into an arbitrary
  topology so a multi-hop mesh (including relay) can be exercised on the JVM with no radios.

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
  if multiple requests fire concurrently. (See §11 for the deadlock this replaced.)
- **Endpoint bookkeeping:** `endpointToNode` / `nodeToEndpoint` maps, `connected`, `connecting`,
  `errorCounts`, and a `blacklisted` set (after `MAX_ENDPOINT_ERRORS` failures).
- **Payloads:** `BYTES` carry serialized `Frame`s (→ `inbound`); `FILE` payloads (avatars) are
  buffered by payload id on receipt, then on `onPayloadTransferUpdate(SUCCESS)` the content `Uri` is
  copied to `cacheDir/<nodeId>.jpg` and announced on `incomingFiles`.

### 3.3 Routing & dedup (`mesh/MeshRouter.kt`, `mesh/SeenSet.kt`)

`MeshRouter` is transport-agnostic and unit-tested. On each inbound frame:

1. `SeenSet.add(frame.id)` — if already seen (within the TTL window), drop.
2. Otherwise deliver locally via the `onDeliver` callback (persist / ack / cache).
3. **Relay**: if `frame.hops < frame.ttl`, re-send `frame.incrementHop()` to every neighbor **except
   the source**.

`originate()`/`sendOwn()` mark a locally-created frame as seen before sending, so an echo arriving
back from the mesh isn't re-delivered or re-relayed.

`SeenSet` is a **bounded, time-windowed LRU** (`maxSize` eviction + `ttlMillis` expiry, injectable
clock). This fixes the legacy app's unbounded, never-expiring, restart-losing seen-set. Combined
with the per-frame TTL/hop-count, propagation is guaranteed to terminate.

### 3.4 Orchestration (`mesh/MeshManager.kt`)

Owns the transport + router and implements `onDeliver`:
- **ChatFrame** → persist to Room (unacked); if the author is a *direct* neighbor, send a
  `ReceiptFrame` back (relays don't ack, mirroring the legacy design).
- **ProfileFrame** → upsert the peer's name/status/pubKey, **merging** so a separately-received
  avatar path isn't clobbered.
- **ReceiptFrame** → `markReceived(ackId)` → drives the ✓ tick on the sender's message.

Also: `sendChat`, profile broadcasting (§6), `heal()` (delegates to transport), and `restart()`
(transport stop+start, used on Bluetooth recovery). `neighborCount` is exposed as a `StateFlow` for
the chat header.

## 4. Wire protocol (`mesh/protocol/Wire.kt`)

A `@Serializable sealed interface Frame` carried as JSON bytes in a Nearby `BYTES` payload
(kotlinx.serialization, class discriminator `t`).

```kotlin
sealed interface Frame { val id: String; val ttl: Int; val hops: Int }

@SerialName("chat")    ChatFrame(id, senderId, sentAt, body, recipientId?, sig?, ttl, hops)
@SerialName("profile") ProfileFrame(id, senderId, sentAt, name, status, avatarHash?, pubKey?, ttl, hops)
@SerialName("receipt") ReceiptFrame(id, senderId, ackId, ttl, hops)
```

- `id` is the globally-unique dedup key; `ttl` (default 8) + `hops` bound propagation;
  `Frame.incrementHop()` returns a copy with `hops + 1`.
- **Reserved for the future:** `ChatFrame.recipientId` (null = broadcast; set = 1:1 DM),
  `ChatFrame.sig` (Ed25519 authorship signature), `ProfileFrame.pubKey` (identity key). Present and
  unused today so DMs/E2E are additive, not a format break.
- Avatars travel as **file payloads**, not inside frames (a 256² JPEG is too large for BYTES and the
  flood); `ProfileFrame.avatarHash` lets a peer detect changes.

`WireCodec.encode/decode` round-trips frames to/from bytes; `decode` returns null on malformed input.

## 5. Data layer (`data/`)

- **Room** (`KnitDatabase`, `knit.db`, `fallbackToDestructiveMigration`):
  - `messages`: `id` (PK, wire id), `senderId`, `recipientId?`, `body`, `sentAt`, `received`.
  - `peers`: `nodeId` (PK), `name`, `status`, `avatarPath?`, `pubKey?`, `updatedAt`.
  - DAOs expose `Flow<List<…>>` for the UI and suspend writes for the manager; `@Upsert` +
    `markReceived`.
- **Repositories** (`MessageRepository`, `PeerRepository`) are the single source of truth.
- **`SettingsStore`** (Preferences DataStore, `knit_settings`): node id, display name, status,
  advertising/discovery toggles, and `avatarUpdatedAt`. **Generates and persists the node id** on
  first read (`getOrCreateNodeId`, transaction-guarded against races).
- **`AvatarStore`**: own avatar picked → center-cropped square → 256² JPEG q90 in `filesDir`; peer
  avatars cached in `cacheDir/<nodeId>.jpg`; `ownAvatarHash()` is a cheap length+mtime fingerprint.

## 6. Identity & profiles

- **`Identity`** resolves (and lazily creates) the stable 8-char node id, caching it. A long-term
  signing/encryption keypair will be added here for E2E; the wire `pubKey` field is its on-ramp.
- **Profile broadcasting** (`MeshManager`):
  - On a **new neighbor** connecting, push the current `ProfileFrame` and (if present) the avatar
    file to that peer.
  - On a **profile change** (name/status/avatar — observed via a combined settings flow,
    `.drop(1)` to skip the initial value), bump a `profileVersion`, re-broadcast the frame, and send
    the avatar file to all neighbors.
  - The profile frame id is `profile-<nodeId>-<profileVersion>`, so an unchanged profile re-sent on
    connect dedups everywhere except the new peer, while a changed profile (new version) re-floods.

## 7. UI layer (`ui/`)

- **Compose + Material 3.** `KnitApp` is the root: it gates on permissions, then hosts a lightweight
  `Crossfade` screen stack (`Onboarding` → `Chat` ⇄ `Profile`) and starts `MeshService` once past
  onboarding. Navigation is deliberately simple; it can move to Navigation Compose when DM threads
  are added.
- **Screens:** `OnboardingScreen` (rationale + `RequestMultiplePermissions` + battery-opt prompt),
  `ChatScreen` (status header, `LazyColumn` of bubbles with auto-scroll, IME-padded input),
  `ProfileScreen` (name/status fields, avatar via photo picker + Coil, node id, battery-opt row).
- **ViewModels** (`ChatViewModel`, `ProfileViewModel`) are Koin `koinViewModel()`s.
  `ChatViewModel` `combine`s messages + peers + neighbor-count + node id + display name into UI rows
  (computing `mine`, sender name, avatar path).
- **Theme** (`ui/theme/`): real M3 light/dark schemes built from the brand coral (`#E67474` family);
  dynamic color is **off by default** so the brand shows.
- **Editable fields use write-through local state**, not the DataStore flow directly (see §11).

## 8. Background survival (`mesh/MeshService.kt`)

A typed foreground service (`connectedDevice`) hosts `MeshManager` so the mesh survives backgrounding:

- **Foreground notification** (min importance) with a Stop action.
- **Heartbeat:** inexact ~15-min `AlarmManager` alarm → `ACTION_HEAL` → `MeshManager.heal()`.
- **Significant motion:** a `TriggerEventListener` (re-armed after each fire) → `heal()` (moving
  likely means new peers in range).
- **Bluetooth recovery:** a `RECEIVER_NOT_EXPORTED` receiver for `ACTION_STATE_CHANGED` → on
  `STATE_ON`, `MeshManager.restart()`.
- All cleaned up in `onDestroy`. Battery optimization is surfaced in onboarding and the profile
  screen (`ui/Battery.kt`), prompting `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

## 9. Encryption posture

Today: **transport-only**. Nearby Connections encrypts each link, but messages are flooded and
re-encrypted hop-by-hop, so **relays can read plaintext** and authorship is unauthenticated. This is
an explicit MVP scope decision. The path to E2E:

1. Generate an Ed25519/X25519 identity keypair in `Identity` (Keystore-backed) and advertise the
   public key via `ProfileFrame.pubKey`.
2. Sign frames (`ChatFrame.sig`) for authenticity.
3. For DMs, encrypt the body to the recipient's key (e.g. libsodium sealed box) so relays can't read
   it; `recipientId` already routes them.
4. Optionally add safety-number verification and at-rest DB encryption (SQLCipher).

## 10. Build & tooling decisions

The project runs on intentionally bleeding-edge tooling (AGP 9.2.1, Gradle 9.4.1, Kotlin 2.2.10,
Compose BOM 2026.02, compileSdk 36.1). Consequences, all load-bearing:

| Decision | Reason |
|---|---|
| **Koin, not Hilt** | Hilt's Gradle plugin is broken on AGP 9.x (dagger#5083/#5099). Koin has no Gradle plugin / no annotation processor → immune. |
| **Coil pinned 3.3.0** | Coil 3.4.0+ is built with Kotlin 2.4.0; its class metadata is unreadable by the AGP-bundled Kotlin 2.2 compiler. Any dep built with Kotlin > 2.3 breaks compilation here. |
| **`android.disallowKotlinSourceSets=false`** | AGP 9 built-in Kotlin otherwise rejects the `kotlin.sourceSets` DSL KSP (Room) uses. |
| **No `kotlin-android` plugin** | AGP 9's built-in Kotlin compiles Kotlin; only compose / serialization / ksp plugins are applied. |
| **KSP `2.2.10-2.0.2`** | Must match the Kotlin version. |

When adding dependencies, pin versions in `gradle/libs.versions.toml` and check (e.g. via the
artifact's Gradle module metadata) that they aren't built with Kotlin > 2.3.

## 11. Notable bugs fixed (lessons)

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

## 12. Testing strategy

- **JVM unit tests** (`app/src/test/`, no hardware): `WireSerializationTest` (round-trips),
  `SeenSetTest` (dedup/TTL/eviction), `MeshRouterTest` (dedup, relay-excludes-source, TTL drop, and
  an A→B→C multi-hop delivery via `FakeLoopTransport`). Run with `./gradlew :app:testDebugUnitTest`.
  Use `UnconfinedTestDispatcher` for flow-propagation tests.
- **Emulator smoke test** validates launch, Koin init, screen rendering, service start, and Nearby
  advertising/discovery — but an emulator generally can't connect to a physical phone.
- **Two physical phones** are required to validate discovery → connect → message relay and
  profile/avatar exchange.

## 13. Known limitations & deferred work

- Relays can read message contents; authorship unauthenticated (transport-only encryption).
- Receipts/relays add some flood overhead (acceptable for MVP); no per-recipient delivery state.
- Tied to Google Play services via Nearby (no de-Googled path yet).
- Destructive Room migrations (history is treated as ephemeral) and unencrypted at-rest storage.
- Deferred by design: Wi-Fi Aware/BLE transports, 1:1 DMs, end-to-end encryption + verification,
  SQLCipher. The wire format (`recipientId`/`sig`/`pubKey`) and `Identity` already leave room for
  them.
