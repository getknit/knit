pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// NO foojay-resolver-convention here, deliberately. That plugin auto-downloads a JDK from api.foojay.io
// when the requested toolchain isn't installed locally. This project already requires JDK 21 (see
// gradle/gradle-daemon-jvm.properties and .agents/rules/build-and-test.md), and for a build that has to be
// byte-reproducible on someone else's machine, "silently fetch a JDK over the network" is the wrong
// failure mode twice over: it makes the compiler a moving, unpinned input, and it needs outbound network
// F-Droid's buildserver may not grant. Without it Gradle fails loudly with "no matching toolchain", which
// the builder fixes by installing JDK 21 (the fdroiddata recipe does this in its `sudo:` block).
// For reference, apps that keep this plugin have to get it patched out of their F-Droid recipe — see
// `prebuild: sed -i -e '/foojay-resolver/d' ../settings.gradle.kts` in metadata/com.geeksville.mesh.yml.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Knit"
include(":app")
// Knit's own detekt rules (the `knit` ruleset), which :app's detekt task loads. Tool-only: nothing depends on it but
// :app's detektPlugins configuration, so an ordinary build, F-Droid's included, configures it and never builds it.
include(":detekt-rules")

// The baseline-profile generator is maintainer-only: a `com.android.test` module that drives the app on a
// device to produce `app/src/main/baseline-prof.txt`, which is what actually ships. Nothing in the module
// itself ever reaches an artifact, so it is included only when asked for. That is deliberate rather than
// tidy — an ordinary build, and F-Droid's byte-compared rebuild in particular, then never configures it,
// never resolves androidx.benchmark, and never materializes the extra build type it needs. See
// .agents/context/baseline-profile.md.
if (providers.gradleProperty("knit.baselineProfile").orNull.toBoolean()) {
    include(":baselineprofile")
}

// The translation runtime (ADR 2026-10.t2h6) is the `app.getknit:translate-native` AAR from Maven Central, built and
// published by the knit-translate-native repository. `-Pknit.translateNative=<path to its checkout>` takes that build
// in its place (a composite build), to try a native change in the app without publishing it. That build needs the NDK;
// an ordinary one, F-Droid's included, never configures it.
providers.gradleProperty("knit.translateNative").orNull?.let { path ->
    includeBuild(path) {
        dependencySubstitution {
            substitute(module("app.getknit:translate-native")).using(project(":translate-native"))
        }
    }
}

// The Wear OS watch app for the mesh-status prototype (the phone side is `WearStatusServer`, behind
// `BuildConfig.WEAR_STATUS`). Opt-in for the same reason as :baselineprofile: F-Droid's rebuild and CI never
// configure it or resolve the Wear libraries, so it cannot move a byte of :app's release.
// The Knit watch face (`watchface/`, ADR 2026-10.3y2s) rides the same switch: a resource-only face whose ring
// :wear's complication feeds, so one is no use without the other; and its analog sibling, the monogram
// (`watchface-monogram/`), one face per APK as the format requires.
if (providers.gradleProperty("knit.wear").orNull.toBoolean()) {
    include(":wear")
    include(":watchface")
    include(":watchface-monogram")
}
