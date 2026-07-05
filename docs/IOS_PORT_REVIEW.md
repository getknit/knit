# iOS port review — risks, gaps, and pre-release changes

Reviewed 2026-07-04, against the shipped two-plane transport (BLE + Wi-Fi Aware behind
`CompositeMeshTransport`) and the layered CBOR wire. Assumption (per direction): the iOS app will be
largely a **separate codebase**; this review is about what the *protocol and the Android app* should
look like so that codebase can interoperate, and what on iOS simply cannot be replicated.

**TL;DR verdict:**

- **Bluetooth LE is the cross-platform plane.** CoreBluetooth has everything the link layer needs
  (dual central+peripheral role, insecure L2CAP CoC streams), but iOS **cannot advertise BLE service
  data** — and our discovery protocol requires a 16-byte service-data payload before a peer even
  enters presence. Without an Android-side fallback discovery profile, an iOS device is invisible to
  every Android device. This is fixable, and cheapest to fix before release.
- **Wi-Fi Aware is not a cross-platform plane, and likely never will be for this app.** iOS 26's
  WiFiAware framework mandates user-mediated pairing per device pair, exposes no NAN follow-up
  messaging (our entire cue plane), no raw-PSK data paths (our `setPskPassphrase`), and enforces a
  service-name format ours violates. Treat NAN as the Android⇄Android accelerator it already is;
  `CompositeMeshTransport` already merges asymmetric radios, so the architecture survives unchanged.
- **The crypto is algorithm-portable but format-poisoned by Tink.** Ed25519/X25519-HPKE/AES-256-GCM
  all exist in CryptoKit, but the wire carries Tink protobuf keysets and Tink's 5-byte output
  prefixes — and the nodeId, safety number, and TOFU pin all hash that exact encoding, so it is
  **frozen forever at first release**. Swapping to raw keys + RAW prefixes is a contained change
  today and an identity-remint (every user, every pin) later. This is the single highest-value
  pre-release change.
