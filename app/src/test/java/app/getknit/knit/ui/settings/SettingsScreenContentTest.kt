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
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.ui.BackgroundBattery
import app.getknit.knit.ui.theme.KnitTheme
import app.getknit.knit.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The app's settings, in five headed groups: the switches, the rows that hand off to Settings › Chats and to a plane's
 * own screen (each plane present only in a build that introduces it), and the profile header row that leads to the
 * profile editor.
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
        onOpenChats: () -> Unit = {},
        relay: RelaySummary = RelaySummary(),
        onOpenRelays: () -> Unit = {},
        showInternetRelays: Boolean = true,
        lora: LoraSummary = LoraSummary(),
        onOpenLora: () -> Unit = {},
        themeMode: ThemeMode = ThemeMode.System,
        onSelectThemeMode: (ThemeMode) -> Unit = {},
        showThemeMode: Boolean = true,
        wifiAware: Boolean = true,
        calls: CallsSummary = CallsSummary(),
        onOpenCalls: () -> Unit = {},
        showCalls: Boolean = true,
        dynamicColor: Boolean = false,
        onToggleDynamicColor: (Boolean) -> Unit = {},
        showDynamicColor: Boolean = true,
        battery: BackgroundBattery = BackgroundBattery.Unrestricted,
        onOpenBackgroundRunning: () -> Unit = {},
        onOpenAbout: () -> Unit = {},
        onOpenLicenses: () -> Unit = {},
        onOpenBackup: () -> Unit = {},
        onOpenBlockedUsers: () -> Unit = {},
        onOpenStartingPoint: () -> Unit = {},
        showLoraRadio: Boolean = true,
        cloneVisible: Boolean = false,
        onSignOut: () -> Unit = {},
        wifiAwareOn: Boolean = true,
        onToggleWifiAware: (Boolean) -> Unit = {},
        watchOn: Boolean = false,
        onToggleWatch: (Boolean) -> Unit = {},
        showWatch: Boolean = true,
    ) {
        compose.setContent {
            KnitTheme {
                SettingsScreenContent(
                    form =
                        SettingsFormState(
                            header = header,
                            themeMode = themeMode,
                            calls = calls,
                            dynamicColor = dynamicColor,
                            relay = relay,
                            lora = lora,
                            cloneVisible = cloneVisible,
                            wifiAwareOn = wifiAwareOn,
                            watchOn = watchOn,
                        ),
                    battery = battery,
                    onBack = {},
                    onOpenProfile = onOpenProfile,
                    onOpenChats = onOpenChats,
                    onSelectThemeMode = onSelectThemeMode,
                    showThemeMode = showThemeMode,
                    wifiAware = wifiAware,
                    onToggleWifiAware = onToggleWifiAware,
                    onToggleWatch = onToggleWatch,
                    showWatch = showWatch,
                    onOpenCalls = onOpenCalls,
                    showCalls = showCalls,
                    onToggleDynamicColor = onToggleDynamicColor,
                    showDynamicColor = showDynamicColor,
                    onOpenRelays = onOpenRelays,
                    onOpenLora = onOpenLora,
                    showInternetRelays = showInternetRelays,
                    showLoraRadio = showLoraRadio,
                    onOpenBackgroundRunning = onOpenBackgroundRunning,
                    onOpenStartingPoint = onOpenStartingPoint,
                    onOpenAbout = onOpenAbout,
                    onOpenLicenses = onOpenLicenses,
                    onOpenBackup = onOpenBackup,
                    onOpenBlockedUsers = onOpenBlockedUsers,
                    onSignOut = onSignOut,
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

    /** Typing, link previews, content filtering and large downloads live behind one row, which opens Settings › Chats. */
    @Test
    fun theChatsRowOpensTheChatsScreen() {
        var opened = 0
        render(onOpenChats = { opened++ })
        compose.onNodeWithText(context.getString(R.string.settings_chats_subtitle)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_chats").performClick()
        assertEquals(1, opened)
        compose.onNodeWithTag("settings_content_filtering").assertDoesNotExist()
        compose.onNodeWithTag("settings_typing_indicators").assertDoesNotExist()
        compose.onNodeWithTag("settings_link_previews").assertDoesNotExist()
        compose.onNodeWithTag("settings_bt_download").assertDoesNotExist()
    }

    /** GitHub #44: Settings › Wi-Fi Aware, on by default, says what each position costs, and toggles the switch. */
    @Test
    fun theWifiAwareRowStatesTheTradeAndTogglesTheSwitch() {
        var toggled: Boolean? = null
        render(wifiAwareOn = true, onToggleWifiAware = { toggled = it })
        compose.onNodeWithTag("settings_wifi_aware").performScrollTo().assertIsOn()
        compose.onNodeWithText(context.getString(R.string.settings_wifi_aware_subtitle_on)).assertExists()
        compose.onNodeWithTag("settings_wifi_aware").performClick()
        assertEquals(false, toggled)
    }

    @Test
    fun theWifiAwareRowOffSaysBluetoothCarriesTheMesh() {
        render(wifiAwareOn = false)
        compose.onNodeWithTag("settings_wifi_aware").performScrollTo().assertIsOff()
        compose.onNodeWithText(context.getString(R.string.settings_wifi_aware_subtitle_off)).assertExists()
    }

    @Test
    fun theWifiAwareRowIsAbsentOnAPhoneWithoutIt() {
        render(wifiAware = false)
        compose.onNodeWithTag("settings_wifi_aware").assertDoesNotExist()
    }

    /** ADR 2026-09.wetm's seventh amendment: Settings › Watch off says how to turn it on, and toggles (its default: SettingsStoreTest). */
    @Test
    fun theWatchRowOffSaysHowToTurnItOnAndToggles() {
        var toggled: Boolean? = null
        render(onToggleWatch = { toggled = it })
        compose.onNodeWithTag("settings_watch").performScrollTo().assertIsOff()
        compose.onNodeWithText(context.getString(R.string.settings_watch_subtitle_off)).assertExists()
        compose.onNodeWithTag("settings_watch").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun theWatchRowOnSaysWhatTheWatchReads() {
        render(watchOn = true)
        compose.onNodeWithTag("settings_watch").performScrollTo().assertIsOn()
        compose.onNodeWithText(context.getString(R.string.settings_watch_subtitle_on)).assertExists()
    }

    @Test
    fun theWatchRowIsAbsentInABuildThatServesNoWatch() {
        render(showWatch = false)
        compose.onNodeWithTag("settings_watch").assertDoesNotExist()
    }

    @Test
    fun lowPowerModeIsNotOffered() {
        // LOW_POWER_OFFERED is false since GitHub #44: the row's code stays, the row does not show.
        render()
        compose.onNodeWithTag("settings_low_power").assertDoesNotExist()
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

    /**
     * A MeshCore board's name is never kept, so the row names it by its kind; it read "On · connecting…". Its battery is a
     * voltage with no percent and never "powered", which the row leaves to the LoRa screen rather than call "plugged in".
     */
    @Test
    fun theLoraRowNamesALiveMeshcoreBoardByItsKind() {
        val battery = BoardBattery(percent = null, voltage = 3.9f, powered = false)
        render(lora = LoraSummary(enabled = true, boundKind = BoardKind.MESHCORE, plane = LoraPlane.Live, battery = battery))
        compose.onNodeWithText("On · MeshCore · connected").performScrollTo().assertIsDisplayed()
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

    /** The Calls row (§3.14) opens the Calls screen, and says whose servers a call uses. */
    @Test
    fun theCallsRowOpensTheCallsScreen() {
        var opened = false
        render(onOpenCalls = { opened = true })
        compose.onNodeWithText(context.getString(R.string.settings_calls_summary_relays)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_calls").performClick()
        assertTrue(opened)
    }

    @Test
    fun theCallsRowNamesOwnServersAndRelayAll() {
        render(calls = CallsSummary(relayAll = true, ownStun = true, ownTurn = true))
        val servers = context.getString(R.string.settings_calls_summary_own_both)
        compose
            .onNodeWithText(context.getString(R.string.settings_calls_summary_relayed, servers))
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** A build with no calls (release) shows no row; an own TURN server needs no Internet-plane relay, so it stays. */
    @Test
    fun theCallsRowIsAbsentWithoutCalls() {
        render(showCalls = false)
        compose.onNodeWithTag("settings_calls").assertDoesNotExist()
    }

    @Test
    fun theCallsRowStaysWhenTheInternetPlaneIsDark() {
        render(showInternetRelays = false)
        compose.onNodeWithTag("settings_calls").performScrollTo().assertIsDisplayed()
    }

    /** The battery position, the one fact on Background running that can stop the mesh, is the row's line. */
    @Test
    fun theBackgroundRowCarriesTheBatteryPositionAndOpensItsPage() {
        var opened = 0
        render(battery = BackgroundBattery.Optimized, onOpenBackgroundRunning = { opened++ })
        compose
            .onNodeWithText(context.getString(R.string.settings_background_summary_optimized))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("settings_background").performClick()
        assertEquals(1, opened)
        // The rows themselves live on the page behind it (BackgroundRunningScreenContentTest).
        compose.onNodeWithTag("settings_battery").assertDoesNotExist()
    }

    @Test
    fun theBackgroundRowSaysARestrictedInstallStopsTheMesh() {
        render(battery = BackgroundBattery.Restricted)
        compose
            .onNodeWithText(context.getString(R.string.settings_background_summary_restricted))
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** Blocked users is a row under Privacy, beside the backup: a list you keep, not a menu item. */
    @Test
    fun theBlockedUsersRowOpensTheBlockedList() {
        var blocked = 0
        render(onOpenBlockedUsers = { blocked++ })
        compose.onNodeWithTag("settings_blocked").performScrollTo().performClick()
        assertEquals(1, blocked)
    }

    /** The starting point is a row in its own right, last on the screen: touched at onboarding and rarely after. */
    @Test
    fun theStartingPointRowOpensTheStartingPointScreen() {
        var opened = 0
        render(onOpenStartingPoint = { opened++ })
        compose.onNodeWithTag("settings_starting_point").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    /** Every group shows its heading where at least one of its rows does. */
    @Test
    fun eachGroupHeadingShowsOverItsRows() {
        render()
        listOf(
            R.string.settings_section_conversations,
            R.string.settings_section_connections,
            R.string.settings_section_privacy,
            R.string.settings_section_appearance,
            R.string.settings_section_this_phone,
        ).forEach { compose.onNodeWithText(context.getString(it)).performScrollTo().assertIsDisplayed() }
    }

    /** A group with none of its rows in this build or on this phone leaves no heading standing over nothing. */
    @Test
    fun aGroupWithNoRowsHasNoHeading() {
        render(wifiAware = false, showInternetRelays = false, showLoraRadio = false, showThemeMode = false, showDynamicColor = false)
        compose.onNodeWithText(context.getString(R.string.settings_section_connections)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.settings_section_appearance)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.settings_section_privacy)).performScrollTo().assertIsDisplayed()
    }

    /** What's new, About and the licenses list sit behind the overflow: none is a setting, so none is a row. */
    @Test
    fun theOverflowMenuOpensAboutAndTheLicenses() {
        var about = 0
        var licenses = 0
        render(onOpenAbout = { about++ }, onOpenLicenses = { licenses++ })

        compose.onNodeWithContentDescription(context.getString(R.string.chat_more_options)).performClick()
        compose.onNodeWithTag("settings_menu_blocked").assertDoesNotExist()
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

    /** The clone row (ADR 2026-09.ypcc) exists only while the flag is up, and its tap is "Sign out here". */
    @Test
    fun theCloneRowShowsOnTheFlagAndAsksToSignOut() {
        var signOuts = 0
        render(cloneVisible = true, onSignOut = { signOuts++ })
        compose.onNodeWithTag("settings_clone_row").assertIsDisplayed().performClick()
        assertEquals(1, signOuts)
    }

    @Test
    fun theCloneRowIsAbsentByDefault() {
        render()
        compose.onNodeWithTag("settings_clone_row").assertDoesNotExist()
    }
}
