import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

// Release signing, as :app's (see the loader there): keystore.properties at the repo root, else KNIT_SIGNING_* /
// KNIT_UPLOAD_* env vars, else unsigned. The watch app only ever goes to Play, in the phone app's listing, so the
// credentials to hand it are the Play *upload* key's: Play App Signing then signs both with the one app signing
// key (Wear OS quality WO-G7: same package name, same signing key). Never the F-Droid distribution key.
val keystoreProps =
    Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

fun releaseSigningCred(
    prop: String,
    envSuffix: String,
): String? =
    (
        keystoreProps.getProperty(prop)
            ?: System.getenv("KNIT_SIGNING_$envSuffix")
            ?: System.getenv("KNIT_UPLOAD_$envSuffix")
    )?.takeIf { it.isNotBlank() }

// Play wants a watch APK's version code unique across every form factor in the listing, on a scheme of its own.
// Deriving it from the phone's keeps one counter (gradle.properties) and keeps both rising together.
val wearVersionCodeBase = 1_000_000_000

// The Wear OS watch app for the mesh-status prototype: four complications and a status screen, fed by one
// read of the phone's `WearStatusServer` GATT characteristic. In the build only under `-Pknit.wear=true`
// (settings.gradle.kts), so nothing here ever reaches :app's graph or a shipped artifact.
android {
    namespace = "app.getknit.knit.wear"
    // Lockstep with :app (see the compileSdk / buildToolsVersion comments there).
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 1
            }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // Wear OS convention: the watch app shares the phone app's id (Play pairs the two by it).
        applicationId = "app.getknit.knit"
        // Wear OS 3 (API 30) — the lab's watch.
        minSdk = 30
        targetSdk = 36
        versionCode = wearVersionCodeBase + providers.gradleProperty("knit.versionCode").get().toInt()
        versionName = providers.gradleProperty("knit.versionName").get()
    }

    signingConfigs {
        val store = releaseSigningCred("storeFile", "STORE_FILE")
        val storePass = releaseSigningCred("storePassword", "STORE_PASSWORD")
        val alias = releaseSigningCred("keyAlias", "KEY_ALIAS")
        val keyPass = releaseSigningCred("keyPassword", "KEY_PASSWORD")
        if (store != null && storePass != null && alias != null && keyPass != null) {
            create("release") {
                storeFile = file(store)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
        }
        getByName("debug") {
            // A debuggable APK runs Compose (and the tile's ProtoLayout) without ART's JIT/AOT optimisations,
            // which on a watch's small cores is visibly laggy. Still the debug variant — debug key, the debug-only
            // DemoReceiver — just not debuggable, so no run-as and no debugger attach.
            isDebuggable = false
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        // The one codec both ends read — compiled here from :app's tree rather than copied, so a phone build
        // and a watch build cannot disagree on a byte. The package is pure Kotlin by rule (see its header).
        getByName("main") {
            kotlin.directories.add("../app/src/main/java/app/getknit/knit/wearstatus")
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(files("src/main/java", "src/test/java"))
}

ktlint {
    version.set(libs.versions.ktlint.get())
    reporters {
        reporter(ReporterType.PLAIN)
    }
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.complications.data.source.ktx)
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.expression)
    implementation(libs.androidx.wear.protolayout.material3)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
