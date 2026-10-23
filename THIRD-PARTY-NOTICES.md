# Third-Party Notices

Knit is licensed under the **GNU General Public License v3.0 or later** (see [`COPYING`](COPYING)).
It redistributes, and depends on, the third-party open-source components listed below. Every component
shipped in the Knit APK is under a **GPL-compatible** license (Apache-2.0, BSD, MIT, the Unicode License,
or a permissive or public-domain notice); the project deliberately carries **no Google Play services /
GMS** dependency.

This file is provided for attribution and to satisfy the notice-retention terms of the Apache License
2.0 (§4), the BSD license, and the MIT license. Where a component ships its own `NOTICE` file, that
notice is incorporated here by reference. The full text of the Apache License 2.0 is available at
<https://www.apache.org/licenses/LICENSE-2.0>.

The same list is shown in the app under **Settings › ⋮ › Open-source licenses**, each row opening the
license text bundled under `app/src/main/assets/legal/`. The in-app copy is
`app/src/main/java/app/getknit/knit/legal/ThirdPartyNotices.kt`; two tests keep the three in step —
`ThirdPartyNoticesSyncTest` pins the tables below to that list row for row, and
`ReleaseClasspathNoticesTest` pins the list to every coordinate on `app/gradle.lockfile`'s release runtime
classpath. Rows read: the component, then the project it comes from, then its SPDX license id.

## Runtime components (shipped in the APK)

