# AGENTS.md — Knit

Instructions for coding agents and contributors working in this repo. Read this before changing
build config, the mesh layer, or the DI graph. For full design detail see
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## What this is

An Android app (Kotlin/Compose) implementing an offline **Wi-Fi Aware (NAN) mesh messenger** — a direct
`android.net.wifi.aware.*` implementation, no Google Nearby / GMS. Single Gradle module `:app`, package
`app.getknit.knit`, minSdk 33 / targetSdk 36 / compileSdk 36.1 (minSdk 33 is required for Wi-Fi Aware
Instant Communication Mode + `NEARBY_WIFI_DEVICES`/`neverForLocation`). It surfaces a "Nearby" broadcast
room plus 1:1 DMs, with profiles, emoji reactions, @-mentions, and content-addressed image attachments.

## Commands

```bash
./gradlew :app:assembleDebug        # build (assembleDebug does NOT compile test sources)
./gradlew :app:compileDebugKotlin   # fast compile check of main sources
./gradlew :app:testDebugUnitTest    # JVM unit tests — run these after touching mesh/protocol/data
./gradlew installDebug              # install on a connected device
./gradlew detekt                    # static analysis via the standalone detekt CLI (reports in build/reports/detekt/)
```

`detekt` runs the standalone CLI (NOT the Gradle plugin) from an isolated `detektCli` configuration
in the root build, mirroring CI's `verify:detekt` job — same jar version (`detekt` in the version
catalog ↔ `DETEKT_VERSION` in `.gitlab-ci.yml`), same `config/detekt/detekt.yml`, same flags. It
never touches `:app`'s classpath, so it can't perturb the app build. The task exits non-zero when
detekt finds issues; HTML/XML/SARIF reports land in `build/reports/detekt/`.

- **JDK 21** is required (the Gradle daemon toolchain is pinned to 21).
- After changing the `MeshTransport` interface, run `testDebugUnitTest` — a test double
  (`RecordingTransport` in `MeshRouterTest`) implements that interface and won't be caught by
  `assembleDebug`.

## Bleeding-edge toolchain constraints (do not "fix" these without reading why)

This project intentionally runs on very new tooling (AGP 9.2.1, Gradle 9.4.1, Kotlin 2.4.0,
Compose BOM 2026.06). That forces several non-obvious choices:

