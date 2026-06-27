# Knit

Offline, serverless messaging between nearby phones — with **end-to-end encrypted** direct and group
chats.

Knit forms an ad-hoc **mesh** over Bluetooth and Wi-Fi (via Google Nearby Connections): when you
send a message, it's transmitted to every device in range, each of which re-transmits it onward, so
messages can "leap-frog" hop-by-hop across many devices with no internet, cell service, accounts, or
servers. Duplicate copies are discarded. The interface is a modern Signal-style messenger.

> **Status:** MVP. The current build has a **"Nearby" public broadcast room**, **1:1 direct
> messages**, and **multi-member group chats**, with profiles (name / status / avatar), emoji
> reactions, @-mentions, and image attachments. **Direct and group messages are end-to-end
> encrypted** — bodies, mentions, and image attachments are readable only by their intended
> recipients, even though every message floods through relay devices. The public Nearby room stays
> plaintext by design (no fixed recipient set). See the [Security note](#security-note).

## Features

- **Mesh relay** over Nearby Connections (`P2P_CLUSTER`): advertise + discover, auto-connect to all
  nearby peers, flood messages with hop-count/TTL bounds and deduplication. Relays use **jittered,
  overhear-suppressed** flooding so a dense cluster isn't stormed with redundant rebroadcasts.
- **Public broadcast chat**, **1:1 direct messages**, and **multi-member group chats** — a
  conversation list and contact picker, message bubbles, relative timestamps, unread badges, and a
  delivery tick (✓).
- **End-to-end encryption** for DMs and group chats — each message's body, mentions, and image
  attachment are sealed with a per-message key (AES-256-GCM) wrapped to each recipient (Tink
  HPKE/X25519) and authenticated with an Ed25519 signature, so relays only ever carry ciphertext.
  Identity keypairs are hardware-backed (AndroidKeyStore), advertised in profiles, pinned on first
  use, and confirmable out of band via a **safety number / QR-code** verification screen.
- **Reactions, @-mentions, and image attachments** — emoji reactions converge across the mesh
  (last-writer-wins); mentions get a dedicated notification; images (GIF/JPEG/PNG/WebP) are
  content-addressed and pulled on demand so the bytes don't ride the flood (encrypted attachments are
  addressed by their ciphertext hash, so dedup/pull is unchanged).
- **Profiles** — display name, status, and avatar — flooded across the mesh; avatars transferred as
  files (and re-pushed only when they actually change) and shown next to messages.
- **On-device content moderation** — abusive-text and explicit-image (NSFW) filtering that runs
  **entirely offline** (no network, no server): a deterministic profanity filter plus a bundled TFLite
  NSFW image classifier. Abusive **text is blocked on send**; explicit **images** are blocked outright
  in the public Nearby room and **allowed-but-discouraged via a "send anyway?" confirmation** in
  DMs/groups; both are **collapsed/blurred behind tap-to-reveal on receive** (receiver-side enforcement
  is what actually protects a user in a mesh), with one-tap block-sender and a user toggle. See
  [`docs/CONTENT_MODERATION.md`](docs/CONTENT_MODERATION.md).
- **Messaging-style notifications** with a separate channel for mentions.
- **Always-on background mesh** via a foreground service, kept healthy by a heartbeat alarm,
  significant-motion re-scan, and Bluetooth-recovery; prompts to disable battery optimization.
- **Material 3** UI with a coral brand theme and full dark mode.

## Requirements

- **Android 10 (API 29) or newer**, with **Google Play services** (Nearby Connections depends on it).
- **JDK 21** and the **Android SDK** (compileSdk 36.1) to build.
- Real mesh testing needs **two or more physical devices** — see [Running](#running).

## Tech stack

Kotlin 2.4.0 · Jetpack Compose (Material 3) + Navigation Compose · AGP 9.2.1 / Gradle 9.4.1 ·
Koin (DI) · Room + SQLCipher (encrypted at rest) · DataStore · kotlinx.serialization (**CBOR** wire
format) · Coil 3 · **Google Tink** (end-to-end crypto) · **ZXing** (QR verification) · TensorFlow Lite
(on-device NSFW image classifier) · `play-services-nearby`.

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
- [`docs/CONTENT_MODERATION.md`](docs/CONTENT_MODERATION.md) — on-device abusive-text / explicit-image
  moderation: design, hook points, the bundled NSFW model, Git LFS, and the deferred ML-toxicity plan.

## Roadmap

Implemented and verified: broadcast room, 1:1 DMs, multi-member group chats, profiles, reactions,
mentions, attachments, at-rest database encryption (SQLCipher), and **end-to-end encryption +
identity verification** for DMs and group chats. Explicitly deferred:

- **Alternate transports** (Wi-Fi Aware / BLE) behind the existing `MeshTransport` abstraction —
  removes the Google Play services dependency.
- **True DM routing** — DMs currently flood the whole mesh and only the addressed recipient delivers/
  acks them; targeted multi-hop routing is future work.
- **Forward secrecy** — E2E uses long-term static identity keys (no ratchet), so compromise of a
  device's identity key would expose past intercepted messages.
- **Encrypting reactions, receipts, and the broadcast room** — these remain cleartext metadata, and
  a **key-request/retransmit** path for messages received before a sender's key is known.

## Security note

**Direct and group messages are end-to-end encrypted.** Each message's content (body, mentions, and
image attachment) is sealed with a fresh per-message key (AES-256-GCM); that key is wrapped to each
recipient with hybrid public-key encryption (Tink HPKE/X25519) and the whole envelope is signed with
the sender's Ed25519 key. Relays — which flood every message hop-by-hop — only ever see ciphertext,
and only the addressed recipient(s) can decrypt. Each device's identity keypair is generated on first
run and stored wrapped under a hardware-backed AndroidKeyStore key, outside the database.

Peers' keys are pinned on first use (TOFU); if a peer's key later changes, verification is reset and
flagged. You can confirm a contact's key out of band — compare the **safety number** in person or
scan their **QR code** — to defend against a relay substituting keys.

Caveats: the public **Nearby broadcast room is plaintext** by design (it has no fixed recipient set).
Reactions and delivery receipts travel as **cleartext metadata**. E2E uses **static keys (no forward
secrecy)** — see the roadmap. The at-rest database is encrypted with SQLCipher.
