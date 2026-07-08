# Architecture

Orientation for the Knit codebase. For full design detail see [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md).

## What this is

An Android app (Kotlin/Compose) implementing an offline **mesh messenger** that runs two radios at once —
**Wi-Fi Aware (NAN)** and **Bluetooth LE** — behind a single `MeshTransport` seam
(`CompositeMeshTransport`). Direct `android.net.wifi.aware.*` / `android.bluetooth.*` implementations, no
Google Nearby / GMS. Single Gradle module `:app`, package `app.getknit.knit`, minSdk 29 / targetSdk 36 /
compileSdk 36.1 (minSdk 29 is the shared data-path floor: BLE L2CAP CoC and the Wi-Fi Aware NDP
`WifiAwareNetworkSpecifier.Builder` are both API 29. Wi-Fi Aware uses Instant Communication Mode +
`NEARBY_WIFI_DEVICES`/`neverForLocation` on 33+ and falls back to `ACCESS_FINE_LOCATION` (location-scoped,
no ICM, `maxSdkVersion=32`) on 29–32; BLE uses the split `BLUETOOTH_*` perms on 31+ and legacy
`BLUETOOTH`/`BLUETOOTH_ADMIN` on 29–30 — a device with only one of the two radios still meshes over that
one). It surfaces a "Nearby" broadcast room plus 1:1 DMs and group chats, with profiles, emoji reactions,
@-mentions, content-addressed image attachments, store-and-forward custody, and on-device content
moderation.

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

## Where to read more

- Subsystem deep-dives: `context/mesh-transport.md` (radios), `context/wire-format.md` (CBOR wire),
  `context/store-and-forward.md` (custody), `context/e2e-encryption.md` (crypto),
  `context/toolchain.md` (build).
- Design docs under `docs/`: `ARCHITECTURE.md`, `ARCHITECTURE_REVIEW.md`, `WIRE_COMPAT.md`,
  `NAN_CONCURRENCY_REAUDIT.md`, `DIGEST_PULL_REATTACH.md`, `CONTENT_MODERATION.md`.
