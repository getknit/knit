// Top-level build file where you can add configuration options common to all sub-projects/modules.

import org.gradle.api.attributes.Bundling

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
        "--input",
        "${mainSrc.asFile},${testSrc.asFile}",
        "--config",
        configFile.asFile.absolutePath,
        "--build-upon-default-config", // overlay config/detekt/detekt.yml on detekt's defaults
        "--jvm-target",
        "11", // matches the app's compileOptions target
        "--report",
        "xml:${reports.resolve("detekt.xml")}",
        "--report",
        "html:${reports.resolve("detekt.html")}",
        "--report",
        "sarif:${reports.resolve("detekt.sarif")}",
    )

    doFirst { reports.mkdirs() }
}

// --- ktlint (local Kotlin style/format lint) --------------------------------------------------
// Run with: ./gradlew ktlint   (autocorrect locally with the `ktlint --format` CLI, not this task).
//
// Like detekt above, deliberately NOT the ktlint Gradle plugin: the same AGP-9.2.1/Kotlin-2.4
// toolchain that broke Hilt's plugin (and why detekt runs standalone) can break third-party Gradle
// plugins, so ktlint runs as the standalone CLI. Its jar + transitive rulesets are isolated in the
// `ktlintCli` configuration below and never touch :app's compile/runtime classpath. Rules are the
// ktlint standard ruleset configured through the repo-root `.editorconfig` (which the CLI
// auto-discovers), including the @Composable function-naming exemption.
//
// ktlint-cli publishes two variants (thin `external` + `shadowed` uber jar); pin the shadowed one —
// the self-contained fat jar that IS the standalone CLI distribution — so resolution is unambiguous
// and pulls no transitive rulesets onto this configuration.
val ktlintCli: Configuration by configurations.creating {
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.SHADOWED))
    }
}

dependencies {
    ktlintCli(libs.ktlint.cli)
}

tasks.register<JavaExec>("ktlint") {
    group = "verification"
    description = "Runs ktlint style/format checks via the standalone CLI (uses the root .editorconfig)."

    val editorConfig = layout.projectDirectory.file(".editorconfig")
    val gradleScripts =
        listOf(
            layout.projectDirectory.file("build.gradle.kts"),
            layout.projectDirectory.file("settings.gradle.kts"),
            layout.projectDirectory.file("app/build.gradle.kts"),
        )
    val reportsDir = layout.buildDirectory.dir("reports/ktlint")

    // Up-to-date-aware: rerun only when a linted .kt source, a gradle script, or the .editorconfig
    // changes. Fingerprint only the .kt files (not all of app/src — that holds the 30 MB tflite assets).
    inputs
        .files(project.fileTree("app/src") { include("**/*.kt") })
        .withPropertyName("kotlinSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(gradleScripts).withPropertyName("gradleScripts")
    inputs.file(editorConfig)
    outputs.dir(reportsDir)

    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")

    val reports = reportsDir.get().asFile
    // Positional globs are resolved by ktlint relative to the working dir (the repo root); the `!`
    // negation keeps generated sources under any build/ dir out. Same standalone jar + `.editorconfig`
    // rules as the `ktlint` CLI a contributor runs, so a clean run here means a clean run there.
    args(
        "app/src/**/*.kt",
        "**/*.gradle.kts",
        "!**/build/**",
        "--reporter=plain",
        "--reporter=html,output=${reports.resolve("ktlint.html")}",
        "--reporter=sarif,output=${reports.resolve("ktlint.sarif")}",
    )

    doFirst { reports.mkdirs() }
}
