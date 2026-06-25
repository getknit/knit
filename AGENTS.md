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
```

- **JDK 21** is required (the Gradle daemon toolchain is pinned to 21).
- After changing the `MeshTransport` interface, run `testDebugUnitTest` — a test double
  (`RecordingTransport` in `MeshRouterTest`) implements that interface and won't be caught by
  `assembleDebug`.

## Bleeding-edge toolchain constraints (do not "fix" these without reading why)

This project intentionally runs on very new tooling (AGP 9.2.1, Gradle 9.4.1, Kotlin 2.2.10,
Compose BOM 2026.02). That forces several non-obvious choices:

- **DI is Koin, not Hilt.** Hilt's Gradle plugin is broken on AGP 9.x in this window
  (dagger#5083 / #5099). Koin is pure-Kotlin runtime DI with no Gradle plugin / no annotation
  processor, so it can't be broken by AGP. Koin is started in `KnitApplication`; modules live in
  `app/src/main/java/app/getknit/knit/di/`.
- **Coil is pinned to 3.3.0.** Coil 3.4.0+ is compiled with Kotlin 2.4.0, whose class metadata the
  AGP-9.2.1-bundled Kotlin 2.2 compiler cannot read. Any dependency built with Kotlin > 2.3 will
  fail to compile here — prefer older releases or check before bumping.
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
mesh/nearby/   NearbyTransport — the ONLY place that imports com.google.android.gms.*
data/          Room (messages, peers, reactions) + repositories · settings/SettingsStore (DataStore)
               · AvatarStore + AttachmentStore (image files) · message/Conversations (DM keys)
identity/      Identity (stable nodeId) · NodeId (derive) · DeviceIdSource · Alias (name fallback)
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
  sealed subclasses with a `@SerialName` (current set: `chat`, `profile`, `receipt`, `reaction`,
  `blobreq`); a non-flooded control frame overrides `relayable` to `false`. `ChatFrame.recipientId`
  addresses DMs; `sig` / `pubKey` are reserved for future E2E — keep them. Changing the encoding or a
  frame's shape is a coordinated wire-format break (no version negotiation yet).
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

## Verifying changes

1. `./gradlew :app:testDebugUnitTest` for mesh/protocol/data logic.
2. Emulator smoke test for UI/startup (launch, Koin init, screen rendering, no crash) — the app
   runs fine on an emulator, it just can't form a real mesh there.
3. Two physical phones for discovery → connect → relay and profile/avatar exchange.

When driving the emulator over `adb`: the soft keyboard overlaps via `adjustResize`, so read element
coordinates from `uiautomator dump` rather than guessing; seed the photo picker by `screencap`-ing
into `/sdcard/Pictures` if you need an image to select.

## Out of scope (deferred, by design)

Alternate transports (Wi-Fi Aware/BLE), **true DM routing** (DMs currently flood — only the addressed
recipient delivers/acks), app-level end-to-end encryption + identity verification, and at-rest DB
encryption (SQLCipher). Don't start these without explicit direction; the wire format
(`recipientId`/`sig`/`pubKey`) and identity layer already leave room for them.
