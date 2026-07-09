package app.getknit.knit.uiauto

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.getknit.knit.BuildConfig
import app.getknit.knit.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import java.util.regex.Pattern

/**
 * Base for the **UIAutomator** black-box suite (`app.getknit.knit.uiauto`). Where the Compose suite
 * ([app.getknit.knit.ui.SeededUiTest]) drives the app *in-process* via the semantics tree, this drives the
 * *real running app* the way a user or automation agent does — through the accessibility / resource-id
 * layer — so it can reach what Compose testing structurally cannot: the **system notification shade** and
 * **process lifecycle** (Home / Recents / Back / rotation).
 *
 * Runs against the same `-PseedDemo=true` radio-less build: the no-op `DemoTransport`, Room pre-seeded
 * through the real repositories (the deterministic "hiking" cast — Maya Okonkwo + Sam/Dani/… + the
 * "Trailhead Crew" group), and the onboarding gate skipped. [requireSeededBuild] hard-fails otherwise.
 *
 * Isolation is Android Test Orchestrator + `clearPackageData=true` (app/build.gradle.kts): each test runs
 * in a fresh, data-wiped process, so the seed reruns every time and the runtime nodeId is regenerated.
 * Seeding is **async**, so tests MUST await content ([waitTag]/[waitText]/[waitDesc]) before asserting.
 *
 * Selectors: `testTagsAsResourceId` is enabled at the NavHost root (`ui/KnitApp.kt`), so every Compose
 * `testTag` surfaces to UIAutomator as a `resource-id`. Compose exports the tag **unqualified**
 * (`resource-id="chat_input"`), but [byTag] tolerates an optional `pkg:id/` prefix so the selector holds
 * regardless. Screens without testTags (Diagnostics, the Requests inbox rows) match by [waitText].
 */
abstract class SeededUiAutomatorTest {
    @get:Rule
    val testName: TestName = TestName()

    protected val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Fail loudly if the androidTest APK was built without `-PseedDemo=true`. `BuildConfig.SEED_DEMO` is a
     * compile-time constant inlined into this test DEX, so a mis-build would otherwise leave every screen on
     * the onboarding gate. A [check] fails the test with a clear message (vs. `assumeTrue`, which would
     * silently pass the whole suite green). Mirrors `SeededUiTest.requireSeededBuild`.
     */
    @Before
    fun requireSeededBuild() {
        check(BuildConfig.SEED_DEMO) {
            "The androidTest APK must be built with -PseedDemo=true (see scripts/ftl-uiauto.sh)."
        }
    }

    /**
     * Captures a screenshot (pass or fail) into the FTL-collected [TestStorage], restores rotation, and
     * returns to the launcher. Capturing on failure too is deliberate — it makes a device-specific timeout
     * diagnosable from the FTL results without a re-run.
     *
     * Deliberately does **not** `am force-stop` the package: this is a self-instrumenting test, so the test
     * process *is* `app.getknit.knit` — stopping it would kill the test mid-teardown and surface as
     * "instrumentation process crashed". Per-test isolation comes from the Orchestrator's `clearPackageData`
     * (app/build.gradle.kts), which wipes data + process between tests.
     */
    @After
    fun captureScreenshotAndReset() {
        screenshot("${javaClass.simpleName}-${testName.methodName}")
        runCatching {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
        runCatching { device.pressHome() }
    }

    /**
     * Cold-starts [MainActivity], optionally deep-linking straight to [route] via the debug `demo_route`
     * extra (e.g. `"chat/nearby"`, `"chat/samr1v00"`, `"contacts"`, `"diagnostics"`; null lands on the
     * seeded chat list), then waits for the app window to be foreground. `CLEAR_TASK` guarantees a fresh
     * task even if a prior launch left one around.
     */
    protected fun launch(route: String? = null) {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                if (route != null) putExtra(DEMO_ROUTE_EXTRA, route)
            }
        context.startActivity(intent)
        assertTrue(
            "app window ($PKG) did not come to the foreground within ${LAUNCH_TIMEOUT_MS}ms",
            device.wait(Until.hasObject(By.pkg(PKG).depth(0)), LAUNCH_TIMEOUT_MS),
        )
    }

    /**
     * Brings the already-running app task back to the foreground via the plain launcher intent (no
     * `demo_route`). Because [MainActivity] is `singleTask`, this reuses the existing instance and does not
     * re-navigate, so the current screen and in-memory state are retained — used to test whether state
     * survives a Home / Recents excursion.
     */
    protected fun bringToForeground() {
        // `am start` (shell uid) reliably brings the singleTask instance to the foreground across OEMs —
        // more robust than context.startActivity(launcherIntent), which Samsung One UI can leave stuck in
        // Recents. No demo_route, so KnitApp doesn't re-navigate: the current screen + in-memory state are
        // retained (onNewIntent on the existing instance, not a fresh onCreate).
        device.executeShellCommand("am start -n $PKG/${MainActivity::class.java.name}")
        assertTrue(
            "app window ($PKG) did not return to the foreground within ${LAUNCH_TIMEOUT_MS}ms",
            device.wait(Until.hasObject(By.pkg(PKG).depth(0)), LAUNCH_TIMEOUT_MS),
        )
    }

