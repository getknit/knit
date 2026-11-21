package app.getknit.knit.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.ui.BackgroundBattery
import app.getknit.knit.ui.DeviceSupervision
import app.getknit.knit.ui.UnusedAppPause
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Background running: the battery position and its one action (ADR 2026-09.gc3m), the unused-app switch
 * (ADR 2026-09.54xg) and the supervision line (ADR 2026-09.a8ud), which sat at the foot of Settings until they got a
 * page of their own.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundRunningScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun render(
        battery: BackgroundBattery = BackgroundBattery.Unrestricted,
        onAllowBattery: () -> Unit = {},
        onOpenBatterySettings: () -> Unit = {},
        supervision: DeviceSupervision = DeviceSupervision.None,
        unusedPause: UnusedAppPause? = null,
        onOpenUnusedPauseSettings: () -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                BackgroundRunningScreenContent(
                    battery = battery,
                    onBack = {},
                    onAllowBattery = onAllowBattery,
                    onOpenBatterySettings = onOpenBatterySettings,
                    supervision = supervision,
                    unusedPause = unusedPause,
                    onOpenUnusedPauseSettings = onOpenUnusedPauseSettings,
                )
            }
        }
    }

    /** Unrestricted is the quiet state: a status line and nothing to press. */
    @Test
    fun theBatteryRowIsQuietWhenUnrestricted() {
        render(battery = BackgroundBattery.Unrestricted)
        compose.onNodeWithText(context.getString(R.string.battery_allowed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_battery_allow").assertDoesNotExist()
        compose.onNodeWithTag("settings_battery_settings").assertDoesNotExist()
    }

    /** Optimized (Android's default) offers the exemption prompt, the one dialog the app can raise itself. */
    @Test
    fun theBatteryRowOffersTheExemptionWhenOptimized() {
        var allowed = 0
        render(battery = BackgroundBattery.Optimized, onAllowBattery = { allowed++ })
        compose.onNodeWithText(context.getString(R.string.battery_optimized)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_battery_allow").performScrollTo().performClick()
        assertEquals(1, allowed)
    }

    /**
     * Restricted is the state ADR 2026-09.f69x's crash lived in, and no prompt of ours can lift it: the row
     * says so and sends the user to the app's info page instead of the exemption dialog.
     */
    @Test
    fun theBatteryRowSendsARestrictedInstallToSettings() {
        var allowed = 0
        var opened = 0
        render(battery = BackgroundBattery.Restricted, onAllowBattery = { allowed++ }, onOpenBatterySettings = { opened++ })
        compose.onNodeWithText(context.getString(R.string.battery_restricted)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_battery_allow").assertDoesNotExist()
        compose.onNodeWithTag("settings_battery_settings").performScrollTo().performClick()
        assertEquals(1, opened)
        assertEquals(0, allowed)
    }

    /** Android 10 has no unused-app switch, so a phone that reports none gets no line about it. */
    @Test
    fun theUnusedAppRowIsAbsentWhereThePlatformHasNoSwitch() {
        render(unusedPause = null)
        compose.onNodeWithTag("settings_unused").assertDoesNotExist()
    }

    @Test
    fun theUnusedAppRowIsQuietOnceTheSwitchIsOff() {
        render(unusedPause = UnusedAppPause.Off)
        compose.onNodeWithText(context.getString(R.string.unused_pause_off)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_unused_settings").assertDoesNotExist()
    }

    /** The switch has no prompt of its own, so the only action while it is on is its settings page. */
    @Test
    fun theUnusedAppRowSendsTheDefaultToSettings() {
        var opened = 0
        render(unusedPause = UnusedAppPause.On, onOpenUnusedPauseSettings = { opened++ })
        compose.onNodeWithText(context.getString(R.string.unused_pause_on)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_unused_settings").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    /** A plain phone has nobody to name, so the supervision line is not there at all. */
    @Test
    fun aPlainPhoneShowsNoSupervisionLine() {
        render()
        compose.onNodeWithTag("settings_supervised").assertDoesNotExist()
    }

    /**
     * A Family Link phone says so under the battery row, in the words the trial earned: the mesh keeps
     * running through a pause, and a parent holds the permissions (ADR 2026-09.a8ud).
     */
    @Test
    fun aFamilyLinkPhoneSaysSoUnderTheBatteryRow() {
        render(supervision = DeviceSupervision.FamilyLink)
        compose.onNodeWithTag("settings_supervised").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_supervised_family_link)).assertIsDisplayed()
    }

    @Test
    fun aManagedPhoneNamesItsAdministrator() {
        render(supervision = DeviceSupervision.Managed)
        compose.onNodeWithText(context.getString(R.string.settings_supervised_managed)).performScrollTo().assertIsDisplayed()
    }
}
