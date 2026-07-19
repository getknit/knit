# Testing & verifying changes

## Verification ladder

1. `./gradlew :app:testDebugUnitTest` for mesh/protocol/data logic — now including **Robolectric +
   in-memory Room** tests that execute the real DAO SQL (see below).
2. Emulator smoke test for UI/startup (launch, Koin init, screen rendering, no crash) — the app
   runs fine on an emulator, it just can't form a real mesh there.
3. Two physical phones for discovery → connect → relay and profile/avatar exchange.
4. **Seeded UI instrumentation suite** (`app/src/androidTest/…/ui/`) for populated-screen rendering across
   devices/API levels — locally on an emulator (`:app:connectedDebugAndroidTest -PseedDemo=true`) or on
   Firebase Test Lab physical devices. See below. A **black-box UIAutomator** twin
   (`…/uiauto/`) covers the system shade + process lifecycle — see below.
5. **Accessibility (ATF) suite** (`app/src/androidTest/…/a11y/`) runs Google's Accessibility Test Framework —
   the same checks the Play Console pre-launch report runs — on API 34+. See below.

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
- **`exportSchema = true`** on `KnitDatabase` + the Room Gradle plugin's
  `room { schemaDirectory("$projectDir/schemas") }` emit
  `app/schemas/app.getknit.knit.data.KnitDatabase/<version>.json` (checked in). Regenerate by clearing
  `app/schemas/` and rebuilding after any `@Database` version bump (KSP caching can otherwise skip re-export).
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
- **Run on FTL**: the seeded app + androidTest APKs (`-PseedDemo=true` for both) run on Firebase Test Lab
  physical devices via `gcloud firebase test android run --use-orchestrator`. The maintainer's runner
  scripts, device matrix, Firebase project, and budget live in the local `.private/` overlay (absent in
  public clones).
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

## Black-box UIAutomator suite

`app/src/androidTest/java/app/getknit/knit/uiauto/` (base `SeededUiAutomatorTest`) is the **UIAutomator**
twin of the Compose suite: it drives the *real running app* through the accessibility / resource-id layer
instead of the in-process semantics tree, so it can reach what Compose testing can't — the **system
notification shade** and **process lifecycle** (Home / Recents / Back / rotation). Same demo-seeded,
radio-less build (`-PseedDemo=true`); `SeededUiAutomatorTest` shares `SeededUiTest`'s contract
(`requireSeededBuild`, `launch(route)` via the `demo_route` extra, `TestStorage` screenshots).

