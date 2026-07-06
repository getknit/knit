# AGENTS.md — Knit

Instructions for coding agents and contributors working in this repo. Read this before changing
build config, the mesh layer, or the DI graph. For full design detail see
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## What this is

An Android app (Kotlin/Compose) implementing an offline **mesh messenger** that runs two radios at once —
**Wi-Fi Aware (NAN)** and **Bluetooth LE** — behind a single `MeshTransport` seam
(`CompositeMeshTransport`). Direct `android.net.wifi.aware.*` / `android.bluetooth.*` implementations, no
Google Nearby / GMS. Single Gradle module `:app`, package `app.getknit.knit`, minSdk 33 / targetSdk 36 /
compileSdk 36.1 (minSdk 33 is required for Wi-Fi Aware Instant Communication Mode +
`NEARBY_WIFI_DEVICES`/`neverForLocation`; a device with only one of the two radios still meshes over that
one). It surfaces a "Nearby" broadcast room plus 1:1 DMs and group chats, with profiles, emoji reactions,
@-mentions, content-addressed image attachments, store-and-forward custody, and on-device content
moderation.

## Commands

```bash
./gradlew :app:assembleDebug        # build (assembleDebug does NOT compile test sources)
./gradlew :app:compileDebugKotlin   # fast compile check of main sources
./gradlew :app:testDebugUnitTest    # JVM unit tests — run these after touching mesh/protocol/data
./gradlew installDebug              # install on a connected device
./gradlew detekt                    # static analysis via the standalone detekt CLI (reports in build/reports/detekt/)
./gradlew ktlint                    # Kotlin style/format lint via the standalone ktlint CLI (reports in build/reports/ktlint/)
```

`detekt` runs the standalone CLI (NOT the Gradle plugin) from an isolated `detektCli` configuration
in the root build, mirroring CI's `verify:detekt` job — same jar version (`detekt` in the version
catalog ↔ `DETEKT_VERSION` in `.gitlab-ci.yml`), same `config/detekt/detekt.yml`, same flags. It
never touches `:app`'s classpath, so it can't perturb the app build. The task exits non-zero when
detekt finds issues; HTML/XML/SARIF reports land in `build/reports/detekt/`.

`ktlint` runs the same way — the standalone ktlint CLI (NOT the Gradle plugin, same toolchain reason
as detekt) from an isolated `ktlintCli` configuration in the root build, pinned to the `shadowed`
(fat-jar) variant so it pulls no rulesets onto `:app`. Rules are the ktlint standard ruleset, configured
via the repo-root **`.editorconfig`** (which the CLI auto-discovers) — including the `@Composable`
function-naming opt-out (`ktlint_function_naming_ignore_when_annotated_with = Composable`). The task
lints `app/src/**/*.kt` + the `*.gradle.kts` scripts and exits non-zero on any violation; report at
`build/reports/ktlint/`. **It does NOT autocorrect** — fix mechanical issues locally with the
`ktlint --format` CLI, then re-run the task. `detekt` and `ktlint` both run as `Stop` hooks
(`.claude/hooks/gradle-{detekt,ktlint}-stop.sh`) alongside `./gradlew lint`. Note the two tools can
disagree: ktlint's one-arg-per-line wrapping can push a function past detekt's `LongMethod=60` — suppress
`LongMethod` on that function (with a one-line reason) rather than fighting the formatter (see
`MeshManager.sendChat`, `NotificationChannels.ensure`).

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
ui/            Compose screens (onboarding, chatlist, chat, contacts, profile, group, diagnostics,
               blocked, share, donate) + ViewModels (Koin koinViewModel()) · KnitApp (Navigation Compose)
mesh/          MeshTransport (interface) · CompositeMeshTransport (runs the radios below simultaneously)
               · MeshRouter (dedup + jittered/suppressed flood) · MeshManager (orchestrator)
               · MeshService (foreground service) · MeshMetrics · BlobExchange/BlobStore
               (content-addressed pull) · ForwardSync/ForwardStore (store-and-forward DM + group +
               broadcast custody) · KeyExchange (keyreq) + PendingInbound (park-until-key) · AckSync
               (delay-tolerant broadcast/group delivery tick) · StoreDigest
               + DigestTracker (pure content-digest anti-entropy for the cue plane) · protocol/Wire.kt
               (layered CBOR WireEnvelope) · link/ (LinkFraming — transport-neutral socket record codec)
mesh/crypto/   E2E (Tink): MessageCrypto (per-msg seal/open) · PublicKeyBundle · MessageContent
               · AttachmentCrypto · SafetyNumber · VerifyPayload (pure, JVM-testable)
mesh/wifiaware/ WifiAwareTransport — the ONLY place that imports android.net.wifi.aware.*
mesh/bluetooth/ BluetoothMeshTransport (BLE advertise/scan + persistent L2CAP links) — the ONLY place
               that imports android.bluetooth.* · ScanDemandPolicy/PromotionPolicy/ConnectBackoffPolicy
