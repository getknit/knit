plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.getknit.knit"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        applicationId = "app.getknit.knit"
        // Wi-Fi Aware transport needs API 33: Instant Communication Mode (fast discovery + data-path
        // bring-up) and NEARBY_WIFI_DEVICES + neverForLocation (so the mesh needs no location permission).
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Demo-screenshot mode: when built with `-PseedDemo=true`, the app seeds a realistic data set
        // and swaps in a no-op transport so every screen renders populated on an emulator (no real
        // mesh). Defaults false, so release/normal builds are unaffected. See DemoSeeder/DemoTransport.
        val seedDemo = (project.findProperty("seedDemo") as? String)?.toBoolean() == true
        buildConfigField("boolean", "SEED_DEMO", seedDemo.toString())
        // Which seed scenario DemoSeeder loads: "hiking" (default) or "festival". Only meaningful when
        // seedDemo is on; picks the persona/message/avatar set so we can shoot multiple marketing themes.
        val demoTheme = (project.findProperty("demoTheme") as? String) ?: "hiking"
        buildConfigField("String", "DEMO_THEME", "\"$demoTheme\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // On-device model files must stay uncompressed so TFLite can mmap them from the APK.
        noCompress += listOf("tflite")
    }

    testOptions {
        unitTests {
            // Robolectric runs the JVM Room/DAO + migration tests (finding #5): it reads AGP's merged
            // manifest/resources config and supplies a Context + framework SQLite so in-memory Room
            // executes the real eviction/GC SQL. See app/src/test/java/app/getknit/knit/data/ and
            // app/src/test/resources/robolectric.properties.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        // Serve the exported Room schemas (app/schemas/) as DEBUG-variant assets so MigrationTestHelper can
        // load them under Robolectric — it reads the schema from context.assets/<db-class>/<version>.json, and
        // Robolectric serves the merged *debug* assets (unit tests run against the debug variant), but not the
        // `test` source set's own assets. Scoped to debug, so the ~15 KB schema JSON never ships in release.
        // See KnitDatabaseMigrationTest.
        getByName("debug") {
            assets.srcDir("schemas")
        }
    }
}

ksp {
    // Export the Room schema JSON (app/schemas/<db-class>/<version>.json) so schema changes are diffable in
    // review and the migration test's MigrationTestHelper can read them. Plugin-free — no Room Gradle plugin
    // (same toolchain caution as Koin-not-Hilt). Requires exportSchema = true on KnitDatabase; regenerate the
    // checked-in schema by building after any @Database version bump.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencyLocking {
    // Lock resolved versions to app/gradle.lockfile so Trivy (and reproducible builds) have a concrete
    // dependency manifest to scan — there is no other lockfile/SBOM. Native Gradle, no plugin. Regenerate
    // with `./gradlew :app:dependencies --write-locks` after bumping versions in libs.versions.toml.
    lockAllConfigurations()
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization (wire protocol: CBOR for compact mesh frames; JSON for the file-header sidecar)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)

    // Persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.sqlcipher.android) // at-rest encryption for the Room DB (SQLCipher)

    // Dependency injection (Koin — pure-Kotlin, no Gradle plugin / no AGP coupling)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Mesh transport is Wi-Fi Aware (android.net.wifi.aware.*, framework API) — no external dependency.

    // Images
    implementation(libs.coil.compose)
    implementation(libs.coil.gif) // animated GIF/WebP decoding (keyboard GIFs)

    // On-device ML runtimes (no network; models bundled in assets). See the version catalog for why
    // the bare classic interpreter (LiteRT 1.4.x) is used rather than MediaPipe or the heavier LiteRT 2.x.
    // The text toxicity tokenizer is pure Kotlin (SentencePieceTokenizer, parses tokenizer.json via
    // kotlinx-serialization) — no native tokenizer lib, so nothing to 16 KB-align and no .so added to the APK.
    implementation(libs.litert)

    // E2E encryption (Tink — Java + native, no Kotlin metadata / no Gradle plugin, like SQLCipher)
    implementation(libs.tink.android)
    // QR identity verification (safety-number / QR verify screen)
    implementation(libs.zxing.android.embedded)
    implementation(libs.zxing.core)

    // Offline "Share Knit app": when installed from Play as an App Bundle, merge the on-device split
    // APKs into one universal APK (ARSCLib) and re-sign it (apksig). Both pure Java, no Kotlin metadata —
    // see gradle/libs.versions.toml. Used only by ui/invite/ApkMerger.kt.
    implementation(libs.reandroid.arsclib)
    implementation(libs.apksig)

    // Play In-App Review — optional at runtime: ReviewPrompter pre-gates on the Play installer and
    // swallows failures, so de-googled/sideloaded devices are unaffected. See gradle/libs.versions.toml.
    implementation(libs.play.review.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.mockk) // relaxed mocks of the concrete Room-backed repos in InboundPipelineTest
    // JVM Room/DAO + migration tests (finding #5): Robolectric supplies a Context + framework SQLite so
    // in-memory Room runs the real eviction/GC SQL, and room-testing's MigrationTestHelper rebuilds the
    // exported schema on that same shadowed SQLite (via androidx.sqlite's AndroidSQLiteDriver, already pulled
    // by Room). See app/src/test/java/app/getknit/knit/data/.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit) // AndroidJUnit4 runner on the JVM (delegated by Robolectric)
    testImplementation(libs.androidx.test.core) // ApplicationProvider.getApplicationContext()
    testImplementation(libs.androidx.room.testing) // MigrationTestHelper (was androidTest-only)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
