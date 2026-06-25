# Knit

Offline, serverless, encrypted-in-transit messaging between nearby phones.

Knit forms an ad-hoc **mesh** over Bluetooth and Wi-Fi (via Google Nearby Connections): when you
send a message, it's transmitted to every device in range, each of which re-transmits it onward, so
messages can "leap-frog" hop-by-hop across many devices with no internet, cell service, accounts, or
servers. Duplicate copies are discarded. The interface is a modern Signal-style messenger.

> **Status:** MVP. The current build has a **"Nearby" public broadcast room** and **1:1 direct
> messages**, with profiles (name / status / avatar), emoji reactions, @-mentions, and image
> attachments. Encryption is currently **transport-level only** (Nearby's per-link encryption) — DMs
> flood the mesh today, so they aren't yet confidential from relays. End-to-end encryption is
> designed-in but not yet implemented — see [Roadmap](#roadmap).

## Features

- **Mesh relay** over Nearby Connections (`P2P_CLUSTER`): advertise + discover, auto-connect to all
  nearby peers, flood messages with hop-count/TTL bounds and deduplication. Relays use **jittered,
  overhear-suppressed** flooding so a dense cluster isn't stormed with redundant rebroadcasts.
- **Public broadcast chat** plus **1:1 direct messages** — a conversation list and contact picker,
  message bubbles, relative timestamps, unread badges, and a delivery tick (✓).
- **Reactions, @-mentions, and image attachments** — emoji reactions converge across the mesh
  (last-writer-wins); mentions get a dedicated notification; images (GIF/JPEG/PNG/WebP) are
  content-addressed and pulled on demand so the bytes don't ride the flood.
- **Profiles** — display name, status, and avatar — flooded across the mesh; avatars transferred as
  files (and re-pushed only when they actually change) and shown next to messages.
- **Messaging-style notifications** with a separate channel for mentions.
- **Always-on background mesh** via a foreground service, kept healthy by a heartbeat alarm,
  significant-motion re-scan, and Bluetooth-recovery; prompts to disable battery optimization.
- **Material 3** UI with a coral brand theme and full dark mode.

## Requirements

- **Android 10 (API 29) or newer**, with **Google Play services** (Nearby Connections depends on it).
- **JDK 21** and the **Android SDK** (compileSdk 36.1) to build.
- Real mesh testing needs **two or more physical devices** — see [Running](#running).

## Tech stack

Kotlin 2.2.10 · Jetpack Compose (Material 3) + Navigation Compose · AGP 9.2.1 / Gradle 9.4.1 ·
Koin (DI) · Room · DataStore · kotlinx.serialization (**CBOR** wire format) · Coil 3 ·
`play-services-nearby`.

## Build

```bash
./gradlew :app:assembleDebug          # build the debug APK
./gradlew :app:testDebugUnitTest      # run JVM unit tests (mesh router + flood suppression, dedup, CBOR codec, …)
./gradlew installDebug                # install on a connected device
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Running

Nearby Connections drives Bluetooth/Wi-Fi radios, so the mesh needs **physical devices**:

1. Install the debug build on **two (or more) phones** that have Google Play services.
2. Launch on each, grant the requested permissions, and (recommended) allow background battery use.
3. Within range, the devices auto-discover and connect ("Connected to N mesh nodes" appears).
4. Send a message on one — it appears on the others; a third device out of direct range receives it
   relayed through the middle device.

The app launches and the UI works on an emulator, but an emulator generally **cannot** mesh with a
physical phone (its network is NAT'd), so use real devices for connectivity testing.

## Documentation

- [`AGENTS.md`](AGENTS.md) — build/test commands, environment constraints, and conventions for
  contributors and coding agents.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — detailed design: mesh algorithm, wire protocol,
  data flow, background survival, build-tooling decisions, and testing strategy.

## Roadmap

Implemented and verified through the MVP (broadcast room, 1:1 DMs, profiles, reactions, mentions,
attachments). Explicitly deferred:

- **Alternate transports** (Wi-Fi Aware / BLE) behind the existing `MeshTransport` abstraction —
  removes the Google Play services dependency.
- **True DM routing** — DMs currently flood the whole mesh and only the addressed recipient delivers/
  acks them; targeted multi-hop routing is future work.
- **End-to-end encryption + identity verification** — wire format reserves `sig` / `pubKey`; today
  relays can read message contents, including DMs (transport-only encryption).
- **At-rest database encryption** (SQLCipher).

## Security note

In-transit confidentiality currently relies on Nearby Connections' per-link encryption. Because
messages are flooded and re-encrypted at each hop, **relay devices can read message contents** — and
this includes 1:1 DMs, which flood the mesh today — and sender identity is not cryptographically
authenticated. Do not treat the current build as end-to-end secure. E2E is the top item on the
roadmap.