moderation/    on-device TextModerator (LexicalTextFilter + MlTextModerator) + ImageModerator
               (NsfwImageModerator) — see docs/CONTENT_MODERATION.md
data/          Room (messages, peers, reactions, blobs, groups, blob_verdicts, forward_store) + repositories
               · settings/SettingsStore (DataStore) · AvatarStore + AttachmentStore + BlobRepository
               (image bytes + NSFW verdicts) · message/Conversations (DM keys) · crypto/ DatabaseKey +
               IdentityKeyStore (AndroidKeyStore-wrapped secrets) + KeystoreSecret
identity/      Identity (stable nodeId + E2E keypair) · NodeId (derive) · DeviceIdSource · DeviceTag · Alias
notifications/ Notifier + MessageNotifier (per-context channels: nearby, groups, DMs, mentions)
di/            Koin modules: appModule, meshModule, moderationModule, uiModule
```

Data flow: UI → `MeshManager` → `MeshRouter` (dedup + jittered relay) → `MeshTransport` → radios;
inbound frames flow back `MeshTransport.inbound` → `MeshRouter` → repositories → Compose
`StateFlow`s. Avatars/attachments travel out of band as file payloads (`incomingFiles`), not in
frames.

## Conventions

- **Keep each radio behind `MeshTransport`.** Nothing outside `mesh/wifiaware/` should import
  `android.net.wifi.aware.*` (or `ConnectivityManager`/`NetworkRequest` for the NAN data path), and
  nothing outside `mesh/bluetooth/` should import `android.bluetooth.*`. Everything above the transport
  talks only to the `MeshTransport` interface; `CompositeMeshTransport` runs both radios at once behind
  that seam (Bluetooth preferred, Wi-Fi Aware second), so orchestration (`MeshManager`/`MeshRouter`) is
  unchanged and another sibling transport drops in the same way. The socket record codec
  (`mesh/link/LinkFraming`) is transport-neutral and shared by the NAN NDP socket and the BLE L2CAP socket.
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
  first `LinkFraming.Type.HELLO` record** over the socket (`mesh/link/LinkHandshake`, shared with BLE), and
  the responder reads it to identify the peer. Tie-break gives one link per pair (larger nodeId =
  client/initiator, smaller = server). The
  responder is anchored to the publish session, so **only *subscribe* is ever re-armed** (publish/responder
  stay up), respecting the "one data interface" rule below.
- **One NAN data interface (`maxNdiInterfaces == 1`) → one aware *network* at a time → cue-driven ephemeral
  sync.** The single hardest constraint, confirmed on Pixel 7/8/9 (`dumpsys wifiaware` →
  `maxNdiInterfaces=1`) — but the limit is **per-role**, not "one NDP, period" (re-audited on-device
  2026-07-04; evidence + corrected model in `docs/NAN_CONCURRENCY_REAUDIT.md`): every **initiator**
  `requestNetwork` is its own aware Network and needs its own NDI, so a second concurrent *initiate* is
  refused with `WifiAwareDataPathStMgr: ... NdpInfos[] - no interfaces available!` (verified: Pixel 7,
  largest, couldn't reach Pixel 9 while linked to Pixel 8) — while the **accept-any responder is ONE
  network that officially multiplexes many concurrent inbound NDPs** on the same NDI (E1: 30+ consecutive
  serves on one request with zero re-attaches; E2: two *simultaneous* inbound NDPs; firmware budget
  `maxNdpSessions=8`; the `dumpsys` `mMaxNdpInApp=1` once read as a per-app cap is a metrics high-water
  mark, not a limit). Each node's *outbound* is still single, so "everyone links to everyone they're larger
  than" still can't work, and the shipped design runs **two planes** (a concurrent-serve redesign is
  proposed in `docs/NAN_CONCURRENCY_REAUDIT.md` §5):
  - **Coordination plane** — Wi-Fi Aware *messages* (`DiscoverySession.sendMessage` / `onMessageReceived`,
    ~255 B, best-effort, `maxQueuedTransmitMessages=8`) ride discovery follow-up frames and need **no data
    path**, so they reach every neighbor at once *and* keep working while the one NDP is busy. Each node
    cues `nodeId|version` — a `StoreDigest` **content digest** (XOR over its **live** custody frame-id set, so
    it is O(1)-incremental and **restart-stable**: same store ⇒ same version, unlike the old monotone
    `SyncEpoch` counter it replaced; expired-but-unswept rows are excluded, folded out **lazily** by
    `StoreDigest.current()` at every read/cue — expiry is frame-global `sentAt + TTL`, so all nodes flip
    together modulo clock skew instead of diverging for up to a sweep period, work item #8) — and
    `DigestTracker` (pure, JVM-tested) flags a peer *sync-wanted*
    when either side's digest changed since the last sync (an identical-digest pair skips the NDP entirely).
    Small floodable frames (broadcast chat, reactions, receipts, group-meta, profiles ≤255 B) *also* ride
    this plane as a best-effort **fast fan-out** (`fastFanout`/`fastSend`), deduped by the receiver's
    `SeenSet`, so they propagate with zero NDP. A cue also bootstraps the reverse handle, so a node whose
    own *subscribe* is broken (e.g. Pixel 9 post-kill) still cues larger peers to pull from it.
  - **Data plane** — one ephemeral NDP, brought up **only** when a peer is sync-wanted (the larger id
    initiates, via the unchanged `initiateTo` + accept-any responder). On link-up each side advertises the
    custody ids it holds (a `LinkFraming.Type.DIGEST` record) and pushes back only the frames the peer lacks
    (`ForwardSync.onDigest`, replacing the old push-all backfill), then the NDP is torn down on
    **quiescence** (no data for `QUIESCENCE_MS`, never mid-file) — freeing the NDI for the next pair. The
    initiator drives teardown and records the sync in `DigestTracker` (it alone consults it); the responder
    just sees the socket close, with a longer `RESPONDER_MAX_HOLD_MS` safety cap for a dead initiator.

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
  Samsung models) — but the **Bluetooth LE plane still meshes** on those devices, since
  `CompositeMeshTransport` merges whichever radios are present, so the UI shows the "unsupported" state
  only when *neither* Wi-Fi Aware nor BLE hardware exists (`hasWifiAwareHardware || hasBleHardware`, in
  onboarding).
- **One file streams at a time per socket.** `mesh/link/LinkFraming` (transport-neutral — the same codec
  runs over the Wi-Fi Aware NDP socket and the BLE L2CAP socket) multiplexes frames + files over one
  connected byte stream; the writer serializes file transfers and interleaves live frames *between* chunks
  (so an 8 MiB blob never stalls traffic), which is why a `FILE_HEADER`→`FILE_CHUNK`s→`FILE_END` run needs
  no file id. Don't push two files down one socket expecting them to interleave.
- **Wi-Fi Aware needs physical devices.** An emulator can't do NAN. Use `FakeLoopTransport` for logic
  tests and two physical Wi-Fi-Aware-capable phones (e.g. Pixels) for real discovery → data path → relay.
- **Forward `signed`/`sig` verbatim on relay — never re-encode them.** The wire is layered CBOR of opaque
  `@ByteString` blobs (`WireEnvelope.signed`/`sig`, `RelayEnvelope.payload`), **not** kotlinx sealed
  polymorphism, precisely so a relay rewrites only `ttl`/`hops` (`WireEnvelope.relayed()`) and passes
  `signed`+`sig` through byte-for-byte. Decoding `signed` to a `RelayEnvelope` and re-encoding it could
  legally reorder CBOR keys and break the originator's Ed25519 signature — the old "an old relay re-encodes
  and breaks the signature" bomb. Keep `RelayEnvelope.type` a plain `String` too (an unknown future type
  must *decode and relay*, not throw). `WireCodec` exposes `encodeWire`/`decodeWire` (WireEnvelope),
  `encodeEnvelope`/`decodeEnvelope` (RelayEnvelope), and `encodePayload`/`decodePayload<T>` (content) — see
  `docs/WIRE_COMPAT.md`.
- **After a version bump, regenerate the lockfile for ALL configurations, not just the ones your
  build resolves.** `app/build.gradle.kts` sets `dependencyLocking { lockAllConfigurations() }`, so
  `app/gradle.lockfile` pins every configuration. `--write-locks` only rewrites the configs a given
  task actually resolves: `:app:assembleDebug` + `:app:testDebugUnitTest` leave the *instrumented*-test
  configs (`debugAndroidTestCompileClasspath`, …) at their old locked versions. `./gradlew lint`
  (which the repo's stop-hook runs) builds the androidTest lint model, hits those stale locks, and
  fails with a wall of "Cannot find a version … {strictly <old>} … enforced by Dependency Locking"
  errors — which look like a resolution break but are just a half-updated lockfile. Always regenerate
  with `./gradlew :app:dependencies --write-locks` (resolves every configuration), then `./gradlew lint`.
- **A bounded custody quota must be *convergent*, or one chatty node churns the mesh forever.** The
  store-and-forward caps (`ForwardRepository`) bound how many carried frames a node holds per sender / group /
  broadcast room. The Wi-Fi Aware cue plane advertises a **content digest** — an XOR over the held frame-id set
  (`StoreDigest`) — and brings up a scarce NDP *only* when two peers' digests differ (`DigestTracker`'s
  identical-digest skip). So the quota and the digest are coupled: if a node can hold a frame set a *peer* can
  never match, their digests never converge and every larger peer re-attempts an NDP on every cue — **forever**.
  The original quota broke this two ways, and both had to be fixed (DB v18, `forward_store.sentAt`): (1) it
  applied **only to relayed traffic**, so an originator kept *all* its own sends (`ORIGIN_SELF`) while carriers
  capped at the quota — field-observed: node `a4gjrq5w` authored 117 custodial frames vs. the 100 per-sender
  quota, peers held 100, it held 117, so it out-diverged them permanently and the radio never idled (a slow
  drip of NDP setup/teardown + re-attach churn, `wanted` never emptying); and (2) it **refused the new frame**
  when full and evicted by local `receivedAt`/origin — both per-node, so even matched counts kept *different*
  sets. The fix: trim each over-quota bucket to its newest-N by the **frame-global `(sentAt, id)`** on **every**
  origin, so all nodes keep the identical set and the digests converge. A third breakage of the same rule was
  TTL expiry (work item #8): the digest folded **all** rows while a sync exchanges only live ones, so an
  expired-but-unswept row (sweep ticks phase per-node) opened a divergence window of up to a sweep period at
  every TTL boundary. Now **expired rows are invisible everywhere observable**: the digest folds live ids only
  (`StoreDigest.current()` folds lapsed ids out lazily at each read — no expiry timer for Doze to defer), the
  quota counts/evictions are live-filtered (buckets mix TTL classes, so an expired short-TTL row can be newer
  by `sentAt` than a live long-TTL row — counting it would evict a live row on the unswept node only), and a
  frame past its frame-global expiry is **refused at store time** (dead-on-arrival guard, which is also what
  stops a skewed-clock peer's re-serve resurrecting a swept frame); the sweep is pure storage GC and
  digest-neutral. Rule of thumb: **anything the content
  digest is folded over must be bounded by a rule that's identical on every node** (same key, same direction,
  same origins, same liveness) — which makes the **TTL constants (`DEFAULT_TTL_MS`/`DEFAULT_BROADCAST_TTL_MS`)
  and the broadcast-chat classification convergence-critical**: two app versions that disagree hold different
  live sets *continuously* for the whole TTL delta, so treat changing them like a wire change. Verify with
  `…debug.STORE`: **`liveFingerprint` must match across devices** (`digestVersion == liveFingerprint` is the
  local invariant; `allFingerprint` legitimately lags by expired residue until the sweep and is NOT
  fleet-comparable at a TTL boundary) and every sender's carried count must be ≤ its quota. (Aside: the
  per-sender bucket lumps a node's profile in with its chat, so a
  node that sends >quota frames evicts its own profile *frame* from custody — harmless, since the pinned key +
  connect-time `pushProfileTo` / edit re-broadcast are the real profile paths, and every node evicts it alike.)
- **Steady-state digest parity + BLE suppression means NAN has no NDP exactly when an image needs it —
  large attachments go through the bulk-want escape hatch, and it must never feed the recovery machinery.**
  With both radios up, BLE holds the link, the composite suppresses NAN's sync to that peer
  (`suppressDataPath`), and converged custody digests make `reconcileWanted` false — so
  `childHoldingLinkTo` only ever finds BLE and a naive "prefer NAN in `sendFile`" silently falls back
  ~always, leaving images to crawl over untuned L2CAP. The fix: `CompositeMeshTransport.sendFile`
  (ATTACHMENT only; any size rides an already-live NAN link, and one ≥ `BULK_MIN_BYTES` = 128 KiB also
  arms a bring-up — the gate compares the **transcoded wire blob**, not the user's source file: a 1-5 MB
  GIF lands at ~150-250 KB as a 480px q70 animated WebP, which is how the original 256 KiB gate quietly
  routed "large GIFs" over BLE) and `MeshManager.deliverChat` (at `blobExchange.want`)
  call `MeshTransport.expectBulkTransfer` on **both** sides of the pair — the requester marks the author,
  the serving side marks the requester; only the larger nodeId can initiate, so whichever side that is has
  a mark — which arms a TTL'd `BulkWantTracker` that `WifiAwareTransport.syncWanted` ORs in ahead of the
  suppression + digest gates. The split is load-bearing: the bulk term reaches ONLY the admission sites
  (`driveSync`/`initiateOwed`/`initiateOwedToReachable`), while `reachableSyncOwed` (the wedge watchdog's
  owed clock — Tier-2 is a process kill), `needsRediscovery` (subscribe re-arm churn is its own wedge
  trigger), `needsIcmRelight`, and `rediscoverDelayMs` read the digest-pure `digestSyncWanted` — a pending
  image always has the BLE fallback carrying it, so it is never an outage to "heal". Marks are gated on a
  fresh sighting (`BULK_FRESH_MS` 45 s, not the 150 s linger), fail-cooled 120 s on a failed initiate, and
  never bypass connect backoff / single-slot admission / SETTLE. The composite grace-waits ≤ 10 s for the
  NDP **off the inbound dispatch coroutine** (`onRequest`→`sendFile` runs inline in the router's single
  inbound collector — a suspension there stalls both radios) then falls back to the link holder;
  `sendFile` now returns enqueue-acceptance so a link that died in the check→enqueue window falls back
  instead of silently dropping the file, and `BlobExchange` keeps a per-(hash, peer) 45 s serve memo so
  the re-ask storm around a slow transfer (60 s re-offer, post-link-up `onNeighborAdded`) can't ship a
  second full copy (field-verified: the late-NDP re-ask after a BLE fallback is real, and the memo ate it).
  Frames, digests, avatars, and (when no NAN link is already up) sub-128 KiB blobs keep the BLE-first
  route byte-for-byte. Every routing decision logs `file route: <kind>/<key> <N>B → <peer> <choice+why>`
  (tag `CompositeMeshTransport`) and every arm accept/reject logs `bulk arm <peer> …` (tag
  `WifiAwareTransport`), so "why did this ride BLE" is one grep away; the `FramedLink`
  `file ATTACHMENT/<hash> <N>B in <ms>ms` line gives the per-plane timing, and `filesNan`/`filesBt`/
  `bulkTimeouts` ride `…debug.STATE`. `bulkTimeouts` climbing much faster than `filesNan` means ghosts are
  being armed.
- **The BLE scan is demand-gated, and a *settled* clique used to scan continuously.** `BluetoothMeshTransport.scanLoop`
  duty-cycles the scan, but `onScanResult` pokes the loop's wake channel on **every** sighting — *including
  already-linked peers* — and the loop consumes a buffered wake immediately, so whenever any peer is in range the
  idle gap collapsed to ~0 and the node scanned back-to-back **forever** (the `PowerPolicy` idle intervals only ever
  bit when *nothing* was nearby). The adaptive throttle (`ScanDemandPolicy`) fixes this by driving Boost/Floor from an
  explicit **demand** check and splitting a dedicated `scanWake` channel (only `scanLoop` drains it; `connectLoop`
  keeps `healSignal`) that `onScanResult` pokes **only for a genuine boost trigger** (a peer we'd initiate to, above
  the RSSI floor, unlinked, off backoff). Floor (`settledIdleAfterScan`, ~2 min) engages only with ≥1 link and no
  candidate/chase — an isolated node still scans aggressively — or while A2DP audio contends the radio. NAN acts as an
  **early-warning**: `CompositeMeshTransport.onForeignReachable` (the reverse of `suppressDataPath`) tells BLE which
  peers another plane can see, and BLE boosts to chase them onto a link, bounded by `PROMOTE_CHASE_MS` so a NAN-only /
  out-of-range peer can't pin Boost. Advertising is untouched (always-on) so BLE-only devices still discover us.
  **Load-bearing invariant: `reachable ⊇ neighbors`.** BLE `reachable` is fed only from scan presence (90 s linger), so
  once the floor stops re-sighting a linked peer it would vanish from the "nearby" UI while still linked — `publishReachable`
  unions live links back in (`_reachable` only, never `_neighbors`, which routes sends). Verify on-device via the
  `bt scan → floor/boost` logcat lines and that a linked peer stays in `…debug.STATE` reachable while the scan is floored.
- **Multi-step data-layer mutations must be transactional — the "single writer" was a myth.** The mesh
  serializes *inbound* frames through one `MeshRouter` `inbound.collect`, but the same repos are also written off
  that path — UI actions on `viewModelScope`, `NotificationActionReceiver` on the app mesh scope, and
  session-scope loops (the 10-min custody prune, `watch*`, `heal`) — all on multi-threaded dispatchers, so a
  read-then-write or two-table write **can** interleave. Any such mutation runs in **`db.withTransaction { }` at
  the repository layer** (the `GroupRepository.recordDeparture` idiom; there are **no** DAO `@Transaction` methods —
  keep DAOs thin). `withTransaction` issues `BEGIN EXCLUSIVE`, so a second transaction's `SELECT` can't run until
  the first commits — that alone closes the check-then-act cases (`ReactionRepository.apply`'s LWW,
  `ForwardRepository`'s count→evict, `GroupRepository.leave/delete`, `BlobRepository.deleteIfUnreferenced`) and the
  UI-`leave`-vs-`InboundPipeline.reconcileGroup` group-resurrection race (both sides must be transactional, or the
  blind roster upsert re-creates a just-left group). **Two things a Room transaction can't cover:** (1) in-memory
  state that must stay in lockstep with the committed rows — `ForwardRepository`'s shared `StoreDigest` — also needs
  a repo-level **`Mutex`, held *outer* to `withTransaction`** (inner deadlocks on SQLCipher's single connection),
  with the digest updated **after** commit under that lock; (2) a **DataStore** read (own-avatar hash, blocked-ids)
  can't enroll, so hoist it **before** the transaction. A blob GC racing an *independent* inserter is only narrowed,
  not closed — the content-addressed blob self-heals via a `BlobExchange` re-pull. Finding #13 in
  `docs/ARCHITECTURE_REVIEW.md`.

## Verifying changes

1. `./gradlew :app:testDebugUnitTest` for mesh/protocol/data logic — now including **Robolectric +
   in-memory Room** tests that execute the real DAO SQL (see below).
2. Emulator smoke test for UI/startup (launch, Koin init, screen rendering, no crash) — the app
   runs fine on an emulator, it just can't form a real mesh there.
3. Two physical phones for discovery → connect → relay and profile/avatar exchange.

### JVM Room/DAO + migration tests (Robolectric)

`app/src/test/java/app/getknit/knit/data/` runs the **real** DAO SQL — the eviction/orphan/GC queries the
`FakeForwardDao`/`FakeReactionDao` only *mirror* (finding #5 in `docs/ARCHITECTURE_REVIEW.md`) — on the JVM
under Robolectric 4.16, plus a `MigrationTestHelper` harness. They run inside the normal
`:app:testDebugUnitTest` (and CI `test:unit`), no device. The wiring is non-obvious and load-bearing — read
before "simplifying":

- **The in-memory test DB skips SQLCipher.** `RoomDbTest` builds via `Room.inMemoryDatabaseBuilder(...)` with
  **no** `openHelperFactory`, so it uses Robolectric's framework SQLite — no passphrase, no `libsqlcipher.so`.
  The eviction/GC SQL runs identically (SQLCipher only encrypts at rest). Never call `KnitDatabase.build()` in
  a test. Call `suspend` DAO methods inside `runTest { }`.
- **`robolectric.properties` forces `application=android.app.Application`.** The real `KnitApplication.onCreate`
  starts Koin, whose static `GlobalContext` isn't reset between tests → `KoinApplicationAlreadyStartedException`
  on the 2nd test. DAO tests bypass Koin, so a plain Application is correct; `sdk=36` matches compileSdk.
- **`exportSchema = true`** on `KnitDatabase` + `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`
  (plugin-free — no Room Gradle plugin) emit `app/schemas/app.getknit.knit.data.KnitDatabase/<version>.json`
  (checked in). Regenerate by building after any `@Database` version bump.
- **`MigrationTestHelper` reads the schema from *debug*-variant assets** —
  `sourceSets["debug"].assets.srcDir("schemas")` in `app/build.gradle.kts`. Robolectric serves the merged
  **debug** assets (unit tests run against debug) but **not** the `test` source set's own assets; release APKs
  never carry the schema. It uses **`AndroidSQLiteDriver`** (the Robolectric-shadowed engine) — the
  connection-returning API needs a `SQLiteDriver`, and `BundledSQLiteDriver` can't load its Android native
  `.so` on the host JVM. There are no `Migration`s yet (destructive fallback), so it's a schema-export + drift
  harness with a filled-in template for the first real migration.
- After adding a test dep, **regenerate the lockfile** (`:app:dependencies --write-locks`, all configs) — see
  the lockfile gotcha above.

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
  logged one-line under tag `KnitBridge` (`adb logcat -d -s KnitBridge:I`). **A new action must be added in
  *two* places** — the `when` in `DebugBridgeReceiver` *and* the `<intent-filter>` in `app/src/debug/AndroidManifest.xml`;
  a package-targeted broadcast for an action missing from the filter is silently not delivered (the receiver
  never runs, and you get `Broadcast completed: result=0` with no `data=` and nothing under `KnitBridge`). Actions:
  - `…debug.SEND` — `--es text <body>` + a target: `--es conv <id>` (`nearby` room, a peer node id for a
    DM, or a `g-…` group id) or `--es to <peerNodeId>` (DM shorthand). No target ⇒ broadcast room. Text is
    passed verbatim — spaces/emoji survive (unlike `adb shell input text`) **provided you quote for the
    on-device shell**: `adb` re-parses the command on the device, so a bare `--es text "hi there"` is
    word-split and truncated to `hi`. Wrap the whole remote command in double quotes and single-quote the
    value (see the example).
  - `…debug.SENDIMG` — sends a real **image attachment** with no UI (a locked device can't drive the photo
    picker): `--es path <file the app can read>` plus the same `conv`/`to` targeting as SEND and optional
    `--es text`. Stage the file into the app's own storage first (scoped storage — the app can't read
    /sdcard paths): `adb push img.jpg /data/local/tmp/ && adb shell "cat /data/local/tmp/img.jpg | run-as
    app.getknit.knit sh -c 'cat > files/img.jpg'"`, then pass `--es path /data/data/app.getknit.knit/files/img.jpg`.
    Runs the production pipeline (AttachmentStore.ingest → sendChat), and the reply carries the attachment
    `hash` to poll for on receivers.
  - `…debug.STATE` — self id/name, transport health, reachable peers, and mesh metrics. Add `--es conv <id>`
    to also dump that thread's latest messages (`--ei limit N`, default 20), each with its `received`
    delivery tick — this is how you **verify receipt on the other device without a screenshot**.
  - `…debug.STORE` — dumps the store-and-forward carry set (the **live** rows are the id set the cue-plane
    content digest is folded over; expired-unswept rows are digest/quota/serve-invisible residue awaiting the
    sweep), for diagnosing why two nodes never converge their digests (the churn from a carried-set delta):
    `digestVersion` (what the transport actually cues, read via the same lazy-folding `StoreDigest.current()`),
    `allFingerprint`/`liveFingerprint` (the digest recomputed over all rows vs. non-expired rows — the
    invariant is **`digestVersion == liveFingerprint`, always**; a mismatch is an in-memory-digest drift bug,
    while `allFingerprint` legitimately lags by the expired residue until the sweep), `counts`,
    `expiredIds`, the full `allIds`, and capped per-row detail (`--ei limit N`, default 100). Diff `allIds`
    across devices to find the stranded frame(s): `… STORE | sed -n 's/.*data="//;s/"$//p' | jq -r '.allIds[]'
    | sort` per device, then `comm`/`diff` the files. **`liveFingerprint` matching across devices = converged**
    (`allFingerprint` is NOT fleet-comparable at a TTL boundary — soak oracles must compare `liveFingerprint`).
  - `…debug.REACT` — `--es id <messageId> --es emoji <emoji>`. `…debug.HEAL` — nudge rescan/re-advertise.
  ```
  # send on A, then confirm it landed on B — no UI, no screenshots. Outer quotes matter: adb re-parses
  # on the device, so quote the whole command and single-quote the text (a bare --es text is word-split).
  adb -s A shell "am broadcast -a app.getknit.knit.debug.SEND  -p app.getknit.knit --es text 'hi there 😀' --es conv nearby"
  adb -s B shell  am broadcast -a app.getknit.knit.debug.STATE -p app.getknit.knit --es conv nearby
  # → data="{…,"messages":[{"from":"<A>","body":"hi there 😀","received":…}]}"
  ```
