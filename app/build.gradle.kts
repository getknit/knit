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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
