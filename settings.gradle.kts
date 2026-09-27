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

// The baseline-profile generator is maintainer-only: a `com.android.test` module that drives the app on a
// device to produce `app/src/main/baseline-prof.txt`, which is what actually ships. Nothing in the module
// itself ever reaches an artifact, so it is included only when asked for. That is deliberate rather than
// tidy — an ordinary build, and F-Droid's byte-compared rebuild in particular, then never configures it,
// never resolves androidx.benchmark, and never materializes the extra build type it needs. See
// .agents/context/baseline-profile.md.
if (providers.gradleProperty("knit.baselineProfile").orNull.toBoolean()) {
    include(":baselineprofile")
}

// The Wear OS watch app for the mesh-status prototype (the phone side is `WearStatusServer`, behind
// `BuildConfig.WEAR_STATUS`). Opt-in for the same reason as :baselineprofile: F-Droid's rebuild and CI never
// configure it or resolve the Wear libraries, so it cannot move a byte of :app's release.
if (providers.gradleProperty("knit.wear").orNull.toBoolean()) {
    include(":wear")
}