- **Stable resource-ids** — the root sets `testTagsAsResourceId` (in `KnitApp`), so `Modifier.testTag`s
  surface in `uiautomator dump` as `resource-id="<tag>"` (the bare tag — some Android/uiautomator versions
  prefix it `app.getknit.knit:id/<tag>`, so a matcher should accept either; see `tap_by_resid` in
  `scripts/screenshots.sh`). Tagged so far: `chat_input`,
  `chat_send`, `chat_row_<conversationId>` (e.g. `chat_row_nearby`), `chatlist_fab`, `contacts_fab`,
  `contact_<nodeId>`, `onboarding_grant`, `onboarding_start`, `profile_name`, `profile_status`,
  `profile_save`, `chat_group_avatar` (opens group details). Use these when you must drive the real UI;
  add more with the same snake_case, screen-prefixed convention.
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
for **every floodable frame** — chat (1:1 DMs, group messages, and the plaintext broadcast room) plus the
small metadata frames (`reaction`/`receipt`/`groupupdate`/`groupleave`/`profile`) — so two phones that meet
only briefly backfill each other's ambient history (the festival case). The subsections below detail the
chat cases (the delivery-critical ones); the metadata types ride the same custody path (`FrameType.isCustodial`). A node persists the
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
`markReceived` (fixing a prior tick-spoof where any signed receipt flipped the ✓✓). A DM receipt **floods**
(`originateSigned`, `relay = true`) so it reaches the sender across hops *and* is custodied like any flood
frame — delay-tolerant for free.

