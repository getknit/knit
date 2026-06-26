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
}

// --- detekt (local static analysis) ----------------------------------------------------------
// Run with: ./gradlew detekt
//
// Deliberately NOT the detekt Gradle plugin. This toolchain (AGP 9.2.1 / Kotlin 2.2.10) breaks
// third-party Gradle plugins (the same reason Koin replaced Hilt — see AGENTS.md), so detekt runs
// as the standalone CLI, exactly like CI's `verify:detekt` job. The CLI and its Kotlin-1.9
// compiler-embeddable are isolated in the `detektCli` configuration below; they never touch :app's
// compile/runtime classpath, so they can't perturb the app build. Same jar version + config + flags
// as CI, so a clean run here means a clean run there.
val detektCli: Configuration by configurations.creating

dependencies {
    detektCli(libs.detekt.cli)
}

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Runs detekt static analysis via the standalone CLI (mirrors CI verify:detekt)."

    val mainSrc = layout.projectDirectory.dir("app/src/main/java")
    val testSrc = layout.projectDirectory.dir("app/src/test/java")
    val configFile = layout.projectDirectory.file("config/detekt/detekt.yml")
    val reportsDir = layout.buildDirectory.dir("reports/detekt")

    // Wire up inputs/outputs so the task is up-to-date-aware and reruns only when sources/config change.
    inputs.dir(mainSrc)
    inputs.dir(testSrc)
    inputs.file(configFile)
    outputs.dir(reportsDir)

    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")

    val reports = reportsDir.get().asFile
    args(
        "--input", "${mainSrc.asFile},${testSrc.asFile}",
        "--config", configFile.asFile.absolutePath,
        "--build-upon-default-config",      // overlay config/detekt/detekt.yml on detekt's defaults
        "--jvm-target", "11",               // matches the app's compileOptions target
        "--report", "xml:${reports.resolve("detekt.xml")}",
        "--report", "html:${reports.resolve("detekt.html")}",
        "--report", "sarif:${reports.resolve("detekt.sarif")}",
    )

    doFirst { reports.mkdirs() }
}