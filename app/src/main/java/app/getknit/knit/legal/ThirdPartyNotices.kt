package app.getknit.knit.legal

/** Whether a notice is a library on the classpath or a data file under `assets/`. Splits the in-app list. */
enum class NoticeKind { RUNTIME, DATA }

/**
 * Which builds package a notice's component. [LIVE_CALLS] rows are WebRTC's and what its `.so` links (ADR 2026-10.3tee,
 * 2026-10.swvp), [TRANSLATE] rows the offline translation runtime's (ADR 2026-10.t2h6), [STT] rows the speech runtime's
 * (ADR 2026-10.nz3g, 2026-10.am8d). All three are on the default release runtime, so `ReleaseClasspathNoticesTest` finds
 * their coordinates on the lockfile's `releaseRuntimeClasspath`, and each drops out of a build that does not package it:
 * the slim APK, or `-PliveCalls=false`, `-Ptranslate=false`, `-Pstt=false`, and for speech a build with no call kind.
 * The in-app list shows each kind only where it is wired ([ThirdPartyNotices.shipped]), and `THIRD-PARTY-NOTICES.md`
 * keeps each in a section of its own.
 */
enum class Packaging { ALWAYS, LIVE_CALLS, TRANSLATE, STT }

/**
 * One row of the in-app Open-source licenses list — and of `THIRD-PARTY-NOTICES.md`, which prints the same
 * [name], [project], [url] and [license] and is kept in step by `ThirdPartyNoticesSyncTest`.
 *
 * [artifacts] are plain prefixes over `"group:artifact"` that this row claims on the release runtime
 * classpath; `ReleaseClasspathNoticesTest` walks `app/gradle.lockfile` and fails when a coordinate has no
 * claimant, so a new shipped dependency cannot slip past the list. Conventions: a trailing `.` claims a
 * group subtree (`androidx.`), a trailing `:` an exact group (`org.jetbrains.kotlin:` — the colon is what
 * keeps it off `kotlinx`), and `group:artifact-prefix` a family inside a group. Longest prefix wins. Empty
 * for a notice with no coordinate of its own (OpenSSL inside SQLCipher's `.so`; the bundled data files; the code and
 * model data inside Moonshine's native libraries).
 */
data class ThirdPartyNotice(
    val key: String,
    val name: String,
    val project: String,
    val url: String,
    val license: License,
    val kind: NoticeKind = NoticeKind.RUNTIME,
    val artifacts: Set<String> = emptySet(),
    val packaging: Packaging = Packaging.ALWAYS,
)

/**
 * The shipped third-party components, in the order `THIRD-PARTY-NOTICES.md` lists them, the speech runtime's last
 * ([SpeechNotices], as its table is). Hand-kept on purpose:
 * a license plugin would put code on `:app`'s classpath and a generated file into a build F-Droid must
 * reproduce byte for byte; the two sync tests do the plugin's job at test time instead.
 */
