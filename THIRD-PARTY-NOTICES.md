# Third-Party Notices

Knit is licensed under the **GNU General Public License v3.0 or later** (see [`COPYING`](COPYING)).
It redistributes, and depends on, the third-party open-source components listed below. Every component
shipped in the Knit APK is under a **GPL-compatible** license (Apache-2.0, BSD, or MIT); the project
deliberately carries **no Google Play services / GMS** dependency.

This file is provided for attribution and to satisfy the notice-retention terms of the Apache License
2.0 (§4), the BSD license, and the MIT license. Where a component ships its own `NOTICE` file, that
notice is incorporated here by reference. The full text of the Apache License 2.0 is available at
<https://www.apache.org/licenses/LICENSE-2.0>.

## Runtime components (shipped in the APK)

| Component | Project | License |
|---|---|---|
| Android Jetpack / AndroidX (`androidx.*` — core-ktx, activity-compose, navigation-compose, lifecycle-\*, room, datastore, exifinterface) | [Android Open Source Project](https://developer.android.com/jetpack) | Apache-2.0 |
| Jetpack Compose (Material 3, UI, tooling — via the Compose BOM) | [Android Open Source Project](https://developer.android.com/jetpack/compose) | Apache-2.0 |
| Kotlin standard library | [JetBrains — Kotlin](https://github.com/JetBrains/kotlin) | Apache-2.0 |
| kotlinx.coroutines | [JetBrains — kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 |
| kotlinx.serialization (JSON + CBOR) | [JetBrains — kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache-2.0 |
| Koin (`koin-android`, `koin-androidx-compose`) | [InsertKoinIO / Koin](https://github.com/InsertKoinIO/koin) | Apache-2.0 |
| Coil 3 (`coil-compose`, `coil-gif`) | [Coil](https://github.com/coil-kt/coil) | Apache-2.0 |
| Google Tink (`tink-android`) — E2E crypto | [Tink](https://github.com/tink-crypto/tink-java) | Apache-2.0 |
| LiteRT / TensorFlow Lite (`com.google.ai.edge.litert`) — on-device ML runtime | [Google AI Edge — LiteRT](https://github.com/google-ai-edge/LiteRT) | Apache-2.0 |
| SQLCipher for Android (`net.zetetic:sqlcipher-android`) — at-rest DB encryption | [SQLCipher](https://github.com/sqlcipher/sqlcipher-android) · [Zetetic LLC](https://www.zetetic.net/sqlcipher/) | BSD-3-Clause-style (Zetetic) |
| &nbsp;&nbsp;↳ OpenSSL (statically linked inside SQLCipher's native library) | [The OpenSSL Project](https://www.openssl.org/) | Apache-2.0 (OpenSSL 3.x) |
| ZXing "core" — QR generation | [ZXing](https://github.com/zxing/zxing) | Apache-2.0 |
| ZXing Android Embedded — QR scanning | [journeyapps/zxing-android-embedded](https://github.com/journeyapps/zxing-android-embedded) | Apache-2.0 |
| ARSCLib — on-device split-APK merge | [REAndroid/ARSCLib](https://github.com/REAndroid/ARSCLib) | Apache-2.0 |
| apksig — on-device APK re-signing | [Android Open Source Project](https://android.googlesource.com/platform/tools/apksig/) | Apache-2.0 |

## Bundled ML models and data

- **On-device moderation models** — the NSFW image classifier (MIT, © 2020 The nsfw_model Developers)
  and the toxicity text classifier (Apache-2.0, derived from Detoxify / ALBERT) bundled under
  `app/src/main/assets/moderation/` are third-party works redistributed under their own licenses. The
  full notices, source URLs, and license texts are in
  [`app/src/main/assets/moderation/README.md`](app/src/main/assets/moderation/README.md).
- **`profanity_en.txt`** — the deterministic profanity wordlist under `app/src/main/assets/moderation/`
  is original content authored for Knit and is covered by the project's GPL-3.0-or-later license (it is
  **not** a third-party work). It is a deliberately small starter set intended to be replaced or
  extended with a vetted, permissively-licensed corpus.

## Build- and test-only dependencies (not distributed)

The following are used only to build, lint, or test Knit and are **not** shipped in the APK, so their
licenses do not affect redistribution of the app: JUnit 4 (EPL-1.0), MockK, Robolectric, Espresso and
the AndroidX Test libraries, UIAutomator, Room testing, Koin test (all Apache-2.0), and the detekt,
ktlint, and Kover build tooling. JUnit's EPL-1.0 license is GPL-incompatible for *distribution*, but
JUnit is never distributed with Knit — it is a test-scope dependency only.

---

When you add, remove, or upgrade a **shipped** dependency, update this file so the attribution list
stays accurate (see [`CONTRIBUTING.md`](CONTRIBUTING.md)).
