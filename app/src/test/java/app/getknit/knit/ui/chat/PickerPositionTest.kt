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

    // menuPlacement: the in-window menu, which lifts the bubble rather than covering it (ADR 2026-11.pk9u).

    @Test
    fun theInWindowMenuLeavesTheBubbleWhereItIsWhenItFitsAboveOrBelow() {
        val high = IntRect(left = 100, top = 1_000, right = 500, bottom = 1_100)
        assertEquals(MenuPlacement(IntOffset(0, 1_000 - 900 - spacing), 0), menuPlacement(high, window, menu, spacing))
        val low = IntRect(left = 100, top = 100, right = 500, bottom = 200)
        assertEquals(MenuPlacement(IntOffset(0, 200 + spacing), 0), menuPlacement(low, window, menu, spacing))
    }

    @Test
    fun withNoRoomOnEitherSideTheBubbleIsLiftedTheShorterWay() {
        // A keyboard-height area: 900 + 16 + 100 fits in 1,100, but not beside the bubble where it sits.
        val area = IntSize(width = 720, height = 1_100)
        val upper = IntRect(left = 100, top = 400, right = 500, bottom = 500)
        // Down: bubble top to 916 = +516. Up: bubble bottom to 1,100 - 916 = 184, so -316.
        assertEquals(MenuPlacement(IntOffset(0, 1_100 - 900), 184 - 500), menuPlacement(upper, area, menu, spacing))
        val lower = IntRect(left = 100, top = 820, right = 500, bottom = 920)
        // Down: 916 - 820 = +96. Up: 184 - 920 = -736.
        assertEquals(MenuPlacement(IntOffset(0, 0), 916 - 820), menuPlacement(lower, area, menu, spacing))
    }

    @Test
    fun aBubbleAndMenuTallerThanTheAreaTogetherStayPutAndTheMenuIsClampedOnScreen() {
        val area = IntSize(width = 720, height = 1_000)
        val tall = IntRect(left = 100, top = 300, right = 500, bottom = 700)

        assertEquals(MenuPlacement(pickerPosition(tall, area, menu, spacing), 0), menuPlacement(tall, area, menu, spacing))
    }
}