object ThirdPartyNotices {
    val ALL: List<ThirdPartyNotice> =
        listOf(
            ThirdPartyNotice(
                key = "androidx",
                name = "Android Jetpack / AndroidX",
                project = "Android Open Source Project",
                url = "https://developer.android.com/jetpack",
                license = License.APACHE_2_0,
                artifacts = setOf("androidx."),
            ),
            ThirdPartyNotice(
                key = "compose",
                name = "Jetpack Compose",
                project = "Android Open Source Project",
                url = "https://developer.android.com/jetpack/compose",
                license = License.APACHE_2_0,
                // The subtree, plus the bare group for the `androidx.compose:compose-bom` coordinate itself.
                artifacts = setOf("androidx.compose.", "androidx.compose:"),
            ),
            ThirdPartyNotice(
                key = "compose-multiplatform",
                name = "Compose Multiplatform runtime",
                project = "JetBrains",
                url = "https://github.com/JetBrains/compose-multiplatform",
                license = License.APACHE_2_0,
                artifacts = setOf("org.jetbrains.compose.", "org.jetbrains.androidx."),
            ),
            ThirdPartyNotice(
                key = "kotlin",
                name = "Kotlin standard library",
                project = "JetBrains — Kotlin",
                url = "https://github.com/JetBrains/kotlin",
                license = License.APACHE_2_0,
                artifacts = setOf("org.jetbrains.kotlin:"),
            ),
            ThirdPartyNotice(
                key = "coroutines",
                name = "kotlinx.coroutines",
                project = "JetBrains — kotlinx.coroutines",
                url = "https://github.com/Kotlin/kotlinx.coroutines",
                license = License.APACHE_2_0,
                artifacts = setOf("org.jetbrains.kotlinx:kotlinx-coroutines", "org.jetbrains.kotlinx:atomicfu"),
            ),
            ThirdPartyNotice(
                key = "serialization",
                name = "kotlinx.serialization",
                project = "JetBrains — kotlinx.serialization",
                url = "https://github.com/Kotlin/kotlinx.serialization",
                license = License.APACHE_2_0,
                artifacts = setOf("org.jetbrains.kotlinx:kotlinx-serialization"),
            ),
            ThirdPartyNotice(
                key = "koin",
                name = "Koin",
                project = "InsertKoinIO / Koin",
                url = "https://github.com/InsertKoinIO/koin",
                license = License.APACHE_2_0,
                artifacts = setOf("io.insert-koin:"),
            ),
            ThirdPartyNotice(
                key = "stately",
                name = "Stately",
                project = "Touchlab",
                url = "https://github.com/touchlab/Stately",
                license = License.APACHE_2_0,
                artifacts = setOf("co.touchlab:"),
            ),
            ThirdPartyNotice(
                key = "coil",
                name = "Coil 3",
                project = "Coil",
                url = "https://github.com/coil-kt/coil",
                license = License.APACHE_2_0,
                artifacts = setOf("io.coil-kt.coil3:"),
            ),
            ThirdPartyNotice(
                key = "accompanist",
                name = "Accompanist DrawablePainter",
                project = "google/accompanist",
                url = "https://github.com/google/accompanist",
                license = License.APACHE_2_0,
                artifacts = setOf("com.google.accompanist:"),
            ),
            ThirdPartyNotice(
                key = "okhttp",
                name = "OkHttp",
                project = "Square",
                url = "https://github.com/square/okhttp",
                license = License.APACHE_2_0,
                artifacts = setOf("com.squareup.okhttp3:"),
            ),
            ThirdPartyNotice(
                key = "okio",
                name = "Okio",
                project = "Square",
                url = "https://github.com/square/okio",
                license = License.APACHE_2_0,
                artifacts = setOf("com.squareup.okio:"),
            ),
            ThirdPartyNotice(
                key = "guava",
                name = "Guava",
                project = "Google",
                url = "https://github.com/google/guava",
                license = License.APACHE_2_0,
                artifacts = setOf("com.google.guava:"),
            ),
            ThirdPartyNotice(
                key = "gson",
                name = "Gson",
                project = "Google",
                url = "https://github.com/google/gson",
                license = License.APACHE_2_0,
                artifacts = setOf("com.google.code.gson:"),
            ),
            ThirdPartyNotice(
                key = "dagger",
                name = "Dagger",
                project = "Google",
                url = "https://github.com/google/dagger",
                license = License.APACHE_2_0,
                // `Provider` is runtime code Dagger uses, so the two inject APIs ride with it rather than
                // sitting in NOT_SHIPPED with the annotation-only jars.
                artifacts = setOf("com.google.dagger:", "javax.inject:", "jakarta.inject:"),
            ),
            ThirdPartyNotice(
                key = "tink",
                name = "Google Tink",
                project = "Tink",
                url = "https://github.com/tink-crypto/tink-java",
                license = License.APACHE_2_0,
                artifacts = setOf("com.google.crypto.tink:"),
            ),
            ThirdPartyNotice(
                key = "litert",
                name = "LiteRT / TensorFlow Lite",
                project = "Google AI Edge — LiteRT",
                url = "https://github.com/google-ai-edge/LiteRT",
                license = License.APACHE_2_0,
                artifacts = setOf("com.google.ai.edge.litert:"),
            ),
            ThirdPartyNotice(
                key = "sqlcipher",
                name = "SQLCipher for Android",
                project = "SQLCipher",
                url = "https://github.com/sqlcipher/sqlcipher-android",
                license = License.BSD_3_CLAUSE,
                artifacts = setOf("net.zetetic:"),
            ),
            ThirdPartyNotice(
                key = "openssl",
                name = "OpenSSL",
                project = "The OpenSSL Project",
                url = "https://www.openssl.org/",
                license = License.APACHE_2_0,
            ),
            ThirdPartyNotice(
                key = "zxing",
                name = "ZXing core",
                project = "ZXing",
                url = "https://github.com/zxing/zxing",
                license = License.APACHE_2_0,
                artifacts = setOf("com.google.zxing:"),
            ),
            ThirdPartyNotice(
                key = "arsclib",
                name = "ARSCLib",
                project = "REAndroid/ARSCLib",
                url = "https://github.com/REAndroid/ARSCLib",
                license = License.APACHE_2_0,
                artifacts = setOf("io.github.reandroid:"),
            ),
            ThirdPartyNotice(
                key = "apksig",
                name = "apksig",
                project = "Android Open Source Project",
                url = "https://android.googlesource.com/platform/tools/apksig/",
                license = License.APACHE_2_0,
                artifacts = setOf("com.android.tools.build:"),
            ),
            ThirdPartyNotice(
                key = "webrtc",
                name = "WebRTC",
                project = "The WebRTC project authors",
                url = "https://webrtc.googlesource.com/src/",
                license = License.BSD_3_CLAUSE_WEBRTC,
                // The community build of Google's retired libwebrtc AAR (libs.versions.toml).
                artifacts = setOf("io.github.webrtc-sdk:"),
                packaging = Packaging.LIVE_CALLS,
            ),
            // What libwebrtc links into its `.so`, from WebRTC's own `tools_webrtc/libs/generate_licenses.py` at the
            // AAR's branch (7871), kept to what the binary shows. No coordinate of its own, like OpenSSL in SQLCipher.
            ThirdPartyNotice(
                key = "webrtc-abseil",
                name = "Abseil",
                project = "Google",
                url = "https://github.com/abseil/abseil-cpp",
                license = License.APACHE_2_0,
                packaging = Packaging.LIVE_CALLS,
            ),
            ThirdPartyNotice(
                key = "webrtc-boringssl",
                name = "BoringSSL",
                project = "Google",
                url = "https://boringssl.googlesource.com/boringssl/",
                license = License.APACHE_2_0,
                packaging = Packaging.LIVE_CALLS,
            ),
            ThirdPartyNotice(
                key = "webrtc-libsrtp",
                name = "libsrtp",
                project = "Cisco Systems",
                url = "https://github.com/cisco/libsrtp",
                license = License.BSD_3_CLAUSE_WEBRTC,
                packaging = Packaging.LIVE_CALLS,
            ),
            ThirdPartyNotice(
                key = "webrtc-opus",
                name = "Opus",
                project = "Xiph.Org Foundation",
                url = "https://opus-codec.org/",
                license = License.BSD_3_CLAUSE_WEBRTC,
                packaging = Packaging.LIVE_CALLS,
            ),
            ThirdPartyNotice(
                key = "webrtc-rnnoise",
                name = "RNNoise",
                project = "Xiph.Org Foundation",
                url = "https://github.com/xiph/rnnoise",
                license = License.BSD_3_CLAUSE_WEBRTC,
                packaging = Packaging.LIVE_CALLS,
            ),
            ThirdPartyNotice(
                key = "webrtc-libvpx",
                name = "libvpx",
                project = "The WebM Project",
                url = "https://chromium.googlesource.com/webm/libvpx",
                license = License.BSD_3_CLAUSE_WEBRTC,
                packaging = Packaging.LIVE_CALLS,
            ),
            ThirdPartyNotice(
                key = "webrtc-libyuv",
                name = "libyuv",
                project = "The LibYuv Project",
                url = "https://chromium.googlesource.com/libyuv/libyuv",
                license = License.BSD_3_CLAUSE_WEBRTC,
                packaging = Packaging.LIVE_CALLS,
            ),
            ThirdPartyNotice(
                key = "webrtc-crc32c",
                name = "CRC32C",
                project = "Google",
                url = "https://github.com/google/crc32c",
                license = License.BSD_3_CLAUSE_WEBRTC,
                packaging = Packaging.LIVE_CALLS,
            ),
            ThirdPartyNotice(
                key = "webrtc-jni-zero",
                name = "JNI Zero",
                project = "The Chromium Authors",
                url = "https://chromium.googlesource.com/chromium/src/third_party/jni_zero/",
                license = License.BSD_3_CLAUSE_WEBRTC,
                packaging = Packaging.LIVE_CALLS,
            ),
            ThirdPartyNotice(
                key = "webrtc-libaom",
                name = "libaom",
                project = "Alliance for Open Media",
                url = "https://aomedia.googlesource.com/aom/",
                license = License.BSD_2_CLAUSE_WEBRTC,
                packaging = Packaging.LIVE_CALLS,
            ),
            ThirdPartyNotice(
                key = "webrtc-dav1d",
                name = "dav1d",
                project = "VideoLAN",
                url = "https://code.videolan.org/videolan/dav1d",
                license = License.BSD_2_CLAUSE_WEBRTC,
                packaging = Packaging.LIVE_CALLS,
            ),
            ThirdPartyNotice(
                key = "webrtc-sframe",
                name = "SFrame",
                project = "Cisco Systems",
                url = "https://github.com/cisco/sframe",
                license = License.BSD_2_CLAUSE_WEBRTC,
                packaging = Packaging.LIVE_CALLS,
            ),
            ThirdPartyNotice(
                key = "webrtc-audio",
                name = "WebRTC's bundled audio routines",
                project = "The WebRTC project authors",
                url = "https://webrtc.googlesource.com/src/",
                license = License.WEBRTC_AUDIO,
                packaging = Packaging.LIVE_CALLS,
            ),
            // The offline translation runtime: our own `translate-native` AAR (built from the knit-translate-native
            // repository), whose one native library statically links slimt and everything below it.
            ThirdPartyNotice(
                key = "slimt",
                name = "slimt",
                project = "slimt Authors",
                url = "https://github.com/jerinphilip/slimt",
                license = License.GPL_2_0_OR_LATER,
                artifacts = setOf("app.getknit:translate-native"),
                packaging = Packaging.TRANSLATE,
            ),
            ThirdPartyNotice(
                key = "slimt-bergamot",
                name = "bergamot-translator and marian-dev",
                project = "browsermt",
                url = "https://github.com/browsermt/bergamot-translator",
                license = License.MPL_2_0,
                packaging = Packaging.TRANSLATE,
            ),
            ThirdPartyNotice(
                key = "slimt-ssplit",
                name = "ssplit",
                project = "browsermt",
                url = "https://github.com/browsermt/ssplit-cpp",
                license = License.APACHE_2_0,
                packaging = Packaging.TRANSLATE,
            ),
            ThirdPartyNotice(
                key = "ruy",
                name = "ruy",
                project = "Google",
                url = "https://github.com/google/ruy",
                license = License.APACHE_2_0,
                packaging = Packaging.TRANSLATE,
            ),
            ThirdPartyNotice(
                key = "cpuinfo",
                name = "cpuinfo",
                project = "PyTorch",
                url = "https://github.com/pytorch/cpuinfo",
                license = License.BSD_2_CLAUSE_CPUINFO,
                packaging = Packaging.TRANSLATE,
            ),
            ThirdPartyNotice(
                key = "sentencepiece",
                name = "SentencePiece",
                project = "Google",
                url = "https://github.com/google/sentencepiece",
                license = License.APACHE_2_0,
                packaging = Packaging.TRANSLATE,
            ),
            ThirdPartyNotice(
                key = "cld2",
                name = "CLD2",
                project = "CLD2 Owners",
                url = "https://github.com/CLD2Owners/cld2",
                license = License.APACHE_2_0,
                packaging = Packaging.TRANSLATE,
            ),
            ThirdPartyNotice(
                key = "nsfw-model",
                name = "NSFW image classifier",
                project = "GantMan/nsfw_model",
                url = "https://github.com/GantMan/nsfw_model",
                license = License.MIT,
                kind = NoticeKind.DATA,
            ),
            ThirdPartyNotice(
                key = "detoxify",
                name = "Toxicity text classifier",
                project = "unitaryai/detoxify",
                url = "https://github.com/unitaryai/detoxify",
                license = License.APACHE_2_0,
                kind = NoticeKind.DATA,
            ),
            ThirdPartyNotice(
                key = "profanity-list",
                name = "Profanity word list",
                project = "dsojevic/profanity-list",
                url = "https://github.com/dsojevic/profanity-list",
                license = License.MIT,
                kind = NoticeKind.DATA,
            ),
            ThirdPartyNotice(
                key = "emoji-catalog",
                name = "Emoji catalog",
                project = "Unicode, Inc. — emoji-test.txt",
                url = "https://www.unicode.org/reports/tr51",
                license = License.UNICODE_3_0,
                kind = NoticeKind.DATA,
            ),
            ThirdPartyNotice(
                key = "emoji-keywords",
                name = "Emoji names and search keywords",
                project = "Unicode, Inc. — CLDR emoji annotations",
                url = "https://github.com/unicode-org/cldr",
                license = License.UNICODE_3_0,
                kind = NoticeKind.DATA,
            ),
            ThirdPartyNotice(
                key = "emojilib",
                name = "More emoji search keywords",
                project = "Mu-An Chiou — emojilib",
                url = "https://github.com/muan/emojilib",
                license = License.MIT,
                kind = NoticeKind.DATA,
            ),
        ) + SpeechNotices.ALL