**Delivery ticks for broadcast/group** have no single recipient, so their receipt is *not* flooded/custodied
(an ack storm + custody bloat): it's a **unicast, point-to-point (`relay = false`) tick** the deliverer sends
straight to the author. That one-shot best-effort send was lost whenever the author was out of range at
delivery time — the *message* converged via custody but the tick did not, leaving the author's ✓✓ missing
"even after convergence". `AckSync` makes it delay-tolerant **without** flooding: the deliverer remembers the
ticks it owes and re-sends them (over a live link when the author is a neighbor → reliable + dropped;
best-effort over the coordination plane otherwise → kept) on every `onNeighborAdded`/`heal` until one lands
or the entry ages out (24 h TTL, bounded, in-memory, self-repopulating on message re-serve like
`KeyExchange`). One surviving receipt flips the ✓✓ ("**≥1 person received it**" — the intended broadcast
semantic); `markReceived` and `ForwardSync.onAck` are idempotent/no-op for these, so duplicate retries are
harmless and never evict custody. Surfaced in Diagnostics/`…debug.STATE` as `receiptsResent`; JVM-tested
(`AckSyncTest`).

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
**broadcast quota** are their only bound; they are never vaccine-purged. `isStorable()` = `FrameType.isCustodial`:
**every floodable type**, not just `chat` — the `isReplayable` family (`chat`/`reaction`/`receipt`/`groupupdate`/`groupleave`)
plus `profile` — so the whole mesh converges on the same state (reactions, receipts, renames, and keys included),
not only the one-hop peers that happened to be present when each first flooded.

