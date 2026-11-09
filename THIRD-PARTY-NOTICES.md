# Third-Party Notices

Knit is licensed under the **GNU General Public License v3.0 or later** (see [`COPYING`](COPYING)).
It redistributes, and depends on, the third-party open-source components listed below. Every component
shipped in the Knit APK is under a **GPL-compatible** license (Apache-2.0, BSD, MIT, MPL-2.0, HPND, the Unicode
License, GPL-2.0-or-later, or a permissive or public-domain notice; the model data compiled into the speech runtime adds
CC-BY-4.0); the project deliberately carries **no Google Play services / GMS** dependency.

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

QR *scanning* uses CameraX (an AndroidX library, above) plus ZXing core; the former ZXing Android Embedded
dependency was dropped when the scanner moved to CameraX.

## Live calls (packaged unless a build turns it off: not in the slim APK or a `-PliveCalls=false` build)

| Component | Project | License |
|---|---|---|
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

## Offline translation (packaged unless a build turns it off: not in the slim APK or a `-Ptranslate=false` build)

| Component | Project | License |
|---|---|---|
| slimt (`app.getknit:translate-native` — Marian inference, the offline translation runtime) | [slimt Authors](https://github.com/jerinphilip/slimt) | GPL-2.0-or-later |
| &nbsp;&nbsp;↳ bergamot-translator and marian-dev (code slimt derives from) | [browsermt](https://github.com/browsermt/bergamot-translator) | MPL-2.0 |
| &nbsp;&nbsp;↳ ssplit (sentence splitting, modified in slimt) | [browsermt](https://github.com/browsermt/ssplit-cpp) | Apache-2.0 |
| &nbsp;&nbsp;↳ ruy (matrix multiplication) | [Google](https://github.com/google/ruy) | Apache-2.0 |
| &nbsp;&nbsp;↳ cpuinfo | [PyTorch](https://github.com/pytorch/cpuinfo) | BSD-2-Clause |
| &nbsp;&nbsp;↳ SentencePiece (tokenizer) | [Google](https://github.com/google/sentencepiece) | Apache-2.0 |
| &nbsp;&nbsp;↳ CLD2 (language detection) | [CLD2 Owners](https://github.com/CLD2Owners/cld2) | Apache-2.0 |

`app.getknit:translate-native` is Knit's own artifact (GPL-3.0-or-later), built reproducibly from the
[knit-translate-native](https://github.com/getknit/knit-translate-native) repository. Its one native library statically
links slimt and the code under it, at the commits that repository's `sources.lock` pins. slimt's `COPYRIGHT` grants
version 2 of the GPL "or (at your option) any later version", which is what lets it ship inside a GPL-3.0-or-later app.
The translation models the app downloads are not bundled. A build without translation (the slim APK, or one made with
`-Ptranslate=false`) carries none of this, and its in-app list hides these rows. The texts are bundled as `GPL-2.0.txt`,
`MPL-2.0.txt` and `BSD-2-Clause-cpuinfo.txt`.

## Bundled ML models and data

| Asset | Source | License |
|---|---|---|
| NSFW image classifier (`nsfw.tflite` — MobileNetV2, © 2020 The nsfw_model Developers) | [GantMan/nsfw_model](https://github.com/GantMan/nsfw_model) | MIT |
| Toxicity text classifier (`toxicity.tflite`, `tokenizer.json` — Detoxify `unbiased-small` on ALBERT) | [unitaryai/detoxify](https://github.com/unitaryai/detoxify) | Apache-2.0 |
| Profanity word list (`profanity_en.txt`, `slurs_en.txt` — © 2021 David Sojevic) | [dsojevic/profanity-list](https://github.com/dsojevic/profanity-list) | MIT |
| Emoji catalog (`emoji_en.tsv` — from `emoji-test.txt`, Emoji 17.0, © Unicode, Inc.) | [Unicode, Inc. — emoji-test.txt](https://www.unicode.org/reports/tr51) | Unicode-3.0 |
| Emoji names and search keywords (`emoji_*.tsv` — from CLDR 48 annotations, © Unicode, Inc.) | [unicode-org/cldr](https://github.com/unicode-org/cldr) | Unicode-3.0 |
| More emoji search keywords (`emoji_en.tsv` — from emojilib 4.0.3, © 2014 Mu-An Chiou) | [muan/emojilib](https://github.com/muan/emojilib) | MIT |

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
- **`emoji_en.tsv` and `emoji_<tag>.tsv`** — the reaction picker's emoji catalog and its per-language overlays
  under `app/src/main/assets/emoji/` are derived works generated by `scripts/gen-emoji-catalog.py` from Unicode's
  [`emoji-test.txt`](https://www.unicode.org/reports/tr51), pinned at Emoji 17.0, and the
  [CLDR](https://github.com/unicode-org/cldr) emoji annotations, pinned at `release-48` (both Unicode License v3,
  © Unicode, Inc.), with more English keywords from [emojilib](https://github.com/muan/emojilib) 4.0.3 (MIT,
  © 2014 Mu-An Chiou). They are redistributed under those licenses; the full license texts, source URLs, pinned
  versions and transform rules are in [`app/src/main/assets/emoji/README.md`](app/src/main/assets/emoji/README.md).

## Speech (packaged unless a build turns it off: not in the slim APK, a `-Pstt=false` build or one with no call kind)

| Component | Project | License |
|---|---|---|
| Moonshine Voice (`ai.moonshine:moonshine-voice` — the speech runtime of walkie transcripts, live captions and voice-note transcripts, with cpp-annote, its speaker-diarization library) | [Moonshine AI](https://github.com/moonshine-ai/moonshine) | MIT |
| &nbsp;&nbsp;↳ nlohmann/json (JSON for Modern C++) | [Niels Lohmann](https://github.com/nlohmann/json) | MIT |
| &nbsp;&nbsp;↳ utf8proc (with its tables of Unicode data) | [JuliaStrings](https://github.com/JuliaStrings/utf8proc) | LicenseRef-utf8proc |
| &nbsp;&nbsp;↳ Eigen (linear algebra, in `libmoonshine.so` and in ONNX Runtime's thread pool) | [Eigen project](https://gitlab.com/libeigen/eigen) | MPL-2.0 |
| &nbsp;&nbsp;↳ kaldi-native-fbank (Kaldi-compatible filter-bank features) | [Xiaomi Corporation](https://github.com/csukuangfj/kaldi-native-fbank) | Apache-2.0 |
| &nbsp;&nbsp;↳ KISS FFT | [Mark Borgerding](https://github.com/mborgerding/kissfft) | BSD-3-Clause (Moonshine) |
| &nbsp;&nbsp;↳ pyannote.audio and pyannote.core (the diarization pipeline cpp-annote ports to C++) | [CNRS](https://github.com/pyannote/pyannote-audio) | MIT |
| &nbsp;&nbsp;↳ VBx (the clustering cpp-annote ports, by way of pyannote.audio) | [Brno University of Technology](https://github.com/BUTSpeechFIT/VBx) | Apache-2.0 |
| &nbsp;&nbsp;↳ ZipVoice (its Zipformer operators, ported for voice cloning) | [k2-fsa](https://github.com/k2-fsa/ZipVoice) | Apache-2.0 |
| &nbsp;&nbsp;↳ Silero VAD model (`silero_vad.onnx`, converted to ORT format and compiled into `libmoonshine.so`) | [Silero Team](https://github.com/snakers4/silero-vad) | MIT |
| &nbsp;&nbsp;↳ Speaker diarization parameters (the PLDA and x-vector transforms of pyannote's community-1 pipeline, compiled in) | [pyannoteAI — speaker-diarization-community-1](https://huggingface.co/pyannote/speaker-diarization-community-1) | CC-BY-4.0 |
| &nbsp;&nbsp;↳ Voice-cloning reference clips (15 VCTK Corpus recordings cut to 4-second, 24 kHz mono clips, compiled in) | [CSTR, University of Edinburgh — VCTK Corpus](https://doi.org/10.7488/ds/2645) | CC-BY-4.0 |
| &nbsp;&nbsp;↳ ONNX Runtime (1.23.2 — `libonnxruntime.so`, Moonshine's minimal build) | [Microsoft](https://github.com/microsoft/onnxruntime) | MIT |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ Abseil (`abseil-cpp`) | [Google](https://github.com/abseil/abseil-cpp) | Apache-2.0 |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ Protocol Buffers (the runtime library, and the ONNX messages generated with it) | [Google](https://github.com/protocolbuffers/protobuf) | BSD-3-Clause (Moonshine) |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ ONNX (the model format's definitions) | [ONNX Project Contributors](https://github.com/onnx/onnx) | Apache-2.0 |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ FlatBuffers (the ORT model format) | [Google](https://github.com/google/flatbuffers) | Apache-2.0 |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ Microsoft GSL | [Microsoft](https://github.com/microsoft/GSL) | MIT |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ SafeInt | [dcleblanc/SafeInt](https://github.com/dcleblanc/SafeInt) | MIT |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ cpuinfo | [PyTorch](https://github.com/pytorch/cpuinfo) | BSD-2-Clause |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ KleidiAI (Arm's matrix-multiplication kernels, arm64 only) | [Arm](https://github.com/ARM-software/kleidiai) | Apache-2.0 |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ XNNPACK (the QNNPACK- and XNNPACK-derived kernels in ONNX Runtime's MLAS, arm64 only) | [Google](https://github.com/google/XNNPACK) | BSD-3-Clause (Moonshine) |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ TensorFlow (code ONNX Runtime derives from: its arena allocator, thread pool and status types) | [The TensorFlow Authors](https://github.com/tensorflow/tensorflow) | Apache-2.0 |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ Caffe2 (code ONNX Runtime derives from: its convolution and math routines) | [Facebook](https://github.com/pytorch/pytorch) | Apache-2.0 |
| &nbsp;&nbsp;&nbsp;&nbsp;↳ Pillow (the resampling filters of ONNX Runtime's Resize) | [python-pillow/Pillow](https://github.com/python-pillow/Pillow) | HPND |

`ai.moonshine:moonshine-voice` 0.1.6 is Moonshine AI's own Android AAR (© 2025 Useful Sensors, Inc., dba Moonshine
AI). In each of its three ABIs it packages `libmoonshine-jni.so`, `libmoonshine.so`, built from Moonshine's `core/` at
its `v0.1.6` tag, and `libonnxruntime.so`, the operator-reduced ONNX Runtime 1.23.2 that Moonshine vendors at that tag
(byte for byte). The rows under it are what those binaries carry, kept to what they show: the libraries of Moonshine's
`core/third-party/` and `core/cpp-annote/` that `libmoonshine.so`'s exported symbols name, the model data compiled into
it, and, under ONNX Runtime, the entries of ONNX Runtime's own `ThirdPartyNotices.txt` at `v1.23.2` that its strings and
source paths show. The speech, text-to-speech and diarization models the runtime loads are downloaded, not bundled.
utfcpp and Boost.Mp11 (header-only, both BSL-1.0) and the LLVM runtime ask no notice of compiled code, and MurmurHash3
is public domain, so they have no row.

The two CC-BY-4.0 rows are redistributed under the [Creative Commons Attribution 4.0 International
License](https://creativecommons.org/licenses/by/4.0/). The speaker-diarization parameters are pyannoteAI's
[`pyannote/speaker-diarization-community-1`](https://huggingface.co/pyannote/speaker-diarization-community-1), converted
to C arrays by cpp-annote. The reference clips come from the CSTR VCTK Corpus (Christophe Veaux, Junichi Yamagishi and
Kirsten MacDonald, The Centre for Speech Technology Research, University of Edinburgh), cut to 4-second, 24 kHz mono
clips and stored with a transcript of what each clip says. The texts are bundled as `BSD-3-Clause-Moonshine.txt`,
`utf8proc.txt`, `HPND-Pillow.txt` and `CC-BY-4.0.txt`; the MIT text carries the MIT rows' copyright lines, and the
Apache-2.0, MPL-2.0 and cpuinfo texts are shared with the rows above.

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
