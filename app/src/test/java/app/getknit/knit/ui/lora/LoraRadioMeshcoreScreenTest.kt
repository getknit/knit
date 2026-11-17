package app.getknit.knit.ui.lora

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The radio screen's MeshCore rows and dialogs (ADR 2026-11.gsqu's Stage 7 amendment), rendered on Robolectric: the board's
 * own facts with none of Meshtastic's, the setup state shown while the board is away, the confirmation's erase and position
 * lines, the replace and Forget dialogs, and the wording the build and the board's firmware pick.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoraRadioMeshcoreScreenTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun render(state: LoraRadioUiState) {
        compose.setContent {
            KnitTheme {
                LoraRadioScreenContent(
                    state = state,
                    onBack = {},
                    onToggle = {},
                    onAskSetup = { calls += "askSetup" },
                    onSetUp = { calls += "setUp" },
                    onRestore = { calls += "restore" },
                    onReplace = { calls += "replace" },
                    onDismissReplace = { calls += "dismissReplace" },
                    onKeepBoard = { calls += "keep" },
                    onForgetAnyway = { calls += "forgetAnyway" },
                )
            }
        }
    }

    private val rows =
        MeshcoreRows(
            firmware = "v1.18.0",
            freqKhz = 869_618,
            spreadFactor = 8,
            bandwidthHz = 62_500,
            codingRate = 8,
            defaultScope = false,
            batteryMv = 4_012,
            sharesPosition = true,
            setup = MeshcoreSetup.SetUp,
        )

    private fun bound(
        connection: LoraConnState = LoraConnState.Ready,
        meshcore: MeshcoreRows = rows,
    ) = LoraRadioUiState(
        enabled = true,
        meshcoreBound = true,
        boardName = "MeshCore-0001",
        boardAddress = "C0:FF:EE:00:00:01",
        connection = connection,
        boardsHeard = 1,
        firmware = meshcore.firmware,
        boardSetUp = meshcore.setup == MeshcoreSetup.SetUp,
        boards = listOf(BoardOption("C0:FF:EE:00:00:01", "MeshCore-0001", selected = true)),
        anyBonded = true,
        anyKind = true,
        meshcore = meshcore,
    )

    @Test
    fun aReadyBoardShowsItsOwnRowsAndNoneOfMeshtastics() {
        render(bound())
        compose.onNodeWithTag("lora_meshcore_kind").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Radio 869.618 MHz · SF8 · BW 62.5 kHz · CR 4/8").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Default region scope: none").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Battery 4.01 V").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 Knit radio in range").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Set up for Knit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_restore").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_setup").assertDoesNotExist()
        compose.onNodeWithTag("lora_setup_dedicated").assertDoesNotExist()
        compose.onNodeWithTag("lora_peers_heard").assertDoesNotExist()
    }

    @Test
    fun anAwayBoardStillShowsItsSetupStateButOffersNothing() {
        render(bound(connection = LoraConnState.Reconnecting, meshcore = MeshcoreRows(setup = MeshcoreSetup.Pending)))
        compose
            .onNodeWithText("Knit's setup is still on this board. Knit cleans it up when the board next connects.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("lora_meshcore_kind").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_setup").assertDoesNotExist()
        compose.onNodeWithTag("lora_restore").assertDoesNotExist()
        compose.onNodeWithTag("lora_meshcore_radio").assertDoesNotExist()
    }

    @Test
    fun aBoardNotSetUpIsOfferedTheSetup() {
        render(bound(meshcore = rows.copy(setup = MeshcoreSetup.None)))
        compose.onNodeWithTag("lora_setup").performScrollTo().performClick()
        assertEquals(listOf("askSetup"), calls)
        compose.onNodeWithTag("lora_restore").assertDoesNotExist()
    }

    @Test
    fun theConfirmationSaysWhatIsErasedAndThatTheBoardMayShareWhereItIs() {
        render(
            bound(meshcore = rows.copy(setup = MeshcoreSetup.None, sharesPosition = null))
                .copy(confirmSetup = true, erase = MeshcoreErase(contacts = 12, slots = 2)),
        )
        compose.onNodeWithTag("lora_meshcore_setup_erase").assertIsDisplayed()
        compose.onNodeWithText("The board holds about 12 contacts.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("It has 2 other channels in use.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_meshcore_position").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_setup_confirm").performClick()
        assertEquals(listOf("setUp"), calls)
    }

    @Test
    fun aBoardKnownNotToShareWhereItIsGetsNoPositionLineAndNothingCountedGetsNoCount() {
        render(
            bound(meshcore = rows.copy(setup = MeshcoreSetup.None, sharesPosition = false))
                .copy(confirmSetup = true, erase = MeshcoreErase(contacts = 0, slots = null)),
        )
        compose.onNodeWithTag("lora_meshcore_setup_erase").assertIsDisplayed()
        compose.onNodeWithTag("lora_meshcore_position").assertDoesNotExist()
        compose.onNodeWithTag("lora_meshcore_erase_contacts").assertDoesNotExist()
        compose.onNodeWithTag("lora_meshcore_erase_channels").assertDoesNotExist()
    }

    @Test
    fun anotherBoardsRecordAsksBeforeTheReplace() {
        render(bound(meshcore = rows.copy(setup = MeshcoreSetup.None)).copy(confirmReplace = true))
        compose.onNodeWithTag("lora_meshcore_replace_dialog").assertIsDisplayed()
        compose.onNodeWithTag("lora_meshcore_replace_confirm").performClick()
        assertEquals(listOf("replace"), calls)
    }

    @Test
    fun aPendingForgetOffersKeepAndForgetAnyway() {
        render(bound(connection = LoraConnState.Reconnecting).copy(forgetPending = true))
        compose.onNodeWithTag("lora_meshcore_forget_dialog").assertIsDisplayed()
        compose.onNodeWithTag("lora_meshcore_forget_keep").performClick()
        compose.onNodeWithTag("lora_meshcore_forget_anyway").performClick()
        assertEquals(listOf("keep", "forgetAnyway"), calls)
    }

    @Test
    fun aForgetNoticeAndAMeshcoreRestoreAreWordedForMeshcore() {
        render(
            bound(meshcore = rows.copy(setup = MeshcoreSetup.None))
                .copy(forgetNotice = MeshcoreForgetNotice.Failed, provisionOutcome = LoraProvisionOutcome.Restored),
        )
        compose.onNodeWithText("Couldn't forget the board. Try again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lora_provision_outcome").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText(
                "Board restored to its own name and settings. The contacts and channels the setup erased stay gone. " +
                    "LoRa is switched off until you set a board up again.",
            ).assertIsDisplayed()
    }

    @Test
    fun theWrongFirmwareIsNamedForTheBoundKind() {
        render(bound(connection = LoraConnState.NeedsPairing).copy(wrongFirmware = true))
        compose
            .onNodeWithText(
                "Not running MeshCore companion firmware, or reflashed since it was paired. Forget it and pair it again, " +
                    "or pick another board.",
            ).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aMeshtasticBindingOnTheWrongFirmwareNamesMeshtastic() {
        render(
            LoraRadioUiState(
                enabled = true,
                boardAddress = "AA:BB:CC:DD:EE:FF",
                connection = LoraConnState.NeedsPairing,
                wrongFirmware = true,
            ),
        )
        compose
            .onNodeWithText(
                "Not running Meshtastic, or reflashed since it was paired. Forget it and pair it again, or pick another board.",
            ).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aBuildWithBothKindsNamesBothWhenNothingIsPaired() {
        render(LoraRadioUiState(enabled = true, anyBonded = false, anyKind = true))
        compose
            .onNodeWithText(
                "No paired LoRa boards found. Pair a Meshtastic or MeshCore board in Bluetooth settings first, with the PIN its " +
                    "screen shows or the one it was set to.",
            ).performScrollTo()
            .assertIsDisplayed()
    }
}
