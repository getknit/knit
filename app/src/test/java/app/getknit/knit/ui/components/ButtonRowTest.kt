package app.getknit.knit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Buttons share a row equally while each fits its share unwrapped, and stack at the full width once one does not. The
 * buttons here are fixed-size boxes, so a box's width is its unwrapped width and each case's numbers are exact.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
class ButtonRowTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun render(widths: List<Dp>) =
        compose.setContent {
            KnitTheme {
                ButtonRow(modifier = Modifier.width(ROW), gap = GAP) {
                    widths.forEachIndexed { i, w -> Box(Modifier.size(w, 40.dp).testTag("b$i")) }
                }
            }
        }

    private fun bounds(i: Int) = compose.onNodeWithTag("b$i").getUnclippedBoundsInRoot()

    @Test
    fun buttonsThatFitTheirShareSitSideBySideAtEqualWidths() {
        // A share is (300 - 12) / 2 = 144.
        render(listOf(144.dp, 60.dp))

        assertEquals(0.dp, bounds(0).left)
        assertEquals("each takes its share", 144.dp, bounds(0).right)
        assertEquals("past the gap", 156.dp, bounds(1).left)
        assertEquals("however narrow it is unwrapped", ROW, bounds(1).right)
        assertEquals("on one line", bounds(0).top, bounds(1).top)
    }

    @Test
    fun oneButtonPastItsShareStacksBothAtTheFullWidth() {
        render(listOf(145.dp, 60.dp))

        assertEquals("the first keeps the top", 0.dp, bounds(0).top)
        assertEquals("the second goes under it, past the gap", bounds(0).bottom + GAP, bounds(1).top)
        assertEquals("the full width", ROW, bounds(0).right - bounds(0).left)
        assertEquals("the full width", ROW, bounds(1).right - bounds(1).left)
    }

    @Test
    fun theShareIsTheRowLessItsGapsSplitEvenly() {
        assertEquals(144, slotWidth(rowWidth = 300, gapPx = 12, count = 2))
        assertEquals(92, slotWidth(rowWidth = 300, gapPx = 12, count = 3))
        assertEquals(300, slotWidth(rowWidth = 300, gapPx = 12, count = 1))
        assertEquals(0, slotWidth(rowWidth = 300, gapPx = 12, count = 0))
    }

    @Test
    fun theWidestButtonDecides() {
        assertTrue(buttonsFit(rowWidth = 300, gapPx = 12, widths = listOf(144, 144)))
        assertFalse("one pixel past the share", buttonsFit(rowWidth = 300, gapPx = 12, widths = listOf(60, 145)))
    }

    @Test
    fun anUnboundedRowKeepsThemSideBySide() {
        assertTrue(buttonsFit(rowWidth = null, gapPx = 12, widths = listOf(900, 900)))
    }

    private companion object {
        val ROW = 300.dp
        val GAP = 12.dp
    }
}