- **DI is Koin, not Hilt.** Hilt's Gradle plugin is broken on AGP 9.x in this window
  (dagger#5083 / #5099). Koin is pure-Kotlin runtime DI with no Gradle plugin / no annotation
  processor, so it can't be broken by AGP. Koin is started in `KnitApplication`; modules live in
  `app/src/main/java/app/getknit/knit/di/`.
- **Built-in Kotlin is overridden to 2.4.0, not AGP's bundled 2.2.10.** AGP 9.2.1 ships KGP 2.2.10,
  whose Kotlin-2.2 compiler cannot read class metadata produced by Kotlin 2.4 (this is what used to
  pin Coil to 3.3.0). The root `build.gradle.kts` puts KGP 2.4.0 on the buildscript classpath
  (`classpath(libs.kotlin.gradle.plugin)`) so built-in Kotlin compiles with 2.4.0 — a supported combo
  (Kotlin 2.4 requires AGP 9.1+ per Google's AGP/Kotlin matrix). **Bumping AGP does not move Kotlin**:
  the 9.3 line (now at RC) and 9.4-alpha still bundle 2.2.10, so the override — not an AGP bump — is
  the lever. Keep KGP and the `ksp` version in lockstep with `kotlin`; KSP adopted independent (KSP2)
  versioning at 2.3.0 (decoupled, Kotlin 2.2+), so it no longer uses the old `<kotlin>-<ksp>` scheme.
- **`android.disallowKotlinSourceSets=false`** is set in `gradle.properties`. AGP 9's built-in
  Kotlin otherwise rejects the `kotlin.sourceSets` DSL that KSP (Room's processor) uses.
- **No explicit `kotlin-android` plugin.** AGP 9's built-in Kotlin handles compilation; only the
  `kotlin.plugin.compose`, `kotlin.plugin.serialization`, and `ksp` plugins are applied.
- Pin third-party versions in `gradle/libs.versions.toml` (version catalog); probe Maven before
  bumping anything that could pull in a newer Kotlin stdlib.

## Architecture in one screen

```
ui/            Compose screens (onboarding, chatlist, chat, contacts, profile) + ViewModels
               (Koin koinViewModel()) · KnitApp (Navigation Compose)
mesh/          MeshTransport (interface) · MeshRouter (dedup + jittered/suppressed flood)
               · MeshManager (orchestrator) · MeshService (foreground service) · MeshMetrics
               · BlobExchange/BlobStore (content-addressed pull) · ForwardSync/ForwardStore
               (store-and-forward DM + group + broadcast custody) · SyncEpoch + CueTracker (pure
               anti-entropy for the cue plane) · protocol/Wire.kt (CBOR Frame)
mesh/crypto/   E2E (Tink): MessageCrypto (per-msg seal/open) · PublicKeyBundle · MessageContent
               · AttachmentCrypto · SafetyNumber · VerifyPayload (pure, JVM-testable)
mesh/wifiaware/ WifiAwareTransport (+ AwareFraming socket codec) — the ONLY place that imports
               android.net.wifi.aware.*
data/          Room (messages, peers, reactions, forward_store) + repositories · settings/SettingsStore (DataStore)
               · AvatarStore + AttachmentStore (image files) · message/Conversations (DM keys)
               · crypto/ DatabaseKey + IdentityKeyStore (AndroidKeyStore-wrapped secrets) + KeystoreSecret
identity/      Identity (stable nodeId + E2E keypair) · NodeId (derive) · DeviceIdSource · Alias
notifications/ Notifier + MessageNotifier (messages + dedicated mentions channel)
di/            Koin modules: appModule, meshModule, uiModule
```

Data flow: UI → `MeshManager` → `MeshRouter` (dedup + jittered relay) → `MeshTransport` → radios;
inbound frames flow back `MeshTransport.inbound` → `MeshRouter` → repositories → Compose
`StateFlow`s. Avatars/attachments travel out of band as file payloads (`incomingFiles`), not in
frames.

## Conventions

- **Keep Wi-Fi Aware behind `MeshTransport`.** Nothing outside `mesh/wifiaware/` should import
  `android.net.wifi.aware.*` (or `ConnectivityManager`/`NetworkRequest` for the NAN data path).
  Everything above the transport talks only to the `MeshTransport` interface, so a future sibling
  transport (e.g. a BLE fallback) drops in without touching orchestration.
- **DI:** declare singletons/ViewModels in the `di/` modules; resolve ViewModels in Compose with
  `org.koin.androidx.compose.koinViewModel()` and the ViewModel DSL from
  `org.koin.core.module.dsl.viewModel` (not the deprecated `androidx.viewmodel.dsl` one).
- **Wire format** (`mesh/protocol/Wire.kt`) is **layered** binary CBOR (`WireCodec`,
  `encodeDefaults = false`, `ignoreUnknownKeys = true`) — not JSON, and deliberately structured so it
  can evolve *additively* without another break. **Read `docs/WIRE_COMPAT.md` before changing any wire
  type.** The three layers:
  - `WireEnvelope` (frozen, the on-radio unit): mutable `ttl`/`hops`, a `relay` flag, the raw `sig`, and
    the opaque `signed` blob. It is the ONLY thing a relay re-encodes — `WireEnvelope.relayed()` rewrites
    just ttl/hops; `signed`+`sig` pass through **byte-for-byte**, so the originator's signed bytes
    survive every hop (this is what kills the old "an old relay re-encodes and breaks the signature"
    bomb). `sig`/`signed`/`payload` are `@ByteString ByteArray` (opaque), never nested objects.
  - `RelayEnvelope` (what `signed` decodes to): the cleartext routing fields a relay/carrier needs —
    `type` (a plain **string** discriminator, so an unknown future type decodes instead of throwing),
    `id`, `senderId`, `sentAt`, `recipientId`, `group` — plus the opaque per-type `payload`.
  - Per-type content (`ChatContent`, `ProfileContent`, `GroupLeaveContent`, `ReceiptContent`,
    `ReactionContent`, `BlobReqContent`, `KeyReqContent`) lives inside `payload`; only endpoints parse it.
    Current `type`s: `chat`, `groupupdate`, `profile`, `receipt`, `reaction`, `groupleave`, `blobreq`,
    `keyreq`.
  **One signature authenticates every type**: `WireEnvelope.sig` is raw Ed25519 over `signed` (which
  binds `type`/`id`/`senderId`), verified byte-exact in `MeshManager.verifyInbound`; `blobreq` (with
  `relay = false`) is the only unsigned frame. `MeshManager` signs on origination; `verifyInbound` drops
  any flooded frame whose signature is missing/invalid or whose key doesn't derive to `senderId`, but
  the router still relays unknown/unverifiable frames verbatim so an old build is never a black hole.
  `Protocol.VERSION`/`capabilities` ride (unauthenticated) in the Wi-Fi Aware `serviceSpecificInfo`
  advert and (authenticated) on `ProfileContent`. Changing `WireEnvelope`'s shape, the `WireCodec`
  config, the signing input, the `SERVICE_NAME` (`WifiAwareTransport`), or removing/renaming a field/type
  is a coordinated wire break; adding a nullable/defaulted field or a new `type` is additive — see
  `docs/WIRE_COMPAT.md`.
- **Pure, testable mesh logic.** `MeshRouter`, `SeenSet`, `WireCodec`, `MeshMetrics`, `BlobExchange`,
  and `Conversations` have no Android dependencies and are unit-tested with `FakeLoopTransport`/fakes.
  Keep them that way. `MeshRouter` relay timing is driven by an injectable `jitter` lambda so tests
  use a fixed delay + virtual time.
- Match the surrounding Kotlin style (official Kotlin style; 4-space indent; trailing commas).

## Gotchas that have already bitten us

- **Don't bind a Compose `TextField` directly to a DataStore-backed flow.** The async write→emit
  round-trip lags a keystroke and resets the field (you can only type one character). Hold editable
  text in a local `MutableStateFlow` in the ViewModel and persist to DataStore in the background —
  see `ProfileViewModel`.
- **Per-peer responders DON'T compose — use one persistent accept-any responder.** The intuitive design
  (each incoming peer gets its own `WifiAwareNetworkSpecifier.Builder(session, peerHandle).setPort(...)`
  responder + `ServerSocket`) works for *two* devices but silently fails for a third: a device already
  acting as responder for one peer cannot stand up a second per-peer responder, so a phone joining an
  existing pair is stranded (its client `requestNetwork` just times out; verified — 7 couldn't join an
  8+9 pair). The fix in `WifiAwareTransport`: each node runs **one** responder built from its *publish*
  session with **no peer handle** (`WifiAwareNetworkSpecifier.Builder(publishSession).setPort(port)`),
  which accepts a data path from **any** initiator over a single `ServerSocket`; all clients share it.
  Because an accept-any responder doesn't know who connected, the **initiator sends its advert as the
  first `AwareFraming.Type.HELLO` record** over the socket, and the responder reads it to identify the
  peer. Tie-break gives one link per pair (larger nodeId = client/initiator, smaller = server). The
  responder is anchored to the publish session, so **only *subscribe* is ever re-armed** (publish/responder
  stay up), respecting the "one data interface" rule below.
- **One NAN data interface (`maxNdiInterfaces == 1`) → one data path at a time → cue-driven ephemeral
  sync.** The single hardest constraint, confirmed on Pixel 7/8/9 (`dumpsys wifiaware` →
  `maxNdiInterfaces=1`): a node can hold an NDP to exactly **one** peer at once — a second data path to a
  *different* peer is refused with `WifiAwareDataPathStMgr: ... NdpInfos[] - no interfaces available!`. So
  the old "everyone links to everyone they're larger than" model can't work for 3+ nodes: the largest node
  tries two initiator NDPs and strands the third phone (verified: Pixel 7, largest, couldn't reach Pixel 9
  while linked to Pixel 8). `WifiAwareTransport` therefore runs **two planes**:
  - **Coordination plane** — Wi-Fi Aware *messages* (`DiscoverySession.sendMessage` / `onMessageReceived`,
    ~255 B, best-effort, `maxQueuedTransmitMessages=8`) ride discovery follow-up frames and need **no data
    path**, so they reach every neighbor at once *and* keep working while the one NDP is busy. Each node
    cues `nodeId|storeEpoch` — a `SyncEpoch` monotonic counter bumped on every carry-store / own-profile
    write — and `CueTracker` (pure, JVM-tested) flags a peer *sync-wanted* when either side's epoch advanced
    since the last sync. A cue also bootstraps the reverse handle, so a node whose own *subscribe* is broken
    (e.g. Pixel 9 post-kill) still cues larger peers to pull from it.
  - **Data plane** — one ephemeral NDP, brought up **only** when a peer is sync-wanted (the larger id
    initiates, via the unchanged `initiateTo` + accept-any responder), drained by the existing
    `onNeighborAdded` backfill, then torn down on **quiescence** (no data for `QUIESCENCE_MS`, never
    mid-file) — freeing the NDI for the next pair. The initiator drives teardown and records the sync in
    `CueTracker` (it alone consults it); the responder just sees the socket close, with a longer
    `RESPONDER_MAX_HOLD_MS` safety cap for a dead initiator.

  Net: an **idle mesh does zero data-path work** (just beacons + occasional cues); a new message triggers a
  targeted sync with only the peers that need it; and everything stays delay-tolerant (store-and-forward
  custody carries what one flood doesn't reach — a rotating series of pairwise syncs propagates
  epidemically). Single-slot admission (`beginConnect`/`beginAccept`: at most one link/handshake/accept,
  plus a `SETTLE_MS` gap after a link ends so the NDI is released before the next `requestNetwork`) keeps
  the radio off the "no interfaces available" wedge. `discoveryLoop`/`rearmSubscribe()` re-fire one-shot
  discovery **only while the slot is free** (never with a live NDP, whose client side rides the subscribe
  session and would be dropped by a re-arm).
- **`requestNetwork` with no timeout leaks the one interface forever — always time-box it.** The 3-arg
  `requestNetwork(request, cb, handler)` has no timeout, so a request that can't be fulfilled stays pending
  forever — its `NetworkCallback` never unregisters and its NDI reservation never frees, so the node
  exhausts its single interface and can never connect again (observed: a Pixel 7 stuck in a
  `terminate: already terminated` loop). Always use the **timeout** overload
  `requestNetwork(req, cb, handler, HANDSHAKE_TIMEOUT_MS)`, clean up in `onUnavailable`, and back a failed
  peer off (`CONNECT_BACKOFF_MS`) so a different sync-wanted peer gets the slot next. Note `neighbors` is the
  ≤1 live link (send routing + the `onNeighborAdded` sync hooks); the **UI reads the smoothed `reachable`
  set** (coordination-plane sightings, lingered `REACHABLE_LINGER_MS`) so it doesn't blink as ephemeral
  syncs come and go.
- **Wi-Fi Aware availability flaps, and may be absent entirely.** `WifiAwareManager.isAvailable()` goes
  false when Wi-Fi is off or Wi-Fi Direct / SoftAP / hotspot seizes the radio; the transport watches
  `ACTION_WIFI_AWARE_STATE_CHANGED`, flips `health` to `Degraded`, tears links down, and re-attaches on
  recovery. `PackageManager.FEATURE_WIFI_AWARE` can be missing outright (some budget/older + certain
  Samsung models) — **there is no fallback transport**, so the UI gates with a clear "unsupported" state
  (`hasWifiAwareHardware`).
- **One file streams at a time per socket.** `AwareFraming` multiplexes frames + files over the single
  NDP socket; the writer serializes file transfers and interleaves live frames *between* chunks (so an
  8 MiB blob never stalls traffic), which is why a `FILE_HEADER`→`FILE_CHUNK`s→`FILE_END` run needs no
  file id. Don't push two files down one socket expecting them to interleave.
- **Wi-Fi Aware needs physical devices.** An emulator can't do NAN. Use `FakeLoopTransport` for logic
  tests and two physical Wi-Fi-Aware-capable phones (e.g. Pixels) for real discovery → data path → relay.
- **Keep the `<Frame>` type argument on the CBOR codec.** `WireCodec` calls
  `cbor.encodeToByteArray<Frame>(frame)` / `decodeFromByteArray<Frame>(bytes)` — the explicit
  `<Frame>` selects polymorphic encoding (the `@SerialName` discriminator). Dropping it serializes the
  concrete subtype without the discriminator and breaks decode on the other end.
- **After a version bump, regenerate the lockfile for ALL configurations, not just the ones your
  build resolves.** `app/build.gradle.kts` sets `dependencyLocking { lockAllConfigurations() }`, so
  `app/gradle.lockfile` pins every configuration. `--write-locks` only rewrites the configs a given
  task actually resolves: `:app:assembleDebug` + `:app:testDebugUnitTest` leave the *instrumented*-test
  configs (`debugAndroidTestCompileClasspath`, …) at their old locked versions. `./gradlew lint`
  (which the repo's stop-hook runs) builds the androidTest lint model, hits those stale locks, and
  fails with a wall of "Cannot find a version … {strictly <old>} … enforced by Dependency Locking"
  errors — which look like a resolution break but are just a half-updated lockfile. Always regenerate
  with `./gradlew :app:dependencies --write-locks` (resolves every configuration), then `./gradlew lint`.

## Verifying changes

1. `./gradlew :app:testDebugUnitTest` for mesh/protocol/data logic.
2. Emulator smoke test for UI/startup (launch, Koin init, screen rendering, no crash) — the app
   runs fine on an emulator, it just can't form a real mesh there.
3. Two physical phones for discovery → connect → relay and profile/avatar exchange.

When driving the emulator over `adb`: the soft keyboard overlaps via `adjustResize`, so read element
coordinates from `uiautomator dump` rather than guessing; seed the photo picker by `screencap`-ing
into `/sdcard/Pictures` if you need an image to select.

## Driving the app on a device (debug builds)

Debug builds carry three affordances so an agent can drive the send→verify loop **without** screenshots
or hunting the (unlabeled, state-dependent) send button's pixel bounds. All are **debug-only** — the
bridge receiver and its manifest entry live in `app/src/debug/` (so the release APK has neither), and the
route extra is gated on `BuildConfig.DEBUG`. `app/build.gradle.kts` is untouched.

- **Headless bridge** (`app/src/debug/.../debug/DebugBridgeReceiver.kt`) — an exported `BroadcastReceiver`
  that calls `MeshManager` directly and returns JSON. Fire with `am broadcast` (target the package with
  `-p app.getknit.knit`); the reply prints on stdout as `Broadcast completed: … data="{…}"` and is also
  logged one-line under tag `KnitBridge` (`adb logcat -d -s KnitBridge:I`). Actions:
  - `…debug.SEND` — `--es text <body>` + a target: `--es conv <id>` (`nearby` room, a peer node id for a
    DM, or a `g-…` group id) or `--es to <peerNodeId>` (DM shorthand). No target ⇒ broadcast room. Text is
    passed verbatim — spaces/emoji survive (unlike `adb shell input text`) **provided you quote for the
    on-device shell**: `adb` re-parses the command on the device, so a bare `--es text "hi there"` is
    word-split and truncated to `hi`. Wrap the whole remote command in double quotes and single-quote the
    value (see the example).
  - `…debug.STATE` — self id/name, transport health, reachable peers, and mesh metrics. Add `--es conv <id>`
    to also dump that thread's latest messages (`--ei limit N`, default 20), each with its `received`
    delivery tick — this is how you **verify receipt on the other device without a screenshot**.
  - `…debug.REACT` — `--es id <messageId> --es emoji <emoji>`. `…debug.HEAL` — nudge rescan/re-advertise.
  ```
  # send on A, then confirm it landed on B — no UI, no screenshots. Outer quotes matter: adb re-parses
  # on the device, so quote the whole command and single-quote the text (a bare --es text is word-split).
  adb -s A shell "am broadcast -a app.getknit.knit.debug.SEND  -p app.getknit.knit --es text 'hi there 😀' --es conv nearby"
  adb -s B shell  am broadcast -a app.getknit.knit.debug.STATE -p app.getknit.knit --es conv nearby
  # → data="{…,"messages":[{"from":"<A>","body":"hi there 😀","received":…}]}"
  ```
- **Stable resource-ids** — the root sets `testTagsAsResourceId` (in `KnitApp`), so `Modifier.testTag`s
  surface in `uiautomator dump` as `resource-id="app.getknit.knit:id/<tag>"`. Tagged so far: `chat_input`,
  `chat_send`, `chat_row_<conversationId>` (e.g. `chat_row_nearby`), `chatlist_fab`, `contacts_fab`,
  `contact_<nodeId>`, `onboarding_grant`, `onboarding_start`, `profile_name`, `profile_status`,
  `profile_save`. Use these when you must drive the real UI; add more with the same snake_case,
  screen-prefixed convention.
- **Cold-start navigation** — `adb shell am start -n app.getknit.knit/.MainActivity --es demo_route chat/<id>`
  opens a thread directly (`chat/nearby`, `chat/<nodeId>`, `chat/g-…`). Cold-start only; for a
  running instance tap a `chat_row_*` element instead.

## End-to-end encryption (implemented)

DMs and group chats are E2E-encrypted; the broadcast "Nearby" room stays plaintext by design (no fixed
recipient set). Scheme (static keys, no ratchet): a per-message random content key AES-256-GCM-encrypts
the `MessageContent` (body + mentions + attachment refs) into an `EncEnvelope` carried inside the
encrypted `ChatContent.enc` payload, and the content key is wrapped (Tink HPKE/X25519) to each recipient.
Identity keypairs live in `IdentityKeyStore` (AndroidKeyStore-wrapped, **outside** the destructively-
migrated DB), advertised via `ProfileContent.pubKey`, pinned TOFU into `PeerEntity.pubKey`, and confirmed
out of band via the safety-number/QR screen (`PeerEntity.verified`). Image attachments are encrypted to
a per-attachment key and content-addressed by ciphertext hash, so `BlobExchange`/`BlobStore` are
unchanged. **Decrypt/verify failures must never throw out of the inbound handler** — `onDeliver` runs
before the router schedules the relay, so a throw would stop forwarding (see `MeshManager.decryptAndDeliver`).

**One signature authenticates every flooded frame** (encrypted *and* plaintext: broadcast `chat`,
`profile`, `groupupdate`, `groupleave`, `reaction`, `receipt`). `WireEnvelope.sig` is raw Ed25519 over
`WireEnvelope.signed` (the canonical `RelayEnvelope` CBOR, which includes the encrypted `ChatContent.enc`
for a DM/group message), and `MeshManager.verifyInbound` (the gate at the top of `onDeliver`) verifies it
**byte-exact over the received `signed` bytes** — no re-encode — and drops any that fail, closing the gap
where a relay could forge a frame (e.g. a profile with a different name) under another node's `senderId`.
Verification reuses the key path (`peers.find(senderId).pubKey` → `PublicKeyBundle.verifier()`, guarded
by `NodeId.fromPublicKeyBundle == senderId`); a `profile` uses the `pubKey` in its own `ProfileContent`
payload since first contact precedes any pin. `blobreq` stays unsigned. Same no-throw contract applies:
`verifyInbound` swallows failures and returns false (drop locally) so the router still relays. The old
separate envelope signature (`MessageCrypto.signingBytes`/`verifyEnvelope`) is gone — subsumed by the one
frame signature; `MessageCrypto.open` now only unwraps + AES-decrypts. `EncEnvelope.v`/`MessageContent.v`
gate the crypto-scheme/content-schema versions in `MeshManager.decrypt` (unknown ⇒ drop locally + count,
but still relay — a delivery gate, never a relay gate; see `docs/WIRE_COMPAT.md`).

## Store-and-forward message delivery (implemented)

The mesh floods a frame once and forgets it, so a message whose recipient (or a path to them) isn't
connected at that instant never arrives. **`ForwardSync` + `ForwardStore`** add delay-tolerant custody
for **every chat message — 1:1 DMs, group messages, and the plaintext broadcast room** (so two phones
that meet only briefly backfill each other's ambient history — the festival case). A node persists the
messages it originates (`ORIGIN_SELF`) or relays
(`ORIGIN_RELAY`) into the encrypted `forward_store` table, and when a neighbor joins (`watchNeighbors`
newcomers → `ForwardSync.onNeighborAdded`) **unicasts** the carried ones to it (skipping a
per-peer-per-session memo + the message's own author). Re-served frames re-enter the existing
`handleInbound` path — deliver + relay, no separate delivery code. A stored frame keeps only its immutable
signed blob + signature (`CarriedFrame`); a fresh `WireEnvelope` (full `ttl`, `hops=0`) is stamped around
it on re-serve, so it re-floods with a full hop budget when re-served much later (the signature covers
neither ttl nor hops, which live in the unsigned wrapper).

**DMs** are carried only when relayed *toward someone else* (gated `!isForMe` in `onDeliver`) and offered
to any newcomer: the recipient delivers + acks, anyone else relays the frame onward. A **delivery receipt
from the addressed recipient** purges the carried copy mesh-wide and tombstones its id
(`ForwardSync.onAck`); because the ack must come *from* the DM's cleartext `recipientId`, a forged receipt
can't evict an undelivered message — the same recipient check also gates `MeshManager.handleReceipt` →
`markReceived` (fixing a prior tick-spoof where any signed receipt flipped the ✓✓).

**Group messages** carry a cleartext member roster (`RelayEnvelope.group.members`) on every frame, so custody
exploits it: a node carries a group message whether or not it is itself a member (for other members who
may be offline), but **push is member-targeted** — `onNeighborAdded` offers a carried group frame only to
a roster member; once any member receives it, the normal flood re-distributes it to the rest, so there's
no spraying group traffic at non-members. A group has no single recipient and no reliable per-member ack,
so it is **never vaccine-purged** — the TTL/cap sweep is its only bound.

**Broadcast-room messages** (`recipientId == null && group == null` — the natural discriminator, no schema
column) are carried too and offered to **every** newcomer (no destination to target), gated only by the
capture path explicitly carrying them (`isForMe(null)` is true, so the DM `!isForMe` gate would otherwise
skip them — see `MeshManager.onDeliver`). Like a group they have no ack, so a **shorter TTL** + a
**broadcast quota** are their only bound; they are never vaccine-purged. `isStorable()` is now simply
`type == CHAT`.

Bounds (`ForwardRepository`): a per-message **TTL** sweep (startup + a 10-min loop + the heartbeat
`heal()`; broadcast gets a shorter TTL than DMs/groups), a **global cap**, a **per-sender quota**, a
**per-group quota**, and a **broadcast quota**, all with relayed-before-our-own eviction. A carrier stores a message only when its sender is **pinned, not blocked,
and its frame signature verifies** (`MeshManager.canCarry` → `MessageCrypto.verify` over the received
`signed` bytes, authenticating without decrypting — a carrier holds no wrapped key). Notifications fire only on first delivery
(`deliverChat` `isNew` gate, conversation-agnostic) so a re-served message (after the 10-min `SeenSet`
window, or a restart that empties it) never replays. The pure logic (`ForwardSync`, `ForwardStore`) is
JVM-tested with `FakeLoopTransport` (`ForwardSyncTest`).

A complementary **retransmit-on-key-arrival** path closes the most common "it never sent" for DMs: a DM
composed before the recipient's key is known is saved `pendingKey` (not flooded), and `handleProfile`
re-seals + floods it once the key arrives (`flushPendingFor`). Groups don't get this — see Out of scope.

The **inbound** complement (`KeyExchange`, `keyreq`) closes the *receive* side: a profile floods once on
connect/edit under a one-shot `SeenSet`-deduped id, so a node that joined late or sits more than one hop
from the originator can permanently miss it and then drop every frame that peer floods with
`NO_SENDER_KEY`. When `verifyInbound` hits that drop it calls `keyExchange.want(senderId)`, which sends a
**signed, point-to-point** (`relay = false`) `keyreq` to its neighbors; a neighbor that holds the peer's
profile re-serves it verbatim (the response rides the existing self-certifying `profile` path — no new
response type, no re-signing), and one that doesn't records the requester and recurses, so the profile
walks hop-by-hop to the requester exactly like a `BlobExchange` blob pull — deliberately request/response,
not another flood. The request is signed (not unsigned like `blobreq`) so a responder authenticates it
against the requester's pinned key — always present, since direct neighbors exchange profiles on connect —
and can ignore a blocked/unknown asker; signing is free precisely because the request never leaves the
direct-neighbor hop. Throttled by a per-peer cooldown + a `missing` set re-asked of each newcomer
(`onNeighborAdded`) and on `heal()`; bookkeeping is in-memory and repopulates as profiles re-arrive.
Recovery is visible in Diagnostics (`keyRequestsSent`/`keysServed`/`keysRecovered`) and JVM-tested with
`FakeLoopTransport` (`KeyExchangeTest`). `handleProfile` also gained a last-writer-wins `sentAt` guard so
a re-served (older) profile can never revert a newer name/status — the key itself is immutable per nodeId.

The dropped frame that *triggered* the request is no longer lost: `verifyInbound` also **parks** it in
`PendingInbound` (an in-memory, bounded, ~2-min-TTL buffer — the inbound complement of the outbound
`flushPendingFor`), and once `handleProfile` pins the key it **replays** every parked frame for that
sender back through `onDeliver` (`pendingInbound.release(...)`, the last statement so the key + any
deviceTag block are applied first). Replay bypasses the router (no re-flood, no `SeenSet` hit) and
`deliverChat`'s `isNew`/idempotent-save gates keep a later store-and-forward re-serve a no-op. The buffer
is in-memory by design (a parked frame is unauthenticated until its key arrives, so it's never persisted)
and bounded by a global cap (the real bound — the senderId is an unauthenticated claim), a per-sender cap,
and the TTL. Only the locally-delivered types are held (`FrameType.isReplayable`). `PendingInbound` is now
just the fast path: DM, group, **and** broadcast frames all also degrade gracefully via store-and-forward
re-serve after the buffer expires (broadcast custody closed the old gap where a broadcast frame had the
`PendingInbound` TTL as its only recovery window). Surfaced in Diagnostics (`framesHeld`/`framesReplayed`)
and JVM-tested (`PendingInboundTest`).

## Out of scope (deferred, by design)

> **Correction:** a **Bluetooth LE plane is implemented** (`mesh/bluetooth/`) and runs *simultaneously* with
> Wi-Fi Aware behind `CompositeMeshTransport` (wired in `di/MeshModule.kt`) — BLE advertise/scan presence +
> persistent L2CAP CoC data links, *preferred* over NAN's ephemeral NDP, with per-peer escalating connect
> backoff and A2DP-audio instrumentation. It is a co-plane, **not** a "fallback", and BLE-capable devices use
> it regardless of Wi-Fi Aware support. Several passages elsewhere in this file still say "pure Wi-Fi Aware /
> no BLE" (e.g. the intro, the `mesh/wifiaware/` "ONLY place" note) and are stale pending a broader doc pass.

Still deferred (by design): a **BLE promotion gate on A2DP audio** (the monitor observes but does not yet
throttle connects while streaming — see the plan); **true DM routing** (DMs still
flood — only the addressed recipient delivers/acks; store-and-forward now *carries* undelivered DMs, see
above, but there is still no routing table); a **group key-gap retransmit** (the group analogue of the DM
`flushPendingFor`: a group message already floods to the members whose keys are known, so reaching a
member whose key arrives *later* needs a fresh re-seal, not custody), or replacing push-on-contact with a
**digest/pull anti-entropy** exchange (efficiency only); and for E2E specifically:
**forward secrecy / a ratchet** (static keys only), **encrypting** reactions/receipts (they are signed
now — see the E2E section — but still flood as cleartext metadata), and encrypting the broadcast room.
(The **inbound key-request** for a frame received from a not-yet-pinned sender — the inbound complement of
retransmit-on-key-arrival — is now implemented; see `KeyExchange` above.)
Don't start these without explicit direction.
