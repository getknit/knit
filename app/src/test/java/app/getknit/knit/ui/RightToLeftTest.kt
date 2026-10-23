package app.getknit.knit.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import app.getknit.knit.ui.chat.columnAt
import app.getknit.knit.ui.components.leftToRight
import app.getknit.knit.ui.theme.BaseTypography
import app.getknit.knit.ui.theme.knitTypography
import app.getknit.knit.ui.voice.seekFraction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a right-to-left layout changes by hand, where Compose does not mirror for us: a canvas's seek, a grid's hit
 * test, and the type scale's direction and default alignment.
 */
class RightToLeftTest {
    @Test
    fun aWaveformTapCountsFromTheLayoutsStart() {
        assertEquals(0.25f, seekFraction(x = 25f, width = 100f, rtl = false), 0f)
        assertEquals("a quarter from the left is three quarters in", 0.75f, seekFraction(x = 25f, width = 100f, rtl = true), 0f)
    }

    @Test
    fun aWaveformTapOutsideItIsClampedToItsEnds() {
        assertEquals(0f, seekFraction(x = -5f, width = 100f, rtl = false), 0f)
        assertEquals(1f, seekFraction(x = -5f, width = 100f, rtl = true), 0f)
        assertEquals(1f, seekFraction(x = 105f, width = 100f, rtl = false), 0f)
        assertEquals(0f, seekFraction(x = 105f, width = 100f, rtl = true), 0f)
    }

    @Test
    fun aWaveformWithNoWidthSeeksToTheStartEitherWay() {
        assertEquals(0f, seekFraction(x = 10f, width = 0f, rtl = false), 0f)
        assertEquals(0f, seekFraction(x = 10f, width = 0f, rtl = true), 0f)
    }

    @Test
    fun anEmojiTapCountsColumnsFromTheLayoutsStart() {
        // Nine columns of 10 px: the leftmost is the first left to right and the last right to left.
        assertEquals(0, columnAt(x = 5f, width = 90f, columns = 9, rtl = false))
        assertEquals(8, columnAt(x = 5f, width = 90f, columns = 9, rtl = true))
        assertEquals(2, columnAt(x = 25f, width = 90f, columns = 9, rtl = false))
        assertEquals(6, columnAt(x = 25f, width = 90f, columns = 9, rtl = true))
        assertEquals("a cell boundary belongs to the next cell", 1, columnAt(x = 10f, width = 90f, columns = 9, rtl = false))
        assertEquals(7, columnAt(x = 10f, width = 90f, columns = 9, rtl = true))
    }

    @Test
    fun anEmojiTapPastEitherEdgeStaysInTheEdgeColumn() {
        assertEquals(8, columnAt(x = 90f, width = 90f, columns = 9, rtl = false))
        assertEquals(0, columnAt(x = 90f, width = 90f, columns = 9, rtl = true))
        assertEquals(0, columnAt(x = -15f, width = 90f, columns = 9, rtl = false))
        assertEquals(8, columnAt(x = -15f, width = 90f, columns = 9, rtl = true))
    }

    @Test
    fun everyStyleTakesItsDirectionFromTheTextAndAlignsToTheLayoutsStart() {
        for ((direction, align) in listOf(LayoutDirection.Ltr to TextAlign.Left, LayoutDirection.Rtl to TextAlign.Right)) {
            val styles = knitTypography(direction).styles()
            val base = BaseTypography.styles()
            assertEquals(15, styles.size)
            styles.forEachIndexed { i, style ->
                assertEquals("style $i, $direction", TextDirection.Content, style.textDirection)
                assertEquals("style $i, $direction", align, style.textAlign)
                assertEquals(
                    "style $i keeps its own slot's size, weight and spacing",
                    base[i],
                    style.copy(textDirection = TextDirection.Unspecified, textAlign = TextAlign.Unspecified),
                )
            }
        }
    }

    // The fifteen styles of Material's scale, in its order. The `*Emphasized` ones are left out, as knitTypography
    // leaves them.
    private fun Typography.styles(): List<TextStyle> =
        listOf(
            displayLarge,
            displayMedium,
            displaySmall,
            headlineLarge,
            headlineMedium,
            headlineSmall,
            titleLarge,
            titleMedium,
            titleSmall,
            bodyLarge,
            bodyMedium,
            bodySmall,
            labelLarge,
            labelMedium,
            labelSmall,
        )

    @Test
    fun leftToRightTextOverridesBoth() {
        val style = knitTypography(LayoutDirection.Rtl).bodySmall.leftToRight()
        assertEquals(TextDirection.Ltr, style.textDirection)
        assertEquals(TextAlign.Left, style.textAlign)
    }
}
