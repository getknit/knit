import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // Test coverage. Deliberately the Gradle plugin (unlike detekt/ktlint, which run as standalone CLIs)
    // — coverage must instrument bytecode and hook the test run, which a source-analyzer CLI can't do.
    // See gradle/libs.versions.toml for why this is safe on the AGP-9.2.1/Kotlin-2.4 toolchain.
    alias(libs.plugins.kover)
}

// Release signing credentials (Google Play upload key). Loaded from a gitignored keystore.properties at
// the repo root, falling back to env vars (CI: KNIT_UPLOAD_*). Absent creds → the release build is left
// unsigned (see android.signingConfigs), so assembleRelease still runs without secrets. Never commit a key.
val keystoreProps =
    Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

fun releaseSigningCred(
    prop: String,
    env: String,
): String? = (keystoreProps.getProperty(prop) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

android {
    namespace = "app.getknit.knit"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

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
        // `--use-orchestrator --environment-variables clearPackageData=true` (see scripts/ftl.sh).
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
        // Release signing for Google Play upload. Creds come from keystore.properties / KNIT_UPLOAD_* env
        // (see the loader above the android block). Missing creds → no "release" config is created and the
        // release build stays UNSIGNED, so assembleRelease still runs (and exercises R8) without secrets;
        // a key is only needed to install on a device or upload to Play. v1..v4 signing stay at AGP
        // defaults, which are correct for an upload key.
        val store = releaseSigningCred("storeFile", "KNIT_UPLOAD_STORE_FILE")
        val storePass = releaseSigningCred("storePassword", "KNIT_UPLOAD_STORE_PASSWORD")
        val alias = releaseSigningCred("keyAlias", "KNIT_UPLOAD_KEY_ALIAS")
        val keyPass = releaseSigningCred("keyPassword", "KNIT_UPLOAD_KEY_PASSWORD")
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
            // R8: shrink + optimize dead code (including the demo/fake classes, now debug-only) and unused
            // resources. Obfuscation is deliberately OFF for this first enablement (`-dontobfuscate` in
            // src/main/keepRules/knit-r8.keep) — the app has fail-silent reflection surfaces (Tink, the
            // tflite moderators) and renaming is the historically risky R8 pass, so it is a scheduled
            // follow-up once the release build is proven stable on-device. Keep rules are auto-combined
            // from src/main/keepRules/*.keep (AGP merges them; no proguardFiles wiring needed).
            // isMinifyEnabled is the standard R8 switch (the previous `optimization { enable }` was AGP 9's
            // experimental *gradual-R8* toggle, which needs android.r8.gradual.support — not what we want for
            // a production release). It runs R8's shrink + optimize passes; obfuscation is disabled in the
            // keep rules. isShrinkResources strips unused res/ (optimized shrinking is automatic in AGP 9).
            isMinifyEnabled = true
            isShrinkResources = true
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

ksp {
    // Export the Room schema JSON (app/schemas/<db-class>/<version>.json) so schema changes are diffable in
    // review and the migration test's MigrationTestHelper can read them. Plugin-free — no Room Gradle plugin
    // (same toolchain caution as Koin-not-Hilt). Requires exportSchema = true on KnitDatabase; regenerate the
    // checked-in schema by building after any @Database version bump.
    arg("room.schemaLocation", "$projectDir/schemas")
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

    // Play In-App Review — optional at runtime: ReviewPrompter pre-gates on the Play installer and
    // swallows failures, so de-googled/sideloaded devices are unaffected. See gradle/libs.versions.toml.
    implementation(libs.play.review.ktx)

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
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    // Firebase Test Lab seeded UI suite (app/src/androidTest/…/ui): explicit runner + rules
    // (ActivityScenario/GrantPermissionRule; runner was only transitive) and the Orchestrator + its
    // test-services APK (androidTestUtil, for local connectedDebugAndroidTest parity). See AGENTS.md /
    // scripts/ftl.sh.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.services.storage) // TestStorage: FTL-collected screenshots
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
