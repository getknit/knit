// Top-level build file where you can add configuration options common to all sub-projects/modules.

// Override AGP 9's built-in Kotlin compiler. AGP 9.2.1 bundles KGP 2.2.10 by default, whose Kotlin-2.2
// compiler cannot read class metadata produced by Kotlin 2.4 (which is why Coil was pinned to 3.3.0).
// Putting a newer KGP on the root buildscript classpath makes built-in Kotlin compile with 2.4.0
// instead — a supported combo (Kotlin 2.4 requires AGP 9.1+). This is what unpins Coil/Compose.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.androidx.room) apply false
    // ktlint is APPLIED on the root project (not `apply false`) so it lints the root Gradle scripts
    // (build.gradle.kts + settings.gradle.kts). It is also applied on :app for the Kotlin sources.
    alias(libs.plugins.ktlint)
}

// Pin the ktlint tool version for the root-project script lint (kept in lockstep with :app — see
// app/build.gradle.kts, which also configures the plain/html/sarif reporters for the app sources).
ktlint {
    version.set(libs.versions.ktlint.get())
}
