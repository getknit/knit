package app.getknit.knit.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.relay.AttachmentWait
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.util.Base64

/**
 * A flagged video's poster in the chat (ADR 2026-11.dgvf): drawn behind the notice only where a blur hides it.
 * `Modifier.blur` is ignored before Android 12, and there the flagged frame showed in the clear.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoBubbleCoverTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        flagged: Boolean,
        blurCovers: Boolean,
    ) {
        compose.setContent {
            KnitTheme {
                VideoBubble(
                    hash = "h",
                    poster = POSTER,
                    durationMs = 9_000L,
                    width = 480,
                    height = 270,
                    ready = true,
                    flagged = flagged,
                    awaitsTap = false,
                    declaredSize = null,
                    wait = AttachmentWait.Nearby,
                    arrival = null,
                    onPlay = {},
                    onDownload = {},
                    onLongClick = {},
                    blurCovers = blurCovers,
                )
            }
        }
    }

    @Test
    fun aFlaggedPosterIsNotDrawnWhereNoBlurCanHideIt() {
        render(flagged = true, blurCovers = false)
        compose.onNodeWithTag("chat_video_poster", useUnmergedTree = true).assertDoesNotExist()

        // The tap that reveals it draws it.
        compose.onNodeWithTag("chat_video_bubble").performClick()
        compose.onNodeWithTag("chat_video_poster", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aFlaggedPosterIsDrawnBlurredWhereTheBlurHidesIt() {
        render(flagged = true, blurCovers = true)
        compose.onNodeWithTag("chat_video_poster", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aCleanPosterIsAlwaysDrawn() {
        render(flagged = false, blurCovers = false)
        compose.onNodeWithTag("chat_video_poster", useUnmergedTree = true).assertExists()
    }

    private companion object {
        val POSTER: String = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
    }
}
