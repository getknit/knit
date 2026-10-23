package app.getknit.knit.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/** Material's scale as Knit sets it, before [knitTypography] gives each style its direction and alignment. */
internal val BaseTypography =
    Typography(
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
    )

/**
 * Material's type scale for a layout running in [direction]: each paragraph takes its direction from its first strong
 * letter, and text that names no alignment sits at the layout's start.
 *
 * Compose otherwise lays every paragraph out in the layout's direction, so in an Arabic interface an English message
 * read right to left (its full stop and question mark jumped to the start of the line), and in an English one an
 * Arabic message read left to right. Deciding from the content is a `TextView`'s default. But `TextAlign.Start`
 * follows the paragraph, so a Latin name in a full-width cell would then sit at the far end of an Arabic row and a
 * column of values would break ranks: the default alignment is the layout's start instead (a view's `viewStart`), as
 * absolute left or right. An explicit `TextAlign` still follows the paragraph.
 *
 * A translated string that opens with a placeholder (a name) takes the name's direction, so an RTL translation wraps a
 * leading placeholder in an isolate. Text read as written whatever the language (a safety number, a key, a log line)
 * overrides the direction with `leftToRight()`.
 */
fun knitTypography(direction: LayoutDirection): Typography {
    val align = if (direction == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left

    fun TextStyle.bidi(): TextStyle = copy(textDirection = TextDirection.Content, textAlign = align)
    // Material 1.4's `*Emphasized` styles are left as they are: nothing here uses them yet.
    return BaseTypography.run {
        copy(
            displayLarge = displayLarge.bidi(),
            displayMedium = displayMedium.bidi(),
            displaySmall = displaySmall.bidi(),
            headlineLarge = headlineLarge.bidi(),
            headlineMedium = headlineMedium.bidi(),
            headlineSmall = headlineSmall.bidi(),
            titleLarge = titleLarge.bidi(),
            titleMedium = titleMedium.bidi(),
            titleSmall = titleSmall.bidi(),
            bodyLarge = bodyLarge.bidi(),
            bodyMedium = bodyMedium.bidi(),
            bodySmall = bodySmall.bidi(),
            labelLarge = labelLarge.bidi(),
            labelMedium = labelMedium.bidi(),
            labelSmall = labelSmall.bidi(),
        )
    }
}
