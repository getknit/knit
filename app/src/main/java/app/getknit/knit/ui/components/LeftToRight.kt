package app.getknit.knit.ui.components

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection

/**
 * Text that reads left to right in every layout: a safety number, a recovery key, a stack trace, a log line, a
 * measured value, a count against a limit. In a right-to-left layout a paragraph's direction comes from its first
 * strong character, and a run that has none (digits, spaces, punctuation) takes the layout's, so "12 / 40" read as
 * "40 / 12" and a safety number's groups came out last first. These are read and compared as written, not as prose.
 *
 * It is also aligned left, as code is in a right-to-left interface, over the theme's default of the layout's start.
 * Where the block itself belongs at the layout's end, align its box rather than the text; an explicit `TextAlign`
 * (a centred safety number) still applies.
 */
fun TextStyle.leftToRight(): TextStyle = copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left)
