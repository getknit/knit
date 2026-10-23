package app.getknit.knit.legal

/** Whether a notice is a library on the classpath or a data file under `assets/`. Splits the in-app list. */
enum class NoticeKind { RUNTIME, DATA }

/**
 * One row of the in-app Open-source licenses list — and of `THIRD-PARTY-NOTICES.md`, which prints the same
 * [name], [project], [url] and [license] and is kept in step by `ThirdPartyNoticesSyncTest`.
 *
 * [artifacts] are plain prefixes over `"group:artifact"` that this row claims on the release runtime
 * classpath; `ReleaseClasspathNoticesTest` walks `app/gradle.lockfile` and fails when a coordinate has no
 * claimant, so a new shipped dependency cannot slip past the list. Conventions: a trailing `.` claims a
 * group subtree (`androidx.`), a trailing `:` an exact group (`org.jetbrains.kotlin:` — the colon is what
 * keeps it off `kotlinx`), and `group:artifact-prefix` a family inside a group. Longest prefix wins. Empty
 * for a notice with no coordinate of its own (OpenSSL inside SQLCipher's `.so`; the bundled data files).
 */
data class ThirdPartyNotice(
    val key: String,
    val name: String,
    val project: String,
    val url: String,
    val license: License,
    val kind: NoticeKind = NoticeKind.RUNTIME,
    val artifacts: Set<String> = emptySet(),
)

/**
 * The shipped third-party components, in the order `THIRD-PARTY-NOTICES.md` lists them. Hand-kept on purpose:
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
        )

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

    /** The notice whose longest [ThirdPartyNotice.artifacts] prefix matches [coordinate] (`group:artifact`), or null. */
    fun claimant(coordinate: String): ThirdPartyNotice? =
        ALL
            .flatMap { notice -> notice.artifacts.map { prefix -> prefix to notice } }
            .filter { (prefix, _) -> coordinate.startsWith(prefix) }
            .maxByOrNull { (prefix, _) -> prefix.length }
            ?.second

    private const val ANNOTATIONS_ONLY = "annotations only, no runtime code; R8 drops every reference"
}