Bounds (`ForwardRepository`): a per-message **TTL** sweep (startup + a 10-min loop + the heartbeat
`heal()`; broadcast gets a shorter TTL than DMs/groups), a **global cap**, a **per-sender quota**, a
**per-group quota**, and a **broadcast quota** — each enforced by evicting its **oldest frame by `sentAt`**
(a frame-global key, so every node keeps the identical newest-N and their content digests converge) rather than
refusing the new one, and applied to **our own sends too** (not just relayed traffic). Ordering by a per-node
key (`receivedAt`) or exempting `ORIGIN_SELF` breaks convergence and churns the cue plane forever — see the
convergent-custody-quota gotcha above. A carrier stores a message only when its sender is **pinned, not blocked,
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
(`onNeighborAdded`) and on `heal()`; the in-memory bookkeeping repopulates as profiles re-arrive.
Because `want`/`onRequest` are keyed by **unauthenticated** senderIds (a peer can flood forged ones), that
bookkeeping is **bounded** exactly like `PendingInbound`: `missing` and `wanters` are capped with
oldest-first eviction and `missing` is TTL-swept (`sweepExpired`, on the `heal()`/prune ticks); an outbound
batch is **chunked** (`MAX_IDS_PER_REQ`) so it can never exceed the link's 512 KiB payload ceiling and crash
the writer coroutine; and an inbound request's id list is **capped** (`MAX_REQUEST_IDS`) so it can't drive
unbounded recursion. `BlobExchange` (whose `blobreq` is unsigned) bounds its `fetching`/`wanters`/serve-memo
the same way. Backstopping all of it, both mesh scopes (the app-lifetime scope in `di/MeshModule.kt` and
`MeshManager`'s session scope) carry a shared `meshExceptionHandler`, and a `FramedLink` writer drops (never
dies on) a record the codec rejects. Recovery is visible in Diagnostics
(`keyRequestsSent`/`keysServed`/`keysRecovered`) and JVM-tested with `FakeLoopTransport` (`KeyExchangeTest`). `handleProfile` also gained a last-writer-wins `sentAt` guard so
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

