package app.getknit.knit.ui.onboarding

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.ui.DeviceSupervision
import app.getknit.knit.ui.MeshPermissionTier
import app.getknit.knit.ui.UnusedAppPause
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The app's front door. Every test renders one fixed step of the stateless content — no transition is
 * driven, the ViewModel's ordering is `OnboardingViewModelTest`'s job — and the load-bearing case is that
 * Start needs the **radio** grants only: notifications and battery are rows, never the gate.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Calls {
        var next = 0
        var back = 0
        var radio = 0
        var notifications = 0
        var battery = 0
        var settings = 0
        var unused = 0
        var ready = 0
        var restore = 0
        var typed = ""
    }

    private fun render(
        step: OnboardingStep,
        name: String = "",
        rows: PermissionRows = PermissionRows.FRESH,
        tier: MeshPermissionTier = MeshPermissionTier.NEARBY_DEVICES,
        meshSupported: Boolean = true,
    ): Calls {
        val calls = Calls()
        compose.setContent {
            KnitTheme {
                OnboardingScreenContent(
                    step = step,
                    name = name,
                    alias = "SmartlyBrightSparrow",
                    nodeId = "node-test",
                    rows = rows,
                    tier = tier,
                    meshSupported = meshSupported,
                    onNext = { calls.next++ },
                    onBack = { calls.back++ },
                    onNameChange = { calls.typed = it },
                    onNameCommit = {},
                    onRequestRadio = { calls.radio++ },
                    onRequestNotifications = { calls.notifications++ },
                    onAllowBattery = { calls.battery++ },
                    onOpenSettings = { calls.settings++ },
                    onReady = { calls.ready++ },
                    onOpenUnusedPauseSettings = { calls.unused++ },
                    onRestore = { calls.restore++ },
                )
            }
        }
        return calls
    }

    @Test
    fun welcomeGetStartedAdvances() {
        val calls = render(OnboardingStep.WELCOME)
        compose.onNodeWithText(context.getString(R.string.onboarding_title)).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_next").performClick()
        assertEquals(1, calls.next)
    }

    /** The second way in: a phone replacing another one restores from a backup instead of minting itself. */
    @Test
    fun welcomeOffersARestoreFromABackup() {
        val calls = render(OnboardingStep.WELCOME)
        compose
            .onNodeWithTag("onboarding_restore")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, calls.restore)
        assertEquals(0, calls.next)
    }

    @Test
    fun nameStepReadsSkipUntilSomethingIsTyped() {
        val calls = render(OnboardingStep.NAME)
        compose.onNodeWithTag("onboarding_next").assertTextEquals(context.getString(R.string.onboarding_name_skip))
        compose.onNodeWithTag("onboarding_name").performTextInput("Alice")
        assertEquals("Alice", calls.typed)
    }

    @Test
    fun nameStepReadsContinueOnceTyped() {
        render(OnboardingStep.NAME, name = "Alice")
        compose.onNodeWithTag("onboarding_next").assertTextEquals(context.getString(R.string.onboarding_continue))
    }

    @Test
    fun nameStepShowsTheAvatarPreview() {
        // The initial itself is semantics-cleared inside Avatar (decorative; AvatarTest pins the letter),
        // so only the preview's presence is observable here — in the unmerged tree, under the page column.
        render(OnboardingStep.NAME, name = "alice")
        compose.onNodeWithTag("onboarding_avatar", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun nameStepImeDoneContinues() {
        val calls = render(OnboardingStep.NAME)
        compose.onNodeWithTag("onboarding_name").performImeAction()
        assertEquals(1, calls.next)
    }

    @Test
    fun startIsDisabledUntilTheRadioGrantsAreHeld() {
        val calls = render(OnboardingStep.PERMISSIONS)
        compose.onNodeWithTag("onboarding_start").assertIsNotEnabled()
        compose.onNodeWithTag("onboarding_grant").performClick()
        assertEquals(1, calls.radio)
    }

    /** The decision this makeover pins: notifications and battery are optional, so Start needs the radios alone. */
    @Test
    fun startNeedsOnlyTheRadios() {
        val calls =
            render(
                OnboardingStep.PERMISSIONS,
                rows = PermissionRows.FRESH.copy(radioGranted = true),
            )
        compose.onNodeWithTag("onboarding_start").assertIsEnabled()
        // The optional rows are still there, still asking, and still not in the way.
        compose.onNodeWithTag("onboarding_notifications").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding_battery").performScrollTo().performClick()
        assertEquals(1, calls.notifications)
        assertEquals(1, calls.battery)
        compose.onNodeWithTag("onboarding_start").performClick()
        assertEquals(1, calls.ready)
    }

    @Test
    fun grantedRowsShowTheCheckAndNoButton() {
        render(OnboardingStep.PERMISSIONS, rows = PermissionRows.ALL)
        // The row merges its descendants into one TalkBack item, so the check's tag lives in the unmerged tree.
        compose.onNodeWithTag("onboarding_grant_granted", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_grant").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_notifications").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_battery").assertDoesNotExist()
    }

    /**
     * Battery use set to Restricted is the one battery state no dialog of ours can change (ADR 2026-09.f69x
     * is what it used to crash on): the row lands on "Open settings" with its own hint, not Android's
     * "won't ask again", and never on the exemption prompt — even when the exemption reads true underneath.
     */
    @Test
    fun aRestrictedBatterySettingOffersSettingsWithItsOwnHint() {
        val calls =
            render(
                OnboardingStep.PERMISSIONS,
                rows = PermissionRows.ALL.copy(batteryRestricted = true),
            )
        compose.onNodeWithTag("onboarding_battery").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_battery_granted", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.onboarding_perm_battery_restricted_hint)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("onboarding_battery_settings").performScrollTo().performClick()
        assertEquals(1, calls.settings)
        assertEquals(0, calls.battery)
        // Still optional: Start does not care.
        compose.onNodeWithTag("onboarding_start").assertIsEnabled()
    }

    /** Android 10 has no unused-app switch, so a phone that reports none draws no row for it. */
    @Test
    fun theUnusedAppRowIsAbsentWhereThePlatformHasNoSwitch() {
        render(OnboardingStep.PERMISSIONS, rows = PermissionRows.ALL)
        compose.onNodeWithText(context.getString(R.string.onboarding_perm_unused_title)).assertDoesNotExist()
    }

    /**
     * The unused-app switch has no dialog: while it is on (the platform default, so nothing is coloured as
     * an error) the row offers only its own settings page, through its own callback — not the generic
     * app-settings one, since Android 11 keeps the switch on a page of its own — and Start does not care.
     */
    @Test
    fun theUnusedAppRowOffersItsOwnSettingsWhileTheSwitchIsOn() {
        val calls = render(OnboardingStep.PERMISSIONS, rows = PermissionRows.ALL.copy(unusedPause = UnusedAppPause.On))
        compose.onNodeWithTag("onboarding_unused").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_unused_granted", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.onboarding_perm_unused_hint)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("onboarding_unused_settings").performScrollTo().performClick()
        assertEquals(1, calls.unused)
        assertEquals(0, calls.settings)
        compose.onNodeWithTag("onboarding_start").assertIsEnabled()
    }

    @Test
    fun theUnusedAppRowShowsTheCheckOnceTheSwitchIsOff() {
        render(OnboardingStep.PERMISSIONS, rows = PermissionRows.ALL.copy(unusedPause = UnusedAppPause.Off))
        compose.onNodeWithTag("onboarding_unused_granted", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("onboarding_unused_settings").assertDoesNotExist()
    }

    @Test
    fun aRadioGrantAndroidWontAskForAgainOffersSettings() {
        val calls = render(OnboardingStep.PERMISSIONS, rows = PermissionRows.FRESH.copy(radioNeedsSettings = true))
        compose.onNodeWithTag("onboarding_grant").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.onboarding_perm_denied_hint)).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_grant_settings").performClick()
        assertEquals(1, calls.settings)
        assertEquals(0, calls.radio)
    }

    /**
     * On a Family Link phone the same instant refusal may be a parent's, but only where a parent can hold
     * that grant: the radio row on the Location tiers names them and where they allow it; on the Nearby
     * devices tier the generic "won't ask again" line stays (ADR 2026-09.a8ud).
     */
    @Test
    fun aFamilyLinkPhoneNamesTheParentOnlyWhereLocationGatesTheRadios() {
        render(
            OnboardingStep.PERMISSIONS,
            rows = PermissionRows.FRESH.copy(radioNeedsSettings = true, supervision = DeviceSupervision.FamilyLink),
            tier = MeshPermissionTier.LOCATION_AND_BLUETOOTH,
        )
        compose.onNodeWithText(context.getString(R.string.onboarding_perm_denied_hint_family_link)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.onboarding_perm_denied_hint)).assertDoesNotExist()
    }

    @Test
    fun aFamilyLinkPhoneKeepsTheGenericHintOnTheNearbyDevicesTier() {
        val calls =
            render(
                OnboardingStep.PERMISSIONS,
                rows = PermissionRows.FRESH.copy(radioNeedsSettings = true, supervision = DeviceSupervision.FamilyLink),
                tier = MeshPermissionTier.NEARBY_DEVICES,
            )
        compose.onNodeWithText(context.getString(R.string.onboarding_perm_denied_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.onboarding_perm_denied_hint_family_link)).assertDoesNotExist()
        // Still the same row: Open settings still opens settings, and Start still ignores it.
        compose.onNodeWithTag("onboarding_grant_settings").performClick()
        assertEquals(1, calls.settings)
    }

    @Test
    fun aManagedPhoneNamesTheAdministratorOnEveryTier() {
        render(
            OnboardingStep.PERMISSIONS,
            rows =
                PermissionRows.FRESH.copy(
                    radioNeedsSettings = true,
                    notificationsNeedSettings = true,
                    supervision = DeviceSupervision.Managed,
                ),
            tier = MeshPermissionTier.NEARBY_DEVICES,
        )
        // Both the radio and the notifications row carry it.
        compose.onAllNodesWithText(context.getString(R.string.onboarding_perm_denied_hint_managed)).assertCountEquals(2)
        compose.onNodeWithText(context.getString(R.string.onboarding_perm_denied_hint)).assertDoesNotExist()
    }

    @Test
    fun unsupportedHardwareDoesNotBlockStart() {
        render(
            OnboardingStep.PERMISSIONS,
            rows = PermissionRows.FRESH.copy(radioGranted = true),
            meshSupported = false,
        )
        // No mesh radio hardware (the radio-less Firebase Test Lab / single-radio-missing reality), yet
        // Start gates only on the grants, independent of meshSupported — the app degrades gracefully rather
        // than dead-ending, so the user can still reach what they already have.
        compose.onNodeWithTag("onboarding_start").assertIsEnabled()
        // And the notice names the gate that fired it: neither radio, not "no Wi-Fi Aware" (work item 18).
        compose.onNodeWithText(context.getString(R.string.onboarding_unsupported)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theNotificationsRowExistsOnlyWhereTheGrantIsRuntime() {
        render(OnboardingStep.PERMISSIONS, tier = MeshPermissionTier.LOCATION)
        compose.onNodeWithTag("onboarding_notifications").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.onboarding_perm_location_title)).assertIsDisplayed()
    }

    @Test
    fun android12NamesLocationAndBluetooth() {
        render(OnboardingStep.PERMISSIONS, tier = MeshPermissionTier.LOCATION_AND_BLUETOOTH)
        compose.onNodeWithTag("onboarding_notifications").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.onboarding_perm_location_bt_title)).assertIsDisplayed()
    }
}
