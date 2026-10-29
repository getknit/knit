package app.getknit.knit.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.settings.BluetoothDownloadLimit
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Settings › Chats: typing indicators, link previews, content filtering and the large-download limit. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatSettingsScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    // The switches default to the form's own defaults, so a test named "by default" goes red when those change.
    private fun render(
        typingIndicatorsEnabled: Boolean = ChatSettingsForm().typingIndicatorsEnabled,
        onToggleTypingIndicators: (Boolean) -> Unit = {},
        linkPreviewsEnabled: Boolean = ChatSettingsForm().linkPreviewsEnabled,
        onToggleLinkPreviews: (Boolean) -> Unit = {},
        showLinkPreviews: Boolean = true,
        contentFilteringEnabled: Boolean = ChatSettingsForm().contentFilteringEnabled,
        onToggleContentFiltering: (Boolean) -> Unit = {},
        bluetoothDownloadLimit: BluetoothDownloadLimit = ChatSettingsForm().bluetoothDownloadLimit,
        onSelectBluetoothDownloadLimit: (BluetoothDownloadLimit) -> Unit = {},
        wifiAware: Boolean = true,
    ) {
        compose.setContent {
            KnitTheme {
                ChatSettingsScreenContent(
                    form =
                        ChatSettingsForm(
                            typingIndicatorsEnabled = typingIndicatorsEnabled,
                            linkPreviewsEnabled = linkPreviewsEnabled,
                            contentFilteringEnabled = contentFilteringEnabled,
                            bluetoothDownloadLimit = bluetoothDownloadLimit,
                        ),
                    onBack = {},
                    onToggleTypingIndicators = onToggleTypingIndicators,
                    onToggleLinkPreviews = onToggleLinkPreviews,
                    onToggleContentFiltering = onToggleContentFiltering,
                    onSelectBluetoothDownloadLimit = onSelectBluetoothDownloadLimit,
                    showLinkPreviews = showLinkPreviews,
                    wifiAware = wifiAware,
                )
            }
        }
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
    fun theLargeDownloadRowStartsAtOneMegabyteAndReportsAPick() {
        // ADR 2026-10.js2p: the default asks above 1 MB; Never turns the card off.
        var picked: BluetoothDownloadLimit? = null
        render(onSelectBluetoothDownloadLimit = { picked = it })
        compose.onNodeWithTag("settings_bt_download").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Over 1 MB").assertIsSelected()
        compose.onNodeWithText("Never").performClick()
        assertEquals(BluetoothDownloadLimit.NEVER, picked)
    }

    /** A phone without Wi-Fi Aware is never told the plane fetches anything on its own (the Moto G trial). */
    @Test
    fun theLargeDownloadRowNamesWifiAwareOnlyWhereThePhoneHasIt() {
        render(wifiAware = false)
        compose.onNodeWithTag("settings_bt_download").performScrollTo()
        compose.onNodeWithText("Wi-Fi Aware", substring = true).assertDoesNotExist()
        compose.onNodeWithText("before Bluetooth brings it", substring = true).assertExists()
    }

    @Test
    fun theLargeDownloadRowNamesWifiAwareWhereThePhoneHasIt() {
        render(wifiAware = true)
        compose.onNodeWithTag("settings_bt_download").performScrollTo()
        compose.onNodeWithText("over Wi-Fi Aware", substring = true).assertExists()
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

    /** On by default, and in every build: the radios carry typing cues whether or not the Internet plane is there. */
    @Test
    fun theTypingIndicatorsRowIsOnByDefaultAndTogglesOff() {
        var toggled: Boolean? = null
        render(showLinkPreviews = false, onToggleTypingIndicators = { toggled = it })
        compose.onNodeWithTag("settings_typing_indicators").performScrollTo().assertIsOn()
        compose.onNodeWithTag("settings_typing_indicators").performClick()
        assertEquals(false, toggled)
    }

    @Test
    fun theTypingIndicatorsRowTogglesBackOn() {
        var toggled: Boolean? = null
        render(typingIndicatorsEnabled = false, onToggleTypingIndicators = { toggled = it })
        compose.onNodeWithTag("settings_typing_indicators").performScrollTo().assertIsOff()
        compose.onNodeWithTag("settings_typing_indicators").performClick()
        assertEquals(true, toggled)
    }

    /** Link previews ship under the Internet plane's build switch, as the relay row on Settings does. */
    @Test
    fun theLinkPreviewsRowFollowsTheInternetPlaneSwitch() {
        render(showLinkPreviews = false)
        compose.onNodeWithTag("settings_link_previews").assertDoesNotExist()
    }
}
