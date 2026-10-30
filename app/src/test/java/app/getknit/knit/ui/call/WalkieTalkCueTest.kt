package app.getknit.knit.ui.call

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import app.getknit.knit.call.TalkCue
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * How a walkie burst spoken ends, as the speaker feels it (docs/VOICE_CALLS.md C-3.11-5): a tick as the button is let
 * go, a firmer buzz when the burst ran to its limit with the button still held, and a status line that says the limit
 * stopped it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp")
class WalkieTalkCueTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val felt = mutableListOf<HapticFeedbackType>()
    private val haptics =
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                felt += hapticFeedbackType
            }
        }
    private var talk by mutableStateOf<Talk>(Talk.Recording(elapsedMs = 12_000, level = 0.4f))

    private fun render(first: Talk) {
        talk = first
        compose.setContent {
            KnitTheme {
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    CallScreen(
                        state =
                            CallState(
                                id = "c1",
                                peerId = "hzwyqdbj74hu6gkurmcyxomaca",
                                outgoing = true,
                                phase = CallPhase.Connected,
                                startedAt = 0,
                                connectedAt = 0,
                                walkie = true,
                            ),
                        name = "Ada",
                        avatarHash = null,
                        route = null,
                        stats = null,
                        onAnswer = {},
                        onDecline = {},
                        onHangUp = {},
                        onMute = {},
                        onRoute = {},
                        clock = { 0L },
                        talk = talk,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun step(next: Talk) {
        compose.runOnIdle { talk = next }
        compose.waitForIdle()
    }

    @Test
    fun lettingGoTicks() {
        render(Talk.Recording(elapsedMs = 12_000, level = 0.4f))
        step(Talk.Sending(TalkCue.RELEASED))
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.Confirm), felt) }
    }

    @Test
    fun aBurstStoppedAtItsLimitBuzzesAndSaysWhy() {
        render(Talk.Recording(elapsedMs = 29_940, level = 0.4f))
        step(Talk.Sending(TalkCue.CAPPED))
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.Reject), felt) }

        step(Talk.Idle(Talk.Note.CAPPED))
        compose.onNodeWithText("Sent at the 30-second limit. Hold again to keep talking.").assertIsDisplayed()
        compose.runOnIdle { assertEquals("the note adds no second buzz", listOf(HapticFeedbackType.Reject), felt) }
    }

    // A call screen opened, or drawn again, while a burst is already sending felt its end before; it does not buzz twice.
    @Test
    fun aScreenDrawnMidSendDoesNotBuzz() {
        render(Talk.Sending(TalkCue.CAPPED))
        step(Talk.Idle(Talk.Note.SENT))
        compose.runOnIdle { assertEquals(emptyList<HapticFeedbackType>(), felt) }
    }
}
