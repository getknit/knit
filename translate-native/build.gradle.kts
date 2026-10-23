plugins {
    // No version: AGP is already on the build classpath from the root project (see baselineprofile/).
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
}

// The translation spike's native library (ADR 2026-10.t2h6): slimt and CLD2 behind a small JNI, built from the
// sources scripts/translate-native.sh fetches. This module is in the build only under `-Ptranslate=true`
// (settings.gradle.kts), so an ordinary build, the release workflow's and F-Droid's byte-compared rebuild never
// configure it, never need the NDK and never package it. A testers' alpha built with the flag takes it in release too.
android {
    namespace = "app.getknit.knit.translatenative"
    // Lockstep with :app (see the compileSdk / buildToolsVersion comments there).
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 1
            }
    }
    buildToolsVersion = "37.0.0"
    // The NDK installed on the build machine; nothing pins it for anyone else, which is fine for a spike only.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 29
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // Release-optimised in every variant: :app's debug takes this module's debug, and an -O0 Marian
                // would make every timing the spike exists to take meaningless.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
                targets += "knittranslate"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            // The system CMake on PATH; the SDK carries none here.
            version = "3.28.3"
        }
    }
}
