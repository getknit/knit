package app.getknit.knit.ui.theme

import androidx.compose.ui.graphics.compositeOver
import app.getknit.knit.ui.chat.CODE_WASH_ALPHA
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Monospace in a bubble sits on a wash of the bubble's own text colour (ADR 2026-10.p95v). The text on that wash
 * must still clear the small-text floor on both bubble fills in both themes, as the plain text does.
 */
class CodeWashContrastTest {
    @Test
    fun monospaceTextStaysReadableOnItsWashOnEveryBubble() {
        val bubbles =
            listOf(LightColorScheme, DarkColorScheme).flatMap { scheme ->
                listOf(
                    "mine" to (scheme.primaryContainer to scheme.onPrimaryContainer),
                    "theirs" to (scheme.surfaceVariant to scheme.onSurfaceVariant),
                )
            }
        for ((side, colours) in bubbles) {
            val (fill, text) = colours
            val wash = text.copy(alpha = CODE_WASH_ALPHA).compositeOver(fill)
            val ratio = contrastRatio(text, wash)
            assertTrue("$side bubble: $ratio on the wash", ratio >= MIN_TEXT_CONTRAST)
        }
    }
}