    /**
     * A resource-id selector for a Compose [tag]. Tolerant of both the unqualified form Compose actually
     * exports (`chat_input`) and a `pkg:id/chat_input` form, so the selector can't break on that detail.
     */
    protected fun byTag(tag: String): BySelector = By.res(Pattern.compile("(?:[\\w.]+:id/)?" + Pattern.quote(tag)))

    /** Waits up to [timeoutMs] for a view with Compose testTag [tag]; returns it or null. */
    protected fun waitTag(
        tag: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): UiObject2? = device.wait(Until.findObject(byTag(tag)), timeoutMs)

    /** Waits up to [timeoutMs] for a view whose visible text contains [text] (substring); returns it or null. */
    protected fun waitText(
        text: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): UiObject2? = device.wait(Until.findObject(By.textContains(text)), timeoutMs)

    /** Waits up to [timeoutMs] for a view whose contentDescription contains [desc] (substring); returns it or null. */
    protected fun waitDesc(
        desc: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): UiObject2? = device.wait(Until.findObject(By.descContains(desc)), timeoutMs)

    /** Like [waitTag] but fails the test (not returns null) when the view never appears. */
    protected fun requireTag(
        tag: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): UiObject2 = requireNotNull(waitTag(tag, timeoutMs)) { "no view with testTag '$tag' within ${timeoutMs}ms" }

    /** Like [waitText] but fails the test (not returns null) when the view never appears — a click target. */
    protected fun requireText(
        text: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): UiObject2 = requireNotNull(waitText(text, timeoutMs)) { "no view with text '$text' within ${timeoutMs}ms" }

    /** Like [waitDesc] but fails the test (not returns null) when the view never appears — a click target. */
    protected fun requireDesc(
        desc: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): UiObject2 = requireNotNull(waitDesc(desc, timeoutMs)) { "no view with contentDescription '$desc' within ${timeoutMs}ms" }

    /** Asserts a view with testTag [tag] appears within [timeoutMs]. */
    protected fun assertTag(
        tag: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) = assertTrue("expected testTag '$tag' on screen within ${timeoutMs}ms", waitTag(tag, timeoutMs) != null)

    /** Asserts a view whose text contains [text] appears within [timeoutMs]. */
    protected fun assertText(
        text: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) = assertTrue("expected text containing '$text' on screen within ${timeoutMs}ms", waitText(text, timeoutMs) != null)

    /** Asserts a view whose contentDescription contains [desc] appears within [timeoutMs]. */
    protected fun assertDesc(
        desc: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) = assertTrue("expected contentDescription containing '$desc' within ${timeoutMs}ms", waitDesc(desc, timeoutMs) != null)

    /** The localized app string for [resId] (assertions match real on-screen copy, not hardcoded English). */
    protected fun str(resId: Int): String = context.getString(resId)

    /**
     * Captures a full-screen screenshot named [name] and writes it as a `.png` through the test-services
     * [TestStorage], which Firebase Test Lab collects as a per-device output — the scoped-storage-safe path
     * (no `WRITE_EXTERNAL_STORAGE` on any API level). Never fails a test: a capture/write error (e.g. no
     * test-services storage when run without the orchestrator) is swallowed and logged. Mirrors
     * `SeededUiTest.screenshot`.
     */
    protected fun screenshot(name: String) {
        runCatching {
            val bitmap: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            TestStorage().openOutputFile("$name.png").use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            }
        }.onFailure { Log.w("SeededUiAutomatorTest", "screenshot '$name' failed", it) }
    }

    protected companion object {
        /** The process UIAutomator drives — no debug applicationIdSuffix, so debug == release id. */
        val PKG: String = BuildConfig.APPLICATION_ID

        const val DEMO_ROUTE_EXTRA = "demo_route"

        // PNG is lossless, so the quality arg is ignored; 100 is the conventional value.
        private const val PNG_QUALITY = 100

        /** Generous ceiling for content: a clearPackageData'd cold process must regenerate the identity,
         * build the SQLCipher DB, and write ~30 seeded rows before the observed flows emit. Sized for the
         * slowest device in the FTL matrix (Galaxy A10 @29); a fast device returns as soon as content shows. */
        const val DEFAULT_TIMEOUT_MS = 25_000L

        /** A text/DM send cold-loads the on-device tflite moderator on first use; the echo can lag ~a minute. */
        const val SEND_ECHO_TIMEOUT_MS = 60_000L

        /** Foreground-window wait after a cold start. */
        const val LAUNCH_TIMEOUT_MS = 15_000L
    }
}
