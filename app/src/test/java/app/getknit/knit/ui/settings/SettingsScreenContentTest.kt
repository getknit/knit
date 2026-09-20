package app.getknit.knit.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.ui.BackgroundBattery
import app.getknit.knit.ui.DeviceSupervision
import app.getknit.knit.ui.UnusedAppPause
import app.getknit.knit.ui.theme.KnitTheme
import app.getknit.knit.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The app's settings: the three switches, the two rows that hand off to a plane's own screen (each present
 * only in a build that introduces that plane), and the profile header row that leads to the profile editor.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun render(
        header: ProfileHeader = ProfileHeader(name = "Alice", alias = "Cool Fox"),
        onOpenProfile: () -> Unit = {},
        relay: RelaySummary = RelaySummary(),
        onOpenRelays: () -> Unit = {},
        showInternetRelays: Boolean = true,
        lora: LoraSummary = LoraSummary(),
        onOpenLora: () -> Unit = {},
        contentFilteringEnabled: Boolean = true,
        onToggleContentFiltering: (Boolean) -> Unit = {},
        themeMode: ThemeMode = ThemeMode.System,
        onSelectThemeMode: (ThemeMode) -> Unit = {},
        showThemeMode: Boolean = true,
        linkPreviewsEnabled: Boolean = false,
        onToggleLinkPreviews: (Boolean) -> Unit = {},
        dynamicColor: Boolean = false,
        onToggleDynamicColor: (Boolean) -> Unit = {},
        showDynamicColor: Boolean = true,
        battery: BackgroundBattery = BackgroundBattery.Unrestricted,
        onAllowBattery: () -> Unit = {},
        onOpenBatterySettings: () -> Unit = {},
        onOpenAbout: () -> Unit = {},
        onOpenLicenses: () -> Unit = {},
        onOpenBackup: () -> Unit = {},
        supervision: DeviceSupervision = DeviceSupervision.None,
        unusedPause: UnusedAppPause? = null,
        onOpenUnusedPauseSettings: () -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                SettingsScreenContent(
                    form =
                        SettingsFormState(
                            header = header,
                            contentFilteringEnabled = contentFilteringEnabled,
                            themeMode = themeMode,
                            linkPreviewsEnabled = linkPreviewsEnabled,
                            dynamicColor = dynamicColor,
                            relay = relay,
                            lora = lora,
                        ),
                    battery = battery,
                    supervision = supervision,
                    onBack = {},
                    onOpenProfile = onOpenProfile,
                    onToggleContentFiltering = onToggleContentFiltering,
                    onSelectThemeMode = onSelectThemeMode,
                    showThemeMode = showThemeMode,
                    onToggleLinkPreviews = onToggleLinkPreviews,
                    onToggleDynamicColor = onToggleDynamicColor,
                    showDynamicColor = showDynamicColor,
                    onOpenRelays = onOpenRelays,
                    onOpenLora = onOpenLora,
                    showInternetRelays = showInternetRelays,
                    onAllowBattery = onAllowBattery,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onOpenAbout = onOpenAbout,
                    onOpenLicenses = onOpenLicenses,
                    onOpenBackup = onOpenBackup,
                    unusedPause = unusedPause,
                    onOpenUnusedPauseSettings = onOpenUnusedPauseSettings,
                )
            }
        }
    }

    /** The header row is how you reach your own profile now, so it carries your name and opens it. */
    @Test
    fun theProfileRowShowsTheNameAndOpensProfile() {
        var opened = 0
        render(onOpenProfile = { opened++ })

        compose.onNodeWithText("Alice").assertIsDisplayed()
        compose.onNodeWithTag("settings_profile_row").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    /**
     * Before a name is set the display name *is* the alias, so the alias line stays away: printing it as
     * both title and subtitle looks broken and makes a screen reader say it twice.
     */
    @Test
    fun theProfileRowShowsTheAliasOnlyOnceBeforeANameIsSet() {
        render(header = ProfileHeader(name = "Cool Fox", alias = null))

        compose.onAllNodesWithText("Cool Fox", substring = true).assertCountEquals(1)
    }

    @Test
    fun theContentFilteringRowTogglesTheFlag() {
        var toggled: Boolean? = null
        render(contentFilteringEnabled = true, onToggleContentFiltering = { toggled = it })
        compose.onNodeWithTag("settings_content_filtering").performScrollTo().assertIsOn()
        compose.onNodeWithTag("settings_content_filtering").performClick()
        assertEquals(false, toggled)
    }

    @Test
    fun internetRelayRowNavigatesWhenThePlaneIsIntroduced() {
        var opened = 0
        render(onOpenRelays = { opened++ }, showInternetRelays = true)

        compose.onNodeWithTag("settings_relays").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    /**
     * The build-flag half of hiding the Internet plane: with `BuildConfig.INTERNET_PLANE` off there is no
     * row, so a release user has no way into a screen whose switch would be inert anyway (the other half
     * is `SettingsStore.spoolEnabled`, which reads false on the same flag).
     */
    @Test
    fun internetRelayRowIsAbsentWhenThePlaneIsDark() {
        render(showInternetRelays = false)

        compose.onNodeWithTag("settings_relays").assertDoesNotExist()
    }

    @Test
    fun theLoraRowSaysWhetherTheBoardIsConnected() {
        render(lora = LoraSummary(enabled = true, boardName = "Meshtastic_1a2b", plane = LoraPlane.Live))
        compose.onNodeWithText("On · Meshtastic_1a2b · connected").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theLoraRowSaysWhenTheBoundBoardIsNotConnected() {
        render(lora = LoraSummary(enabled = true, boardName = "Meshtastic_1a2b", plane = LoraPlane.Down))
        compose.onNodeWithText("On · Meshtastic_1a2b · not connected").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theLoraRowShowsTheBoardsBattery() {
        val battery = BoardBattery(percent = 78, voltage = 3.92f, powered = false)
        render(lora = LoraSummary(enabled = true, boardName = "Meshtastic_1a2b", plane = LoraPlane.Live, battery = battery))
        compose.onNodeWithText("On · Meshtastic_1a2b · connected · battery 78%").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theThemeRowStartsOnSystemAndReportsAPin() {
        var picked: ThemeMode? = null
        render(onSelectThemeMode = { picked = it })
        compose.onNodeWithTag("settings_theme_mode").performScrollTo()
        compose.onNodeWithText("System").assertIsSelected()
        compose.onNodeWithText("Dark").performClick()
        assertEquals(ThemeMode.Dark, picked)
    }

    @Test
    fun theThemeRowShowsThePinnedMode() {
        render(themeMode = ThemeMode.Light)
        compose.onNodeWithTag("settings_theme_mode").performScrollTo()
        compose.onNodeWithText("Light").assertIsSelected()
    }

    /** Below API 31 there is no per-app night mode, so the control is absent rather than dead. */
    @Test
    fun theThemeRowIsAbsentWhereThePlatformCannotDoIt() {
        render(showThemeMode = false)
        compose.onNodeWithTag("settings_theme_mode").assertDoesNotExist()
    }

    @Test
    fun theWallpaperColoursRowIsOffByDefaultAndTogglesOn() {
        var toggled: Boolean? = null
        render(onToggleDynamicColor = { toggled = it })
        compose.onNodeWithTag("settings_dynamic_color").performScrollTo().assertIsOff()
        compose.onNodeWithTag("settings_dynamic_color").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun theWallpaperColoursRowTogglesOff() {
        var toggled: Boolean? = null
        render(dynamicColor = true, onToggleDynamicColor = { toggled = it })
        compose.onNodeWithTag("settings_dynamic_color").performScrollTo().assertIsOn()
        compose.onNodeWithTag("settings_dynamic_color").performClick()
        assertEquals(false, toggled)
    }

    /** Below API 31 the platform has no wallpaper palette, so the row is absent rather than dead. */
    @Test
    fun theWallpaperColoursRowIsAbsentWhereThePlatformCannotDoIt() {
        render(showDynamicColor = false)
        compose.onNodeWithTag("settings_dynamic_color").assertDoesNotExist()
    }

    @Test
    fun theLinkPreviewsRowIsOffByDefaultAndTogglesOn() {
        var toggled: Boolean? = null
        render(onToggleLinkPreviews = { toggled = it })
        compose.onNodeWithTag("settings_link_previews").performScrollTo().assertIsOff()
        compose.onNodeWithTag("settings_link_previews").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun theLinkPreviewsRowTogglesOff() {
        var toggled: Boolean? = null
        render(linkPreviewsEnabled = true, onToggleLinkPreviews = { toggled = it })
        compose.onNodeWithTag("settings_link_previews").performScrollTo().assertIsOn()
        compose.onNodeWithTag("settings_link_previews").performClick()
        assertEquals(false, toggled)
    }

    @Test
    fun theLinkPreviewsRowFollowsTheInternetPlaneSwitch() {
        render(showInternetRelays = false)
        compose.onNodeWithTag("settings_link_previews").assertDoesNotExist()
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

    /** About and the licenses list sit behind the overflow: neither is a setting, so neither is a row. */
    @Test
    fun theOverflowMenuOpensAboutAndTheLicenses() {
        var about = 0
        var licenses = 0
        render(onOpenAbout = { about++ }, onOpenLicenses = { licenses++ })

        compose.onNodeWithContentDescription(context.getString(R.string.chat_more_options)).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_menu_about)).performClick()
        assertEquals(1, about)

        compose.onNodeWithContentDescription(context.getString(R.string.chat_more_options)).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_menu_licenses)).performClick()
        assertEquals(1, licenses)
        assertEquals(1, about)
    }

    /** Backup and restore is a row, not a menu item: it changes what happens to your data, so it sits with the settings. */
    @Test
    fun theBackupRowOpensTheBackupScreen() {
        var backup = 0
        render(onOpenBackup = { backup++ })
        compose.onNodeWithTag("settings_backup").performScrollTo().performClick()
        assertEquals(1, backup)
    }
}
