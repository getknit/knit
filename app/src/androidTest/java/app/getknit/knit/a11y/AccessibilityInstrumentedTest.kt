package app.getknit.knit.a11y

import android.os.Build
import android.util.Log
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.services.storage.TestStorage
import app.getknit.knit.ui.SeededUiTest
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Runs Google's **Accessibility Test Framework (ATF)** — the same framework the Play Console pre-launch
 * report runs — against every seeded screen, so a11y regressions (missing labels, sub-48dp touch targets,
 * low text/image contrast, bad traversal order) fail here before a build ever reaches Play.
 *
 * Reuses the seeded, radio-less harness ([SeededUiTest]): each test deep-links the real `MainActivity`
 * straight to a screen via the `demo_route` extra, waits for the async seed to render, then evaluates the
 * live Compose `AccessibilityNodeInfo` tree via [tryPerformAccessibilityChecks]. The `route`/`await` pairs
 * mirror the existing per-screen instrumented tests in `app.getknit.knit.ui`.
 *
 * **API-34 floor.** The Compose ATF integration ([enableAccessibilityChecks]/[tryPerformAccessibilityChecks])
 * is `@RequiresApi(34)`, so this suite needs an API-34+ device. `@SdkSuppress(minSdkVersion = 34)` makes the
 * runner *skip* (not fail) on the older FTL matrix devices (API 29/33) and doubles as lint's `NewApi` guard
 * for the checked calls (lint's `UseSdkSuppress` wants `@SdkSuppress`, not `@RequiresApi`, in tests). Run it
 * on the dedicated managed emulator:
 * ```
 * ./gradlew :app:pixel8api34DebugAndroidTest -PseedDemo=true \
 *     -Pandroid.testInstrumentationRunnerArguments.package=app.getknit.knit.a11y
 * ```
 * or on Firebase Test Lab's API-36 device.
 *
 * **Severity policy: errors fail, warnings logged, all findings reported.** The [AccessibilityValidator]
 * throws (fails the test) only for [AccessibilityCheckResultType.ERROR] — the Play-blocking class. The
 * [addCheckListener][AccessibilityValidator.addCheckListener] logs every WARNING/INFO to logcat under
 * [A11Y_TAG] and [writeReport] dumps the screen's actionable findings to a `a11y-<screen>.txt` file via
 * [TestStorage] (collected by FTL, mirrored locally under `app/build/.../additional_output/`). Tune the gate
 * with `setThrowExceptionFor(...)`; silence a known-acceptable finding with `setSuppressingResultMatcher(...)`.
 *
 * **Contrast needs real pixels.** ATF's text/image contrast checks read a screenshot. On the headless
 * emulator `UiAutomation.takeScreenshot()` races the UI thread and returns null → ATF NPEs, so screenshot
 * capture is enabled only on **real hardware** ([isEmulator] gate). On the emulator the contrast checks
 * report NOT_RUN (structural checks still run in full); on an FTL physical device
 * they run for real.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE) // API 34 — the ATF Compose API's floor
class AccessibilityInstrumentedTest : SeededUiTest() {
    @get:Rule
    val a11yTestName = TestName()

    /** Every result ATF produced for the screen under audit — filtered into a report in [writeReport]. */
    private val results = mutableListOf<String>()

