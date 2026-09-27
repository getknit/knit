import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

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
        versionCode = providers.gradleProperty("knit.versionCode").get().toInt()
        versionName = providers.gradleProperty("knit.versionName").get()
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
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.complications.data.source.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
