package app.getknit.knit.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The choice control keeps a segmented row while every label fits its slice of the width, and becomes a list of radio
 * buttons once one does not — the layout a longer translation needs, rather than a label cut at the segment's edge.
 *
 * The window is 400 dp at density 1 (Robolectric's default is 320 dp), so the 360 dp row is what it says. Its slice
 * for a label is 44 dp beside the centring slot and 70 dp without it ([segmentLabelWidth]); at labelLarge, "On",
 * "Off" and "Auto" fit the first, and "System" (about 49 dp) only the second.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-mdpi")
class ChoiceButtonsTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        labels: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit = {},
    ) = compose.setContent {
        KnitTheme {
            ChoiceButtons(
                choices = labels.mapIndexed { i, label -> i to label },
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.width(ROW),
                tagOf = { "choice_$it" },
            )
        }
    }

    private fun assertSideBySide() {
        val first = compose.onNodeWithTag("choice_0").getUnclippedBoundsInRoot()
        val last = compose.onNodeWithTag("choice_2").getUnclippedBoundsInRoot()
        assertEquals("segments sit side by side", first.top, last.top)
        assertTrue("the last segment is right of the first", last.left > first.right)
    }

    @Test
    fun labelsThatFitBesideTheCentringSlotShareOneRow() {
        render(listOf("On", "Off", "Auto"), selected = 0)
        assertSideBySide()
    }

    @Test
    fun labelsThatFitOnlyWithoutTheCentringSlotKeepTheRow() {
        render(listOf("System", "Light", "Dark"), selected = 0)
        assertSideBySide()
    }

    @Test
    fun theRowSelectsAndReportsAClick() {
        var picked = -1
        render(listOf("System", "Light", "Dark"), selected = 0) { picked = it }

        compose.onNodeWithTag("choice_0").assertIsSelected()
        compose.onNodeWithTag("choice_2").assertIsNotSelected().performClick()
        assertEquals(2, picked)
    }

    @Test
    fun aLabelTooLongForItsSegmentStacksEveryChoiceAtFullWidth() {
        render(listOf("Never", "Datenschutzeinstellungen", "Always"), selected = 1)

        val first = compose.onNodeWithTag("choice_0").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("choice_1").getUnclippedBoundsInRoot()
        assertTrue("the choices stack, one under another", second.top >= first.bottom)
        assertEquals("each starts at the row's start", first.left, second.left)
        assertEquals("each runs to the row's end", first.right, second.right)
        assertEquals("the row is the full width", ROW, first.right - first.left)
    }

    @Test
    fun theListSelectsAndReportsAClick() {
        var picked = -1
        render(listOf("Never", "Datenschutzeinstellungen", "Always"), selected = 1) { picked = it }

        compose.onNodeWithTag("choice_1").assertIsSelected()
        compose.onNodeWithTag("choice_0").assertIsNotSelected().performClick()
        assertEquals(0, picked)
    }

    // Each case below breaks exactly one of fitsSegment's two rules, at a width taken from the measurer itself, and
    // asserts the line count it relies on — so neither rule is tested through the other, and a font change can't make
    // a case pass for the wrong reason.

    @Test
    fun aLabelOnTwoLinesWithEveryWordWholeFits() {
        val width = wordWidth("Over 1")
        assertEquals(2, lines("Over 1 MB", width))
        assertTrue(fitsSegment(measurer, "Over 1 MB", style, width))
    }

    @Test
    fun aWordWiderThanTheSegmentDoesNotFitEvenOnTwoLines() {
        val word = "Datenschutzeinstellungen"
        val width = wordWidth(word) - 1
        assertTrue("the line rule alone would pass it", lines(word, width) <= 2)
        assertFalse(fitsSegment(measurer, word, style, width))
    }

    @Test
    fun aLabelPastTwoLinesDoesNotFitThoughEveryWordDoes() {
        val label = "Ask before anything over one"
        val width = label.split(' ').maxOf { wordWidth(it) }
        assertTrue("the word rule alone would pass it", lines(label, width) > 2)
        assertFalse(fitsSegment(measurer, label, style, width))
    }

    @Test
    fun noRoomDoesNotFit() {
        assertFalse(fitsSegment(measurer, "Dark", style, widthPx = 0))
    }

    @Test
    fun aSegmentLosesItsPaddingTheCheckAndTheCentringSlot() {
        // A third of 360 dp, less 12 dp of padding a side, the check's 26 dp slot, and the 26 dp that centres the label.
        assertEquals(44.dp, segmentLabelWidth(ROW, count = 3, check = true, centred = true))
        assertEquals(70.dp, segmentLabelWidth(ROW, count = 3, check = true, centred = false))
        assertEquals(96.dp, segmentLabelWidth(ROW, count = 3, check = false, centred = false))
    }

    private val style = TextStyle(fontSize = 14.sp)

    private val measurer =
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
            defaultDensity = Density(1f),
            defaultLayoutDirection = LayoutDirection.Ltr,
        )

    private fun wordWidth(text: String) = measurer.measure(text, style, softWrap = false).size.width

    private fun lines(
        text: String,
        width: Int,
    ) = measurer.measure(text, style, constraints = Constraints(maxWidth = width)).lineCount

    private companion object {
        val ROW = 360.dp
    }
}
