import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover) // test coverage — instruments bytecode + hooks the test run
    alias(libs.plugins.detekt) // static analysis (dev.detekt)
    alias(libs.plugins.ktlint) // Kotlin style/format lint (ktlintCheck / ktlintFormat)
    alias(libs.plugins.androidx.room) // Room schema export (room { schemaDirectory(…) } below)
}

// Release signing credentials. Loaded from a gitignored keystore.properties at the repo root, falling back
// to env vars (CI). Absent creds → the release build is left unsigned (see android.signingConfigs), so
// assembleRelease still runs without secrets — which is also exactly what F-Droid's buildserver produces.
// Never commit a key.
//
// This config is credential-GENERIC and serves two distinct signing identities: the Play *upload* key for
// `bundleRelease`, and the public distribution key for the `assembleRelease` APK that F-Droid verifies and
// redistributes. You pick one by which keystore the credentials point at; the build never sees both. See
// keystore.properties.example and .agents/context/distribution.md.
val keystoreProps =
    Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

// Env fallback accepts KNIT_SIGNING_* (identity-neutral, preferred) before the original KNIT_UPLOAD_*
// names. Both still work; the rename exists because "UPLOAD" is actively misleading for the identity that
// matters most — .github/workflows/release.yml feeds this the *distribution* key, whose certificate is
// pinned forever in fdroiddata. Signing the public APK with the Play upload key by mistake is the one
// unrecoverable error here, so the variable shouldn't be named after the wrong key.
fun releaseSigningCred(
    prop: String,
    envSuffix: String,
): String? =
    (
        keystoreProps.getProperty(prop)
            ?: System.getenv("KNIT_SIGNING_$envSuffix")
            ?: System.getenv("KNIT_UPLOAD_$envSuffix")
    )?.takeIf { it.isNotBlank() }

// Native-symbol extraction for the Play AAB, OFF by default. It is the one part of the release build that
// depends on a tool outside the Gradle/AGP pin — the NDK's llvm-objcopy/llvm-strip — and AGP degrades
// SILENTLY when the NDK is absent (it warns and ships the prebuilt .so unstripped). That makes the output
// bytes a function of the *build machine*, which breaks the F-Droid reproducible-build contract: F-Droid
// rebuilds this commit on their buildserver and byte-compares against the APK we publish, so "stripped
// here, unstripped there" is a verification failure. Default OFF pins the APK to the no-strip path on every
// machine (see packaging.jniLibs below); the Play release turns it back ON with -Pknit.nativeSymbols=true,
// which is the only build that needs it (symbols ride in the AAB's BUNDLE-METADATA, not in an APK).
val nativeSymbols = (project.findProperty("knit.nativeSymbols") as? String)?.toBoolean() == true

