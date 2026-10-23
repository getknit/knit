package app.getknit.knit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
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
 * A row's trailing part stays beside its content while the two share the width, and moves under it — at the start past
 * an indent, or at the end — once it would squeeze the content. The slots here are fixed-size boxes, so each case's
 * widths are exact rather than a font's.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
class AdaptiveRowTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        content: Dp,
        trailing: Dp,
        stackedAtEnd: Boolean = false,
    ) = renderWith(trailing, stackedAtEnd) { Box(Modifier.size(content, 40.dp).testTag("content")) }

    private fun renderWith(
        trailing: Dp,
        stackedAtEnd: Boolean = false,
        content: @Composable () -> Unit,
    ) = compose.setContent {
        KnitTheme {
            AdaptiveRow(
                modifier = Modifier.width(ROW),
                gap = GAP,
                stackedGap = STACKED_GAP,
                stackedAtEnd = stackedAtEnd,
                stackedIndent = INDENT,
                trailing = { Box(Modifier.size(trailing, 20.dp).testTag("trailing")) },
                content = content,
            )
        }
    }

    @Test
    fun aTrailingPartThatFitsSitsAtTheEndBesideTheContent() {
        render(content = 100.dp, trailing = 100.dp)

        val content = compose.onNodeWithTag("content").getUnclippedBoundsInRoot()
        val trailing = compose.onNodeWithTag("trailing").getUnclippedBoundsInRoot()
        assertEquals("at the row's end", ROW, trailing.right)
        assertEquals("centred on the content", content.top + 10.dp, trailing.top)
    }

    @Test
    fun onePastBothRulesMovesUnderTheContentPastTheIndent() {
        // 250 + 12 + 200 is past 300 on one line, and 212 is past two fifths of the row.
        render(content = 250.dp, trailing = 200.dp)

        val content = compose.onNodeWithTag("content").getUnclippedBoundsInRoot()
        val trailing = compose.onNodeWithTag("trailing").getUnclippedBoundsInRoot()
        assertEquals("under the content", content.bottom + STACKED_GAP, trailing.top)
        assertEquals("past the indent", INDENT, trailing.left)
    }

    @Test
    fun aStackedButtonKeepsTheEnd() {
        render(content = 250.dp, trailing = 200.dp, stackedAtEnd = true)

        val content = compose.onNodeWithTag("content").getUnclippedBoundsInRoot()
        val trailing = compose.onNodeWithTag("trailing").getUnclippedBoundsInRoot()
        assertEquals("under the content", content.bottom + STACKED_GAP, trailing.top)
        assertEquals("at the row's end", ROW, trailing.right)
    }

    @Test
    fun aParagraphReadsItsLongestWordNotItsWholeLength() {
        // A paragraph: 900 dp unwrapped, but its longest word only 80. With a 108 dp trailing part (120 with its gap, the
        // share exactly) it stays beside and wraps in what is left; read by its unwrapped width, it would stack.
        renderWith(trailing = 108.dp) { Paragraph(minWidth = 80.dp, maxWidth = 900.dp) }

        val content = compose.onNodeWithTag("content").getUnclippedBoundsInRoot()
        val trailing = compose.onNodeWithTag("trailing").getUnclippedBoundsInRoot()
        assertEquals("at the row's end", ROW, trailing.right)
        assertEquals("centred on the content", content.top + 10.dp, trailing.top)
        assertEquals("the paragraph takes what is left", ROW - GAP - 108.dp, content.right - content.left)
    }

    // The decision alone, each case on one side of exactly one rule (row 300, gap 12, share two fifths = 120).

    @Test
    fun bothOnOneLineStayBesideHoweverWideTheTrailingPart() {
        assertTrue(decide(contentMin = 90, contentMax = 90, trailing = 190))
        assertFalse("one pixel past the line, and past its share", decide(contentMin = 99, contentMax = 99, trailing = 190))
    }

    @Test
    fun aParagraphKeepsATrailingPartWithinItsShare() {
        assertTrue("108 + 12 is the share exactly", decide(contentMin = 80, contentMax = 900, trailing = 108))
        assertFalse("one past the share", decide(contentMin = 80, contentMax = 900, trailing = 109))
    }

    @Test
    fun aParagraphWhoseLongestWordWouldNotFitStacksThoughTheShareHolds() {
        assertTrue(decide(contentMin = 200, contentMax = 900, trailing = 88))
        assertFalse("its longest word needs more than the 200 left", decide(contentMin = 201, contentMax = 900, trailing = 88))
    }

    @Test
    fun anUnboundedRowKeepsItBeside() {
        assertTrue(sideBySide(rowWidth = null, trailingWidth = 900, contentMinWidth = 900, contentMaxWidth = 900, gapPx = 12, 0.4f))
    }

    private fun decide(
        contentMin: Int,
        contentMax: Int,
        trailing: Int,
    ) = sideBySide(
        rowWidth = 300,
        trailingWidth = trailing,
        contentMinWidth = contentMin,
        contentMaxWidth = contentMax,
        gapPx = 12,
        maxTrailingShare = 0.4f,
    )

    /** A stand-in for wrapping text: [minWidth] and [maxWidth] as its intrinsics, 40 dp tall at whatever width it gets. */
    @Composable
    private fun Paragraph(
        minWidth: Dp,
        maxWidth: Dp,
    ) {
        Layout(
            modifier = Modifier.testTag("content"),
            measurePolicy =
                object : MeasurePolicy {
                    override fun MeasureScope.measure(
                        measurables: List<Measurable>,
                        constraints: Constraints,
                    ) = layout(constraints.maxWidth, 40.dp.roundToPx()) {}

                    override fun IntrinsicMeasureScope.minIntrinsicWidth(
                        measurables: List<IntrinsicMeasurable>,
                        height: Int,
                    ) = minWidth.roundToPx()

                    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
                        measurables: List<IntrinsicMeasurable>,
                        height: Int,
                    ) = maxWidth.roundToPx()
                },
        )
    }

    private companion object {
        val ROW = 300.dp
        val GAP = 12.dp
        val STACKED_GAP = 4.dp
        val INDENT = 22.dp
    }
}
