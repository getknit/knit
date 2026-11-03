// Knit's own detekt rules (ruleset `knit`, configured in config/detekt/detekt.yml). :app's and :wear's detekt tasks load
// this jar through their `detektPlugins` configurations and nothing else depends on it, so no APK ever carries a byte of it
// and F-Droid's release rebuild only configures it. See .agents/context/toolchain.md.
plugins {
    // On the classpath already: the root buildscript's kotlin-gradle-plugin (the Kotlin 2.4 override) provides it.
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    // Provided by detekt at run time; the stdlib arrives with it (gradle.properties keeps KGP from adding its own),
    // so the plugin jar carries no dependency of its own onto :app's detektPlugins classpath.
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(libs.junit)
}

ktlint {
    version.set(libs.versions.ktlint.get())
}

dependencyLocking {
    // As :app: every configuration pinned in detekt-rules/gradle.lockfile, regenerated with --write-locks.
    lockAllConfigurations()
}
