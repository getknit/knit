package app.getknit.knit.ui.lora

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The LoRa radio screen's stateless content, rendered on Robolectric: the two empty states, the show-all
 * toggle that only appears when the board filter hid something, and the channel verdict a connected board
 * earns. Mirrors `InternetRelayScreenContentTest`; the content scrolls, so the lower rows are scrolled to first.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoraRadioScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        state: LoraRadioUiState,
        onToggle: (Boolean) -> Unit = {},
        onShowAllBoards: (Boolean) -> Unit = {},
        onToggleBridge: (Boolean) -> Unit = {},
        onToggleRoom: (Boolean) -> Unit = {},
        onAskSetup: () -> Unit = {},
        onSetUp: () -> Unit = {},
        onRestore: () -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                LoraRadioScreenContent(
                    state = state,
                    onBack = {},
                    onToggle = onToggle,
                    onToggleBridge = onToggleBridge,
                    onToggleRoom = onToggleRoom,
                    onShowAllBoards = onShowAllBoards,
                    onAskSetup = onAskSetup,
                    onSetUp = onSetUp,
                    onRestore = onRestore,
                )
            }
        }
    }

    private fun connected(
        channelName: String? = "Knit",
        boardSetUp: Boolean = true,
    ) = LoraRadioUiState(
        enabled = true,
        boardName = "Meshtastic_1a2b",
        boardAddress = "AA:BB:CC:DD:EE:FF",
        channel = 1,
        connection = LoraConnState.Ready,
        boardNodeNum = "!0000002a",
        snr = 6.5f,
        rssi = -85,
        heard = 2,
        boardsHeard = 1,
        firmware = "2.5.0",
        channelName = channelName,
        boardSetUp = boardSetUp,
        boards = listOf(BoardOption("AA:BB:CC:DD:EE:FF", "Meshtastic_1a2b", selected = true)),
        anyBonded = true,
    )

    @Test
    fun theSwitchReflectsTheStoredSettingAndReportsATap() {
        var toggled: Boolean? = null
        render(LoraRadioUiState(enabled = false), onToggle = { toggled = it })
        compose.onNodeWithTag("lora_switch").assertIsOff()
        compose.onNodeWithTag("lora_switch").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun noPairedDeviceAtAllAsksToPairOne() {
        render(LoraRadioUiState(enabled = true, anyBonded = false))
        compose.onNodeWithText("No paired Meshtastic boards found", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_show_all_boards").assertDoesNotExist()
    }

    @Test
    fun onlyNonBoardsPairedSaysSoAndOffersToShowThem() {
        var showAll: Boolean? = null
        render(LoraRadioUiState(enabled = true, anyBonded = true, hiddenBoards = 2), onShowAllBoards = { showAll = it })
        compose.onNodeWithTag("lora_board_none_meshtastic").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 other paired devices hidden").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_show_all_boards").assertIsOff()
        compose.onNodeWithTag("lora_show_all_boards").performClick()
        assertEquals(true, showAll)
    }

    @Test
    fun theShowAllToggleStaysAwayWhenNothingIsHidden() {
        render(connected())
        compose.onNodeWithTag("lora_board_AA:BB:CC:DD:EE:FF").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_show_all_boards").assertDoesNotExist()
    }

    @Test
    fun theShowAllToggleStaysWhileItIsOn() {
        // Everything revealed means nothing is hidden any more — the toggle must not vanish under the finger.
        render(connected().copy(hiddenBoards = 0, showAllBoards = true))
        compose.onNodeWithTag("lora_show_all_boards").assertIsOn()
    }

    @Test
    fun aConnectedBoardNamesItsFirmwareAndPeers() {
        render(connected())
        compose.onNodeWithText("Firmware 2.5.0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 other radio in range").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 people reachable over LoRa").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aBoardStillConnectingIsNotOfferedTheSetup() {
        render(LoraRadioUiState(enabled = true, boardAddress = "AA:BB:CC:DD:EE:FF", connection = LoraConnState.Connecting))
        compose.onNodeWithTag("lora_setup").assertDoesNotExist()
        compose.onNodeWithTag("lora_peers_heard").assertDoesNotExist()
        compose.onNodeWithTag("lora_boards_heard").assertDoesNotExist()
    }

    @Test
    fun aConnectedBoardShowsItsBattery() {
        render(connected().copy(battery = BoardBattery(percent = 78, voltage = 3.92f, powered = false)))
        compose.onNodeWithTag("lora_battery").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Battery 78% · 3.92 V").assertIsDisplayed()
    }

    @Test
    fun aPluggedInBoardSaysSo() {
        render(connected().copy(battery = BoardBattery(percent = null, voltage = 4.1f, powered = true)))
        compose.onNodeWithText("Plugged in · 4.10 V").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun noBatteryReadingMeansNoBatteryLine() {
        render(connected())
        compose.onNodeWithTag("lora_battery").assertDoesNotExist()
    }

    @Test
    fun theBridgeSwitchReflectsTheStoredSettingAndReportsATap() {
        var toggled: Boolean? = null
        render(connected().copy(bridgeEnabled = false), onToggleBridge = { toggled = it })
        compose.onNodeWithTag("lora_bridge_switch").performScrollTo().assertIsOff()
        compose.onNodeWithTag("lora_bridge_switch").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun theRoomSwitchReflectsTheStoredSettingAndReportsATap() {
        var toggled: Boolean? = null
        render(connected().copy(roomEnabled = false), onToggleRoom = { toggled = it })
        compose.onNodeWithTag("lora_room_switch").performScrollTo().assertIsOff()
        compose.onNodeWithTag("lora_room_switch").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun theAirtimeLedgerShowsOnceTheBoardIsConnected() {
        render(connected().copy(airtimePercent = 42))
        compose.onNodeWithTag("lora_airtime").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("42%", substring = true).assertIsDisplayed()
    }

    @Test
    fun aSpareBoardSaysItIsListeningRatherThanLookingBroken() {
        render(connected().copy(bridgePassive = true))
        compose.onNodeWithTag("lora_role_passive").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theActiveGatewaySaysNothingAboutItsRole() {
        render(connected())
        compose.onNodeWithTag("lora_role_passive").assertDoesNotExist()
    }

    @Test
    fun theStatusSeparatesRadiosInRangeFromPeopleReachableThroughThem() {
        // One board relaying two other authors' frames is one radio, not three — the field report.
        render(connected().copy(boardsHeard = 1, heard = 3))
        compose.onNodeWithTag("lora_boards_heard").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 other radio in range").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3 people reachable over LoRa").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun thePeopleLineStaysAwayWhenItWouldOnlyRestateTheRadioCount() {
        render(connected().copy(boardsHeard = 1, heard = 1))
        compose.onNodeWithTag("lora_boards_heard").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_peers_heard").assertDoesNotExist()
    }

    @Test
    fun aBoardThatIsNotSetUpOffersTheOneSetupButtonAndOnlyAsks() {
        var asked = 0
        var setUp = 0
        render(connected(boardSetUp = false), onAskSetup = { asked++ }, onSetUp = { setUp++ })
        compose
            .onNodeWithTag("lora_setup")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, asked)
        assertEquals("the button asks; the dialog acts", 0, setUp)
        compose.onNodeWithTag("lora_restore").assertDoesNotExist()
    }

    @Test
    fun theConfirmationSpellsOutTheCostAndIsWhatSetsTheBoardUp() {
        var setUp = 0
        render(connected(boardSetUp = false).copy(confirmSetup = true), onSetUp = { setUp++ })
        compose.onNodeWithText("stops relaying other radios' traffic", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("lora_setup_confirm").performClick()
        assertEquals(1, setUp)
    }

    @Test
    fun aSetUpBoardSaysSoAndOffersOnlyTheWayBack() {
        var restored = 0
        render(connected(), onRestore = { restored++ })
        compose.onNodeWithTag("lora_setup").assertDoesNotExist()
        compose.onNodeWithText("sharing the public Meshtastic frequency", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_restore").performScrollTo().performClick()
        assertEquals(1, restored)
    }

    @Test
    fun aBoardStillUnderItsOldNameIsOfferedTheRename() {
        var setUp = 0
        render(connected().copy(needsRename = true, meshName = "Meshtastic 002a", knitName = "Knit 002a"), onSetUp = { setUp++ })
        compose.onNodeWithText("still called \u201CMeshtastic 002a\u201D", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_rename").performScrollTo().performClick()
        assertEquals(1, setUp)
    }

    @Test
    fun aBoardMissingOnlyTheUnmonitoredMarkIsOfferedThatInstead() {
        // Same one `set_owner`, but the board is already named — so calling it a rename would be a lie
        // (ADR 2026-09.emd7). The names matching is how the screen tells the two halves apart.
        var setUp = 0
        render(connected().copy(needsRename = true, meshName = "Knit 002a", knitName = "Knit 002a"), onSetUp = { setUp++ })
        compose.onNodeWithText("the mesh isn't told nobody reads it", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_rename").performScrollTo().performClick()
        assertEquals(1, setUp)
    }

    @Test
    fun anUnconnectedBoardIsNeverOfferedTheSetupStep() {
        render(LoraRadioUiState(enabled = true, anyBonded = true))
        compose.onNodeWithTag("lora_setup").assertDoesNotExist()
        compose.onNodeWithTag("lora_restore").assertDoesNotExist()
    }
}
