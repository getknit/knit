# Testing & verifying changes

## Verification ladder

1. `./gradlew :app:testDebugUnitTest` for mesh/protocol/data logic — now including **Robolectric +
   in-memory Room** tests that execute the real DAO SQL (see below).
2. Emulator smoke test for UI/startup (launch, Koin init, screen rendering, no crash) — the app
   runs fine on an emulator, it just can't form a real mesh there.
3. Two physical phones for discovery → connect → relay and profile/avatar exchange.
4. **Seeded UI instrumentation suite** (`app/src/androidTest/…/ui/`) for populated-screen rendering across
   devices/API levels — locally on an emulator (`:app:connectedDebugAndroidTest -PseedDemo=true`) or on
   Firebase Test Lab physical devices (`bash scripts/ftl.sh`). See below.

> Wi-Fi Aware needs physical devices — an emulator can't do NAN. Use `FakeLoopTransport` for logic tests
> and two physical Wi-Fi-Aware-capable phones (e.g. Pixels) for real discovery → data path → relay.

## JVM Room/DAO + migration tests (Robolectric)

`app/src/test/java/app/getknit/knit/data/` runs the **real** DAO SQL — the eviction/orphan/GC queries the
`FakeForwardDao`/`FakeReactionDao` only *mirror* (finding #5 in `docs/ARCHITECTURE_REVIEW.md`) — on the JVM
under Robolectric 4.16, plus a `MigrationTestHelper` harness. They run inside the normal
`:app:testDebugUnitTest` (and CI `test:unit`), no device. The wiring is non-obvious and load-bearing — read
before "simplifying":

- **The in-memory test DB skips SQLCipher.** `RoomDbTest` builds via `Room.inMemoryDatabaseBuilder(...)` with
  **no** `openHelperFactory`, so it uses Robolectric's framework SQLite — no passphrase, no `libsqlcipher.so`.
  The eviction/GC SQL runs identically (SQLCipher only encrypts at rest). Never call `KnitDatabase.build()` in
  a test. Call `suspend` DAO methods inside `runTest { }`.
- **`robolectric.properties` forces `application=android.app.Application`.** The real `KnitApplication.onCreate`
  starts Koin, whose static `GlobalContext` isn't reset between tests → `KoinApplicationAlreadyStartedException`
  on the 2nd test. DAO tests bypass Koin, so a plain Application is correct; `sdk=36` matches compileSdk.
- **`exportSchema = true`** on `KnitDatabase` + `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`
  (plugin-free — no Room Gradle plugin) emit `app/schemas/app.getknit.knit.data.KnitDatabase/<version>.json`
  (checked in). Regenerate by building after any `@Database` version bump.
- **`MigrationTestHelper` reads the schema from *debug*-variant assets** —
  `sourceSets["debug"].assets.srcDir("schemas")` in `app/build.gradle.kts`. Robolectric serves the merged
  **debug** assets (unit tests run against debug) but **not** the `test` source set's own assets; release APKs
  never carry the schema. It uses **`AndroidSQLiteDriver`** (the Robolectric-shadowed engine) — the
  connection-returning API needs a `SQLiteDriver`, and `BundledSQLiteDriver` can't load its Android native
  `.so` on the host JVM. **DB v1 is the frozen launch baseline** — there is **no** destructive fallback: from
  v1 forward every `@Database` bump MUST add a tested `Migration` to `KnitMigrations` (`data/KnitMigrations.kt`,
  `ALL` is empty at launch) and a from→to case to `KnitDatabaseMigrationTest` — a missing migration throws at
  open time (caught here in CI), never silently wipes. Today the harness validates the exported v1 schema; the
  first real migration (`MIGRATION_1_2`) fills in the commented template. (The pre-1.0 alpha builds churned
  through destructive v2…v22 bumps that rode the wire/crypto breaks; that history is collapsed — see
  `docs/WIRE_COMPAT.md` for the break record.)
- After adding a test dep, **regenerate the lockfile** (`:app:dependencies --write-locks`, all configs) — see
  the lockfile rule in `rules/build-and-test.md`.

## Seeded UI instrumentation suite + Firebase Test Lab

`app/src/androidTest/java/app/getknit/knit/ui/` is a Compose/Espresso instrumentation suite that hunts
**device- and API-specific UI quirks** on real hardware. Because the mesh radios can't work on a Firebase
Test Lab (FTL) datacenter device (no peers, no NAN), the suite runs the app against the **demo-seeded,
radio-less build** (`-PseedDemo=true`): the no-op `DemoTransport` replaces the radios, `DemoSeeder` populates
Room through the real repositories, onboarding is skipped, and `MeshService` never starts — so every screen
renders fully populated and deterministic (the "hiking" theme; seeded ids `samr1v00`/`danich01`/… and the
existing `testTag`s are the assertion anchors). Graceful **radio-absent** behaviour is verified separately by
the JVM tests (`CompositeMeshTransportTest`, `RadioWarningTest`, `OnboardingScreenContentTest`), not here.

- **Run locally** (emulator is fine — no real mesh needed):
  `./gradlew :app:connectedDebugAndroidTest -PseedDemo=true` (target one device with `ANDROID_SERIAL=…`).
- **Run on FTL**: `bash scripts/ftl.sh` — builds the seeded app + androidTest APKs and runs the default
  3-device matrix **a10@29 / cheetah@33 / b0q@36** (API 29/33/36) under `--use-orchestrator`. Override with
  `DEVICES=…`, `PROJECT=…`, `TIMEOUT=…`, or `SKIP_BUILD=1`. gcloud must be authed to the Firebase project
  (`knit-mesh`); the **free tier is 5 physical-device runs/day**, so the default spends 3.
- **Isolation is Android Test Orchestrator + `clearPackageData=true`** (`testOptions.execution` +
  `androidTestUtil` orchestrator/test-services in `app/build.gradle.kts`): each test runs in a fresh,
  data-wiped process, so the identity + DB regenerate and the seed re-runs every time.
- **`BuildConfig.SEED_DEMO` is compile-time-inlined into the androidTest DEX**, so the **test** APK must also
  be built with `-PseedDemo=true` (the gradle/FTL commands above pass it to both). `SeededUiTest` fails loudly
  with a `check(SEED_DEMO)` if not — never gate the suite on `Assume.assumeTrue(SEED_DEMO)` (a mis-build would
  silently skip everything green).
- **Screenshots**: every test captures one (pass or fail) via `UiAutomation` + test-services `TestStorage`
  (`androidx.test.services:storage`), which FTL collects as a per-device output and AGP mirrors locally under
  `app/build/outputs/connected_android_test_additional_output/`. This is the scoped-storage-safe path (no
  `WRITE_EXTERNAL_STORAGE` on any API); do **not** switch to `testlab-instr-lib`'s `FirebaseScreenCaptureProcessor`,
  which hardcodes a `/sdcard/screenshots` write that only FTL's device env grants (it silently no-ops on a
  plain emulator).
- **Gotchas that bit us:** the seed is async, so `waitUntil` for content (never assert on immediate launch);
  assert on the **newest/on-screen** message (a `LazyColumn` won't compose off-screen items, and an
  image-attachment message's async height throws off the auto-scroll on some devices — assert a freshly-sent
  echo instead); a text send cold-loads the tflite moderator, so give the echo a generous timeout; and
  `ProfileViewModel` one-shot-reads the display name in `init`, racing the seed (test the edit round-trip, not
  the pre-loaded name).

When driving the emulator over `adb`: the soft keyboard overlaps via `adjustResize`, so read element
coordinates from `uiautomator dump` rather than guessing; seed the photo picker by `screencap`-ing
into `/sdcard/Pictures` if you need an image to select. For the headless debug bridge (send/verify without
screenshots), see `context/debug-bridge.md`.
