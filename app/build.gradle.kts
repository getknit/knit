plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.getknit.knit"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "app.getknit.knit"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }
    androidResources {
        // On-device model files must stay uncompressed so TFLite can mmap them from the APK.
        noCompress += listOf("tflite")
    }
    packaging {
        resources {
            // The ai.djl.huggingface:tokenizers jar bundles ~50 MB of desktop natives
            // (native/lib/{linux,osx,win}-*) as Java resources; on Android the .so comes from the
            // ai.djl.android:tokenizer-native AAR, so strip the desktop ones from the APK.
            excludes += "native/**"
        }
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
    implementation(libs.kotlinx.coroutines.play.services)

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

    // Mesh transport
    implementation(libs.play.services.nearby)

    // Images
    implementation(libs.coil.compose)
    implementation(libs.coil.gif) // animated GIF/WebP decoding (keyboard GIFs)

    // On-device NSFW image classifier runtime (no network; model bundled in assets). See the version
    // catalog for why the bare TFLite interpreter is used rather than MediaPipe/LiteRT.
    implementation(libs.tensorflow.lite)

    // On-device tokenizer for the text toxicity classifier — HuggingFace tokenizers over the bundled
    // tokenizer.json (exact training parity). Maven Central; offline arm64/x86_64 .so via the -native
    // AAR; no INTERNET. Desktop natives in the tokenizers jar are stripped via packaging { } above.
    implementation(libs.djl.huggingface.tokenizers)
    implementation(libs.djl.android.tokenizer.native)

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