    @Before
    fun enableChecks() {
        val validator =
            AccessibilityValidator()
                .setThrowExceptionFor(AccessibilityCheckResultType.ERROR) // ERROR fails the test (also the default)
                // Screenshots power the text/image CONTRAST checks. On the headless emulator
                // UiAutomation.takeScreenshot() races the UI thread and returns null → ATF's Screenshotter
                // copies it unguarded → an NPE that aborts the whole screen's audit. So capture is ON only on
                // real hardware (FTL); locally the contrast checks report NOT_RUN while every structural check
                // (labels, touch targets, traversal, duplicate/redundant descriptions) still runs.
                .setCaptureScreenshots(!isEmulator())
                .addCheckListener(
                    AccessibilityValidator.AccessibilityCheckListener { _, viewResults ->
                        viewResults.forEach { result ->
                            val line = "${result.type}: ${result.getMessage(Locale.getDefault())}"
                            results += line
                            // Log the actionable tiers (ERROR/WARNING/INFO); NOT_RUN/SUPPRESSED are noise.
                            if (result.type != AccessibilityCheckResultType.NOT_RUN &&
                                result.type != AccessibilityCheckResultType.SUPPRESSED
                            ) {
                                Log.w(A11Y_TAG, line)
                            }
                        }
                    },
                )
        compose.enableAccessibilityChecks(validator)
    }

    @Test fun chatList() = audit(route = null) { awaitTag("chat_row_nearby") }

    @Test fun nearbyChat() = audit(route = "chat/nearby") { awaitTag("chat_input") }

    @Test fun dmChat() = audit(route = "chat/samr1v00") { awaitTag("chat_input") }

    @Test fun contacts() = audit(route = "contacts") { awaitTag("contact_samr1v00") }

    @Test fun profile() = audit(route = "profile") { awaitTag("profile_name") }

    @Test fun diagnostics() = audit(route = "diagnostics") { awaitText("Maya Okonkwo") }

    /**
     * Launches [route], waits for its seeded content via [awaitContent], then audits the whole screen. A
     * failing ATF ERROR throws `AccessibilityViewCheckException`, whose message enumerates each violation
     * (check name + element), failing this test. The `finally` writes the report either way, so a passing
     * screen's WARNING/INFO findings are captured too — [SeededUiTest] still grabs the screenshot in its `@After`.
     */
    private fun audit(
        route: String?,
        awaitContent: () -> Unit,
    ) {
        results.clear()
        launch(route)
        awaitContent()
        try {
            compose.onRoot().tryPerformAccessibilityChecks()
        } finally {
            writeReport()
        }
    }

    /**
     * Dumps the screen's **actionable** findings (ERROR/WARNING/INFO) — with a trailing count of the
     * NOT_RUN/SUPPRESSED checks (not applicable to these elements; contrast on the emulator) — to
     * `a11y-<screen>.txt` via [TestStorage]. Never fails a test: a write error is swallowed and logged.
     */
    private fun writeReport() {
        val actionable = results.filterNot { it.startsWith("NOT_RUN") || it.startsWith("SUPPRESSED") }
        val skipped = results.size - actionable.size
        val body =
            buildString {
                appendLine("Accessibility (ATF) findings — ${a11yTestName.methodName}")
                if (actionable.isEmpty()) {
                    appendLine("No ERROR/WARNING/INFO findings.")
                } else {
                    actionable.forEach { appendLine("- $it") }
                }
                appendLine("[$skipped checks NOT_RUN/SUPPRESSED — not applicable to these elements; text/image")
                appendLine(" contrast is NOT_RUN unless run on real hardware, e.g. on Firebase Test Lab]")
            }
        runCatching {
            TestStorage().openOutputFile("a11y-${a11yTestName.methodName}.txt").use { it.write(body.toByteArray()) }
        }.onFailure { Log.w(A11Y_TAG, "a11y report write for '${a11yTestName.methodName}' failed", it) }
    }

    /** A Gradle-managed / FTL emulator (ranchu/goldfish) — where `takeScreenshot()` can't be trusted. */
    private fun isEmulator(): Boolean {
        val hardware = Build.HARDWARE.lowercase(Locale.ROOT)
        return hardware == "ranchu" ||
            hardware == "goldfish" ||
            Build.FINGERPRINT.contains("generic") ||
            Build.PRODUCT.contains("sdk") ||
            Build.PRODUCT.contains("atd")
    }

    private companion object {
        const val A11Y_TAG = "A11y"
    }
}
