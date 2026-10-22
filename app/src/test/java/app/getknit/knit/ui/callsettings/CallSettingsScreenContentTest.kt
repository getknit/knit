package app.getknit.knit.ui.callsettings

import android.content.Context
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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.call.OwnIceServers
import app.getknit.knit.call.ProbeResult
import app.getknit.knit.call.ProbeVerdict
import app.getknit.knit.call.SttModelState
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The Calls screen (§3.14): the switches, the fields behind them, Save and Test. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CallSettingsScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun render(
        state: CallSettingsUiState,
        live: Boolean = true,
        ownServers: Boolean = live,
        onToggleRelayAll: (Boolean) -> Unit = {},
        onToggleTurn: (Boolean) -> Unit = {},
        onTest: () -> Unit = {},
        transcripts: TranscriptsUi? = null,
        onToggleTranscripts: (Boolean) -> Unit = {},
        onDownloadSpeechModel: () -> Unit = {},
        onCancelSpeechModel: () -> Unit = {},
        onRemoveSpeechModel: () -> Unit = {},
        fullScreen: Boolean? = null,
        onOpenFullScreenSettings: () -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                CallSettingsScreenContent(
                    state = state,
                    onBack = {},
                    live = live,
                    ownServers = ownServers,
                    onToggleRelayAll = onToggleRelayAll,
                    onToggleTurn = onToggleTurn,
                    onTest = onTest,
                    transcripts = transcripts,
                    onToggleTranscripts = onToggleTranscripts,
                    onDownloadSpeechModel = onDownloadSpeechModel,
                    onCancelSpeechModel = onCancelSpeechModel,
                    onRemoveSpeechModel = onRemoveSpeechModel,
                    fullScreen = fullScreen,
                    onOpenFullScreenSettings = onOpenFullScreenSettings,
                )
            }
        }
    }

    /** Below Android 14 every call may ring full screen, so there is no line to draw. */
    @Test
    fun theFullScreenLineIsAbsentWhereThereIsNothingToAsk() {
        render(CallSettingsUiState())
        compose.onNodeWithTag("call_settings_full_screen").assertDoesNotExist()
    }

    @Test
    fun aCallThatCannotRingFullScreenOffersTheSystemPage() {
        var opened = 0
        render(CallSettingsUiState(), fullScreen = false, onOpenFullScreenSettings = { opened++ })
        compose.onNodeWithText(context.getString(R.string.call_settings_full_screen_off)).assertIsDisplayed()
        compose.onNodeWithTag("call_settings_full_screen_settings").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aCallThatCanRingFullScreenSaysSoWithNothingToPress() {
        render(CallSettingsUiState(), fullScreen = true)
        compose.onNodeWithText(context.getString(R.string.call_settings_full_screen_on)).assertIsDisplayed()
        compose.onNodeWithTag("call_settings_full_screen_settings").assertDoesNotExist()
    }

    @Test
    fun theFieldsHideWhileTheirSwitchIsOff() {
        var toggled: Boolean? = null
        render(CallSettingsUiState(), onToggleTurn = { toggled = it })
        compose.onNodeWithTag("call_settings_turn_switch").assertIsOff()
        compose.onNodeWithTag("call_settings_turn_url").assertDoesNotExist()
        compose.onNodeWithTag("call_settings_stun_field").assertDoesNotExist()
        compose.onNodeWithTag("call_settings_turn_switch").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun relayAllIsHere() {
        var toggled: Boolean? = null
        render(CallSettingsUiState(turnAvailable = true), onToggleRelayAll = { toggled = it })
        compose.onNodeWithTag("call_settings_relay_all").assertIsEnabled().performClick()
        assertEquals(true, toggled)
    }

    /** No TURN server to relay through (a free ticket's relay, say): the switch cannot be turned on, and says why. */
    @Test
    fun relayAllCannotBeTurnedOnWithoutTurn() {
        var toggled: Boolean? = null
        render(CallSettingsUiState(turnAvailable = false), onToggleRelayAll = { toggled = it })
        compose
            .onNodeWithTag("call_settings_relay_all")
            .assertIsOff()
            .assertIsNotEnabled()
            .performClick()
        compose.onNodeWithText(context.getString(R.string.settings_relay_calls_no_turn)).assertIsDisplayed()
        assertEquals(null, toggled)
    }

    /** On from before the TURN server went: it can still be turned off, and says calls cannot connect meanwhile. */
    @Test
    fun relayAllLeftOnWithoutTurnCanStillBeTurnedOff() {
        var toggled: Boolean? = null
        render(CallSettingsUiState(relayAll = true, turnAvailable = false), onToggleRelayAll = { toggled = it })
        compose.onNodeWithText(context.getString(R.string.settings_relay_calls_no_turn_on)).assertIsDisplayed()
        compose
            .onNodeWithTag("call_settings_relay_all")
            .assertIsOn()
            .assertIsEnabled()
            .performClick()
        assertEquals(false, toggled)
    }

    /** Switched on with nothing valid saved: the screen says the relays' server is used meanwhile. */
    @Test
    fun anOnSwitchWithNothingSavedSaysSo() {
        render(CallSettingsUiState(saved = OwnIceServers(turnOn = true)))
        compose.onNodeWithTag("call_settings_turn_url").performScrollTo()
        compose.onNodeWithText(context.getString(R.string.call_settings_waiting)).performScrollTo()
        compose.onNodeWithTag("call_settings_test").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("call_settings_save").assertIsNotEnabled()
    }

    @Test
    fun aMalformedTurnUrlIsNamedAndBlocksSave() {
        val saved = OwnIceServers(turnOn = true)
        render(
            CallSettingsUiState(saved = saved, draft = CallServerDraft(turnUrl = "turn:ada@t.example.org", turnUser = "a", turnPass = "b")),
        )
        compose.onNodeWithText(context.getString(R.string.call_settings_turn_url_invalid)).performScrollTo()
        compose.onNodeWithTag("call_settings_save").assertIsNotEnabled()
    }

    @Test
    fun aTestAnswerIsShownPerKind() {
        var tested = false
        val saved = OwnIceServers(stunOn = true, stunUrls = listOf("stun:s.example.org"))
        render(
            CallSettingsUiState(
                saved = saved,
                draft = CallServerDraft.of(saved),
                result = ProbeResult(stun = ProbeVerdict.Reached, turn = ProbeVerdict.NotTried),
            ),
            onTest = { tested = true },
        )
        val stunLine =
            context.getString(R.string.call_settings_result_stun, context.getString(R.string.call_settings_stun_reached))
        compose.onNodeWithText(stunLine).performScrollTo()
        compose
            .onNodeWithTag("call_settings_test")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(true, tested)
    }

    // A build with mesh calls and speech but no live calls (ADR 2026-10.swvp): the transcripts section alone, no relay,
    // server or Test row and no Save.
    @Test
    fun aBuildWithoutLiveCallsShowsTheTranscriptsAlone() {
        render(CallSettingsUiState(), live = false, transcripts = TranscriptsUi(on = true, bytes = 1_000_000))
        compose.onNodeWithTag("call_settings_transcripts").assertIsDisplayed()
        val liveRows = listOf("call_settings_relay_all", "call_settings_stun_switch", "call_settings_turn_switch", "call_settings_test")
        for (tag in liveRows + "call_settings_save") {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }

    // A release build with live calls (OWN_ICE_WIRED off, ADR 2026-10.mr88's amendment): "Relay all calls" stays, the
    // own servers' rows, their Test and Save go.
    @Test
    fun aBuildWithoutOwnServersKeepsTheRelayRowAlone() {
        render(CallSettingsUiState(), live = true, ownServers = false)
        compose.onNodeWithTag("call_settings_relay_all").assertIsDisplayed()
        val serverRows = listOf("call_settings_stun_switch", "call_settings_turn_switch", "call_settings_test")
        for (tag in serverRows + "call_settings_save") {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }

    // The walkie transcripts (docs/VOICE_CALLS.md C-3.11-9): their switch, and the speech model they need.
    @Test
    fun theTranscriptsSectionIsAbsentWithoutTheWalkie() {
        render(CallSettingsUiState())
        compose.onNodeWithTag("call_settings_transcripts").assertDoesNotExist()
        compose.onNodeWithTag("call_settings_speech_model").assertDoesNotExist()
    }

    @Test
    fun theTranscriptsSwitchWritesThrough() {
        var toggled: Boolean? = null
        render(CallSettingsUiState(), transcripts = TranscriptsUi(on = true, bytes = MODEL_BYTES), onToggleTranscripts = { toggled = it })
        compose
            .onNodeWithTag("call_settings_transcripts")
            .performScrollTo()
            .assertIsOn()
            .performClick()
        assertEquals(false, toggled)
    }

    @Test
    fun theSpeechModelRowOffersTheDownloadAndSaysItsCost() {
        var downloads = 0
        render(
            CallSettingsUiState(),
            transcripts = TranscriptsUi(on = true, model = SttModelState.Absent, bytes = MODEL_BYTES, metered = true),
            onDownloadSpeechModel = { downloads++ },
        )
        compose.onNodeWithText("142 MB", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.call_settings_speech_model_metered)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.call_settings_speech_model_download)).performScrollTo().performClick()
        assertEquals(1, downloads)
    }

    @Test
    fun aSpeechModelDownloadUnderWayCanBeCancelled() {
        var cancels = 0
        render(
            CallSettingsUiState(),
            transcripts = TranscriptsUi(on = true, model = SttModelState.Downloading(17_400_000, MODEL_BYTES), bytes = MODEL_BYTES),
            onCancelSpeechModel = { cancels++ },
        )
        compose.onNodeWithText("17 MB of 142 MB", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.call_settings_speech_model_cancel)).performScrollTo().performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun aFailedSpeechModelDownloadSaysWhyAndOffersAnotherTry() {
        var downloads = 0
        render(
            CallSettingsUiState(),
            transcripts = TranscriptsUi(on = true, model = SttModelState.Failed("No Internet connection"), bytes = MODEL_BYTES),
            onDownloadSpeechModel = { downloads++ },
        )
        compose.onNodeWithText("No Internet connection", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.call_settings_speech_model_retry)).performScrollTo().performClick()
        assertEquals(1, downloads)
    }

    /** Switched off, a model already on the phone can still be removed. */
    @Test
    fun anInstalledSpeechModelCanBeRemovedWithTranscriptsOff() {
        var removes = 0
        render(
            CallSettingsUiState(),
            transcripts = TranscriptsUi(on = false, model = SttModelState.Ready, bytes = MODEL_BYTES),
            onRemoveSpeechModel = { removes++ },
        )
        compose.onNodeWithTag("call_settings_transcripts").performScrollTo().assertIsOff()
        compose.onNodeWithText(context.getString(R.string.call_settings_speech_model_download)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.call_settings_speech_model_remove)).performScrollTo().performClick()
        assertEquals(1, removes)
    }

    private companion object {
        const val MODEL_BYTES = 142_315_154L
    }
}
