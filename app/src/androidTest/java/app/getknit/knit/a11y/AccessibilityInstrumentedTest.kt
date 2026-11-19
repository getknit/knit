package app.getknit.knit.a11y

import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.services.storage.TestStorage
import app.getknit.knit.ui.SeededUiTest
import app.getknit.knit.ui.chat.emojiOnlyCount
import app.getknit.knit.ui.theme.OutlineDark
import app.getknit.knit.ui.theme.OutlineLight
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import org.hamcrest.BaseMatcher
import org.hamcrest.Description
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
 * [TestStorage] (collected by FTL; pulled locally into `app/build/intermediates/…_android_test_additional_output/`
 * through the `useTestStorageService` runner argument). Tune the gate
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
                .setSuppressingResultMatcher(ContrastMisreads)
                .addCheckListener(
                    AccessibilityValidator.AccessibilityCheckListener { _, viewResults ->
                        viewResults.forEach { result ->
                            val line =
                                "${result.type} [${result.sourceCheckClass.simpleName} @ ${where(result)}]: " +
                                    result.getMessage(Locale.getDefault())
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

    // A contact who is verified, open to chat, in a group with us and claims a board, so this audits the
    // badge strip, the Message button and every row of both sections at once — the peer profile had no
    // ATF case at all before its sections landed (ADR 2026-09.hknx). Anchored on the node-id row, the
    // last one the "in common" pre-combine fills.
    @Test fun profileDetails() = audit(route = "profileDetails/danich01") { awaitTag("profile_details_node_id") }

    // Anchored on the header row rather than the Scaffold tag: `screen_settings` exists from the first
    // frame, before the header's DataStore read lands, so it would audit a half-empty screen.
    @Test fun settings() = audit(route = "settings") { awaitTag("settings_profile_row") }

    // The seeded build latches the toxicity model (DemoSeeder.seedLatchedModel), so this audits the
    // poison-pill row and its error-tinted copy too, not just the metrics list.
    @Test fun diagnostics() = audit(route = "diagnostics") { awaitText("Maya Okonkwo") }

    // About is synchronous — BuildConfig, Build and one PackageManager read — so any tag would do; the last
    // Build row keeps the anchor honest if a row ever turns async. The licenses list is a lazy column, so its
    // first row is the one that is on screen; the GPL text is the only screen here with an IO read, and its
    // body tag appears only once the paragraphs are in.
    @Test fun about() = audit(route = "about") { awaitTag("about_build_device") }

    @Test fun licenses() = audit(route = "licenses") { awaitTag("licenses_row_knit") }

    @Test fun licenseText() = audit(route = "license/gpl-3.0-or-later") { awaitTag("license_text_body") }

    @Test fun internetRelays() = audit(route = "relays") { awaitTag("relays_switch") }

    // The seeded build banks lifetime numbers, met peers and a few custodied frames (DemoWriter.seedYourMesh),
    // so this audits the populated screen — the hero, the carrying card and the three stat rows — not the
    // fresh-install copy. Anchored on the last stat row, which is the row a slow DataStore read fills last.
    @Test fun yourMesh() = audit(route = "yourMesh") { awaitTag("your_mesh_people_met") }

    // The seeded build reports a bound, connected, Knit-provisioned board (DemoPlanes + DemoLoraPlane), so
    // this audits the populated screen — the live status block, the battery and signal rows, the picker and
    // the Restore action — rather than the "pair a board first" empty state an emulator would otherwise give.
    @Test fun loraRadio() = audit(route = "lora") { awaitTag("lora_switch") }

    // The seeded build leaves one stranger's DM and one stranger's group unaccepted (DemoScenario.requests /
    // requestGroup), so this audits the inbox with rows — the accept/block/delete actions on each — not the
    // empty state.
    @Test fun messageRequests() = audit(route = "requests") { awaitTag("request_row_river7x2") }

    // One seeded blocked peer (DemoScenario.blocked), so the list has a row and its unblock action to audit.
    @Test fun blockedUsers() = audit(route = "blocked") { awaitTag("screen_blocked_users") }

    // demo-group-4 is one of ours, seeded with three reactors across two emoji AND delivered to two of its
    // three other members, so this audits the populated screen — the filter chips, the people list, and both
    // halves of the delivered/waiting split (whose rows carry their status in their own description, since a
    // section header is not announced with the rows beneath it) — not the empty state.
    @Test fun messageDetails() = audit(route = "messageDetails/demo-group-4") { awaitTag("recipient_row_samr1v00") }

    // The seeded build plants one synthetic crash (DemoSeeder.seedCrashReport), so this audits the real
    // screen — dense monospace trace and the error-tinted destructive action — not the empty state.
    @Test fun crashLog() = audit(route = "crash") { awaitTag("screen_crash_log") }

    @Test fun addContact() = audit(route = "addContact") { awaitTag("screen_add_contact") }

    // Onboarding, all four pages. The seeded build normally skips the gate, but the demo route lands on it
    // directly as a first run (WELCOME → PRESET → NAME → PERMISSIONS); nothing here needs seeded data, and the
    // audit never taps Allow (the system dialog is another window, not this screen). The preset page's Continue
    // waits for a pick, so every page past it taps one first.
    @Test fun onboardingWelcome() = audit(route = "onboarding") { awaitTag("onboarding_page_welcome") }

    // Everyday picked, so the audit covers the selected card and the relay disclosure it opens, not only the
    // unpicked group.
    @Test
    fun onboardingPreset() =
        audit(route = "onboarding") {
            toPresetPage()
            compose.onNodeWithTag("preset_everyday").performScrollTo().performClick()
            awaitTag("preset_relay_disclosure")
        }

    @Test
    fun onboardingName() =
        audit(route = "onboarding") {
            toNamePage()
        }

    @Test
    fun onboardingPermissions() =
        audit(route = "onboarding") {
            toNamePage()
            compose.onNodeWithTag("onboarding_next").performClick()
            awaitTag("onboarding_page_permissions")
        }

    private fun toPresetPage() {
        awaitTag("onboarding_page_welcome")
        compose.onNodeWithTag("onboarding_next").performClick()
        awaitTag("onboarding_page_preset")
    }

    private fun toNamePage() {
        toPresetPage()
        compose.onNodeWithTag("preset_private").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding_next").assertIsEnabled().performClick()
        awaitTag("onboarding_page_name")
    }

    // Search, three ways: the idle field, a query with all three sections answering (Sam is a chat, a
    // contact and a speaker), and the no-results state.
    @Test fun searchIdle() = audit(route = "search") { awaitTag("search_input") }

    @Test
    fun searchResults() =
        audit(route = "search") {
            awaitTag("search_input")
            compose.onNodeWithTag("search_input").performTextInput("sam")
            awaitTag("search_result_person_samr1v00")
        }

    @Test
    fun searchNoResults() =
        audit(route = "search") {
            awaitTag("search_input")
            compose.onNodeWithTag("search_input").performTextInput("zzzz")
            awaitTag("search_empty")
        }

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

    /**
     * Names the element a finding is about — its test tag when it has one, else its class — plus its screen
     * bounds, so a duplicate-label finding says *which* items collide rather than only how many.
     */
    private fun where(result: AccessibilityViewCheckResult): String {
        val element = result.element ?: return "screen"
        val name = element.testTag?.toString()?.takeIf { it.isNotEmpty() } ?: element.className?.toString()
        return "$name ${element.boundsInScreen}"
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

    /**
     * The contrast findings that measure something other than the text — each case below was a WARNING on
     * the FTL hardware pass, and each is exact, so a real low-contrast label still reports:
     *
     * - **Emoji.** A reaction glyph's "text colour" is its artwork (the yellow of 😂, the red of ❤️);
     *   contrast is not defined for a colour bitmap.
     * - **Disabled controls.** WCAG 1.4.3 exempts an inactive component, and ATF skips one itself
     *   (`RESULT_ID_NOT_ENABLED`) — but Compose exposes a disabled button's label as its own text node that
     *   carries no enabled state, so ATF measures the greyed label. The disabled state is on an ancestor.
     * - **An outlined field's border.** An empty or short field is mostly 1dp stroke by pixel count, so the
     *   heuristic picks the theme's `outline` as the "foreground". The stroke is a component boundary
     *   (WCAG 1.4.11, 3:1, which it passes); the field's own text is drawn in `onSurface`.
     */
    private object ContrastMisreads : BaseMatcher<AccessibilityViewCheckResult>() {
        private val outlines = setOf(OutlineLight.toArgb(), OutlineDark.toArgb())

        override fun matches(item: Any?): Boolean {
            val result = item as? AccessibilityViewCheckResult ?: return false
            if (result.sourceCheckClass != TextContrastCheck::class.java) return false
            val element = result.element ?: return false
            val text = element.text?.toString().orEmpty()
            return (text.isNotBlank() && emojiOnlyCount(text) > 0) ||
                generateSequence(element) { it.parentView }.any { !it.isEnabled } ||
                (element.isEditable == true && result.metadata?.getInt(TextContrastCheck.KEY_FOREGROUND_COLOR, 0) in outlines)
        }

        override fun describeTo(description: Description) {
            description.appendText("a text-contrast finding on emoji, a disabled control or a field border")
        }
    }

    private companion object {
        const val A11Y_TAG = "A11y"
    }
}
