package app.getknit.knit.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import app.getknit.knit.BuildConfig
import app.getknit.knit.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName

/**
 * Base for the Firebase Test Lab seeded UI suite (see .agents/context/testing.md). Every test launches the real
 * [MainActivity] against a **demo-seeded, radio-less** build: `-PseedDemo=true` swaps in the no-op
 * `DemoTransport` (so the two mesh radios are inert — exactly the Test Lab reality), seeds a full
 * conversation history into Room through the real repositories, and skips the onboarding permission gate.
 * Tests then assert against the deterministic seeded data (the "hiking" theme by default) by `testTag`.
 *
 * Isolation is Android Test Orchestrator + `clearPackageData=true` (app/build.gradle.kts): each test runs
 * in a fresh, data-wiped process, so `KnitApplication.onCreate` regenerates a fresh identity and re-seeds
 * the DB every time. Seeding is **async**, so tests MUST await content ([awaitTag]/[awaitText]/
 * [awaitContentDescription]) before asserting — never assert on immediate launch.
 *
 * Each test also captures one screenshot in [captureScreenshotAndClose] (pass or fail), surfaced in FTL's
 * Results → Screenshots tab so a screen's rendering is comparable across the API 29/33/36 matrix.
 */
abstract class SeededUiTest {
    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    @get:Rule
    val testName: TestName = TestName()

    private var scenario: ActivityScenario<MainActivity>? = null

    /**
     * Fail loudly if the androidTest APK was built without `-PseedDemo=true`. `BuildConfig.SEED_DEMO` is a
     * compile-time constant inlined into this test DEX, so a mis-build would otherwise leave every screen on
     * the onboarding gate. A [check] fails the test with a clear message; `Assume.assumeTrue` would instead
     * silently skip the whole suite green.
     */
    @Before
    fun requireSeededBuild() {
        check(BuildConfig.SEED_DEMO) {
            "The androidTest APK must be built with -PseedDemo=true (see .agents/context/testing.md)."
        }
    }

    /**
     * Runs after every test (pass or fail), while the activity is still up — [ActivityScenario.close] is
     * called only afterwards. Captures a screenshot named for the test class + method, then tears the
     * activity down. Capturing on failure too is deliberate: it's what makes a device-specific timeout
     * diagnosable from the FTL results without a re-run.
     */
    @After
    fun captureScreenshotAndClose() {
        screenshot("${javaClass.simpleName}-${testName.methodName}")
        scenario?.close()
        scenario = null
    }

    /**
     * Launches [MainActivity], optionally deep-linking straight to [route] via the debug `demo_route` extra
     * (e.g. `"chat/nearby"`, `"contacts"`, `"profile"`, `"diagnostics"`). A null route lands on the seeded
     * chat list.
     */
    protected fun launch(route: String? = null): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (route != null) putExtra(DEMO_ROUTE_EXTRA, route)
            }
        return ActivityScenario.launch<MainActivity>(intent).also { scenario = it }
    }

    /**
     * Captures a full-screen screenshot named [name] and writes it as a `.png` through the test-services
     * [TestStorage], which Firebase Test Lab collects as a per-device test output. This is the
     * scoped-storage-safe path — it needs no `WRITE_EXTERNAL_STORAGE` on any API level (the outcome the FTL
     * docs promise for API 29+), unlike `testlab-instr-lib`'s processor, which hardcodes a `/sdcard/screenshots`
     * write that only FTL's device environment grants (so that library silently no-ops on a plain emulator).
     *
     * Never fails a test: a capture/write error (e.g. no test-services storage when run without the
     * orchestrator, or a null capture) is swallowed and logged.
     */
    protected fun screenshot(name: String) {
        runCatching {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            TestStorage().openOutputFile("$name.png").use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            }
        }.onFailure { Log.w("SeededUiTest", "screenshot '$name' failed", it) }
    }

    /** Waits until at least one node tagged [tag] exists — absorbs the async seed + SQLCipher DB build. */
    protected fun awaitTag(
        tag: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) = compose.waitUntil(timeoutMs) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    /** Waits until at least one node contains [text] (substring match). */
    protected fun awaitText(
        text: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) = compose.waitUntil(timeoutMs) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Waits until at least one node carries [description] in its contentDescription. Chat-list rows fold
     * their title/preview into the row contentDescription (`clearAndSetSemantics`), so this is how a row is
     * matched by its conversation title.
     */
    protected fun awaitContentDescription(
        description: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) = compose.waitUntil(timeoutMs) {
        compose.onAllNodesWithContentDescription(description, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private companion object {
        const val DEMO_ROUTE_EXTRA = "demo_route"

        // PNG is lossless, so the quality arg is ignored; 100 is the conventional value.
        const val PNG_QUALITY = 100

        // Generous: on a clearPackageData'd cold process the async seed must regenerate the identity, build
        // the SQLCipher DB, and write ~30 rows + decode avatar blobs before the observed flows emit. Assert
        // only on content that is on-screen (a LazyColumn does not compose off-screen items no matter how
        // long you wait) — e.g. the newest message in an auto-scrolled chat, not the oldest. Sized for the
        // slowest device in the matrix (the low-end Galaxy A10 @29); on a fast device the await returns as
        // soon as the content appears, so the ceiling costs nothing on the happy path.
        const val DEFAULT_TIMEOUT_MS = 25_000L
    }
}
