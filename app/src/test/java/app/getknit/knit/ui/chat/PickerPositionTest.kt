package app.getknit.knit.ui.chat

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the long-press menu goes ([pickerPosition]): above the bubble, below it when that would clip the top, and
 * always back inside the window — a seven-row menu under a tall bubble near the top of a small phone must not push
 * Block and Delete off the bottom.
 */
class PickerPositionTest {
    private val window = IntSize(width = 720, height = 1_280)
    private val menu = IntSize(width = 600, height = 900)
    private val spacing = 16

    @Test
    fun aMenuThatFitsAboveTheBubbleSitsAboveIt() {
        val bubble = IntRect(left = 100, top = 1_000, right = 500, bottom = 1_200)

        assertEquals(IntOffset(0, 1_000 - 900 - spacing), pickerPosition(bubble, window, menu, spacing))
    }

    @Test
    fun aMenuThatJustFitsAboveTheBubbleStillSitsAboveIt() {
        val bubble = IntRect(left = 100, top = 900 + spacing, right = 500, bottom = 1_100)

        assertEquals(0, pickerPosition(bubble, window, menu, spacing).y)
    }

    @Test
    fun aMenuThatWouldClipTheTopDropsBelowTheBubble() {
        val bubble = IntRect(left = 100, top = 100, right = 500, bottom = 200)

        assertEquals(IntOffset(0, 200 + spacing), pickerPosition(bubble, window, menu, spacing))
    }

    @Test
    fun aMenuBelowATallBubbleIsPulledBackInsideTheBottomOfTheWindow() {
        // A tall message near the top: no room above, and below it the menu would end at 1_316, past the window.
        val bubble = IntRect(left = 100, top = 0, right = 500, bottom = 400)

        assertEquals(IntOffset(0, 1_280 - 900), pickerPosition(bubble, window, menu, spacing))
    }

    @Test
    fun aMenuTallerThanTheWindowKeepsItsTopOnScreen() {
        val bubble = IntRect(left = 100, top = 0, right = 500, bottom = 400)

        assertEquals(0, pickerPosition(bubble, window, IntSize(600, 1_500), spacing).y)
    }

    @Test
    fun theMenuIsCentredOverTheBubbleAndKeptInsideTheSides() {
        val narrow = IntSize(width = 200, height = 300)

        assertEquals(200, pickerPosition(IntRect(200, 800, 400, 900), window, narrow, spacing).x)
        assertEquals(720 - 200, pickerPosition(IntRect(650, 800, 720, 900), window, narrow, spacing).x)
    }
}