- The pure mesh logic (router, custody, digests, key exchange) ports 1:1 — but its **tuning
  constants become cross-platform normative**: the convergent-custody-quota rule ("anything the
  digest folds over must be bounded identically on every node") now spans operating systems.

## 1. Transport reality on iOS

### 1.1 Bluetooth LE — viable, with three hard deltas

What maps cleanly:

- **Roles.** iOS runs central (scan + dial) and peripheral (advertise + listen) simultaneously, same
  as `BluetoothMeshTransport`. The larger-nodeId-dials tie-break works unchanged.
- **L2CAP CoC.** `listenUsingInsecureL2capChannel()`/`createInsecureL2capChannel(psm)` map to
  `CBPeripheralManager.publishL2CAPChannel(withEncryption: false)` /
  `CBPeripheral.openL2CAPChannel(_:)` (iOS 11+). Both are LE Credit-Based Flow Control channels; no
  pairing/bonding on either side. `LinkFraming` is a plain length-prefixed byte stream
  (`[type:1][len:4 BE][payload]`, 512 KiB record cap, 64 KiB file chunks) — trivially portable.
- **Address rotation.** Links are keyed by nodeId, never MAC (`links: Map<String, FramedLink>`;
  `deviceFor` refreshed per sighting), so iOS resolvable-private-address rotation is already
  tolerated — provided the nodeId in the (fallback) advert stays stable.

The three hard deltas:

1. **iOS peripherals cannot advertise service data — our discovery payload has nowhere to ride.**
   `CBPeripheralManager.startAdvertising` accepts only a local name and service UUIDs; there is no
   service-data or manufacturer-data key. Our scanner (`BluetoothMeshTransport.onScanResult`) drops
   any sighting whose `getServiceData(SERVICE_UUID)` is missing or shorter than the 16-byte
   `BleAdvertPayload` (version ‖ caps ‖ nodeId[8] ‖ digestCue[4] ‖ PSM[2], big-endian) — so an iOS
   peer never enters presence, never accumulates dwell, and is never dialed. The inverse direction
   works today: iOS centrals *can read* Android's service data, so iOS discovers Android fine.
   **Fix direction (Android-side, additive):** define a *name-encoded advert* fallback — iOS
   advertises the same 16 bytes encoded into `CBAdvertisementDataLocalNameKey` (e.g.
   `kn1` + base36/hex payload, ~22 chars, fits the ~28-byte foreground budget alongside the 16-bit
   UUID), and the Android scanner, on a `SERVICE_UUID` sighting with no service data, parses
   `scanRecord.deviceName` instead. Optionally add a GATT characteristic exposing the same payload
   as a second fallback. Both are backward-compatible scanner additions, but the *profile* should be
   specified (and ideally shipped) before release so fielded Android builds can see the first iOS
   build without a mandatory update.
2. **Background iOS ≈ off the mesh (for discovery).** A backgrounded iOS app's advertisement loses
   its local name and moves its service UUID to the "overflow area", which only a *foreground* iOS
   scanner explicitly targeting that UUID can see — **Android scanners can never see a backgrounded
   iOS advert**, and two backgrounded iOS devices can't discover each other. Established L2CAP links
   do survive backgrounding (with the `bluetooth-central`/`bluetooth-peripheral` background modes
   and state restoration), so a linked iOS peer keeps meshing until the link drops; it just can't be
   *re*-discovered until foregrounded. Store-and-forward custody is exactly the right mitigation and
   needs no change — but product expectations (and the iOS UI) must reflect "mesh runs while open".
3. **No scan-policy control.** `ScanDemandPolicy`/`PowerPolicy` (duty cycles, scan modes, boost/floor)
   have no CoreBluetooth equivalents — iOS manages radio duty itself and applies its own background
   coalescing. The iOS port keeps the *decision* layer (whom to dial, backoff, link budget:
   `PromotionPolicy` is pure and ports as-is) and drops the scan-throttle layer.

Two more BLE notes:

- **The 16-bit UUID `0xFE30` is squatted.** The `0xFE00–0xFEFF` block is Bluetooth SIG-assigned to
  member companies; `0xFE30` is not allocated to us. It was chosen because the legacy 31-byte advert
  can't fit a 128-bit UUID *plus* 16 bytes of service data. This is a ship-risk independent of iOS
  (collision with the real assignee's devices, SIG compliance) — either obtain a SIG member UUID or
  accept and document the risk. Any change to it is the BLE analogue of a `SERVICE_NAME` bump
  (discovery hard-partition), so decide **before** release. The name-encoded iOS fallback profile
  should be designed against whatever UUID is final.
- **One-way HELLO assumes the advert identified the responder.** Only the initiator sends
  `HELLO` (`LinkHandshake`); the responder's identity comes from the advert the initiator scanned.
  That coupling is exactly what the iOS fallback weakens. Making the responder reply with its own
  HELLO record (initiator validates it against the expected nodeId) removes the last dependency on
  advert parsing, doubles as a liveness check, and is a *wire-adjacent* change — cheapest done now,
  inside the same pre-release break window as everything else in §5.

### 1.2 Wi-Fi Aware — Apple-gated; do not plan on cross-OS NAN

iOS 26 (WWDC25) did add a WiFiAware framework — NAN / Wi-Fi Aware 4.0, the standardized cousin of
AWDL — but its shape is incompatible with an open stranger-mesh:

- **Pairing is mandatory.** Connections require prior pairing via DeviceDiscoveryUI (user picks the
  device, confirms a PIN) or AccessorySetupKit. There is no "connect to whoever matches the service"
  path. A festival mesh that must link to strangers with zero ceremony cannot exist on this API. (It
  also breaks our accept-any responder model, which by design doesn't know who's connecting.)
- **No follow-up messaging.** The framework is discovery → (paired) connection via
  `NWListener`/`NWBrowser`; nothing exposes NAN discovery follow-up frames
  (`DiscoverySession.sendMessage`). That is our **entire coordination plane**: digest cues, the
  fast fan-out / fast-send path (`MSG_FRAME_TAG 0x01`), the reverse-handle bootstrap for pure
  responders, the 30 s heartbeat, and the wedge-watchdog corroboration all ride it. The passive SSI
  digest tail (`|d<version>`) is the only cue an iOS publisher could even theoretically emit.
- **No raw-PSK data path.** Our NDP uses a fixed app-wide passphrase
  (`setPskPassphrase("knit-mesh-nan-psk-v1")`); Apple derives NDP security from its pairing
  ceremony. Even if discovery matched, the data paths wouldn't.
- **Service-name format.** Info.plist `WiFiAwareServices` names are `_name._proto`, ≤15 chars,
  letters/numbers/dashes. `app.getknit.knit.MESH.v6` (24 chars, dots) can't be declared on iOS at
  all. If any iOS NAN story is ever wanted (even iOS⇄iOS), the name must be Apple-conformant — and
  renaming is a discovery hard-partition, so it must ride a pre-release break, not a post-release
  one.
- **Field interop is rough anyway.** Apple developer-forum threads and vendor trackers document
  Android⇄iOS Wi-Fi Aware failures at the discovery/pairing layer (dropped events over missing DCEA
  attributes, PIN not displayed), and hardware support starts around iPhone 12 on iOS 26.

**Conclusion:** ship iOS as **BLE-only mesh**. Keep NAN as the Android⇄Android fast plane it is.
`CompositeMeshTransport` already handles radio asymmetry (a device with one radio meshes over that
one; `hasFastPlane` is per-child; suppression/foreign-reachable only engage where planes coexist),
so no orchestration change is needed for mixed Android/iOS meshes — an iOS peer just looks like a
BLE-only Android device. The one Android-side consequence worth noting: for an iOS peer, *everything*
(including what NAN fast-fans today) flows over the persistent L2CAP links, which is exactly how a
BLE-only Android device behaves today. Nothing branches on capabilities yet, so no compatibility
logic is required — `ProfileContent.protoVersion`/`capabilities` and the advert caps byte give us the
hooks if that ever changes.

## 2. Protocol & crypto portability

### 2.1 What ports cleanly (and why the design helps)

- **The byte-exact signature discipline is a gift to cross-platform work.** Because relays forward
  `signed`+`sig` verbatim and verifiers hash the *received* bytes, the two codebases never need
  canonical/deterministic CBOR agreement — each side only signs bytes *it* encoded and must be able
  to *decode* the other's. The iOS port must uphold the same invariant: never re-encode `signed`,
  rewrite only `ttl`/`hops` on relay.
- **Pure-logic subsystems port 1:1**: `MeshRouter`/`SeenSet` (dedup + jittered flood),
  `ForwardSync`/`ForwardStore` (custody), `StoreDigest` (FNV-1a-64 XOR fold — spec below),
  `DigestTracker`, `AckSync`, `KeyExchange`, `PendingInbound`, `BlobExchange`, `Conversations`,
  `PromotionPolicy`. Their JVM test suites are effectively executable specs — port the tests
  alongside the logic.
- **Primitives all exist in CryptoKit**: Ed25519 (`Curve25519.Signing`), X25519-HPKE
  (`CryptoKit.HPKE`, iOS 17+: DHKEM(X25519)+HKDF-SHA256+AES-256-GCM matches our Tink template), and
  AES-256-GCM (12-byte nonce, 128-bit tag — `AES.GCM.SealedBox.combined` is `nonce‖ct‖tag`, ours is
  split `nonce`/`ct‖tag`, a byte-shuffle). Identity keys live in the iOS Keychain; note the Secure
  Enclave holds only P-256, so Curve25519 keys are Keychain/Data-Protection-resident — the same
  effective posture as our AndroidKeyStore-*wrapped* (not -resident) secrets.
- **All the deterministic derivations are plain hash math** and just need spec + vectors: nodeId
  (SHA-256 over `"knit-node-id-v1:" + bundle`, first 8 bytes mapped `% 36` into `[a-z0-9]`), group id
  (`"knit-group-id-v1:"` + sorted member set, 24 hex chars, `g-` prefix), `DeviceTag`
  (`"knit-device-tag-v1:"` + platform device id, 16 hex — iOS uses `identifierForVendor`; it resets
  more often than `ANDROID_ID`, which only softens an already-soft block deterrent), `SafetyNumber`
  (sorted `id|bundle` pair → SHA-256 → 8×5 digits), blob addresses (lowercase-hex SHA-256 of
  ciphertext), `FrameId` (16 random bytes, base64url, no padding), and the **`Alias`
  display-name derivation (FNV-1a over nodeId into word tables) — if the word lists aren't ported
  byte-identically, the two platforms show different names for the same peer.**
- **AEAD header binding** is a plain string: `"$id|$senderId|$sentAt|$thread"` — portable, but the
  `thread` value's derivation (recipient id for DMs, group id for groups) must be in the spec.

### 2.2 Tink-isms baked into the wire — fix before release (highest-value change)

Three Tink implementation details currently *are* the protocol:

1. **`PublicKeyBundle.encoded` wraps Tink protobuf keysets.** The wire/pinned form is
   base64(CBOR(`{hybridPub, sigPub}`)) where each field is a serialized **Tink `Keyset` proto**. An
   iOS implementation must parse Google's keyset protobuf just to extract two 32-byte public keys.
2. **Non-RAW key templates leak 5-byte output prefixes.** `IdentityKeyStore` uses
   `DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM` and `ED25519` — the TINK-prefix variants. So
   every `WireEnvelope.sig` is 69 bytes (`0x01 ‖ keyId[4] ‖ Ed25519[64]`) and every `WrappedKey.wk`
   is `0x01 ‖ keyId[4] ‖ enc[32] ‖ ct`, where `keyId` comes out of the keyset proto. CryptoKit
   produces standard RFC 8032 / RFC 9180 bytes; iOS would have to fabricate and strip Tink prefixes.
3. **The bundle bytes are CBOR-bloated.** `PublicKeyBundle.Proto` fields lack `@ByteString`, so
   kotlinx encodes each keyset as a CBOR *array of ints* (~2–3× size) — another quirk iOS must
   reproduce bit-for-bit, because…

…**the encoded bundle string is hashed into everything durable**: the nodeId derivation, the safety
number, and the TOFU pin (`PeerEntity.pubKey` equality). After release, changing the bundle format
re-mints every user's identity and invalidates every pinned peer and QR verification. Before
release, it's a contained change:

- Replace the bundle with raw keys: CBOR `{sigPub: @ByteString 32B, hpkePub: @ByteString 32B}` (or
  even a fixed 64-byte concatenation), base64 as today.
- Switch templates to the RAW variants (`ED25519_RAW`, the `_RAW` HPKE template) so signatures are
  bare 64-byte RFC 8032 and wrapped keys are bare `enc ‖ ct` RFC 9180. Android keeps Tink
  internally; only the bytes on the wire change.
- Bundle this with the already-planned pre-release wire break (service-name / UUID bump) so it costs
  nothing extra in partition terms.

### 2.3 The CBOR profile must be pinned in writing

kotlinx-serialization CBOR (1.11.0) has defaults an iOS codec (SwiftCBOR & co.) must be tested
against: indefinite-length map/array encoding by default, `@ByteString` vs array-of-int `ByteArray`
encoding, `encodeDefaults = false` (absent fields ≠ null fields), `ignoreUnknownKeys = true` on
decode. None of this is exotic, but it is currently *implied by a library default* rather than
specified. Two actions: (a) write the profile down as normative (consider flipping to
definite-length encoding while breaks are still free — it's the friendlier target for non-kotlinx
codecs); (b) check in **golden vectors** — hex fixtures for every frame type (encode + decode
direction), key bundles, signatures over known keys, sealed/opened `EncEnvelope`s, nodeId/groupId/
safety-number/digest/alias derivations — validated by a JVM test and consumed verbatim by the future
iOS test suite. Vectors are the single cheapest way to keep two codebases from drifting.

### 2.4 Convergence constants become cross-platform law

The convergent-custody-quota invariant (see AGENTS.md) — every node must bound the carried set by
the *identical* rule, or digests never match and the cue plane churns — now spans platforms. The
custody TTLs, global cap, per-sender/per-group/broadcast quotas, the eviction key (frame-global
`(sentAt, id)`, newest-N), `FrameType.isCustodial`'s exact membership, and the `StoreDigest` fold
(FNV-1a 64: offset `0xCBF29CE484222325`, prime `0x100000001B3`, over the id's UTF-8 bytes; XOR
across the set) are **protocol constants, not tuning knobs**. On the BLE plane a divergence is less
catastrophic than on NAN (persistent links, no scarce NDI) but still means every digest exchange
re-runs. Extract them into one clearly-marked normative object and cover them in the vectors.

## 3. Feature-by-feature port assessment

| Feature | Portability | Notes |
|---|---|---|
| E2E DMs / groups | Clean after §2.2 | CryptoKit HPKE needs iOS 17+ (a fine floor) |
| Store-and-forward custody | Clean | Constants normative (§2.4); Room → GRDB/SQLite |
| Broadcast room / reactions / receipts / mentions / replies | Clean | Pure wire types |
| Blob attachments | Mostly clean | Content addressing + `AttachmentCrypto` trivial. Android *encodes* WebP (`WebpTranscode`/`AnimatedWebpMuxer`); iOS decodes WebP natively (14+) but encoding needs libwebp — either bundle it or let iOS originate JPEG/HEIC-transcoded-to-JPEG. `attachmentMime` already rides the wire; **specify the cross-platform mime set** (e.g. `image/webp`, `image/jpeg`, `image/png`) so neither side sends what the other can't render |
| Content moderation | Medium effort | Detoxify ALBERT int8 TFLite + NSFW model run under LiteRT on iOS (or convert to Core ML); `SentencePieceTokenizer` is ~200 lines of pure logic to port. Parity matters: App Store UGC rules (1.2) require filtering + report + block — we have all three, keep them |
| Identity / keys | Clean after §2.2 | Keychain-resident Curve25519; `DeviceTag` from `identifierForVendor` |
| Notifications | Rework | Channels → UNNotification categories/threads; local-only (no push in an offline mesh); fire only while the app runs — aligns with the background story in §1.1 |
| MeshService (foreground service) | **No equivalent** | The always-on model doesn't exist on iOS. Mesh participation ≈ app open + linked-in-background grace. Product-level expectation, not an engineering gap |
| Diagnostics screen / debug bridge | Rework | `DebugBridgeReceiver`/`am broadcast` is Android-only; plan an iOS analogue early (local HTTP endpoint or XCUITest hooks) — the send→verify-without-screenshots loop is how this repo's agents/tests drive devices, and the iOS repo will want the same |
| Onboarding / permissions | Rework | `NEARBY_WIFI_DEVICES` etc. → Bluetooth permission + (if NAN ever) `com.apple.developer.wifi-aware` entitlement, `WiFiAwareServices` plist, Local Network prompt |
| APK-share invite (`ApkMerger`) | **Drop** | No sideloading on iOS; nearest analogue is sharing an App Store link — worthless offline. Accept the gap |
| Donate screen | **App Store risk** | Payments to the developer inside the app are IAP territory (guideline 3.1.1); donations may need restructuring (external link rules shift; worst case remove on iOS) |
| Play review prompt | Trivial | `SKStoreReviewController` |

## 4. Platform / distribution considerations

- **Encryption export compliance**: standard algorithms (exempt category), but App Store Connect
  still wants the self-classification (`ITSAppUsesNonExemptEncryption` = YES with exemption) and a
  French import declaration for E2E messaging.
- **UGC review posture**: an anonymous local mesh chat will get reviewer attention. Moderation +
  block + report + safety numbers are genuine assets here; write the review notes around them.
- **Testing reality**: the iOS simulator has no CoreBluetooth radio support — everything in §1.1
  needs physical iPhones against physical Androids, from day one. The `FakeLoopTransport` pattern
  ports (protocol logic stays simulator-testable); golden vectors (§2.3) cover the codec seam
  without hardware.
- **Version floors**: BLE plane → any modern iOS (pick iOS 17 for CryptoKit HPKE and call it done).
  NAN, if ever, → iOS 26 + roughly iPhone 12+.

## 5. Do-now changes in the Android app (before release)

Ordered by leverage; the first three are the ones that get *harder than an engineering task* —
i.e. become identity/partition breaks — the moment real users exist.

1. **De-Tink the wire crypto format** (§2.2): raw 32-byte keys in `PublicKeyBundle` (with
   `@ByteString`), RAW-prefix templates in `IdentityKeyStore`, spec the byte layouts
   (`sig` = 64 B RFC 8032; `wk` = `enc[32] ‖ ct` RFC 9180). One coordinated wire break, bundled with
   (2)/(3). *Effort: S–M (the migration story for existing installs is the only wrinkle, and
   pre-release there is none).*
2. **Fix the BLE identity/discovery couplings while breaks are free**: (a) responder HELLO reply so
   link identity never depends on advert parsing; (b) settle the service UUID question (`0xFE30`
   squatting) — both partition discovery if changed later. *Effort: S.*
3. **Choose an Apple-conformant NAN service name** (≤15 chars, `_name._proto`) in the same break —
   even though cross-OS NAN is a long shot, the rename costs one constant today and a mesh partition
   later. *Effort: trivial.*
4. **Write `docs/PROTOCOL.md` (normative spec) + golden vectors** (§2.3): CBOR profile, every frame
   type, every derivation + salt, the BLE advert/HELLO/`LinkFraming` byte layouts, the
   `Protocol.advertise` string, AEAD header (incl. `thread` derivation), custody constants (§2.4),
   and fixture files a Swift test target can consume unmodified. *Effort: M — mostly extraction from
   AGENTS.md/WIRE_COMPAT.md + this review into normative form.*
5. **Spec (and ideally ship) the iOS BLE discovery fallback** (§1.1): the name-encoded advert
   profile and the scanner path that accepts service-data-less sightings by parsing it. Shipping the
   scanner side in the release build means the first iOS build meshes with *fielded* Androids, not
   just updated ones. *Effort: S–M.*
6. **Extract the normative convergence constants** (§2.4) into one place with a "cross-platform
   law" comment. *Effort: S.*
7. **Constrain the attachment mime set** in the spec and reject/transcode outside it. *Effort: S.*
8. **Optional, worth a deliberate decision: carve the pure protocol core toward Kotlin
   Multiplatform.** The pure files already avoid Android imports but lean on `java.security`/
   `java.util.Base64`; putting those behind tiny facades keeps the door open to sharing
   `mesh/protocol` + the pure mesh logic with iOS as a KMP module instead of re-implementing it.
   That halves the drift risk that (4) exists to manage — while keeping UI and transports native,
   consistent with the separate-codebase assumption. If KMP is a firm no, skip this; vectors carry
   the load. *Effort: S now, M later to actually stand up KMP.*

Items 1–3 share one coordinated pre-release wire/discovery break (bump `SERVICE_NAME` and the BLE
UUID once, together). After release, every one of them is somewhere between painful and impossible.

## References (iOS-side facts)

- Apple, [Wi-Fi Aware framework](https://developer.apple.com/documentation/WiFiAware) and
  [`com.apple.developer.wifi-aware` entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.wifi-aware);
  WWDC25 session [“Supercharge device connectivity with Wi-Fi Aware”](https://developer.apple.com/videos/play/wwdc2025/228/)
  (pairing via DeviceDiscoveryUI/AccessorySetupKit, `WiFiAwareServices` name format).
- Android⇄iOS Wi-Fi Aware interop failures:
  [Apple Developer Forums #790195](https://developer.apple.com/forums/thread/790195),
  [#801280](https://developer.apple.com/forums/thread/801280),
  [Google Issue Tracker #446078848](https://issuetracker.google.com/issues/446078848),
  [espressif/esp-idf#16743](https://github.com/espressif/esp-idf/issues/16743).
- MacRumors, [iOS 26 adding Wi-Fi Aware](https://www.macrumors.com/2025/06/21/ios-26-adding-two-new-wi-fi-features/).
- Apple, `CBPeripheralManager.startAdvertising` (advertising key restrictions; background/overflow
  behavior), `CBPeripheralManager.publishL2CAPChannel(withEncryption:)`, CryptoKit `HPKE` (iOS 17+).