| Component | Project | License |
|---|---|---|
| Android Jetpack / AndroidX (`androidx.*` — core-ktx, activity, navigation, lifecycle, room3, datastore, exifinterface, camera (CameraX), media3, core-telecom, emoji2, appcompat and their transitive AndroidX libraries) | [Android Open Source Project](https://developer.android.com/jetpack) | Apache-2.0 |
| Jetpack Compose (Material 3, UI, tooling — via the Compose BOM) | [Android Open Source Project](https://developer.android.com/jetpack/compose) | Apache-2.0 |
| Compose Multiplatform runtime (`org.jetbrains.compose.*`, `org.jetbrains.androidx.*` — pulled in by Koin's Compose artifacts; on Android they resolve onto Jetpack Compose) | [JetBrains](https://github.com/JetBrains/compose-multiplatform) | Apache-2.0 |
| Kotlin standard library | [JetBrains — Kotlin](https://github.com/JetBrains/kotlin) | Apache-2.0 |
| kotlinx.coroutines (+ `atomicfu`) | [JetBrains — kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 |
| kotlinx.serialization (JSON + CBOR) | [JetBrains — kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache-2.0 |
| Koin (`koin-android`, `koin-androidx-compose`) | [InsertKoinIO / Koin](https://github.com/InsertKoinIO/koin) | Apache-2.0 |
| Stately (`co.touchlab:stately-*` — via Koin) | [Touchlab](https://github.com/touchlab/Stately) | Apache-2.0 |
| Coil 3 (`coil-compose`, `coil-gif`) | [Coil](https://github.com/coil-kt/coil) | Apache-2.0 |
| Accompanist DrawablePainter (via Coil) | [google/accompanist](https://github.com/google/accompanist) | Apache-2.0 |
| OkHttp (`okhttp`, `okhttp-android` — the spool plane's dialer and the link-preview fetcher) | [Square](https://github.com/square/okhttp) | Apache-2.0 |
| Okio (via OkHttp and Coil) | [Square](https://github.com/square/okio) | Apache-2.0 |
| Guava (`guava`, `failureaccess`, `listenablefuture` — via CameraX) | [Google](https://github.com/google/guava) | Apache-2.0 |
| Gson (via Tink) | [Google](https://github.com/google/gson) | Apache-2.0 |
| Dagger (`dagger`, with `javax.inject` and `jakarta.inject` — via CameraX) | [Google](https://github.com/google/dagger) | Apache-2.0 |
| Google Tink (`tink-android`) — E2E crypto | [Tink](https://github.com/tink-crypto/tink-java) | Apache-2.0 |
| LiteRT / TensorFlow Lite (`com.google.ai.edge.litert`) — on-device ML runtime | [Google AI Edge — LiteRT](https://github.com/google-ai-edge/LiteRT) | Apache-2.0 |
| SQLCipher for Android (`net.zetetic:sqlcipher-android`) — at-rest DB encryption | [SQLCipher](https://github.com/sqlcipher/sqlcipher-android) · [Zetetic LLC](https://www.zetetic.net/sqlcipher/) | BSD-3-Clause (Zetetic) |
| &nbsp;&nbsp;↳ OpenSSL (statically linked inside SQLCipher's native library) | [The OpenSSL Project](https://www.openssl.org/) | Apache-2.0 (OpenSSL 3.x) |
| ZXing core — QR generation | [ZXing](https://github.com/zxing/zxing) | Apache-2.0 |
| ARSCLib — on-device split-APK merge | [REAndroid/ARSCLib](https://github.com/REAndroid/ARSCLib) | Apache-2.0 |
| apksig — on-device APK re-signing | [Android Open Source Project](https://android.googlesource.com/platform/tools/apksig/) | Apache-2.0 |
| WebRTC (`io.github.webrtc-sdk:android` — libwebrtc, the media engine of live calls) | [The WebRTC project authors](https://webrtc.googlesource.com/src/) | BSD-3-Clause (WebRTC) |
| &nbsp;&nbsp;↳ Abseil (`abseil-cpp`) | [Google](https://github.com/abseil/abseil-cpp) | Apache-2.0 |
| &nbsp;&nbsp;↳ BoringSSL (with fiat-crypto) — TLS and DTLS for call media | [Google](https://boringssl.googlesource.com/boringssl/) | Apache-2.0 |
| &nbsp;&nbsp;↳ libsrtp — SRTP for call media | [Cisco Systems](https://github.com/cisco/libsrtp) | BSD-3-Clause (WebRTC) |
| &nbsp;&nbsp;↳ Opus — the call audio codec | [Xiph.Org Foundation](https://opus-codec.org/) | BSD-3-Clause (WebRTC) |
| &nbsp;&nbsp;↳ RNNoise (noise suppression) | [Xiph.Org Foundation](https://github.com/xiph/rnnoise) | BSD-3-Clause (WebRTC) |
| &nbsp;&nbsp;↳ libvpx (VP8/VP9) | [The WebM Project](https://chromium.googlesource.com/webm/libvpx) | BSD-3-Clause (WebRTC) |
| &nbsp;&nbsp;↳ libyuv | [The LibYuv Project](https://chromium.googlesource.com/libyuv/libyuv) | BSD-3-Clause (WebRTC) |
| &nbsp;&nbsp;↳ CRC32C | [Google](https://github.com/google/crc32c) | BSD-3-Clause (WebRTC) |
| &nbsp;&nbsp;↳ JNI Zero (`org.jni_zero`, the Java–native glue) | [The Chromium Authors](https://chromium.googlesource.com/chromium/src/third_party/jni_zero/) | BSD-3-Clause (WebRTC) |
| &nbsp;&nbsp;↳ libaom (AV1) | [Alliance for Open Media](https://aomedia.googlesource.com/aom/) | BSD-2-Clause (WebRTC) |
| &nbsp;&nbsp;↳ dav1d (AV1 decoding) | [VideoLAN](https://code.videolan.org/videolan/dav1d) | BSD-2-Clause (WebRTC) |
| &nbsp;&nbsp;↳ SFrame | [Cisco Systems](https://github.com/cisco/sframe) | BSD-2-Clause (WebRTC) |
| &nbsp;&nbsp;↳ WebRTC's bundled audio routines (PFFFT, Ooura FFT, Mark Olesen's FFT, Wilco Dijkstra's square root, SpanDSP G.711 and G.722) | [The WebRTC project authors](https://webrtc.googlesource.com/src/) | LicenseRef-WebRTC-Audio |

The rows under WebRTC are the third-party code libwebrtc statically links into its native library, taken from
WebRTC's own license list (`tools_webrtc/libs/generate_licenses.py`) at the AAR's branch, `branch-heads/7871`, and
kept to what the shipped binary shows. Their texts are bundled as `BSD-3-Clause-WebRTC.txt`,
`BSD-2-Clause-WebRTC.txt` and `WebRTC-audio-notices.txt`. The LLVM runtime it also links (libc++, libc++abi,
libunwind) is under Apache-2.0 WITH LLVM-exception, which asks no notice of compiled code.

QR *scanning* uses CameraX (an AndroidX library, above) plus ZXing core; the former ZXing Android Embedded
dependency was dropped when the scanner moved to CameraX.

## Bundled ML models and data

| Asset | Source | License |
|---|---|---|
| NSFW image classifier (`nsfw.tflite` — MobileNetV2, © 2020 The nsfw_model Developers) | [GantMan/nsfw_model](https://github.com/GantMan/nsfw_model) | MIT |
| Toxicity text classifier (`toxicity.tflite`, `tokenizer.json` — Detoxify `unbiased-small` on ALBERT) | [unitaryai/detoxify](https://github.com/unitaryai/detoxify) | Apache-2.0 |
| Profanity word list (`profanity_en.txt`, `slurs_en.txt` — © 2021 David Sojevic) | [dsojevic/profanity-list](https://github.com/dsojevic/profanity-list) | MIT |
| Emoji catalog (`emoji_en.tsv` — from `emoji-test.txt`, Emoji 17.0, © Unicode, Inc.) | [Unicode, Inc. — emoji-test.txt](https://www.unicode.org/reports/tr51) | Unicode-3.0 |

- **On-device moderation models** — the NSFW image classifier and the toxicity text classifier bundled
  under `app/src/main/assets/moderation/` are third-party works redistributed under their own licenses. The
  full notices, source URLs, and license texts are in
  [`app/src/main/assets/moderation/README.md`](app/src/main/assets/moderation/README.md).
- **`profanity_en.txt`, `slurs_en.txt`** — the deterministic profanity and slur word lists under
  `app/src/main/assets/moderation/` are derived works generated from [dsojevic/profanity-list](https://github.com/dsojevic/profanity-list)
  (MIT, © 2021 David Sojevic), pinned at commit `c27924319aa9bd6f917e3782b4f4b6604a50b652`, by
  `scripts/gen-profanity-list.py`. They are redistributed under the MIT License; the full license text,
  source URL, pinned commit, and transform/curation rules are in
  [`app/src/main/assets/moderation/README.md`](app/src/main/assets/moderation/README.md).
- **`emoji_en.tsv`** — the reaction picker's emoji catalog under `app/src/main/assets/emoji/` is a derived
  work generated from Unicode's [`emoji-test.txt`](https://www.unicode.org/reports/tr51) (Unicode License
  v3, © Unicode, Inc.), pinned at Emoji 17.0, by `scripts/gen-emoji-catalog.py`. It is redistributed under
  the Unicode License v3; the full license text, source URL, pinned version, and transform rules are in
  [`app/src/main/assets/emoji/README.md`](app/src/main/assets/emoji/README.md).

## Build- and test-only dependencies (not distributed)

The following are used only to build, lint, or test Knit and are **not** shipped in the APK, so their
licenses do not affect redistribution of the app: JUnit 4 (EPL-1.0), MockK, Robolectric, Espresso and
the AndroidX Test libraries, UIAutomator, Room testing, Koin test (all Apache-2.0), and the detekt,
ktlint, and Kover build tooling. JUnit's EPL-1.0 license is GPL-incompatible for *distribution*, but
JUnit is never distributed with Knit — it is a test-scope dependency only.

A handful of annotation-only jars (JSpecify, the Checker Framework qualifiers, JSR-305, Error Prone and
J2ObjC annotations, AutoValue annotations, JetBrains annotations) sit on the release classpath but carry no
runtime code — R8 drops every reference — so they need no notice; `ReleaseClasspathNoticesTest` lists them
as `NOT_SHIPPED` with that reason.

---

When you add, remove, or upgrade a **shipped** dependency, update this file **and** the matching row in
`app/src/main/java/app/getknit/knit/legal/ThirdPartyNotices.kt` so the attribution list stays accurate;
the two tests above fail until the table, the list and the lockfile agree (see
[`CONTRIBUTING.md`](CONTRIBUTING.md)).
