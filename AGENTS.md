# AGENTS.md — Knit

Instructions for coding agents and contributors working in this repo. Read this before changing
build config, the mesh layer, or the DI graph. For full design detail see
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## What this is

An Android app (Kotlin/Compose) implementing an offline Bluetooth/Wi-Fi **mesh messenger** on top of
Google Nearby Connections. Single Gradle module `:app`, package `app.getknit.knit`,
minSdk 29 / targetSdk 36 / compileSdk 36.1. It surfaces a "Nearby" broadcast room plus 1:1 DMs,
with profiles, emoji reactions, @-mentions, and content-addressed image attachments.

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
               · BlobExchange/BlobStore (content-addressed pull) · protocol/Wire.kt (CBOR Frame)
mesh/crypto/   E2E (Tink): MessageCrypto (per-msg seal/open) · PublicKeyBundle · MessageContent
               · AttachmentCrypto · SafetyNumber · VerifyPayload (pure, JVM-testable)
mesh/nearby/   NearbyTransport — the ONLY place that imports com.google.android.gms.*
data/          Room (messages, peers, reactions) + repositories · settings/SettingsStore (DataStore)
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

- **Keep GMS/Nearby behind `MeshTransport`.** Nothing outside `mesh/nearby/` should import
  `com.google.android.gms.*`. New transports (Wi-Fi Aware, BLE) go in sibling packages implementing
  the same interface.
- **DI:** declare singletons/ViewModels in the `di/` modules; resolve ViewModels in Compose with
  `org.koin.androidx.compose.koinViewModel()` and the ViewModel DSL from
  `org.koin.core.module.dsl.viewModel` (not the deprecated `androidx.viewmodel.dsl` one).
- **Wire format** (`mesh/protocol/Wire.kt`) is a `@Serializable sealed interface Frame` serialized as
  **binary CBOR** (`WireCodec`, `encodeDefaults = false`) — not JSON. New frame types are added as
  sealed subclasses with a `@SerialName` (current set: `chat`, `groupupdate`, `profile`, `receipt`,
  `reaction`, `blobreq`); a non-flooded control frame overrides `relayable` to `false`.
  `ChatFrame.recipientId`/`group` address DMs/groups (cleartext, for routing); `ChatFrame.enc` +
  `sig` carry the E2E envelope + Ed25519 signature and `ProfileFrame.pubKey` the public-key bundle —
  all now in use (see the E2E section below). Changing the encoding or a frame's shape is a coordinated
  wire-format break (no version negotiation yet).
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
- **Serialize Nearby `requestConnection` calls** and never self-join. A previous bug
  (`connectJob = scope.launch { connectJob?.join(); … }`) made the coroutine await itself and
  deadlock, so connections never formed. Requests now run on a single-thread dispatcher with
  `await()` — see `NearbyTransport.connectTo`.
- **Nearby needs physical devices.** An emulator generally can't mesh with a real phone (NAT'd
  network). Use `FakeLoopTransport` for logic tests and two physical phones for connectivity.
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

## End-to-end encryption (implemented)

DMs and group chats are E2E-encrypted; the broadcast "Nearby" room stays plaintext by design (no fixed
recipient set). Scheme (static keys, no ratchet): a per-message random content key AES-256-GCM-encrypts
the `MessageContent` (body + mentions + attachment refs) into `ChatFrame.enc`, the content key is
wrapped (Tink HPKE/X25519) to each recipient, and the envelope is Ed25519-signed into `ChatFrame.sig`.
Identity keypairs live in `IdentityKeyStore` (AndroidKeyStore-wrapped, **outside** the destructively-
migrated DB), advertised via `ProfileFrame.pubKey`, pinned TOFU into `PeerEntity.pubKey`, and confirmed
out of band via the safety-number/QR screen (`PeerEntity.verified`). Image attachments are encrypted to
a per-attachment key and content-addressed by ciphertext hash, so `BlobExchange`/`BlobStore` are
unchanged. **Decrypt/verify failures must never throw out of the inbound handler** — `onDeliver` runs
before the router schedules the relay, so a throw would stop forwarding (see `MeshManager.decryptAndDeliver`).

## Out of scope (deferred, by design)

Alternate transports (Wi-Fi Aware/BLE), **true DM routing** (DMs currently flood — only the addressed
recipient delivers/acks), and for E2E specifically: **forward secrecy / a ratchet** (static keys only),
encrypting **reactions/receipts** (they flood as cleartext metadata), encrypting the broadcast room, and
a **key-request/retransmit** protocol for messages received before the sender's key is known. Don't
start these without explicit direction.