Because custody now carries our **own** frames too, `verifyInbound` short-circuits any frame whose `senderId`
is our own nodeId — a silent local no-op drop *before* the `NO_SENDER_KEY` path. A neighbor re-serves a carried
copy of a `chat`/`reaction` we originated, and its re-flood reaches us again once our `SeenSet` window has
lapsed; since a node never pins its **own** key in `peers` (`handleProfile` only upserts inbound senders), that
copy would otherwise be counted as `NO_SENDER_KEY`, parked in `PendingInbound` until its TTL (no self-profile
ever arrives to release it), and trigger a `keyExchange.want(self)` no-op — pure noise for a message we already
delivered at origination. We drop it silently; the router still relays it and neighbors dedup it one hop out.
(Field-observed 2026-07-02, idle 3-node mesh: a slow drip of `drop chat/reaction … no key to verify it` from
the node's *own* id, with `keyReq` stuck at 0 — the `KeyExchange.want` self-guard — and `framesHeld` climbing.)

## Out of scope (deferred, by design)

> **The Bluetooth LE plane is implemented** (`mesh/bluetooth/`) and runs *simultaneously* with Wi-Fi Aware
> behind `CompositeMeshTransport` (wired in `di/MeshModule.kt`): BLE advertise/scan presence + persistent
> L2CAP CoC data links, *preferred* over NAN's ephemeral NDP, with per-peer escalating connect backoff and
> A2DP-audio instrumentation. It is a co-plane, **not** a fallback, and BLE-capable devices use it
> regardless of Wi-Fi Aware support. The **digest/pull anti-entropy** exchange that once appeared here as
> deferred is also implemented — see the cue-plane `StoreDigest`/`DigestTracker` + the data-path
> `LinkFraming.Type.DIGEST` id-diff (`docs/DIGEST_PULL_REATTACH.md`).

Still deferred (by design): a **BLE promotion gate on A2DP audio** — the adaptive scan throttle now drops the
**scan** to its floor while streaming (`ScanDemandPolicy` / the demand-gated `scanLoop`), but **connects** are still
not gated on `contended` (it remains diagnostic-only for the connect path); **true DM routing** (DMs still
flood — only the addressed recipient delivers/acks; store-and-forward now *carries* undelivered DMs, see
above, but there is still no routing table); a **group key-gap retransmit** (the group analogue of the DM
`flushPendingFor`: a group message already floods to the members whose keys are known, so reaching a
member whose key arrives *later* needs a fresh re-seal, not custody); and for E2E specifically:
**forward secrecy / a ratchet** (static keys only), **encrypting** reactions/receipts (they are signed
now — see the E2E section — but still flood as cleartext metadata), and encrypting the broadcast room.
(The **inbound key-request** for a frame received from a not-yet-pinned sender — the inbound complement of
retransmit-on-key-arrival — is now implemented; see `KeyExchange` above.)
Don't start these without explicit direction.