android {
    namespace = "app.getknit.knit"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    // Pinned ONLY to run llvm-objcopy for release native-symbol extraction (see debugSymbolLevel in
    // buildTypes) — this app compiles no native code, so the exact version is not correctness-sensitive;
    // any recent NDK extracts the same .dynsym. It's pinned for reproducibility and because a missing NDK
    // fails SILENTLY (empty symbols, no build error). BUMP POLICY: only in lockstep with an AGP upgrade,
    // to AGP's new *default* NDK (AGP release notes → "Default NDK version"; a stale/missing pin also shows
    // up as an `android.ndkVersion …` build warning). Don't chase NDK releases on their own cadence — with
    // no native build there's nothing to gain. After bumping: `sdkmanager "ndk;<ver>"` on every build
    // machine + CI, then re-verify the .sym files land in the AAB's BUNDLE-METADATA (the failure is silent).
    // Set ONLY on the Play path (-Pknit.nativeSymbols=true) so the default build needs no NDK at all —
    // see the nativeSymbols comment above the android block.
    if (nativeSymbols) ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "app.getknit.knit"
        // minSdk 29 is the shared data-path floor: BLE L2CAP CoC and the Wi-Fi Aware NDP
        // (WifiAwareNetworkSpecifier.Builder) are both API 29. Both radios run on 29+. Wi-Fi Aware uses
        // Instant Communication Mode + NEARBY_WIFI_DEVICES on 33+ and ACCESS_FINE_LOCATION (no ICM) on 29-32;
        // BLE uses the split BLUETOOTH_* perms on 31+ and legacy BLUETOOTH/BLUETOOTH_ADMIN on 29-30. Location
        // is confined to 29-32 (maxSdkVersion 32); 33+ stays location-free.
        minSdk = 29
        targetSdk = 36
        // Single source of truth in gradle.properties (knit.versionCode / knit.versionName). Play App
        // Signing requires versionCode to strictly increase per upload — CI can inject a monotonic value
        // with `-Pknit.versionCode=$CI_PIPELINE_IID` without editing this file.
        versionCode = providers.gradleProperty("knit.versionCode").get().toInt()
        versionName = providers.gradleProperty("knit.versionName").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Android Test Orchestrator isolation: wipe app data (identity keystore + SQLCipher DB + DataStore)
        // between instrumentation tests so each test re-generates a fresh identity and re-seeds a clean demo
        // DB. Paired with testOptions.execution below; on Firebase Test Lab pass the same via
        // `--use-orchestrator --environment-variables clearPackageData=true` (see .agents/context/testing.md).
        testInstrumentationRunnerArguments["clearPackageData"] = "true"

        // Demo-screenshot mode: when built with `-PseedDemo=true`, the app seeds a realistic data set
        // and swaps in a no-op transport so every screen renders populated on an emulator (no real
        // mesh). Defaults false, so release/normal builds are unaffected. See DemoSeeder/DemoTransport.
        // Demo-trailer mode: `-PdemoDirector=true` plays a scripted, animated conversation (the promo
        // trailer) instead of the static screenshot seed. It IMPLIES seedDemo — it reuses every demo seam
        // (no-op transport, permission-gate skip, no MeshService, `demo_route`) — so turning it on lights
        // those up for free. Debug-only; the director + seeder live in src/debug (see DemoWiring).
        val demoDirector = (project.findProperty("demoDirector") as? String)?.toBoolean() == true
        val seedDemo = (project.findProperty("seedDemo") as? String)?.toBoolean() == true || demoDirector
        buildConfigField("boolean", "SEED_DEMO", seedDemo.toString())
        buildConfigField("boolean", "DEMO_DIRECTOR", demoDirector.toString())
        // Which seed scenario DemoSeeder/DemoDirector loads: "hiking" (default) or "festival". Only meaningful
        // when seedDemo is on; picks the persona/message/avatar set so we can shoot multiple marketing themes.
        val demoTheme = (project.findProperty("demoTheme") as? String) ?: "hiking"
        buildConfigField("String", "DEMO_THEME", "\"$demoTheme\"")
    }

    signingConfigs {
        // Release signing — Play upload key or the public distribution key, depending on which keystore the
        // creds point at (see the loader above the android block). Missing creds → no "release" config is
        // created and the release build stays UNSIGNED, so assembleRelease still runs (and exercises R8)
        // without secrets; a key is only needed to install on a device, upload to Play, or publish a
        // GitHub Release. v1..v4 signing stay at AGP defaults, correct for both identities.
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
        release {
            // R8: shrink + optimize + OBFUSCATE (name mangling), plus unused-resource shrinking. Keep rules
            // are auto-combined from src/main/keepRules/*.keep (AGP merges them; no proguardFiles wiring
            // needed). The wire protocol survives renaming because it's kotlinx.serialization compiler-plugin
            // CBOR/JSON — map keys are baked $$serializer descriptor string literals R8 doesn't rewrite — and
            // the frozen wire/identity DTOs are pinned unrenamed in knit-r8.keep; the fail-silent reflection
            // surfaces (Tink, the tflite moderators, SQLCipher) are kept there too. isMinifyEnabled is the
            // standard R8 switch (the previous `optimization { enable }` was AGP 9's experimental *gradual-R8*
            // toggle, which needs android.r8.gradual.support — not what we want for a production release).
            // isShrinkResources strips unused res/ (optimized shrinking is automatic in AGP 9). NOTE:
            // mapping.txt (build/outputs/mapping/<variant>/) is now the deobfuscation map — retain it per
            // release to symbolicate crashes.
            isMinifyEnabled = true
            isShrinkResources = true
            // Native crash/ANR symbolication for the prebuilt .so we ship (tflite/LiteRT jni,
            // datastore_shared_counter, SQLCipher, graphics-path). AGP extracts the symbols into the AAB's
            // BUNDLE-METADATA and Play Console picks them up automatically — no manual upload. This is the
            // *native* counterpart to the mapping.txt R8 map above (which only covers Kotlin/Java frames).
            // Extraction runs the NDK's llvm-objcopy, so a matching NDK must be installed — if it's absent
            // strip/extract SILENTLY no-op (empty symbols, no build error), so the warning would persist.
            // SYMBOL_TABLE, not FULL, is deliberate: these are third-party libs compiled release with NO
            // DWARF, so FULL (--only-keep-debug) yields near-empty .dbg (.dynsym NOBITS, 0 symbols) while
            // SYMBOL_TABLE keeps the real .dynsym (function names) — 444 FUNC syms for tflite vs 0. We have
            // no first-party native code; revisit FULL only if we ever ship our own -g-compiled .so.
            // Gated on -Pknit.nativeSymbols=true (the Play bundleRelease invocation); off by default so the
            // APK build is NDK-free and byte-identical everywhere — see the nativeSymbols comment up top.
            if (nativeSymbols) {
                ndk {
                    debugSymbolLevel = "SYMBOL_TABLE"
                }
            }
            // Don't stamp the build machine's Git state into the APK. AGP otherwise writes
            // META-INF/version-control-info.textproto containing the local checkout's HEAD revision (or
            // `NO_SUPPORTED_VCS_FOUND` when built outside a Git work tree), which makes the packaged bytes
            // a function of *how the builder obtained the source*. It is the one difference that survived
            // an otherwise byte-identical rebuild of this commit inside F-Droid's buildserver container —
            // 1 differing entry out of 185 — and it would fail their rebuild-and-compare verification.
            // The feature only feeds Play Console's "see the code" crash links; mapping.txt still
            // symbolicates, and the source is public and tagged, so nothing real is lost.
            vcsInfo {
                include = false
            }
            // Never build a demo-seeded release, even with `-PseedDemo=true` — demo mode is debug-only and
            // its classes ship only in src/debug. Overrides the defaultConfig SEED_DEMO/DEMO_DIRECTOR fields.
            buildConfigField("boolean", "SEED_DEMO", "false")
            buildConfigField("boolean", "DEMO_DIRECTOR", "false")
            // Unsigned when no keystore.properties / KNIT_UPLOAD_* creds are present (see signingConfigs).
            signingConfig = signingConfigs.findByName("release")
        }
        create("staging") {
            // Inherit release's R8 shrink/optimize + resource shrinking + the SEED_DEMO=false override.
            initWith(getByName("release"))
            // …but sign with the debug keystore (AGP auto-creates this config; always present, no secrets).
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // On-device model files must stay uncompressed so TFLite can mmap them from the APK.
        noCompress += listOf("tflite")
    }

    packaging {
        jniLibs {
            // With nativeSymbols off there is no NDK, and AGP's strip step then degrades SILENTLY (it
            // warns and packages the .so as-is). Opt out of stripping *explicitly* instead, so the
            // packaged bytes are identical whether or not the build machine happens to have an NDK —
            // that determinism is what makes F-Droid's rebuild-and-byte-compare verification possible.
            // Costs ~nothing in APK size: every .so we ship is a third-party release build (LiteRT,
            // SQLCipher, datastore-shared-counter, graphics-path) that upstream already stripped.
            if (!nativeSymbols) keepDebugSymbols += "**/*.so"
        }
    }

    testOptions {
        // Run instrumentation tests under Android Test Orchestrator (each test in its own process; combined
        // with the `clearPackageData` runner arg above). Only affects LOCAL connectedDebugAndroidTest —
        // FTL injects its own orchestrator via `--use-orchestrator`. animationsDisabled stabilizes UI tests.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
        unitTests {
            // Robolectric runs the JVM Room/DAO + migration tests (finding #5): it reads AGP's merged
            // manifest/resources config and supplies a Context + framework SQLite so in-memory Room
            // executes the real eviction/GC SQL. See app/src/test/java/app/getknit/knit/data/ and
            // app/src/test/resources/robolectric.properties.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }

        // Gradle Managed Device: a headless emulator Gradle provisions/boots/tears down itself, so
        // `./gradlew :app:pixel7api33DebugAndroidTest -PseedDemo=true` runs the seeded suite on an
        // emulator ONLY — it never touches whatever physical lab devices are attached to adb (which
        // plain `connectedDebugAndroidTest` would). Pixel 7 @ API 33 mirrors the FTL matrix's middle
        // device (cheetah@33 is a Pixel 7). `aosp-atd` = the Automated Test Device image: headless,
        // GMS-stripped, fastest for UI tests — and the app uses no GMS, so nothing is lost.
        managedDevices {
            localDevices {
                create("pixel7api33") {
                    device = "Pixel 7"
                    apiLevel = 33
                    systemImageSource = "aosp-atd"
                }
                // Headless emulator for the accessibility suite (app.getknit.knit.a11y): the Compose ATF
                // checks are @RequiresApi(34), so they need an API-34+ device — pixel7api33 is too old and
                // just skips them (@SdkSuppress). Run: `./gradlew :app:pixel8api34DebugAndroidTest
                // -PseedDemo=true -Pandroid.testInstrumentationRunnerArguments.package=app.getknit.knit.a11y`.
                // aosp-atd (headless, GMS-stripped) is fine — ATF is a pure library; fall back to "aosp" if
                // the ATD image isn't published for 34.
                create("pixel8api34") {
                    device = "Pixel 8"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }

    sourceSets {
        // Serve the exported Room schemas (app/schemas/) as DEBUG-variant assets so MigrationTestHelper can
        // load them under Robolectric — it reads the schema from context.assets/<db-class>/<version>.json, and
        // Robolectric serves the merged *debug* assets (unit tests run against the debug variant), but not the
        // `test` source set's own assets. Scoped to debug, so the ~15 KB schema JSON never ships in release.
        // See KnitDatabaseMigrationTest.
        getByName("debug") {
            assets.directories.add("schemas")
        }
        // The `staging` build type is release-with-R8 signed by the debug key (device testing only). It has
        // no src/staging, so reuse the release variant's no-op DemoWiring stub for the two src/main demo
        // seams (seedDemoIfEnabled / demoTransportOrNull) — staging must not demo-seed, exactly like release.
        getByName("staging") {
            kotlin.directories.add("src/release/java")
        }
    }
}

// Guards against building an APK whose moderation models are Git LFS pointer stubs (or otherwise
// truncated). This used to be a live hazard: `*.tflite` was tracked in Git LFS, and any checkout without a
// working LFS client — notably F-Droid's buildserver, which has no LFS support — silently substitutes a
// ~130-byte pointer file. Both moderators catch an unreadable model and degrade to allow-all *by design*
// (see NsfwImageModerator/MlTextModerator), so nothing downstream would ever complain; the app would just
// ship with moderation quietly disabled. The models are plain Git blobs now (see .gitattributes), which
// makes that impossible from a normal clone — this keeps it impossible from an abnormal one.
val checkModerationModels =
    tasks.register("checkModerationModels") {
        description = "Fails the build if a bundled .tflite moderation model is missing or a stub."
        // Resolved to plain Files and a Provider HERE, at configuration time, and captured by value below:
        // a doLast lambda that reached out to a script-level property instead would pull the build-script
        // object into the configuration cache, which cannot serialize it (org.gradle.configuration-cache
        // is on — see gradle.properties).
        val models =
            listOf("nsfw.tflite", "toxicity.tflite").map {
                layout.projectDirectory.file("src/main/assets/moderation/$it").asFile
            }
        val stamp = layout.buildDirectory.file("tmp/checkModerationModels.stamp")
        inputs.files(models).withPropertyName("moderationModels")
        outputs.file(stamp)
        doLast {
            models.forEach { model ->
                if (!model.isFile) {
                    throw GradleException("Moderation model missing: $model")
                }
                val head = model.inputStream().use { String(it.readNBytes(48), Charsets.US_ASCII) }
                if (head.startsWith("version https://git-lfs")) {
                    throw GradleException(
                        "Moderation model $model is a Git LFS pointer, not the real model. " +
                            "This repo no longer uses LFS for *.tflite — re-checkout the file " +
                            "(git checkout -- ${model.name}) or fetch it from a full clone.",
                    )
                }
                if (model.length() < 1_000_000L) {
                    throw GradleException(
                        "Moderation model $model is only ${model.length()} bytes — expected >= 1 MB. " +
                            "A truncated model would silently disable content moderation.",
                    )
                }
            }
            stamp
                .get()
                .asFile
                .apply { parentFile.mkdirs() }
                .writeText("ok")
        }
    }

tasks.named("preBuild") { dependsOn(checkModerationModels) }

room {
    // Export the Room schema JSON via the Room Gradle plugin (replaces the raw ksp `room.schemaLocation`
    // arg — the plugin rejects that arg if also set). With only build types (no product flavors) it writes
    // the flat schemas/app.getknit.knit.data.KnitDatabase/<version>.json — same layout the ksp arg produced —
    // which the debug sourceSet below serves as a unit-test asset so MigrationTestHelper can read it.
    // Requires exportSchema = true on KnitDatabase; regenerate the checked-in schema by clearing app/schemas/
    // and rebuilding after any @Database version bump (KSP incremental caching can otherwise skip re-export).
    schemaDirectory("$projectDir/schemas")
}

kover {
    // Coverage is measured from the DEBUG unit tests (`:app:testDebugUnitTest` — the JVM mesh/protocol/data +
    // Robolectric Room/Compose suites), so the per-variant report tasks to run are the *Debug ones:
    //   ./gradlew :app:koverHtmlReportDebug   → app/build/reports/kover/htmlDebug/index.html
    //   ./gradlew :app:koverXmlReportDebug    → app/build/reports/kover/reportDebug.xml (CI-parseable)
    reports {
        filters {
            excludes {
                // Only *generated* code — everything hand-written (including di/ wiring and the
                // Robolectric-tested *ScreenContent composables) stays measured so the number is honest.
                // NOTE: in Kover class globs, `*` does NOT cross the package separator `.` — use `**` to
                // span packages (verified on-report; a bare `*_Impl` matches nothing here). `$$serializer`,
                // `R`, and `Manifest` never appear in the report, so they need no rule.
                classes(
                    "**_Impl", // Room-generated DAO/database implementations (KSP)
                    "**_Impl$*", // ...and their nested classes ($1, $Companion, open-delegates)
                    "**ComposableSingletons*", // Compose-generated lambda-holder classes
                    "**BuildConfig", // generated BuildConfig
                )
            }
        }
    }
}

detekt {
    // Overlay config/detekt/detekt.yml on detekt's bundled defaults (== the old CLI's
    // --build-upon-default-config). Analyze the same inputs the CLI did — main + unit-test Kotlin — set
    // explicitly rather than via source-set autodiscovery: AGP 9's built-in Kotlin (no kotlin-android
    // plugin) can leave detekt's discovery empty. No compile classpath is wired, so this runs WITHOUT type
    // resolution, exactly like the old `detekt-cli` invocation. Reports land in build/reports/detekt/.
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(files("src/main/java", "src/test/java"))
}

ktlint {
    // Pin the ktlint *tool* version (libs.versions.ktlint) so rule behavior stays fixed independent of the
    // plugin version. Rules come from the repo-root .editorconfig (auto-discovered), incl. the @Composable
    // function-naming opt-out. `ktlintFormat` autocorrects; `ktlintCheck` verifies. Reports → build/reports/ktlint/.
    version.set(libs.versions.ktlint.get())
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.HTML)
        reporter(ReporterType.SARIF)
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
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

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

    // Mesh transport is Wi-Fi Aware (android.net.wifi.aware.*, framework API) — no external dependency.

    // Images
    implementation(libs.coil.compose)
    implementation(libs.coil.gif) // animated GIF/WebP decoding (keyboard GIFs)

    // On-device ML runtimes (no network; models bundled in assets). See the version catalog for why
    // the bare classic interpreter (LiteRT 1.4.x) is used rather than MediaPipe or the heavier LiteRT 2.x.
    // The text toxicity tokenizer is pure Kotlin (SentencePieceTokenizer, parses tokenizer.json via
    // kotlinx-serialization) — no native tokenizer lib, so nothing to 16 KB-align and no .so added to the APK.
    implementation(libs.litert)

    // E2E encryption (Tink — Java + native, no Kotlin metadata / no Gradle plugin, like SQLCipher)
    implementation(libs.tink.android)
    // QR identity verification (safety-number / QR verify screen)
    implementation(libs.zxing.android.embedded)
    implementation(libs.zxing.core)

    // Offline "Share Knit app": when installed from Play as an App Bundle, merge the on-device split
    // APKs into one universal APK (ARSCLib) and re-sign it (apksig). Both pure Java, no Kotlin metadata —
    // see gradle/libs.versions.toml. Used only by ui/invite/ApkMerger.kt.
    implementation(libs.reandroid.arsclib)
    implementation(libs.apksig)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.mockk) // relaxed mocks of the concrete Room-backed repos in InboundPipelineTest
    // JVM Room/DAO + migration tests (finding #5): Robolectric supplies a Context + framework SQLite so
    // in-memory Room runs the real eviction/GC SQL, and room-testing's MigrationTestHelper rebuilds the
    // exported schema on that same shadowed SQLite (via androidx.sqlite's AndroidSQLiteDriver, already pulled
    // by Room). See app/src/test/java/app/getknit/knit/data/.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit) // AndroidJUnit4 runner on the JVM (delegated by Robolectric)
    testImplementation(libs.androidx.test.core) // ApplicationProvider.getApplicationContext()
    testImplementation(libs.androidx.room.testing) // MigrationTestHelper (was androidTest-only)
    // Compose UI tests run on Robolectric (createComposeRule in :app:testDebugUnitTest, no emulator) against
    // the stateless *ScreenContent composables. The BOM (implementation platform) and compose-ui-test-manifest
    // (debugImplementation, below) are already on the unit-test classpath; only the junit4 rule needs adding.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Accessibility Test Framework (ATF) checks in the Compose suite — the same framework the Play
    // pre-launch report runs. Pulls ATF + AccessibilityValidator transitively; drives the API-34+
    // a11y package (app.getknit.knit.a11y, @RequiresApi(34)). See .agents/context/testing.md.
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    // Firebase Test Lab seeded UI suite (app/src/androidTest/…/ui): explicit runner + rules
    // (ActivityScenario/GrantPermissionRule; runner was only transitive) and the Orchestrator + its
    // test-services APK (androidTestUtil, for local connectedDebugAndroidTest parity). See AGENTS.md /
    // .agents/context/testing.md.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.services.storage) // TestStorage: FTL-collected screenshots
    // UIAutomator black-box suite (app.getknit.knit.uiauto): drives the real app process via resource-ids
    // (testTagsAsResourceId) + the system UI (notification shade, Recents). See .agents/context/testing.md.
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