- **Selectors ride the same `testTag`s.** `testTagsAsResourceId` is set at the NavHost root, so a Compose
  `testTag` surfaces to UIAutomator as a `resource-id`. Compose exports it **unqualified**
  (`resource-id="chat_input"`), so `SeededUiAutomatorTest.byTag()` uses a tolerant `By.res(Pattern)` that
  accepts an optional `pkg:id/` prefix. Screens without tags (Diagnostics, request rows' inner text) match
  by `waitText`/`waitDesc`.
- **Popups don't inherit `testTagsAsResourceId`.** A Compose `DropdownMenu`/`AlertDialog` is a separate
  window, so a `testTag` inside one does **not** surface as a `resource-id` — drive menu items and dialog
  buttons by their (localized) **text**, an editable field by its `android.widget.EditText` class, and a
  confirm button whose label is a substring of the dialog title by *exact* text (`requireExactText`, e.g.
  "Block" under "Block this person?"). This is why the overflow-nav / group-management / requests-block tests
  select popups by text, not tag.
- **`UiDevice.executeShellCommand` word-splits and does not honour quotes** (unlike an `adb shell "…"` that
  the device re-parses). A `--es text 'two words'` reaches the app as just `'two`, so a debug broadcast fired
  from within a test must use single-token extras (see `ModerationRevealUiAutomatorTest`).
- **Coverage today:** the seeded core flows + DM send (`SeededFlowsUiAutomatorTest`), process lifecycle
  (`LifecycleUiAutomatorTest`), the notification-shade→requests flow (`MessageRequestNotificationUiAutomatorTest`),
  overflow-menu navigation to the untagged screens (`OverflowNavigationUiAutomatorTest`), contacts→DM/group
  creation (`ContactsFlowUiAutomatorTest` — note the picker lists only *established* contacts: accepted-DM ∪
  group co-member ∪ verified, so Nearby-only strangers never appear), group rename/leave
  (`GroupManagementUiAutomatorTest`), the in-app requests badge + block path (`RequestsInboxUiAutomatorTest`),
  and the received-flagged tap-to-reveal (`ModerationRevealUiAutomatorTest`, via the `FLAGMSG` debug seam).
- **Isolated FTL target.** On Firebase Test Lab this package is run **on its own**
  (`--test-targets "package app.getknit.knit.uiauto"`) and **excluded** from the default Compose run
  (`notPackage app.getknit.knit.uiauto`) so black-box system-UI flakiness never reddens it. Both use the
  one seeded androidTest APK — the split is a runtime filter. (Maintainer runners live in the `.private/` overlay.)
- **Run locally**: `./gradlew :app:connectedDebugAndroidTest -PseedDemo=true
  -Pandroid.testInstrumentationRunnerArguments.package=app.getknit.knit.uiauto` (drop the `-P…package` arg
  to run everything). `@After` force-stops the app so a bare run (no orchestrator) still isolates.
- **The message-request notification seam.** The radio-less build never runs `InboundPipeline` (the only
  caller of `Notifier.notifyMessageRequests`) and seeds no requests, so the debug bridge action
  **`REQNOTIF`** (`DebugBridgeReceiver`) writes synthetic unaccepted inbound DMs and posts the real
  heads-up: `adb shell am broadcast -p app.getknit.knit -a app.getknit.knit.debug.REQNOTIF --ei count 1`.
  The test grants `POST_NOTIFICATIONS` via `pm grant` (API 33+ only; install-time granted on 29–32), fires
  the seam, then drives the shade → tap → Requests inbox → Accept. The inbox rows carry
  `request_row_<id>` / `request_accept_<id>` tags.

## Accessibility (ATF) suite

`app/src/androidTest/java/app/getknit/knit/a11y/` (`AccessibilityInstrumentedTest`) runs Google's
**Accessibility Test Framework (ATF)** — the same framework the Play Console **pre-launch report** runs —
against every seeded screen, so a11y regressions (missing labels, sub-48dp touch targets, low text/image
contrast, bad traversal order) fail locally before upload. It reuses `SeededUiTest` (deep-link each screen
via `demo_route`, await the seed, then audit) and adds one dependency, `ui-test-junit4-accessibility`, which
pulls ATF transitively: `compose.enableAccessibilityChecks(validator)` +
`compose.onRoot().tryPerformAccessibilityChecks()`.

- **API-34 floor.** The Compose ATF integration is `@RequiresApi(34)`, so the suite is gated with
  `@SdkSuppress(minSdkVersion = 34)` — it **skips** (not fails) on the API 29/33 matrix devices, and
  `@SdkSuppress` also satisfies lint's `NewApi`; do **not** add `@RequiresApi` in a test (lint's
  `UseSdkSuppress` rejects it). The default managed device `pixel7api33` is too old, so a new **`pixel8api34`**
  managed emulator (`aosp-atd`) runs it headless.
- **Severity policy: errors fail, warnings logged, all findings reported.** The `AccessibilityValidator`
  throws (fails the test) only for `AccessibilityCheckResultType.ERROR`; an `addCheckListener` logs every
  WARNING/INFO to logcat under tag `A11y`, and each test writes its actionable findings to a
  `a11y-<screen>.txt` file via `TestStorage` (collected by FTL, mirrored locally under
  `app/build/.../additional_output/` — plus AGP's per-test `logcat-<test>.txt`). Suppress a known-acceptable
  finding with `setSuppressingResultMatcher(...)`; widen the gate with `setThrowExceptionFor(...)`.
- **Contrast needs real pixels, so screenshot capture is hardware-gated.** ATF's text/image contrast checks
  read a screenshot; on the headless emulator `UiAutomation.takeScreenshot()` races the UI thread and
  returns null → ATF NPEs. So `setCaptureScreenshots(!isEmulator())`: **off on the emulator** (contrast
  reports NOT_RUN; structural checks — labels, touch targets, traversal, duplicate/redundant descriptions —
  still fully run) and **on for real hardware**, so contrast actually runs on the Firebase Test Lab
  physical-device pass (and Play's pre-launch report covers it too).
- **Run locally** (headless emulator, no physical device):
  `./gradlew :app:pixel8api34DebugAndroidTest -PseedDemo=true
  -Pandroid.testInstrumentationRunnerArguments.package=app.getknit.knit.a11y`.
- **Run on FTL**: the `a11y` package runs on an **API-34+** Firebase Test Lab device; it also rides the default
  FTL run but *skips* on the API 29/33 devices. (Maintainer runner lives in the `.private/` overlay.)

When driving the emulator over `adb`: the soft keyboard overlaps via `adjustResize`, so read element
coordinates from `uiautomator dump` rather than guessing; seed the photo picker by `screencap`-ing
into `/sdcard/Pictures` if you need an image to select. For the headless debug bridge (send/verify without
screenshots), see `context/debug-bridge.md`.
