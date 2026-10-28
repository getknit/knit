package app.getknit.knit.ui.call

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The walkie's talk button (docs/VOICE_CALLS.md §3.11): a press starts a burst, the lift sends it wherever the finger
 * has wandered meanwhile, and only a gesture the system cancels drops it. A thumb drifting off the button over a long
 * burst used to end it mid-sentence (2026-10-27 field test).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp")
class WalkieTalkButtonTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private var talks = 0
    private var sends = 0
    private var cancels = 0

    private fun render() {
        compose.setContent {
            KnitTheme {
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
                    onTalk = { talks++ },
                    onTalkSend = { sends++ },
                    onTalkCancel = { cancels++ },
                )
            }
        }
    }

    private val button get() = compose.onNodeWithContentDescription("Hold to talk")

    @Test
    fun aLiftOnTheButtonSendsTheBurst() {
        render()
        button.performTouchInput {
            down(center)
            up()
        }
        compose.runOnIdle {
            assertEquals(1, talks)
            assertEquals(1, sends)
            assertEquals(0, cancels)
        }
    }

    @Test
    fun aFingerThatDriftsOffTheButtonStillSendsAtItsLift() {
        render()
        button.performTouchInput {
            down(center)
            // Well past the button's edge, as a thumb wanders over a long burst.
            moveBy(Offset(width * 1.5f, -height * 1.5f))
        }
        compose.runOnIdle {
            assertEquals(1, talks)
            assertEquals(0, sends)
            assertEquals(0, cancels)
        }
        button.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, sends)
            assertEquals(0, cancels)
        }
    }

    @Test
    fun aGestureTheSystemCancelsDropsTheBurst() {
        render()
        button.performTouchInput {
            down(center)
            cancel()
        }
        compose.runOnIdle {
            assertEquals(1, talks)
            assertEquals(0, sends)
            assertEquals(1, cancels)
        }
    }
}