    /**
     * Release-classpath coordinates that ship no runtime code, so they need no notice: annotation-only jars
     * R8 strips to nothing. Prefix → why. `ReleaseClasspathNoticesTest` insists every entry still matches
     * something, so a dropped dependency takes its allowlist line with it.
     */
    val NOT_SHIPPED: Map<String, String> =
        mapOf(
            "org.jspecify:" to ANNOTATIONS_ONLY,
            "org.checkerframework:" to ANNOTATIONS_ONLY,
            "com.google.code.findbugs:" to ANNOTATIONS_ONLY,
            "com.google.errorprone:" to ANNOTATIONS_ONLY,
            "com.google.j2objc:" to ANNOTATIONS_ONLY,
            "com.google.auto.value:" to ANNOTATIONS_ONLY,
            "org.jetbrains:annotations" to ANNOTATIONS_ONLY,
        )

    /**
     * The rows a build shows: every [Packaging.ALWAYS] one, the [Packaging.LIVE_CALLS] ones where [liveCalls], the
     * [Packaging.TRANSLATE] ones where [translate] and the [Packaging.STT] ones where [stt].
     */
    fun shipped(
        liveCalls: Boolean,
        translate: Boolean,
        stt: Boolean,
    ): List<ThirdPartyNotice> =
        ALL.filter {
            when (it.packaging) {
                Packaging.ALWAYS -> true
                Packaging.LIVE_CALLS -> liveCalls
                Packaging.TRANSLATE -> translate
                Packaging.STT -> stt
            }
        }

    /** The notice whose longest [ThirdPartyNotice.artifacts] prefix matches [coordinate] (`group:artifact`), or null. */
    fun claimant(coordinate: String): ThirdPartyNotice? =
        ALL
            .flatMap { notice -> notice.artifacts.map { prefix -> prefix to notice } }
            .filter { (prefix, _) -> coordinate.startsWith(prefix) }
            .maxByOrNull { (prefix, _) -> prefix.length }
            ?.second

    private const val ANNOTATIONS_ONLY = "annotations only, no runtime code; R8 drops every reference"
}
