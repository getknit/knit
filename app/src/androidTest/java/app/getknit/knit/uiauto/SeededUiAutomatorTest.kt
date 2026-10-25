package app.getknit.knit.uiauto

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.getknit.knit.BuildConfig
import app.getknit.knit.MainActivity
import app.getknit.knit.demo.DemoSeeder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import java.util.Locale
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
            "The androidTest APK must be built with -PseedDemo=true (see .agents/context/testing.md)."
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
     * task even if a prior launch left one around. Waits for [DemoSeeder.seeded] first, as `SeededUiTest.launch`
     * does: this suite runs in the app's own process, and the seed writes the peers long before the requests and
     * the room's newest posts, so a list drawn early keeps reflowing under a tap.
     */
    protected fun launch(route: String? = null) {
        runBlocking { withTimeout(SEED_TIMEOUT_MS) { DemoSeeder.seeded.await() } }
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

    /**
     * Like [requireDesc] but scoped to [parent]'s subtree. Use wherever a control repeats once per row and
     * the unscoped selector would return whichever copy the tree happens to yield first — the Requests
     * inbox draws one overflow per request, and the seeded inbox is never a single row. [parent] came from
     * a waiting call, so its children are already materialized and this needs no timeout of its own.
     */
    protected fun requireDescIn(
        parent: UiObject2,
        desc: String,
    ): UiObject2 =
        requireNotNull(parent.findObject(By.descContains(desc))) {
            "no view with contentDescription '$desc' inside '${parent.resourceName}'"
        }

    /**
     * Scrolls the screen's list in [direction] until a view matching [selector] is on screen, and returns it;
     * fails the test when it never shows within [timeoutMs]. A `LazyColumn` composes only the rows in its
     * viewport, so a row below the fold has no node at all and no plain wait can find it — how far down it
     * sits depends on the device's screen height (a short emulator and a tall FTL phone disagree). The list
     * itself fills in asynchronously, so a scroll that hits the end without a match waits for content and
     * tries again rather than failing at once.
     */
    protected fun scrollTo(
        selector: BySelector,
        label: String,
        direction: Direction = Direction.DOWN,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): UiObject2 {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            device.findObject(selector)?.let { return it }
            val list = device.findObject(By.scrollable(true).pkg(PKG))
            // scroll() is false once the list cannot move further: let the async content land, then look again.
            if (list == null || !list.scroll(direction, SCROLL_PERCENT)) {
                device.wait(Until.findObject(selector), SCROLL_SETTLE_MS)?.let { return it }
            }
        }
        error("no view matching $label within ${timeoutMs}ms of scrolling ${direction.name.lowercase(Locale.ROOT)}")
    }

    /** [scrollTo] a view with Compose testTag [tag]. */
    protected fun scrollToTag(
        tag: String,
        direction: Direction = Direction.DOWN,
    ): UiObject2 = scrollTo(byTag(tag), "testTag '$tag'", direction)

    /** [scrollTo] a view whose visible text contains [text]. */
    protected fun scrollToText(
        text: String,
        direction: Direction = Direction.DOWN,
    ): UiObject2 = scrollTo(By.textContains(text), "text '$text'", direction)

    /**
     * Waits up to [timeoutMs] for a view whose visible text equals [text] **exactly**; returns it or null.
     * Use where a substring match ([waitText]) is ambiguous — a dialog's confirm button often repeats a word
     * from its title (e.g. the "Block" button vs. the "Block this person?" title).
     */
    protected fun waitExactText(
        text: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): UiObject2? = device.wait(Until.findObject(By.text(text)), timeoutMs)

    /** Like [waitExactText] but fails the test (not returns null) when the view never appears — a click target. */
    protected fun requireExactText(
        text: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): UiObject2 = requireNotNull(waitExactText(text, timeoutMs)) { "no view with exact text '$text' within ${timeoutMs}ms" }

    /** Asserts a view with testTag [tag] appears within [timeoutMs]. */
    protected fun assertTag(
        tag: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) = assertTrue("expected testTag '$tag' on screen within ${timeoutMs}ms", waitTag(tag, timeoutMs) != null)

    /**
     * Asserts the view with testTag [tag] leaves the screen (or was never there) within [timeoutMs].
     * The counterpart to [assertTag] for a row that an action is supposed to remove — an empty-state
     * assertion would only hold if that row were the last one on the screen.
     */
    protected fun assertTagGone(
        tag: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) = assertTrue("expected testTag '$tag' to leave the screen within ${timeoutMs}ms", device.wait(Until.gone(byTag(tag)), timeoutMs))

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
     * True on a Gradle-managed / FTL **emulator** (ranchu/goldfish, including the `aosp-atd` images). Some
     * black-box behaviour can only be validated on real hardware: e.g. the headless ATD emulator's SystemUI
     * posts a notification correctly (confirmed via `dumpsys notification`) but never exposes its content to
     * the accessibility tree, so the shade can't be driven. A test that needs such a surface gates with
     * `Assume.assumeFalse(isEmulator())` and runs on the FTL physical-device pass. Mirrors the a11y suite.
     */
    protected fun isEmulator(): Boolean {
        val hardware = Build.HARDWARE.lowercase(Locale.ROOT)
        return hardware == "ranchu" ||
            hardware == "goldfish" ||
            Build.FINGERPRINT.contains("generic") ||
            Build.PRODUCT.contains("sdk") ||
            Build.PRODUCT.contains("atd")
    }

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

        /** How long [launch] waits for the demo seed to finish; `SeededUiTest.SEED_TIMEOUT_MS`'s value. */
        const val SEED_TIMEOUT_MS = 60_000L

        /** How much of the list's height one [scrollTo] step moves: under a screen, so no row is skipped over. */
        private const val SCROLL_PERCENT = 0.6f

        /** How long [scrollTo] waits for content at the end of the list before it tries again. */
        private const val SCROLL_SETTLE_MS = 1_000L
    }
}
